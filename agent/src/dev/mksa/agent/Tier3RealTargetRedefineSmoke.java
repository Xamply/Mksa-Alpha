package dev.mksa.agent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T3 corte N (Pilar 3, primer corte real del aplicador): prueba UNA hipotesis
 * acotada -- "?la infraestructura transaccional existente (captura de base/vivo
 * de Pilares 0-1, universo estructural de corte M, redefineClasses ya probado
 * en corte F/G sobre clases privadas) alcanza para ejecutar con EXITO un
 * redefineClasses() real sobre un target de Minecraft/Mixin real, sin requerir
 * cambios estructurales (body-only)?" -- NO intenta el pipeline completo de
 * demix (reset a base + replay de mixins no-victima, aun bloqueado por
 * demix_pipeline_pending/rollback_pipeline_pending en el gate real). Es
 * deliberadamente mas chico: redefine el target REAL a sus MISMOS bytes vivos
 * (identity redefine) -- si la JVM acepta esa llamada sin lanzar
 * UnsupportedOperationException/VerifyError sobre una clase real cargada por
 * el classloader del juego (no el classloader del agent), eso ya prueba que el
 * primitivo `Instrumentation.redefineClasses()` funciona fuera del sandbox
 * sintetico de corte F/G (Tier3RedefSmoke/Tier3ControlledDemix, siempre sobre
 * dev.mksa.agent.TierXControlledDemixTarget). Es el primer corte que toca una
 * clase de Minecraft de verdad -- por eso touchesMinecraftClasses=true y
 * touchesMixinTargets=true, a diferencia de TODOS los smokes anteriores.
 *
 * Target original congelado en docs/proyecto.md S3.33: balm /
 * net.minecraft.class_2621 (RandomizableContainerBlockEntity), unico mixin
 * RandomizableContainerBlockEntityMixin -- body-only (fuera de
 * accessor_invoker_interfaces, corte M), dueno unico, runtimeOrder.verified,
 * sin overlap con LedgerTransformer. Corte P REFUTO el reset a base sobre ese
 * target (agregaba una interfaz no detectada por el universo de corte M).
 * Corte Q cerro esa brecha (categoria interface_contributions) y el scout
 * corregido eligio un candidato nuevo: **balm / net.minecraft.class_826**,
 * unico mixin net.blay09.mods.balm.internal.mixin.ChestRendererMixin --
 * body-only tambien respecto a interface_contributions (superset), dueno
 * unico, runtimeOrder.verified, sin overlap de transformer (ver rationale
 * explicito impreso por --tier3-target-scout tras la extension de corte Q).
 * Corte R reutiliza este mismo smoke (identity redefine) retargeteado al
 * candidato nuevo -- misma hipotesis exacta que corte N, sobre un target
 * distinto que ya no tiene el punto ciego de interfaz no detectada.
 *
 * Corte V investigo el codigo fuente de Mixin y confirmo CERRADO el catalogo
 * completo de mecanismos que agregan forma nueva a un target (interfaz,
 * metodo, campo, mas el caso de modificador @Shadow+@Mutable) en cuatro
 * categorias del universo estructural (interface_contributions,
 * method_contributions, field_contributions, field_modifier_contributions).
 * El scout, ya extendido con las cuatro, senalo un candidato mas solido que
 * balm/net.minecraft.class_826 (que solo habia sido verificado contra UNA
 * categoria al momento de corte N/R): **fabric-block-view-api-v2 /
 * net.minecraft.class_1922**, unico mixin
 * net.fabricmc.fabric.mixin.blockview.BlockGetterMixin (confirmado por
 * inspeccion de bytecode del jar real, constant pool de @Mixin) -- pasa las
 * cuatro categorias confirmadas cerradas, ademas del resto de criterios ya
 * exigidos (dueno unico, runtimeOrder.verified, sin overlap de transformer).
 * Corte W retargetea este mismo smoke (identity redefine) a ese candidato --
 * misma hipotesis exacta que corte N/R, con mayor confianza a priori dado el
 * catalogo cerrado, pero sin darlo por sentado: el resultado se observa y
 * documenta como siempre.
 *
 * Limite honesto explicito (a diferencia de Tier3ControlledDemix, que si
 * verifica el efecto real via Tier3ControlledDemixTarget.value()): no hay una
 * forma segura y generica de leer el bytecode ACTUALMENTE activo de una clase
 * real ya cargada sin re-disparar la cadena de ClassFileTransformer (corte H ya
 * probo que retransformClasses no entrega el snapshot exacto). Por eso este
 * corte NO reclama haber verificado que el bytecode redefinido esta activo --
 * solo reclama que la llamada a redefineClasses() con bytes reales, sobre una
 * clase real ya tejida por Mixin, retorna sin excepcion. Ese es exactamente el
 * alcance de la hipotesis que el usuario pidio probar, ni mas ni menos.
 *
 * No requiere BASE_BYTES (Tier3MixinAudit, corte B/C): al ser identity redefine
 * (vivo -> mismos bytes vivos), no hay variante "base" involucrada todavia --
 * eso queda para el siguiente corte, una vez que ESTE corte confirme que el
 * primitivo funciona sobre el target real.
 */
