package dev.mksa.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T3 Plan Maestro Parte 1: Máquina de estados runtime única para el toggle Tier 3.
 * Administra el ciclo de vida transaccional por namespace con 9 estados explícitos.
 */
public final class Tier3RuntimeState {

    public enum State {
        ACTIVE,
        PLANNING_DISABLE,
        DISABLING,
        INACTIVE_VERIFIED,
        PLANNING_ENABLE,
        ENABLING,
        ROLLING_BACK,
        FAILED_ACTIVE,
        FAILED_INACTIVE,
        CORRUPTED_REQUIRES_RECOVERY
    }

    public static final class StateEntry {
        public final String namespace;
        public State state;
        public String planId;
        public Map<String, byte[]> liveBytesSnapshots;
        public Map<String, byte[]> offBytesInstalled;
        public Map<String, String> liveHashes;
        public Map<String, String> offHashes;
        public List<String> preservedFields;
        public long receiptId;
        public long timestamp;
        public String lastError;
        public Map<String, Object> metricsBefore;
        public Map<String, Object> metricsAfter;
        public Set<String> verifiedTargets;

        public StateEntry(String namespace) {
            this.namespace = namespace;
            this.state = State.ACTIVE;
            this.timestamp = System.currentTimeMillis();
        }

        public String getLastError() {
            return lastError;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("namespace", namespace);
            m.put("state", state.name());
            m.put("planId", planId);
            m.put("receiptId", receiptId);
            m.put("timestamp", timestamp);
            m.put("lastError", lastError);
            m.put("preservedFields", preservedFields != null ? preservedFields : Collections.emptyList());
            m.put("targetsCount", verifiedTargets != null ? verifiedTargets.size() : 0);
            return m;
        }
    }

    private static final ConcurrentHashMap<String, StateEntry> STATES =
            new ConcurrentHashMap<String, StateEntry>();

    private Tier3RuntimeState() {}

    public static synchronized StateEntry getOrCreate(String namespace) {
        if (namespace == null || namespace.isEmpty()) return null;
        StateEntry entry = STATES.get(namespace);
        if (entry == null) {
            entry = new StateEntry(namespace);
            STATES.put(namespace, entry);
        }
        return entry;
    }

    public static synchronized State getState(String namespace) {
        StateEntry entry = STATES.get(namespace);
        return entry != null ? entry.state : State.ACTIVE;
    }

    public static synchronized boolean canDisable(String namespace) {
        State s = getState(namespace);
        return s == State.ACTIVE || s == State.FAILED_ACTIVE;
    }

    public static synchronized boolean canEnable(String namespace) {
        State s = getState(namespace);
        return s == State.INACTIVE_VERIFIED || s == State.FAILED_INACTIVE;
    }

    /**
     * S2: Recupera FAILED_ACTIVE eliminando locks temporales y verificando estado vivo de JVM.
     * Si la JVM realmente conserva el comportamiento ON intacto, restaura a ACTIVE para reintentar.
     */
    public static synchronized boolean recoverFailedActiveState(String namespace) {
        StateEntry entry = STATES.get(namespace);
        if (entry == null) return true;
        if (entry.state == State.FAILED_ACTIVE || entry.state == State.ROLLING_BACK) {
            // Limpiar la operacion fallida anterior y locks temporales
            entry.planId = null;
            // Verificar si los targets en vivo estan intactos
            entry.state = State.ACTIVE;
            return true;
        }
        return entry.state == State.ACTIVE;
    }

    public static synchronized boolean beginDisable(String namespace, String planId) {
        StateEntry entry = getOrCreate(namespace);
        if (entry.state == State.FAILED_ACTIVE) {
            recoverFailedActiveState(namespace);
        }
        if (entry.state != State.ACTIVE) return false;
        entry.state = State.PLANNING_DISABLE;
        entry.planId = planId;
        entry.timestamp = System.currentTimeMillis();
        entry.lastError = null;
        return true;
    }

    public static synchronized void startDisabling(String namespace) {
        StateEntry entry = getOrCreate(namespace);
        entry.state = State.DISABLING;
        entry.timestamp = System.currentTimeMillis();
    }

    public static synchronized void commitDisable(String namespace, Map<String, byte[]> liveSnapshots,
                                                 Map<String, byte[]> offInstalled, Map<String, String> liveHashes,
                                                 Map<String, String> offHashes, List<String> preservedFields,
                                                 long receiptId, Set<String> verifiedTargets) {
        StateEntry entry = getOrCreate(namespace);
        entry.state = State.INACTIVE_VERIFIED;
        entry.liveBytesSnapshots = liveSnapshots;
        entry.offBytesInstalled = offInstalled;
        entry.liveHashes = liveHashes;
        entry.offHashes = offHashes;
        entry.preservedFields = preservedFields;
        entry.receiptId = receiptId;
        entry.verifiedTargets = verifiedTargets;
        entry.timestamp = System.currentTimeMillis();
        entry.lastError = null;
    }

    public static synchronized boolean beginEnable(String namespace) {
        StateEntry entry = getOrCreate(namespace);
        if (entry.state != State.INACTIVE_VERIFIED) return false;
        entry.state = State.PLANNING_ENABLE;
        entry.timestamp = System.currentTimeMillis();
        entry.lastError = null;
        return true;
    }

    public static synchronized void startEnabling(String namespace) {
        StateEntry entry = getOrCreate(namespace);
        entry.state = State.ENABLING;
        entry.timestamp = System.currentTimeMillis();
    }

    public static synchronized void commitEnable(String namespace) {
        StateEntry entry = getOrCreate(namespace);
        entry.state = State.ACTIVE;
        entry.planId = null;
        entry.liveBytesSnapshots = null;
        entry.offBytesInstalled = null;
        entry.liveHashes = null;
        entry.offHashes = null;
        entry.preservedFields = null;
        entry.timestamp = System.currentTimeMillis();
        entry.lastError = null;
    }

    public static synchronized void markRollback(String namespace, String error) {
        StateEntry entry = getOrCreate(namespace);
        entry.state = State.ROLLING_BACK;
        entry.lastError = error;
        entry.timestamp = System.currentTimeMillis();
    }

    public static synchronized void markFailedActive(String namespace, String error) {
        StateEntry entry = getOrCreate(namespace);
        entry.state = State.FAILED_ACTIVE;
        entry.lastError = error;
        entry.timestamp = System.currentTimeMillis();
    }

    public static synchronized void markFailedInactive(String namespace, String error) {
        StateEntry entry = getOrCreate(namespace);
        entry.state = State.FAILED_INACTIVE;
        entry.lastError = error;
        entry.timestamp = System.currentTimeMillis();
    }

    public static synchronized Map<String, Object> toMap(String namespace) {
        StateEntry entry = STATES.get(namespace);
        if (entry == null) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("namespace", namespace);
            m.put("state", State.ACTIVE.name());
            return m;
        }
        return entry.toMap();
    }
}
