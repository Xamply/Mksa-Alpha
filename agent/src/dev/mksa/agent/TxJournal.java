package dev.mksa.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Journal de transacciones in-process (Tier 1 corte 5).
 *
 * <p>Estado explícito de cada {@code tx.tier1Disable}/{@code tx.tier1Enable}: el
 * receipt construido aquí es el <b>contrato de salida</b> de esos métodos y la
 * fuente única para la UI futura (barra de progreso, historial, debug, preview).
 * No es telemetría: cada paso tiene veredicto, métrica nativa y mensaje de error
 * si lo hubo. Sin ello, el envoltorio sería una caja negra que devuelve "ok" o no.
 *
 * <p>Decisiones durables fijadas en el plan del corte 5:
 * <ul>
 *   <li><b>Persistencia en memoria con cap 32.</b> No persiste cross-restart. La
 *       verdad para restaurar es el archivo MKSAR1 (corte 3), que sí sobrevive al
 *       agente; el receipt es metadata reconstruible.</li>
 *   <li><b>txId monótono por sesión</b> ({@link AtomicLong} desde 1). NO global
 *       cross-restart: la UI puede etiquetar como "sesión#3 tx#42" si lo necesita.</li>
 *   <li><b>Snapshot mide MUNDO</b> (bloques+chunks+items), no registro: recetas y
 *       tags se cuentan al filtrarlos (en {@code steps.dpReload.detail}).</li>
 *   <li><b>Best-effort + reporte</b>: si un paso falla tras otro OK, status=PARTIAL
 *       con {@code error} apuntando al paso roto; {@code rollback} queda null (el
 *       usuario llama {@code tier1Enable} si quiere revertir). Si falla antes de
 *       mutar nada, status=FAILED.</li>
 * </ul>
 *
 * <p>Thread-safety: {@link TxReceipt} es mutable durante la construcción, pero
 * solo el orquestador (un hilo) lo escribe; tras {@link #complete} pasa al journal
 * y debe tratarse como inmutable. La estructura del journal está sincronizada.
 *
 * <p>JSON: {@link TxReceipt#toMap()} devuelve el objeto plano que el IPC serializa
 * directo. El {@code epoch} sale como String (hex 16 chars) para no perder
 * precisión en {@code long} cruzando JSON.
 */
public final class TxJournal {

    public static final TxJournal INSTANCE = new TxJournal();

    /** Cap del journal en memoria. La UI futura puede mostrar más si persiste a disco. */
    private static final int HISTORY_CAP = 32;

    private final AtomicLong nextTxId = new AtomicLong(1);

    /** LinkedHashMap accedido bajo synchronized(this); insertion-order preservado. */
    private final LinkedHashMap<Long, TxReceipt> history = new LinkedHashMap<Long, TxReceipt>();

    private TxJournal() {}

    /** Genera un txId nuevo. Atómico, monótono creciente por sesión. */
    public long nextId() { return nextTxId.getAndIncrement(); }

    /** Inserta el receipt completado en el journal; evicciona el más viejo si supera cap. */
    public synchronized void complete(TxReceipt receipt) {
        if (receipt == null) return;
        history.put(receipt.txId, receipt);
        while (history.size() > HISTORY_CAP) {
            // LinkedHashMap.entrySet().iterator().next() = el más viejo en orden de inserción
            history.remove(history.keySet().iterator().next());
        }
    }

    /** Receipt por id, o null si ya fue evicted o nunca existió. */
    public synchronized TxReceipt get(long txId) {
        return history.get(txId);
    }

    /** Snapshot inmutable de todos los receipts vivos, en orden de inserción. */
    public synchronized List<TxReceipt> all() {
        return new ArrayList<TxReceipt>(history.values());
    }

    public synchronized int size() { return history.size(); }

    // ─────────────────────────────────────────────────────────────────────────
    // TxReceipt
    // ─────────────────────────────────────────────────────────────────────────

    /** Veredicto agregado de una transacción. */
    public enum Status { OK, PARTIAL, FAILED }

    /** Operación que produjo el receipt. */
    public enum Op { disable, enable }

    /**
     * Receipt explícito de una transacción Tier 1 in-process. Mutable durante la
     * construcción (el orquestador rellena snapshot, va appendiando steps, y al
     * final pone status/error/duración). Inmutable de hecho una vez en el journal.
     *
     * <p>Forma JSON estable (consumida por launcher/UI):
     * <pre>{@code
     * {
     *   "txId": 42,
     *   "op": "disable",
     *   "namespace": "mcwstairs",
     *   "status": "OK",
     *   "startedAtMs": 1718900000000,
     *   "durationMs": 612,
     *   "epoch": "681fea41a8b39c02",
     *   "snapshot": { "blocks": 7, "chunks": 1, "items": 0 },
     *   "restorePath": "…/MKSAR1-42.bin",
     *   "steps": [
     *     { "name": "vetoArm",  "status": "OK", "ms": 1,   "detail": { … } },
     *     { "name": "snapshot", "status": "OK", "ms": 12,  "detail": { … } },
     *     { "name": "sweep",    "status": "OK", "ms": 84,  "detail": { … } },
     *     { "name": "dpReload", "status": "OK", "ms": 485, "detail": { … } }
     *   ],
     *   "error": null,
     *   "rollback": null
     * }
     * }</pre>
     */
    public static final class TxReceipt {
        public final long txId;
        public final Op op;
        public final String namespace;
        public final long startedAtMs;
        public final String epoch;        // hex 16 chars, evita perder precisión en JSON
        public volatile Status status;    // se fija en finalize()
        public volatile long durationMs;  // se fija en finalize()
        public volatile String restorePath;  // promovido a nivel raíz para que enable lo halle
        public final Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        public final List<TxStep> steps = Collections.synchronizedList(new ArrayList<TxStep>());
        public volatile Map<String, Object> error;     // {step, class, message} o null
        public volatile Map<String, Object> rollback;  // {attempted, steps, status} o null

        public TxReceipt(long txId, Op op, String namespace, String epochHex) {
            this.txId = txId;
            this.op = op;
            this.namespace = namespace;
            this.startedAtMs = System.currentTimeMillis();
            this.epoch = epochHex;
        }

        /** Append un paso al receipt. El llamador debe haber medido ms ya. */
        public TxStep addStep(String name, Status status, long ms, Map<String, Object> detail) {
            TxStep s = new TxStep(name, status, ms, detail);
            steps.add(s);
            return s;
        }

        /**
         * Cierra el receipt: fija status agregado y durationMs. Nombrado {@code seal}
         * (no {@code finalize}) para no colisionar con {@link Object#finalize()}.
         */
        public void seal(Status status) {
            this.status = status;
            this.durationMs = System.currentTimeMillis() - startedAtMs;
        }

        /** Marca un paso fallido y agrega el error al receipt. Asume llamador ya appendió el step FAILED. */
        public void recordError(String step, Throwable t) {
            Map<String, Object> e = new LinkedHashMap<String, Object>();
            e.put("step", step);
            e.put("class", t.getClass().getName());
            e.put("message", String.valueOf(t.getMessage()));
            this.error = e;
        }

        /** Forma plana JSON-ready. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("txId", txId);
            m.put("op", op.name());
            m.put("namespace", namespace);
            m.put("status", status == null ? Status.FAILED.name() : status.name());
            m.put("startedAtMs", startedAtMs);
            m.put("durationMs", durationMs);
            m.put("epoch", epoch);
            m.put("snapshot", new LinkedHashMap<String, Object>(snapshot));
            if (restorePath != null) m.put("restorePath", restorePath);
            List<Map<String, Object>> sl = new ArrayList<Map<String, Object>>();
            synchronized (steps) {
                for (TxStep s : steps) sl.add(s.toMap());
            }
            m.put("steps", sl);
            m.put("error", error);
            m.put("rollback", rollback);
            return m;
        }
    }

    /** Un paso de la coreografía del receipt: nombre, veredicto, métrica nativa, error opcional. */
    public static final class TxStep {
        public final String name;
        public final Status status;
        public final long ms;
        public final Map<String, Object> detail;

        public TxStep(String name, Status status, long ms, Map<String, Object> detail) {
            this.name = name;
            this.status = status;
            this.ms = ms;
            this.detail = detail != null ? detail : new LinkedHashMap<String, Object>();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", name);
            m.put("status", status.name());
            m.put("ms", ms);
            m.put("detail", detail);
            return m;
        }
    }
}
