package dev.mksa.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Clasificación de mods por intrusividad (proyecto §3.4).
 *
 * Estructural y universal: opera sobre las raíces del contenido del mod como
 * rutas (Path) — un jar montado por el loader o un directorio explotado, da
 * igual. No enlaza con ningún loader ni mira metadata por formato: solo qué
 * hay dentro del árbol de archivos. Esto la hace válida para Fabric, Quilt,
 * Forge y NeoForge sin una sola rama por loader en la lógica de tier.
 *
 * El tier es la cota más barata que es correcta dado lo que se observa:
 *
 *   T0 — solo datos: ninguna clase. Reload de datapacks basta.
 *   T1 — solo registro: tiene código, pero ninguna referencia a las APIs de
 *        suscripción (event bus, canales, ticks). Proxy + barrido + reload.
 *   T2 — con runtime: el bytecode referencia una API de suscripción → el mod
 *        puede enganchar handlers/canales/ticks. Lo anterior + desuscripción.
 *   T3 — invasivo: declara mixins o coremods. Reteje clases vanilla; exige
 *        des-mixineo (DCEVM) o el fallback de relaunch.
 *
 * Discriminación T1↔T2 por CAPACIDAD ESTÁTICA (inc.4), no por captura en vivo.
 * Se inspecciona el bytecode del mod (el constant pool de sus .class lista,
 * como UTF-8 plano y en forma internal-name con '/', todo tipo/método que
 * referencia) buscando subcadenas marcadoras de las APIs de suscripción. El
 * tier decide el mecanismo de teardown, una propiedad ESTABLE del mod: lo que
 * el mod *puede* hacer es la señal correcta — una captura de un solo arranque
 * depende de config/mundo y sub-clasificaría. Es lado-agente puro: sin ASM,
 * sin ClassFileTransformer, sin atribución por classloader. La captura en vivo
 * (refs vivas para desuscribir) pertenece a la fase de mutación, no a aquí.
 *
 * HEURÍSTICA CONSERVADORA POR DISEÑO. Los marcadores son una sobre-aproximación
 * deliberada: ante cualquier duda preferimos sobre-clasificar a T2 (el mecanismo
 * superset, cuya garantía cubre también a un T1) antes que sub-clasificar un mod
 * con runtime real a T1. En concreto, T1 solo se emite tras escanear TODAS las
 * clases del mod sin un solo marcador; un .class ilegible cuenta como runtime; y
 * una raíz no inspeccionable nunca rebaja el tier. La dirección del error es
 * siempre hacia arriba.
 *
 * Devuelve Integer para que `null` siga significando "sin clasificar" si las
 * raíces no son inspeccionables (mod sin rutas legibles): mentir un tier es
 * peor que admitir desconocimiento.
 */
final class Tier {

    /** Servicio de transformación de modlauncher: coremod moderno de Forge/NeoForge. */
    private static final String TRANSFORM_SERVICE =
            "META-INF/services/cpw.mods.modlauncher.api.ITransformationService";

    /** Atributos del MANIFEST que delatan mixins o coremods sin archivo aparte. */
    private static final String[] INTRUSIVE_MANIFEST_ATTRS = {
            "MixinConfigs",                    // mixins declarados en el manifest
            "FMLCorePlugin",                   // coremod legacy de Forge
            "FMLCorePluginContainsFMLMod",
    };

