package dev.mksa.agent;

import dev.mksa.bridge.LedgerSink;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Cerebro del ledger (lado agente), Paso 2 del inc.5: época real + agregado de
 * presencia por namespace (modelo §5.3, la base del translator).
 *
 * Vive en el CL del sistema (no en el CL aislado del transformer/ASM): no
 * necesita ASM y sí ser consultable directo por {@code Agent.dispatch}. Se
 * registra con el {@link dev.mksa.bridge.Bridge} por la interfaz {@link LedgerSink}
 * (bootstrap), así que el CL de esta impl es indiferente para el dispatch.
 *
 * Dos cosas:
 *  · Época: hash estable del modset (§2 "hash del modset"). El contador monótono
 *    y la tabla `epochs` (§6) llegan con SQLite; aquí la época ES el hash.
 *  · Agregado: (cx,cz) → namespace → nº de bloques, escaneando la palette del
 *    chunk en los hooks de (de)serialización. Cada escaneo REEMPLAZA la entrada
 *    del chunk (read y write recalculan lo mismo → compactación natural). En
 *    memoria, reconstruible; SQLite `index.db` se difiere.
 *
 * Reflexión NBT propia y robusta a versiones: se evitan los getters tipados que
 * en 1.21.5 devuelven Optional; se usa solo `get(String)→Tag`, `instanceof List`,
 * `toString()` y el método que devuelve `long[]`/`int`. Las claves NBT
 * (`sections`, `block_states`, `palette`, `data`, `Name`, `xPos`, `zPos`) son
 * literales del formato de guardado, idénticas en intermediary y mojmap.
 *
 * Nada aquí lanza hacia el serializador: todo va en try/catch (corre en el hilo
 * del servidor dentro de la (de)serialización).
 */
public final class Ledger implements LedgerSink {

    public static final Ledger INSTANCE = new Ledger();

    // ---- época ----
    private volatile boolean epochReady;
    private volatile long epochHash;

    // ---- agregado: clave (cx,cz) empaquetada → (namespace → nº de bloques) ----
    private final ConcurrentHashMap<Long, Map<String, Long>> byChunk =
            new ConcurrentHashMap<Long, Map<String, Long>>();

    // ---- ledger en vivo (Paso 3): agencia ----
    // chunkKey (cx,cz) → (posición local empaquetada → namespace del actor).
    // pos local = (lx & 0xF) | ((lz & 0xF) << 4) | (y << 8), con lx/lz/y del
    // BlockPos.asLong() de la mutación. Último escritor gana (invariante 11);
    // un actor vanilla que sobrescribe limpia la entrada (compactación §4.2).
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Integer, String>> agency =
            new ConcurrentHashMap<Long, ConcurrentHashMap<Integer, String>>();
    private final AtomicLong setBlocks = new AtomicLong();

    // ---- diag de pieza 2 (Tier 1 corte 2): por qué byChunk no se incrementa ----
    // Distingue las razones de "onSetBlock entró pero byChunk no se tocó":
    //   prevNull:   prevState==null (early-return de setBlockState)
    //   prevSame:   prev y new son la misma referencia (no-op a nivel de identidad)
    //   nsNull:     namespaceOf(prev) o namespaceOf(new) == null (blockIndex no listo)
    //   nsEqual:    los dos namespaces coinciden (típico vanilla→vanilla)
    //   bothVanilla: ambos minecraft (updateByChunk descarta early)
    //   inc/dec:    actualizaciones efectivas
    private final AtomicLong byChunkInc = new AtomicLong();
    private final AtomicLong byChunkDec = new AtomicLong();
    private final AtomicLong setBlockPrevNull = new AtomicLong();
    private final AtomicLong setBlockPrevSame = new AtomicLong();
    private final AtomicLong setBlockNsNull = new AtomicLong();
    private final AtomicLong setBlockNsEqual = new AtomicLong();
    private final AtomicLong setBlockBothVanilla = new AtomicLong();
    public long byChunkIncCount() { return byChunkInc.get(); }
    public long byChunkDecCount() { return byChunkDec.get(); }
    public long setBlockPrevNullCount() { return setBlockPrevNull.get(); }
    public long setBlockPrevSameCount() { return setBlockPrevSame.get(); }
    public long setBlockNsNullCount() { return setBlockNsNull.get(); }
    public long setBlockNsEqualCount() { return setBlockNsEqual.get(); }
    public long setBlockBothVanillaCount() { return setBlockBothVanilla.get(); }

    // ---- Tier 1 corte 3: filtro de registro vivo (veto al entry de setBlockState) ----
    // armedBlockVeto contiene los namespaces cuyo contenido NO puede colocarse mientras
    // el veto esté armado. Volatile + replace-on-update (no mutación in-place) → lectura
    // hot-path sin sincronizar. Cuando está vacío (caso normal), shouldVeto retorna false
    // tras un volatile read + isEmpty — sin incrementar contadores, sin tocar reflexión.
    private volatile Set<String> armedBlockVeto = Collections.emptySet();
    private final AtomicLong vetoHits = new AtomicLong();         // setBlock vetados
    private final AtomicLong vetoPassthrough = new AtomicLong();  // setBlock visto con veto armado y no víctima
    public Set<String> armedBlockVeto() { return armedBlockVeto; }
    public long vetoHitsCount() { return vetoHits.get(); }
    public long vetoPassthroughCount() { return vetoPassthrough.get(); }

    // Índice de bloques modded: bloque (singleton) → namespace, solo ns≠minecraft.
    // Da en O(1) "¿es modded el contenido?" y su namespace. Publicado vía volatile
    // tras construirse entero (lectura sin más escrituras → segura sin sincronizar).
    private volatile Map<Object, String> blockIndex;
    private volatile Object blockRegistry;       // Registry<Block> vivo
    private volatile Method blockGetId;          // getId(Block): Identifier
    private volatile Method getBlockMethod;      // BlockState.getBlock(): Block
    private volatile boolean getBlockFailed;
    // Firma resuelta para getBlockMethod ("clase.método"). Diagnóstico: confirma
    // que la calibración por identidad sobre dos anclas eligió el método correcto
    // entre JVMs (fix del Tier 1 corte 2 v? sobre el bug intermitente del corte 2).
    private volatile String getBlockMethodSig = "unresolved";
    public String getBlockMethodSig() { return getBlockMethodSig; }
    private volatile Method asLongMethod;        // BlockPos.asLong(): long
    private volatile boolean asLongFailed;
    private static final long NO_LONG = Long.MIN_VALUE;

    // Raíces de cada mod (namespace → paths), de Boot, para casar el actor por
    // code source de la clase llamante.
    private volatile Map<String, List<Path>> modRoots;
    private final ConcurrentHashMap<Class<?>, String> nsByClass =
            new ConcurrentHashMap<Class<?>, String>();

    // StackWalker (runtime Java 21) por reflexión: el agente compila a Java 8.
    private volatile boolean swInited, swFailed;
    private Object stackWalker;
    private Method walkMethod;
    private Method frameGetDeclaringClass;

    // ---- reflexión NBT de escritura (construcción del prov) ----
    private volatile Constructor<?> compoundCtor;
    private volatile Method wPutInt, wPutLong, wPutString, wPut;
    private volatile Class<?> listTagClass;   // capturado del tag "sections" (es un List)
    private volatile boolean writeReady;

    // ---- reflexión NBT, resuelta una vez (las clases de tag son estables) ----
    private volatile Method getTag;                 // CompoundTag.get(String): Tag
    private final ConcurrentHashMap<Class<?>, Method> longArrayOf =
            new ConcurrentHashMap<Class<?>, Method>();
    private final ConcurrentHashMap<Class<?>, Method> intOf =
            new ConcurrentHashMap<Class<?>, Method>();

    private volatile boolean warned;

    // ---- diag de scan() vs captureChunk: ¿por qué se desincronizan? ----
    // Contadores que el dispatcher expone via ledger.stats para diag del corte 1.
    private final AtomicLong scanCalls = new AtomicLong();
    private final AtomicLong scanOk = new AtomicLong();
    private final AtomicLong scanFails = new AtomicLong();
    private volatile String lastScanError = "none";
    // ¿Cuántas veces scan() vio una palette con el namespace armado (= víctima)?
    private final AtomicLong scanArmedNsHits = new AtomicLong();
    private final AtomicLong captureCalls = new AtomicLong();
    private final AtomicLong capturePalettesWithArmedNs = new AtomicLong();
    public long scanCallsCount() { return scanCalls.get(); }
    public long scanOkCount() { return scanOk.get(); }
    public long scanFailsCount() { return scanFails.get(); }
    public String lastScanError() { return lastScanError; }
    public long scanArmedNsHitsCount() { return scanArmedNsHits.get(); }
    public long captureCallsCount() { return captureCalls.get(); }
    public long capturePalettesWithArmedNsCount() { return capturePalettesWithArmedNs.get(); }

    // ---- captura armada (corte 3): barrido dirigido por el ledger ----
    // El barrido se ARMA por IPC (ledger.sweepArm{ns,path}) justo antes de cerrar A.
    // Mientras está armado, el hook de write de chunk captura los bloques de la
    // víctima (pos + NBT de la entrada de palette + be_nbt) y el hook de NbtIo
    // captura sus items del playerdata; todo al archivo de restauración (§7). Es la
    // primera vez que el ledger actúa como MECANISMO y no como telemetría (§3.14).
    private volatile String armedNs;            // namespace de la víctima; null = desarmado
    private volatile RestoreWriter restoreWriter;
    private final AtomicLong capturedBlocks = new AtomicLong();
    private final AtomicLong capturedItems = new AtomicLong();
    private volatile Method tagWrite;           // Tag.write(java.io.DataOutput): void
    private volatile boolean tagWriteFailed;

    // ---- re-inyección armada (corte 4): el ledger ESCRIBE al mundo ----
    // Se ARMA por IPC (ledger.restoreArm{path}) en C, antes de cargar los chunks. El
    // hook de lectura (parse() entry → onChunkRead) inyecta los bloques de la víctima
    // en el CompoundTag del chunk ANTES de que MC lo deserialice → C reconstruye el
    // chunk CON la huella (su modset resuelve el namespace). Patcheo de serialización,
    // simétrico inverso de la captura; sin handle vivo. Inyección perezosa (§3.2/§6).
    private volatile boolean restoring;
    // chunkKey (cx,cz) → registros de bloque {x,y,z, NBT de la entrada de palette}.
    private volatile Map<Long, List<BlockRec>> restoreByChunk;
    // player uuid hash → registros de item {uuid,slot,nbt}; uuid vacío = fallback global.
    private volatile Map<String, List<ItemRec>> restoreByPlayer;
    private volatile List<ItemRec> restoreWildcardItems;
    private final AtomicLong restoredBlocks = new AtomicLong();
    private final AtomicLong restoredItems = new AtomicLong();
    private final AtomicLong restoreConflicts = new AtomicLong();
    private final AtomicLong restoreMisses = new AtomicLong();
    // Diagnóstico de la re-inyección de items (corte 4b): contadores que distinguen
    // las causas posibles de restoredItems=0 sin tocar warnOnce (que es global y se
    // silencia tras el primer aviso de scan). playerReadDiag describe el último read.
    private final AtomicLong playerReadCalls = new AtomicLong();   // onPlayerRead con restoring
    private final AtomicLong playerReadInv = new AtomicLong();     // de esos, con Inventory
    private final AtomicLong itemRecsMatched = new AtomicLong();   // recs aplicables sumados
    private volatile String playerReadDiag = "none";
    // Lector NBT inverso de Tag.write(DataOutput): el TagType de CompoundTag.
    private volatile Object compoundType;       // TagType<CompoundTag> (campo estático)
    private volatile Method typeLoad;           // load(DataInput, NbtAccounter): Tag
    private volatile Object nbtAccounter;       // instancia permisiva, o null si no hace falta
    private volatile boolean readerFailed;
    private volatile Constructor<?> longArrayCtor;  // LongArrayTag(long[])
    private volatile boolean longArrayCtorFailed;

    // ---- reconstrucción NBT→BlockState vivo (corte 5b) ----
    // La restitución viva (InProcess.tier1Restitution) reconstruye un BlockState vivo
    // desde el NBT de la entrada de palette capturada y lo inyecta con setBlock en el
    // hilo del servidor. El primitivo es el inverso canónico NbtUtils.readBlockState(
    // HolderGetter<Block>, CompoundTag): el palette-entry guardado tiene exactamente
    // el formato que writeBlockState produce. El HolderGetter es el registro de bloques
    // vivo (en 1.21 Registry<Block> ES un HolderGetter). Sin handle propio: usa setBlock.
    private volatile Class<?> compoundTagClass;     // clase de CompoundTag, cacheada en scan()
    private volatile Method mReadBlockState;        // NbtUtils.readBlockState(HolderGetter, CompoundTag): BlockState
    private volatile boolean readBlockStateFailed;
    // Nombres de clase estables de NbtUtils (mismo criterio que los de Wcg: solo se
    // hardcodean nombres de clase estables por diseño, nunca de campo/método).
    private static final String[] NBTUTILS_CLASSES = {
            "net.minecraft.class_2512",            // NbtUtils (intermediary)
            "net.minecraft.nbt.NbtUtils",          // mojmap
    };

    /** Un bloque a restaurar: posición global + NBT de su entrada de palette. */
    private static final class BlockRec {
        final int x, y, z;
        final byte[] nbt;
        BlockRec(int x, int y, int z, byte[] nbt) { this.x = x; this.y = y; this.z = z; this.nbt = nbt; }
    }

    /** Un ItemStack a restaurar: uuid del jugador (si existe), slot y NBT completo. */
    private static final class ItemRec {
        final byte[] uuid;
        final int slot;
        final byte[] nbt;
        ItemRec(byte[] uuid, int slot, byte[] nbt) {
            this.uuid = uuid == null ? new byte[0] : uuid;
            this.slot = slot;
            this.nbt = nbt;
        }
    }

    private Ledger() {}

    // ====================== época ======================

    /** Calcula la época a partir del modset (loader+version+mc+mods ordenados). */
    public void computeEpoch(String loader, String loaderVersion, String mc,
                             List<Map<String, Object>> mods) {
        StringBuilder sb = new StringBuilder();
        sb.append(loader).append('|').append(loaderVersion).append('|').append(mc);
        List<String> entries = new ArrayList<String>();
        if (mods != null) {
            for (Map<String, Object> m : mods) {
                Object id = m.get("id");
                Object ver = m.get("version");
                entries.add(String.valueOf(id) + "@" + String.valueOf(ver));
            }
        }
        Collections.sort(entries);
        for (String e : entries) sb.append('|').append(e);
        epochHash = fnv1a64(sb.toString());
        epochReady = true;
    }

    public boolean epochReady() { return epochReady; }
    public long epoch() { return epochHash; }
    public String hashHex() { return String.format("%016x", epochHash); }

    /** {epoch, hash, parent, diff} para state.epoch (parent/diff con SQLite). */
    public Map<String, Object> epochInfo() {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("epoch", epochHash);
        m.put("hash", hashHex());
        m.put("parent", null);
        m.put("diff", null);
        return m;
    }

