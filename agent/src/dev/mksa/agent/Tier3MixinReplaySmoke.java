package dev.mksa.agent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T3 corte AK: prueba en dos pasos del motor de replay selectivo
 * (Tier3MixinReplay), sobre candidatos reales de este proyecto -- ninguno
 * sintetico.
 *
 * Paso "fidelity" (net.minecraft.class_1922, sin exclusion): confirma que
 * replay() con el conjunto de exclusion vacio reproduce EXACTAMENTE (sha256)
 * lo que Mixin ya hizo organicamente (Tier3LiveCapture), condicion necesaria
 * antes de confiar en el mecanismo sobre un target co-owned donde no hay una
 * "respuesta conocida" previa para comparar byte a byte.
 *
 * Paso "structural" (net.minecraft.class_2586, co-owned por
 * fabric-block-view-api-v2 + entity_model_features + entity_texture_features
 * + fabric-data-attachment-api-v1, cortes AI/AJ): excluye el mixin real de
 * fabric-block-view-api-v2 (BlockEntityMixin) y verifica ESTRUCTURALMENTE
 * (fields/methods name+desc, via ASM real resuelto por reflexion, sin
 * hardcodear los FQCN de los co-owners sobrevivientes) que el resultado es
 * un subconjunto de lo que la carga organica completa produjo y que algo
 * realmente se excluyo -- despues redefine real + rollback en finally.
 */
final class Tier3MixinReplaySmoke {

    private static final String FIDELITY_TARGET = "net.minecraft.class_1922";

    private static final String STRUCTURAL_TARGET = "net.minecraft.class_2586";
    private static final String STRUCTURAL_VICTIM_MIXIN =
            "net.fabricmc.fabric.mixin.blockview.BlockEntityMixin";

    static Map<String, Object> run(Instrumentation inst) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("model", "tier3_mixin_replay_smoke_v1");

        if (inst == null) {
            out.put("status", "unsupported");
            out.put("reason", "instrumentation_missing");
            return out;
        }

        Map<String, Object> fidelity = runFidelityStep(inst);
        out.put("fidelity", fidelity);

        Map<String, Object> structural = runStructuralStep(inst);
        out.put("structural", structural);

