package dev.mksa.agent;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline de arranque de Fase 2 (protocolo §3.2):
 * premain → loader-detected → mappings-resolved → registries-frozen
 * → wcg-ready → game-ready.
 *
 * Todo por sondeo de clases cargadas + reflexión: el agente no enlaza con
 * ningún loader en compilación (contrato de universalidad, proyecto §1).
 */
final class Boot implements Runnable {

    static volatile String loader;        // fabric | quilt | forge | neoforge
    static volatile String loaderVersion;
    static volatile String mcVersion;
    static volatile String names;         // intermediary | mojmap
    static volatile List<Map<String, Object>> mods = Collections.emptyList();
    /** namespace (= modId) → raíces del mod, para casar el actor por code source. */
    static volatile Map<String, List<java.nio.file.Path>> modRoots = Collections.emptyMap();
    /** Instancia viva del cliente (Minecraft/class_310), para el motor in-process (§3.5). */
    static volatile Object clientInstance;

    /** clase marcadora → loader. El impl del loader carga mucho antes que el juego. */
    private static final String[][] MARKERS = {
            { "net.fabricmc.loader.impl.FabricLoaderImpl", "fabric" },
            { "org.quiltmc.loader.impl.QuiltLoaderImpl", "quilt" },
            { "net.neoforged.fml.loading.FMLLoader", "neoforge" },
            { "net.minecraftforge.fml.loading.FMLLoader", "forge" },
    };

    private final Instrumentation inst;

    private Boot(Instrumentation inst) { this.inst = inst; }

    static void start(Instrumentation inst) {
        Thread t = new Thread(new Boot(inst), "mksa-boot");
        t.setDaemon(true);
        t.start();
    }

    public void run() {
        try {
            Class<?> marker = waitForClass(MARKERS, 300_000);
            if (marker == null) {
                Agent.bootFailed("loader-detected", "ningún modloader conocido apareció en 300s");
                return;
            }
            // Cache de iconos en disco (mksa/mod-icons/) — antes de enumerar, los
            // helpers consultan el cache para evitar leer del jar y, si está vacío,
            // encolan un fetch contra Modrinth para próximos arranques.
            ModIconStore.init(Agent.mksaDir);
            if ("fabric".equals(loader) || "quilt".equals(loader)) {
                enumerateFabricMods(marker);
            } else {
                enumerateForgeMods(marker);
            }
            // Los fetches de iconos arrancan dentro de la enumeración y siguen
            // corriendo en background — el boot NO bloquea. Ledger.modThinListMods
            // lee el cache en cada llamada, así los iconos aparecen en la UI a
            // medida que la descarga termina (con auto-refresh del panel).
            ModIconStore.log("BOOT-DONE " + ModIconStore.stats());
            // Época del ledger (inc.5 Paso 2): hash estable del modset, ya conocido
            // tras la enumeración. Los writes de chunk ocurren mucho después (carga
            // del mundo), así que la época será real al estamparse en mksa:prov.
            Ledger.INSTANCE.computeEpoch(loader, loaderVersion, mcVersion, mods);
            // Raíces de los mods → Ledger, para resolver el actor (Paso 3) por code
            // source de la clase llamante. Recolectadas durante la enumeración.
            Ledger.INSTANCE.setModRoots(modRoots);
            Map<String, Object> detail = Agent.map("loader", loader);
            if (loaderVersion != null) detail.put("loaderVersion", loaderVersion);
            if (mcVersion != null) detail.put("mc", mcVersion);
            Agent.stage("loader-detected", detail);

            // §3.11: el loader determina la fuente de nombres en runtime.
            names = ("fabric".equals(loader) || "quilt".equals(loader)) ? "intermediary" : "mojmap";
            Agent.stage("mappings-resolved", Agent.map("names", names));

            // Gate de arranque: que exista la INSTANCIA del cliente, no solo su
            // clase. La clase carga antes de que los mods registren (los mixins
            // la referencian temprano) y el bootstrap vanilla deja los registros
            // quietos varios segundos antes de correr los entrypoints de los
            // mods (dentro del constructor del cliente): leer ahí congela un
            // snapshot solo-vanilla — verificado con waystones. La instancia es
            // además un gate universal: no depende de la pantalla de título, que
            // mods como Essential reemplazan, dejando sin cargar la clase vanilla.
            if (!waitForGameInstance(600_000)) {
                Agent.bootFailed("game-ready", "la instancia del cliente no apareció en 600s");
                return;
            }

            // v0: el contenido está congelado cuando dos lecturas consecutivas
            // separadas 2s son idénticas. Detectar el freeze en su instante
            // exacto llegará con los hooks de escritura del ledger.
            int prev = -1;
            int stable = 0;
            long deadline = System.currentTimeMillis() + 600_000;
            boolean built = false;
            while (System.currentTimeMillis() < deadline && stable < 2) {
                try {
                    Wcg.build(inst);
                    built = true;
                    if (Wcg.totalEntries == prev) stable++;
                    else { stable = 0; prev = Wcg.totalEntries; }
                } catch (Throwable t) {
                    // registro en plena mutación (CME o similar): aún no estable
                    stable = 0;
                }
                Thread.sleep(2000);
            }
            if (built && stable >= 2) {
                Wcg.ready = true;
                Agent.stage("registries-frozen", new LinkedHashMap<String, Object>());
                Agent.stage("wcg-ready", Agent.map(
                        "registries", Wcg.definitions.size(),
                        "entries", Wcg.totalEntries));
            } else {
                // sin WCG el juego sigue siendo jugable; se informa y se continúa
                Agent.bootFailed("wcg-ready", built
                        ? "los registros no se estabilizaron en 600s"
                        : "no se pudieron leer los registros");
            }
            // Índice de bloques modded (Paso 3): identidad del contenido de un
            // setBlock en O(1). Tras la estabilización de registros; tolerante a
            // fallo (sin índice, onSetBlock solo cuenta, sin agencia).
            Ledger.INSTANCE.buildBlockIndex(inst);
            Agent.stage("game-ready", new LinkedHashMap<String, Object>());
        } catch (Throwable t) {
            Agent.bootFailed(Agent.bootStage, String.valueOf(t));
        }
    }