    /**
     * Subcadenas internal-name (ASCII) que delatan CAPACIDAD de suscripción a
     * runtime: event bus, canales de red o ticks. Conocimiento por loader
     * codificado como datos y escaneado de forma uniforme — la lógica no se
     * ramifica por loader. Se escanea la unión de todos: un jar de un loader no
     * contiene los marcadores de otro, así que no hay falsos positivos cruzados.
     *
     * El conjunto es ANCHO a propósito (la sobre-clasificación a T2 es el lado
     * seguro): p.ej. en Forge/NeoForge un mod de solo-registro que use
     * DeferredRegister.register(IEventBus) arrastra el marcador "IEventBus" y
     * cae a T2 — correcto bajo la política conservadora. El split T1/T2 rinde
     * sobre todo en Fabric, cuyo registro va por Registry sin marcador.
     */
    private static final String[] SUBSCRIPTION_MARKERS = {
            // ── Forge / NeoForge ──────────────────────────────────────────
            // Event bus: instancia, anotación de método y registro por clase.
            // "bus/api/" es común a Forge (net/minecraftforge/eventbus/api/...)
            // y NeoForge (net/neoforged/bus/api/...). EventBusSubscriber cubre
            // Forge (Mod$EventBusSubscriber) y NeoForge (fml/common/...).
            "bus/api/IEventBus",
            "bus/api/SubscribeEvent",
            "EventBusSubscriber",
            // Red clásica de Forge. (La red de NeoForge va por evento de bus
            // RegisterPayloadHandlersEvent → ya cubierta por los marcadores de bus.)
            "SimpleChannel",
            "PacketDistributor",
            // ── Fabric / Quilt ────────────────────────────────────────────
            // El tipo Event<T> sobre el que se llama register()/invoker(): TODO
            // callback de Fabric (incluidos lifecycle y ticks, que SON Events)
            // emite un invoke sobre este owner. Canales: el paquete networking.
            "fabric/api/event/Event",
            "fabric/api/networking/",
            // Quilt QSL (best-effort; fuera del conjunto que el verify exige).
            "qsl/base/api/event/Event",
            "qsl/networking",
    };

    /** Marcadores precomputados a bytes ASCII para búsqueda en el .class crudo. */
    private static final byte[][] MARKER_BYTES = toAscii(SUBSCRIPTION_MARKERS);

    /**
     * Clasifica a partir de las raíces del mod. Agrega sobre todas las raíces
     * legibles. Devuelve null si ninguna raíz pudo leerse.
     *
     * Orden de fuerza del veredicto: T3 (intrusivo) corta de inmediato; si no,
     * T0 cuando no hay clases; si hay clases, T2 si alguna raíz muestra runtime,
     * T1 solo si TODAS se escanearon limpias.
     */
    static Integer classify(List<Path> roots) {
        if (roots == null || roots.isEmpty()) return null;
        boolean anyReadable = false;
        boolean sawClass = false;
        boolean hasRuntime = false;
        for (Path root : roots) {
            if (root == null) continue;
            Scan s = scan(root);
            if (!s.readable) continue;
            anyReadable = true;
            if (s.intrusive) return 3;
            if (s.sawClass) sawClass = true;
            if (s.hasRuntime) hasRuntime = true;
        }
        if (!anyReadable) return null;
        if (!sawClass) return 0;
        return hasRuntime ? 2 : 1;
    }

    private static final class Scan {
        boolean readable;
        boolean intrusive;
        boolean sawClass;
        boolean hasRuntime;
    }

    /**
     * Inspecciona un árbol de raíz. Tolerante: cualquier fallo deja readable=false.
     *
     * Una raíz puede llegar de dos formas: una raíz de filesystem ya montada por
     * el loader (dir explotado, o el "/" de un jar que el loader mapeó — Fabric
     * getRootPaths, Forge SecureJar.getRootPath) que se recorre en sitio y no nos
     * toca cerrar; o un .jar en disco (la fallback getFilePath de Forge), que
     * Files.walk no recorre por dentro — hay que montarlo como zip FS, recorrerlo
     * y cerrarlo nosotros. Se distingue por isRegularFile: el jar lo es, una raíz
     * de FS no.
     */
    private static Scan scan(Path root) {
        if (Files.isRegularFile(root)) return scanJarFile(root);
        Scan out = new Scan();
        try {
            inspect(root, root, out);
        } catch (Throwable t) {
            // raíz no recorrible (FS cerrado, permiso, jar exótico): no readable
        }
        return out;
    }

    /** Monta un .jar en disco como zip FS, lo recorre y lo cierra. */
    private static Scan scanJarFile(Path jar) {
        Scan out = new Scan();
        try (java.nio.file.FileSystem fs = java.nio.file.FileSystems.newFileSystem(jar, (ClassLoader) null)) {
            for (Path fsRoot : fs.getRootDirectories()) {
                inspect(fsRoot, fsRoot, out);
                if (out.intrusive) break;
            }
        } catch (Throwable t) {
            // no es un zip válido o no se pudo montar: no readable
        }
        return out;
    }

