package dev.mksa.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

final class Tier3RedefSmoke {
    private static final byte[] ORIGINAL = ascii("original");
    private static final byte[] PATCHED = ascii("patched!");

    static Map<String, Object> run(Instrumentation inst) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("model", "controlled_redefine_smoke_v1");
        out.put("targetClass", Tier3RedefSmokeTarget.class.getName());
        out.put("targetScope", "agent_private_test_class");
        out.put("touchesMinecraftClasses", Boolean.FALSE);
        out.put("touchesMixinTargets", Boolean.FALSE);

        if (inst == null) {
            out.put("status", "unsupported");
            out.put("redefineSupported", Boolean.FALSE);
            out.put("reason", "instrumentation_missing");
            return out;
        }
        boolean redefineSupported = inst.isRedefineClassesSupported();
        boolean modifiable = false;
        try {
            modifiable = inst.isModifiableClass(Tier3RedefSmokeTarget.class);
        } catch (Throwable ignored) {
        }
        out.put("redefineSupported", Boolean.valueOf(redefineSupported));
        out.put("modifiableClass", Boolean.valueOf(modifiable));
        if (!redefineSupported || !modifiable) {
            out.put("status", "unsupported");
            out.put("reason", !redefineSupported ? "redefine_classes_not_supported" : "target_not_modifiable");
            return out;
        }

        byte[] originalBytes = null;
        boolean patchedApplied = false;
        boolean rollbackAttempted = false;
        boolean rollbackOk = false;
        try {
            String pre = Tier3RedefSmokeTarget.value();
            originalBytes = readClassBytes();
            byte[] patchedBytes = patchLiteral(originalBytes, ORIGINAL, PATCHED);
            out.put("preValue", pre);
            out.put("originalSha256", sha256(originalBytes));
            out.put("patchedSha256", sha256(patchedBytes));
            out.put("originalSize", Long.valueOf(originalBytes.length));
            out.put("patchedSize", Long.valueOf(patchedBytes.length));

            redefine(inst, patchedBytes);
            patchedApplied = true;
            String patched = Tier3RedefSmokeTarget.value();
            boolean patchedOk = "patched!".equals(patched);
            out.put("patchedValue", patched);
            out.put("patchedOk", Boolean.valueOf(patchedOk));
            if (!patchedOk) {
                out.put("status", "patched_verification_failed");
                return out;
            }

            rollbackAttempted = true;
            redefine(inst, originalBytes);
            rollbackOk = "original".equals(Tier3RedefSmokeTarget.value());
            out.put("rollbackValue", Tier3RedefSmokeTarget.value());
            out.put("rollbackOk", Boolean.valueOf(rollbackOk));
            out.put("status", rollbackOk ? "ok" : "rollback_verification_failed");
            return out;
        } catch (Throwable t) {
            out.put("status", "failed");
            out.put("error", t.getClass().getSimpleName());
            out.put("message", String.valueOf(t.getMessage()));
            return out;
        } finally {
            if (patchedApplied && !rollbackOk && originalBytes != null) {
                rollbackAttempted = true;
                try {
                    redefine(inst, originalBytes);
                    rollbackOk = "original".equals(Tier3RedefSmokeTarget.value());
                } catch (Throwable t) {
                    out.put("rollbackError", t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
            out.put("rollbackAttempted", Boolean.valueOf(rollbackAttempted));
            out.put("finalValue", Tier3RedefSmokeTarget.value());
            out.put("finalRestored", Boolean.valueOf("original".equals(Tier3RedefSmokeTarget.value())));
            if (!out.containsKey("rollbackOk")) out.put("rollbackOk", Boolean.valueOf(rollbackOk));
        }
    }

    private static void redefine(Instrumentation inst, byte[] bytes) throws Exception {
        inst.redefineClasses(new ClassDefinition(Tier3RedefSmokeTarget.class, bytes));
    }

    private static byte[] readClassBytes() throws IOException {
        String res = "/" + Tier3RedefSmokeTarget.class.getName().replace('.', '/') + ".class";
        InputStream in = Tier3RedefSmokeTarget.class.getResourceAsStream(res);
        if (in == null) throw new IOException("class resource missing: " + res);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static byte[] patchLiteral(byte[] original, byte[] from, byte[] to) throws IOException {
        if (from.length != to.length) throw new IOException("literal length mismatch");
        byte[] copy = original.clone();
        int at = -1;
        for (int i = 0; i <= copy.length - from.length; i++) {
            boolean match = true;
            for (int j = 0; j < from.length; j++) {
                if (copy[i + j] != from[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                if (at >= 0) throw new IOException("literal not unique");
                at = i;
            }
        }
        if (at < 0) throw new IOException("literal not found");
        System.arraycopy(to, 0, copy, at, to.length);
        return copy;
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

    private static byte[] ascii(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) out[i] = (byte) s.charAt(i);
        return out;
    }

    private Tier3RedefSmoke() {}
}
