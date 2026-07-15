package dev.mksa.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Tier3ControlledDemix {
    private static final byte[] MIXED = ascii("mixedON");
    private static final byte[] BASE = ascii("baseOFF");

    static Map<String, Object> run(Instrumentation inst) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("model", "controlled_demix_tx_smoke_v1");
        out.put("receiptModel", "tier3_demix_tx_receipt_v1");
        out.put("targetClass", Tier3ControlledDemixTarget.class.getName());
        out.put("targetScope", "agent_private_test_class");
        out.put("touchesMinecraftClasses", Boolean.FALSE);
        out.put("touchesMixinTargets", Boolean.FALSE);

        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        List<Object> steps = new ArrayList<Object>();
        receipt.put("model", "tier3_demix_tx_receipt_v1");
        receipt.put("txId", "controlled-demix-smoke");
        receipt.put("targetClass", Tier3ControlledDemixTarget.class.getName());
        receipt.put("steps", steps);
        out.put("receipt", receipt);

        if (inst == null) {
            out.put("status", "unsupported");
            out.put("redefineSupported", Boolean.FALSE);
            out.put("reason", "instrumentation_missing");
            receipt.put("status", "UNSUPPORTED");
            return out;
        }
        boolean redefineSupported = inst.isRedefineClassesSupported();
        boolean modifiable = false;
        try {
            modifiable = inst.isModifiableClass(Tier3ControlledDemixTarget.class);
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

        byte[] mixedBytes = null;
        byte[] baseBytes = null;
        boolean disabledApplied = false;
        boolean rollbackAttempted = false;
        boolean rollbackOk = false;
        try {
            mixedBytes = readClassBytes();
            baseBytes = patchLiteral(mixedBytes, MIXED, BASE);
            out.put("mixedSha256", sha256(mixedBytes));
            out.put("baseSha256", sha256(baseBytes));
            out.put("mixedSize", Long.valueOf(mixedBytes.length));
            out.put("baseSize", Long.valueOf(baseBytes.length));

            redefine(inst, mixedBytes);
            String pre = Tier3ControlledDemixTarget.value();
            out.put("preValue", pre);
            steps.add(step("capture_current", "OK", "Snapshot de bytes mixed/applied capturado."));
            if (!"mixedON".equals(pre)) {
                throw new IllegalStateException("baseline verification failed: " + pre);
            }

            redefine(inst, baseBytes);
            disabledApplied = true;
            String disabled = Tier3ControlledDemixTarget.value();
            out.put("disableValue", disabled);
            boolean disableOk = "baseOFF".equals(disabled);
            out.put("disableOk", Boolean.valueOf(disableOk));
            steps.add(step("reset_target_to_base", disableOk ? "OK" : "FAILED",
                    "Redefinicion controlada al bytecode base."));
            if (!disableOk) {
                receipt.put("status", "FAILED");
                out.put("status", "disable_verification_failed");
                return out;
            }

            redefine(inst, mixedBytes);
            disabledApplied = false;
            String restored = Tier3ControlledDemixTarget.value();
            out.put("restoreValue", restored);
            boolean restoreOk = "mixedON".equals(restored);
            out.put("restoreOk", Boolean.valueOf(restoreOk));
            steps.add(step("restore_mixin_variant", restoreOk ? "OK" : "FAILED",
                    "Redefinicion de vuelta al bytecode mixed/applied."));
            receipt.put("status", restoreOk ? "OK" : "FAILED");
            out.put("status", restoreOk ? "ok" : "restore_verification_failed");
            return out;
        } catch (Throwable t) {
            out.put("status", "failed");
            out.put("error", t.getClass().getSimpleName());
            out.put("message", String.valueOf(t.getMessage()));
            receipt.put("status", "FAILED");
            steps.add(step("error", "FAILED", t.getClass().getSimpleName() + ": " + t.getMessage()));
            return out;
        } finally {
            if (mixedBytes != null && (disabledApplied || !"mixedON".equals(Tier3ControlledDemixTarget.value()))) {
                rollbackAttempted = true;
                try {
                    redefine(inst, mixedBytes);
                    rollbackOk = "mixedON".equals(Tier3ControlledDemixTarget.value());
                    steps.add(step("rollback_to_mixed", rollbackOk ? "OK" : "FAILED",
                            "Rollback a bytes mixed/applied originales."));
                } catch (Throwable t) {
                    steps.add(step("rollback_to_mixed", "FAILED", t.getClass().getSimpleName() + ": " + t.getMessage()));
                    out.put("rollbackError", t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            } else {
                rollbackOk = "mixedON".equals(Tier3ControlledDemixTarget.value());
            }
            out.put("rollbackAttempted", Boolean.valueOf(rollbackAttempted));
            out.put("rollbackOk", Boolean.valueOf(rollbackOk));
            out.put("finalValue", Tier3ControlledDemixTarget.value());
            out.put("finalRestored", Boolean.valueOf("mixedON".equals(Tier3ControlledDemixTarget.value())));
            receipt.put("rollbackAttempted", Boolean.valueOf(rollbackAttempted));
            receipt.put("rollbackOk", Boolean.valueOf(rollbackOk));
            receipt.put("finalValue", Tier3ControlledDemixTarget.value());
        }
    }

    private static Map<String, Object> step(String action, String status, String detail) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("action", action);
        m.put("status", status);
        m.put("detail", detail);
        return m;
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

    private Tier3ControlledDemix() {}
}
