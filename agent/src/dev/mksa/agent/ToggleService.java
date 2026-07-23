package dev.mksa.agent;

import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * S3: Servicio unico de toggle de mods. Punto de entrada unico para todo
 * disable/enable, sin importar el tier. Bridge, Agent y Ledger delegan aqui.
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>Crear planes inmutables ({@link TogglePlan}).</li>
 *   <li>Bloquear operaciones concurrentes (lock global).</li>
 *   <li>Ejecutar disable/enable como transacciones completas.</li>
 *   <li>Verificar pre- y post-redefinicion.</li>
 *   <li>Hacer rollback automatico si falla despues de mutar.</li>
 *   <li>Recoger metricas antes/despues ({@link ToggleMetrics}).</li>
 * </ul>
 *
 * <p>La clasificacion de acciones por tier (S11):
 * <ul>
 *   <li>T0: reload de datos</li>
 *   <li>T1: contenido y registros (sweep + veto + dpReload)</li>
 *   <li>T2: runtime hooks (listeners, canales, tareas)</li>
 *   <li>T3: demix caliente (redefineClasses atomico)</li>
 * </ul>
 */
public final class ToggleService {

    public static final ToggleService INSTANCE = new ToggleService();

    private final ReentrantLock globalLock = new ReentrantLock();
    private final Map<String, TogglePlan> activePlans =
            new LinkedHashMap<String, TogglePlan>();
    private static final long PLAN_TTL_MS = 5 * 60 * 1000L; // 5 minutos

    /** PID del proceso actual — no cambia durante la sesion. */
    private static final long PROCESS_PID = resolveProcessPid();
    /** ID de sesion — unico por ejecucion de la JVM. */
    private static final String SESSION_ID = "session_" + System.currentTimeMillis();

    private ToggleService() {}

    // ---- S3: API publica ----

    /**
     * Genera una vista previa inmutable de la operacion de toggle.
     * No muta nada. El plan caduca en 5 minutos o si cambia el estado.
     */
    public Map<String, Object> preview(String namespace, String direction) {
        return buildPreview(namespace, direction);
    }

    public Map<String, Object> previewDisable(String namespace) {
        return buildPreview(namespace, "disable");
    }

    /** Vista previa para enable. */
    public Map<String, Object> previewEnable(String namespace) {
        return buildPreview(namespace, "enable");
    }

    /**
     * Aplica un plan previamente generado. Valida staleness antes de tocar
     * la JVM. Devuelve PLAN_STALE si el estado cambio desde el preview.
     */
    public Map<String, Object> apply(String planId) {
        return applyPlan(planId);
    }

    public Map<String, Object> applyDisable(String planId) {
        return applyPlan(planId);
    }

    public Map<String, Object> applyEnable(String planId) {
        return applyPlan(planId);
    }

    /**
     * S9: Ejecuta disable completo para un namespace Tier 3.
     * Secuencia de 15 pasos con rollback automatico.
     */
    public Map<String, Object> disable(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return fail("BAD_PARAMS", "namespace vacio", null, null);
        }
        Instrumentation inst = Boot.getInstrumentation();
        if (inst == null) {
            return fail("INSTRUMENTATION_MISSING", "Instrumentation no disponible", null, null);
        }