        boolean fidelityOk = "ok".equals(fidelity.get("status"));
        boolean structuralOk = Boolean.TRUE.equals(structural.get("ok"));
        out.put("status", (fidelityOk && structuralOk) ? "ok" : "failed");
        return out;
    }

    private static Map<String, Object> runFidelityStep(Instrumentation inst) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("target", FIDELITY_TARGET);
        Class<?> anchor = resolveLoadedClass(inst, FIDELITY_TARGET);
        if (anchor == null) {
            m.put("status", "unsupported");
            m.put("reason", "target_not_loaded");
            return m;
        }
        byte[] baseBytes = Tier3MixinAudit.baseBytes(FIDELITY_TARGET);
        if (baseBytes == null) {
            m.put("status", "unsupported");
            m.put("reason", "base_bytes_missing_call_mixinPlan_first");
            return m;
        }
        byte[] liveBytes = Tier3LiveCapture.get(FIDELITY_TARGET);
        m.put("liveBytesAvailable", Boolean.valueOf(liveBytes != null));

        Tier3MixinReplay.Outcome outcome = Tier3MixinReplay.replay(
                anchor, FIDELITY_TARGET, baseBytes, Collections.<String>emptySet());
        m.put("replayOk", Boolean.valueOf(outcome.ok));
        m.put("allMixinClassNames", outcome.allMixinClassNames);
        m.put("excludedMixinClassNames", outcome.excludedMixinClassNames);
        m.put("appliedMixinClassNames", outcome.appliedMixinClassNames);
        m.put("framesRecomputed", Boolean.valueOf(outcome.framesRecomputed));
        if (!outcome.ok) {
            m.put("status", "failed");
            m.put("failedStep", outcome.failedStep);
            m.put("error", outcome.error);
            m.put("message", outcome.message);
            return m;
        }
        m.put("replaySha256", sha256(outcome.bytes));
        m.put("replaySize", Long.valueOf(outcome.bytes.length));
        if (liveBytes != null) {
            m.put("liveSha256", sha256(liveBytes));
            m.put("liveSize", Long.valueOf(liveBytes.length));
            boolean matches = sha256(outcome.bytes).equals(sha256(liveBytes));
            m.put("matchesLive", Boolean.valueOf(matches));
            m.put("status", matches ? "ok" : "mismatch");
            if (!matches) dumpDebug(outcome.bytes, liveBytes);
        } else {
            m.put("matchesLive", null);
            m.put("status", "unsupported");
            m.put("reason", "live_bytes_missing");
        }
        return m;
    }

    private static Map<String, Object> runStructuralStep(Instrumentation inst) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("target", STRUCTURAL_TARGET);
        m.put("excludedMixin", STRUCTURAL_VICTIM_MIXIN);
        Class<?> anchor = resolveLoadedClass(inst, STRUCTURAL_TARGET);
        if (anchor == null) {
            m.put("ok", Boolean.FALSE);
            m.put("status", "unsupported");
            m.put("reason", "target_not_loaded");
            return m;
        }
        byte[] baseBytes = Tier3MixinAudit.baseBytes(STRUCTURAL_TARGET);
        if (baseBytes == null) {
            m.put("ok", Boolean.FALSE);
            m.put("status", "unsupported");
            m.put("reason", "base_bytes_missing_call_mixinPlan_first");
            return m;
        }
        byte[] liveBytes = Tier3LiveCapture.get(STRUCTURAL_TARGET);
        if (liveBytes == null) {
            m.put("ok", Boolean.FALSE);
            m.put("status", "unsupported");
            m.put("reason", "live_bytes_missing");
            return m;
        }

        Set<String> exclude = new HashSet<String>();
        exclude.add(STRUCTURAL_VICTIM_MIXIN);
        Tier3MixinReplay.Outcome outcome = Tier3MixinReplay.replay(anchor, STRUCTURAL_TARGET, baseBytes, exclude);
        m.put("replayOk", Boolean.valueOf(outcome.ok));
        m.put("allMixinClassNames", outcome.allMixinClassNames);
        m.put("excludedMixinClassNames", outcome.excludedMixinClassNames);
        m.put("appliedMixinClassNames", outcome.appliedMixinClassNames);
        m.put("framesRecomputed", Boolean.valueOf(outcome.framesRecomputed));
        if (!outcome.ok) {
            m.put("ok", Boolean.FALSE);
            m.put("status", "failed");
            m.put("failedStep", outcome.failedStep);
            m.put("error", outcome.error);
            m.put("message", outcome.message);
            return m;
        }
        if (!outcome.excludedMixinClassNames.contains(STRUCTURAL_VICTIM_MIXIN)) {
            m.put("ok", Boolean.FALSE);
            m.put("status", "failed");
            m.put("reason", "victim_mixin_not_found_among_real_mixins_for_target");
            return m;
        }

        ClassLoader anchorLoader = anchor.getClassLoader();
        Set<String> baseMembers = memberKeys(anchorLoader, baseBytes);
        Set<String> liveMembers = memberKeys(anchorLoader, liveBytes);
        Set<String> replayedMembers = memberKeys(anchorLoader, outcome.bytes);
        if (baseMembers == null || liveMembers == null || replayedMembers == null) {
            m.put("ok", Boolean.FALSE);
            m.put("status", "failed");
            m.put("reason", "member_key_extraction_failed");
            return m;
        }

        Set<String> addedByAllMixins = new HashSet<String>(liveMembers);
        addedByAllMixins.removeAll(baseMembers);
        Set<String> addedByReplay = new HashSet<String>(replayedMembers);
        addedByReplay.removeAll(baseMembers);

        Set<String> excludedFromReplay = new HashSet<String>(addedByAllMixins);
        excludedFromReplay.removeAll(addedByReplay);
        Set<String> unexpectedInReplay = new HashSet<String>(addedByReplay);
        unexpectedInReplay.removeAll(addedByAllMixins);

        m.put("addedByAllMixinsCount", Long.valueOf(addedByAllMixins.size()));
        m.put("addedByReplayCount", Long.valueOf(addedByReplay.size()));
        m.put("excludedFromReplay", new ArrayList<String>(excludedFromReplay));
        m.put("unexpectedInReplay", new ArrayList<String>(unexpectedInReplay));

        boolean subsetOk = unexpectedInReplay.isEmpty();
        boolean somethingExcludedOk = !excludedFromReplay.isEmpty();
        m.put("replayIsSubsetOfLive", Boolean.valueOf(subsetOk));
        m.put("somethingWasExcluded", Boolean.valueOf(somethingExcludedOk));

        boolean redefineOk = false;
        String redefineError = null;
        try {
            inst.redefineClasses(new ClassDefinition(anchor, outcome.bytes));
            redefineOk = true;
        } catch (Throwable t) {
            redefineError = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            try {
                inst.redefineClasses(new ClassDefinition(anchor, liveBytes));
            } catch (Throwable t) {
                m.put("rollbackError", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        m.put("redefineOk", Boolean.valueOf(redefineOk));
        if (redefineError != null) m.put("redefineError", redefineError);

        boolean ok = subsetOk && somethingExcludedOk && redefineOk;
        m.put("ok", Boolean.valueOf(ok));
        m.put("status", ok ? "ok" : "failed");
        return m;
    }

    private static Set<String> memberKeys(ClassLoader anchor, byte[] classBytes) {
        try {
            Class<?> classReaderClass = Class.forName("org.objectweb.asm.ClassReader", true, anchor);
            Constructor<?> crCtor = classReaderClass.getConstructor(byte[].class);
            crCtor.setAccessible(true);
            Object classReader = crCtor.newInstance((Object) classBytes);
            Class<?> classNodeClass = Class.forName("org.objectweb.asm.tree.ClassNode", true, anchor);
            Constructor<?> cnCtor = classNodeClass.getConstructor();
            cnCtor.setAccessible(true);
            Object classNode = cnCtor.newInstance();
            Class<?> classVisitorClass = Class.forName("org.objectweb.asm.ClassVisitor", true, anchor);
            Method acceptMethod = classReaderClass.getMethod("accept", classVisitorClass, int.class);
            acceptMethod.setAccessible(true);
            acceptMethod.invoke(classReader, classNode, Integer.valueOf(0));

            Set<String> out = new HashSet<String>();
            Field fieldsField = classNodeClass.getField("fields");
            fieldsField.setAccessible(true);
            List<?> fields = (List<?>) fieldsField.get(classNode);
            for (Object f : fields) {
                Field nameF = f.getClass().getField("name");
                nameF.setAccessible(true);
                Field descF = f.getClass().getField("desc");
                descF.setAccessible(true);
                out.add("F:" + nameF.get(f) + ":" + descF.get(f));
            }
            Field methodsField = classNodeClass.getField("methods");
            methodsField.setAccessible(true);
            List<?> methods = (List<?>) methodsField.get(classNode);
            for (Object mn : methods) {
                Field nameF = mn.getClass().getField("name");
                nameF.setAccessible(true);
                Field descF = mn.getClass().getField("desc");
                descF.setAccessible(true);
                out.add("M:" + nameF.get(mn) + ":" + descF.get(mn));
            }
            // ClassNode.interfaces (List<String>, nombres internos) -- mixins que
            // solo declaran "implements" sin agregar ningun field/method propio
            // (ej. BlockEntityMixin, marker-only) contribuyen EXCLUSIVAMENTE aqui;
            // sin esto el chequeo estructural es ciego a su exclusion.
            Field interfacesField = classNodeClass.getField("interfaces");
            interfacesField.setAccessible(true);
            List<?> interfaces = (List<?>) interfacesField.get(classNode);
            for (Object itf : interfaces) {
                out.add("I:" + itf);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void dumpDebug(byte[] replayBytes, byte[] liveBytes) {
        try {
            String dir = System.getProperty("java.io.tmpdir");
            java.io.File rf = new java.io.File(dir, "mksa-tier3ak-replay.class");
            java.io.File lf = new java.io.File(dir, "mksa-tier3ak-live.class");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(rf)) { out.write(replayBytes); }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(lf)) { out.write(liveBytes); }
        } catch (Throwable ignored) {
        }
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

    private Tier3MixinReplaySmoke() {}
}
