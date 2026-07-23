package dev.mksa.agent;

import java.util.*;

/**
 * Sección 2: Grafo de dependencias y cierre de grupos transaccionales en runtime.
 * Lee las relaciones declaradas por los mods (depends, recommends, mixin co-owners)
 * y resuelve los grupos atómicos de desactivación/reactivación en orden topológico.
 */
public final class DependencyGraph {

    public static final class ModNode {
        public final String id;
        public final Set<String> dependsOn = new LinkedHashSet<String>();
        public final Set<String> requiredBy = new LinkedHashSet<String>();
        public final Set<String> coOwners = new LinkedHashSet<String>();
        public boolean isLoaded = true;

        public ModNode(String id) {
            this.id = id;
        }
    }

    private static final Map<String, ModNode> nodes = new LinkedHashMap<String, ModNode>();

    private DependencyGraph() {}

    /**
     * Inicializa o actualiza el grafo de dependencias a partir de Boot.mods.
     */
    public static synchronized void buildGraph() {
        nodes.clear();
        if (Boot.mods == null) return;

        // 1. Registrar todos los nodos
        for (Map<String, Object> m : Boot.mods) {
            String id = (String) m.get("id");
            if (id != null && !id.isEmpty()) {
                nodes.put(id, new ModNode(id));
            }
        }

        // 2. Poblar relaciones
        for (Map<String, Object> m : Boot.mods) {
            String id = (String) m.get("id");
            if (id == null) continue;
            ModNode node = nodes.get(id);
            if (node == null) continue;

            Object dependsObj = m.get("depends");
            if (dependsObj instanceof Map) {
                Map<?, ?> depMap = (Map<?, ?>) dependsObj;
                for (Object depKey : depMap.keySet()) {
                    String depId = String.valueOf(depKey);
                    if (nodes.containsKey(depId) && !depId.equals(id) && !"minecraft".equals(depId) && !"java".equals(depId) && !"fabricloader".equals(depId)) {
                        node.dependsOn.add(depId);
                        ModNode parentNode = nodes.get(depId);
                        if (parentNode != null) {
                            parentNode.requiredBy.add(id);
                        }
                    }
                }
            } else if (dependsObj instanceof List) {
                List<?> depList = (List<?>) dependsObj;
                for (Object depItem : depList) {
                    String depId = String.valueOf(depItem);
                    if (nodes.containsKey(depId) && !depId.equals(id) && !"minecraft".equals(depId) && !"java".equals(depId) && !"fabricloader".equals(depId)) {
                        node.dependsOn.add(depId);
                        ModNode parentNode = nodes.get(depId);
                        if (parentNode != null) {
                            parentNode.requiredBy.add(id);
                        }
                    }
                }
            }

            // Relaciones conocidas de runtime / mixin co-ownership
            if ("waystones".equals(id)) {
                node.dependsOn.add("balm");
                ModNode balmNode = nodes.get("balm");
                if (balmNode != null) balmNode.requiredBy.add("waystones");
            } else if ("biomesoplenty".equals(id)) {
                node.dependsOn.add("terrablender");
                ModNode tbNode = nodes.get("terrablender");
                if (tbNode != null) tbNode.requiredBy.add("biomesoplenty");
            }
        }
    }

    /**
     * Retorna el mensaje de advertencia si se intenta desactivar un mod padre
     * mientras sus mods dependientes continúan activos.
     */
    public static synchronized String checkSingleDisableAllowed(String namespace) {
        buildGraph();
        ModNode node = nodes.get(namespace);
        if (node == null) return null;

        List<String> activeDependents = new ArrayList<String>();
        for (String reqId : node.requiredBy) {
            Tier3RuntimeState.State state = Tier3RuntimeState.getState(reqId);
            if (state == Tier3RuntimeState.State.ACTIVE || state == Tier3RuntimeState.State.FAILED_ACTIVE) {
                activeDependents.add(reqId);
            }
        }

        if (!activeDependents.isEmpty()) {
            return capitalize(activeDependents.get(0)) + " depende de " + capitalize(namespace) + ". Debes desactivar ambos en grupo.";
        }
        return null;
    }

    /**
     * Calcula el grupo de desactivación (el mod solicitado + sus dependencias o dependientes necesarios).
     */
    public static synchronized List<String> getDisableGroup(String namespace) {
        buildGraph();
        Set<String> group = new LinkedHashSet<String>();
        collectDisableGroup(namespace, group);
        return new ArrayList<String>(group);
    }

    private static void collectDisableGroup(String current, Set<String> group) {
        if (!group.add(current)) return;
        ModNode node = nodes.get(current);
        if (node == null) return;

        // Si deshabilitamos Biomes o' Plenty, incluimos TerraBlender
        for (String depId : node.dependsOn) {
            Tier3RuntimeState.State s = Tier3RuntimeState.getState(depId);
            if (s == Tier3RuntimeState.State.ACTIVE || s == Tier3RuntimeState.State.FAILED_ACTIVE) {
                group.add(depId);
            }
        }
        // Si un mod dependiente está activo, también debe incluirse en la transacción
        for (String reqId : node.requiredBy) {
            Tier3RuntimeState.State s = Tier3RuntimeState.getState(reqId);
            if (s == Tier3RuntimeState.State.ACTIVE || s == Tier3RuntimeState.State.FAILED_ACTIVE) {
                group.add(reqId);
            }
        }
    }

    /**
     * Calcula el grupo de reactivación en orden topológico correcto
     * (los mods base/dependencias primero, los mods dependientes después).
     */
    public static synchronized List<String> getEnableGroup(String namespace) {
        buildGraph();
        List<String> disableGroup = getDisableGroup(namespace);
        // Invertir el orden para reactivar la base primero (ej. TerraBlender -> Biomes o' Plenty)
        List<String> enableGroup = new ArrayList<String>(disableGroup);
        Collections.reverse(enableGroup);
        return enableGroup;
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return "";
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}
