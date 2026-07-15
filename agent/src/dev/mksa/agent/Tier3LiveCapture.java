package dev.mksa.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Corte I: captura pasiva del bytecode vivo ya tejido por Mixin, en su primera
 * definicion real -- NO via Instrumentation.retransformClasses (corte H probo que
 * eso no entrega un snapshot exacto: ver Tier3RetransformCaptureSmoke.java).
 *
 * Se instala en premain (Agent.installTier3LiveCapture), igual que LedgerTransformer:
 * antes de que la JVM cargue cualquier clase del juego. Fabric/Forge NO usan
 * java.lang.instrument para aplicar Mixin -- su classloader teje los mixins y LUEGO
 * llama al defineClass nativo de la JVM; ese hook nativo es el que dispara la cadena
 * de ClassFileTransformer. Por eso, un transformer instalado aqui ve el bytecode YA
 * TEJIDO por Mixin en su primera definicion, sin la ambiguedad de retransform.
 *
 * Puramente observacional: transform() siempre devuelve null (nunca muta bytecode).
 */
final class Tier3LiveCapture implements ClassFileTransformer {

    /** dotted class name -> bytes capturados en su PRIMERA definicion real. */
    private static final ConcurrentHashMap<String, byte[]> LIVE_BYTES =
            new ConcurrentHashMap<String, byte[]>();

    /** nombres internos JVM (slash) a vigilar; mutable en caliente. */
    private static volatile Set<String> watchSet = Collections.emptySet();

    static void install(Instrumentation inst) {
        inst.addTransformer(new Tier3LiveCapture(), false);
        System.err.println("[mksa] tier3 live-capture transformer instalado");
    }

    /** Anade targets (nombres dotted, como los usa Tier3MixinAudit) al watch-set global. */
    static void addTargets(Collection<String> dottedNames) {
        if (dottedNames == null || dottedNames.isEmpty()) return;
        Set<String> next = new HashSet<String>(watchSet);
        int before = next.size();
        for (String dotted : dottedNames) {
            if (dotted == null || dotted.isEmpty()) continue;
            next.add(dotted.replace('.', '/'));
        }
        watchSet = next;
        System.err.println("[mksa] tier3 live-capture watch-set: " + before + " -> " + next.size());
    }

    static byte[] get(String dottedName) {
        return LIVE_BYTES.get(dottedName);
    }

    static int capturedCount() {
        return LIVE_BYTES.size();
    }

    private Tier3LiveCapture() {}

    /**
     * T3 corte "ceguera a lambdas y clases externas": antes esta captura estaba
     * acotada a watchSet (solo targets @Mixin conocidos, poblado por
     * Boot.computeTier3WatchSet). Eso dejaba sin bytes vivos a CUALQUIER clase
     * externa ordinaria (vanilla, Sodium, Balm, fastutil...) que
     * Tier3MixinAudit.scanExternalLoadedClasses necesita leer para el escaneo
     * de consumidores de forma -- forzando su unico fallback,
     * readLoadedClassBytes (cl.getResource().openStream()), que falla
     * ampliamente bajo el classloader Knot de Fabric para clases ordinarias
     * (confirmado en vivo: ~2881 fallos sobre 236 clases unicas), marcando
     * UNREADABLE_SHAPE_REF (risk=true incondicional) y bloqueando
     * canLowerDecision casi universalmente. Como este transformer ya se instala
     * en premain -- antes de que cargue practicamente cualquier clase del juego
     * -- capturar aqui de forma INCONDICIONAL (ya no solo watchSet) entrega,
     * para CUALQUIER clase no-hidden, exactamente los bytes que la JVM le paso
     * a defineClass, sin depender de que el classloader exponga un recurso de
     * classpath legible. Costo aceptado deliberadamente: bytes de cada clase
     * cargada en la JVM completa (unas ~20-30k en una partida real con mods,
     * pocos KB cada una) quedan retenidos en memoria por el resto de la sesion
     * -- aceptable para un agente de auditoria/diagnostico (mandato del
     * usuario: preferir correctitud real sobre costo de implementacion).
     * watchSet/addTargets se dejan intactos (ya no gatean nada aqui, pero
     * documentan que targets fueron pedidos explicitamente; inofensivos).
     *
     * <p>Nota conocida (no resuelta aqui, ver scanExternalLoadedClasses): una
     * clase "hidden" (JEP 371, ej. proxy de LambdaMetafactory) NUNCA dispara
     * este transformer -- Lookup.defineHiddenClass bypassa deliberadamente la
     * cadena de ClassFileTransformer por especificacion de la JVM (confirmado
     * empiricamente: hook de definicion sin retransform, 0 hits sobre lambdas
     * creadas). Para esas clases, scanExternalLoadedClasses las saltea via
     * isHiddenClass() en vez de depender de una captura que nunca llegara.
     */
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) return null;
        String dotted = className.replace('/', '.');
        if (!LIVE_BYTES.containsKey(dotted)) {
            LIVE_BYTES.putIfAbsent(dotted, classfileBuffer.clone());
        }
        return null;
    }
}
