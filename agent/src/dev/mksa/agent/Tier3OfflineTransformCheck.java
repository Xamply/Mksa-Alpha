package dev.mksa.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T3 Fase 9, corte AB (siguiente pregunta directa del usuario tras el corte
 * AA/refutacion de runtime_transformed_bytes_missing): "?el sistema puede
 * SABER que le haria Mixin a una clase, sin esperar a que cargue organicamente
 * en el mundo real?"
 *
 * Confirmado primero en un spike AISLADO (E:/tmp/mixin-real-toggle-spike/, NO
 * este repo) con un mixin sintetico de prueba: IMixinTransformer.transformClassBytes(
 * name, transformedName, basicClass), obtenido reflectivamente via
 * MixinEnvironment.getCurrentEnvironment().getActiveTransformer(), produce
 * bytes IDENTICOS (sha256 exacto) a los que la carga organica real produce
 * despues -- sin interferir con ella. Este corte repite exactamente esa misma
 * prueba, pero sobre el candidato real congelado de este proyecto:
 * fabric-block-view-api-v2, sus 6 targets reales (confirmados por corte AA:
 * 3 cargan en sesion tipica, 3 no -- los tres de rendering especial).
 *
 * Para los targets que YA cargaron organicamente (class_2586, class_1922,
 * class_4538) esto permite comparar sintetico vs. organico real. Para los
 * que NO cargaron (class_853, class_6850, class_11791) esto prueba si el
 * mecanismo puede producir bytes transformados de todas formas, sin que la
 * clase cargue nunca -- exactamente el caso que corte AA dejo sin resolver.
 *
 * Precondicion (igual que corte P/Y/AA): el llamador debe invocar
 * t3.mixinPlan(ns="fabric-block-view-api-v2") ANTES de este comando, para que
 * Tier3MixinAudit.BASE_BYTES tenga bytes base cacheados de los 6 targets
 * (captureBaseBytecode lee por classpath resource, no requiere que la clase
 * haya cargado). El resultado real (match/mismatch, interferencia si la hay)
 * se observa y reporta tal cual sale, sin asumir el resultado de antemano.
 */
final class Tier3OfflineTransformCheck {

    private static final String FROZEN_NS = "fabric-block-view-api-v2";

    private static final String[][] TARGETS = {
            {"net.minecraft.class_2586", "net.fabricmc.fabric.mixin.blockview.BlockEntityMixin"},
            {"net.minecraft.class_1922", "net.fabricmc.fabric.mixin.blockview.BlockGetterMixin"},
            {"net.minecraft.class_4538", "net.fabricmc.fabric.mixin.blockview.LevelReaderMixin"},
            {"net.minecraft.class_853", "net.fabricmc.fabric.mixin.blockview.client.MovingBlockRenderStateMixin"},
            {"net.minecraft.class_6850", "net.fabricmc.fabric.mixin.blockview.client.RenderRegionCacheMixin"},
            {"net.minecraft.class_11791", "net.fabricmc.fabric.mixin.blockview.client.RenderSectionRegionMixin"},
    };

    static Map<String, Object> run(Instrumentation inst) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("model", "tier3_offline_transform_check_v1");
        out.put("frozenCandidateNs", FROZEN_NS);
        out.put("hypothesis",
                "transformClassBytes puede invocarse sinteticamente sobre bytes base de un "
                        + "target Mixin real ANTES de la carga organica y producir bytes "
                        + "identicos (sha256) a la carga organica, sin interferencia");

        if (inst == null) {
            out.put("status", "unsupported");
            out.put("reason", "instrumentation_missing");
            return out;
        }

        Class<?> anchorClass = null;
        String anchorClassName = null;
        for (String[] t : TARGETS) {
            Class<?> c = resolveLoadedClass(inst, t[0]);
            if (c != null) {
                anchorClass = c;
                anchorClassName = t[0];
                break;
            }
        }
        out.put("anchorClass", anchorClassName);
        if (anchorClass == null) {
            out.put("status", "unsupported");
            out.put("reason", "no_target_loaded_to_anchor_classloader");
            return out;
        }

        Object transformer;
        Method transformClassBytesMethod;
        try {
            ClassLoader anchor = anchorClass.getClassLoader();
            Class<?> menvClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", true, anchor);
            Method getCurrentEnvironment = menvClass.getMethod("getCurrentEnvironment");
            Object currentEnv = getCurrentEnvironment.invoke(null);
            if (currentEnv == null) throw new IllegalStateException("getCurrentEnvironment_returned_null");
            Method getActiveTransformer = menvClass.getMethod("getActiveTransformer");
            transformer = getActiveTransformer.invoke(currentEnv);
            if (transformer == null) throw new IllegalStateException("getActiveTransformer_returned_null");
            Class<?> ifaceClass = Class.forName(
                    "org.spongepowered.asm.mixin.transformer.IMixinTransformer", true, anchor);
            transformClassBytesMethod = ifaceClass.getMethod(
                    "transformClassBytes", String.class, String.class, byte[].class);
        } catch (Throwable t) {
            out.put("status", "failed");
            out.put("reason", "transformer_resolution_failed");
            out.put("error", t.getClass().getSimpleName());
            out.put("message", String.valueOf(t.getMessage()));
            return out;
        }
        out.put("transformerResolved", Boolean.TRUE);

