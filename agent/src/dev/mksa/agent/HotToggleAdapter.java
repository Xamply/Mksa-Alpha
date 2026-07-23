package dev.mksa.agent;

import java.util.Map;

/**
 * T3 Plan Maestro Parte 2: Interfaz para adaptadores hot (HotToggleAdapter).
 * Permite desmontar superficies complejas (JNI, singletons, hilos propios).
 */
public interface HotToggleAdapter {
    Map<String, Object> probe(Map<String, Object> context);
    Map<String, Object> snapshot(Map<String, Object> context);
    Map<String, Object> disable(Map<String, Object> context, Map<String, Object> snapshot);
    Map<String, Object> enable(Map<String, Object> context, Map<String, Object> snapshot);
    Map<String, Object> verifyDisabled(Map<String, Object> context);
    Map<String, Object> verifyEnabled(Map<String, Object> context);
    Map<String, Object> rollback(Map<String, Object> context, Map<String, Object> snapshot);
}
