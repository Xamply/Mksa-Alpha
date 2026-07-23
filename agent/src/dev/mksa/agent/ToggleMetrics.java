package dev.mksa.agent;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S13: Metricas antes y despues de un toggle.
 *
 * <p>Protocolo:
 * <ol>
 *   <li>medir 10 segundos antes (baseline)</li>
 *   <li>descartar frames anormales</li>
 *   <li>ejecutar toggle</li>
 *   <li>esperar estabilizacion (cooldown)</li>
 *   <li>medir 10 segundos despues</li>
 *   <li>calcular delta</li>
 *   <li>mostrar confianza de la medicion</li>
 * </ol>
 *
 * <p>Las metricas NO autorizan ni bloquean el toggle. Solo muestran el efecto.
 */
public final class ToggleMetrics {

    private ToggleMetrics() {}

    /**
     * Muestrea metricas del sistema durante {@code durationSeconds} segundos.
     * Recoge: heap, non-heap (metaspace), GC, threads, clases, CPU time estimado.
     */
    public static Map<String, Object> sampleWindow(int durationSeconds) {
        if (durationSeconds <= 0) durationSeconds = 10;
        Map<String, Object> out = new LinkedHashMap<String, Object>();

        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();

        // Snapshot inicial de GC
        long gcCountBefore = 0;
        long gcTimeBefore = 0;
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gc : gcBeans) {
            if (gc.getCollectionCount() >= 0) gcCountBefore += gc.getCollectionCount();
            if (gc.getCollectionTime() >= 0) gcTimeBefore += gc.getCollectionTime();
        }

        // Snapshot inicial de CPU
        long cpuBefore = 0;
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                cpuBefore = ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
            }
        } catch (Throwable ignored) {}

        long startNanos = System.nanoTime();
        long endNanos = startNanos + (long) durationSeconds * 1_000_000_000L;

        // Muestreo periodico (cada 500ms)
        long heapUsedSum = 0;
        long heapCommittedSum = 0;
        long nonHeapUsedSum = 0;
        int sampleCount = 0;
        long maxHeapUsed = 0;
        long minHeapUsed = Long.MAX_VALUE;

        while (System.nanoTime() < endNanos) {
            MemoryUsage heap = mem.getHeapMemoryUsage();
            MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();
            long hu = heap.getUsed();
            heapUsedSum += hu;
            heapCommittedSum += heap.getCommitted();
            nonHeapUsedSum += nonHeap.getUsed();
            if (hu > maxHeapUsed) maxHeapUsed = hu;
            if (hu < minHeapUsed) minHeapUsed = hu;
            sampleCount++;
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        }

        if (sampleCount == 0) sampleCount = 1;

        // Snapshot final de GC
        long gcCountAfter = 0;
        long gcTimeAfter = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            if (gc.getCollectionCount() >= 0) gcCountAfter += gc.getCollectionCount();
            if (gc.getCollectionTime() >= 0) gcTimeAfter += gc.getCollectionTime();
        }

        // Snapshot final de CPU
        long cpuAfter = 0;
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                cpuAfter = ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
            }
        } catch (Throwable ignored) {}

        out.put("heapUsedAvg", Long.valueOf(heapUsedSum / sampleCount));
        out.put("heapUsedMax", Long.valueOf(maxHeapUsed));
        out.put("heapUsedMin", Long.valueOf(minHeapUsed));
        out.put("heapCommittedAvg", Long.valueOf(heapCommittedSum / sampleCount));
        out.put("nonHeapUsedAvg", Long.valueOf(nonHeapUsedSum / sampleCount));
        out.put("gcCountDelta", Long.valueOf(gcCountAfter - gcCountBefore));
        out.put("gcTimeDeltaMs", Long.valueOf(gcTimeAfter - gcTimeBefore));
        out.put("threadCount", Integer.valueOf(threads.getThreadCount()));
        out.put("loadedClassCount", Integer.valueOf(ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()));
        out.put("cpuTimeDeltaNs", Long.valueOf(cpuAfter - cpuBefore));
        out.put("sampleCount", Integer.valueOf(sampleCount));
        out.put("durationMs", Long.valueOf((System.nanoTime() - startNanos) / 1_000_000L));

        return out;
    }

    /**
     * Calcula el delta entre un snapshot antes y uno despues.
     * Incluye la confianza de la medicion.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> computeDelta(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> delta = new LinkedHashMap<String, Object>();
        if (before == null || after == null) {
            delta.put("confidence", "LOW");
            delta.put("reason", "missing_sample");
            return delta;
        }

        delta.put("heapUsedDelta", longDelta(before, after, "heapUsedAvg"));
        delta.put("heapCommittedDelta", longDelta(before, after, "heapCommittedAvg"));
        delta.put("nonHeapUsedDelta", longDelta(before, after, "nonHeapUsedAvg"));
        delta.put("gcCountDelta", longDelta(before, after, "gcCountDelta"));
        delta.put("gcTimeDeltaMs", longDelta(before, after, "gcTimeDeltaMs"));
        delta.put("threadCountDelta", longDelta(before, after, "threadCount"));
        delta.put("loadedClassDelta", longDelta(before, after, "loadedClassCount"));

        // Confianza: alta si ambas muestras tienen >= 10 samples y GC delta < 3
        int samplesB = intVal(before, "sampleCount");
        int samplesA = intVal(after, "sampleCount");
        long gcDelta = longVal(after, "gcCountDelta");
        String confidence;
        if (samplesB >= 10 && samplesA >= 10 && gcDelta <= 2) {
            confidence = "HIGH";
        } else if (samplesB >= 5 && samplesA >= 5) {
            confidence = "MEDIUM";
        } else {
            confidence = "LOW";
        }
        delta.put("confidence", confidence);

        // Copiar raw para visualizacion
        delta.put("before", before);
        delta.put("after", after);

        return delta;
    }

    /**
     * Mide baseline bloqueante. Devuelve el snapshot baseline.
     * El caller hace el toggle y luego llama {@link #postToggleMeasurement}.
     */
    public static Map<String, Object> measureBaseline(int baselineSeconds) {
        return sampleWindow(baselineSeconds);
    }

    /**
     * Mide post-toggle despues de un cooldown.
     * Devuelve el delta completo entre el baseline y la medicion post.
     */
    public static Map<String, Object> postToggleMeasurement(
            Map<String, Object> baseline, int cooldownSeconds, int postSeconds) {
        if (cooldownSeconds > 0) {
            try { Thread.sleep(cooldownSeconds * 1000L); } catch (InterruptedException ignored) {}
        }
        Map<String, Object> post = sampleWindow(postSeconds);
        return computeDelta(baseline, post);
    }

    private static long longDelta(Map<String, Object> before, Map<String, Object> after, String key) {
        return longVal(after, key) - longVal(before, key);
    }

    private static long longVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private static int intVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }
}