    private static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= (s.charAt(i) & 0xff);
            h *= 0x100000001b3L;
            h ^= (s.charAt(i) >>> 8);
            h *= 0x100000001b3L;
        }
        return h;
    }

    // ====================== agregado / consultas ======================

    /**
     * Snapshot ATÓMICO de presencia del namespace en una sola pasada sobre {@link #byChunk}:
     * devuelve {@code {blocks, chunks, chunkKeys}} computados en la misma iteración.
     * Usado por {@code tier1Disable} (corte 5) para evitar la race condition entre
     * {@code counts(ns)} y {@code chunksWithNs(ns)} cuando un autosave intermedio
     * reescribe {@code byChunk[ckey]} (§3.17 aplicado a la frontera "qué chunks visitar").
     *
     * <p>{@code chunkKeys} es una snapshot inmutable: las claves de {@code byChunk} son
     * {@code Long} (immutable); aunque luego cambie el {@code Map<String,Long>} interno,
     * la lista snapshot no se ve afectada — el sweep que la consume tiene su propia
     * fuente de verdad estable.
     */
    public Map<String, Object> snapshotPresence(String ns) {
        long blocks = 0;
        java.util.List<Long> keys = new java.util.ArrayList<Long>();
        if (ns != null && !ns.isEmpty()) {
            for (Map.Entry<Long, Map<String, Long>> e : byChunk.entrySet()) {
                Long c = e.getValue().get(ns);
                if (c != null && c > 0) {
                    blocks += c;
                    keys.add(e.getKey());
                }
            }
        }
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("blocks", blocks);
        m.put("chunks", (long) keys.size());
        m.put("chunkKeys", keys);
        return m;
    }

    /** {blocks, entities, containerItems, chunks, chunksLoaded} para wcg.counts. */
    public Map<String, Object> counts(String ns) {
        long blocks = 0;
        long chunks = 0;
        for (Map<String, Long> perNs : byChunk.values()) {
            Long c = perNs.get(ns);
            if (c != null && c > 0) { blocks += c; chunks++; }
        }
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("blocks", blocks);
        m.put("entities", 0L);        // hook del serializador de entidades: incremento posterior
        m.put("containerItems", 0L);  // ledger en vivo: incremento posterior
        m.put("chunks", chunks);
        m.put("chunksLoaded", chunks); // sin hooks de descarga, lo visto == lo cargado
        return m;
    }

    /** Contadores del ledger en vivo (diagnóstico, ledger.stats del Paso 3). */
    public long setBlocksCount() { return setBlocks.get(); }

    public long moddedBlockCount() {
        Map<Object, String> idx = blockIndex;
        return idx == null ? 0 : idx.size();
    }

    public long agencyEntryCount() {
        long n = 0;
        for (ConcurrentHashMap<Integer, String> m : agency.values()) n += m.size();
        return n;
    }

    // ====================== captura armada (corte 3) ======================

    /**
     * Arma el barrido para la víctima {@code ns}, escribiendo el archivo de
     * restauración en {@code path} (lo provee el launcher: el agente no tiene handle
     * al servidor para conocer la ruta del mundo). Desde aquí, cada write de chunk
     * y cada writeCompressed de playerdata capturan la huella de {@code ns}.
     * Devuelve {@code {armed, path, ns}} o {@code {armed:false, error}}.
     */
    public synchronized Map<String, Object> arm(String ns, String path) {
        Map<String, Object> r = new HashMap<String, Object>();
        try {
            RestoreWriter w = new RestoreWriter(path, ns, epochHash);
            restoreWriter = w;
            armedNs = ns;
            capturedBlocks.set(0);
            capturedItems.set(0);
            capturedPositions.clear();
            r.put("armed", Boolean.TRUE);
            r.put("ns", ns);
            r.put("path", path);
        } catch (Throwable t) {
            r.put("armed", Boolean.FALSE);
            r.put("error", String.valueOf(t));
        }
        return r;
    }

    public boolean armed() { return armedNs != null; }
    public long capturedBlockCount() { return capturedBlocks.get(); }
    public long capturedItemCount() { return capturedItems.get(); }

    /** Posiciones globales (x,y,z) exactas que captureChunk capturó durante el armed save.
     *  Útil para el sweep in-process: en lugar de iterar byChunk (frágil ante races),
     *  itera directamente las posiciones que ya sabemos que tienen la víctima. */
    private final java.util.concurrent.CopyOnWriteArrayList<long[]> capturedPositions =
            new java.util.concurrent.CopyOnWriteArrayList<long[]>();
    public java.util.List<long[]> capturedPositions() {
        return new ArrayList<long[]>(capturedPositions);
    }
    public int capturedPositionCount() { return capturedPositions.size(); }

    /**
     * Desarma el barrido del corte 3 y cierra el archivo de restauración. Idempotente.
     * Defensivo: invocar dos {@link #arm} consecutivos sin desarmar perdería el dedup
     * {@code seenBlocks}/{@code seenItems} del writer anterior; este método lo evita.
     * Usado por el barrido in-process (Tier 1 corte 1) tras la captura piggyback.
     */
    public synchronized void disarm() {
        armedNs = null;
        RestoreWriter w = restoreWriter;
        restoreWriter = null;
        if (w != null) w.close();
    }

    /**
     * Namespace del bloque de un {@code BlockState} para el barrido in-process:
     * resuelve {@code BlockState→Block} (cacheado) y mira el {@link #blockIndex} de
     * bloques modded. Devuelve {@code null} si el índice aún no se construyó
     * (pre wcg-ready) — el caller debe distinguir esto de {@code "minecraft"}.
     * Devuelve {@code "minecraft"} si el bloque es vainilla (no está en el índice
     * modded). Devuelve el namespace del mod si es modded. No lanza.
     */
    public String namespaceOf(Object state) {
        if (state == null) return null;
        Map<Object, String> idx = blockIndex;
        if (idx == null) return null;
        Object block = blockOf(state);
        if (block == null) return null;
        String ns = idx.get(block);
        return ns != null ? ns : "minecraft";
    }

    /**
     * ¿El BlockState vivo es aire? (corte 5b §7). Resuelve el id del bloque vivo
     * (blockOf→blockGetId) y aplica la misma semántica que {@link #isAir(String)}
     * que usa el path offline {@code injectCell}. Conservador: si no se puede
     * clasificar (registro/método sin resolver), devuelve {@code false} → la celda
     * cuenta como NO-aire → la restitución no la sobreescribe (no clobber).
     */
    public boolean isAirState(Object state) {
        try {
            Object block = blockOf(state);
            if (block == null) return false;
            Object reg = blockRegistry;
            Method gid = blockGetId;
            if (reg == null || gid == null) return false;
            Object id = gid.invoke(reg, block);
            return id != null && isAir(id.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Lista de claves {@code (cx,cz)} empaquetadas de los chunks que contienen
     * presencia del namespace {@code ns} según el agregado en memoria
     * ({@link #byChunk}). Empaquetado: {@code (cx<<32)|(cz&0xffffffffL)}.
     * O(chunks vistos). Devuelve lista vacía si {@code ns} no aparece o si
     * el agregado no se ha poblado todavía (ningún {@code scan()} ha corrido).
     *
     * Limitación conocida (Tier 1 corte 1): si un chunk vivo nunca se serializó
     * en esta sesión, no aparece aquí. El barrido in-process lo cubre forzando un
     * save previo (deuda técnica documentada en el plan del corte).
     */
    public List<Long> chunksWithNs(String ns) {
        List<Long> out = new ArrayList<Long>();
        if (ns == null || ns.isEmpty()) return out;
        for (Map.Entry<Long, Map<String, Long>> e : byChunk.entrySet()) {
            Long c = e.getValue().get(ns);
            if (c != null && c > 0) out.add(e.getKey());
        }
        return out;
    }

    // ====================== re-inyección armada (corte 4) ======================

    /**
     * Arma la re-inyección leyendo el archivo de restauración {@code path} (lo provee
     * el launcher) y agrupando sus registros de bloque por chunk. A partir de aquí,
     * cada chunk que C deserialice y tenga registros se restaura en su NBT antes de
     * que {@code parse()} lo lea. Devuelve {@code {armed, blocks, chunks}}.
     */
    public synchronized Map<String, Object> restoreArm(String path) {
        Map<String, Object> r = new HashMap<String, Object>();
        try {
            RestoreData data = readRestore(path);
            Map<Long, List<BlockRec>> byChunkRecs = data.blocksByChunk;
            long blocks = 0;
            for (List<BlockRec> l : byChunkRecs.values()) blocks += l.size();
            long items = 0;
            for (List<ItemRec> l : data.itemsByPlayer.values()) items += l.size();
            items += data.wildcardItems.size();
            restoreByChunk = byChunkRecs;
            restoreByPlayer = data.itemsByPlayer;
            restoreWildcardItems = data.wildcardItems;
            restoredBlocks.set(0);
            restoredItems.set(0);
            restoreConflicts.set(0);
            restoreMisses.set(0);
            restoring = true;
            r.put("armed", Boolean.TRUE);
            r.put("blocks", blocks);
            r.put("items", items);
            r.put("chunks", (long) byChunkRecs.size());
            r.put("players", (long) data.itemsByPlayer.size());
        } catch (Throwable t) {
            r.put("armed", Boolean.FALSE);
            r.put("error", String.valueOf(t));
        }
        return r;
    }

    public boolean restoring() { return restoring; }
    public long restoredBlockCount() { return restoredBlocks.get(); }
    public long restoredItemCount() { return restoredItems.get(); }
    public long restoreConflictCount() { return restoreConflicts.get(); }
    public long restoreMissCount() { return restoreMisses.get(); }
    public long playerReadCallCount() { return playerReadCalls.get(); }
    public long playerReadInvCount() { return playerReadInv.get(); }
    public long itemRecsMatchedCount() { return itemRecsMatched.get(); }
    public String playerReadDiag() { return playerReadDiag; }

    /** Resultado parseado del archivo MKSAR1: bloques por chunk, items por jugador, items por contenedor. */
    private static final class RestoreData {
        final Map<Long, List<BlockRec>> blocksByChunk = new HashMap<Long, List<BlockRec>>();
        final Map<String, List<ItemRec>> itemsByPlayer = new HashMap<String, List<ItemRec>>();
        final List<ItemRec> wildcardItems = new ArrayList<ItemRec>();
        final List<ContainerRec> containers = new ArrayList<ContainerRec>();
    }

    /** Un ItemStack en un contenedor (cofre/barril/...): dimensión + pos empaquetada + slot + NBT. */
    public static final class ContainerRec {
        public final String dimKey;
        public final long packedPos;
        public final int slot;
        public final byte[] nbt;
        ContainerRec(String dimKey, long packedPos, int slot, byte[] nbt) {
            this.dimKey = dimKey == null ? "" : dimKey;
            this.packedPos = packedPos;
            this.slot = slot;
            this.nbt = nbt;
        }
    }

    /** Accesor público a la clase del CompoundTag cacheada por scan() — la usa InProcess para tagFromBytes. */
    public Class<?> compoundTagClass() { return compoundTagClass; }

    /**
     * Accesor público sobre {@link #readRestore} para la restitución viva (corte 5b):
     * aplana los registros de bloque del archivo a {@code {Integer x, y, z, byte[] nbt}}
     * sin exponer {@code RestoreData}/{@code BlockRec}. Lanza {@code IOException} si el
     * archivo no existe o es inválido (InProcess lo traduce a error de holder).
     */
    public List<Object[]> readRestoreBlocks(String path) throws IOException {
        RestoreData data = readRestore(path);
        List<Object[]> out = new ArrayList<Object[]>();
        for (List<BlockRec> l : data.blocksByChunk.values()) {
            for (BlockRec r : l) {
                out.add(new Object[] {
                        Integer.valueOf(r.x), Integer.valueOf(r.y),
                        Integer.valueOf(r.z), r.nbt });
            }
        }
        return out;
    }

    /** Lee el archivo de restauración (formato MKSAR1, corte 3). */
    private RestoreData readRestore(String path) throws IOException {
        byte[] b = java.nio.file.Files.readAllBytes(new File(path).toPath());
        RestoreData out = new RestoreData();
        int[] o = { 0 };
        if (b.length < 7 || !"MKSAR1\n".equals(new String(b, 0, 7, StandardCharsets.US_ASCII))) {
            throw new IOException("archivo de restauración inválido (magic)");
        }
        o[0] = 7;
        int nslen = rdInt(b, o);
        o[0] += nslen;          // ns (no se usa en la re-inyección de bloques)
        o[0] += 8;              // epoch long
        while (o[0] < b.length) {
            int kind = b[o[0]++] & 0xff;
            if (kind == 'B') {
                int x = rdInt(b, o), y = rdInt(b, o), z = rdInt(b, o);
                int nl = rdInt(b, o);
                byte[] nbt = new byte[nl];
                System.arraycopy(b, o[0], nbt, 0, nl);
                o[0] += nl;
                int bl = rdInt(b, o);
                if (bl >= 0) o[0] += bl;          // be_nbt: se ignora en corte 4 (solo bloques simples)
                int cx = x >> 4, cz = z >> 4;
                long key = (cx & 0xffffffffL) << 32 | (cz & 0xffffffffL);
                List<BlockRec> l = out.blocksByChunk.get(key);
                if (l == null) { l = new ArrayList<BlockRec>(); out.blocksByChunk.put(key, l); }
                l.add(new BlockRec(x, y, z, nbt));
            } else if (kind == 'I') {
                int ul = rdInt(b, o);
                byte[] uuid = new byte[Math.max(0, ul)];
                if (ul > 0) {
                    System.arraycopy(b, o[0], uuid, 0, ul);
                    o[0] += ul;
                }
                int slot = rdInt(b, o);
                int nl = rdInt(b, o);
                byte[] nbt = new byte[nl];
                System.arraycopy(b, o[0], nbt, 0, nl);
                o[0] += nl;
                ItemRec rec = new ItemRec(uuid, slot, nbt);
                if (uuid.length == 0) {
                    out.wildcardItems.add(rec);
                } else {
                    String key = uuidKey(uuid);
                    List<ItemRec> l = out.itemsByPlayer.get(key);
                    if (l == null) { l = new ArrayList<ItemRec>(); out.itemsByPlayer.put(key, l); }
                    l.add(rec);
                }
            } else if (kind == 'C') {
                // Container item (corte 6): dimKey + packedPos + slot + NBT.
                int dl = rdInt(b, o);
                String dimKey = new String(b, o[0], dl, StandardCharsets.UTF_8);
                o[0] += dl;
                long packed = rdLong(b, o);
                int slot = rdInt(b, o);
                int nl = rdInt(b, o);
                byte[] nbt = new byte[nl];
                System.arraycopy(b, o[0], nbt, 0, nl);
                o[0] += nl;
                out.containers.add(new ContainerRec(dimKey, packed, slot, nbt));
            } else {
                throw new IOException("registro de restauración corrupto: kind=" + kind);
            }
        }
        return out;
    }

    private static int rdInt(byte[] b, int[] o) {
        int v = ((b[o[0]] & 0xff) << 24) | ((b[o[0] + 1] & 0xff) << 16)
                | ((b[o[0] + 2] & 0xff) << 8) | (b[o[0] + 3] & 0xff);
        o[0] += 4;
        return v;
    }

    private static long rdLong(byte[] b, int[] o) {
        long hi = ((long) rdInt(b, o)) & 0xffffffffL;
        long lo = ((long) rdInt(b, o)) & 0xffffffffL;
        return (hi << 32) | lo;
    }

    /**
     * Re-inyecta los bloques de un chunk en su CompoundTag antes de que parse() lo
     * lea. Por cada registro: localiza la sección por Y, comprueba la política de
     * conflicto (§7: celda no-aire → omitir), añade la entrada de palette si falta,
     * re-codifica el array empaquetado y lo escribe de vuelta. Todo en try/catch.
     */
    private void injectChunk(Object chunkTag, List<BlockRec> recs) {
        try {
            Method get = getTag;
            if (get == null) {
                get = resolveGet(chunkTag.getClass());
                if (get == null) return;
                getTag = get;
            }
            ensureWriteMethods(chunkTag.getClass());
            Object sectionsTag = get.invoke(chunkTag, "sections");
            if (!(sectionsTag instanceof List)) return;
            List<?> sections = (List<?>) sectionsTag;

            for (BlockRec rec : recs) {
                int sy = rec.y >> 4;
                Object section = sectionForY(get, sections, sy);
                if (section == null) { restoreMisses.incrementAndGet(); continue; }
                Object bs = get.invoke(section, "block_states");
                if (bs == null) { restoreMisses.incrementAndGet(); continue; }
                if (injectCell(get, bs, rec)) restoredBlocks.incrementAndGet();
            }
        } catch (Throwable t) {
            warnOnce("injectChunk: " + t);
        }
    }

    /** Sección cuyo tag "Y" == sy, o null. */
    private Object sectionForY(Method get, List<?> sections, int sy) throws Exception {
        for (Object section : sections) {
            if (section == null) continue;
            if (numFromTag(get.invoke(section, "Y")) == sy) return section;
        }
        return null;
    }

    /**
     * Inyecta un bloque en la celda correspondiente de una sección. Devuelve true si
     * se escribió, false si se omitió (conflicto §7 o error). Re-codifica el array
     * empaquetado completo (la inversa de scan()).
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private boolean injectCell(Method get, Object bs, BlockRec rec) {
        try {
            Object paletteTag = get.invoke(bs, "palette");
            if (!(paletteTag instanceof List)) return false;
            List palette = (List) paletteTag;
            int size = palette.size();
            if (size == 0) return false;
            int cell = ((rec.y & 15) << 8) | ((rec.z & 15) << 4) | (rec.x & 15);

            // índices de palette actuales (decodificados) — necesarios para conflicto y re-encode
            long[] data = longArray(get.invoke(bs, "data"));
            int[] indices = new int[4096];
            if (data == null) {
                // palette de un solo valor: todas las celdas son palette[0]
                for (int i = 0; i < 4096; i++) indices[i] = 0;
            } else {
                int bits = Math.max(4, ceilLog2(size));
                int perLong = 64 / bits;
                long mask = (1L << bits) - 1;
                for (int i = 0; i < 4096; i++) {
                    int li = i / perLong;
                    if (li >= data.length) break;
                    int off = (i % perLong) * bits;
                    indices[i] = (int) ((data[li] >>> off) & mask);
                }
            }

            // política de conflicto (§7): la celda actual debe ser aire para inyectar
            int cur = indices[cell];
            if (cur >= 0 && cur < size) {
                String curName = tagString(get.invoke(palette.get(cur), "Name"));
                if (!isAir(curName)) { restoreConflicts.incrementAndGet(); return false; }
            }

            // entrada de palette de la víctima (deserializada del NBT capturado)
            Object entry = tagFromBytes(rec.nbt, bs.getClass());
            if (entry == null) { restoreMisses.incrementAndGet(); return false; }
            String wantName = tagString(get.invoke(entry, "Name"));

            // ¿ya está en la palette? (mismo Name+Properties via toString); si no, añadir
            int target = -1;
            for (int i = 0; i < size; i++) {
                if (entry.toString().equals(palette.get(i).toString())) { target = i; break; }
            }
            if (target < 0) {
                palette.add(entry);
                target = size;
                size++;
            }
            indices[cell] = target;

            // re-encode del array empaquetado con el (posible nuevo) tamaño de palette
            int bits = Math.max(4, ceilLog2(size));
            int perLong = 64 / bits;
            int longs = (4096 + perLong - 1) / perLong;
            long[] out = new long[longs];
            long mask = (1L << bits) - 1;
            for (int i = 0; i < 4096; i++) {
                int li = i / perLong;
                int off = (i % perLong) * bits;
                out[li] |= (indices[i] & mask) << off;
            }
            // escribir palette (si creció ya está mutada in-place) y data de vuelta
            Object dataTag = longArrayTagOf(out, get.invoke(bs, "data"));
            if (dataTag == null) { restoreMisses.incrementAndGet(); return false; }
            wPut.invoke(bs, "data", dataTag);
            // wantName solo para diagnóstico
            if (wantName == null) warnOnce("injectCell: entrada sin Name");
            return true;
        } catch (Throwable t) {
            warnOnce("injectCell: " + t);
            return false;
        }
    }

    private static boolean isAir(String name) {
        return name == null || "minecraft:air".equals(name)
                || "minecraft:cave_air".equals(name) || "minecraft:void_air".equals(name);
    }

    /**
     * Construye un LongArrayTag desde un long[]. Resuelve (una vez) el constructor que
     * toma long[] sobre la clase del tag "data" existente (un LongArrayTag). Si no hay
     * "data" existente (palette de un valor), reutiliza la clase ya resuelta.
     */
    private Object longArrayTagOf(long[] values, Object existingDataTag) {
        try {
            Constructor<?> ctor = longArrayCtor;
            if (ctor == null) {
                if (longArrayCtorFailed) return null;
                Class<?> cls = existingDataTag != null ? existingDataTag.getClass() : null;
                if (cls == null) { longArrayCtorFailed = true; return null; }
                for (Constructor<?> c : cls.getDeclaredConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 1 && p[0] == long[].class) { c.setAccessible(true); ctor = c; break; }
                }
                if (ctor == null) { longArrayCtorFailed = true; return null; }
                longArrayCtor = ctor;
            }
            return ctor.newInstance((Object) values);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Deserializa un CompoundTag desde los bytes que {@code Tag.write(DataOutput)}
     * produjo en la captura (corte 3) — el de-risk del corte 4. Resuelve el
     * {@code TagType} de CompoundTag (campo estático cuyo valor tiene
     * {@code load(DataInput, NbtAccounter)}) y un NbtAccounter permisivo, y carga.
     * {@code anyCompound} es un CompoundTag vivo para obtener su clase.
     */
    public Object tagFromBytes(byte[] bytes, Class<?> compoundClass) {
        if (readerFailed) return null;
        try {
            if (typeLoad == null) resolveCompoundReader(compoundClass);
            if (typeLoad == null) { readerFailed = true; return null; }
            DataInput in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (nbtAccounter != null) return typeLoad.invoke(compoundType, in, nbtAccounter);
            return typeLoad.invoke(compoundType, in);
        } catch (Throwable t) {
            warnOnce("tagFromBytes: " + t);
            return null;
        }
    }

    /**
     * Reconstruye un {@code BlockState} VIVO desde el NBT de la entrada de palette
     * capturada (corte 5b). El de-risk del corte: el palette-entry tiene exactamente
     * la forma {@code {Name, Properties}} que {@code NbtUtils.writeBlockState} produce,
     * así que el inverso canónico {@code NbtUtils.readBlockState(HolderGetter<Block>,
     * CompoundTag)} lo reconstruye con propiedades, defaults y desconocido→aire.
     * El HolderGetter es el registro de bloques vivo (en 1.21 lo es). Devuelve el
     * BlockState o {@code null} (miss) si no se puede resolver/deserializar.
     */
    public Object reconstructBlockState(byte[] paletteEntryNbt) {
        if (readBlockStateFailed) return null;
        try {
            Class<?> cc = compoundTagClass;
            if (cc == null) return null;            // ningún chunk escaneado aún
            Method rbs = mReadBlockState;
            if (rbs == null) {
                rbs = resolveReadBlockState();
                if (rbs == null) { readBlockStateFailed = true; return null; }
                mReadBlockState = rbs;
            }
            Object tag = tagFromBytes(paletteEntryNbt, cc);
            if (tag == null) return null;
            return rbs.invoke(null, blockRegistry, tag);
        } catch (Throwable t) {
            warnOnce("reconstructBlockState: " + t);
            return null;
        }
    }

    /**
     * Resuelve {@code NbtUtils.readBlockState} por forma + round-trip (corte 5b).
     * NbtUtils se localiza por nombre de clase estable vía el classloader del registro
     * vivo. El método: estático, 2 params, {@code param[1]} acepta un CompoundTag,
     * {@code param[0]} es una interfaz (HolderGetter) que acepta nuestro registro,
     * retorno de referencia. Puede haber varios candidatos por forma → se desambigua
     * con un round-trip contra un id conocido ({@code minecraft:stone}), el mismo
     * patrón de calibración por comportamiento del §3.18.
     */
    private synchronized Method resolveReadBlockState() {
        if (mReadBlockState != null) return mReadBlockState;
        Object reg = blockRegistry;
        Class<?> cc = compoundTagClass;
        if (reg == null || cc == null) return null;
        try {
            ClassLoader cl = reg.getClass().getClassLoader();
            Class<?> nbtUtils = null;
            for (String name : NBTUTILS_CLASSES) {
                try { nbtUtils = Class.forName(name, false, cl); break; } catch (Throwable ignored) {}
            }
            if (nbtUtils == null) { warnOnce("readBlockState: NbtUtils no hallado"); return null; }
            for (Method m : nbtUtils.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 2) continue;
                if (!p[1].isAssignableFrom(cc)) continue;          // 2º arg toma CompoundTag
                if (!p[0].isInterface() || !p[0].isInstance(reg)) continue;  // 1º arg HolderGetter
                Class<?> ret = m.getReturnType();
                if (ret.isPrimitive() || ret == void.class || ret == String.class) continue;
                m.setAccessible(true);
                if (readBlockStateRoundTripOk(m)) return m;
            }
            warnOnce("readBlockState: ningún candidato pasó forma+round-trip");
            return null;
        } catch (Throwable t) {
            warnOnce("resolveReadBlockState: " + t);
            return null;
        }
    }

    /** Round-trip de calibración: {Name:minecraft:stone} → readBlockState → blockOf == stone. */
    private boolean readBlockStateRoundTripOk(Method readBlockState) {
        try {
            if (!writeReady) ensureWriteMethods(compoundTagClass);
            Object t = compoundCtor.newInstance();
            wPutString.invoke(t, "Name", "minecraft:stone");
            Object st = readBlockState.invoke(null, blockRegistry, t);
            if (st == null) return false;
            Object block = blockOf(st);
            if (block == null) return false;
            Object id = blockGetId.invoke(blockRegistry, block);
            return id != null && "minecraft:stone".equals(id.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Resuelve TagType<CompoundTag>.load(DataInput, NbtAccounter) y un accounter permisivo. */
    private synchronized void resolveCompoundReader(Class<?> compoundClass) {
        if (typeLoad != null || readerFailed) return;
        try {
            // El TagType vive en un campo estático de CompoundTag cuyo valor expone un
            // método load cuyo 1er parámetro es un DataInput. Lo buscamos por forma.
            for (Field f : compoundClass.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object val;
                try { val = f.get(null); } catch (Throwable t) { continue; }
                if (val == null) continue;
                Method load = findLoad(val.getClass(), compoundClass);
                if (load != null) {
                    compoundType = val;
                    typeLoad = load;
                    break;
                }
            }
            if (typeLoad == null) { readerFailed = true; return; }
            // Si load toma 2 params, el 2º es NbtAccounter: instancia permisiva.
            Class<?>[] p = typeLoad.getParameterTypes();
            if (p.length >= 2) {
                nbtAccounter = makeAccounter(p[1]);
                if (nbtAccounter == null) { readerFailed = true; typeLoad = null; }
            }
        } catch (Throwable t) {
            readerFailed = true;
            warnOnce("resolveCompoundReader: " + t);
        }
    }

    /**
     * Método {@code load(DataInput, [NbtAccounter]): Tag} de un TagType, por forma.
     * Exige que el retorno sea un supertipo de CompoundTag (el interfaz Tag), para no
     * confundir {@code load} con un {@code parse}/visitor que devuelve otra cosa. El
     * {@code load} de un TagType es el inverso EXACTO de {@code Tag.write(DataOutput)}
     * (ambos manejan solo el payload; el tipo lo conoce el TagType), por eso lee los
     * bytes que la captura produjo.
     */
    private static Method findLoad(Class<?> typeClass, Class<?> compoundClass) {
        for (Method m : typeClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 1 || p.length > 2) continue;
            if (!DataInput.class.isAssignableFrom(p[0])) continue;
            Class<?> ret = m.getReturnType();
            if (ret.isPrimitive() || ret == void.class) continue;
            if (!ret.isAssignableFrom(compoundClass)) continue; // devuelve un Tag (super de CompoundTag)
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    /** Instancia permisiva de NbtAccounter: factoría estática no-arg (unlimitedHeap), o ctor. */
    private static Object makeAccounter(Class<?> accounterClass) {
        try {
            for (Method m : accounterClass.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0) continue;
                if (!accounterClass.isAssignableFrom(m.getReturnType())) continue;
                m.setAccessible(true);
                Object a = m.invoke(null);
                if (a != null) return a;
            }
        } catch (Throwable ignored) {
            // sin factoría no-arg: probar factoría con un límite grande, o ctor
        }
        try {
            for (Method m : accounterClass.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == long.class
                        && accounterClass.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    Object a = m.invoke(null, Long.MAX_VALUE);
                    if (a != null) return a;
                }
            }
        } catch (Throwable ignored) {
            // continúa al ctor
        }
        try {
            for (Constructor<?> c : accounterClass.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 1 && p[0] == long.class) {
                    c.setAccessible(true);
                    return c.newInstance(Long.MAX_VALUE);
                }
            }
        } catch (Throwable ignored) {
            // sin forma de crearlo
        }
        return null;
    }

    // ====================== índice de bloques modded ======================

    /** Raíces de cada mod (namespace→paths), para casar el actor por code source. */
    public void setModRoots(Map<String, List<Path>> roots) { this.modRoots = roots; }

    /**
     * Construye el índice bloque→namespace (solo modded) desde el registro vivo de
     * bloques (Wcg.liveRegistry "minecraft:block"). Da en O(1) el namespace del
     * contenido de un BlockState (vía BlockState→Block). Se llama tras wcg-ready.
     */
    public void buildBlockIndex(Instrumentation inst) {
        try {
            Object[] reg = Wcg.liveRegistry(inst, "minecraft:block");
            if (reg == null) { warnOnce("registro de bloques no hallado"); return; }
            Object registry = reg[0];
            Method getId = (Method) reg[1];
            IdentityHashMap<Object, String> idx = new IdentityHashMap<Object, String>();
            for (Object block : (Iterable<?>) registry) {
                Object idObj;
                try { idObj = getId.invoke(registry, block); } catch (Throwable t) { continue; }
                if (idObj == null) continue;
                String id = idObj.toString();
                int colon = id.indexOf(':');
                if (colon < 0) continue;
                String ns = id.substring(0, colon);
                if (!"minecraft".equals(ns)) idx.put(block, ns);
            }
            blockRegistry = registry;
            blockGetId = getId;
            blockIndex = idx;  // publicación: lectura sin más escrituras
        } catch (Throwable t) {
            warnOnce("buildBlockIndex: " + t);
        }
    }

    /** BlockState→Block por su único método no-arg cuyo resultado está registrado. */
    private Object blockOf(Object state) {
        Method m = getBlockMethod;
        if (m == null) {
            if (getBlockFailed) return null;
            m = resolveGetBlock(state);
            if (m == null) { getBlockFailed = true; return null; }
            getBlockMethod = m;
        }
        try { return m.invoke(state); } catch (Throwable t) { return null; }
    }

    private Method resolveGetBlock(Object state) {
        Object reg = blockRegistry;
        Method getId = blockGetId;
        if (reg == null || getId == null) return null;

        // Desambiguación por comportamiento (mismo patrón que resolveGet del P3 §3.6).
        // El código antiguo se quedaba con el primer método no-arg cuyo retorno fuera
        // un Block registrado — criterio ambiguo: en BlockState hay varios candidatos
        // estructuralmente válidos (bridges por covariant return, accesores del owner
        // de BlockStateBase, etc.) y getMethods() puede devolverlos en cualquier orden
        // entre JVMs. Si el "primero" era uno que en algún state devuelve un Block
        // de otra entrada (o un singleton tipo AIR), namespaceOf(state) acababa
        // colapsando a "minecraft" para TODO; byChunkInc y wouldSweep caen a cero.
        //
        // Anclas: dos Block distintos del registro. Resolvemos defaultBlockState() en
        // Block (no-arg público que devuelve un instance de stateClass, distinto para
        // dos blocks) y exigimos al candidato identidad sobre AMBAS anclas.
        Object blockA = null, blockB = null;
        for (Object b : (Iterable<?>) reg) {
            if (b == null) continue;
            if (blockA == null) { blockA = b; continue; }
            if (b != blockA) { blockB = b; break; }
        }
        if (blockA == null || blockB == null) return null;

        Class<?> stateClass = state.getClass();
        Object stateA = null, stateB = null;
        for (Method m : blockA.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            Class<?> r = m.getReturnType();
            if (r.isPrimitive() || r == String.class || r == void.class) continue;
            try {
                m.setAccessible(true);
                Object sA = m.invoke(blockA);
                Object sB = m.invoke(blockB);
                if (sA == null || sB == null) continue;
                if (!stateClass.isInstance(sA) || !stateClass.isInstance(sB)) continue;
                if (sA == sB) continue;       // singleton: no es defaultBlockState
                stateA = sA; stateB = sB;
                break;
            } catch (Throwable ignored) {}
        }
        if (stateA == null) return null;

        for (Method cand : stateClass.getMethods()) {
            if (Modifier.isStatic(cand.getModifiers())) continue;
            if (cand.getParameterTypes().length != 0) continue;
            Class<?> r = cand.getReturnType();
            if (r.isPrimitive() || r == String.class || r == void.class) continue;
            try {
                cand.setAccessible(true);
                Object rA = cand.invoke(stateA);
                Object rB = cand.invoke(stateB);
                if (rA == blockA && rB == blockB) {
                    getBlockMethodSig = cand.getDeclaringClass().getName() + "." + cand.getName();
                    return cand;
                }
            } catch (Throwable ignored) {
                // getter con efecto/lanzante: lo descartamos y probamos el siguiente
            }
        }
        return null;
    }

    /** BlockPos.asLong() por su único método de instancia no-arg que devuelve long. */
    private long asLong(Object pos) {
        Method m = asLongMethod;
        if (m == null) {
            if (asLongFailed) return NO_LONG;
            for (Method cand : pos.getClass().getMethods()) {
                if (Modifier.isStatic(cand.getModifiers())) continue;
                if (cand.getParameterTypes().length != 0) continue;
                if (cand.getReturnType() != long.class) continue;
                cand.setAccessible(true);
                m = cand;
                break;
            }
            if (m == null) { asLongFailed = true; return NO_LONG; }
            asLongMethod = m;
        }
        try {
            Object v = m.invoke(pos);
            return v instanceof Long ? (Long) v : NO_LONG;
        } catch (Throwable t) {
            return NO_LONG;
        }
    }

    // ====================== resolución del actor ======================

    /**
     * Namespace del actor de la mutación en curso: la primera clase llamante que
     * casa con las raíces de algún mod (§2 "classloader de la clase llamante" —
     * aquí por code source, universal entre Fabric y Forge/Neo). Sin match, o sin
     * StackWalker, → "minecraft" (el caso vanilla del 99%, el que se auto-verifica).
     */
    private String resolveActor() {
        initStackWalker();
        if (swFailed || stackWalker == null) return "minecraft";
        try {
            Object result = walkMethod.invoke(stackWalker, new Function<Object, Object>() {
                public Object apply(Object streamObj) {
                    Iterator<?> it = ((Stream<?>) streamObj).iterator();
                    while (it.hasNext()) {
                        Object frame = it.next();
                        Class<?> c;
                        try { c = (Class<?>) frameGetDeclaringClass.invoke(frame); }
                        catch (Throwable t) { continue; }
                        String ns = nsForClass(c);
                        if (!"minecraft".equals(ns)) return ns; // primer llamante modded
                    }
                    return "minecraft";
                }
            });
            return result == null ? "minecraft" : (String) result;
        } catch (Throwable t) {
            return "minecraft";
        }
    }

    private void initStackWalker() {
        if (swInited) return;
        synchronized (this) {
            if (swInited) return;
            try {
                Class<?> sw = Class.forName("java.lang.StackWalker");
                Class<?> opt = Class.forName("java.lang.StackWalker$Option");
                @SuppressWarnings({ "unchecked", "rawtypes" })
                Object retain = Enum.valueOf((Class) opt, "RETAIN_CLASS_REFERENCE");
                stackWalker = sw.getMethod("getInstance", opt).invoke(null, retain);
                walkMethod = sw.getMethod("walk", Function.class);
                Class<?> frame = Class.forName("java.lang.StackWalker$StackFrame");
                frameGetDeclaringClass = frame.getMethod("getDeclaringClass");
            } catch (Throwable t) {
                swFailed = true;   // runtime sin StackWalker (Java 8): actor siempre vanilla
            }
            swInited = true;
        }
    }

    private String nsForClass(Class<?> c) {
        String cached = nsByClass.get(c);
        if (cached != null) return cached;
        String ns = computeNsForClass(c);
        nsByClass.put(c, ns);
        return ns;
    }

    private String computeNsForClass(Class<?> c) {
        String n = c.getName();
        if (n.startsWith("net.minecraft.") || n.startsWith("java.") || n.startsWith("jdk.")
                || n.startsWith("sun.") || n.startsWith("dev.mksa.")
                || n.startsWith("net.fabricmc.") || n.startsWith("org.quiltmc.")
                || n.startsWith("net.neoforged.") || n.startsWith("net.minecraftforge.")
                || n.startsWith("cpw.mods.") || n.startsWith("org.spongepowered.")) {
            return "minecraft";
        }
        try {
            ProtectionDomain pd = c.getProtectionDomain();
            if (pd == null) return "minecraft";
            CodeSource cs = pd.getCodeSource();
            if (cs == null) return "minecraft";
            URL loc = cs.getLocation();
            if (loc == null) return "minecraft";
            String csStr = loc.toString();
            Map<String, List<Path>> roots = modRoots;
            if (roots == null) return "minecraft";
            for (Map.Entry<String, List<Path>> e : roots.entrySet()) {
                for (Path root : e.getValue()) {
                    if (matchesRoot(csStr, root)) return e.getKey();
                }
            }
        } catch (Throwable ignored) {
            // sin code source accesible: actor vanilla por defecto
        }
        return "minecraft";
    }

    /**
     * ¿El code source de una clase casa con la raíz de un mod? Best-effort y
     * documentado (§ riesgos del plan): compara el nombre del jar y la ruta. La
     * raíz puede ser un .jar en disco o un Path dentro de un zip FS montado
     * (Fabric jar-in-jar); el code source, una URL file:/jar:.
     */
    private static boolean matchesRoot(String csStr, Path root) {
        try {
            String rs = root.toString().replace('\\', '/');
            String cs = csStr.replace('\\', '/');
            int jar = rs.toLowerCase().lastIndexOf(".jar");
            if (jar >= 0) {
                int slash = rs.lastIndexOf('/', jar);
                String jarName = rs.substring(slash + 1, jar + 4);
                if (!jarName.isEmpty() && cs.contains(jarName)) return true;
            }
            return cs.contains(rs) || rs.contains(cs);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ====================== construcción / lectura del prov ======================

    /** Resuelve (una vez) ctor + putInt/putLong/putString/put del CompoundTag. */
    private void ensureWriteMethods(Class<?> compound) throws Exception {
        if (writeReady) return;
        synchronized (this) {
            if (writeReady) return;
            Constructor<?> ctor = compound.getDeclaredConstructor();
            ctor.setAccessible(true);
            Method pInt = null, pLong = null, pStr = null, pPut = null;
            for (Method m : compound.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                Class<?> ret = m.getReturnType();
                if (pInt == null && p.length == 2 && p[0] == String.class
                        && p[1] == int.class && ret == void.class) pInt = m;
                if (pLong == null && p.length == 2 && p[0] == String.class
                        && p[1] == long.class && ret == void.class) pLong = m;
                if (pStr == null && p.length == 2 && p[0] == String.class
                        && p[1] == String.class && ret == void.class) pStr = m;
                // put(String, Tag): Tag — devuelve el tipo del 2º param, supertipo del Compound.
                if (pPut == null && p.length == 2 && p[0] == String.class
                        && ret == p[1] && p[1].isAssignableFrom(compound) && p[1] != String.class) pPut = m;
            }
            if (pInt == null || pLong == null || pStr == null || pPut == null) {
                throw new NoSuchMethodException("CompoundTag put* no resueltos en " + compound);
            }
            pInt.setAccessible(true);
            pLong.setAccessible(true);
            pStr.setAccessible(true);
            pPut.setAccessible(true);
            compoundCtor = ctor;
            wPutInt = pInt; wPutLong = pLong; wPutString = pStr; wPut = pPut;
            writeReady = true;
        }
    }

    /** Construye la sección {v, epoch, agency?} (§5.1). Requiere ensureWriteMethods. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Object buildProv(long epoch, Map<Integer, String> agencyForChunk) throws Exception {
        Object prov = compoundCtor.newInstance();
        wPutInt.invoke(prov, "v", 1);
        wPutLong.invoke(prov, "epoch", epoch);
        if (agencyForChunk != null && !agencyForChunk.isEmpty() && listTagClass != null) {
            Object list = listTagClass.getDeclaredConstructor().newInstance();
            List raw = (List) list;  // ListTag implementa java.util.List<Tag>
            for (Map.Entry<Integer, String> e : agencyForChunk.entrySet()) {
                Object entry = compoundCtor.newInstance();
                wPutInt.invoke(entry, "pos", e.getKey());
                wPutString.invoke(entry, "actor", e.getValue());
                raw.add(entry);
            }
            wPut.invoke(prov, "agency", list);
        }
        return prov;
    }

    /** Rehidrata el mapa de agencia desde mksa:prov.agency de un chunk leído. */
    private void loadAgency(Object chunkTag) {
        try {
            Method get = getTag;
            if (get == null) return;       // scan() lo resuelve; corre antes que esto
            Object prov = get.invoke(chunkTag, "mksa:prov");
            if (prov == null) return;
            Object agList = get.invoke(prov, "agency");
            if (!(agList instanceof List)) return;
            long key = chunkKey(get, chunkTag);
            ConcurrentHashMap<Integer, String> inner = new ConcurrentHashMap<Integer, String>();
            for (Object e : (List<?>) agList) {
                int pos = intTag(get.invoke(e, "pos"));
                String actor = tagString(get.invoke(e, "actor"));
                if (actor != null) inner.put(pos, actor);
            }
            if (!inner.isEmpty()) agency.put(key, inner);
        } catch (Throwable t) {
            warnOnce("loadAgency: " + t);
        }
    }

    // ====================== sink (hilo del servidor) ======================

    @Override
    public Object onChunkWrite(Object compoundTag) {
        scan(compoundTag);                 // agregado de presencia (Paso 2)
        if (armedNs != null) captureChunk(compoundTag);  // barrido armado (corte 3)
        // Agencia del chunk + construcción de la sección mksa:prov entera.
        ConcurrentHashMap<Integer, String> ag = null;
        try {
            if (getTag != null) ag = agency.get(chunkKey(getTag, compoundTag));
        } catch (Throwable ignored) {
            // sin key no hay agencia que estampar; el prov saldrá con {v, epoch}
        }
        try {
            ensureWriteMethods(compoundTag.getClass());
            return buildProv(epochHash, ag);
        } catch (Throwable t) {
            warnOnce("buildProv: " + t);
            return null;                   // el Bridge cae a {v, epoch:0}
        }
    }

    @Override
    public void onChunkRead(Object compoundTag) {
        // Re-inyección armada (corte 4): mutar el NBT ANTES de scan/parse, para que
        // tanto el agregado como la reconstrucción del chunk vean la huella restaurada.
        if (restoring) {
            Map<Long, List<BlockRec>> byCh = restoreByChunk;
            if (byCh != null && getTag != null) {
                try {
                    List<BlockRec> recs = byCh.get(chunkKey(getTag, compoundTag));
                    if (recs != null && !recs.isEmpty()) injectChunk(compoundTag, recs);
                } catch (Throwable ignored) {
                    // sin key (getTag aún sin resolver): injectChunk lo resuelve en el siguiente
                }
            } else if (byCh != null) {
                // getTag aún no resuelto: injectChunk lo resuelve y reintenta la key dentro
                tryInjectResolvingKey(compoundTag, byCh);
            }
        }
        scan(compoundTag);
        loadAgency(compoundTag);           // rehidrata la agencia persistida
    }

    /** Resuelve getTag y reintenta el match de chunk para la re-inyección. */
    private void tryInjectResolvingKey(Object compoundTag, Map<Long, List<BlockRec>> byCh) {
        try {
            Method get = resolveGet(compoundTag.getClass());
            if (get == null) return;
            getTag = get;
            List<BlockRec> recs = byCh.get(chunkKey(get, compoundTag));
            if (recs != null && !recs.isEmpty()) injectChunk(compoundTag, recs);
        } catch (Throwable ignored) {
            // no se pudo resolver: scan() lo intentará de nuevo
        }
    }

    /**
     * Hook de LevelChunk.setBlockState (Paso 3 + Tier 1 corte 2, ledger en vivo, §3.6).
     * Inyectado ANTES de cada ARETURN, recibe el {@code newState} (param del método)
     * y el {@code prevState} (valor que el método estaba a punto de devolver). Hace
     * dos cosas independientes:
     *
     * <ol>
     *   <li><b>Agencia (§4.4)</b> sobre {@code newState}: resuelve el actor (clase
     *       llamante→mod→ns) y registra agencia si el actor es modded y ≠
     *       propietario del contenido nuevo. Sin cambios respecto al Paso 3.</li>
     *   <li><b>Agregado byChunk in-vivo</b> (pieza 2 del Tier 1 corte 2): si el
     *       namespace del contenido cambia, decrementa el contador del prev y/o
     *       incrementa el del new. Hace que {@code byChunk} sea fuente de verdad
     *       en vivo, sin esperar a {@code scan()} post-save (§3.18). {@code wcg.counts}
     *       deja de mentir; deja de existir la deuda {@code aggregateStale}.</li>
     * </ol>
     *
     * {@code prevState == null} significa que el método retornó sin cambiar nada
     * (mismo state, chunk descargado, ProtoChunk, etc.) → no tocamos byChunk. Igual
     * si {@code blockIndex} aún no se construyó (pre wcg-ready). Todo en try/catch:
     * nunca lanza hacia setBlockState.
     */
    @Override
    public void onSetBlock(Object pos, Object newState, Object prevState) {
        try {
            setBlocks.incrementAndGet();
            Map<Object, String> idx = blockIndex;
            if (idx == null || pos == null || newState == null) return;
            long lp = asLong(pos);
            if (lp == NO_LONG) return;
            int x = (int) (lp >> 38);
            int y = (int) ((lp << 52) >> 52);
            int z = (int) ((lp << 26) >> 38);
            int cx = x >> 4, cz = z >> 4;
            long ckey = (cx & 0xffffffffL) << 32 | (cz & 0xffffffffL);
            int local = (x & 15) | ((z & 15) << 4) | (y << 8);

            Object block = blockOf(newState);
            String contentNs = "minecraft";
            if (block != null) {
                String ns = idx.get(block);
                if (ns != null) contentNs = ns;
            }
            String actor = resolveActor();
            boolean actorModded = !"minecraft".equals(actor);

            if (actorModded && !actor.equals(contentNs)) {
                ConcurrentHashMap<Integer, String> inner = agency.get(ckey);
                if (inner == null) {
                    inner = new ConcurrentHashMap<Integer, String>();
                    ConcurrentHashMap<Integer, String> prev = agency.putIfAbsent(ckey, inner);
                    if (prev != null) inner = prev;
                }
                inner.put(local, actor);
            } else {
                // actor vanilla (o actor==contenido): la mutación supersede cualquier
                // agencia previa en esa posición → se limpia (compactación §4.2).
                ConcurrentHashMap<Integer, String> inner = agency.get(ckey);
                if (inner != null) inner.remove(local);
            }

            // ── Pieza 2 del Tier 1 corte 2: agregado byChunk in-vivo ──
            // prevState=null → el método no cambió nada → byChunk queda igual.
            if (prevState == null) { setBlockPrevNull.incrementAndGet(); return; }
            // Si prevState == newState (misma instancia), tampoco hay cambio neto.
            if (prevState == newState) { setBlockPrevSame.incrementAndGet(); return; }
            String newNs = namespaceOf(newState);   // null si blockIndex no listo
            String prevNs = namespaceOf(prevState);
            if (newNs == null || prevNs == null) { setBlockNsNull.incrementAndGet(); return; }
            if (newNs.equals(prevNs)) { setBlockNsEqual.incrementAndGet(); return; }
            updateByChunk(ckey, prevNs, newNs);
        } catch (Throwable t) {
            warnOnce("onSetBlock: " + t);
        }
    }

    /**
     * Tier 1 corte 3: filtro de registro vivo. Inyectado al ENTRY de
     * {@code LevelChunk.setBlockState(BlockPos, BlockState, …)}. Si devuelve
     * {@code true}, la inyección hace {@code ACONST_NULL; ARETURN} — corto-circuita
     * el método antes de mutar el chunk. {@code null} es la señal canónica de "no
     * hubo cambio" que MC ya emite cuando {@code prev == state}; los callers ya
     * saben tratarla (no envían packet, no disparan neighbor updates).
     *
     * Hot path: corre en CADA {@code setBlockState}. Cuando no hay veto armado (caso
     * normal), el coste es un volatile read + {@link Set#isEmpty()} — sin contador,
     * sin reflexión. La JIT inlinea trivialmente.
     *
     * Falla cerrado: ante cualquier excepción (también las del namespaceOf) devuelve
     * {@code false} (no vetar > silenciar bug). La pieza 2 del corte 2 ya ejercita
     * {@code namespaceOf} en cada mutación, así que el coste cuando SÍ hay veto
     * armado es el mismo que el del observador in-vivo (cacheado, sin reflexión).
     */
    @Override
    public boolean shouldVeto(Object newState) {
        Set<String> v = armedBlockVeto;
        if (v.isEmpty() || newState == null) return false;
        try {
            String ns = namespaceOf(newState);
            if (ns != null && v.contains(ns)) {
                vetoHits.incrementAndGet();
                return true;
            }
            vetoPassthrough.incrementAndGet();
            return false;
        } catch (Throwable t) {
            warnOnce("shouldVeto: " + t);
            return false;
        }
    }

    /**
     * Arma el filtro de registro vivo para los {@code namespaces} dados. Mientras
     * esté armado, todo {@code LevelChunk.setBlockState} cuyo nuevo state pertenezca
     * a uno de esos namespaces se corto-circuita: el método devuelve {@code null}
     * sin mutar, como si el caller hubiese pedido idempotencia. Idempotente: invocar
     * dos veces reemplaza el conjunto, no compone. Pasar set vacío equivale a
     * {@link #disarmBlockVeto()}. Tier 1 corte 3.
     */
    public synchronized Map<String, Object> armBlockVeto(Set<String> namespaces) {
        Set<String> next = (namespaces == null || namespaces.isEmpty())
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new HashSet<String>(namespaces));
        armedBlockVeto = next;
        Map<String, Object> r = new HashMap<String, Object>();
        r.put("armed", Boolean.TRUE);
        r.put("count", (long) next.size());
        r.put("namespaces", new ArrayList<String>(next));
        return r;
    }

    /** Desarma el filtro. Idempotente. */
    public synchronized Map<String, Object> disarmBlockVeto() {
        armedBlockVeto = Collections.<String>emptySet();
        Map<String, Object> r = new HashMap<String, Object>();
        r.put("disarmed", Boolean.TRUE);
        return r;
    }

    // ====================== Tier 1 corte 4: filtro de datapack ======================
    // armedDp = namespaces cuyas recetas/tags se descartan en el próximo (y siguientes)
    // reload. El filtrado ocurre dentro de la fase apply del reload: el Bridge invoca
    // onRecipePayload (al return del prepare tipado de RecipeManager) y onTagBuild (al
    // entry del build tipado de TagManagerLoader). Patrón filter, no des-registrar (§3.1)
    // llevado al datapack: el contenido del namespace no se construye como estado vivo,
    // el resto pasa intacto. Tier 1 corte 4.
    private volatile Set<String> armedDp = Collections.emptySet();
    private final AtomicLong dpFilteredRecipes = new AtomicLong();
    private final AtomicLong dpFilteredTags = new AtomicLong();
    private final AtomicLong dpRecipeHookCalls = new AtomicLong();
    private final AtomicLong dpTagHookCalls = new AtomicLong();
    // Diagnóstico: si el hook se invoca pero la reflexión sobre el payload no halla
    // los dos campos (Map + Multimap), no filtramos y registramos el fallo para que el
    // verify pueda distinguir "el hook no corrió" de "el hook corrió pero falló la reflexión".
    private volatile String dpLastRecipePayloadClass = "none";
    private volatile String dpLastError = "none";

    public Set<String> armedDp() { return armedDp; }
    public long dpFilteredRecipesCount() { return dpFilteredRecipes.get(); }
    public long dpFilteredTagsCount() { return dpFilteredTags.get(); }
    public long dpRecipeHookCallsCount() { return dpRecipeHookCalls.get(); }
    public long dpTagHookCallsCount() { return dpTagHookCalls.get(); }
    public String dpLastRecipePayloadClass() { return dpLastRecipePayloadClass; }
    public String dpLastError() { return dpLastError; }

    /**
     * Arma el filtro de datapack para los {@code namespaces} dados. El próximo reload
     * (cualquier llamada a {@code MinecraftServer.reloadResources}, manual o por
     * {@code tx.tier1DpReload}) verá los hooks de RecipeManager.prepare y
     * TagManagerLoader.build con este conjunto armado, y excluirá in-place las
     * entradas cuyo id tenga ns víctima. Pasar set vacío equivale a desarmar.
     * Idempotente. Tier 1 corte 4.
     */
    public synchronized Map<String, Object> armDpFilter(Set<String> namespaces) {
        Set<String> next = (namespaces == null || namespaces.isEmpty())
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new HashSet<String>(namespaces));
        armedDp = next;
        Map<String, Object> r = new HashMap<String, Object>();
        r.put("armed", Boolean.TRUE);
        r.put("count", (long) next.size());
        r.put("namespaces", new ArrayList<String>(next));
        return r;
    }

    /** Desarma el filtro de datapack. Idempotente. */
    public synchronized Map<String, Object> disarmDpFilter() {
        armedDp = Collections.<String>emptySet();
        Map<String, Object> r = new HashMap<String, Object>();
        r.put("disarmed", Boolean.TRUE);
        return r;
    }

    // ---- helpers para onRecipePayload / onTagBuild ----

    // Resolución cacheada de los campos {Multimap, Map} del payload de recetas
    // (class_10289). Se calcula una vez por payload class y se reusa.
    private volatile Class<?> recipePayloadClass;
    private volatile Field recipePayloadMultimapField;
    private volatile Field recipePayloadMapField;
    // Constructor / método estático para construir un Multimap mutable sin compile-time
    // dep en Guava. Resuelto desde el classloader del propio Multimap inmutable.
    private volatile Class<?> hashMultimapClass;
    private volatile Method hashMultimapCreate;
    private volatile Method multimapPut;
    private volatile Method multimapEntries;

    /**
     * Resuelve los dos campos del payload class_10289 (RecipeManager$RecipeMap):
     * el Multimap (ImmutableMultimap) por su nombre de tipo y el Map (ImmutableMap)
     * por isAssignableFrom Map. Se ejecuta una vez por clase y cachea. Devuelve
     * {@code true} si halla AMBOS (sin uno solo no filtramos).
     */
    private boolean resolveRecipePayloadFields(Class<?> payloadClass) {
        if (payloadClass == recipePayloadClass
                && recipePayloadMapField != null && recipePayloadMultimapField != null) {
            return true;
        }
        try {
            Agent.openModule(Agent.instrumentation, payloadClass);
        } catch (Throwable ignored) {}
        Field fMap = null, fMm = null;
        for (Class<?> k = payloadClass; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                // Multimap: el tipo declarado del campo es la interfaz Multimap de Guava.
                if ("com.google.common.collect.Multimap".equals(ft.getName()) && fMm == null) {
                    fMm = f;
                } else if (Map.class.isAssignableFrom(ft) && fMap == null) {
                    fMap = f;
                }
            }
            if (fMap != null && fMm != null) break;
        }
        if (fMap == null || fMm == null) {
            dpLastError = "payload " + payloadClass.getName()
                    + " sin par Map+Multimap (map=" + (fMap != null) + " mm=" + (fMm != null) + ")";
            return false;
        }
        try { fMap.setAccessible(true); fMm.setAccessible(true); }
        catch (Throwable t) { dpLastError = "setAccessible: " + t; return false; }
        recipePayloadMapField = fMap;
        recipePayloadMultimapField = fMm;
        recipePayloadClass = payloadClass;
        return true;
    }

    // ---- F4 wcg.refs: lectura del mapa de recetas VIVO (sin reload) ----
    // Reusa la resolución de campos del corte 4 (resolveRecipePayloadFields) para
    // alcanzar el Map<ResourceKey, RecipeHolder> que el RecipeManager (class_1863)
    // guarda en su payload (class_10289). El de-risk de aristas de referencia
    // (tx.probeRecipes) lo recorre sin disparar un reload.
    private volatile Class<?> recipeManagerClass;
    private volatile Field recipeManagerPayloadField; // campo del RM que tiene el payload
    private volatile boolean recipeManagerIsPayload;   // o el RM mismo es el contenedor (viejo)

    /**
     * Devuelve el {@code Map<ResourceKey, RecipeHolder>} vivo que tiene el
     * {@code RecipeManager} dado, sin disparar reload. Halla el payload (objeto con
     * par Map+Multimap, vía {@link #resolveRecipePayloadFields}) ya sea como campo del
     * RecipeManager (1.21.2+: RecipeMap class_10289) o como el RecipeManager mismo
     * (versiones viejas). {@code null} + {@code dpLastError} si no resuelve.
     */
    @SuppressWarnings("unchecked")
    public Map<Object, Object> liveRecipeMap(Object recipeManager) {
        if (recipeManager == null) { dpLastError = "liveRecipeMap: recipeManager null"; return null; }
        try {
            Object payload = resolvePayloadObject(recipeManager);
            if (payload == null) return null; // dpLastError ya seteado
            if (!resolveRecipePayloadFields(payload.getClass())) return null;
            Object m = recipePayloadMapField.get(payload);
            if (!(m instanceof Map)) { dpLastError = "liveRecipeMap: map field no es Map"; return null; }
            return (Map<Object, Object>) m;
        } catch (Throwable t) {
            dpLastError = "liveRecipeMap: " + t;
            return null;
        }
    }

    private Object resolvePayloadObject(Object recipeManager) throws Exception {
        Class<?> rmc = recipeManager.getClass();
        if (rmc == recipeManagerClass) {
            if (recipeManagerIsPayload) return recipeManager;
            if (recipeManagerPayloadField != null) return recipeManagerPayloadField.get(recipeManager);
        }
        try { Agent.openModule(Agent.instrumentation, rmc); } catch (Throwable ignored) {}
        // Caso A: el RecipeManager ES el contenedor con par Map+Multimap (versiones viejas).
        if (resolveRecipePayloadFields(rmc)) {
            recipeManagerClass = rmc;
            recipeManagerIsPayload = true;
            recipeManagerPayloadField = null;
            return recipeManager;
        }
        // Caso B: un campo del RecipeManager cuyo valor es el payload (class_10289).
        for (Class<?> k = rmc; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft == String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(recipeManager);
                    if (v == null) continue;
                    if (resolveRecipePayloadFields(v.getClass())) {
                        recipeManagerClass = rmc;
                        recipeManagerIsPayload = false;
                        recipeManagerPayloadField = f;
                        return v;
                    }
                } catch (Throwable ignored) {}
            }
        }
        dpLastError = "resolvePayloadObject: ningun payload Map+Multimap en " + rmc.getName();
        return null;
    }

    /** Namespace dueño de un {@code ResourceKey} (wrapper público del parse del corte 4). */
    public String nsOfResourceKey(Object key) { return namespaceOfResourceKey(key); }

    /**
     * Resuelve métodos de Guava Multimap por reflexión, desde la INSTANCIA de Multimap
     * que ya tenemos (su classloader es el que tiene Guava cargado). Sin compile-time
     * dep en Guava. Cachea {@code HashMultimap.create()}, {@code put(Object,Object)} y
     * {@code entries()}.
     */
    private boolean resolveMultimapMethods(Object aMultimap) {
        if (hashMultimapClass != null && hashMultimapCreate != null
                && multimapPut != null && multimapEntries != null) return true;
        try {
            ClassLoader cl = aMultimap.getClass().getClassLoader();
            Class<?> mmCls = Class.forName("com.google.common.collect.Multimap", true, cl);
            multimapEntries = mmCls.getMethod("entries");
            multimapPut = mmCls.getMethod("put", Object.class, Object.class);
            hashMultimapClass = Class.forName("com.google.common.collect.HashMultimap", true, cl);
            hashMultimapCreate = hashMultimapClass.getMethod("create");
            return true;
        } catch (Throwable t) {
            dpLastError = "resolveMultimapMethods: " + t;
            return false;
        }
    }

    /**
     * Extrae el namespace de un {@code class_5321 (ResourceKey)}: parsea su toString
     * "ResourceKey[<reg> / <ns:path>]" — formato estable desde 1.18. Robusto a
     * versiones sin reflexión sobre los accesores. {@code null} si no encaja.
     */
    private static String namespaceOfResourceKey(Object key) {
        if (key == null) return null;
        String s = key.toString();
        if (s == null) return null;
        int slash = s.indexOf(" / ");
        if (slash < 0) return null;
        int end = s.indexOf(']', slash);
        if (end < 0) end = s.length();
        String loc = s.substring(slash + 3, end).trim();
        int colon = loc.indexOf(':');
        return (colon > 0) ? loc.substring(0, colon) : null;
    }

    /**
     * Extrae el namespace de un {@code class_2960 (ResourceLocation/Identifier)}:
     * toString = "ns:path". Estable desde Minecraft Beta.
     */
    private static String namespaceOfIdentifier(Object id) {
        if (id == null) return null;
        String s = id.toString();
        if (s == null) return null;
        int colon = s.indexOf(':');
        return (colon > 0) ? s.substring(0, colon) : null;
    }

    /**
     * Tier 1 corte 4 — filtro de recetas. Invocado por el Bridge al RETURN del
     * prepare tipado de RecipeManager (class_1863.method_64680), con el payload
     * class_10289 recién construido. Si hay namespace(s) armado(s), reemplaza los
     * dos campos finales (ImmutableMultimap field_54644 + ImmutableMap field_54645)
     * por copias sin las entradas con ns víctima.
     *
     * Estrategia:
     *   1. Filtrar field_54645 (Map<ResourceKey, RecipeHolder>) por key→namespace.
     *      Conservar también el set de RecipeHolder "a quitar" (por identidad).
     *   2. Filtrar field_54644 (Multimap<RecipeType, RecipeHolder>) por value en el
     *      set anterior. Esto evita resolver el accesor key() de RecipeHolder.
     *   3. Reemplazar ambos campos via reflexión (setAccessible + set).
     *
     * Falla silenciosa: si la reflexión sobre Multimap/Map/payload no resuelve,
     * registra dpLastError y no filtra (el reload sigue normal con todas las
     * recetas). Mejor reload completo que crash en mitad del apply.
     */
    @Override
    public void onRecipePayload(Object payload) {
        dpRecipeHookCalls.incrementAndGet();
        Set<String> armed = armedDp;
        if (armed.isEmpty() || payload == null) return;
        try {
            dpLastRecipePayloadClass = payload.getClass().getName();
            if (!resolveRecipePayloadFields(payload.getClass())) return;
            Object originalMap = recipePayloadMapField.get(payload);
            Object originalMm = recipePayloadMultimapField.get(payload);
            if (!(originalMap instanceof Map) || originalMm == null) {
                dpLastError = "fields no son Map/Multimap en runtime";
                return;
            }
            if (!resolveMultimapMethods(originalMm)) return;

            // 1) filtrar el Map<ResourceKey, RecipeHolder> por ns del key.
            Map<Object, Object> origMap = (Map<Object, Object>) originalMap;
            Map<Object, Object> newMap = new java.util.LinkedHashMap<Object, Object>(origMap.size());
            Set<Object> toRemoveHolders = Collections.newSetFromMap(
                    new IdentityHashMap<Object, Boolean>());
            long removed = 0;
            for (Map.Entry<Object, Object> e : origMap.entrySet()) {
                String ns = namespaceOfResourceKey(e.getKey());
                if (ns != null && armed.contains(ns)) {
                    toRemoveHolders.add(e.getValue());
                    removed++;
                    continue;
                }
                newMap.put(e.getKey(), e.getValue());
            }

            // 2) Filtrar el Multimap<RecipeType, RecipeHolder> por value (RecipeHolder).
            Object newMm = hashMultimapCreate.invoke(null);
            Object entries = multimapEntries.invoke(originalMm);
            if (entries instanceof Iterable) {
                for (Object e : (Iterable<?>) entries) {
                    if (!(e instanceof Map.Entry)) continue;
                    Map.Entry<?, ?> me = (Map.Entry<?, ?>) e;
                    if (toRemoveHolders.contains(me.getValue())) continue;
                    multimapPut.invoke(newMm, me.getKey(), me.getValue());
                }
            }

            // 3) Reemplazar los dos campos finales. setAccessible(true) basta sin
            //    tocar Field.modifiers: estamos en el módulo MC, ya abierto por
            //    openModule en resolveRecipePayloadFields.
            recipePayloadMapField.set(payload, newMap);
            recipePayloadMultimapField.set(payload, newMm);
            dpFilteredRecipes.addAndGet(removed);
        } catch (Throwable t) {
            dpLastError = "onRecipePayload: " + t;
            warnOnce("onRecipePayload: " + t);
        }
    }

    /**
     * Tier 1 corte 4 — filtro de tags. Invocado por el Bridge a la ENTRY del build
     * tipado de TagManagerLoader (class_3503.method_18242), con el
     * {@code Map<Identifier, List<Tag>>} mutable. Elimina las entradas cuyo id tenga
     * ns armado — el resultado del build no contendrá tags propios de la víctima.
     *
     * Alcance v1: solo descarta tags del NAMESPACE víctima ({@code mcwstairs:foo}).
     * Tags vanilla que contengan entradas del namespace víctima se conservan (esas
     * son referencias cross-namespace = cascade, F4/wcg.refs — fuera del corte 4).
     */
    @Override
    public void onTagBuild(Object tagMap) {
        dpTagHookCalls.incrementAndGet();
        Set<String> armed = armedDp;
        if (armed.isEmpty() || tagMap == null) return;
        if (!(tagMap instanceof Map)) return;
        try {
            Map<Object, Object> map = (Map<Object, Object>) tagMap;
            long removed = 0;
            Iterator<Map.Entry<Object, Object>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Object, Object> e = it.next();
                String ns = namespaceOfIdentifier(e.getKey());
                if (ns != null && armed.contains(ns)) {
                    it.remove();
                    removed++;
                }
            }
            if (removed > 0) dpFilteredTags.addAndGet(removed);
        } catch (Throwable t) {
            dpLastError = "onTagBuild: " + t;
            warnOnce("onTagBuild: " + t);
        }
    }

    /**
     * Aplica un cambio de namespace en una celda al agregado {@link #byChunk}:
     * decrementa {@code prevNs} (si es modded) e incrementa {@code newNs} (si lo
     * es). Atómico por chunk vía {@link ConcurrentHashMap#compute}; muta el
     * {@code Map<String,Long>} interno in-place (corre en el hilo del servidor,
     * y {@code scan()} reemplaza el inner map wholesale — una eventual carrera es
     * benigna: ambos llegan a la misma cifra desde la palette real). Limpia
     * entradas que llegan a 0 para no inflar el mapa con namespaces vacíos.
     */
    private void updateByChunk(long ckey, final String prevNs, final String newNs) {
        final boolean prevModded = prevNs != null && !"minecraft".equals(prevNs);
        final boolean newModded = newNs != null && !"minecraft".equals(newNs);
        if (!prevModded && !newModded) { setBlockBothVanilla.incrementAndGet(); return; }
        byChunk.compute(ckey, new java.util.function.BiFunction<Long, Map<String, Long>, Map<String, Long>>() {
            public Map<String, Long> apply(Long k, Map<String, Long> perNs) {
                if (perNs == null) perNs = new HashMap<String, Long>();
                if (prevModded) {
                    Long c = perNs.get(prevNs);
                    long nv = (c == null ? 0L : c) - 1L;
                    if (nv > 0) perNs.put(prevNs, nv);
                    else perNs.remove(prevNs);
                    byChunkDec.incrementAndGet();
                }
                if (newModded) {
                    Long c = perNs.get(newNs);
                    perNs.put(newNs, (c == null ? 0L : c) + 1L);
                    byChunkInc.incrementAndGet();
                }
                return perNs;
            }
        });
    }

    /**
     * Hook de NbtIo.writeCompressed (corte 3). El playerdata y level.dat pasan por
     * aquí; filtramos por la clave "Inventory" (es un jugador) y, si el barrido está
     * armado, capturamos al archivo de restauración los items cuyo namespace es el de
     * la víctima — del inventario y del cofre del ender. No lanza.
     */
    @Override
    public void onPlayerWrite(Object compoundTag) {
        RestoreWriter w = restoreWriter;
        String ns = armedNs;
        if (w == null || ns == null || compoundTag == null) return;
        try {
            Method get = getTag;
            if (get == null) {
                get = resolveGet(compoundTag.getClass());
                if (get == null) return;
                getTag = get;
            }
            Object inv = get.invoke(compoundTag, "Inventory");
            if (!(inv instanceof List)) return;   // no es un jugador (level.dat, etc.)
            byte[] uuid = playerUuid(get, compoundTag);
            captureItems(get, (List<?>) inv, ns, uuid, w);
            Object ender = get.invoke(compoundTag, "EnderItems");
            if (ender instanceof List) captureItems(get, (List<?>) ender, ns, uuid, w);
        } catch (Throwable t) {
            warnOnce("onPlayerWrite: " + t);
        }
    }

    /**
     * Hook de TagValueInput.create (corte 4b). Si la restauración está armada y el
     * CompoundTag es el de un jugador, reinyecta los ItemStack de la víctima ANTES de
     * que el codec lo decodifique (el ValueInput es solo-lectura). Simétrico a
     * onPlayerWrite. El trigger es el punto de convergencia universal del lado-lectura
     * (host vía level.dat y .dat por fallback), no NbtIo.readCompressed —que el host
     * nunca recorría—. Como create() es genérico (lo llama cada decode por codec), se
     * exige una clave exclusiva de jugador además de "Inventory" (un villager también
     * tiene Inventory; sin el filtro la rama wildcard le inyectaría el item).
     */
    @Override
    public void onPlayerRead(Object compoundTag) {
        if (!restoring || compoundTag == null) return;
        playerReadCalls.incrementAndGet();
        try {
            Method get = getTag;
            if (get == null) {
                get = resolveGet(compoundTag.getClass());
                if (get == null) { playerReadDiag = "no-get"; return; }
                getTag = get;
            }
            Object inv = get.invoke(compoundTag, "Inventory");
            if (!(inv instanceof List)) {
                // No es playerdata (level.dat u otro). Diagnóstico: las claves de
                // primer nivel, para confirmar QUÉ CompoundTag llega por aquí.
                playerReadDiag = "no-inv:" + topKeys(get, compoundTag);
                return;
            }
            if (!isPlayerTag(get, compoundTag)) {
                // Tiene Inventory pero NO es un jugador (p.ej. un villager): no inyectar,
                // para que la rama wildcard no le meta el item de la víctima.
                return;
            }
            playerReadInv.incrementAndGet();
            // Corte 4b: cierra la reversibilidad del jugador sobre la mochila
            // (el caso medido en corte 2b). EnderItems sigue compartiendo el mismo
            // registro 'I' del formato interim y se deja fuera para no duplicar un
            // stack sin marcador de contenedor.
            injectPlayerItems(get, compoundTag, inv);
        } catch (Throwable t) {
            playerReadDiag = "ex:" + t;
            warnOnce("onPlayerRead: " + t);
        }
    }

    /** Captura los items de una lista (inventario/ender) cuyo namespace es la víctima. */
    private void captureItems(Method get, List<?> items, String ns, byte[] uuid, RestoreWriter w) {
        try {
            for (Object it : items) {
                if (it == null) continue;
                String id = tagString(get.invoke(it, "id"));
                if (id == null || !ns.equals(namespaceOf(id))) continue;
                int slot = numFromTag(get.invoke(it, "Slot"));  // Slot es ByteTag → SNBT
                byte[] nbt = nbtBytes(it);
                if (w.item(uuid, slot, nbt)) capturedItems.incrementAndGet();
            }
        } catch (Throwable t) {
            warnOnce("captureItems: " + t);
        }
    }

    /**
     * ¿El CompoundTag es de un jugador? Comprueba claves exclusivas del NBT de player
     * (abilities, playerGameType, EnderItems) que ninguna otra entidad serializa.
     * TagValueInput.create es genérico (lo llama cada decode por codec), así que
     * "Inventory" por sí solo no distingue a un villager de un jugador.
     */
    private boolean isPlayerTag(Method get, Object compound) {
        try {
            return get.invoke(compound, "abilities") != null
                    || get.invoke(compound, "playerGameType") != null
                    || get.invoke(compound, "EnderItems") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** UUID del jugador (IntArrayTag de 4 ints) como 16 bytes, o vacío si no se halla. */
    private byte[] playerUuid(Method get, Object compound) {
        try {
            int[] u = intArray(get.invoke(compound, "UUID"));
            if (u != null && u.length == 4) {
                ByteBuffer bb = ByteBuffer.allocate(16);
                for (int x : u) bb.putInt(x);
                return bb.array();
            }
        } catch (Throwable ignored) {
            // sin UUID legible: la captura del item vale por slot+nbt
        }
        return new byte[0];
    }

    /** Reinyecta items de la víctima en Inventory/EnderItems con política de conflicto §7. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void injectPlayerItems(Method get, Object playerTag, Object itemsTag) {
        if (!(itemsTag instanceof List)) return;
        List items = (List) itemsTag;
        List<ItemRec> recs = matchingItems(get, playerTag);
        itemRecsMatched.addAndGet(recs.size());
        if (recs.isEmpty()) {
            byte[] pu = playerUuid(get, playerTag);
            Map<String, List<ItemRec>> bp = restoreByPlayer;
            List<ItemRec> wc = restoreWildcardItems;
            playerReadDiag = "no-recs uuid=" + (pu.length > 0 ? uuidKey(pu) : "none")
                    + " keys=" + (bp == null ? "null" : bp.keySet())
                    + " wild=" + (wc == null ? "null" : wc.size());
            return;
        }
        for (ItemRec rec : recs) {
            try {
                int idx = findItemBySlot(get, items, rec.slot);
                if (idx >= 0) {
                    Object cur = items.get(idx);
                    String curId = tagString(get.invoke(cur, "id"));
                    if (!isAirItem(curId)) {
                        restoreConflicts.incrementAndGet();
                        continue;
                    }
                    Object entry = tagFromBytes(rec.nbt, playerTag.getClass());
                    if (entry == null) { restoreMisses.incrementAndGet(); continue; }
                    items.set(idx, entry);
                    restoredItems.incrementAndGet();
                    continue;
                }
                Object entry = tagFromBytes(rec.nbt, playerTag.getClass());
                if (entry == null) { restoreMisses.incrementAndGet(); continue; }
                items.add(entry);
                restoredItems.incrementAndGet();
            } catch (Throwable t) {
                restoreMisses.incrementAndGet();
                warnOnce("injectPlayerItems: " + t);
            }
        }
    }

    /** Registros de item aplicables a este playerdata: UUID exacto + fallback sin UUID. */
    private List<ItemRec> matchingItems(Method get, Object playerTag) {
        List<ItemRec> out = new ArrayList<ItemRec>();
        Map<String, List<ItemRec>> byPlayer = restoreByPlayer;
        if (byPlayer != null) {
            byte[] uuid = playerUuid(get, playerTag);
            if (uuid.length > 0) {
                List<ItemRec> exact = byPlayer.get(uuidKey(uuid));
                if (exact != null) out.addAll(exact);
            }
        }
        List<ItemRec> any = restoreWildcardItems;
        if (any != null && !any.isEmpty()) out.addAll(any);
        return out;
    }

    /** Índice del stack en el slot dado, o -1 si no existe. */
    private int findItemBySlot(Method get, List<?> items, int slot) {
        try {
            for (int i = 0; i < items.size(); i++) {
                Object it = items.get(i);
                if (it == null) continue;
                if (numFromTag(get.invoke(it, "Slot")) == slot) return i;
            }
        } catch (Throwable ignored) {
            // sin slot legible: se tratará como ausente
        }
        return -1;
    }

    /** Fallback de conflicto para item vacío/aire. */
    private static boolean isAirItem(String id) {
        return id == null || id.isEmpty() || "minecraft:air".equals(id);
    }

    /** Claves de primer nivel de un CompoundTag (diagnóstico), o "?" si no se pueden leer. */
    private String topKeys(Method get, Object compound) {
        try {
            for (Method cand : compound.getClass().getMethods()) {
                if (Modifier.isStatic(cand.getModifiers())) continue;
                if (cand.getParameterTypes().length != 0) continue;
                if (!java.util.Set.class.isAssignableFrom(cand.getReturnType())) continue;
                cand.setAccessible(true);
                Object v = cand.invoke(compound);
                if (v instanceof java.util.Set) {
                    java.util.Set<?> s = (java.util.Set<?>) v;
                    StringBuilder sb = new StringBuilder("[");
                    int n = 0;
                    for (Object k : s) { if (n++ > 0) sb.append(','); sb.append(k); if (n >= 12) { sb.append(",…"); break; } }
                    return sb.append(']').toString();
                }
            }
        } catch (Throwable ignored) {
            // sin keySet legible
        }
        return "?";
    }

    /**
     * Captura al archivo de restauración los bloques de la víctima de un chunk que se
     * serializa (corte 3). Re-recorre las palettes como {@link #scan}, pero por CELDA:
     * computa la posición global y guarda {pos, NBT de la entrada de palette (Name +
     * Properties), be_nbt si hay block entity ahí}. Solo corre con el barrido armado.
     */
    private void captureChunk(Object chunkTag) {
        captureCalls.incrementAndGet();
        RestoreWriter w = restoreWriter;
        String ns = armedNs;
        if (w == null || ns == null) return;
        try {
            Method get = getTag;
            if (get == null) {
                get = resolveGet(chunkTag.getClass());
                if (get == null) return;
                getTag = get;
            }
            long key = chunkKey(get, chunkTag);
            int cx = (int) (key >> 32);
            int cz = (int) key;
            Object sectionsTag = get.invoke(chunkTag, "sections");
            if (!(sectionsTag instanceof List)) return;
            Object beTag = get.invoke(chunkTag, "block_entities"); // para be_nbt

            for (Object section : (List<?>) sectionsTag) {
                if (section == null) continue;
                int sy = numFromTag(get.invoke(section, "Y"));     // índice de sección
                Object bs = get.invoke(section, "block_states");
                if (bs == null) continue;
                Object paletteTag = get.invoke(bs, "palette");
                if (!(paletteTag instanceof List)) continue;
                List<?> palette = (List<?>) paletteTag;
                int size = palette.size();
                if (size == 0) continue;

                boolean[] victim = new boolean[size];
                boolean any = false;
                for (int i = 0; i < size; i++) {
                    String pn = namespaceOf(tagString(get.invoke(palette.get(i), "Name")));
                    if (ns.equals(pn)) { victim[i] = true; any = true; }
                }
                if (!any) continue;
                capturePalettesWithArmedNs.incrementAndGet();

                long[] data = longArray(get.invoke(bs, "data"));
                if (data == null) {
                    // palette de un solo valor: las 4096 celdas son palette[0]
                    if (victim[0]) {
                        byte[] nbt = nbtBytes(palette.get(0));
                        for (int i = 0; i < 4096; i++) {
                            captureCell(w, get, beTag, cx, cz, sy, i, nbt);
                        }
                    }
                    continue;
                }
                int bits = Math.max(4, ceilLog2(size));
                int perLong = 64 / bits;
                long mask = (1L << bits) - 1;
                for (int i = 0; i < 4096; i++) {
                    int li = i / perLong;
                    if (li >= data.length) break;
                    int off = (i % perLong) * bits;
                    int idx = (int) ((data[li] >>> off) & mask);
                    if (idx < 0 || idx >= size || !victim[idx]) continue;
                    captureCell(w, get, beTag, cx, cz, sy, i, nbtBytes(palette.get(idx)));
                }
            }
        } catch (Throwable t) {
            warnOnce("captureChunk: " + t);
        }
    }

    /** Emite un registro de bloque para la celda i de una sección (orden y·256+z·16+x). */
    private void captureCell(RestoreWriter w, Method get, Object beTag,
                             int cx, int cz, int sy, int i, byte[] nbt) throws IOException {
        int x = cx * 16 + (i & 15);
        int z = cz * 16 + ((i >> 4) & 15);
        int y = sy * 16 + (i >> 8);
        if (w.block(x, y, z, nbt, beNbtAt(get, beTag, x, y, z))) {
            capturedBlocks.incrementAndGet();
            capturedPositions.add(new long[] { x, y, z });
        }
    }

    /** be_nbt del block entity en (x,y,z), casando contra block_entities, o null. */
    private byte[] beNbtAt(Method get, Object beTag, int x, int y, int z) {
        if (!(beTag instanceof List)) return null;
        try {
            for (Object be : (List<?>) beTag) {
                if (be == null) continue;
                if (intTag(get.invoke(be, "x")) == x
                        && intTag(get.invoke(be, "y")) == y
                        && intTag(get.invoke(be, "z")) == z) {
                    return nbtBytes(be);
                }
            }
        } catch (Throwable ignored) {
            // sin be legible: el bloque se restaura por state, sin datos de contenedor
        }
        return null;
    }

    /** Serializa un Tag a su NBT binario por Tag.write(DataOutput) (método del JDK). */
    public byte[] nbtBytes(Object tag) {
        if (tag == null) return new byte[0];
        try {
            Method m = tagWrite;
            if (m == null) {
                if (tagWriteFailed) return new byte[0];
                m = resolveTagWrite(tag.getClass());
                if (m == null) { tagWriteFailed = true; return new byte[0]; }
                tagWrite = m;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            m.invoke(tag, dos);
            dos.flush();
            return baos.toByteArray();
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    /** Tag.write(DataOutput): void — instancia, 1 param DataOutput (JDK), void. */
    private static Method resolveTagWrite(Class<?> tagClass) {
        for (Method m : tagClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || !DataOutput.class.isAssignableFrom(p[0])) continue;
            if (m.getReturnType() != void.class) continue;
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    /** int[] de un IntArrayTag (UUID), por su único método no-arg que devuelve int[]. */
    private int[] intArray(Object tag) {
        if (tag == null) return null;
        try {
            for (Method cand : tag.getClass().getMethods()) {
                if (Modifier.isStatic(cand.getModifiers())) continue;
                if (cand.getParameterTypes().length != 0) continue;
                if (cand.getReturnType() != int[].class) continue;
                cand.setAccessible(true);
                Object v = cand.invoke(tag);
                return v instanceof int[] ? (int[]) v : null;
            }
        } catch (Throwable ignored) {
            // sin getter int[]: UUID vacío
        }
        return null;
    }

    /**
     * Valor numérico de un tag (ByteTag de Slot/Y, IntTag) parseando su SNBT —
     * robusto frente a {@code getId()} (devuelve el id de tipo, no el valor) y a los
     * múltiples getters numéricos coercitivos de NumericTag. ByteTag.toString() es
     * "4b"/"-4b"; IntTag, "12". Tomamos el entero inicial con signo.
     */
    private static int numFromTag(Object tag) {
        if (tag == null) return 0;
        String s = tag.toString().trim();
        int i = 0, n = s.length();
        StringBuilder sb = new StringBuilder();
        if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) sb.append(s.charAt(i++));
        while (i < n && Character.isDigit(s.charAt(i))) sb.append(s.charAt(i++));
        if (sb.length() == 0 || (sb.length() == 1 && !Character.isDigit(sb.charAt(0)))) return 0;
        try { return Integer.parseInt(sb.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    /** Escanea las palettes del chunk y reemplaza su entrada en el agregado. */
    private void scan(Object chunkTag) {
        scanCalls.incrementAndGet();
        try {
            Method get = getTag;
            if (get == null) {
                get = resolveGet(chunkTag.getClass());
                if (get == null) { warnOnce("get(String) no resuelto"); scanFails.incrementAndGet(); lastScanError = "get-null"; return; }
                getTag = get;
            }
            // El chunkTag es un CompoundTag: cacheamos su clase una vez para la
            // reconstrucción NBT→BlockState del corte 5b (tagFromBytes la necesita).
            if (compoundTagClass == null) compoundTagClass = chunkTag.getClass();
            Object sectionsTag = get.invoke(chunkTag, "sections");
            if (!(sectionsTag instanceof List)) { scanFails.incrementAndGet(); lastScanError = "sections-not-list"; return; }
            // El tag "sections" ES un ListTag (instanceof List): capturamos su clase
            // una vez para poder construir el ListTag de agencia sin hardcodear el
            // nombre por versión (class_2499 / ListTag).
            if (listTagClass == null) listTagClass = sectionsTag.getClass();

            long key = chunkKey(get, chunkTag);

            Map<String, Long> perNs = new HashMap<String, Long>();
            for (Object section : (List<?>) sectionsTag) {
                if (section == null) continue;
                Object bs = get.invoke(section, "block_states");
                if (bs == null) continue;
                Object paletteTag = get.invoke(bs, "palette");
                if (!(paletteTag instanceof List)) continue;
                List<?> palette = (List<?>) paletteTag;
                int size = palette.size();
                if (size == 0) continue;

                // índice de palette → namespace
                String[] paletteNs = new String[size];
                for (int i = 0; i < size; i++) {
                    paletteNs[i] = namespaceOf(tagString(get.invoke(palette.get(i), "Name")));
                }

                long[] data = longArray(get.invoke(bs, "data"));
                if (data == null) {
                    // palette de un solo valor: las 4096 celdas son palette[0]
                    add(perNs, paletteNs[0], 4096L);
                    continue;
                }
                int bits = Math.max(4, ceilLog2(size));
                int perLong = 64 / bits;
                long mask = (1L << bits) - 1;
                int[] idxCount = new int[size];
                for (int i = 0; i < 4096; i++) {
                    int li = i / perLong;
                    if (li >= data.length) break;
                    int off = (i % perLong) * bits;
                    int idx = (int) ((data[li] >>> off) & mask);
                    if (idx >= 0 && idx < size) idxCount[idx]++;
                }
                for (int i = 0; i < size; i++) {
                    if (idxCount[i] != 0) add(perNs, paletteNs[i], idxCount[i]);
                }
            }
            byChunk.put(key, perNs);
            scanOk.incrementAndGet();
            // ¿Este chunk contiene la víctima armada?
            String ans = armedNs;
            if (ans != null) {
                Long c = perNs.get(ans);
                if (c != null && c > 0) scanArmedNsHits.incrementAndGet();
            }
        } catch (Throwable t) {
            scanFails.incrementAndGet();
            lastScanError = String.valueOf(t);
            warnOnce("scan: " + t);
        }
    }

    private static void add(Map<String, Long> m, String ns, long n) {
        Long cur = m.get(ns);
        m.put(ns, (cur == null ? 0L : cur) + n);
    }

    private long chunkKey(Method get, Object chunkTag) throws Exception {
        long cx = intTag(get.invoke(chunkTag, "xPos"));
        long cz = intTag(get.invoke(chunkTag, "zPos"));
        return (cx & 0xffffffffL) << 32 | (cz & 0xffffffffL);
    }

    // ====================== reflexión NBT ======================

    /**
     * Resuelve {@code get(String): Tag} de CompoundTag — el lector no destructivo.
     *
     * CUIDADO (causa de la corrupción del inc.5 P3): CompoundTag tiene DOS métodos
     * con firma idéntica {@code (String) -> Tag}: {@code get} (Map.get, lee) y
     * {@code remove} (Map.remove, BORRA y devuelve lo borrado). Quedarse con el
     * primero de {@code getMethods()} es una lotería que en 1.21.11 caía en
     * {@code remove} → cada "lectura" del scan borraba la clave del chunk
     * (sections/xPos/zPos…) → chunks mutilados y pos [0,0] al recargar.
     *
     * Desambiguación por COMPORTAMIENTO, robusta a versiones: el getter correcto
     * NO muta. Se prueba cada candidato sobre un tag de juguete con una clave
     * puesta; {@code get} la devuelve dos veces, {@code remove} devuelve null la
     * segunda (ya la borró). Si ninguno resulta no-mutante, se devuelve null
     * (mejor no leer que corromper).
     */
    private Method resolveGet(Class<?> compound) {
        List<Method> candidates = new ArrayList<Method>();
        for (Method m : compound.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || p[0] != String.class) continue;
            Class<?> r = m.getReturnType();
            if (r == String.class || r.isPrimitive() || r == Object.class || r == compound) continue;
            if (r.isAssignableFrom(compound)) candidates.add(m); // devuelve Tag (super de CompoundTag)
        }
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) {
            candidates.get(0).setAccessible(true);
            return candidates.get(0);
        }
        // Más de un (String)->Tag: probar cuál NO muta (get) y descartar remove.
        try {
            ensureWriteMethods(compound);
            for (Method m : candidates) {
                m.setAccessible(true);
                Object test = compoundCtor.newInstance();
                wPut.invoke(test, "__mksa_probe__", compoundCtor.newInstance());
                Object first = m.invoke(test, "__mksa_probe__");
                Object second = m.invoke(test, "__mksa_probe__");
                if (first != null && second != null) return m; // get: no mutó
            }
            warnOnce("resolveGet: ningún (String)->Tag no-mutante; scan desactivado");
        } catch (Throwable t) {
            warnOnce("resolveGet probe: " + t);
        }
        return null; // ante la duda, no leer (no corromper)
    }

    /** Valor de un StringTag: su toString() es SNBT entre comillas; las quitamos. */
    private static String tagString(Object stringTag) {
        if (stringTag == null) return null;
        String s = stringTag.toString();
        int n = s.length();
        if (n >= 2 && s.charAt(0) == '"' && s.charAt(n - 1) == '"') {
            return s.substring(1, n - 1);
        }
        return s;
    }

    private static String namespaceOf(String id) {
        if (id == null) return "minecraft";
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    /** long[] de un LongArrayTag, por su único método no-arg que devuelve long[]. */
    private long[] longArray(Object tag) {
        if (tag == null) return null;
        try {
            Method m = longArrayOf.get(tag.getClass());
            if (m == null) {
                for (Method cand : tag.getClass().getMethods()) {
                    if (!Modifier.isStatic(cand.getModifiers())
                            && cand.getParameterTypes().length == 0
                            && cand.getReturnType() == long[].class) {
                        cand.setAccessible(true);
                        m = cand;
                        break;
                    }
                }
                if (m == null) return null;
                longArrayOf.put(tag.getClass(), m);
            }
            Object v = m.invoke(tag);
            return v instanceof long[] ? (long[]) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** int de un IntTag (xPos/zPos), por su método no-arg que devuelve int. */
    private int intTag(Object tag) {
        if (tag == null) return 0;
        try {
            Method m = intOf.get(tag.getClass());
            if (m == null) {
                for (Method cand : tag.getClass().getMethods()) {
                    if (!Modifier.isStatic(cand.getModifiers())
                            && cand.getParameterTypes().length == 0
                            && cand.getReturnType() == int.class) {
                        cand.setAccessible(true);
                        m = cand;
                        break;
                    }
                }
                if (m == null) return 0;
                intOf.put(tag.getClass(), m);
            }
            Object v = m.invoke(tag);
            return v instanceof Integer ? (Integer) v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int ceilLog2(int n) {
        if (n <= 1) return 0;
        return 32 - Integer.numberOfLeadingZeros(n - 1);
    }

    private static String uuidKey(byte[] uuid) {
        StringBuilder sb = new StringBuilder(uuid.length * 2);
        for (byte b : uuid) {
            int v = b & 0xff;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private void warnOnce(String msg) {
        if (warned) return;
        warned = true;
        System.err.println("[mksa] ledger Paso 2: " + msg);
    }

    // ---- API in-process para el mod thin (LedgerSink) ----
    // El mod thin (Fabric CL) llama al Bridge (bootstrap CL), que dispatcha aquí
    // por la interfaz. Devolvemos JSON serializado (String): es el mínimo común
    // entre los dos classloaders. Reusamos Agent.Json.write, que ya escribe
    // Map/List/String/Number/Boolean y cae a String.valueOf para lo demás.

    @Override
    public String modThinListMods() {
        List<Map<String, Object>> src = Boot.mods;
        // armedBlockVeto = namespaces actualmente desactivados in-process (corte 3).
        // El loader sigue viendo el jar (enabled del Boot.mods = true siempre), pero
        // el usuario lo desactivo por Tier 1; "running" es la verdad para la UI.
        Set<String> vetoed = armedBlockVeto;
        List<Object> out = new ArrayList<Object>(src.size());
        int iconsServed = 0;
        for (Map<String, Object> m : src) {
            Map<String, Object> e = new HashMap<String, Object>();
            e.put("id", m.get("id"));
            e.put("name", m.get("name"));
            e.put("version", m.get("version"));
            Object nsList = m.get("namespaces");
            e.put("namespaces", nsList);
            e.put("tier", m.get("tier"));
            e.put("enabled", m.get("enabled"));   // loader-side (compat)
            // Rutas absolutas de los .jar del mod. La UI filtra por "vive en
            // <instance>/mods/" para esconder submodulos de fabric-api y mods anidados.
            e.put("files", m.get("files"));
            // Descripción y icono. La descripción es estática (capturada en Boot).
            // El icono se lee LIVE del cache en cada llamada: el fetcher de Modrinth
            // sigue rellenando mksa/mod-icons/ después del boot, así que cada
            // refresh de la UI ve los iconos que han llegado entretanto sin
            // necesidad de reiniciar el juego.
            Object desc = m.get("description");
            if (desc != null) e.put("description", desc);
            Object id = m.get("id");
            if (id instanceof String) {
                byte[] iconBytes = ModIconStore.loadCached((String) id);
                if (iconBytes != null && iconBytes.length > 0) {
                    e.put("icon", java.util.Base64.getEncoder().encodeToString(iconBytes));
                    iconsServed++;
                }
            }
            // running = no veto armado para ningun namespace del mod. Si un mod
            // declara varios, queda "off" en cuanto cualquiera este vetado.
            boolean running = true;
            if (nsList instanceof List) {
                for (Object o : (List<?>) nsList) {
                    if (o != null && vetoed.contains(String.valueOf(o))) { running = false; break; }
                }
            }
            e.put("running", running);
            // Tier 1 es lo único cubierto por el binomio in-process (sweep + filter
            // + dpReload + restitución viva). El panel pinta el botón gris para los
            // demás con tooltip explicativo.
            Object tier = m.get("tier");
            e.put("supportedDisable",
                    tier instanceof Integer && ((Integer) tier).intValue() == 1);
            out.add(e);
        }
        Map<String, Object> root = new HashMap<String, Object>();
        root.put("ok", Boolean.TRUE);
        root.put("epoch", hashHex());
        root.put("mods", out);
        ModIconStore.log("MODTHIN-LIST mods=" + src.size() + " iconsServed=" + iconsServed);
        return Agent.Json.write(root);
    }

    @Override
    public String modThinDisable(String ns) {
        if (ns == null || ns.isEmpty()) {
            return Agent.Json.write(modThinErr("BAD_PARAMS", "ns vacio"));
        }
        try {
            String path = modThinRestorePath(ns);
            ensureParent(path);
            Map<String, Object> r = InProcess.INSTANCE.tier1Disable(ns, path);
            return Agent.Json.write(modThinReceiptToReply(r));
        } catch (Throwable t) {
            return Agent.Json.write(modThinErr("INTERNAL", t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }

    @Override
    public String modThinEnable(String ns) {
        if (ns == null || ns.isEmpty()) {
            return Agent.Json.write(modThinErr("BAD_PARAMS", "ns vacio"));
        }
        try {
            String path = modThinRestorePath(ns);
            if (!new File(path).isFile()) {
                Map<String, Object> err = modThinErr("NO_RESTORE",
                        "no hay archivo de restauracion para '" + ns + "'");
                err.put("path", path);
                return Agent.Json.write(err);
            }
            Map<String, Object> r = InProcess.INSTANCE.tier1Enable(ns, path);
            return Agent.Json.write(modThinReceiptToReply(r));
        } catch (Throwable t) {
            return Agent.Json.write(modThinErr("INTERNAL", t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }

    @Override
    public String modThinEpoch() {
        Map<String, Object> r = new HashMap<String, Object>();
        r.put("ok", Boolean.TRUE);
        r.put("epoch", hashHex());
        return Agent.Json.write(r);
    }

    /**
     * Convierte un receipt del tier1Disable/Enable (Map heterogéneo con TxJournal.Status,
     * snapshot, steps, etc.) en una respuesta compacta para el panel del jugador.
     * Aplana solo lo que la UI necesita: ok, status, blocks, chunks, restoreStats si
     * existe. El receipt completo viaja por IPC `tx.*` para diagnóstico — el mod thin
     * no lo necesita.
     */
    private static Map<String, Object> modThinReceiptToReply(Map<String, Object> r) {
        Map<String, Object> out = new HashMap<String, Object>();
        // tier1Disable/Enable devuelven {ok:bool|null, status, snapshot:{blocks,chunks}, ...}.
        // Si hay "error" arriba, es un fail() temprano (BAD_PARAMS, etc.).
        if (r.get("error") != null) {
            out.put("ok", Boolean.FALSE);
            out.put("error", String.valueOf(r.get("error")));
            if (r.get("code") != null) out.put("code", String.valueOf(r.get("code")));
            return out;
        }
        Object status = r.get("status");
        String st = status == null ? "UNKNOWN" : String.valueOf(status);
        out.put("ok", "OK".equals(st));
        out.put("status", st);
        Object snap = r.get("snapshot");
        if (snap instanceof Map) {
            Map<?, ?> sm = (Map<?, ?>) snap;
            if (sm.get("blocks") != null) out.put("blocks", sm.get("blocks"));
            if (sm.get("chunks") != null) out.put("chunks", sm.get("chunks"));
        }
        if (r.get("txId") != null) out.put("txId", r.get("txId"));
        if (r.get("restorePath") != null) out.put("restorePath", String.valueOf(r.get("restorePath")));
        return out;
    }

    private static Map<String, Object> modThinErr(String code, String msg) {
        Map<String, Object> e = new HashMap<String, Object>();
        e.put("ok", Boolean.FALSE);
        e.put("code", code);
        e.put("error", msg);
        return e;
    }

    /**
     * Ruta del restore file dentro de la instancia. CWD = carpeta minecraft cuando
     * MC corre desde el launcher (verificado en F1: el launcher hace ProcessBuilder
     * con cwd en {@code <instancia>/minecraft}). Un archivo por namespace; el
     * último disable gana, igual que el último restore. Si en el futuro queremos
     * snapshots históricos, se cuelga timestamp al nombre.
     */
    private static String modThinRestorePath(String ns) {
        String gameDir = System.getProperty("user.dir", ".");
        return new File(new File(gameDir, "mksa" + File.separator + "restore"),
                ns + ".mksar").getAbsolutePath();
    }

    private static void ensureParent(String path) {
        File p = new File(path).getParentFile();
        if (p != null && !p.isDirectory()) p.mkdirs();
    }

    /**
     * Escritor del archivo de restauración (corte 3) — formato propio binario,
     * interim: el esquema SQLite §7 (restore_blocks/entities/items) sigue siendo el
     * contrato congelado y llega con el motor de restauración (corte 4). Aquí basta
     * con persistir NBT completo por contribución, suficiente para reconstruir.
     *
     * Layout (big-endian, como NBT):
     *   "MKSAR1\n" | ns(len int + utf8) | epoch long
     *   registros: 'B' x,y,z(int) nbtLen(int) nbt | beLen(int, -1 si no hay) [be]
     *              'I' uuidLen(int) uuid slot(int) nbtLen(int) nbt
     *
     * Append sincronizado con flush por registro: el JVM puede morir en el cierre con
     * gracia y el archivo queda completo. Dedup por (pos) y (uuidHash,slot) para
     * tolerar varios overloads de writeCompressed o un re-guardado del mismo chunk.
     */
    private static final class RestoreWriter {
        private final DataOutputStream out;
        private final Set<Long> seenBlocks = new HashSet<Long>();
        private final Set<Long> seenItems = new HashSet<Long>();

        RestoreWriter(String path, String ns, long epoch) throws IOException {
            this(path, ns, epoch, false);
        }

        /**
         * @param append  si true, abre el archivo en modo APPEND y NO escribe la cabecera
         *   salvo que el archivo esté vacío/nuevo. Esto permite que el block sweep (que
         *   abre con append=false: trunca + cabecera) y los sweeps de items/containers
         *   (append=true) escriban al MISMO archivo sin pisarse. Sin esto, cada sweep
         *   truncaba el archivo del anterior y solo sobrevivían los últimos records
         *   (los bloques, primeros en escribirse, se perdían siempre).
         */
        RestoreWriter(String path, String ns, long epoch, boolean append) throws IOException {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            boolean needHeader = !append || !f.exists() || f.length() == 0;
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f, append)));
            if (needHeader) {
                out.writeBytes("MKSAR1\n");
                byte[] nb = ns.getBytes(StandardCharsets.UTF_8);
                out.writeInt(nb.length);
                out.write(nb);
                out.writeLong(epoch);
                out.flush();
            }
        }

        synchronized boolean block(int x, int y, int z, byte[] nbt, byte[] be) throws IOException {
            long key = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
            if (!seenBlocks.add(key)) return false;
            out.writeByte('B');
            out.writeInt(x); out.writeInt(y); out.writeInt(z);
            out.writeInt(nbt.length); out.write(nbt);
            if (be == null) { out.writeInt(-1); }
            else { out.writeInt(be.length); out.write(be); }
            out.flush();
            return true;
        }

        synchronized boolean item(byte[] uuid, int slot, byte[] nbt) throws IOException {
            long h = 1125899906842597L;
            for (byte b : uuid) h = 31 * h + b;
            long key = 31 * h + slot;
            if (!seenItems.add(key)) return false;
            out.writeByte('I');
            out.writeInt(uuid.length); out.write(uuid);
            out.writeInt(slot);
            out.writeInt(nbt.length); out.write(nbt);
            out.flush();
            return true;
        }

        /** Record 'C' (container): dimKey + packedPos + slot + NBT. Corte 6. */
        synchronized boolean container(String dimKey, long packedPos, int slot, byte[] nbt) throws IOException {
            // Dedup por (dimKey, packedPos, slot): mismo cofre, mismo slot, una sola entrada.
            long h = 1125899906842597L;
            for (byte b : dimKey.getBytes(StandardCharsets.UTF_8)) h = 31 * h + b;
            h = 31 * h + packedPos;
            long key = 31 * h + slot;
            if (!seenItems.add(key)) return false;
            byte[] db = dimKey.getBytes(StandardCharsets.UTF_8);
            out.writeByte('C');
            out.writeInt(db.length); out.write(db);
            out.writeLong(packedPos);
            out.writeInt(slot);
            out.writeInt(nbt.length); out.write(nbt);
            out.flush();
            return true;
        }

        synchronized void close() {
            try { out.close(); } catch (IOException ignored) {}
        }
    }

    // ====================== Tier 1 corte 6: helpers públicos ======================
    // Lectores planos sobre MKSAR1 que devuelven listas Object[] sin exponer los
    // records internos. Patrón idéntico a readRestoreBlocks (corte 5b).

    /** Lee items 'I' (jugador) del MKSAR1. Cada Object[] = {uuid:byte[], slot:int, nbt:byte[]}. */
    public List<Object[]> readRestoreItems(String path) throws IOException {
        RestoreData data = readRestore(path);
        List<Object[]> out = new ArrayList<Object[]>();
        for (List<ItemRec> l : data.itemsByPlayer.values()) {
            for (ItemRec r : l) {
                out.add(new Object[] { r.uuid, Integer.valueOf(r.slot), r.nbt });
            }
        }
        for (ItemRec r : data.wildcardItems) {
            out.add(new Object[] { r.uuid, Integer.valueOf(r.slot), r.nbt });
        }
        return out;
    }

    /** Lee containers 'C' del MKSAR1. Cada Object[] = {dimKey:String, packedPos:Long, slot:int, nbt:byte[]}. */
    public List<Object[]> readRestoreContainers(String path) throws IOException {
        RestoreData data = readRestore(path);
        List<Object[]> out = new ArrayList<Object[]>();
        for (ContainerRec r : data.containers) {
            out.add(new Object[] { r.dimKey, Long.valueOf(r.packedPos), Integer.valueOf(r.slot), r.nbt });
        }
        return out;
    }

    /**
     * Crea un RestoreWriter público en modo APPEND (corte 6 lo necesita desde InProcess).
     * Los sweeps de items y containers corren DESPUÉS del block sweep en la misma
     * transacción de disable y deben AÑADIR sus records al mismo archivo, no truncarlo.
     * Si el archivo aún no existe (block sweep no capturó nada / no corrió), el writer
     * escribe la cabecera él mismo, así el archivo siempre es válido.
     */
    public Object newRestoreWriter(String path, String ns) throws IOException {
        return new RestoreWriter(path, ns, epochHash, true);
    }

    /** Llama a item() sobre un RestoreWriter (typed como Object). */
    public boolean writeItemRecord(Object writer, byte[] uuid, int slot, byte[] nbt) throws IOException {
        return ((RestoreWriter) writer).item(uuid, slot, nbt);
    }

    /** Llama a container() sobre un RestoreWriter (typed como Object). */
    public boolean writeContainerRecord(Object writer, String dimKey, long packedPos, int slot, byte[] nbt) throws IOException {
        return ((RestoreWriter) writer).container(dimKey, packedPos, slot, nbt);
    }

    /** Cierra un RestoreWriter (typed como Object). */
    public void closeRestoreWriter(Object writer) {
        if (writer instanceof RestoreWriter) ((RestoreWriter) writer).close();
    }
}