final class Tier3RealTargetRedefineSmoke {
    private static final String TARGET_DOTTED = "net.minecraft.class_1922";
    private static final String FROZEN_NS = "fabric-block-view-api-v2";
    private static final String FROZEN_MIXIN =
            "net.fabricmc.fabric.mixin.blockview.BlockGetterMixin";

    static Map<String, Object> run(Instrumentation inst) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("model", "real_target_redefine_smoke_v1");
        out.put("receiptModel", "tier3_demix_tx_receipt_v1");
        out.put("targetClass", TARGET_DOTTED);
        out.put("targetScope", "real_minecraft_mixin_target");
        out.put("touchesMinecraftClasses", Boolean.TRUE);
        out.put("touchesMixinTargets", Boolean.TRUE);
        out.put("frozenCandidateNs", FROZEN_NS);
        out.put("frozenCandidateMixin", FROZEN_MIXIN);
        out.put("hypothesis",
                "existing_tx_infra_sufficient_for_real_redefine_on_non_structural_target");
        out.put("verificationModel", "redefine_call_success_only_no_behavioral_probe");

        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        List<Object> steps = new ArrayList<Object>();
        receipt.put("model", "tier3_demix_tx_receipt_v1");
        receipt.put("txId", "real-target-redefine-smoke-corte-w");
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
        if (liveBytes == null) {
            out.put("status", "unsupported");
            out.put("reason", "live_bytes_missing");
            receipt.put("status", "UNSUPPORTED");
            return out;
        }
        out.put("liveSha256", sha256(liveBytes));
        out.put("liveSize", Long.valueOf(liveBytes.length));
        int preMethods = target.getDeclaredMethods().length;
        int preFields = target.getDeclaredFields().length;
        out.put("preDeclaredMethods", Long.valueOf(preMethods));
        out.put("preDeclaredFields", Long.valueOf(preFields));

        try {
            redefine(inst, target, liveBytes);
            steps.add(step("redefine_identity_on_real_target", "OK",
                    "redefineClasses() real sobre " + TARGET_DOTTED + " con sus propios bytes vivos (identity)."));
            int postMethods = target.getDeclaredMethods().length;
            int postFields = target.getDeclaredFields().length;
            out.put("postDeclaredMethods", Long.valueOf(postMethods));
            out.put("postDeclaredFields", Long.valueOf(postFields));
            boolean schemaStable = postMethods == preMethods && postFields == preFields;
            out.put("schemaStable", Boolean.valueOf(schemaStable));
            steps.add(step("verify_schema_stable", schemaStable ? "OK" : "FAILED",
                    "Conteo de metodos/campos declarados no cambio tras el redefine identity."));
            receipt.put("status", "OK");
            out.put("status", "ok");
            return out;
        } catch (Throwable t) {
            out.put("status", "failed");
            out.put("error", t.getClass().getSimpleName());
            out.put("message", String.valueOf(t.getMessage()));
            receipt.put("status", "FAILED");
            steps.add(step("redefine_identity_on_real_target", "FAILED",
                    t.getClass().getSimpleName() + ": " + t.getMessage()));
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

    private Tier3RealTargetRedefineSmoke() {}
}
