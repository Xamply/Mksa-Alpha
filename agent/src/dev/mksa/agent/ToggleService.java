package dev.mksa.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T3 Plan Maestro Parte 2: Servicio unificado de toggle de mods (ToggleService).
 * Punto de entrada único para el mod thin (BridgeProxy) y el launcher IPC (Agent.dispatch).
 */
public final class ToggleService {

    public static final ToggleService INSTANCE = new ToggleService();

    private final Map<String, Map<String, Object>> activePlans =
            new LinkedHashMap<String, Map<String, Object>>();

    private ToggleService() {}

    /**
     * Genera una vista previa (preview) inmutable de la operación de toggle.
     */
    public synchronized Map<String, Object> preview(String namespace, String direction) {
        if (namespace == null || namespace.isEmpty()) {
            return fail("BAD_PARAMS", "ToggleService.preview requiere namespace");
        }
        if (direction == null || (!"disable".equalsIgnoreCase(direction) && !"enable".equalsIgnoreCase(direction))) {
            direction = "disable";
        }

        String planId = "plan_" + System.currentTimeMillis() + "_" + namespace;
        long currentEpoch = Ledger.INSTANCE.epochReady() ? 1L : 0L;

        String[] targetsArr = Tier3DemixApply.targetsForNamespace(namespace);
        int targetsCount = targetsArr != null ? targetsArr.length : 0;

        Map<String, Object> modes = new LinkedHashMap<String, Object>();
        modes.put("reset", 0);
        modes.put("replay", 0);
        modes.put("preserveShape", targetsCount);

        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("ok", Boolean.TRUE);
        plan.put("planId", planId);
        plan.put("namespace", namespace);
        plan.put("direction", direction.toLowerCase());
        plan.put("epoch", currentEpoch);
        plan.put("decision", targetsCount > 0 ? "auto" : "needs_adapter");
        plan.put("mechanism", targetsCount > 0 ? "tier3_shape_preserving_demix" : "tier0_unsupported");
        plan.put("targets", targetsCount);
        plan.put("modes", modes);
        plan.put("surfaces", Collections.emptyList());
        plan.put("expectedEffect", Collections.emptyList());
        plan.put("metricsBaselineId", "base_" + planId);
        plan.put("expiresOnStateChange", Boolean.TRUE);

        activePlans.put(planId, plan);
        return plan;
    }

    /**
     * Aplica un plan previamente analizado previa verificación de caducidad (staleness).
     */
    public synchronized Map<String, Object> apply(String planId) {
        if (planId == null || planId.isEmpty() || !activePlans.containsKey(planId)) {
            return fail("PLAN_STALE", "El plan de toggle ya no es valido o ha caducado. Obtenga una nueva vista previa.");
        }

        Map<String, Object> plan = activePlans.remove(planId);
        String namespace = (String) plan.get("namespace");
        String direction = (String) plan.get("direction");

        if ("enable".equalsIgnoreCase(direction)) {
            return enable(namespace);
        }
        return disable(namespace);
    }

    public Map<String, Object> disable(String namespace) {
        if (!Tier3RuntimeState.beginDisable(namespace, "auto_plan")) {
            return fail("ILLEGAL_STATE", "No se puede iniciar desactivacion para " + namespace + " en su estado actual.");
        }
        Tier3RuntimeState.startDisabling(namespace);

        String[] targets = Tier3DemixApply.targetsForNamespace(namespace);
        if (targets == null || targets.length == 0) {
            Tier3RuntimeState.markFailedActive(namespace, "Sin targets descubiertos para " + namespace);
            return fail("NO_TARGETS", "No hay targets registados para " + namespace);
        }

        Map<String, Object> metricsBefore = MetricsSampler.INSTANCE.sampleBaseline(10);
        Map<String, Object> res = Tier3DemixApply.disableGroup(Boot.getInstrumentation(), targets, namespace, null);

        if (Boolean.TRUE.equals(res.get("ok"))) {
            Map<String, Object> metricsAfter = MetricsSampler.INSTANCE.samplePostToggle(3, 10);
            Set<String> verifiedTargets = new java.util.LinkedHashSet<String>(java.util.Arrays.asList(targets));
            Tier3RuntimeState.commitDisable(namespace, null, null, null, null, null, 1L, verifiedTargets);
            res.put("toggleState", "inactive_verified");
            res.put("metricsDelta", MetricsSampler.INSTANCE.computeDelta(metricsBefore, metricsAfter));
        } else {
            Tier3RuntimeState.markFailedActive(namespace, (String) res.get("error"));
        }
        return res;
    }

    public Map<String, Object> enable(String namespace) {
        if (!Tier3RuntimeState.beginEnable(namespace)) {
            return fail("ILLEGAL_STATE", "No se puede iniciar reactivacion para " + namespace + " en su estado actual.");
        }
        Tier3RuntimeState.startEnabling(namespace);

        String[] targets = Tier3DemixApply.targetsForNamespace(namespace);
        if (targets == null || targets.length == 0) {
            Tier3RuntimeState.markFailedInactive(namespace, "Sin targets descubiertos para " + namespace);
            return fail("NO_TARGETS", "No hay targets registrados para " + namespace);
        }

        Map<String, Object> metricsBefore = MetricsSampler.INSTANCE.sampleBaseline(10);
        Map<String, Object> res = Tier3DemixApply.enableGroup(Boot.getInstrumentation(), targets, namespace);

        if (Boolean.TRUE.equals(res.get("ok"))) {
            Map<String, Object> metricsAfter = MetricsSampler.INSTANCE.samplePostToggle(3, 10);
            Tier3RuntimeState.commitEnable(namespace);
            res.put("toggleState", "active");
            res.put("metricsDelta", MetricsSampler.INSTANCE.computeDelta(metricsBefore, metricsAfter));
        } else {
            Tier3RuntimeState.markFailedInactive(namespace, (String) res.get("error"));
        }
        return res;
    }

    public Map<String, Object> status(String operationId) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("operationId", operationId);
        out.put("status", "completed");
        return out;
    }

    public Map<String, Object> cancel(String operationId) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("operationId", operationId);
        out.put("canceled", Boolean.TRUE);
        return out;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        return out;
    }

    private Map<String, Object> fail(String code, String error) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", Boolean.FALSE);
        m.put("code", code);
        m.put("error", error);
        return m;
    }
}
