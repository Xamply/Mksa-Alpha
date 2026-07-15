package dev.mksa.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T3 corte Parte 2: smoke test dedicado de
 * Tier3MixinAudit.scanFieldReferencesForSmoke -- confirma, sobre bytecode
 * real, que el escaner de instrucciones nuevo (scanCode) decodifica sin
 * desincronizarse los opcodes de mayor riesgo senalados en el plan:
 * invokeinterface (185, operando de 4 bytes, no 2 como los demas invoke*) y
 * wide (196, 2 o 4 bytes extra segun el opcode ensanchado), ademas de
 * tableswitch/lookupswitch cuando aparecen.
 *
 * No depende de que ningun mod este instalado: siempre escanea un set fijo
 * de clases del propio JDK (java.util.ArrayList/HashMap/Collections/
 * AbstractCollection), todas conocidas por invocar metodos de interfaz
 * (Collection/Map/Iterator) en sus cuerpos -- garantiza cobertura de
 * invokeinterface incluso sin mods cargados. Si hay mods instalados, agrega
 * ademas mixins reales del proyecto (Tier3MixinAudit.sampleMixinBytesForSmoke)
 * como cobertura adicional, best-effort.
 */
final class Tier3FieldScanSmoke {

    private static final String[] JDK_CANDIDATES = {
        "java/util/ArrayList.class",
        "java/util/HashMap.class",
        "java/util/Collections.class",
        "java/util/AbstractCollection.class",
    };

    private static final int MIXIN_SAMPLE_CAP = 20;

    static Map<String, Object> run() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("model", "tier3_field_scan_smoke_v1");

        List<Object> jdkResults = new ArrayList<Object>();
        int jdkOk = 0;
        for (String resource : JDK_CANDIDATES) {
            String dotted = resource.substring(0, resource.length() - ".class".length()).replace('/', '.');
            Map<String, Object> r = scanOne(dotted, loadJdkClassBytes(resource));
            jdkResults.add(r);
            if (Boolean.TRUE.equals(r.get("ok"))) jdkOk++;
        }
        out.put("jdkClasses", jdkResults);
        out.put("jdkClassesScanned", Long.valueOf(jdkResults.size()));
        out.put("jdkClassesOk", Long.valueOf(jdkOk));

        Map<String, byte[]> mixinSamples = Tier3MixinAudit.sampleMixinBytesForSmoke(MIXIN_SAMPLE_CAP);
        List<Object> mixinResults = new ArrayList<Object>();
        int mixinOk = 0;
        for (Map.Entry<String, byte[]> e : mixinSamples.entrySet()) {
            Map<String, Object> r = scanOne(e.getKey(), e.getValue());
            mixinResults.add(r);
            if (Boolean.TRUE.equals(r.get("ok"))) mixinOk++;
        }
        out.put("mixinClasses", mixinResults);
        out.put("mixinClassesScanned", Long.valueOf(mixinResults.size()));
        out.put("mixinClassesOk", Long.valueOf(mixinOk));

        // Las clases JDK son la garantia dura -- siempre presentes, siempre
        // deben parsear limpio. Los mixins son cobertura extra: si no hay
        // ninguno disponible (sin mods instalados) no es una falla del smoke.
        boolean jdkAllOk = jdkOk == jdkResults.size() && !jdkResults.isEmpty();
        boolean mixinAllOk = mixinOk == mixinResults.size();
        boolean ok = jdkAllOk && mixinAllOk;
        out.put("ok", Boolean.valueOf(ok));
        out.put("status", ok ? "ok" : "failed");
        return out;
    }

    private static Map<String, Object> scanOne(String dotted, byte[] classBytes) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("class", dotted);
        if (classBytes == null) {
            r.put("ok", Boolean.FALSE);
            r.put("reason", "class_bytes_unavailable");
            return r;
        }
        r.put("classSize", Long.valueOf(classBytes.length));
        try {
            int fieldRefs = Tier3MixinAudit.scanFieldReferencesForSmoke(classBytes, dotted).size();
            r.put("ok", Boolean.TRUE);
            r.put("fieldRefsFound", Long.valueOf(fieldRefs));
        } catch (Throwable t) {
            r.put("ok", Boolean.FALSE);
            r.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return r;
    }

    private static byte[] loadJdkClassBytes(String resourcePath) {
        try (InputStream in = ClassLoader.getSystemResourceAsStream(resourcePath)) {
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) >= 0) buf.write(chunk, 0, n);
            return buf.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private Tier3FieldScanSmoke() {}
}
