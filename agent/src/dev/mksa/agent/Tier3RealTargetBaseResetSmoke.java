package dev.mksa.agent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T3 corte P (Pilar 3, siguiente hipotesis inmediata tras corte O): "?un
 * redefineClasses() real desde los bytes VIVOS (ya mezclados por Mixin) hacia
 * los bytes BASE (capturados por classpath resource, pre-mixin) tiene exito o
 * falla por UnsupportedOperationException por schema-change, sobre el mismo
 * target congelado?" -- a diferencia de corte O (mutacion cosmetica del
 * atributo SourceFile, deliberadamente schema-preserving), este SI es el
 * primer paso real del pipeline de demix (mixed->base), con el riesgo de
 * schema-change que corte N/O identificaron y evitaron a proposito: el
 * candidato congelado (balm/net.minecraft.class_2621) fue elegido por ser
 * "body-only" en el sentido de corte M (sin interfaces Accessor/Invoker
 * agregadas), pero eso NO garantiza que el mixin no agregue campos/metodos
 * @Unique al cuerpo de la clase -- algo extremadamente comun y no medido por
 * el universo estructural de corte M. Esta es la primera vez que se prueba
 * eso con evidencia real en vez de asumirlo.
 *
 * Igual que Tier3RealTargetContentRedefineSmoke (corte O), se hace un
 * roundtrip: redefine a base, despues redefine de vuelta a los bytes vivos
 * originales -- pero aqui el resultado esperado es genuinamente incierto (a
 * diferencia de corte O, donde la mutacion cosmetica garantizaba
 * schema-stability por construccion). Si la JVM tira
 * UnsupportedOperationException al redefinir a base, eso NO es un fallo del
 * smoke: es la respuesta real a la hipotesis (refutada para este target en su
 * forma actual), y por especificacion de JVMTI redefineClasses la llamada que
 * tira excepcion no aplica ningun cambio (no hace falta rollback en ese caso,
 * pero se verifica que el schema efectivamente no cambio de todas formas,
 * como confirmacion independiente).
 *
 * Target original congelado en docs/proyecto.md S3.33/S3.34/S3.35 -- balm /
 * net.minecraft.class_2621, mixin unico RandomizableContainerBlockEntityMixin
 * -- REFUTO el reset a base (UnsupportedOperationException, "attempted to
 * change superclass or interfaces": el mixin agregaba una interfaz no
 * detectada por el universo estructural de corte M). Corte Q cerro esa brecha
 * (categoria interface_contributions) y el scout corregido eligio un
 * candidato nuevo, sin ese punto ciego: balm / net.minecraft.class_826, mixin
 * unico ChestRendererMixin. Corte T retargetea este mismo smoke al candidato
 * nuevo -- misma hipotesis exacta que corte P, para confirmar si el cierre de
 * la brecha de corte Q efectivamente elimino el modo de fallo observado.
 *
 * Corte V investigo el codigo fuente de Mixin y confirmo CERRADO el catalogo
 * completo de mecanismos que agregan o mutan forma en un target (cuatro
 * categorias: interface/method/field/field_modifier_contributions). El
 * scout, ya extendido con las cuatro, senalo un candidato mas solido que
 * balm/net.minecraft.class_826 (solo verificado contra UNA categoria al
 * momento de corte P/T): **fabric-block-view-api-v2 /
 * net.minecraft.class_1922**, unico mixin
 * net.fabricmc.fabric.mixin.blockview.BlockGetterMixin -- pasa las cuatro
 * categorias confirmadas cerradas. Corte Y retargetea este mismo smoke
 * (reset a base, el mas incierto de los tres) a ese candidato. Es, de los
 * tres, el corte con el resultado mas interesante: aqui es donde
 * efectivamente se arriesga UnsupportedOperationException por cambio de
 * forma si el mixin agrega algo no cubierto por el catalogo. El catalogo
 * cerrado de corte V da mayor confianza a priori que en la corrida original
 * sobre class_826, pero -- igual que entonces -- el resultado no se asume:
 * se observa y se documenta tal cual salga.
 */
final class Tier3RealTargetBaseResetSmoke {
    private static final String TARGET_DOTTED = "net.minecraft.class_1922";
    private static final String FROZEN_NS = "fabric-block-view-api-v2";
    private static final String FROZEN_MIXIN =
            "net.fabricmc.fabric.mixin.blockview.BlockGetterMixin";

