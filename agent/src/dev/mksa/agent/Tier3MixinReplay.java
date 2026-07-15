package dev.mksa.agent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * T3 corte AK: replay selectivo de mixins reusando el motor real de Mixin
 * (MixinConfig.getMixinsFor -> TargetClassContext -> applyMixins) en vez de
 * reimplementar fusion de bytecode con ASM a mano. Confirmado por javap real
 * contra sponge-mixin-0.17.3+mixin.0.8.7.jar (la libreria real de este
 * proyecto): getMixinsFor/getName/getClassName son publicos; MixinInfo
 * implementa Comparable<MixinInfo> (usable en un TreeSet sin reimplementar
 * orden); TargetClassContext(MixinEnvironment, Extensions, sessionId,
 * className, ClassNode, SortedSet<MixinInfo>) + applyMixins() dispara la
 * fusion REAL (MixinApplicatorStandard interno, el mismo motor del juego).
 *
 * Riesgo confirmado y mitigado: ClassInfo.cache (Map<String,ClassInfo>
 * estatico global) devuelve una entrada existente tal cual si ya fue
 * poblada por la carga organica original, ignorando el ClassNode base nuevo
 * -- se evict explicitamente antes de construir el TargetClassContext para
 * forzar reconstruccion fresca desde nuestros bytes base reales.
 *
 * Puramente reflexivo: dev.mksa.agent (fuera de .ledger) compila sin ASM ni
 * Mixin en el classpath (agent/build.sh paso 1), por lo que ninguna clase de
 * org.objectweb.asm.* ni org.spongepowered.asm.* puede importarse aqui --
 * mismo patron ya establecido en Tier3OfflineTransformCheck (corte AB).
 */
final class Tier3MixinReplay {

    static final class Outcome {
        final boolean ok;
        final byte[] bytes;
        final List<String> allMixinClassNames;
        final List<String> excludedMixinClassNames;
        final List<String> appliedMixinClassNames;
        final boolean framesRecomputed;
        final String failedStep;
        final String error;
        final String message;

        private Outcome(boolean ok, byte[] bytes, List<String> allMixinClassNames,
                        List<String> excludedMixinClassNames, List<String> appliedMixinClassNames,
                        boolean framesRecomputed, String failedStep, String error, String message) {
            this.ok = ok;
            this.bytes = bytes;
            this.allMixinClassNames = allMixinClassNames;
            this.excludedMixinClassNames = excludedMixinClassNames;
            this.appliedMixinClassNames = appliedMixinClassNames;
            this.framesRecomputed = framesRecomputed;
            this.failedStep = failedStep;
            this.error = error;
            this.message = message;
        }

        static Outcome ok(byte[] bytes, List<String> all, List<String> excluded,
                          List<String> applied, boolean framesRecomputed) {
            return new Outcome(true, bytes, all, excluded, applied, framesRecomputed, null, null, null);
        }

        static Outcome failed(String failedStep, Throwable t, List<String> all, List<String> excluded) {
            return new Outcome(false, null, all, excluded, null, false, failedStep,
                    t.getClass().getSimpleName(), String.valueOf(t.getMessage()));
        }
    }