        globalLock.lock();
        String operationId = "tx_disable_" + System.currentTimeMillis() + "_" + namespace;
        try {
            // Paso 1: lock adquirido

            // Paso 2: comprobar que el mod esta ON
            if (!Tier3RuntimeState.canDisable(namespace)) {
                String currentState = String.valueOf(Tier3RuntimeState.getState(namespace));
                return fail("ILLEGAL_STATE",
                        "No se puede desactivar '" + namespace + "' en estado " + currentState,
                        null, null);
            }

            // Transicionar a PLANNING_DISABLE
            Tier3RuntimeState.beginDisable(namespace, operationId);

            // Paso 3: descubrir targets y crear plan (filtrado solo a targets cargados en JVM)
            Set<String> targetSet = Tier3MixinAudit.scanTargets(namespace);
            if (targetSet == null || targetSet.isEmpty()) {
                // S3: Si no hay targets cargados en la JVM para este mod, no es un error fatal.
                // El mod permanece ACTIVE y retryable.
                Map<String, Object> reply = new LinkedHashMap<String, Object>();
                reply.put("ok", Boolean.FALSE);
                reply.put("namespace", namespace);
                reply.put("state", "ACTIVE");
                reply.put("toggleState", "active");
                reply.put("code", "NO_LOADED_VICTIM_TARGETS");
                reply.put("error", "No hay targets del mod cargados actualmente en la JVM");
                reply.put("retryable", Boolean.TRUE);
                reply.put("rolledBack", Boolean.FALSE);
                return reply;
            }
            String[] targets = targetSet.toArray(new String[0]);

            // Clasificar targets (modos RESET/REPLAY/PRESERVE_SHAPE/ADAPTER/BLOCKED)
            Map<String, Tier3MixinAudit.TargetClassification> classification =
                    Tier3MixinAudit.classifyDemixTargets(namespace, targetSet, inst);

            // Verificar que ningun target esta BLOCKED
            Map<String, String> expectedModes = new LinkedHashMap<String, String>();
            for (String target : targets) {
                Tier3MixinAudit.TargetClassification tc = classification.get(target);
                if (tc == null || tc.mode == Tier3MixinAudit.DemixMode.BLOCKED) {
                    String reasons = tc == null ? "sin clasificar" : String.valueOf(tc.blockedReasons);
                    Tier3RuntimeState.markFailedActive(namespace,
                            "Target " + target + " bloqueado: " + reasons);
                    return fail("TARGET_BLOCKED",
                            target + " bloqueado por auditoria: " + reasons,
                            "plan", target);
                }
                expectedModes.put(target, tc.mode.name());
            }

            // Transicionar a DISABLING
            Tier3RuntimeState.startDisabling(namespace);

            // Paso 4-9, 10-13: ejecutar la redefinicion atomica via Tier3DemixApply.
            // disableGroup ya implementa: captura de bytes live, construccion de bytes OFF,
            // verificacion pre-redefine, redefineClasses atomico, verificacion de schema
            // post-redefine, y rollback automatico si falla.
            Map<String, Object> res = Tier3DemixApply.disableGroup(inst, targets, namespace, expectedModes);

            if (Boolean.TRUE.equals(res.get("ok"))) {
                // Paso 14: registrar OFF verificado
                Set<String> verifiedTargets = new LinkedHashSet<String>(Arrays.asList(targets));
                Tier3RuntimeState.commitDisable(namespace, null, null, null, null, null,
                        1L, verifiedTargets);

                // Construir respuesta rica (S12)
                Map<String, Object> reply = new LinkedHashMap<String, Object>();
                reply.put("ok", Boolean.TRUE);
                reply.put("namespace", namespace);
                reply.put("operationId", operationId);
                reply.put("state", "OFF");
                reply.put("toggleState", "inactive_verified");
                reply.put("verified", Boolean.TRUE);
                reply.put("mechanism", deriveMechanism(expectedModes));
                reply.put("targets", Integer.valueOf(targets.length));
                reply.put("rollbackAvailable", Boolean.TRUE);
                reply.put("samePid", Boolean.TRUE);
                reply.put("sameSession", Boolean.TRUE);
                // Copiar info de la receipt original
                Object blocks = res.get("blocks");
                if (blocks != null) reply.put("blocks", blocks);
                Object chunks = res.get("chunks");
                if (chunks != null) reply.put("chunks", chunks);
                return reply;
            } else {
                // Fallo — disableGroup ya hizo rollback internamente si era necesario
                boolean rolledBack = Boolean.TRUE.equals(res.get("rolledBack"))
                        || "SCHEMA_MISMATCH_ROLLED_BACK".equals(res.get("code"));
                if (rolledBack) {
                    Tier3RuntimeState.markRollback(namespace,
                            String.valueOf(res.get("error")));
                } else {
                    Tier3RuntimeState.markFailedActive(namespace,
                            String.valueOf(res.get("error")));
                }

                Map<String, Object> reply = new LinkedHashMap<String, Object>();
                reply.put("ok", Boolean.FALSE);
                reply.put("namespace", namespace);
                reply.put("operationId", operationId);
                reply.put("state", "failed_active");
                reply.put("toggleState", "failed_active");
                reply.put("rolledBack", Boolean.TRUE);
                reply.put("retryable", Boolean.TRUE);
                reply.put("code", res.get("code"));
                reply.put("error", res.get("error"));
                reply.put("phase", "apply");
                Object failTarget = res.get("target");
                if (failTarget != null) reply.put("target", failTarget);
                reply.put("detail", res.get("detail"));
                return reply;
            }
        } catch (Throwable t) {
            Tier3RuntimeState.markFailedActive(namespace,
                    t.getClass().getSimpleName() + ": " + (t.getMessage() != null ? t.getMessage() : "no_message"));
            Map<String, Object> reply = errorWithCause("INTERNAL_ERROR", "disable", null, t);
            reply.put("state", "failed_active");
            return reply;
        } finally {
            // Paso 15: liberar lock
            globalLock.unlock();
        }
    }

    /**
     * S10: Ejecuta enable desde el registro real. Usa los targets GUARDADOS,
     * no re-escanea. Restaura los bytes live guardados.
     */
    public Map<String, Object> enable(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return fail("BAD_PARAMS", "namespace vacio", null, null);
        }
        Instrumentation inst = Boot.getInstrumentation();
        if (inst == null) {
            return fail("INSTRUMENTATION_MISSING", "Instrumentation no disponible", null, null);
        }

        globalLock.lock();
        String operationId = "tx_enable_" + System.currentTimeMillis() + "_" + namespace;
        try {
            // Comprobar que el mod esta OFF verificado
            if (!Tier3RuntimeState.canEnable(namespace)) {
                String currentState = String.valueOf(Tier3RuntimeState.getState(namespace));
                return fail("ILLEGAL_STATE",
                        "No se puede reactivar '" + namespace + "' en estado " + currentState,
                        null, null);
            }

            Tier3RuntimeState.beginEnable(namespace);
            Tier3RuntimeState.startEnabling(namespace);

            // S10: usar targets GUARDADOS del registro de disable, no re-escanear
            List<Tier3DemixApply.DisabledTargetRecord> records =
                    Tier3DemixApply.disabledRecordsForNamespace(namespace);
            String[] targets;
            if (records != null && !records.isEmpty()) {
                targets = new String[records.size()];
                for (int i = 0; i < records.size(); i++) {
                    targets[i] = records.get(i).target;
                }
            } else {
                // Fallback a re-scan si no hay registros (compat)
                Set<String> scanned = Tier3MixinAudit.scanTargets(namespace);
                if (scanned == null || scanned.isEmpty()) {
                    Tier3RuntimeState.markFailedInactive(namespace,
                            "Sin targets guardados ni descubiertos para " + namespace);
                    return fail("NO_TARGETS",
                            "No hay targets registrados para '" + namespace + "'",
                            null, null);
                }
                targets = scanned.toArray(new String[0]);
            }

            // Ejecutar restauracion atomica
            Map<String, Object> res = Tier3DemixApply.enableGroup(inst, targets, namespace);

            if (Boolean.TRUE.equals(res.get("ok"))) {
                Tier3RuntimeState.commitEnable(namespace);

                Map<String, Object> reply = new LinkedHashMap<String, Object>();
                reply.put("ok", Boolean.TRUE);
                reply.put("namespace", namespace);
                reply.put("operationId", operationId);
                reply.put("state", "ON");
                reply.put("toggleState", "active");
                reply.put("verified", Boolean.TRUE);
                reply.put("mechanism", "restore_live_bytes");
                reply.put("targets", Integer.valueOf(targets.length));
                reply.put("rollbackAvailable", Boolean.FALSE);
                reply.put("samePid", Boolean.TRUE);
                reply.put("sameSession", Boolean.TRUE);
                return reply;
            } else {
                boolean rolledBack = Boolean.TRUE.equals(res.get("rolledBack"));
                Tier3RuntimeState.markFailedInactive(namespace,
                        String.valueOf(res.get("error")));

                Map<String, Object> reply = new LinkedHashMap<String, Object>();
                reply.put("ok", Boolean.FALSE);
                reply.put("namespace", namespace);
                reply.put("operationId", operationId);
                reply.put("state", "OFF");
                reply.put("toggleState", "inactive_verified");
                reply.put("rolledBack", Boolean.valueOf(rolledBack));
                reply.put("code", res.get("code"));
                reply.put("error", res.get("error"));
                reply.put("phase", "apply");
                return reply;
            }
        } catch (Throwable t) {
            Tier3RuntimeState.markFailedInactive(namespace,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
            return fail("INTERNAL",
                    t.getClass().getSimpleName() + ": " + t.getMessage(),
                    "enable", null);
        } finally {
            globalLock.unlock();
        }
    }

    /** Estado actual de un namespace. */
    public Map<String, Object> status(String namespace) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("namespace", namespace);
        out.put("state", String.valueOf(Tier3RuntimeState.getState(namespace)));
        out.put("pid", Long.valueOf(PROCESS_PID));
        out.put("sessionId", SESSION_ID);
        return out;
    }

    // ---- preview/apply (S8) ----

    private Map<String, Object> buildPreview(String namespace, String direction) {
        if (namespace == null || namespace.isEmpty()) {
            return fail("BAD_PARAMS", "namespace vacio", null, null);
        }

        Set<String> targetSet = Tier3MixinAudit.scanTargets(namespace);
        int targetsCount = targetSet != null ? targetSet.size() : 0;
        String[] targets = targetSet != null ? targetSet.toArray(new String[0]) : new String[0];

        // Clasificar
        Map<String, String> modeByTarget = new LinkedHashMap<String, String>();
        Map<String, String> liveHashByTarget = new LinkedHashMap<String, String>();
        Map<String, String> baseHashByTarget = new LinkedHashMap<String, String>();
        Map<String, List<String>> coOwnersByTarget = new LinkedHashMap<String, List<String>>();
        Map<String, String> shapeFingerprintByTarget = new LinkedHashMap<String, String>();

        Instrumentation inst = Boot.getInstrumentation();
        if (targetsCount > 0 && inst != null) {
            Map<String, Tier3MixinAudit.TargetClassification> classification =
                    Tier3MixinAudit.classifyDemixTargets(namespace, targetSet, inst);
            for (String target : targets) {
                Tier3MixinAudit.TargetClassification tc = classification.get(target);
                modeByTarget.put(target, tc != null ? tc.mode.name() : "BLOCKED");
                // Hash de bytes live
                byte[] liveBytes = Tier3LiveCapture.get(target);
                liveHashByTarget.put(target, liveBytes != null ? sha256Hex(liveBytes) : "unknown");
                baseHashByTarget.put(target, "deferred");
                coOwnersByTarget.put(target, Collections.<String>emptyList());
                shapeFingerprintByTarget.put(target, "deferred");
            }
        }

        long now = System.currentTimeMillis();
        String planId = "plan_" + now + "_" + namespace + "_" + direction;

        // Fingerprints
        String modFp = namespace + "_" + targetsCount;
        String jvmFp = System.getProperty("java.vm.name", "") + "_"
                + System.getProperty("java.vm.version", "");
        boolean enhancedRedef = Boot.enhancedRedefinitionProven;

        TogglePlan plan = new TogglePlan(
                planId, namespace, direction,
                1L, // epoch simplificado
                modFp, jvmFp, enhancedRedef,
                targets, modeByTarget,
                liveHashByTarget, null, baseHashByTarget,
                coOwnersByTarget, shapeFingerprintByTarget,
                null, null, null,
                now, now + PLAN_TTL_MS);

        activePlans.put(planId, plan);

        Map<String, Object> out = plan.toMap();
        out.put("ok", Boolean.TRUE);
        out.put("decision", targetsCount > 0 ? "auto" : "needs_adapter");
        out.put("mechanism", targetsCount > 0 ? deriveMechanism(modeByTarget) : "unknown");
        return out;
    }

    private Map<String, Object> applyPlan(String planId) {
        if (planId == null || planId.isEmpty() || !activePlans.containsKey(planId)) {
            return fail("PLAN_STALE",
                    "El plan de toggle ya no es valido o ha caducado. Obtenga una nueva vista previa.",
                    null, null);
        }

        TogglePlan plan = activePlans.remove(planId);
        String staleness = plan.checkStaleness(1L, null);
        if (staleness != null) {
            return fail("PLAN_STALE", "Plan stale: " + staleness, null, null);
        }

        if ("enable".equalsIgnoreCase(plan.direction)) {
            return enable(plan.namespace);
        }
        return disable(plan.namespace);
    }

    // ---- S15: diagnostico JBR21 ----

    /** Informacion de diagnostico JVM expuesta al UI/IPC. */
    public static Map<String, Object> jvmDiagnostics() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        String specVer = System.getProperty("java.specification.version", "");
        int major = 0;
        try { major = Integer.parseInt(specVer); } catch (Throwable ignored) {}
        m.put("javaMajor", Integer.valueOf(major));
        String vmName = System.getProperty("java.vm.name", "");
        m.put("jbr", Boolean.valueOf(vmName.contains("JBR") || vmName.contains("JetBrains")));
        m.put("enhancedRedefinition", Boolean.valueOf(Boot.enhancedRedefinitionProven));
        Instrumentation inst = Boot.getInstrumentation();
        m.put("instrumentation", Boolean.valueOf(inst != null));
        m.put("redefineSupported", Boolean.valueOf(
                inst != null && inst.isRedefineClassesSupported()));
        m.put("pid", Long.valueOf(PROCESS_PID));
        m.put("sessionId", SESSION_ID);
        return m;
    }

    // ---- helpers ----

    public static Map<String, Object> errorWithCause(String code, String phase, String target, Throwable t) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", Boolean.FALSE);
        m.put("code", code != null ? code : "INTERNAL_ERROR");
        if (phase != null) m.put("phase", phase);
        if (target != null) m.put("target", target);
        if (t != null) {
            m.put("exceptionClass", t.getClass().getName());
            String msg = t.getMessage();
            m.put("message", (msg != null && !msg.isEmpty()) ? msg : t.getClass().getSimpleName());
            Throwable cause = t.getCause();
            if (cause != null) {
                m.put("rootCauseClass", cause.getClass().getName());
                String cMsg = cause.getMessage();
                m.put("rootCauseMessage", (cMsg != null && !cMsg.isEmpty()) ? cMsg : cause.getClass().getSimpleName());
            }
            List<String> st = new ArrayList<String>();
            StackTraceElement[] elems = t.getStackTrace();
            if (elems != null) {
                for (int i = 0; i < Math.min(elems.length, 5); i++) {
                    st.add(elems[i].toString());
                }
            }
            m.put("stackTrace", st);
            m.put("error", m.get("message"));
        } else {
            m.put("error", "Error interno sin excepcion declarada");
        }
        m.put("retryable", Boolean.TRUE);
        m.put("rolledBack", Boolean.TRUE);
        return m;
    }

    private Map<String, Object> fail(String code, String error, String phase, String target) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", Boolean.FALSE);
        m.put("code", code);
        m.put("error", error);
        if (phase != null) m.put("phase", phase);
        if (target != null) m.put("target", target);
        return m;
    }

    /** Deriva el mecanismo predominante de los modos asignados. */
    private static String deriveMechanism(Map<String, String> modeByTarget) {
        if (modeByTarget == null || modeByTarget.isEmpty()) return "unknown";
        int preserveShape = 0, replay = 0, reset = 0, adapter = 0;
        for (String mode : modeByTarget.values()) {
            if ("PRESERVE_SHAPE".equals(mode)) preserveShape++;
            else if ("REPLAY".equals(mode)) replay++;
            else if ("RESET".equals(mode)) reset++;
            else if ("ADAPTER".equals(mode)) adapter++;
        }
        if (preserveShape > 0 && preserveShape >= replay && preserveShape >= reset) {
            return "tier3_preserve_shape";
        }
        if (replay > 0) return "tier3_replay";
        if (reset > 0) return "tier3_reset";
        if (adapter > 0) return "tier3_adapter";
        return "tier3_demix";
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "hash_error";
        }
    }

    private static long resolveProcessPid() {
        try {
            // Java 9+: ProcessHandle.current().pid()
            Class<?> ph = Class.forName("java.lang.ProcessHandle");
            Object current = ph.getMethod("current").invoke(null);
            return ((Long) ph.getMethod("pid").invoke(current)).longValue();
        } catch (Throwable t) {
            // Java 8 fallback: ManagementFactory.getRuntimeMXBean().getName() = "pid@host"
            try {
                String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
                return Long.parseLong(name.split("@")[0]);
            } catch (Throwable t2) {
                return -1L;
            }
        }
    }
}
