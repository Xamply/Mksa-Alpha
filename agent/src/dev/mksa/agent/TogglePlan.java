package dev.mksa.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S8: Plan inmutable de toggle. Captura el estado completo del sistema en el
 * momento de planificar para detectar staleness antes de aplicar.
 *
 * <p>Un plan expira cuando:
 * <ul>
 *   <li>El TTL temporal caduca ({@link #expiresAt}).</li>
 *   <li>La epoca cambio ({@link #epoch} vs. el hash de Boot.mods).</li>
 *   <li>Los hashes de bytes live cambiaron (drift detectado).</li>
 *   <li>El orden de mixins cambio (re-clasificacion).</li>
 * </ul>
 * El ToggleService no fabrica un plan nuevo silenciosamente:
 * devuelve PLAN_STALE y obliga a obtener una nueva vista previa.
 */
public final class TogglePlan {

    final String planId;
    final String namespace;
    final String direction;    // "disable" | "enable"
    final long epoch;
    final String modFingerprint;
    final String jvmFingerprint;
    final boolean jvmSupportsEnhancedRedefinition;
    final String[] targets;
    final Map<String, String> modeByTarget;
    final Map<String, String> liveHashByTarget;
    final Map<String, String> offHashByTarget;
    final Map<String, String> baseHashByTarget;
    final Map<String, List<String>> coOwnersByTarget;
    final Map<String, String> shapeFingerprintByTarget;
    final Map<String, Object> auditEvidence;
    final List<String> runtimeSurfaces;
    final List<String> worldSurfaces;
    final long createdAt;
    final long expiresAt;

    TogglePlan(String planId, String namespace, String direction, long epoch,
              String modFingerprint, String jvmFingerprint,
              boolean jvmSupportsEnhancedRedefinition,
              String[] targets, Map<String, String> modeByTarget,
              Map<String, String> liveHashByTarget, Map<String, String> offHashByTarget,
              Map<String, String> baseHashByTarget,
              Map<String, List<String>> coOwnersByTarget,
              Map<String, String> shapeFingerprintByTarget,
              Map<String, Object> auditEvidence,
              List<String> runtimeSurfaces, List<String> worldSurfaces,
              long createdAt, long expiresAt) {
        this.planId = planId;
        this.namespace = namespace;
        this.direction = direction;
        this.epoch = epoch;
        this.modFingerprint = modFingerprint;
        this.jvmFingerprint = jvmFingerprint;
        this.jvmSupportsEnhancedRedefinition = jvmSupportsEnhancedRedefinition;
        this.targets = targets;
        this.modeByTarget = Collections.unmodifiableMap(new LinkedHashMap<String, String>(modeByTarget));
        this.liveHashByTarget = Collections.unmodifiableMap(new LinkedHashMap<String, String>(liveHashByTarget));
        this.offHashByTarget = offHashByTarget != null
                ? Collections.unmodifiableMap(new LinkedHashMap<String, String>(offHashByTarget))
                : Collections.<String, String>emptyMap();
        this.baseHashByTarget = Collections.unmodifiableMap(new LinkedHashMap<String, String>(baseHashByTarget));
        this.coOwnersByTarget = Collections.unmodifiableMap(new LinkedHashMap<String, List<String>>(coOwnersByTarget));
        this.shapeFingerprintByTarget = Collections.unmodifiableMap(new LinkedHashMap<String, String>(shapeFingerprintByTarget));
        this.auditEvidence = auditEvidence != null
                ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(auditEvidence))
                : Collections.<String, Object>emptyMap();
        this.runtimeSurfaces = runtimeSurfaces != null
                ? Collections.unmodifiableList(new ArrayList<String>(runtimeSurfaces))
                : Collections.<String>emptyList();
        this.worldSurfaces = worldSurfaces != null
                ? Collections.unmodifiableList(new ArrayList<String>(worldSurfaces))
                : Collections.<String>emptyList();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    /**
     * Comprueba si el plan es stale dado el estado live actual.
     * @return null si el plan es fresco; razon de staleness si no.
     */
    String checkStaleness(long currentEpoch, Map<String, String> currentLiveHashes) {
        if (isExpired()) return "PLAN_EXPIRED";
        if (currentEpoch != epoch) return "EPOCH_CHANGED";
        if (currentLiveHashes != null) {
            for (Map.Entry<String, String> e : liveHashByTarget.entrySet()) {
                String currentHash = currentLiveHashes.get(e.getKey());
                if (currentHash != null && !currentHash.equals(e.getValue())) {
                    return "LIVE_HASH_DRIFT:" + e.getKey();
                }
            }
        }
        return null;
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("planId", planId);
        m.put("namespace", namespace);
        m.put("direction", direction);
        m.put("epoch", Long.valueOf(epoch));
        m.put("modFingerprint", modFingerprint);
        m.put("jvmFingerprint", jvmFingerprint);
        m.put("jvmSupportsEnhancedRedefinition", Boolean.valueOf(jvmSupportsEnhancedRedefinition));
        m.put("targets", Integer.valueOf(targets.length));
        m.put("targetList", java.util.Arrays.asList(targets));
        m.put("modeByTarget", modeByTarget);
        m.put("coOwnersByTarget", coOwnersByTarget);
        m.put("runtimeSurfaces", runtimeSurfaces);
        m.put("worldSurfaces", worldSurfaces);
        m.put("createdAt", Long.valueOf(createdAt));
        m.put("expiresAt", Long.valueOf(expiresAt));
        return m;
    }
}
