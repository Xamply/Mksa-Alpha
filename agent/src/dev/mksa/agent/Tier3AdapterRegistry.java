package dev.mksa.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T3 Plan Maestro Parte 2: Registro de adaptadores de toggle y generador de esqueletos (Tier3AdapterRegistry).
 */
public final class Tier3AdapterRegistry {

    public static final Tier3AdapterRegistry INSTANCE = new Tier3AdapterRegistry();

    private final Map<String, HotToggleAdapter> adapters =
            new ConcurrentHashMap<String, HotToggleAdapter>();

    private Tier3AdapterRegistry() {}

    public void register(String namespace, HotToggleAdapter adapter) {
        if (namespace != null && adapter != null) {
            adapters.put(namespace, adapter);
        }
    }

    public HotToggleAdapter getAdapter(String namespace) {
        return namespace != null ? adapters.get(namespace) : null;
    }

    public Map<String, Object> generateSkeleton(String namespace, String surface) {
        Map<String, Object> skeleton = new LinkedHashMap<String, Object>();
        skeleton.put("namespace", namespace);
        skeleton.put("surface", surface);
        skeleton.put("adapterClassName", "dev.mksa.agent.adapters." + capitalize(namespace) + "HotAdapter");
        skeleton.put("status", "skeleton_generated");
        skeleton.put("ok", Boolean.FALSE);
        skeleton.put("code", "ADAPTER_NOT_IMPLEMENTED");
        skeleton.put("retryable", Boolean.TRUE);
        skeleton.put("codeStub",
                "public class " + capitalize(namespace) + "HotAdapter implements HotToggleAdapter {\n"
              + "    public Map<String, Object> probe(Map<String, Object> ctx) { Map<String, Object> m = new LinkedHashMap<String, Object>(); m.put(\"ok\", false); m.put(\"code\", \"ADAPTER_NOT_IMPLEMENTED\"); m.put(\"surface\", \"" + surface + "\"); m.put(\"retryable\", true); return m; }\n"
              + "    public Map<String, Object> snapshot(Map<String, Object> ctx) { throw new UnsupportedOperationException(\"ADAPTER_NOT_IMPLEMENTED\"); }\n"
              + "    public Map<String, Object> disable(Map<String, Object> ctx, Map<String, Object> snap) { Map<String, Object> m = new LinkedHashMap<String, Object>(); m.put(\"ok\", false); m.put(\"code\", \"ADAPTER_NOT_IMPLEMENTED\"); m.put(\"retryable\", true); return m; }\n"
              + "    public Map<String, Object> enable(Map<String, Object> ctx, Map<String, Object> snap) { Map<String, Object> m = new LinkedHashMap<String, Object>(); m.put(\"ok\", false); m.put(\"code\", \"ADAPTER_NOT_IMPLEMENTED\"); m.put(\"retryable\", true); return m; }\n"
              + "    public Map<String, Object> verifyDisabled(Map<String, Object> ctx) { Map<String, Object> m = new LinkedHashMap<String, Object>(); m.put(\"ok\", false); m.put(\"code\", \"ADAPTER_NOT_IMPLEMENTED\"); return m; }\n"
              + "    public Map<String, Object> verifyEnabled(Map<String, Object> ctx) { Map<String, Object> m = new LinkedHashMap<String, Object>(); m.put(\"ok\", false); m.put(\"code\", \"ADAPTER_NOT_IMPLEMENTED\"); return m; }\n"
              + "    public Map<String, Object> rollback(Map<String, Object> ctx, Map<String, Object> snap) { Map<String, Object> m = new LinkedHashMap<String, Object>(); m.put(\"ok\", false); m.put(\"code\", \"ADAPTER_NOT_IMPLEMENTED\"); return m; }\n"
              + "}\n"
        );
        return skeleton;
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return "Mod";
        String clean = str.replaceAll("[^a-zA-Z0-9]", "");
        if (clean.isEmpty()) return "Mod";
        return Character.toUpperCase(clean.charAt(0)) + clean.substring(1);
    }
}