        List<Object> targets = new ArrayList<Object>();
        int baseBytesCapturedCount = 0;
        int syntheticCapturedCount = 0;
        int liveBytesCapturedCount = 0;
        int comparableCount = 0;
        int matchCount = 0;
        int mismatchCount = 0;
        boolean interferenceDetected = false;

        for (String[] pair : TARGETS) {
            String target = pair[0];
            String mixin = pair[1];
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("class", target);
            m.put("mixin", mixin);

            boolean loadedBefore = resolveLoadedClass(inst, target) != null;
            m.put("loadedBeforeCheck", Boolean.valueOf(loadedBefore));

            byte[] baseBytes = Tier3MixinAudit.baseBytes(target);
            boolean baseAvailable = baseBytes != null;
            m.put("baseBytesAvailable", Boolean.valueOf(baseAvailable));
            if (baseAvailable) {
                baseBytesCapturedCount++;
                m.put("baseSize", Long.valueOf(baseBytes.length));
                m.put("baseSha256", sha256(baseBytes));
            }

            byte[] syntheticBytes = null;
            if (baseAvailable) {
                try {
                    syntheticBytes = (byte[]) transformClassBytesMethod.invoke(
                            transformer, target, target, baseBytes);
                    m.put("syntheticAttempted", Boolean.TRUE);
                    m.put("syntheticOk", Boolean.TRUE);
                } catch (Throwable t) {
                    m.put("syntheticAttempted", Boolean.TRUE);
                    m.put("syntheticOk", Boolean.FALSE);
                    m.put("syntheticError", t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            } else {
                m.put("syntheticAttempted", Boolean.FALSE);
            }

            boolean loadedAfter = resolveLoadedClass(inst, target) != null;
            m.put("loadedAfterSyntheticCall", Boolean.valueOf(loadedAfter));
            if (loadedAfter != loadedBefore) {
                interferenceDetected = true;
                m.put("interference", Boolean.TRUE);
            }

            if (syntheticBytes != null) {
                syntheticCapturedCount++;
                m.put("syntheticSize", Long.valueOf(syntheticBytes.length));
                m.put("syntheticSha256", sha256(syntheticBytes));
                m.put("syntheticDiffersFromBase",
                        Boolean.valueOf(syntheticBytes.length != baseBytes.length
                                || !sha256(syntheticBytes).equals(sha256(baseBytes))));
            }

            byte[] liveBytes = Tier3LiveCapture.get(target);
            boolean liveAvailable = liveBytes != null;
            m.put("liveBytesAvailable", Boolean.valueOf(liveAvailable));
            if (liveAvailable) {
                liveBytesCapturedCount++;
                m.put("liveSize", Long.valueOf(liveBytes.length));
                m.put("liveSha256", sha256(liveBytes));
            }

            if (syntheticBytes != null && liveAvailable) {
                comparableCount++;
                boolean matches = sha256(syntheticBytes).equals(sha256(liveBytes));
                m.put("syntheticMatchesLive", Boolean.valueOf(matches));
                if (matches) matchCount++; else mismatchCount++;
            } else {
                m.put("syntheticMatchesLive", null);
            }

            targets.add(m);
        }

        out.put("targets", targets);
        out.put("targetsTotal", Long.valueOf(TARGETS.length));
        out.put("baseBytesCapturedCount", Long.valueOf(baseBytesCapturedCount));
        out.put("syntheticCapturedCount", Long.valueOf(syntheticCapturedCount));
        out.put("liveBytesCapturedCount", Long.valueOf(liveBytesCapturedCount));
        out.put("comparableCount", Long.valueOf(comparableCount));
        out.put("matchCount", Long.valueOf(matchCount));
        out.put("mismatchCount", Long.valueOf(mismatchCount));
        out.put("interferenceDetected", Boolean.valueOf(interferenceDetected));
        out.put("status", "ok");
        return out;
    }

    private static Class<?> resolveLoadedClass(Instrumentation inst, String dottedName) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c != null && dottedName.equals(c.getName())) return c;
        }
        return null;
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

    private Tier3OfflineTransformCheck() {}
}