    /** clase del cliente, estable por diseño (como class_442 arriba). */
    private static final String[] CLIENT_CLASSES = {
            "net.minecraft.class_310",        // MinecraftClient (intermediary)
            "net.minecraft.client.Minecraft", // mojmap
    };

    /**
     * Espera a que exista la instancia del cliente. Descubrimiento estructural:
     * el singleton es el campo estático cuyo tipo es la propia clase. Devuelve
     * true si la encontró; false si venció el plazo.
     */
    private boolean waitForGameInstance(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                String n = c.getName();
                for (String want : CLIENT_CLASSES) {
                    if (!n.equals(want)) continue;
                    try {
                        Agent.openModule(inst, c);
                        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            if (f.getType() != c) continue;
                            f.setAccessible(true);
                            Object instance = f.get(null);
                            if (instance != null) {
                                clientInstance = instance;   // retenida para el motor in-process
                                return true;
                            }
                        }
                    } catch (Throwable ignored) {
                        // clase a medio inicializar o campo inaccesible: reintento
                    }
                }
            }
            Thread.sleep(500);
        }
        return false;
    }

    /** Sondea las clases cargadas hasta ver una de las marcadoras; fija `loader`. */
    private Class<?> waitForClass(String[][] markers, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Class<?>[] all = inst.getAllLoadedClasses();
            for (Class<?> c : all) {
                String n = c.getName();
                for (String[] m : markers) {
                    if (n.equals(m[0])) {
                        loader = m[1];
                        return c;
                    }
                }
            }
            Thread.sleep(500);
        }
        return null;
    }

    /**
     * Enumera mods vía la API pública del loader, por reflexión y desde el
     * classloader del propio loader. Espera a que la lista sea no-vacía:
     * en premain el loader aún no ha poblado sus contenedores.
     */
    private void enumerateFabricMods(Class<?> marker) {
        try {
            ClassLoader cl = marker.getClassLoader();
            Class<?> api = cl.loadClass("net.fabricmc.loader.api.FabricLoader");
            Object instance = api.getMethod("getInstance").invoke(null);
            Collection<?> all = Collections.emptyList();
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                all = (Collection<?>) api.getMethod("getAllMods").invoke(instance);
                if (!all.isEmpty()) break;
                Thread.sleep(500);
            }
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            Map<String, List<java.nio.file.Path>> roots =
                    new LinkedHashMap<String, List<java.nio.file.Path>>();
            for (Object container : all) {
                Object meta = call(container, "getMetadata");
                String id = (String) call(meta, "getId");
                Object ver = call(meta, "getVersion");
                String version = ver == null ? null : (String) call(ver, "getFriendlyString");
                String name = (String) call(meta, "getName");
                List<String> deps = new ArrayList<String>();
                try {
                    Collection<?> dd = (Collection<?>) call(meta, "getDependencies");
                    if (dd != null) {
                        for (Object dep : dd) {
                            Object kind = call(dep, "getKind");
                            if (kind == null || "DEPENDS".equals(String.valueOf(kind))) {
                                deps.add((String) call(dep, "getModId"));
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // getKind no existe en loaders viejos; deps quedan vacías antes que mentir
                }
                if ("minecraft".equals(id)) mcVersion = version;
                if ("fabricloader".equals(id) || "quilt_loader".equals(id)) loaderVersion = version;
                List<java.nio.file.Path> modRootPaths = fabricRoots(container);
                Map<String, Object> m = Agent.map(
                        "id", id, "version", version, "name", name);
                m.put("namespaces", Collections.singletonList(id));
                m.put("tier", Tier.classify(modRootPaths));
                m.put("deps", deps);
                m.put("files", fabricFiles(container));
                m.put("enabled", Boolean.TRUE);
                m.put("description", extractDescription(meta));
                if (resolveIcon(id, name)) iconsLoaded++;
                else iconsMissed++;
                out.add(m);
                if (id != null && !modRootPaths.isEmpty()) roots.put(id, modRootPaths);
            }
            ModIconStore.log("ENUM Fabric: " + out.size() + " mods, " + iconsLoaded + " en cache, " + iconsMissed + " encolados");
            mods = out;
            modRoots = roots;
        } catch (Throwable t) {
            System.err.println("[mksa] enumeración de mods falló: " + t);
            t.printStackTrace();
        }
    }

    private int iconsLoaded;
    private int iconsMissed;

    /**
     * Enumera mods en Forge/NeoForge vía ModList. Los métodos se buscan en las
     * interfaces públicas del SPI (IModInfo), nunca en las clases impl: bajo
     * módulos, las impl viven en paquetes no exportados y la reflexión directa
     * sobre ellas falla. ModList se puebla durante el descubrimiento de mods,
     * así que se sondea hasta que la lista sea no-vacía.
     */
    private void enumerateForgeMods(Class<?> marker) {
        try {
            ClassLoader cl = marker.getClassLoader();
            boolean neo = marker.getName().startsWith("net.neoforged");
            Class<?> modListCls = null;
            List<?> infos = Collections.emptyList();
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (modListCls == null) {
                        modListCls = cl.loadClass(neo
                                ? "net.neoforged.fml.ModList"
                                : "net.minecraftforge.fml.ModList");
                    }
                    Object modList = modListCls.getMethod("get").invoke(null);
                    if (modList != null) {
                        infos = (List<?>) modListCls.getMethod("getMods").invoke(modList);
                        if (!infos.isEmpty()) break;
                    }
                } catch (Throwable ignored) {
                    // ModList aún no existe o está a medio poblar
                }
                Thread.sleep(500);
            }
            Class<?> iModInfo = cl.loadClass(neo
                    ? "net.neoforged.neoforgespi.language.IModInfo"
                    : "net.minecraftforge.forgespi.language.IModInfo");
            java.lang.reflect.Method getModId = iModInfo.getMethod("getModId");
            java.lang.reflect.Method getVersion = iModInfo.getMethod("getVersion");
            java.lang.reflect.Method getDisplayName = iModInfo.getMethod("getDisplayName");
            java.lang.reflect.Method getDependencies = iModInfo.getMethod("getDependencies");
            Class<?> modVersion = cl.loadClass(iModInfo.getName() + "$ModVersion");
            java.lang.reflect.Method depModId = modVersion.getMethod("getModId");
            java.lang.reflect.Method depKind = null;
            try {
                depKind = modVersion.getMethod("getType");      // neoforge: DependencyType
            } catch (NoSuchMethodException e) {
                try {
                    depKind = modVersion.getMethod("isMandatory"); // forge: boolean
                } catch (NoSuchMethodException ignored) {}
            }
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            Map<String, List<java.nio.file.Path>> roots =
                    new LinkedHashMap<String, List<java.nio.file.Path>>();
            for (Object info : infos) {
                String id = (String) getModId.invoke(info);
                Object ver = getVersion.invoke(info);
                String version = ver == null ? null : ver.toString();
                String name = (String) getDisplayName.invoke(info);
                List<String> deps = new ArrayList<String>();
                try {
                    Collection<?> dd = (Collection<?>) getDependencies.invoke(info);
                    if (dd != null) {
                        for (Object dep : dd) {
                            boolean required = true;
                            if (depKind != null) {
                                Object k = depKind.invoke(dep);
                                required = k instanceof Boolean
                                        ? ((Boolean) k).booleanValue()
                                        : "REQUIRED".equals(String.valueOf(k));
                            }
                            if (required) deps.add((String) depModId.invoke(dep));
                        }
                    }
                } catch (Throwable ignored) {
                    // deps quedan vacías antes que mentir
                }
                if ("minecraft".equals(id)) mcVersion = version;
                if ("neoforge".equals(id) || "forge".equals(id)) loaderVersion = version;
                List<java.nio.file.Path> modRootPaths = forgeRoots(info);
                Map<String, Object> m = Agent.map(
                        "id", id, "version", version, "name", name);
                m.put("namespaces", Collections.singletonList(id));
                m.put("tier", Tier.classify(modRootPaths));
                m.put("deps", deps);
                m.put("files", forgeFiles(info));
                m.put("enabled", Boolean.TRUE);
                m.put("description", extractDescription(info));
                if (resolveIcon(id, name)) iconsLoaded++;
                else iconsMissed++;
                out.add(m);
                if (id != null && !modRootPaths.isEmpty()) roots.put(id, modRootPaths);
            }
            ModIconStore.log("ENUM Forge: " + out.size() + " mods, " + iconsLoaded + " en cache, " + iconsMissed + " encolados");
            mods = out;
            modRoots = roots;
        } catch (Throwable t) {
            System.err.println("[mksa] enumeración de mods falló: " + t);
            t.printStackTrace();
        }
    }

    /**
     * Raíces de contenido de un ModContainer de Fabric/Quilt, para clasificar
     * el tier sobre los archivos del mod (jar montado o dir explotado). La API
     * moderna es getRootPaths(): List&lt;Path&gt; (un mod puede ser multi-raíz, p.
     * ej. jar-in-jar); loaders viejos exponen getRootPath(): Path. Por reflexión
     * para no enlazar con el loader. Lista vacía → Tier devuelve null.
     */
    @SuppressWarnings("unchecked")
    private static List<java.nio.file.Path> fabricRoots(Object container) {
        try {
            Object v = call(container, "getRootPaths");
            if (v instanceof List) return (List<java.nio.file.Path>) v;
        } catch (Throwable ignored) {
            // API vieja: probamos getRootPath singular
        }
        try {
            Object v = call(container, "getRootPath");
            if (v instanceof java.nio.file.Path) {
                return Collections.singletonList((java.nio.file.Path) v);
            }
        } catch (Throwable ignored) {
            // sin raíces accesibles: el tier quedará null
        }
        return Collections.emptyList();
    }

    /**
     * Archivos en disco (el/los jar) de un mod de Fabric/Quilt, para que el
     * launcher pueda excluirlos de mods/ en un relaunch (Fase 3, corte 1).
     * ModContainer.getOrigin().getPaths() da las rutas REALES en disco cuando el
     * origen es de tipo PATH; para mods anidados (jar-in-jar, kind NESTED)
     * getPaths() lanza y devolvemos vacío — un módulo anidado no se aísla sin
     * tocar su jar contenedor. Por reflexión, sin enlazar con el loader.
     */
    @SuppressWarnings("unchecked")
    private static List<String> fabricFiles(Object container) {
        try {
            Object origin = call(container, "getOrigin");
            if (origin != null) {
                Object v = call(origin, "getPaths");
                if (v instanceof List) {
                    List<String> out = new ArrayList<String>();
                    for (Object p : (List<Object>) v) out.add(String.valueOf(p));
                    return out;
                }
            }
        } catch (Throwable ignored) {
            // NESTED u origen sin getPaths(): sin archivo en disco aislable
        }
        return Collections.emptyList();
    }

    /**
     * Jar en disco de un mod de Forge/NeoForge para el mismo fin: la cadena
     * IModInfo.getOwningFile() → IModFileInfo.getFile() → IModFile.getFilePath()
     * devuelve el .jar real en disco (no la raíz montada dentro del zip-FS que
     * usa forgeRoots para clasificar el tier). Por reflexión sobre el SPI público.
     */
    private static List<String> forgeFiles(Object info) {
        Object modFile = chain(info, "getOwningFile", "getFile");
        if (modFile == null) return Collections.emptyList();
        Object filePath = callQuiet(modFile, "getFilePath");
        if (filePath instanceof java.nio.file.Path) {
            return Collections.singletonList(String.valueOf(filePath));
        }
        return Collections.emptyList();
    }

    /**
     * Raíces de contenido de un IModInfo de Forge/NeoForge. Cadena del SPI:
     * IModInfo.getOwningFile() → IModFileInfo.getFile() → IModFile, del que la
     * raíz del jar montado sale por getSecureJar().getRootPath() (Path dentro de
     * un FS ya abierto); si esa cadena no está (Forge viejo, sin SecureJar), se
     * cae a getFilePath(): el .jar en disco, que Tier monta él mismo.
     *
     * Todo por reflexión por nombre de método sobre las interfaces públicas — no
     * se nombra ninguna clase impl, no exportada bajo JPMS. Cualquier eslabón
     * ausente devuelve lista vacía y el tier queda null antes que mentir.
     */
    private static List<java.nio.file.Path> forgeRoots(Object info) {
        Object modFile = chain(info, "getOwningFile", "getFile");
        if (modFile == null) return Collections.emptyList();
        Object secureJar = callQuiet(modFile, "getSecureJar");
        if (secureJar != null) {
            Object root = callQuiet(secureJar, "getRootPath");
            if (root instanceof java.nio.file.Path) {
                return Collections.singletonList((java.nio.file.Path) root);
            }
        }
        Object filePath = callQuiet(modFile, "getFilePath");
        if (filePath instanceof java.nio.file.Path) {
            return Collections.singletonList((java.nio.file.Path) filePath);
        }
        return Collections.emptyList();
    }

    /** Encadena llamadas sin-argumentos por nombre; null en cuanto un eslabón falla. */
    private static Object chain(Object target, String... methods) {
        Object cur = target;
        for (String mth : methods) {
            if (cur == null) return null;
            cur = callQuiet(cur, mth);
        }
        return cur;
    }

    /**
     * Como call() pero devuelve null en vez de lanzar, y resuelve el método sobre
     * una interfaz pública cuando la haya: bajo JPMS las clases impl del loader
     * viven en paquetes no exportados, e invocar un Method declarado en la impl
     * lanza IllegalAccessException — el del SPI público sí es invocable.
     */
    private static Object callQuiet(Object target, String method) {
        for (Class<?> iface : allInterfaces(target.getClass())) {
            try {
                java.lang.reflect.Method m = iface.getMethod(method);
                return m.invoke(target);
            } catch (NoSuchMethodException nsme) {
                // esta interfaz no lo declara: probamos la siguiente
            } catch (Throwable t) {
                return null;
            }
        }
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Todas las interfaces que implementa cls, directas y heredadas. */
    private static java.util.Set<Class<?>> allInterfaces(Class<?> cls) {
        java.util.LinkedHashSet<Class<?>> out = new java.util.LinkedHashSet<Class<?>>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            collectInterfaces(c, out);
        }
        return out;
    }

    private static void collectInterfaces(Class<?> c, java.util.Set<Class<?>> out) {
        for (Class<?> i : c.getInterfaces()) {
            if (out.add(i)) collectInterfaces(i, out);
        }
    }

    /** Invocación reflexiva tolerante a clases impl no públicas del loader. */
    private static Object call(Object target, String method) throws Exception {
        java.lang.reflect.Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    /** Descripción del mod (getDescription(): String). Vale para Fabric/Quilt (meta) y Forge/NeoForge (IModInfo). */
    private static String extractDescription(Object source) {
        if (source == null) return null;
        try {
            Object d = callQuiet(source, "getDescription");
            if (d == null) return null;
            String s = d.toString();
            if (s.isEmpty()) return null;
            return s;
        } catch (Throwable ignored) { return null; }
    }

    /**
     * Encola la descarga del icono del mod desde Modrinth si todavía no está
     * cacheado. Boot.mods NO almacena el icono — el binario lo guarda el
     * fetcher en {@code mksa/mod-icons/<modId>.png} y {@link Ledger#modThinListMods}
     * lo lee LIVE en cada llamada. Así no importa cuánto tarde Modrinth: cuando
     * la UI haga el siguiente refresh verá el icono.
     *
     * @return true si el icono ya estaba cacheado, false si se encoló (o falló).
     */
    private static boolean resolveIcon(String modId, String modName) {
        if (modId == null || modId.isEmpty()) return false;
        if (ModIconStore.loadCached(modId) != null) return true;
        ModIconStore.scheduleFetch(modId, modName);
        return false;
    }

}