    /**
     * Dos pasadas sobre walkRoot, ambas con el FS abierto (el escaneo de
     * contenido no puede diferirse: scanJarFile cierra el zip FS al volver).
     *
     *   Pasada 1 (barata, solo nombres + manifest): fija readable, detecta
     *   intrusive (mixins/coremods) y sawClass. Si intrusive, vuelve sin leer
     *   contenido — un T3 no paga el escaneo de bytecode.
     *
     *   Pasada 2 (solo si hay código y no es intrusivo): lee los bytes de cada
     *   .class y busca marcadores de suscripción; corta al primer acierto. Un T2
     *   para pronto; un T1 puro recorre todas sus clases. Conservadora: un .class
     *   ilegible cuenta como runtime (preferimos T2 a sub-clasificar a T1).
     */
    private static void inspect(Path walkRoot, Path relRoot, Scan out) throws Exception {
        // Pasada 1: estructura (nombres + manifest).
        try (Stream<Path> walk = Files.walk(walkRoot)) {
            for (java.util.Iterator<Path> it = walk.iterator(); it.hasNext(); ) {
                Path p = it.next();
                if (Files.isDirectory(p)) continue;
                out.readable = true;
                String rel = relativize(relRoot, p);
                String lower = rel.toLowerCase(java.util.Locale.ROOT);

                // Mixin: el nombre de config es el contrato común a todos los
                // loaders que usan Mixin, lo referencie quien lo referencie.
                if (lower.endsWith(".mixins.json")) { out.intrusive = true; return; }
                // Coremod moderno (modlauncher) y legacy (json en META-INF).
                if (rel.equals(TRANSFORM_SERVICE)) { out.intrusive = true; return; }
                if (lower.equals("meta-inf/coremods.json")) { out.intrusive = true; return; }
                // El manifest puede declarar mixins/coremods sin archivo aparte.
                if (lower.equals("meta-inf/manifest.mf") && manifestIntrusive(p)) {
                    out.intrusive = true; return;
                }
                if (lower.endsWith(".class")) out.sawClass = true;
            }
        }

        // Pasada 2: contenido (solo si hay código que escanear y no es T3).
        if (out.intrusive || !out.sawClass) return;
        try (Stream<Path> walk = Files.walk(walkRoot)) {
            for (java.util.Iterator<Path> it = walk.iterator(); it.hasNext(); ) {
                Path p = it.next();
                if (Files.isDirectory(p)) continue;
                String lower = relativize(relRoot, p).toLowerCase(java.util.Locale.ROOT);
                if (!lower.endsWith(".class")) continue;
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(p);
                } catch (Throwable t) {
                    // .class ilegible: no podemos probar ausencia de suscripción
                    // → conservador a T2.
                    out.hasRuntime = true;
                    return;
                }
                if (containsMarker(bytes)) { out.hasRuntime = true; return; }
            }
        }
    }

    /** ¿El MANIFEST trae atributos de mixin/coremod? */
    private static boolean manifestIntrusive(Path manifestPath) {
        try (java.io.InputStream in = Files.newInputStream(manifestPath)) {
            Manifest mf = new Manifest(in);
            Attributes main = mf.getMainAttributes();
            for (String attr : INTRUSIVE_MANIFEST_ATTRS) {
                String v = main.getValue(attr);
                if (v != null && !v.trim().isEmpty()) return true;
            }
        } catch (Throwable ignored) {
            // manifest ilegible: no afirmamos intrusividad por su causa
        }
        return false;
    }

    /** ¿Los bytes del .class contienen alguna subcadena marcadora? */
    private static boolean containsMarker(byte[] classBytes) {
        for (byte[] needle : MARKER_BYTES) {
            if (indexOf(classBytes, needle) >= 0) return true;
        }
        return false;
    }

    /** Búsqueda de subcadena byte a byte (los .class son KB; suficiente). */
    private static int indexOf(byte[] hay, byte[] needle) {
        if (needle.length == 0 || hay.length < needle.length) return -1;
        int last = hay.length - needle.length;
        outer:
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[][] toAscii(String[] markers) {
        byte[][] out = new byte[markers.length][];
        for (int i = 0; i < markers.length; i++) {
            out[i] = markers[i].getBytes(StandardCharsets.US_ASCII);
        }
        return out;
    }

    /** Ruta relativa con separadores '/', estable entre el FS por defecto y los montados. */
    private static String relativize(Path root, Path child) {
        try {
            String rel = root.relativize(child).toString();
            return rel.replace('\\', '/');
        } catch (Throwable t) {
            return child.toString().replace('\\', '/');
        }
    }

    private Tier() {}
}