    static Map<String, Object> run(Instrumentation inst) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("model", "real_target_base_reset_smoke_v1");
        out.put("receiptModel", "tier3_demix_tx_receipt_v1");
        out.put("targetClass", TARGET_DOTTED);
        out.put("targetScope", "real_minecraft_mixin_target");
        out.put("touchesMinecraftClasses", Boolean.TRUE);
        out.put("touchesMixinTargets", Boolean.TRUE);
        out.put("frozenCandidateNs", FROZEN_NS);
        out.put("frozenCandidateMixin", FROZEN_MIXIN);
        out.put("hypothesis", "real_reset_to_base_redefine_succeeds_or_fails_by_schema_change_on_real_target");
        out.put("verificationModel",
                "redefine_live_to_base_roundtrip_plus_schema_observation_no_active_bytecode_probe");

        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        List<Object> steps = new ArrayList<Object>();
        receipt.put("model", "tier3_demix_tx_receipt_v1");
        receipt.put("txId", "real-target-base-reset-smoke-corte-y");
        receipt.put("targetClass", TARGET_DOTTED);
        receipt.put("steps", steps);
        out.put("receipt", receipt);

        if (inst == null) {
            out.put("status", "unsupported");
            out.put("redefineSupported", Boolean.FALSE);
            out.put("reason", "instrumentation_missing");
            receipt.put("status", "UNSUPPORTED");
            return out;
        }

        Class<?> target = resolveLoadedClass(inst, TARGET_DOTTED);
        out.put("resolved", Boolean.valueOf(target != null));
        if (target == null) {
            out.put("status", "unsupported");
            out.put("reason", "target_not_loaded");
            receipt.put("status", "UNSUPPORTED");
            return out;
        }
        steps.add(step("resolve_loaded_class", "OK",
                "Class<?> real resuelta via Instrumentation.getAllLoadedClasses()."));

        boolean redefineSupported = inst.isRedefineClassesSupported();
        boolean modifiable = false;
        try {
            modifiable = inst.isModifiableClass(target);
        } catch (Throwable ignored) {
        }
        out.put("redefineSupported", Boolean.valueOf(redefineSupported));
        out.put("modifiableClass", Boolean.valueOf(modifiable));
        if (!redefineSupported || !modifiable) {
            out.put("status", "unsupported");
            out.put("reason", !redefineSupported ? "redefine_classes_not_supported" : "target_not_modifiable");
            receipt.put("status", "UNSUPPORTED");
            return out;
        }

        byte[] liveBytes = Tier3LiveCapture.get(TARGET_DOTTED);
        out.put("liveBytesAvailable", Boolean.valueOf(liveBytes != null));
        byte[] baseBytes = Tier3MixinAudit.baseBytes(TARGET_DOTTED);
        out.put("baseBytesAvailable", Boolean.valueOf(baseBytes != null));
        if (liveBytes == null || baseBytes == null) {
            out.put("status", "unsupported");
            out.put("reason", liveBytes == null ? "live_bytes_missing" : "base_bytes_missing");
            receipt.put("status", "UNSUPPORTED");
            return out;
        }
        out.put("liveSha256", sha256(liveBytes));
        out.put("liveSize", Long.valueOf(liveBytes.length));
        out.put("baseSha256", sha256(baseBytes));
        out.put("baseSize", Long.valueOf(baseBytes.length));
        boolean contentDiffersFromLive = !out.get("baseSha256").equals(out.get("liveSha256"));
        out.put("contentDiffersFromLive", Boolean.valueOf(contentDiffersFromLive));
        if (!contentDiffersFromLive) {
            // base identico a vivo (mixin no cambio nada de contenido observable
            // en este target puntual) -- posible pero no es lo que este corte
            // quiere medir (ya lo cubrio corte N, identity redefine).
            out.put("status", "unsupported");
            out.put("reason", "base_identical_to_live_not_a_content_reset");
            receipt.put("status", "UNSUPPORTED");
            return out;
        }
        steps.add(step("read_base_and_live_bytes", "OK",
                "Bytes base (classpath, pre-mixin) y vivos (ya mezclados) leidos, con contenido genuinamente distinto."));

        int preMethods = target.getDeclaredMethods().length;
        int preFields = target.getDeclaredFields().length;
        out.put("preDeclaredMethods", Long.valueOf(preMethods));
        out.put("preDeclaredFields", Long.valueOf(preFields));

