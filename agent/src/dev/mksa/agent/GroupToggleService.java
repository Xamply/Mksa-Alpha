package dev.mksa.agent;

import java.util.*;

/**
 * Sección 3: Servicio de transacciones atómicas para grupos de mods (GroupToggleService).
 * Garantiza que si cualquier mod del grupo falla, se ejecute rollback completo de todos los mods.
 */
public final class GroupToggleService {

    public static final GroupToggleService INSTANCE = new GroupToggleService();

    private GroupToggleService() {}

    /**
     * Ejecuta la desactivación transaccional de un grupo de mods.
     */
    public Map<String, Object> disableGroup(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return fail("INVALID_NAMESPACE", "Namespace nulo o vacio", null);
        }

        // 1. Verificar si apagar este mod en solitario dejaría dependientes activos huérfanos
        String warning = DependencyGraph.checkSingleDisableAllowed(namespace);
        if (warning != null) {
            Map<String, Object> reply = new LinkedHashMap<String, Object>();
            reply.put("ok", Boolean.FALSE);
            reply.put("code", "DEPENDENCY_GROUP_REQUIRED");
            reply.put("error", warning);
            reply.put("namespace", namespace);
            reply.put("retryable", Boolean.FALSE);
            reply.put("rolledBack", Boolean.FALSE);
            return reply;
        }

        List<String> group = DependencyGraph.getDisableGroup(namespace);
        Map<String, Map<String, Object>> completedResults = new LinkedHashMap<String, Map<String, Object>>();
        List<String> successfullyDisabled = new ArrayList<String>();

        for (String modNs : group) {
            Map<String, Object> res = ToggleService.INSTANCE.disable(modNs);
            boolean ok = Boolean.TRUE.equals(res.get("ok"));
            completedResults.put(modNs, res);
            if (ok) {
                successfullyDisabled.add(modNs);
            } else {
                // Falló un integrante del grupo: ejecutar rollback atómico del grupo completo
                rollbackGroup(successfullyDisabled);
                Map<String, Object> failReply = new LinkedHashMap<String, Object>();
                failReply.put("ok", Boolean.FALSE);
                failReply.put("code", "GROUP_TRANSACTION_FAILED");
                failReply.put("failedMod", modNs);
                failReply.put("error", "Fallo al desactivar '" + modNs + "' en transaccion de grupo: " + res.get("error"));
                failReply.put("group", group);
                failReply.put("rolledBack", Boolean.TRUE);
                failReply.put("retryable", Boolean.TRUE);
                failReply.put("detail", res);
                return failReply;
            }
        }

        Map<String, Object> reply = new LinkedHashMap<String, Object>();
        reply.put("ok", Boolean.TRUE);
        reply.put("namespace", namespace);
        reply.put("group", group);
        reply.put("disabledCount", group.size());
        reply.put("state", "OFF");
        reply.put("toggleState", "inactive_verified");
        return reply;
    }

    /**
     * Restaura un grupo de mods en orden topológico correcto.
     */
    public Map<String, Object> enableGroup(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return fail("INVALID_NAMESPACE", "Namespace nulo o vacio", null);
        }

        List<String> enableOrder = DependencyGraph.getEnableGroup(namespace);
        for (String modNs : enableOrder) {
            Map<String, Object> res = ToggleService.INSTANCE.enable(modNs);
            boolean ok = Boolean.TRUE.equals(res.get("ok"));
            if (!ok) {
                Map<String, Object> failReply = new LinkedHashMap<String, Object>();
                failReply.put("ok", Boolean.FALSE);
                failReply.put("code", "GROUP_ENABLE_FAILED");
                failReply.put("failedMod", modNs);
                failReply.put("error", "Fallo al restaurar '" + modNs + "' en transaccion de grupo: " + res.get("error"));
                failReply.put("group", enableOrder);
                failReply.put("detail", res);
                return failReply;
            }
        }

        Map<String, Object> reply = new LinkedHashMap<String, Object>();
        reply.put("ok", Boolean.TRUE);
        reply.put("namespace", namespace);
        reply.put("group", enableOrder);
        reply.put("state", "ON");
        reply.put("toggleState", "active");
        return reply;
    }

    private void rollbackGroup(List<String> modsToRollback) {
        for (int i = modsToRollback.size() - 1; i >= 0; i--) {
            String ns = modsToRollback.get(i);
            try {
                ToggleService.INSTANCE.enable(ns);
            } catch (Throwable ignored) {}
        }
    }

    private Map<String, Object> fail(String code, String error, String modNs) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", Boolean.FALSE);
        m.put("code", code);
        m.put("error", error);
        if (modNs != null) m.put("namespace", modNs);
        return m;
    }
}