    /**
     * @param anchorClass una clase Mixin real ya cargada (para resolver el
     *                    mismo classloader/copia de ASM y Mixin que usa el
     *                    juego -- no la copia aislada de mksa-ledger.jar).
     * @param targetClassName FQCN con puntos del target (ej. net.minecraft.class_2586).
     * @param baseBytes bytecode base sobre el que fusionar (vainilla, sin ningun mixin).
     * @param excludeMixinClassNames FQCN de mixins a excluir de la fusion (los del mod victima).
     */
    static Outcome replay(Class<?> anchorClass, String targetClassName, byte[] baseBytes,
                          Set<String> excludeMixinClassNames) {
        List<String> allNames = new ArrayList<String>();
        List<String> excludedNames = new ArrayList<String>();
        String step = "resolve_transformer";
        try {
            ClassLoader anchor = anchorClass.getClassLoader();

            Class<?> menvClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", true, anchor);
            Method getCurrentEnvironment = menvClass.getMethod("getCurrentEnvironment");
            getCurrentEnvironment.setAccessible(true);
            Object currentEnv = getCurrentEnvironment.invoke(null);
            if (currentEnv == null) throw new IllegalStateException("getCurrentEnvironment_returned_null");
            Method getActiveTransformer = menvClass.getMethod("getActiveTransformer");
            getActiveTransformer.setAccessible(true);
            Object transformer = getActiveTransformer.invoke(currentEnv);
            if (transformer == null) throw new IllegalStateException("getActiveTransformer_returned_null");
            Class<?> transformerClass = transformer.getClass();

            step = "resolve_processor_and_extensions";
            Field processorField = findField(transformerClass, "processor");
            processorField.setAccessible(true);
            Object processor = processorField.get(transformer);

            Field extensionsField = findField(transformerClass, "extensions");
            extensionsField.setAccessible(true);
            Object extensions = extensionsField.get(transformer);

            Field sessionIdField = findField(processor.getClass(), "sessionId");
            sessionIdField.setAccessible(true);
            String sessionId = (String) sessionIdField.get(processor);

            step = "resolve_configs";
            Field configsField = findField(processor.getClass(), "configs");
            configsField.setAccessible(true);
            Object configsObj = configsField.get(processor);
            List<?> configs = configsObj instanceof List ? (List<?>) configsObj : Collections.emptyList();

            step = "collect_mixins_for_target";
            List<Object> mixinInfos = new ArrayList<Object>();
            Method getClassNameMethod = null;
            for (Object cfg : configs) {
                Method getMixinsFor = cfg.getClass().getMethod("getMixinsFor", String.class);
                getMixinsFor.setAccessible(true);
                Object result = getMixinsFor.invoke(cfg, targetClassName);
                if (result instanceof List) {
                    for (Object mi : (List<?>) result) {
                        if (mi == null) continue;
                        if (getClassNameMethod == null) {
                            getClassNameMethod = mi.getClass().getMethod("getClassName");
                            getClassNameMethod.setAccessible(true);
                        }
                        mixinInfos.add(mi);
                    }
                }
            }

            step = "filter_mixins";
            SortedSet<Object> keep = new TreeSet<Object>();
            for (Object mi : mixinInfos) {
                String cn = (String) getClassNameMethod.invoke(mi);
                allNames.add(cn);
                if (excludeMixinClassNames.contains(cn)) {
                    excludedNames.add(cn);
                } else {
                    keep.add(mi);
                }
            }

            step = "evict_classinfo_cache";
            Class<?> classInfoClass = Class.forName("org.spongepowered.asm.mixin.transformer.ClassInfo", true, anchor);
            Field cacheField = classInfoClass.getDeclaredField("cache");
            cacheField.setAccessible(true);
            Object cacheObj = cacheField.get(null);
            if (cacheObj instanceof Map) {
                ((Map<?, ?>) cacheObj).remove(targetClassName);
                ((Map<?, ?>) cacheObj).remove(targetClassName.replace('.', '/'));
            }

            step = "parse_base_bytes";
            Class<?> classReaderClass = Class.forName("org.objectweb.asm.ClassReader", true, anchor);
            Constructor<?> crCtor = classReaderClass.getConstructor(byte[].class);
            crCtor.setAccessible(true);
            Object classReader = crCtor.newInstance((Object) baseBytes);
            Class<?> classNodeClass = Class.forName("org.objectweb.asm.tree.ClassNode", true, anchor);
            Constructor<?> cnCtor = classNodeClass.getConstructor();
            cnCtor.setAccessible(true);
            Object classNode = cnCtor.newInstance();
            Class<?> classVisitorClass = Class.forName("org.objectweb.asm.ClassVisitor", true, anchor);
            Method acceptMethod = classReaderClass.getMethod("accept", classVisitorClass, int.class);
            acceptMethod.setAccessible(true);
            final int expandFrames = 8;
            acceptMethod.invoke(classReader, classNode, Integer.valueOf(expandFrames));

            step = "build_target_class_context";
            Class<?> extensionsClass = Class.forName(
                    "org.spongepowered.asm.mixin.transformer.ext.Extensions", true, anchor);
            Class<?> tccClass = Class.forName(
                    "org.spongepowered.asm.mixin.transformer.TargetClassContext", true, anchor);
            Constructor<?> tccCtor = tccClass.getDeclaredConstructor(
                    menvClass, extensionsClass, String.class, String.class,
                    classNodeClass, SortedSet.class);
            tccCtor.setAccessible(true);
            Object targetContext = tccCtor.newInstance(
                    currentEnv, extensions, sessionId, targetClassName, classNode, keep);

            step = "apply_mixins";
            Method applyMixinsMethod = tccClass.getDeclaredMethod("applyMixins");
            applyMixinsMethod.setAccessible(true);
            applyMixinsMethod.invoke(targetContext);

            step = "get_merged_class_node";
            Method getClassNodeMethod = tccClass.getMethod("getClassNode");
            getClassNodeMethod.setAccessible(true);
            Object mergedNode = getClassNodeMethod.invoke(targetContext);

            step = "recompute_frames";
            boolean framesRecomputed;
            try {
                Method computeFramesForClass = transformerClass.getMethod(
                        "computeFramesForClass", menvClass, String.class, classNodeClass);
                computeFramesForClass.setAccessible(true);
                Object rc = computeFramesForClass.invoke(transformer, currentEnv, targetClassName, mergedNode);
                framesRecomputed = Boolean.TRUE.equals(rc);
            } catch (Throwable ignored) {
                framesRecomputed = false;
            }

            step = "serialize";
            // Mismo writer que usa Mixin de verdad, PERO confirmado por javap real
            // de TreeTransformer.writeClass(ClassNode) (bytecode, no fuente) que la
            // rama MixinClassWriter(ClassReader, int) (COPY_POOL) solo se toma si
            // this.classReader != null && this.classNode == param (identidad de
            // referencia) -- y el campo privado TreeTransformer.classNode NUNCA se
            // asigna en ninguna parte de MixinTransformer/TreeTransformer (solo se
            // pone a null); el path real de produccion (MixinTransformer.
            // transformClass -> readClass(name, bytes), overload de 2 args que cachea
            // el reader pero no el ClassNode) por lo tanto SIEMPRE cae al else:
            // new MixinClassWriter(COMPUTE_MAXS|COMPUTE_FRAMES=3) SIN reader (sin
            // COPY_POOL, constant pool fresco). Usar MixinClassWriter(reader, 3) aqui
            // (como se hacia antes) produce bytecode logicamente identico -- mismo
            // tamano, mismos miembros, mismas signatures -- pero con el constant pool
            // reordenado por COPY_POOL, lo que rompe la comparacion sha256 de
            // fidelidad. Replicando el else real: MixinClassWriter(3) sin reader.
            Class<?> classWriterClass = Class.forName("org.objectweb.asm.ClassWriter", true, anchor);
            Class<?> mixinClassWriterClass = Class.forName(
                    "org.spongepowered.asm.transformers.MixinClassWriter", true, anchor);
            Constructor<?> mcwCtor = mixinClassWriterClass.getConstructor(int.class);
            mcwCtor.setAccessible(true);
            final int computeMaxsAndFrames = 3;
            Object classWriter = mcwCtor.newInstance(computeMaxsAndFrames);
            Method acceptOnNode = classNodeClass.getMethod("accept", classVisitorClass);
            acceptOnNode.setAccessible(true);
            acceptOnNode.invoke(mergedNode, classWriter);
            Method toByteArray = classWriterClass.getMethod("toByteArray");
            toByteArray.setAccessible(true);
            byte[] resultBytes = (byte[]) toByteArray.invoke(classWriter);

            List<String> applied = new ArrayList<String>(allNames);
            applied.removeAll(excludedNames);
            return Outcome.ok(resultBytes, allNames, excludedNames, applied, framesRecomputed);
        } catch (Throwable t) {
            return Outcome.failed(step, t, allNames, excludedNames);
        }
    }

    private static Field findField(Class<?> start, String name) throws NoSuchFieldException {
        Class<?> c = start;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private Tier3MixinReplay() {}
}