        boolean resetApplied = false;
        boolean rollbackApplied = false;
        boolean rollbackOk = false;
        try {
            redefine(inst, target, baseBytes);
            resetApplied = true;
            steps.add(step("redefine_reset_to_base_on_real_target", "OK",
                    "redefineClasses() real sobre " + TARGET_DOTTED + " con los bytes BASE (pre-mixin)."));
            int midMethods = target.getDeclaredMethods().length;
            int midFields = target.getDeclaredFields().length;
            out.put("midDeclaredMethods", Long.valueOf(midMethods));
            out.put("midDeclaredFields", Long.valueOf(midFields));
            boolean schemaStableAfterReset = midMethods == preMethods && midFields == preFields;
            out.put("schemaStableAfterReset", Boolean.valueOf(schemaStableAfterReset));
            steps.add(step("verify_schema_stable_after_reset", schemaStableAfterReset ? "OK" : "FAILED",
                    "Conteo de metodos/campos declarados tras el reset a base, comparado contra el conteo vivo original."));

            rollbackApplied = true;
            redefine(inst, target, liveBytes);
            rollbackOk = true;
            steps.add(step("redefine_rollback_to_live_on_real_target", "OK",
                    "redefineClasses() real de vuelta a los bytes vivos originales (round trip)."));
            int postMethods = target.getDeclaredMethods().length;
            int postFields = target.getDeclaredFields().length;
            out.put("postDeclaredMethods", Long.valueOf(postMethods));
            out.put("postDeclaredFields", Long.valueOf(postFields));
            boolean schemaStableAfterRollback = postMethods == preMethods && postFields == preFields;
            out.put("schemaStableAfterRollback", Boolean.valueOf(schemaStableAfterRollback));
            steps.add(step("verify_schema_stable_after_rollback", schemaStableAfterRollback ? "OK" : "FAILED",
                    "Conteo de metodos/campos declarados no cambio tras el rollback."));

            out.put("rollbackApplied", Boolean.valueOf(true));
            out.put("rollbackOk", Boolean.valueOf(true));
            receipt.put("status", "OK");
            out.put("status", "ok");
            return out;
        } catch (Throwable t) {
            out.put("status", "failed");
            out.put("error", t.getClass().getSimpleName());
            out.put("message", String.valueOf(t.getMessage()));
            out.put("resetApplied", Boolean.valueOf(resetApplied));
            out.put("rollbackApplied", Boolean.valueOf(rollbackApplied));
            out.put("rollbackOk", Boolean.valueOf(rollbackOk));
            receipt.put("status", "FAILED");
            steps.add(step(resetApplied ? "redefine_rollback_to_live_on_real_target"
                            : "redefine_reset_to_base_on_real_target",
                    "FAILED", t.getClass().getSimpleName() + ": " + t.getMessage()));
            // Por especificacion de JVMTI, si redefineClasses tira excepcion NO
            // aplica ningun cambio -- confirmacion independiente de que el schema
            // sigue exactamente como estaba antes del intento.
            int afterFailureMethods = target.getDeclaredMethods().length;
            int afterFailureFields = target.getDeclaredFields().length;
            out.put("schemaUnchangedAfterFailure",
                    Boolean.valueOf(afterFailureMethods == preMethods && afterFailureFields == preFields));
            if (resetApplied && !rollbackApplied) {
                try {
                    redefine(inst, target, liveBytes);
                    out.put("bestEffortRollbackOk", Boolean.TRUE);
                    steps.add(step("best_effort_rollback_to_live_on_real_target", "OK",
                            "Rollback de mejor esfuerzo tras excepcion en el reset a base."));
                } catch (Throwable t2) {
                    out.put("bestEffortRollbackOk", Boolean.FALSE);
                    out.put("bestEffortRollbackError", t2.getClass().getSimpleName() + ": " + t2.getMessage());
                    steps.add(step("best_effort_rollback_to_live_on_real_target", "FAILED",
                            t2.getClass().getSimpleName() + ": " + t2.getMessage()));
                }
            }
            return out;
        }
    }

    private static Map<String, Object> step(String action, String status, String detail) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("action", action);
        m.put("status", status);
        m.put("detail", detail);
        return m;
    }

    private static Class<?> resolveLoadedClass(Instrumentation inst, String dottedName) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c != null && dottedName.equals(c.getName())) return c;
        }
        return null;
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

    private Tier3RealTargetBaseResetSmoke() {}
}
