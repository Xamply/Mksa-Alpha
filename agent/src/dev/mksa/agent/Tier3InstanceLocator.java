package dev.mksa.agent;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * T3 corte AM (fix rendererPools): localizador generico de instancias vivas
 * por recorrido reflexivo del grafo de objetos, con raiz en Boot.clientInstance
 * (el singleton class_310/Minecraft ya capturado en boot para el motor
 * in-process, §3.5). No hay API estandar de Instrumentation para enumerar
 * instancias vivas de una clase arbitraria -- este recorrido acotado (BFS por
 * campos declarados + elementos de Map/Collection/array) es la alternativa
 * sin JVMTI nativo, pensada para reutilizarse en cualquier candidato Tier3
 * futuro con el mismo problema (estado de instancia perdido en un ciclo
 * redefineClasses), no solo class_11228.
 */
final class Tier3InstanceLocator {

    private static final int MAX_DEPTH = 10;
    private static final int MAX_VISITED = 50_000;

    private Tier3InstanceLocator() {
    }

    /** Devuelve la primera instancia vivas de targetClass alcanzable desde
     * Boot.clientInstance, o null si no se encuentra dentro de los limites. */
    static Object locate(Class<?> targetClass) {
        Object root = Boot.clientInstance;
        if (root == null || targetClass == null) {
            return null;
        }

        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<Object, Boolean>();
        ArrayDeque<Object> queueObj = new ArrayDeque<Object>();
        ArrayDeque<Integer> queueDepth = new ArrayDeque<Integer>();
        queueObj.add(root);
        queueDepth.add(Integer.valueOf(0));
        visited.put(root, Boolean.TRUE);

        while (!queueObj.isEmpty()) {
            if (visited.size() > MAX_VISITED) {
                return null;
            }
            Object node = queueObj.poll();
            int depth = queueDepth.poll().intValue();

            if (targetClass.equals(node.getClass())) {
                return node;
            }
            if (depth >= MAX_DEPTH) {
                continue;
            }

            for (Object child : children(node)) {
                if (child == null || visited.containsKey(child)) {
                    continue;
                }
                visited.put(child, Boolean.TRUE);
                queueObj.add(child);
                queueDepth.add(Integer.valueOf(depth + 1));
            }
        }
        return null;
    }

    private static Iterable<Object> children(Object node) {
        java.util.List<Object> out = new java.util.ArrayList<Object>();
        try {
            if (node instanceof Map) {
                for (Object v : ((Map<?, ?>) node).values()) out.add(v);
                return out;
            }
            if (node instanceof Collection) {
                for (Object v : (Collection<?>) node) out.add(v);
                return out;
            }
            if (node.getClass().isArray()) {
                if (!node.getClass().getComponentType().isPrimitive()) {
                    int len = Array.getLength(node);
                    for (int i = 0; i < len; i++) out.add(Array.get(node, i));
                }
                return out;
            }
        } catch (Throwable ignored) {
            return out;
        }

        for (Class<?> c = node.getClass(); c != null; c = c.getSuperclass()) {
            String cn = c.getName();
            if (cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.")
                    || cn.startsWith("sun.") || cn.startsWith("com.sun.")) {
                continue;
            }
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(node);
                    if (v != null) out.add(v);
                } catch (Throwable ignored) {
                    // Campo no accesible (encapsulamiento fuerte del modulo, SecurityManager,
                    // etc.) -- se ignora ese campo puntual, el recorrido sigue.
                }
            }
        }
        return out;
    }
}
