package dev.mksa.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Corte H (cerrado): demuestra que Instrumentation.retransformClasses NO entrega
 * un snapshot exacto del bytecode vivo ya redefinido (capturedMatchesMixed=false,
 * capturedMatchesBase=false) -- hallazgo negativo, mantenido como evidencia
 * tecnica. SUPERADO como mecanismo de captura por el corte I:
 * {@link Tier3LiveCapture} captura pasivamente en la PRIMERA definicion real
 * (premain, antes de que cargue cualquier clase del juego), no via retransform.
 * Este smoke nunca alimento txGate.runtimeTransformedBytesCaptured (ver
 * Tier3MixinAudit.buildTxGate) -- quedaba hardcodeado a false antes del corte I
 * -- asi que no hay regresion que gestionar al dejarlo sin cambios funcionales.
 */
final class Tier3RetransformCaptureSmoke {
    private static final byte[] MIXED = ascii("mixedON");
    private static final byte[] BASE = ascii("baseOFF");

    static Map<String, Object> run(Instrumentation inst) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("model", "tier3_retransform_capture_smoke_v1");
        out.put("targetClass", Tier3ControlledDemixTarget.class.getName());
        out.put("targetScope", "agent_private_test_class");
        out.put("touchesMinecraftClasses", Boolean.FALSE);
        out.put("touchesMixinTargets", Boolean.FALSE);

        if (inst == null) {
            out.put("status", "unsupported");
            out.put("reason", "instrumentation_missing");
            out.put("retransformSupported", Boolean.FALSE);
            return out;
        }

        boolean redefineSupported = inst.isRedefineClassesSupported();
        boolean retransformSupported = inst.isRetransformClassesSupported();
        boolean modifiable = false;
        try {
            modifiable = inst.isModifiableClass(Tier3ControlledDemixTarget.class);
        } catch (Throwable ignored) {
        }
        out.put("redefineSupported", Boolean.valueOf(redefineSupported));
        out.put("retransformSupported", Boolean.valueOf(retransformSupported));
        out.put("modifiableClass", Boolean.valueOf(modifiable));
        if (!redefineSupported || !retransformSupported || !modifiable) {
            out.put("status", "unsupported");
            out.put("reason", !redefineSupported ? "redefine_classes_not_supported"
                    : (!retransformSupported ? "retransform_classes_not_supported" : "target_not_modifiable"));
            return out;
        }

        CaptureTransformer capture = new CaptureTransformer(
                Tier3ControlledDemixTarget.class.getName().replace('.', '/'));
        byte[] mixedBytes = null;
        byte[] baseBytes = null;
        boolean transformerInstalled = false;
        try {
            mixedBytes = readClassBytes();
            baseBytes = patchLiteral(mixedBytes, MIXED, BASE);
            out.put("mixedSha256", sha256(mixedBytes));
            out.put("baseSha256", sha256(baseBytes));
            out.put("mixedSize", Long.valueOf(mixedBytes.length));
            out.put("baseSize", Long.valueOf(baseBytes.length));

            redefine(inst, mixedBytes);
            out.put("preValue", Tier3ControlledDemixTarget.value());
            redefine(inst, baseBytes);
            out.put("valueBeforeRetransform", Tier3ControlledDemixTarget.value());

            inst.addTransformer(capture, true);
            transformerInstalled = true;
            inst.retransformClasses(Tier3ControlledDemixTarget.class);

            byte[] captured = capture.captured;
            out.put("captureCount", Long.valueOf(capture.count));
            out.put("valueAfterRetransform", Tier3ControlledDemixTarget.value());
            out.put("retransformMutatedClass",
                    Boolean.valueOf(!"baseOFF".equals(Tier3ControlledDemixTarget.value())));
            if (captured != null) {
                String capturedSha = sha256(captured);
                out.put("capturedSha256", capturedSha);
                out.put("capturedSize", Long.valueOf(captured.length));
                out.put("capturedMatchesMixed", Boolean.valueOf(capturedSha.equals(sha256(mixedBytes))));
                out.put("capturedMatchesBase", Boolean.valueOf(capturedSha.equals(sha256(baseBytes))));
            }
            out.put("status", "ok");
            return out;
        } catch (Throwable t) {
            out.put("status", "failed");
            out.put("error", t.getClass().getSimpleName());
            out.put("message", String.valueOf(t.getMessage()));
            return out;
        } finally {
            if (transformerInstalled) {
                try {
                    inst.removeTransformer(capture);
                } catch (Throwable ignored) {
                }
            }
            if (mixedBytes != null) {
                try {
                    redefine(inst, mixedBytes);
                } catch (Throwable ignored) {
                }
            }
            out.put("finalValue", Tier3ControlledDemixTarget.value());
            out.put("finalRestored", Boolean.valueOf("mixedON".equals(Tier3ControlledDemixTarget.value())));
        }
    }

    private static void redefine(Instrumentation inst, byte[] bytes) throws Exception {
        inst.redefineClasses(new ClassDefinition(Tier3ControlledDemixTarget.class, bytes));
    }

    private static byte[] readClassBytes() throws IOException {
        String res = "/" + Tier3ControlledDemixTarget.class.getName().replace('.', '/') + ".class";
        InputStream in = Tier3ControlledDemixTarget.class.getResourceAsStream(res);
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
            in.close();
        }
    }

    private static byte[] patchLiteral(byte[] bytes, byte[] from, byte[] to) {
        if (from.length != to.length) throw new IllegalArgumentException("literal length mismatch");
        byte[] out = bytes.clone();
        int idx = indexOf(out, from);
        if (idx < 0) throw new IllegalStateException("literal not found");
        System.arraycopy(to, 0, out, idx, to.length);
        return out;
    }

    private static int indexOf(byte[] bytes, byte[] needle) {
        outer:
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
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
        try {
            return s.getBytes("US-ASCII");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private static final class CaptureTransformer implements ClassFileTransformer {
        final String targetInternalName;
        volatile byte[] captured;
        volatile long count;

        CaptureTransformer(String targetInternalName) {
            this.targetInternalName = targetInternalName;
        }

        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (targetInternalName.equals(className)) {
                count++;
                captured = classfileBuffer == null ? null : classfileBuffer.clone();
            }
            return null;
        }
    }

    private Tier3RetransformCaptureSmoke() {}
}
