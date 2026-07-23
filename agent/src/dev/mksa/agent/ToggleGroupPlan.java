package dev.mksa.agent;

import java.util.*;

/**
 * Sección 3: Plan transaccional inmutable para grupo de mods (ToggleGroupPlan).
 * Contiene el orden topológico de ejecución, rollback de grupo y asignación de estrategias.
 */
public final class ToggleGroupPlan {

    public final String operationId;
    public final String requestedNamespace;
    public final List<String> groupMembers;
    public final List<String> enableOrder;
    public final Map<String, Set<String>> targetsByMod;
    public final Set<String> sharedClasses;
    public final long timestamp;

    public ToggleGroupPlan(String requestedNamespace, List<String> groupMembers, List<String> enableOrder,
                           Map<String, Set<String>> targetsByMod, Set<String> sharedClasses) {
        this.operationId = "grp-" + UUID.randomUUID().toString().substring(0, 8);
        this.requestedNamespace = requestedNamespace;
        this.groupMembers = Collections.unmodifiableList(new ArrayList<String>(groupMembers));
        this.enableOrder = Collections.unmodifiableList(new ArrayList<String>(enableOrder));
        this.targetsByMod = Collections.unmodifiableMap(new LinkedHashMap<String, Set<String>>(targetsByMod));
        this.sharedClasses = Collections.unmodifiableSet(new LinkedHashSet<String>(sharedClasses));
        this.timestamp = System.currentTimeMillis();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("operationId", operationId);
        m.put("requestedNamespace", requestedNamespace);
        m.put("groupMembers", groupMembers);
        m.put("enableOrder", enableOrder);
        m.put("sharedClassesCount", sharedClasses.size());
        m.put("timestamp", timestamp);
        return m;
    }
}
