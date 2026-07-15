package dev.mksa.agent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T3 corte AG: primer aplicador transaccional REAL de demix (no un smoke).
 * Hasta este corte, Tier 3 solo tenia diagnostico (buildTxGate/demixSteps) y
 * pruebas de mecanismo aisladas (Tier3RealTargetBaseResetSmoke, corte P/T/Y):
 * ningun codigo real ejecutaba un disable/enable de un target Mixin. Este
 * corte lo hace, pero deliberadamente acotado a UN SOLO target -- el mismo
 * que corte Z confirmo con exito real bajo JBR21 (reset a base + rollback a
 * vivo, schema estable en ambos sentidos) y que ademas tiene cero co-owners
 * (unico mixin del target, sin replay de otros namespaces necesario).
 * Generalizar a mas targets o al namespace completo es trabajo futuro
 * explicito, no este corte (confirmado con el usuario).
 *
 * <p>Principio de diseno del usuario (guardado en memoria de proyecto):
 * "desactivar" un target NO es borrarlo -- el mod/target sigue existiendo,
 * se conoce toda su estructura, y por eso se puede suspender su contribucion
 * sin afectar al mundo. "Restaurar" no es revertir a una foto congelada del
 * momento del disable: debe reconciliar con los cambios reales que el mundo
 * sufrio mientras estaba desactivado. Para ESTE target puntual
 * (net.minecraft.class_1922, mixin BlockGetterMixin -- una vista de LECTURA
 * sobre BlockGetter) ese principio se satisface trivialmente: redefineClasses
 * (JVMTI), con o sin JBR21, jamas toca instancias vivas ni datos del mundo --
 * el mecanismo nunca escribe ni borra estado del mundo, asi que no hay nada
 * que reconciliar aqui. (Corte AH-fix: OJO, esto es distinto de decir que el
 * SCHEMA declarado -- metodos/campos/interfaces -- queda siempre estable.
 * JBR21 [-XX:+AllowEnhancedClassRedefinition] fue adoptado precisamente
 * porque SI permite cambiar ese schema en una redefinicion [confirmado corte
 * Z, agregar/quitar interfaz en class_1922], algo que JVMTI estandar
 * rechaza. Para class_1922 el schema declarado da la casualidad de quedar
 * estable porque su mixin no agrega/quita miembros [changesTotal=0] -- es un
 * hecho de ESE mixin, no una garantia general del mecanismo. Ver
 * verify_schema_matches_target_bytes_after_reset/_after_rollback: desde
 * corte AH-fix la verificacion compara el schema post-redefine contra los
 * bytes objetivo REALES, no contra un snapshot pre-redefine -- ese snapshot
 * era el invariante equivocado, ver Tier3MixinAudit.expectedDeclaredCounts.)
 * Un Tier 3 mod cuyos mixins SI tengan efectos visibles en el mundo
 * necesitara logica de reconciliacion real (analoga a tier1Enable/
 * restitucion NBT, corte 5b de Tier 1) en un corte futuro -- no esta
 * resuelto en general, solo trivialmente satisfecho para este target de
 * solo-lectura.
 *
 * <p>T3 corte AH: generaliza {@code ELIGIBLE_TARGETS} (retirado, ver corte
 * tender-seeking-fern) de un unico target a
 * los 4 targets de fabric-block-view-api-v2 con cero co-owners --
 * class_1922 (ya probado en corte AG), class_4538 (LevelReaderMixin, carga
 * organica confirmada), class_6850 y class_11791 (RenderRegionCacheMixin /
 * RenderSectionRegionMixin, nunca cargan organicamente en sesion tipica
 * pero eso ya lo maneja disable() de forma segura via TARGET_NOT_LOADED,
 * sin mutacion). El mismo principio de solo-lectura/sin reconciliacion del
 * parrafo anterior aplica a los 4 targets nuevos (todos son mixins de vista
 * sobre BlockGetter/LevelReader/cachés de render).
 *
 * <p>T3 corte AL: conecta {@link Tier3MixinReplay} (corte AK, replay
 * selectivo de mixins reusando el motor real de Mixin) para soportar
 * tambien los 2 targets co-owned de fabric-block-view-api-v2 --
 * class_853 (co-owner real de fabric-renderer-indigo) y class_2586
 * (BlockEntityMixin, co-owner de entity_texture_features, divergencia de
 * orden resuelta en corte AJ). Ver {@code ELIGIBLE_REPLAY_TARGETS} (retirado). A
 * diferencia de {@code ELIGIBLE_TARGETS} (reset a bytes base completos),
 * estos targets se desactivan reemplazando los bytes vivos por
 * base + mixins de TODOS los owners salvo {@code REPLAY_VICTIM_NS} --
 * preservando la contribucion real del/de los co-owner(s). enable()
 * restaura a los bytes vivos completos exactamente igual que para los
 * targets sin co-owner, sin logica adicional. Sigue diferida la
 * reconciliacion real para mixins con efectos visibles en el mundo (ningun
 * target Tier 3 conocido hoy los tiene -- ver parrafo anterior).
 *
 * <p>T3 corte AM: generaliza el registro plano (2 Set + 1 constante) a un
 * registro {@code target -> {mode, victimNs}} ({@code TARGETS}, retirado en
 * corte tender-seeking-fern -- ver {@link Tier3MixinAudit#classifyDemixTargets}),
 * y agrega el primer target Tier 3 con estado vivo real (chat_heads -- campos
 * @Unique chatheads$headData/chatheads$owner) junto con el primer aplicador
 * GRUPAL atomico ({@link #disableGroup}/{@link #enableGroup}). La apuesta de diseno:
 * Instrumentation.redefineClasses(ClassDefinition...) acepta un arreglo y la
 * JVM (JVMTI RedefineClasses) verifica TODAS las clases antes de aplicar
 * CUALQUIERA -- si el campo que desaparece (ej. class_303) y el unico mixin
 * que lo leia (ej. class_338, ChatComponentMixin) se redefinen en la MISMA
 * llamada atomica, nunca existe una ventana donde uno exista sin el otro. No
 * hace falta migrar estado de instancias vivas -- hace falta agrupar la
 * operacion correctamente. Los 15 targets reales de chat_heads (research de
 * este corte, ver memoria de proyecto) se suman al registro: 12 en modo
 * RESET (cero co-owners confirmados por auditoria real de bytecode contra
 * los otros 21 mods instalados, incluyendo los 3 que cargan estado real y
 * los 2 reverificados class_3872/class_11228) y 3 en modo REPLAY (co-owned
 * -- class_338 con Balm incluyendo colision de metodo real, class_634 con
 * Artifacts/Sodium/Trinkets/Immersive Aircraft, class_10538 con
 * entity_texture_features).
 *
 * <p>Reutiliza TxJournal (Tier 1 corte 5) tal cual -- mismo Op.disable/
 * Op.enable, TxReceipt, TxStep -- sin inventar un formato de receipt nuevo.
 * El campo TxReceipt.namespace lleva aqui el nombre dotted del target
 * (net.minecraft.class_1922), no un namespace de mod; es el mismo campo,
 * reutilizado con otro significado de string libre. disableGroup/enableGroup
 * usan el mismo TxReceipt con namespace="group:N_targets" -- un solo receipt
 * para toda la operacion atomica, no uno por target.
 */
final class Tier3DemixApply {

    /** T3 corte AM: los 15 targets de chat_heads, para que
     * t3.demixGroupDisable/t3.demixGroupEnable resuelvan el grupo
     * server-side a partir de {"namespace": "chat_heads"} -- el caller no
     * envia la lista de targets. */
    static final String[] CHAT_HEADS_TARGETS = new String[] {
            "net.minecraft.class_7471",
            "net.minecraft.class_7594",
            "net.minecraft.class_2535",
            "net.minecraft.class_1066",
            "net.minecraft.class_11878",
            "net.minecraft.class_4717$class_464",
            "net.minecraft.class_11879",
            "net.minecraft.class_11879$class_11880",
            "net.minecraft.class_3872",
            "net.minecraft.class_11228",
            "net.minecraft.class_338",
            "net.minecraft.class_634",
            "net.minecraft.class_10538",
    };

    /** Resuelve los targets de un namespace conocido para el grupo atomico.
     * null si el namespace no tiene un grupo registrado. */
    static String[] targetsForNamespace(String namespace) {
        if ("chat_heads".equals(namespace)) return CHAT_HEADS_TARGETS.clone();
        return null;
    }

    /** Registro de un target actualmente desactivado por este mecanismo:
     * que namespace lo desactivo, con que modo (reset_v1/replay_v1), que
     * bytes estaban VIVOS justo antes de aplicar el disable (para volver a
     * ellos en el enable normal o para un rollback automatico si el propio
     * disable termina en estado inconsistente -- ver §5) y que receipt de
     * TxJournal documenta la operacion. */
    static final class DisabledTargetRecord {
        final String target;
        final String mode;
        final byte[] preDisableLiveBytesSnapshot;
        /** Bytes REALMENTE instalados por el redefineClasses del disable (bytes
         * base o replay, segun mode) -- lo que esta activo AHORA mismo, mientras
         * el target sigue desactivado. Usado por enableGroup como bytes de
         * rollback si la verificacion de schema post-redefine del propio enable
         * no calza (§5): revertir "hacia adelante, de vuelta a este mismo estado
         * desactivado conocido", en vez de dejar el target en un schema a medio
         * camino sin nombre. */
        final byte[] installedDisabledBytesSnapshot;
        final long disabledAtReceiptId;

        DisabledTargetRecord(String target, String mode, byte[] preDisableLiveBytesSnapshot,
                byte[] installedDisabledBytesSnapshot, long disabledAtReceiptId) {
            this.target = target;
            this.mode = mode;
            this.preDisableLiveBytesSnapshot = preDisableLiveBytesSnapshot;
            this.installedDisabledBytesSnapshot = installedDisabledBytesSnapshot;
            this.disabledAtReceiptId = disabledAtReceiptId;
        }
    }

    /** target sin namespace conocido (camino singular disable()/enable(),
     * usado solo por el arnes de pruebas t3.demixApplyDisable/Enable en
     * Agent.java -- nunca es el camino end-to-end real, que siempre pasa
     * por disableGroup/enableGroup con un namespace real). */
    private static final String UNSCOPED_NS = "";

    /** Estado in-process: que targets estan actualmente en bytes BASE o
     * REPLAY (des-mixineados) via este mecanismo, indexado por el namespace
     * que los desactivo. Ausente = vivo (estado normal, mixin aplicado).
     * Namespace-indexado (en vez de un Map<String,Boolean> plano) porque
     * enableOne/enableGroup necesitan saber, dado un namespace, exactamente
     * que targets y que bytes restaurar -- sin volver a escanear disco, que
     * pudo cambiar mientras el mod estaba desactivado. El estado real vivo
     * de la JVM (este registro) es la fuente de verdad, no los jars en
     * disco. */
    private static final ConcurrentHashMap<String, List<DisabledTargetRecord>> DISABLED_BY_NS =
            new ConcurrentHashMap<String, List<DisabledTargetRecord>>();

    /** true si target esta desactivado por este mecanismo, en cualquier
     * namespace -- los nombres de clase target son globalmente unicos, asi
     * que no hay riesgo de colision entre namespaces. Reemplaza
     * DISABLED.get(target)==TRUE. */
    private static boolean isDisabledAnywhere(String target) {
        for (List<DisabledTargetRecord> records : DISABLED_BY_NS.values()) {
            for (DisabledTargetRecord r : records) {
                if (r.target.equals(target)) return true;
            }
        }
        return false;
    }

    /** Busca el registro de un target desactivado, en cualquier namespace.
     * null si no esta desactivado. */
    private static DisabledTargetRecord findDisabledRecord(String target) {
        for (List<DisabledTargetRecord> records : DISABLED_BY_NS.values()) {
            for (DisabledTargetRecord r : records) {
                if (r.target.equals(target)) return r;
            }
        }
        return null;
    }

    /** Registra target como desactivado bajo namespace (null -> UNSCOPED_NS).
     * preDisableLiveBytes son los bytes vivos justo antes de este disable --
     * lo que un enable normal (o un rollback automatico de un disable fallido,
     * §5) debe reinstalar. */
    private static synchronized void markDisabled(String namespace, String target, String mode,
            byte[] preDisableLiveBytes, byte[] installedDisabledBytes, long receiptId) {
        String key = namespace == null ? UNSCOPED_NS : namespace;
        List<DisabledTargetRecord> records = DISABLED_BY_NS.get(key);
        if (records == null) {
            records = new ArrayList<DisabledTargetRecord>();
            DISABLED_BY_NS.put(key, records);
        }
        // Reemplaza cualquier registro previo del mismo target (no deberia
        // existir uno vivo -- disable() ya rechaza doble-disable -- pero
        // evita duplicados silenciosos si algun caller se salta ese chequeo).
        for (int i = records.size() - 1; i >= 0; i--) {
            if (records.get(i).target.equals(target)) records.remove(i);
        }
        records.add(new DisabledTargetRecord(target, mode, preDisableLiveBytes, installedDisabledBytes, receiptId));
    }

    /** Quita target del registro de desactivados, en cualquier namespace que
     * lo tenga. Devuelve el registro quitado, o null si no estaba. */
    private static synchronized DisabledTargetRecord clearDisabled(String target) {
        for (List<DisabledTargetRecord> records : DISABLED_BY_NS.values()) {
            for (int i = records.size() - 1; i >= 0; i--) {
                DisabledTargetRecord r = records.get(i);
                if (r.target.equals(target)) {
                    records.remove(i);
                    return r;
                }
            }
        }
        return null;
    }

    /** Registros actualmente desactivados para un namespace (lista vacia si
     * ninguno). Usado por enableOne para saber que targets/modos/bytes
     * restaurar sin volver a escanear disco. */
    static List<DisabledTargetRecord> disabledRecordsForNamespace(String namespace) {
        List<DisabledTargetRecord> records = DISABLED_BY_NS.get(namespace == null ? UNSCOPED_NS : namespace);
        return records == null ? Collections.<DisabledTargetRecord>emptyList()
                : new ArrayList<DisabledTargetRecord>(records);
    }

    /**
     * T3 corte AM (fix rendererPools) -> corte Parte 2 (clasificacion automatica):
     * campos de instancia que un RESET (o un REPLAY con co-owners no replayables)
     * hace desaparecer y reaparecer con valor por defecto, porque redefineClasses()
     * nunca vuelve a correr el constructor/inicializador sobre una instancia ya
     * viva. class_11228 (GuiRenderer) perdia asi "rendererPools" (@Unique de
     * fabric-rendering-v1) al volver de RESET a bytes vivos -- confirmado real,
     * corte AM, crash NPE post-enable. La identidad del objeto SI sobrevive
     * redefineClasses (solo cambia forma/bytecode de la clase, no que instancia es
     * "la" instancia), asi que capturar el valor por reflexion antes del disable y
     * restaurarlo despues del enable alcanza, sin tocar la mecanica de bytecode.
     * Corte Parte 2 reemplazo el registro hardcodeado (un solo target tipeado a
     * mano) por Tier3MixinAudit.preserveFieldsForTarget -- clasificacion automatica
     * por escaneo de bytecode (safe/preserve/unknown), cubre todos los mods sin
     * trabajo manual por namespace.
     */

    /** target#field -> valor capturado antes del disable, pendiente de
     * restaurar despues del enable correspondiente. */
    private static final ConcurrentHashMap<String, Object> FIELD_SNAPSHOT =
            new ConcurrentHashMap<String, Object>();

    /** Sondea y guarda (si aplica) los campos que Tier3MixinAudit clasifico como
     * "preserve" para este namespace/target, ANTES de que redefineClasses() corra.
     * No lanza -- cualquier falla se agrega como advertencia no fatal a
     * warningsOut (la redefinicion atomica de bytecode ya funciona sola; esto es
     * una mejora encima, no un requisito). cls debe ser la Class<?> ya resuelta
     * via resolveLoadedClass (mismo classloader real que cargo la instancia --
     * Class.forName con el classloader del agente resolveria la clase
     * equivocada). namespace puede ser null en callers viejos -- en ese caso el
     * snapshot se salta por completo, mismo comportamiento best-effort. */
    private static void snapshotPreservedFields(String namespace, String target, Class<?> cls,
                                                List<String> warningsOut, List<String> preservedOut) {
        String[] fields = namespace == null ? null
                : Tier3MixinAudit.preserveFieldsForTarget(namespace, target).get(target);
        if (fields == null || fields.length == 0 || cls == null) return;
        Object instance = Tier3InstanceLocator.locate(cls);
        if (instance == null) {
            warningsOut.add(target + ": no se pudo localizar una instancia viva (snapshot omitido, campos "
                    + Arrays.toString(fields) + ")");
            return;
        }
        for (String fieldName : fields) {
            try {
                Field f = findDeclaredField(instance.getClass(), fieldName);
                f.setAccessible(true);
                Object value = f.get(instance);
                FIELD_SNAPSHOT.put(target + "#" + fieldName, value);
                preservedOut.add(target + "#" + fieldName);
            } catch (Throwable t) {
                warningsOut.add(target + "#" + fieldName + ": fallo el snapshot ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            }
        }
    }

    /** Simetrico a snapshotPreservedFields -- corre DESPUES de que
     * redefineClasses() ya restauro los bytes vivos, y reescribe el valor
     * capturado sobre la MISMA instancia (identidad preservada por
     * redefineClasses). No fatal por el mismo motivo que el snapshot. */
    private static void restorePreservedFields(String namespace, String target, Class<?> cls,
                                               List<String> warningsOut, List<String> preservedOut) {
        String[] fields = namespace == null ? null
                : Tier3MixinAudit.preserveFieldsForTarget(namespace, target).get(target);
        if (fields == null || fields.length == 0 || cls == null) return;
        Object instance = Tier3InstanceLocator.locate(cls);
        if (instance == null) {
            warningsOut.add(target + ": no se pudo relocalizar la instancia para restaurar campos "
                    + Arrays.toString(fields));
            return;
        }
        for (String fieldName : fields) {
            String key = target + "#" + fieldName;
            if (!FIELD_SNAPSHOT.containsKey(key)) {
                warningsOut.add(key + ": no habia snapshot previo, nada que restaurar");
                continue;
            }
            try {
                Object value = FIELD_SNAPSHOT.remove(key);
                Field f = findDeclaredField(instance.getClass(), fieldName);
                f.setAccessible(true);
                f.set(instance, value);
                preservedOut.add(key);
            } catch (Throwable t) {
                warningsOut.add(key + ": fallo la restauracion ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            }
        }
    }

    private static Field findDeclaredField(Class<?> start, String name) throws NoSuchFieldException {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // sigue subiendo por la jerarquia
            }
        }
        throw new NoSuchFieldException(name);
    }

    static Map<String, Object> disable(Instrumentation inst, String target, String namespace) {
        if (target == null || target.isEmpty()) {
            return fail("BAD_PARAMS", "t3.demixApplyDisable requiere target");
        }
        if (namespace == null || namespace.isEmpty()) {
            return fail("BAD_PARAMS", "t3.demixApplyDisable requiere namespace");
        }
        if (inst == null) {
            return fail("INSTRUMENTATION_MISSING", "Instrumentation no disponible");
        }
        Tier3MixinAudit.TargetClassification classification = Tier3MixinAudit
                .classifyDemixTargets(namespace, Collections.singleton(target), inst)
                .get(target);
        if (classification == null || classification.mode == Tier3MixinAudit.DemixMode.BLOCKED) {
            return fail("TARGET_NOT_ELIGIBLE",
                    "target fuera del alcance probado o bloqueado por auditoria en vivo (razones: "
                            + (classification == null ? "sin clasificar" : classification.blockedReasons) + ")");
        }
        if (isDisabledAnywhere(target)) {
            return fail("ALREADY_DISABLED", target + " ya esta desactivado (bytes base aplicados)");
        }

        Class<?> cls = resolveLoadedClass(inst, target);
        if (cls == null) {
            return fail("TARGET_NOT_LOADED", target + " no esta cargado en esta JVM");
        }

        boolean redefineSupported = inst.isRedefineClassesSupported();
        boolean modifiable = false;
        try {
            modifiable = inst.isModifiableClass(cls);
        } catch (Throwable ignored) {
        }
        if (!redefineSupported) {
            return fail("REDEFINE_CLASSES_NOT_SUPPORTED", "Esta JVM no soporta redefineClasses()");
        }
        if (!modifiable) {
            return fail("TARGET_NOT_MODIFIABLE", target + " no es modificable via Instrumentation");
        }

        if (classification.mode == Tier3MixinAudit.DemixMode.REPLAY) {
            return disableViaReplay(inst, cls, target, namespace);
        }
        return disableViaBaseReset(inst, cls, target);
    }

    /** Camino original (corte AG/AH) -- reset completo a bytes base para
     * targets sin co-owner. Sin cambios de logica en corte AL. */
    private static Map<String, Object> disableViaBaseReset(Instrumentation inst, Class<?> cls, String target) {
        byte[] liveBytes = Tier3LiveCapture.get(target);
        byte[] baseBytes = Tier3MixinAudit.baseBytes(target);
        if (liveBytes == null) {
            return fail("LIVE_BYTES_MISSING", "Tier3LiveCapture no tiene bytes vivos capturados de " + target);
        }
        if (baseBytes == null) {
            return fail("BASE_BYTES_MISSING", "No hay bytes base (pre-mixin) capturados de " + target);
        }
        String liveSha256 = sha256(liveBytes);
        String baseSha256 = sha256(baseBytes);
        if (baseSha256.equals(liveSha256)) {
            return fail("NOTHING_TO_DISABLE", "base y vivo son identicos para " + target + " -- nada que resetear");
        }

        long txId = TxJournal.INSTANCE.nextId();
        TxJournal.TxReceipt rcpt = new TxJournal.TxReceipt(
                txId, TxJournal.Op.disable, target, Ledger.INSTANCE.hashHex());
        rcpt.addStep("resolve_loaded_class", TxJournal.Status.OK, 0L,
                detail("Class<?> real resuelta via Instrumentation.getAllLoadedClasses()."));
        rcpt.addStep("read_base_and_live_bytes", TxJournal.Status.OK, 0L,
                detail2("baseSha256", baseSha256, "liveSha256", liveSha256));

        int preMethods = cls.getDeclaredMethods().length;
        int preFields = cls.getDeclaredFields().length;
        // Corte AH-fix: lo que hay que verificar es que el schema post-redefine
        // coincida con los bytes BASE que se le pidio al JVM instalar -- no que
        // no haya cambiado respecto al estado previo. Ese invariante (pre==mid)
        // solo se sostiene para mixins sin miembros declarados agregados/quitados
        // (changesTotal=0, ej. class_1922); es falso en general (ej. class_4538,
        // que agrega 2 metodos declarados -- quitarlos al resetear a base es el
        // resultado CORRECTO, no una falla).
        int[] expected = Tier3MixinAudit.expectedDeclaredCounts(baseBytes);

        try {
            redefine(inst, cls, baseBytes);
            rcpt.addStep("redefine_reset_to_base_on_real_target", TxJournal.Status.OK, 0L,
                    detail("redefineClasses() real sobre " + target + " con los bytes BASE (pre-mixin)."));

            int midMethods = cls.getDeclaredMethods().length;
            int midFields = cls.getDeclaredFields().length;
            boolean schemaVerifiable = expected != null;
            boolean schemaMatchesTarget = schemaVerifiable
                    && midMethods == expected[0] && midFields == expected[1];
            rcpt.addStep("verify_schema_matches_target_bytes_after_reset",
                    !schemaVerifiable ? TxJournal.Status.OK
                            : schemaMatchesTarget ? TxJournal.Status.OK : TxJournal.Status.FAILED,
                    0L,
                    schemaDetail(preMethods, preFields, midMethods, midFields, expected));

            // El redefine ya tuvo exito (no lanzo) -- el estado del target SI
            // cambio, independientemente de si la verificacion de schema cuadro.
            markDisabled(null, target, "reset_v1", liveBytes, baseBytes, txId);
            rcpt.seal(schemaVerifiable && !schemaMatchesTarget
                    ? TxJournal.Status.PARTIAL
                    : TxJournal.Status.OK);
            TxJournal.INSTANCE.complete(rcpt);
            Map<String, Object> out = rcpt.toMap();
            out.put("ok", Boolean.TRUE);
            out.put("baseSha256", baseSha256);
            out.put("liveSha256", liveSha256);
            return out;
        } catch (Throwable t) {
            // Especificacion JVMTI: si redefineClasses tira excepcion NO aplica
            // ningun cambio -- NO se marca desactivado.
            rcpt.addStep("redefine_reset_to_base_on_real_target", TxJournal.Status.FAILED, 0L,
                    detail(t.getClass().getSimpleName() + ": " + t.getMessage()));
            rcpt.recordError("redefine_reset_to_base_on_real_target", t);
            rcpt.seal(TxJournal.Status.FAILED);
            TxJournal.INSTANCE.complete(rcpt);
            Map<String, Object> out = rcpt.toMap();
            out.put("ok", Boolean.FALSE);
            out.put("code", "REDEFINE_FAILED");
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            return out;
        }
    }

    /** T3 corte AL: desactivacion via replay selectivo (Tier3MixinReplay) para
     * targets co-owned -- reemplaza bytes vivos por base + mixins de todos los
     * owners salvo {@code victimNs}, preservando la contribucion real del/de
     * los co-owner(s). A diferencia de disableViaBaseReset, no compara
     * base-vs-live (siempre difieren: hay al menos 2 owners de mixins sobre
     * este target) -- la verificacion real es que el exclude-set aplicado por
     * el replay coincida exactamente con el solicitado. T3 corte AM: victimNs
     * ya no es la constante global REPLAY_VICTIM_NS. T3 corte
     * tender-seeking-fern: victimNs es simplemente el namespace del grupo
     * (disableGroup/disable ya lo exigen como parametro obligatorio) -- la
     * vieja entrada TARGETS.victimNs siempre coincidia con el namespace del
     * grupo que la invocaba, nunca un namespace distinto, asi que no hacia
     * falta un campo separado. */
    private static Map<String, Object> disableViaReplay(Instrumentation inst, Class<?> cls, String target,
            String victimNs) {
        byte[] liveBytes = Tier3LiveCapture.get(target);
        byte[] baseBytes = Tier3MixinAudit.baseBytes(target);
        if (liveBytes == null) {
            return fail("LIVE_BYTES_MISSING", "Tier3LiveCapture no tiene bytes vivos capturados de " + target);
        }
        if (baseBytes == null) {
            return fail("BASE_BYTES_MISSING", "No hay bytes base (pre-mixin) capturados de " + target);
        }
        String liveSha256 = sha256(liveBytes);
        String baseSha256 = sha256(baseBytes);

        Set<String> excludeSet = Tier3MixinAudit.mixinClassNamesForTarget(victimNs, target, Boot.modRoots);
        if (excludeSet.isEmpty()) {
            return fail("REPLAY_VICTIM_MIXINS_NOT_FOUND",
                    "No se encontraron mixins de " + victimNs + " declarando " + target + " como target.");
        }

        long txId = TxJournal.INSTANCE.nextId();
        TxJournal.TxReceipt rcpt = new TxJournal.TxReceipt(
                txId, TxJournal.Op.disable, target, Ledger.INSTANCE.hashHex());
        rcpt.addStep("resolve_loaded_class", TxJournal.Status.OK, 0L,
                detail("Class<?> real resuelta via Instrumentation.getAllLoadedClasses()."));
        rcpt.addStep("resolve_replay_exclude_set", TxJournal.Status.OK, 0L,
                detail2("victimNs", victimNs, "excludeMixinClassNames", excludeSet));

        Tier3MixinReplay.Outcome outcome = Tier3MixinReplay.replay(cls, target, baseBytes, excludeSet);
        if (!outcome.ok) {
            rcpt.addStep("mixin_replay", TxJournal.Status.FAILED, 0L,
                    detail("replay fallo en paso " + outcome.failedStep + ": " + outcome.error
                            + " " + outcome.message));
            rcpt.seal(TxJournal.Status.FAILED);
            TxJournal.INSTANCE.complete(rcpt);
            Map<String, Object> out = rcpt.toMap();
            out.put("ok", Boolean.FALSE);
            out.put("code", "MIXIN_REPLAY_FAILED");
            out.put("error", "replay fallo en paso " + outcome.failedStep + ": " + outcome.error);
            return out;
        }
        rcpt.addStep("mixin_replay", TxJournal.Status.OK, 0L,
                detail2("allMixinClassNames", outcome.allMixinClassNames,
                        "excludedMixinClassNames", outcome.excludedMixinClassNames));

        Set<String> actualExcluded = new LinkedHashSet<String>(outcome.excludedMixinClassNames);
        boolean excludedAsRequested = actualExcluded.containsAll(excludeSet) && excludeSet.containsAll(actualExcluded);
        rcpt.addStep("verify_excluded_set_matches_requested",
                excludedAsRequested ? TxJournal.Status.OK : TxJournal.Status.FAILED, 0L,
                detail2("requested", excludeSet, "actual", outcome.excludedMixinClassNames));
        if (!excludedAsRequested) {
            rcpt.seal(TxJournal.Status.FAILED);
            TxJournal.INSTANCE.complete(rcpt);
            Map<String, Object> out = rcpt.toMap();
            out.put("ok", Boolean.FALSE);
            out.put("code", "REPLAY_EXCLUDE_SET_MISMATCH");
            out.put("error", "el exclude-set real del replay no coincide con el solicitado");
            return out;
        }

        int preMethods = cls.getDeclaredMethods().length;
        int preFields = cls.getDeclaredFields().length;
        int[] expected = Tier3MixinAudit.expectedDeclaredCounts(outcome.bytes);
        String replaySha256 = sha256(outcome.bytes);

        try {
            redefine(inst, cls, outcome.bytes);
            rcpt.addStep("redefine_reset_to_replayed_bytes_on_real_target", TxJournal.Status.OK, 0L,
                    detail("redefineClasses() real sobre " + target
                            + " con bytes = base + mixins no-victima (model=replay_v1)."));

            int midMethods = cls.getDeclaredMethods().length;
            int midFields = cls.getDeclaredFields().length;
            boolean schemaVerifiable = expected != null;
            boolean schemaMatchesTarget = schemaVerifiable
                    && midMethods == expected[0] && midFields == expected[1];
            rcpt.addStep("verify_schema_matches_target_bytes_after_reset",
                    !schemaVerifiable ? TxJournal.Status.OK
                            : schemaMatchesTarget ? TxJournal.Status.OK : TxJournal.Status.FAILED,
                    0L,
                    schemaDetail(preMethods, preFields, midMethods, midFields, expected));

            markDisabled(victimNs, target, "replay_v1", liveBytes, outcome.bytes, txId);
            rcpt.seal(schemaVerifiable && !schemaMatchesTarget
                    ? TxJournal.Status.PARTIAL
                    : TxJournal.Status.OK);
            TxJournal.INSTANCE.complete(rcpt);
            Map<String, Object> out = rcpt.toMap();
            out.put("ok", Boolean.TRUE);
            out.put("model", "replay_v1");
            out.put("baseSha256", baseSha256);
            out.put("liveSha256", liveSha256);
            out.put("replaySha256", replaySha256);
            out.put("excludedMixinClassNames", outcome.excludedMixinClassNames);
            out.put("appliedMixinClassNames", outcome.appliedMixinClassNames);
            return out;
        } catch (Throwable t) {
            // Especificacion JVMTI: si redefineClasses tira excepcion NO aplica
            // ningun cambio -- NO se marca desactivado.
            rcpt.addStep("redefine_reset_to_replayed_bytes_on_real_target", TxJournal.Status.FAILED, 0L,
                    detail(t.getClass().getSimpleName() + ": " + t.getMessage()));
            rcpt.recordError("redefine_reset_to_replayed_bytes_on_real_target", t);
            rcpt.seal(TxJournal.Status.FAILED);
            TxJournal.INSTANCE.complete(rcpt);
            Map<String, Object> out = rcpt.toMap();
            out.put("ok", Boolean.FALSE);
            out.put("code", "REDEFINE_FAILED");
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            return out;
        }
    }

    static Map<String, Object> enable(Instrumentation inst, String target) {
        if (target == null || target.isEmpty()) {
            return fail("BAD_PARAMS", "t3.demixApplyEnable requiere target");
        }
        if (inst == null) {
            return fail("INSTRUMENTATION_MISSING", "Instrumentation no disponible");
        }
        if (!isDisabledAnywhere(target)) {
            return fail("NOT_DISABLED", target + " no esta desactivado por este mecanismo -- nada que restaurar");
        }

        Class<?> cls = resolveLoadedClass(inst, target);
        if (cls == null) {
            return fail("TARGET_NOT_LOADED", target + " no esta cargado en esta JVM");
        }

        byte[] liveBytes = Tier3LiveCapture.get(target);
        if (liveBytes == null) {
            return fail("LIVE_BYTES_MISSING", "Tier3LiveCapture no tiene bytes vivos capturados de " + target);
        }
        String liveSha256 = sha256(liveBytes);

        long txId = TxJournal.INSTANCE.nextId();
        TxJournal.TxReceipt rcpt = new TxJournal.TxReceipt(
                txId, TxJournal.Op.enable, target, Ledger.INSTANCE.hashHex());
        rcpt.addStep("resolve_loaded_class", TxJournal.Status.OK, 0L,
                detail("Class<?> real resuelta via Instrumentation.getAllLoadedClasses()."));

        int preMethods = cls.getDeclaredMethods().length;
        int preFields = cls.getDeclaredFields().length;
        // Corte AH-fix: mismo razonamiento que disable() -- verificar contra los
        // bytes vivos que se le pidio al JVM instalar, no contra el snapshot
        // pre-redefine (invariante equivocado en general, ver Javadoc de clase).
        int[] expected = Tier3MixinAudit.expectedDeclaredCounts(liveBytes);

        try {
            redefine(inst, cls, liveBytes);
            rcpt.addStep("redefine_rollback_to_live_on_real_target", TxJournal.Status.OK, 0L,
                    detail("redefineClasses() real de vuelta a los bytes vivos originales sobre " + target + "."));

            int postMethods = cls.getDeclaredMethods().length;
            int postFields = cls.getDeclaredFields().length;
            boolean schemaVerifiable = expected != null;
            boolean schemaMatchesTarget = schemaVerifiable
                    && postMethods == expected[0] && postFields == expected[1];
            rcpt.addStep("verify_schema_matches_target_bytes_after_rollback",
                    !schemaVerifiable ? TxJournal.Status.OK
                            : schemaMatchesTarget ? TxJournal.Status.OK : TxJournal.Status.FAILED,
                    0L,
                    schemaDetail(preMethods, preFields, postMethods, postFields, expected));

            // El redefine ya tuvo exito (no lanzo) -- el estado del target SI
            // volvio a vivo, independientemente de si la verificacion de schema cuadro.
            clearDisabled(target);
            rcpt.seal(schemaVerifiable && !schemaMatchesTarget
                    ? TxJournal.Status.PARTIAL
                    : TxJournal.Status.OK);
            TxJournal.INSTANCE.complete(rcpt);
            Map<String, Object> out = rcpt.toMap();
            out.put("ok", Boolean.TRUE);
            out.put("liveSha256", liveSha256);
            return out;
        } catch (Throwable t) {
            // El registro de desactivado queda intacto: sigue desactivado, el
            // usuario puede reintentar enable.
            rcpt.addStep("redefine_rollback_to_live_on_real_target", TxJournal.Status.FAILED, 0L,
                    detail(t.getClass().getSimpleName() + ": " + t.getMessage()));
            rcpt.recordError("redefine_rollback_to_live_on_real_target", t);
            rcpt.seal(TxJournal.Status.FAILED);
            TxJournal.INSTANCE.complete(rcpt);
            Map<String, Object> out = rcpt.toMap();
            out.put("ok", Boolean.FALSE);
            out.put("code", "REDEFINE_FAILED");
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            return out;
        }
    }

    /** Resultado de calcular (sin aplicar) los bytes finales de un target para
     * disableGroup -- separa "calcular" de "redefinir" para poder abortar sin
     * tocar la JVM si CUALQUIER target del grupo falla al calcular. */
    private static final class ByteResult {
        final boolean ok;
        final byte[] bytes;
        final String code;
        final String error;
        final Map<String, Object> detail;

        private ByteResult(boolean ok, byte[] bytes, String code, String error, Map<String, Object> detail) {
            this.ok = ok;
            this.bytes = bytes;
            this.code = code;
            this.error = error;
            this.detail = detail;
        }

        static ByteResult ok(byte[] bytes, Map<String, Object> detail) {
            return new ByteResult(true, bytes, null, null, detail);
        }

        static ByteResult fail(String code, String error) {
            return new ByteResult(false, null, code, error, null);
        }
    }

    private static ByteResult computeDisableBytes(String target, Class<?> cls,
            Tier3MixinAudit.DemixMode mode, String namespace) {
        if (mode == Tier3MixinAudit.DemixMode.REPLAY) {
            return computeReplayBytes(target, cls, namespace);
        }
        return computeResetBytes(target);
    }

    private static ByteResult computeResetBytes(String target) {
        byte[] liveBytes = Tier3LiveCapture.get(target);
        byte[] baseBytes = Tier3MixinAudit.baseBytes(target);
        if (liveBytes == null) {
            return ByteResult.fail("LIVE_BYTES_MISSING", "Tier3LiveCapture no tiene bytes vivos capturados de " + target);
        }
        if (baseBytes == null) {
            return ByteResult.fail("BASE_BYTES_MISSING", "No hay bytes base (pre-mixin) capturados de " + target);
        }
        String liveSha256 = sha256(liveBytes);
        String baseSha256 = sha256(baseBytes);
        if (baseSha256.equals(liveSha256)) {
            return ByteResult.fail("NOTHING_TO_DISABLE", "base y vivo son identicos para " + target + " -- nada que resetear");
        }
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("model", "reset_v1");
        detail.put("baseSha256", baseSha256);
        detail.put("liveSha256", liveSha256);
        return ByteResult.ok(baseBytes, detail);
    }

    private static ByteResult computeReplayBytes(String target, Class<?> cls, String victimNs) {
        byte[] liveBytes = Tier3LiveCapture.get(target);
        byte[] baseBytes = Tier3MixinAudit.baseBytes(target);
        if (liveBytes == null) {
            return ByteResult.fail("LIVE_BYTES_MISSING", "Tier3LiveCapture no tiene bytes vivos capturados de " + target);
        }
        if (baseBytes == null) {
            return ByteResult.fail("BASE_BYTES_MISSING", "No hay bytes base (pre-mixin) capturados de " + target);
        }
        String liveSha256 = sha256(liveBytes);
        String baseSha256 = sha256(baseBytes);

        Set<String> excludeSet = Tier3MixinAudit.mixinClassNamesForTarget(victimNs, target, Boot.modRoots);
        if (excludeSet.isEmpty()) {
            return ByteResult.fail("REPLAY_VICTIM_MIXINS_NOT_FOUND",
                    "No se encontraron mixins de " + victimNs + " declarando " + target + " como target.");
        }

        Tier3MixinReplay.Outcome outcome = Tier3MixinReplay.replay(cls, target, baseBytes, excludeSet);
        if (!outcome.ok) {
            return ByteResult.fail("MIXIN_REPLAY_FAILED",
                    "replay fallo en paso " + outcome.failedStep + ": " + outcome.error + " " + outcome.message);
        }
        Set<String> actualExcluded = new LinkedHashSet<String>(outcome.excludedMixinClassNames);
        boolean excludedAsRequested = actualExcluded.containsAll(excludeSet) && excludeSet.containsAll(actualExcluded);
        if (!excludedAsRequested) {
            return ByteResult.fail("REPLAY_EXCLUDE_SET_MISMATCH", "el exclude-set real del replay no coincide con el solicitado");
        }
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("model", "replay_v1");
        detail.put("victimNs", victimNs);
        detail.put("baseSha256", baseSha256);
        detail.put("liveSha256", liveSha256);
        detail.put("replaySha256", sha256(outcome.bytes));
        detail.put("excludedMixinClassNames", outcome.excludedMixinClassNames);
        detail.put("appliedMixinClassNames", outcome.appliedMixinClassNames);
        return ByteResult.ok(outcome.bytes, detail);
    }

    /**
     * T3 corte AM: aplicador GRUPAL atomico -- la pieza central de este corte.
     * Calcula los bytes finales de TODOS los targets primero (computeDisableBytes,
     * sin tocar la JVM); si CUALQUIERA falla, aborta sin llamar redefineClasses
     * ni una sola vez (cero mutacion). Si todos calculan bien, arma un unico
     * ClassDefinition[] y llama Instrumentation.redefineClasses(defs) UNA vez --
     * JVMTI verifica TODAS las clases antes de aplicar CUALQUIERA (todo o nada),
     * asi que un campo que desaparece (ej. class_303) y el unico mixin que lo leia
     * (ej. class_338) se redefinen en el mismo instante atomico, sin ventana
     * intermedia inconsistente. Un solo TxReceipt cubre todo el grupo.
     *
     * @param namespace  namespace de mod dueño del grupo (e.g. "chat_heads"), usado
     *     SOLO para el veto/rebuild de pestaña de inventario creativo (corte tabs
     *     universal) -- no participa de la resolucion de targets/bytes, que sigue
     *     siendo responsabilidad exclusiva de {@code targets}. Puede ser null (compat
     *     con callers viejos que no lo tengan a mano): en ese caso el paso de pestaña
     *     se salta, mismo espiritu best-effort que el resto de estos pasos.
     * @param expectedModes  modo (RESET/REPLAY) que el caller vio en un gate previo
     *     (Tier3MixinAudit.plan -> targetPlans[].mode), tipicamente segundos u horas
     *     antes de esta llamada -- la ventana TOCTOU real entre "se le mostro al
     *     usuario que esto era seguro" y "se ejecuta de verdad". Este metodo NUNCA
     *     confia ciegamente en el mapa recibido: siempre recalcula la clasificacion
     *     en vivo ({@code classification}, abajo) y, si expectedModes no es null,
     *     exige que coincida exactamente antes de tocar un solo byte de bytecode --
     *     si el jar de un mod cambio en disco, se cargo/descargo una clase, o
     *     cualquier otra deriva entre el gate y este apply, aborta con
     *     TARGET_MODE_STALE en vez de aplicar una decision que ya no es la que se
     *     audito. Puede ser null (callers sin gate previo, p.ej. arneses de prueba
     *     directos): en ese caso se omite la comparacion y se procede solo con la
     *     clasificacion recien calculada (mismo comportamiento que antes de esta
     *     verificacion, sin regresion).
     */
    static Map<String, Object> disableGroup(Instrumentation inst, String[] targets, String namespace,
            Map<String, String> expectedModes) {
        if (targets == null || targets.length == 0) {
            return fail("BAD_PARAMS", "t3.demixGroupDisable requiere una lista de targets no vacia");
        }
        if (namespace == null || namespace.isEmpty()) {
            return fail("BAD_PARAMS", "t3.demixGroupDisable requiere namespace");
        }
        if (inst == null) {
            return fail("INSTRUMENTATION_MISSING", "Instrumentation no disponible");
        }
        if (!inst.isRedefineClassesSupported()) {
            return fail("REDEFINE_CLASSES_NOT_SUPPORTED", "Esta JVM no soporta redefineClasses()");
        }

        List<String> targetList = new ArrayList<String>(Arrays.asList(targets));
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String target : targetList) {
            if (target == null || target.isEmpty()) {
                return fail("BAD_PARAMS", "target vacio en la lista del grupo");
            }
            if (!seen.add(target)) {
                return fail("BAD_PARAMS", "target duplicado en la lista del grupo: " + target);
            }
            if (isDisabledAnywhere(target)) {
                return fail("ALREADY_DISABLED", target + " ya esta desactivado (bytes base aplicados)");
            }
        }

        // T3 corte tender-seeking-fern (tarea #31): clasificacion en vivo, no
        // whitelist estatica -- una sola pasada de auditoria de bytecode cubre
        // todos los targets del grupo, generalizando a CUALQUIER namespace.
        Map<String, Tier3MixinAudit.TargetClassification> classification =
                Tier3MixinAudit.classifyDemixTargets(namespace, seen, inst);
        for (String target : targetList) {
            Tier3MixinAudit.TargetClassification tc = classification.get(target);
            if (tc == null || tc.mode == Tier3MixinAudit.DemixMode.BLOCKED) {
                return fail("TARGET_NOT_ELIGIBLE",
                        target + " fuera del alcance probado o bloqueado por auditoria en vivo (razones: "
                                + (tc == null ? "sin clasificar" : tc.blockedReasons) + ")");
            }
        }

        // T3 corte tender-seeking-fern (tarea #32): anti-TOCTOU -- si el caller trae
        // el modo que vio en un gate previo, debe coincidir EXACTO con lo que
        // acabamos de reclasificar en vivo. Aborta antes de resolver una sola
        // Class<?> o tocar bytecode si algo derivo entre el gate y este apply.
        if (expectedModes != null) {
            List<String> staleTargets = new ArrayList<String>();
            Map<String, Object> staleDetail = new LinkedHashMap<String, Object>();
            for (String target : targetList) {
                String expected = expectedModes.get(target);
                String actual = classification.get(target).mode.name();
                if (expected == null || !expected.equals(actual)) {
                    staleTargets.add(target);
                    Map<String, Object> d = new LinkedHashMap<String, Object>();
                    d.put("expected", expected == null ? "ausente_en_gate" : expected);
                    d.put("actual", actual);
                    staleDetail.put(target, d);
                }
            }
            if (!staleTargets.isEmpty()) {
                Map<String, Object> out = fail("TARGET_MODE_STALE",
                        "el modo decidido en el gate ya no coincide con la clasificacion en vivo para "
                                + staleTargets + " -- el estado del mod pudo cambiar entre auditar y aplicar "
                                + "(jar modificado en disco, clase cargada/descargada, etc). Nada se aplico.");
                out.put("staleTargets", staleTargets);
                out.put("staleDetail", staleDetail);
                return out;
            }
        }

        Map<String, Class<?>> classes = new LinkedHashMap<String, Class<?>>();
        for (String target : targetList) {
            Class<?> cls = resolveLoadedClass(inst, target);
            if (cls == null) {
                return fail("TARGET_NOT_LOADED", target + " no esta cargado en esta JVM");
            }
            boolean modifiable = false;
            try {
                modifiable = inst.isModifiableClass(cls);
            } catch (Throwable ignored) {
            }
            if (!modifiable) {
                return fail("TARGET_NOT_MODIFIABLE", target + " no es modificable via Instrumentation");
            }
            classes.put(target, cls);
        }

        long txId = TxJournal.INSTANCE.nextId();
        TxJournal.TxReceipt rcpt = new TxJournal.TxReceipt(
                txId, TxJournal.Op.disable, "group:" + targetList.size() + "_targets", Ledger.INSTANCE.hashHex());

        // Corte tabs universal: mismo paso best-effort que InProcess.txStepTabVeto
        // (Tier 1) -- vacia la pestaña de inventario creativo del namespace y fuerza
        // el rebuild (cierra el hueco via ItemGroups.updateEntries doble). Nunca
        // bloquea la secuencia de redefine ya probada.
        txStepTabVeto(rcpt, namespace);

        List<Object> perTarget = new ArrayList<Object>();
        ClassDefinition[] defs = new ClassDefinition[targetList.size()];
        Map<String, byte[]> liveBytesByTarget = new LinkedHashMap<String, byte[]>();
        Map<String, int[]> preCountsByTarget = new LinkedHashMap<String, int[]>();
        Map<String, int[]> expectedByTarget = new LinkedHashMap<String, int[]>();
        int i = 0;
        for (String target : targetList) {
            ByteResult br = computeDisableBytes(target, classes.get(target),
                    classification.get(target).mode, namespace);
            if (!br.ok) {
                // Nada mutado en la JVM todavia -- ningun redefineClasses se llamo.
                rcpt.addStep("compute_bytes_" + target, TxJournal.Status.FAILED, 0L, detail(br.error));
                rcpt.seal(TxJournal.Status.FAILED);
                TxJournal.INSTANCE.complete(rcpt);
                Map<String, Object> out = rcpt.toMap();
                out.put("ok", Boolean.FALSE);
                out.put("code", br.code);
                out.put("error", "fallo calculando bytes de " + target + ": " + br.error);
                out.put("failedTarget", target);
                return out;
            }
            rcpt.addStep("compute_bytes_" + target, TxJournal.Status.OK, 0L, br.detail);
            Map<String, Object> tm = new LinkedHashMap<String, Object>(br.detail);
            tm.put("target", target);
            perTarget.add(tm);
            Class<?> cls = classes.get(target);
            byte[] liveBytes = Tier3LiveCapture.get(target);
            if (liveBytes == null) {
                // Nada mutado todavia -- sin esto, un rollback posterior (si el
                // schema post-redefine no calzara) construiria
                // new ClassDefinition(cls, null) y reventaria con NPE FUERA del
                // redefineClasses inicial, es decir DESPUES de que el primer
                // redefine ya hubiera mutado el target -- exactamente el tipo de
                // corrupcion de estado vivo no detectada que este corte exige
                // prevenir. Mismo codigo/patron que disable()/enableGroup.
                rcpt.addStep("read_live_bytes_" + target, TxJournal.Status.FAILED, 0L,
                        detail("Tier3LiveCapture no tiene bytes vivos capturados de " + target));
                rcpt.seal(TxJournal.Status.FAILED);
                TxJournal.INSTANCE.complete(rcpt);
                Map<String, Object> out = rcpt.toMap();
                out.put("ok", Boolean.FALSE);
                out.put("code", "LIVE_BYTES_MISSING");
                out.put("error", "Tier3LiveCapture no tiene bytes vivos capturados de " + target
                        + " -- no hay snapshot de rollback confiable, nada se aplico");
                out.put("failedTarget", target);
                return out;
            }
            liveBytesByTarget.put(target, liveBytes);
            preCountsByTarget.put(target, new int[] { cls.getDeclaredMethods().length, cls.getDeclaredFields().length });
            int[] expectedCounts = Tier3MixinAudit.expectedDeclaredCounts(br.bytes);
            // Corte tender-seeking-fern (tarea #35), inyeccion de fallo para
            // probar el rollback automatico de verdad: gateado exclusivamente
            // por una system property que solo el harness de verificacion fija
            // (MKSA_EXTRA_JVM_ARGS -> -Dmksa.test.forceSchemaMismatchTarget=<fqcn>,
            // ver launch.rs -- la ruta de produccion nunca define esta property).
            // Corrompe SOLO nuestra propia contabilidad de "schema esperado", no
            // los bytes que realmente se instalan via redefineClasses -- el
            // redefine sigue siendo 100% real y valido, la unica mentira es nuestra
            // propia expectativa, para que la comparacion post-redefine detecte un
            // mismatch genuino y dispare el mismo camino de rollback atomico que
            // correria ante una discrepancia real, sin fabricar el resultado.
            if (expectedCounts != null && target.equals(
                    System.getProperty("mksa.test.forceSchemaMismatchTarget"))) {
                expectedCounts = new int[] { expectedCounts[0] + 1, expectedCounts[1] };
            }
            expectedByTarget.put(target, expectedCounts);
            defs[i++] = new ClassDefinition(cls, br.bytes);
        }

        // T3 corte tender-seeking-fern (tarea #34): verificacion de estado vivo
        // PRE-mutacion. El diseno original preveia usar
        // Instrumentation.retransformClasses para forzar a JVMTI a devolver el
        // bytecode REALMENTE cargado ahora mismo -- pero Tier3RetransformCaptureSmoke
        // (corte H) ya probo, con evidencia real, que retransformClasses NO entrega
        // un snapshot exacto para clases ya redefinidas por este mecanismo
        // (capturedMatchesMixed=false Y capturedMatchesBase=false). Usar ese
        // mecanismo igual seria fabricar una verificacion que no es tal -- exactamente
        // lo que este proyecto tiene prohibido. El sustituto honesto, que SI funciona
        // con evidencia real (reflexion es 100% fiable, a diferencia de retransform):
        // comparar la forma REFLEXIVA de la clase YA cargada ahora mismo
        // (cls.getDeclaredMethods/Fields, ground truth de la JVM) contra la forma
        // esperada de los bytes que Tier3LiveCapture capturo pasivamente en la
        // PRIMERA definicion real (corte I). Si difieren, algo redefinio esta clase
        // por fuera de este mecanismo entre esa captura y ahora -- deriva real de
        // estado vivo, se aborta ANTES de tocar bytecode.
        List<String> preMutationDrift = new ArrayList<String>();
        Map<String, Object> preMutationDetails = new LinkedHashMap<String, Object>();
        for (String target : targetList) {
            int[] pre = preCountsByTarget.get(target);
            int[] expectedFromCapturedLive = Tier3MixinAudit.expectedDeclaredCounts(liveBytesByTarget.get(target));
            boolean verifiable = expectedFromCapturedLive != null;
            boolean matches = !verifiable
                    || (pre[0] == expectedFromCapturedLive[0] && pre[1] == expectedFromCapturedLive[1]);
            Map<String, Object> d = schemaDetail(pre[0], pre[1], pre[0], pre[1], expectedFromCapturedLive);
            preMutationDetails.put(target, d);
            rcpt.addStep("verify_live_state_pre_mutation_" + target,
                    !verifiable ? TxJournal.Status.OK : matches ? TxJournal.Status.OK : TxJournal.Status.FAILED,
                    0L, d);
            if (verifiable && !matches) preMutationDrift.add(target);
        }
        if (!preMutationDrift.isEmpty()) {
            rcpt.seal(TxJournal.Status.FAILED);
            TxJournal.INSTANCE.complete(rcpt);
            Map<String, Object> out = rcpt.toMap();
            out.put("ok", Boolean.FALSE);
            out.put("code", "LIVE_STATE_DRIFT_DETECTED");
            out.put("error", "la forma reflexiva actualmente cargada de " + preMutationDrift
                    + " no coincide con la forma capturada pasivamente por Tier3LiveCapture en su primera "
                    + "definicion real -- el estado vivo de la JVM ya no es el que este mecanismo asume como base. "
                    + "Nada se aplico.");
            out.put("driftedTargets", preMutationDrift);
            out.put("preMutationDetails", preMutationDetails);
            return out;
        }

        try {
            List<String> fieldWarnings = new ArrayList<String>();
            List<String> fieldsPreserved = new ArrayList<String>();
            for (String target : targetList) {
                snapshotPreservedFields(namespace, target, classes.get(target), fieldWarnings, fieldsPreserved);
            }
            inst.redefineClasses(defs);
            rcpt.addStep("redefine_classes_atomic_group", TxJournal.Status.OK, 0L,
                    detail("redefineClasses() real sobre " + targetList.size()
                            + " clases en UNA sola llamada atomica (JVMTI valida todas antes de aplicar cualquiera)."));

            // Corte tier3 end-to-end §5: el redefine de arriba ya tuvo exito (JVMTI
            // ya aplico TODO el grupo, todo-o-nada) -- pero eso solo garantiza que
            // los bytes parsearon y verificaron a nivel de classfile, no que el
            // schema resultante sea el que este mecanismo esperaba instalar. Si
            // CUALQUIER target del grupo no calza, todo el grupo se revierte con un
            // segundo redefineClasses de vuelta a los bytes vivos originales -- la
            // misma garantia todo-o-nada de JVMTI cubre tambien el rollback.
            List<String> schemaMismatches = new ArrayList<String>();
            Map<String, Object> schemaDetails = new LinkedHashMap<String, Object>();
            for (String target : targetList) {
                Class<?> cls = classes.get(target);
                int[] pre = preCountsByTarget.get(target);
                int[] expected = expectedByTarget.get(target);
                int midMethods = cls.getDeclaredMethods().length;
                int midFields = cls.getDeclaredFields().length;
                boolean schemaVerifiable = expected != null;
                boolean schemaMatchesTarget = schemaVerifiable
                        && midMethods == expected[0] && midFields == expected[1];
                Map<String, Object> sd = schemaDetail(pre[0], pre[1], midMethods, midFields, expected);
                schemaDetails.put(target, sd);
                rcpt.addStep("verify_schema_matches_target_bytes_" + target,
                        !schemaVerifiable ? TxJournal.Status.OK
                                : schemaMatchesTarget ? TxJournal.Status.OK : TxJournal.Status.FAILED,
                        0L, sd);
                if (schemaVerifiable && !schemaMatchesTarget) schemaMismatches.add(target);
            }

            if (!schemaMismatches.isEmpty()) {
                ClassDefinition[] rollbackDefs = new ClassDefinition[targetList.size()];
                int r = 0;
                for (String target : targetList) {
                    rollbackDefs[r++] = new ClassDefinition(classes.get(target), liveBytesByTarget.get(target));
                }
                try {
                    inst.redefineClasses(rollbackDefs);
                    rcpt.addStep("rollback_redefine_classes_atomic_group", TxJournal.Status.OK, 0L,
                            detail2("reason", "schema post-redefine no coincide con los bytes solicitados en "
                                    + schemaMismatches, "mismatchedTargets", schemaMismatches));
                    rcpt.seal(TxJournal.Status.ROLLED_BACK);
                    TxJournal.INSTANCE.complete(rcpt);
                    Map<String, Object> out = rcpt.toMap();
                    out.put("ok", Boolean.FALSE);
                    out.put("code", "SCHEMA_MISMATCH_ROLLED_BACK");
                    out.put("error", "verificacion de schema post-redefine fallo en " + schemaMismatches
                            + " -- grupo completo revertido a bytes vivos originales, ningun target quedo desactivado");
                    out.put("mismatchedTargets", schemaMismatches);
                    out.put("schemaDetails", schemaDetails);
                    return out;
                } catch (Throwable rollbackFailure) {
                    // Peor caso real: el disable aplico un schema que no calzaba Y el
                    // intento de revertir tambien fallo. El grupo queda en un estado
                    // que este mecanismo NO puede describir como "desactivado" ni
                    // como "vivo" con confianza -- se declara explicitamente en vez
                    // de fingir cualquiera de los dos. No se marca DISABLED_BY_NS
                    // (seria mentir sobre que bytes estan instalados ahora).
                    rcpt.addStep("rollback_redefine_classes_atomic_group", TxJournal.Status.FAILED, 0L,
                            detail(rollbackFailure.getClass().getSimpleName() + ": " + rollbackFailure.getMessage()));
                    rcpt.recordError("rollback_redefine_classes_atomic_group", rollbackFailure);
                    rcpt.seal(TxJournal.Status.FAILED);
                    TxJournal.INSTANCE.complete(rcpt);
                    Map<String, Object> out = rcpt.toMap();
                    out.put("ok", Boolean.FALSE);
                    out.put("code", "external_state_tamper_suspected");
                    out.put("error", "schema post-redefine no coincidio en " + schemaMismatches
                            + " Y el redefineClasses de rollback tambien fallo ("
                            + rollbackFailure.getClass().getSimpleName() + ": " + rollbackFailure.getMessage()
                            + ") -- el estado real de estas clases en la JVM no puede confirmarse desde aqui, "
                            + "requiere inspeccion manual antes de cualquier otra operacion sobre este grupo");
                    out.put("mismatchedTargets", schemaMismatches);
                    out.put("schemaDetails", schemaDetails);
                    return out;
                }
            }

            for (String target : targetList) {
                String mode = classification.get(target).mode == Tier3MixinAudit.DemixMode.REPLAY
                        ? "replay_v1" : "reset_v1";
                markDisabled(namespace, target, mode, liveBytesByTarget.get(target),
                        bytesForTarget(defs, classes.get(target)), txId);
            }
            // T3 corte AN: quitar retroactivamente las cabezas de los mensajes de
            // chat ya existentes -- class_303/class_303$class_7590 nunca forman
            // parte de defs (ver buildTargetRegistry), asi que esto no depende del
            // orden respecto al redefine de arriba. Ver Tier3ChatHeadsHistoryRewrite
            // (regla universal de re-render retroactivo).
            List<String> historyWarnings = new ArrayList<String>();
            List<String> historyRewritten = new ArrayList<String>();
            List<String> historyDiagnostics = new ArrayList<String>();
            Tier3ChatHeadsHistoryRewrite.stripHeadsFromHistory(classes, historyWarnings, historyRewritten, historyDiagnostics);
            rcpt.seal(TxJournal.Status.OK);
            TxJournal.INSTANCE.complete(rcpt);
            Map<String, Object> out = rcpt.toMap();
            out.put("ok", Boolean.TRUE);
            out.put("model", "group_disable_v1");
            out.put("targetsTotal", Long.valueOf(targetList.size()));
            out.put("targets", perTarget);
            out.put("fieldsPreserved", fieldsPreserved);
            out.put("fieldRestoreWarnings", fieldWarnings);
            out.put("historyMessagesRewritten", Long.valueOf(historyRewritten.size()));
            out.put("historyRewriteWarnings", historyWarnings);
            out.put("historyRewriteDiagnostics", historyDiagnostics);
            return out;
        } catch (Throwable thrown) {
            // Especificacion JVMTI: si redefineClasses tira excepcion NO aplica
            // ningun cambio -- ningun target se marca desactivado.
            rcpt.addStep("redefine_classes_atomic_group", TxJournal.Status.FAILED, 0L,
                    detail(thrown.getClass().getSimpleName() + ": " + thrown.getMessage()));
            rcpt.recordError("redefine_classes_atomic_group", thrown);
            rcpt.seal(TxJournal.Status.FAILED);
            TxJournal.INSTANCE.complete(rcpt);
            Map<String, Object> out = rcpt.toMap();
            out.put("ok", Boolean.FALSE);
            out.put("code", "REDEFINE_FAILED");
            out.put("error", thrown.getClass().getSimpleName() + ": " + thrown.getMessage());
            return out;
        }
    }

    /** Busca en defs la definicion cuya clase es cls y devuelve sus bytes. Usado
     * para poblar installedDisabledBytesSnapshot sin mantener un mapa paralelo
     * target-&gt;bytes fuera de defs (que ya es la fuente unica de verdad de lo
     * que se le pidio a redefineClasses instalar). */
    private static byte[] bytesForTarget(ClassDefinition[] defs, Class<?> cls) {
        for (ClassDefinition d : defs) {
            if (d.getDefinitionClass() == cls) return d.getDefinitionClassFile();
        }
        return null;
    }

    /** Simetrico a disableGroup -- restaura TODOS los targets del grupo a sus
     * bytes vivos originales en una unica llamada atomica a redefineClasses.
     * @param namespace  ver Javadoc de {@link #disableGroup}; puede ser null. */
    static Map<String, Object> enableGroup(Instrumentation inst, String[] targets, String namespace) {
        if (targets == null || targets.length == 0) {
            return fail("BAD_PARAMS", "t3.demixGroupEnable requiere una lista de targets no vacia");
        }
        if (inst == null) {
            return fail("INSTRUMENTATION_MISSING", "Instrumentation no disponible");
        }
        if (!inst.isRedefineClassesSupported()) {
            return fail("REDEFINE_CLASSES_NOT_SUPPORTED", "Esta JVM no soporta redefineClasses()");
        }

        List<String> targetList = new ArrayList<String>(Arrays.asList(targets));
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String target : targetList) {
            if (target == null || target.isEmpty()) {
                return fail("BAD_PARAMS", "target vacio en la lista del grupo");
            }
            if (!seen.add(target)) {
                return fail("BAD_PARAMS", "target duplicado en la lista del grupo: " + target);
            }
            if (!isDisabledAnywhere(target)) {
                return fail("NOT_DISABLED", target + " no esta desactivado por este mecanismo -- nada que restaurar");
            }
        }

        Map<String, Class<?>> classes = new LinkedHashMap<String, Class<?>>();
        for (String target : targetList) {
            Class<?> cls = resolveLoadedClass(inst, target);
            if (cls == null) {
                return fail("TARGET_NOT_LOADED", target + " no esta cargado en esta JVM");
            }
            classes.put(target, cls);
        }

        long txId = TxJournal.INSTANCE.nextId();
        TxJournal.TxReceipt rcpt = new TxJournal.TxReceipt(
                txId, TxJournal.Op.enable, "group:" + targetList.size() + "_targets", Ledger.INSTANCE.hashHex());

        // Corte tabs universal: simetrico de txStepTabVeto en disableGroup -- desarma
        // el veto de pestaña y fuerza el rebuild (el boton reaparece con sus items
        // reales). Best-effort, mismo contrato.
        txStepTabRestore(rcpt, namespace);

        List<Object> perTarget = new ArrayList<Object>();
        ClassDefinition[] defs = new ClassDefinition[targetList.size()];
        Map<String, int[]> preCountsByTarget = new LinkedHashMap<String, int[]>();
        Map<String, int[]> expectedByTarget = new LinkedHashMap<String, int[]>();
        Map<String, byte[]> installedDisabledBytesByTarget = new LinkedHashMap<String, byte[]>();
        int i = 0;
        for (String target : targetList) {
            byte[] liveBytes = Tier3LiveCapture.get(target);
            if (liveBytes == null) {
                rcpt.addStep("read_live_bytes_" + target, TxJournal.Status.FAILED, 0L,
                        detail("Tier3LiveCapture no tiene bytes vivos capturados de " + target));
                rcpt.seal(TxJournal.Status.FAILED);
                TxJournal.INSTANCE.complete(rcpt);
                Map<String, Object> out = rcpt.toMap();
                out.put("ok", Boolean.FALSE);
                out.put("code", "LIVE_BYTES_MISSING");
                out.put("error", "Tier3LiveCapture no tiene bytes vivos capturados de " + target);
                out.put("failedTarget", target);
                return out;
            }
            String liveSha256 = sha256(liveBytes);
            rcpt.addStep("read_live_bytes_" + target, TxJournal.Status.OK, 0L,
                    detail2("target", target, "liveSha256", liveSha256));
            Map<String, Object> tm = new LinkedHashMap<String, Object>();
            tm.put("target", target);
            tm.put("liveSha256", liveSha256);
            perTarget.add(tm);
            Class<?> cls = classes.get(target);
            preCountsByTarget.put(target, new int[] { cls.getDeclaredMethods().length, cls.getDeclaredFields().length });
            expectedByTarget.put(target, Tier3MixinAudit.expectedDeclaredCounts(liveBytes));
            DisabledTargetRecord rec = findDisabledRecord(target);
            installedDisabledBytesByTarget.put(target, rec == null ? null : rec.installedDisabledBytesSnapshot);
            defs[i++] = new ClassDefinition(cls, liveBytes);
        }

        // T3 corte tender-seeking-fern (tarea #34): simetrico de la verificacion
        // PRE-mutacion de disableGroup -- ver Javadoc extenso alli sobre por que
        // NO se usa Instrumentation.retransformClasses (corte H,
        // Tier3RetransformCaptureSmoke: probado que no entrega bytes exactos para
        // clases ya redefinidas por este mecanismo). Aqui la forma esperada es la
        // de installedDisabledBytesSnapshot (lo que se instalo al desactivar), no
        // la de Tier3LiveCapture (esa es la forma VIVA, que es precisamente lo que
        // se esta a punto de restaurar, no la base de comparacion). Si no hay
        // snapshot disponible el chequeo queda no-verificable (no bloquea, mismo
        // criterio que el chequeo post-redefine ya existente) -- solo bloquea
        // cuando SI hay snapshot y la forma reflexiva actual no calza con el.
        List<String> preMutationDrift = new ArrayList<String>();
        Map<String, Object> preMutationDetails = new LinkedHashMap<String, Object>();
        for (String target : targetList) {
            int[] pre = preCountsByTarget.get(target);
            int[] expectedFromInstalledDisabled =
                    Tier3MixinAudit.expectedDeclaredCounts(installedDisabledBytesByTarget.get(target));
            boolean verifiable = expectedFromInstalledDisabled != null;
            boolean matches = !verifiable
                    || (pre[0] == expectedFromInstalledDisabled[0] && pre[1] == expectedFromInstalledDisabled[1]);
            Map<String, Object> d = schemaDetail(pre[0], pre[1], pre[0], pre[1], expectedFromInstalledDisabled);
            preMutationDetails.put(target, d);
            rcpt.addStep("verify_live_state_pre_mutation_" + target,
                    !verifiable ? TxJournal.Status.OK : matches ? TxJournal.Status.OK : TxJournal.Status.FAILED,
                    0L, d);
            if (verifiable && !matches) preMutationDrift.add(target);
        }
        if (!preMutationDrift.isEmpty()) {
            rcpt.seal(TxJournal.Status.FAILED);
            TxJournal.INSTANCE.complete(rcpt);
            Map<String, Object> out = rcpt.toMap();
            out.put("ok", Boolean.FALSE);
            out.put("code", "LIVE_STATE_DRIFT_DETECTED");
            out.put("error", "la forma reflexiva actualmente cargada (desactivada) de " + preMutationDrift
                    + " no coincide con los bytes que este mecanismo registro haber instalado al desactivarla -- "
                    + "el estado vivo de la JVM ya no es el que este mecanismo asume como base. Nada se aplico.");
            out.put("driftedTargets", preMutationDrift);
            out.put("preMutationDetails", preMutationDetails);
            return out;
        }

        try {
            inst.redefineClasses(defs);
            rcpt.addStep("redefine_classes_atomic_group", TxJournal.Status.OK, 0L,
                    detail("redefineClasses() real de vuelta a los bytes vivos originales sobre " + targetList.size()
                            + " clases en UNA sola llamada atomica."));

            // Corte tier3 end-to-end §5: mismo chequeo simetrico que disableGroup --
            // el redefine ya tuvo exito (JVMTI todo-o-nada), pero eso no garantiza
            // que el schema resultante coincida con los bytes vivos que se pidio
            // reinstalar. Si algun target no calza, se revierte el grupo entero de
            // vuelta al estado desactivado conocido (installedDisabledBytesSnapshot),
            // nunca se deja a medio camino.
            List<String> schemaMismatches = new ArrayList<String>();
            Map<String, Object> schemaDetails = new LinkedHashMap<String, Object>();
            for (String target : targetList) {
                Class<?> cls = classes.get(target);
                int[] pre = preCountsByTarget.get(target);
                int[] expected = expectedByTarget.get(target);
                int postMethods = cls.getDeclaredMethods().length;
                int postFields = cls.getDeclaredFields().length;
                boolean schemaVerifiable = expected != null;
                boolean schemaMatchesTarget = schemaVerifiable
                        && postMethods == expected[0] && postFields == expected[1];
                Map<String, Object> sd = schemaDetail(pre[0], pre[1], postMethods, postFields, expected);
                schemaDetails.put(target, sd);
                rcpt.addStep("verify_schema_matches_target_bytes_" + target,
                        !schemaVerifiable ? TxJournal.Status.OK
                                : schemaMatchesTarget ? TxJournal.Status.OK : TxJournal.Status.FAILED,
                        0L, sd);
                if (schemaVerifiable && !schemaMatchesTarget) schemaMismatches.add(target);
            }

            if (!schemaMismatches.isEmpty()) {
                boolean allRollbackBytesAvailable = true;
                for (String target : schemaMismatches) {
                    if (installedDisabledBytesByTarget.get(target) == null) allRollbackBytesAvailable = false;
                }
                if (!allRollbackBytesAvailable) {
                    rcpt.addStep("rollback_redefine_classes_atomic_group", TxJournal.Status.FAILED, 0L,
                            detail("no hay snapshot de bytes desactivados previos para revertir -- registro incompleto"));
                    rcpt.seal(TxJournal.Status.FAILED);
                    TxJournal.INSTANCE.complete(rcpt);
                    Map<String, Object> out = rcpt.toMap();
                    out.put("ok", Boolean.FALSE);
                    out.put("code", "external_state_tamper_suspected");
                    out.put("error", "schema post-redefine no coincidio en " + schemaMismatches
                            + " y no hay snapshot de bytes desactivados previos para revertir con confianza -- "
                            + "el estado real de estas clases en la JVM no puede confirmarse desde aqui");
                    out.put("mismatchedTargets", schemaMismatches);
                    out.put("schemaDetails", schemaDetails);
                    return out;
                }
                ClassDefinition[] rollbackDefs = new ClassDefinition[targetList.size()];
                int r = 0;
                for (String target : targetList) {
                    byte[] rollbackBytes = installedDisabledBytesByTarget.get(target);
                    rollbackDefs[r++] = new ClassDefinition(classes.get(target), rollbackBytes);
                }
                try {
                    inst.redefineClasses(rollbackDefs);
                    rcpt.addStep("rollback_redefine_classes_atomic_group", TxJournal.Status.OK, 0L,
                            detail2("reason", "schema post-redefine no coincide con los bytes vivos solicitados en "
                                    + schemaMismatches, "mismatchedTargets", schemaMismatches));
                    rcpt.seal(TxJournal.Status.ROLLED_BACK);
                    TxJournal.INSTANCE.complete(rcpt);
                    Map<String, Object> out = rcpt.toMap();
                    out.put("ok", Boolean.FALSE);
                    out.put("code", "SCHEMA_MISMATCH_ROLLED_BACK");
                    out.put("error", "verificacion de schema post-redefine fallo en " + schemaMismatches
                            + " -- grupo completo revertido al estado desactivado previo, ningun target quedo activado");
                    out.put("mismatchedTargets", schemaMismatches);
                    out.put("schemaDetails", schemaDetails);
                    return out;
                } catch (Throwable rollbackFailure) {
                    rcpt.addStep("rollback_redefine_classes_atomic_group", TxJournal.Status.FAILED, 0L,
                            detail(rollbackFailure.getClass().getSimpleName() + ": " + rollbackFailure.getMessage()));
                    rcpt.recordError("rollback_redefine_classes_atomic_group", rollbackFailure);
                    rcpt.seal(TxJournal.Status.FAILED);
                    TxJournal.INSTANCE.complete(rcpt);
                    Map<String, Object> out = rcpt.toMap();
                    out.put("ok", Boolean.FALSE);
                    out.put("code", "external_state_tamper_suspected");
                    out.put("error", "schema post-redefine no coincidio en " + schemaMismatches
                            + " Y el redefineClasses de rollback tambien fallo ("
                            + rollbackFailure.getClass().getSimpleName() + ": " + rollbackFailure.getMessage()
                            + ") -- el estado real de estas clases en la JVM no puede confirmarse desde aqui, "
                            + "requiere inspeccion manual antes de cualquier otra operacion sobre este grupo");
                    out.put("mismatchedTargets", schemaMismatches);
                    out.put("schemaDetails", schemaDetails);
                    return out;
                }
            }

            for (String target : targetList) clearDisabled(target);
            List<String> fieldWarnings = new ArrayList<String>();
            List<String> fieldsPreserved = new ArrayList<String>();
            for (String target : targetList) {
                restorePreservedFields(namespace, target, classes.get(target), fieldWarnings, fieldsPreserved);
            }
            // T3 corte AN: restaurar los heads horneados en mensajes de chat ya
            // existentes -- simetrico al strip de disableGroup (regla universal
            // de re-render retroactivo, ver Tier3ChatHeadsHistoryRewrite).
            List<String> historyWarnings = new ArrayList<String>();
            List<String> historyRewritten = new ArrayList<String>();
            List<String> historyDiagnostics = new ArrayList<String>();
            Tier3ChatHeadsHistoryRewrite.restoreHeadsToHistory(classes, historyWarnings, historyRewritten, historyDiagnostics);
            rcpt.seal(TxJournal.Status.OK);
            TxJournal.INSTANCE.complete(rcpt);
            Map<String, Object> out = rcpt.toMap();
            out.put("ok", Boolean.TRUE);
            out.put("model", "group_enable_v1");
            out.put("targetsTotal", Long.valueOf(targetList.size()));
            out.put("targets", perTarget);
            out.put("fieldsPreserved", fieldsPreserved);
            out.put("fieldRestoreWarnings", fieldWarnings);
            out.put("historyMessagesRewritten", Long.valueOf(historyRewritten.size()));
            out.put("historyRewriteWarnings", historyWarnings);
            out.put("historyRewriteDiagnostics", historyDiagnostics);
            return out;
        } catch (Throwable thrown) {
            rcpt.addStep("redefine_classes_atomic_group", TxJournal.Status.FAILED, 0L,
                    detail(thrown.getClass().getSimpleName() + ": " + thrown.getMessage()));
            rcpt.recordError("redefine_classes_atomic_group", thrown);
            rcpt.seal(TxJournal.Status.FAILED);
            TxJournal.INSTANCE.complete(rcpt);
            Map<String, Object> out = rcpt.toMap();
            out.put("ok", Boolean.FALSE);
            out.put("code", "REDEFINE_FAILED");
            out.put("error", thrown.getClass().getSimpleName() + ": " + thrown.getMessage());
            return out;
        }
    }

    private static Map<String, Object> fail(String code, String error) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", Boolean.FALSE);
        m.put("code", code);
        m.put("error", error);
        return m;
    }

    /**
     * Corte tabs universal: mismo mecanismo que {@code InProcess.txStepTabVeto}
     * (Tier 1), reutilizado tal cual aca porque {@code disableGroup} es la unica
     * via Tier 3 que hoy ejecuta de verdad una desactivacion a nivel de mod
     * (namespace-aware via {@code targetsForNamespace}). Best-effort: nunca lanza,
     * nunca bloquea la secuencia de redefine ya probada. namespace null (callers
     * viejos sin el a mano) => paso registrado skipped, sin tocar el veto.
     */
    private static void txStepTabVeto(TxJournal.TxReceipt rcpt, String namespace) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        try {
            if (namespace == null || namespace.isEmpty()) {
                d.put("skipped", Boolean.TRUE);
                d.put("reason", "namespace_not_provided");
            } else {
                Set<String> s = new HashSet<String>();
                s.add(namespace);
                Map<String, Object> r = Ledger.INSTANCE.armTabVeto(s);
                Ledger.INSTANCE.rebuildAllCreativeTabs();
                d.put("armed", Boolean.TRUE);
                d.put("count", r.get("count"));
                d.put("paramsCaptured", Ledger.INSTANCE.tabParamsCapturedCount());
                d.put("rebuildCalls", Ledger.INSTANCE.tabRebuildCallsCount());
                d.put("tabsSeen", Ledger.INSTANCE.tabRebuildTabsSeenCount());
                d.put("invoked", Ledger.INSTANCE.tabRebuildInvokedCount());
                d.put("invokeErrors", Ledger.INSTANCE.tabRebuildInvokeErrorsCount());
                d.put("skipReason", Ledger.INSTANCE.tabRebuildLastSkipReason());
                d.put("lastError", Ledger.INSTANCE.tabRebuildLastError());
            }
        } catch (Throwable t) {
            d.put("skipped", Boolean.TRUE);
            d.put("error", String.valueOf(t));
        }
        long ms = System.currentTimeMillis() - t0;
        rcpt.addStep("tabVeto", TxJournal.Status.OK, ms, d);
    }

    /** Simetrico a {@link #txStepTabVeto} -- ver Javadoc alli. */
    private static void txStepTabRestore(TxJournal.TxReceipt rcpt, String namespace) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        try {
            if (namespace == null || namespace.isEmpty()) {
                d.put("skipped", Boolean.TRUE);
                d.put("reason", "namespace_not_provided");
            } else {
                Ledger.INSTANCE.disarmTabVeto();
                Ledger.INSTANCE.rebuildAllCreativeTabs();
                d.put("disarmed", Boolean.TRUE);
                d.put("paramsCaptured", Ledger.INSTANCE.tabParamsCapturedCount());
                d.put("rebuildCalls", Ledger.INSTANCE.tabRebuildCallsCount());
                d.put("tabsSeen", Ledger.INSTANCE.tabRebuildTabsSeenCount());
                d.put("invoked", Ledger.INSTANCE.tabRebuildInvokedCount());
                d.put("invokeErrors", Ledger.INSTANCE.tabRebuildInvokeErrorsCount());
                d.put("skipReason", Ledger.INSTANCE.tabRebuildLastSkipReason());
                d.put("lastError", Ledger.INSTANCE.tabRebuildLastError());
            }
        } catch (Throwable t) {
            d.put("skipped", Boolean.TRUE);
            d.put("error", String.valueOf(t));
        }
        long ms = System.currentTimeMillis() - t0;
        rcpt.addStep("tabRestore", TxJournal.Status.OK, ms, d);
    }

    private static Map<String, Object> detail(String msg) {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("detail", msg);
        return d;
    }

    private static Map<String, Object> detail2(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put(k1, v1);
        d.put(k2, v2);
        return d;
    }

    /** Corte AH-fix: detalle explicito del chequeo de schema -- expone pre
     * (snapshot antes del redefine, solo informativo), post (reflexion tras
     * el redefine) y expected (parseado de los bytes que se le pidio al JVM
     * instalar). Si expected es null, los bytes objetivo no parsearon y el
     * chequeo quedo "no verificable" (no es lo mismo que fallido). */
    private static Map<String, Object> schemaDetail(int preMethods, int preFields,
            int postMethods, int postFields, int[] expected) {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("preDeclaredMethods", preMethods);
        d.put("preDeclaredFields", preFields);
        d.put("postDeclaredMethods", postMethods);
        d.put("postDeclaredFields", postFields);
        if (expected != null) {
            d.put("expectedDeclaredMethods", expected[0]);
            d.put("expectedDeclaredFields", expected[1]);
        } else {
            d.put("expectedDeclaredMethods", (Object) null);
            d.put("expectedDeclaredFields", (Object) null);
            d.put("note", "no se pudo parsear los bytes objetivo -- chequeo no verificable, no fallido");
        }
        return d;
    }

    private static Class<?> resolveLoadedClass(Instrumentation inst, String dottedName) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c != null && dottedName.equals(c.getName())) return c;
        }
        return null;
    }

    /** T3 corte AM (fix rendererPools): sondeo de solo lectura, no muta nada --
     * confirma si Tier3InstanceLocator puede alcanzar una instancia viva de
     * target desde Boot.clientInstance, antes de confiar en el snapshot/restore
     * real de campos que se apoya en ese mismo localizador. */
    static Map<String, Object> probeInstance(Instrumentation inst, String target) {
        if (target == null || target.isEmpty()) {
            return fail("BAD_PARAMS", "t3.probeInstance requiere target");
        }
        Class<?> cls = resolveLoadedClass(inst, target);
        if (cls == null) {
            return fail("TARGET_NOT_LOADED", target + " no esta cargado en esta JVM");
        }
        long startNanos = System.nanoTime();
        Object instance = Tier3InstanceLocator.locate(cls);
        long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("target", target);
        out.put("found", Boolean.valueOf(instance != null));
        out.put("identityHash", instance != null ? Integer.toHexString(System.identityHashCode(instance)) : null);
        out.put("tookMs", Long.valueOf(tookMs));
        return out;
    }

    private static void redefine(Instrumentation inst, Class<?> target, byte[] bytes) throws Exception {
        inst.redefineClasses(new ClassDefinition(target, bytes));
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private Tier3DemixApply() {}
}
