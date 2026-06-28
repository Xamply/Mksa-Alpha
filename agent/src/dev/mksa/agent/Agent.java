package dev.mksa.agent;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * MKSA fable-agent — stub de Fase 1.
 *
 * Implementa el subconjunto F1 del protocolo IPC v1 (docs/protocolo-ipc.md):
 * descubrimiento por archivo de contacto, hello con token, ping, boot.stage,
 * shutdown. La instrumentación real llega en Fase 2.
 *
 * Contrato Java 8: sin dependencias, sin NIO de sockets, sin var/lambdas con
 * API >8. Compilar con --release 8.
 */
public final class Agent {

    static final int PROTO = 1;
    static final String VERSION = "0.2.0";

    static String token;
    static Path mksaDir;
    static String sessionId;
    static volatile String bootStage = "premain";

    /** Instrumentation, para el descubrimiento estructural bajo demanda (in-process). */
    static volatile Instrumentation instrumentation;

    // Una sola conexión de launcher activa (protocolo §1.4).
    static final Object connLock = new Object();
    static Conn active;

    // Lockout tras fallos de autenticación (protocolo §1.4).
    static int authFails;
    static long lockoutUntil;

    public static void premain(String agentArgs, Instrumentation inst) {
        token = System.getProperty("mksa.token");
        String dir = System.getProperty("mksa.dir");
        if (token == null || token.isEmpty() || dir == null || dir.isEmpty()) {
            System.err.println("[mksa] sin mksa.token/mksa.dir; agente inactivo (lanzado fuera del launcher MKSA)");
            return;
        }
        mksaDir = Paths.get(dir);
        sessionId = randomHex(6);
        instrumentation = inst;
        // Ledger (inc.5): el Bridge debe vivir en el bootstrap para que el bytecode
        // inyectado lo resuelva desde cualquier classloader de MC. Se instala antes
        // que el transformer y mucho antes de que el código inyectado se ejecute.
        installBridge(inst);
        installLedger(inst);
        registerLedgerSink();
        Thread t = new Thread(new Runnable() {
            public void run() { serve(); }
        }, "mksa-ipc");
        t.setDaemon(true);
        t.start();
        Boot.start(inst);
    }

    /**
     * Extrae mksa-bridge.jar (empaquetado como recurso dentro del agent jar) a un
     * temporal y lo añade al bootstrap classloader. Así dev.mksa.bridge.Bridge es
     * visible desde el classloader que cargue las clases de Minecraft, requisito
     * para que el INVOKESTATIC inyectado por el LedgerTransformer enlace.
     */
    static void installBridge(Instrumentation inst) {
        try {
            InputStream in = Agent.class.getResourceAsStream("/mksa-bridge.jar");
            if (in == null) {
                System.err.println("[mksa] mksa-bridge.jar no está en el agent jar; ledger inactivo");
                return;
            }
            Path tmp = Files.createTempFile("mksa-bridge", ".jar");
            tmp.toFile().deleteOnExit();
            try {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                in.close();
            }
            inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(tmp.toFile()));
            System.err.println("[mksa] bridge añadido al bootstrap: " + tmp);
        } catch (Throwable th) {
            System.err.println("[mksa] installBridge falló: " + th);
        }
    }

    /** Mantiene viva la referencia al classloader del ledger (evita GC). */
    static ClassLoader ledgerLoader;

    /**
     * Extrae mksa-ledger.jar (dev.mksa.agent.ledger.* + ASM) a un temporal y lo
     * carga en un URLClassLoader AISLADO, no en el del sistema.
     *
     * Por qué aislado y no dentro del agent jar: el JVM añade el javaagent jar al
     * classpath del SISTEMA, y los loaders (Fabric LoaderUtil.verifyClasspath)
     * abortan si encuentran ASM duplicado ahí — el suyo y el nuestro. Metiendo ASM
     * en un classloader propio, nunca aparece en el classpath del sistema.
     *
     * El loader es child-first para org.objectweb.asm.* y dev.mksa.agent.ledger.*:
     * así usamos SIEMPRE nuestro ASM (versión fijada), no el que traiga el loader.
     * El transformer solo manipula byte[] y emite un INVOKESTATIC al Bridge por
     * nombre (string), así que no necesita ver clases de Minecraft.
     */
    static void installLedger(Instrumentation inst) {
        try {
            InputStream in = Agent.class.getResourceAsStream("/mksa-ledger.jar");
            if (in == null) {
                System.err.println("[mksa] mksa-ledger.jar no está en el agent jar; ledger inactivo");
                return;
            }
            Path tmp = Files.createTempFile("mksa-ledger", ".jar");
            tmp.toFile().deleteOnExit();
            try {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                in.close();
            }
            java.net.URL[] urls = { tmp.toUri().toURL() };
            LedgerClassLoader cl = new LedgerClassLoader(urls, Agent.class.getClassLoader());
            ledgerLoader = cl;
            Class<?> tr = cl.loadClass("dev.mksa.agent.ledger.LedgerTransformer");
            tr.getMethod("install", Instrumentation.class).invoke(null, inst);
            System.err.println("[mksa] ledger cargado en classloader aislado: " + tmp);
        } catch (Throwable th) {
            Throwable root = th;
            if (th instanceof java.lang.reflect.InvocationTargetException) {
                Throwable cause = ((java.lang.reflect.InvocationTargetException) th).getCause();
                if (cause != null) root = cause;
            }
            System.err.println("[mksa] installLedger falló: " + th + " root=" + root);
        }
    }

    /**
     * Registra el cerebro del ledger (Paso 2) con el Bridge por reflexión. El
     * Bridge vive en el bootstrap (ya añadido por installBridge), su interfaz
     * LedgerSink también; Ledger (CL del sistema) la implementa. A partir de aquí
     * cada (de)serialización de chunk escanea la palette y la época es real.
     */
    static void registerLedgerSink() {
        try {
            Class<?> bridge = Class.forName("dev.mksa.bridge.Bridge");
            Class<?> sinkIface = Class.forName("dev.mksa.bridge.LedgerSink");
            bridge.getMethod("setSink", sinkIface).invoke(null, Ledger.INSTANCE);
            System.err.println("[mksa] ledger sink registrado (Paso 2)");
        } catch (Throwable th) {
            System.err.println("[mksa] no se pudo registrar el ledger sink: " + th);
        }
    }

    /** Child-first para ASM y el paquete ledger; el resto (JDK) delega al padre. */
    static final class LedgerClassLoader extends java.net.URLClassLoader {
        LedgerClassLoader(java.net.URL[] urls, ClassLoader parent) { super(urls, parent); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    boolean mine = name.startsWith("org.objectweb.asm.")
                            || name.startsWith("dev.mksa.agent.ledger.");
                    if (mine) {
                        try { c = findClass(name); } catch (ClassNotFoundException ignored) {}
                    }
                    if (c == null) c = super.loadClass(name, false);
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }

    // ---- etapas de arranque (protocolo §3.2) ----

    static void stage(String stage, Map<String, Object> detail) {
        bootStage = stage;
        broadcast(evt("boot.stage", map("stage", stage, "detail", detail)));
        System.err.println("[mksa] boot.stage " + stage);
        // F4 warm-up: tras game-ready, amortiza la construcción del índice invertido
        // wcg.refs (~1.7 s) en background. Tolerante: jamás falla la emisión por esto.
        if ("game-ready".equals(stage)) {
            try { InProcess.INSTANCE.warmRefsIndexAsync(); } catch (Throwable ignored) {}
        }
    }

    static void bootFailed(String stage, String error) {
        broadcast(evt("boot.failed", map("stage", stage, "error", error)));
        System.err.println("[mksa] boot.failed en " + stage + ": " + error);
    }

    static void broadcast(String line) {
        Conn c;
        synchronized (connLock) { c = active; }
        if (c != null) c.sendQuiet(line);
    }

    static void serve() {
        final ServerSocket server;
        try {
            server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
            writeContactFile(server.getLocalPort());
        } catch (IOException e) {
            System.err.println("[mksa] no se pudo abrir el socket IPC: " + e);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                Conn c;
                synchronized (connLock) { c = active; }
                if (c != null) c.sendQuiet(evt("shutdown", map("reason", "user-quit")));
                try { Files.deleteIfExists(mksaDir.resolve("agent.json")); } catch (IOException ignored) {}
            }
        }, "mksa-shutdown"));
        System.err.println("[mksa] agente escuchando en 127.0.0.1:" + server.getLocalPort());
        while (true) {
            try {
                final Socket s = server.accept();
                if (System.currentTimeMillis() < lockoutUntil) { s.close(); continue; }
                Thread h = new Thread(new Runnable() {
                    public void run() { handle(s); }
                }, "mksa-conn");
                h.setDaemon(true);
                h.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    static void writeContactFile(int port) throws IOException {
        Files.createDirectories(mksaDir);
        String started = iso8601(System.currentTimeMillis());
        String json = "{\"v\":1,\"pid\":" + pid() + ",\"port\":" + port
                + ",\"started\":\"" + started + "\"}\n";
        Path tmp = mksaDir.resolve("agent.json.tmp");
        Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, mksaDir.resolve("agent.json"),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, mksaDir.resolve("agent.json"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void handle(Socket s) {
        Conn c = null;
        try {
            s.setSoTimeout(5000); // timeout de hello (protocolo §1.4)
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            c = new Conn(s);
            String first = in.readLine();
            if (first == null) { s.close(); return; }
            Object msg = Json.parse(first);
            if (!isHello(msg)) { authFail(); s.close(); return; }
            Map<?, ?> m = (Map<?, ?>) msg;
            Map<?, ?> p = (Map<?, ?>) m.get("p");
            Object id = m.get("id");
            if (!token.equals(p.get("token"))) {
                c.sendQuiet(err(id, "AUTH_FAILED", "token incorrecto", null));
                authFail();
                s.close();
                return;
            }
            long theirProto = num(p.get("proto"), 0);
            if (theirProto < 1) {
                c.sendQuiet(err(id, "PROTO_INCOMPATIBLE", "se requiere proto >= 1", null));
                s.close();
                return;
            }
            authFails = 0;
            synchronized (connLock) {
                if (active != null) active.close(); // el launcher más reciente gana (§1.4)
                active = c;
            }
            Map<String, Object> ok = map(
                    "proto", PROTO, "side", "agent", "version", VERSION,
                    "session", sessionId, "boot", bootStage);
            c.send(res(id, ok));
            s.setSoTimeout(0);
            String line;
            while ((line = in.readLine()) != null) {
                dispatch(c, line);
            }
        } catch (Exception e) {
            // conexión caída: limpieza abajo
        } finally {
            synchronized (connLock) { if (active == c) active = null; }
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    static void dispatch(Conn c, String line) throws IOException {
        Object msg;
        try { msg = Json.parse(line); } catch (Exception e) { return; }
        if (!(msg instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) msg;
        if (!"req".equals(m.get("t"))) return; // evts desconocidos se ignoran (§2.1)
        Object id = m.get("id");
        String method = String.valueOf(m.get("m"));
        if ("ping".equals(method)) {
            c.send(res(id, new LinkedHashMap<String, Object>()));
        } else if ("state.snapshot".equals(method)) {
            if (Boot.loader == null) {
                c.send(err(id, "NOT_READY", "el arranque no ha llegado a loader-detected",
                        map("requires", "loader-detected")));
                return;
            }
            c.send(res(id, map(
                    "mc", Boot.mcVersion,
                    "loader", map("name", Boot.loader, "version", Boot.loaderVersion),
                    "names", Boot.names,
                    "epoch", Ledger.INSTANCE.epochReady() ? (Object) Ledger.INSTANCE.epoch() : null,
                    "boot", bootStage,
                    "mods", Boot.mods)));
        } else if ("state.mods".equals(method)) {
            if (Boot.loader == null) {
                c.send(err(id, "NOT_READY", "el arranque no ha llegado a loader-detected",
                        map("requires", "loader-detected")));
                return;
            }
            c.send(res(id, map("mods", Boot.mods)));
        } else if ("state.epoch".equals(method)) {
            if (!Ledger.INSTANCE.epochReady()) {
                c.send(err(id, "NOT_READY", "la época se calcula tras enumerar los mods",
                        map("requires", "loader-detected")));
                return;
            }
            c.send(res(id, Ledger.INSTANCE.epochInfo()));
        } else if ("wcg.counts".equals(method)) {
            // Lectura del agregado en memoria (modelo §5.3): O(chunks vistos). No
            // requiere wcg-ready — el agregado se puebla al (de)serializar chunks.
            Map<?, ?> p = m.get("p") instanceof Map ? (Map<?, ?>) m.get("p") : null;
            String ns = p != null && p.get("ns") instanceof String ? (String) p.get("ns") : null;
            if (ns == null || ns.isEmpty()) {
                c.send(err(id, "BAD_PARAMS", "wcg.counts requiere ns", null));
                return;
            }
            c.send(res(id, Ledger.INSTANCE.counts(ns)));
        } else if ("wcg.definitions".equals(method)) {
            if (!Wcg.ready) {
                c.send(err(id, "NOT_READY", "el WCG aún no está construido",
                        map("requires", "wcg-ready")));
                return;
            }
            Map<?, ?> p = m.get("p") instanceof Map ? (Map<?, ?>) m.get("p") : null;
            String ns = p != null && p.get("ns") instanceof String ? (String) p.get("ns") : null;
            if (ns == null || ns.isEmpty()) {
                c.send(err(id, "BAD_PARAMS", "wcg.definitions requiere ns", null));
                return;
            }
            String reg = p.get("reg") instanceof String ? (String) p.get("reg") : null;
            String cursor = null;
            long limit = 500;
            if (p.get("page") instanceof Map) {
                Map<?, ?> page = (Map<?, ?>) p.get("page");
                if (page.get("cursor") instanceof String) cursor = (String) page.get("cursor");
                limit = num(page.get("limit"), 500);
            }
            c.send(res(id, Wcg.query(ns, reg, cursor, limit)));
        } else if ("wcg.refs".equals(method)) {
            // F4 query invertida de cascada: "si desactivas el ns V, ¿qué recetas de
            // OTROS mods se rompen porque usan sus items?", agrupado por dueño. Backing
            // EN MEMORIA (índice invertido reconstruible, sellado con epoch); por ahora
            // SOLO aristas de receta (tags/loot/persistencia diferidos). El método es
            // contrato congelado, lo que crece es su completitud, no su forma.
            Map<?, ?> p = m.get("p") instanceof Map ? (Map<?, ?>) m.get("p") : null;
            String ns = p != null && p.get("ns") instanceof String ? (String) p.get("ns") : null;
            if (ns == null || ns.isEmpty()) {
                c.send(err(id, "BAD_PARAMS", "wcg.refs requiere ns", null));
                return;
            }
            boolean rebuild = p != null && Boolean.TRUE.equals(p.get("rebuild"));
            Map<String, Object> r = InProcess.INSTANCE.wcgRefs(ns, rebuild);
            if (Boolean.TRUE.equals(r.get("ok"))) {
                c.send(res(id, r));
            } else {
                String code = r.get("code") instanceof String ? (String) r.get("code") : "INTERNAL";
                c.send(err(id, code, String.valueOf(r.get("error")), r));
            }
        } else if ("ledger.stats".equals(method)) {
            // Diagnóstico del ledger. Del Bridge (bootstrap): contadores de la
            // tubería de procedencia (Pasos 1-2). Del Ledger (CL del sistema): el
            // ledger en vivo (Paso 3) — setBlocks, agencia, índice de bloques.
            long written = 0, readWithProv = 0, readTotal = 0;
            try {
                Class<?> b = Class.forName("dev.mksa.bridge.Bridge");
                long[] s = (long[]) b.getMethod("stats").invoke(null);
                written = s[0]; readWithProv = s[1]; readTotal = s[2];
            } catch (Throwable th) {
                c.send(err(id, "NOT_READY", "bridge del ledger no disponible: " + th, null));
                return;
            }
            c.send(res(id, map(
                    "written", written,
                    "readWithProv", readWithProv,
                    "readTotal", readTotal,
                    "setBlocks", Ledger.INSTANCE.setBlocksCount(),
                    "agencyEntries", Ledger.INSTANCE.agencyEntryCount(),
                    "moddedBlocks", Ledger.INSTANCE.moddedBlockCount(),
                    "armed", Ledger.INSTANCE.armed(),
                    "capturedBlocks", Ledger.INSTANCE.capturedBlockCount(),
                    "capturedItems", Ledger.INSTANCE.capturedItemCount(),
                    "restoring", Ledger.INSTANCE.restoring(),
                    "restoredBlocks", Ledger.INSTANCE.restoredBlockCount(),
                    "restoredItems", Ledger.INSTANCE.restoredItemCount(),
                    "restoreConflicts", Ledger.INSTANCE.restoreConflictCount(),
                    "restoreMisses", Ledger.INSTANCE.restoreMissCount(),
                    "playerReadCalls", Ledger.INSTANCE.playerReadCallCount(),
                    "playerReadInv", Ledger.INSTANCE.playerReadInvCount(),
                    "itemRecsMatched", Ledger.INSTANCE.itemRecsMatchedCount(),
                    "playerReadDiag", Ledger.INSTANCE.playerReadDiag(),
                    // Diag pieza 2 (Tier 1 corte 2): por qué byChunk no se incrementó.
                    "byChunkInc", Ledger.INSTANCE.byChunkIncCount(),
                    "byChunkDec", Ledger.INSTANCE.byChunkDecCount(),
                    "setBlockPrevNull", Ledger.INSTANCE.setBlockPrevNullCount(),
                    "setBlockPrevSame", Ledger.INSTANCE.setBlockPrevSameCount(),
                    "setBlockNsNull", Ledger.INSTANCE.setBlockNsNullCount(),
                    "setBlockNsEqual", Ledger.INSTANCE.setBlockNsEqualCount(),
                    "setBlockBothVanilla", Ledger.INSTANCE.setBlockBothVanillaCount(),
                    "getBlockMethodSig", Ledger.INSTANCE.getBlockMethodSig(),
                    // Tier 1 corte 3 (filtro de registro vivo).
                    "vetoArmed", (long) Ledger.INSTANCE.armedBlockVeto().size(),
                    "vetoHits", Ledger.INSTANCE.vetoHitsCount(),
                    "vetoPassthrough", Ledger.INSTANCE.vetoPassthroughCount(),
                    // Tier 1 corte 4 (reload dirigido de datapacks).
                    "dpArmed", (long) Ledger.INSTANCE.armedDp().size(),
                    "dpFilteredRecipes", Ledger.INSTANCE.dpFilteredRecipesCount(),
                    "dpFilteredTags", Ledger.INSTANCE.dpFilteredTagsCount(),
                    "dpRecipeHookCalls", Ledger.INSTANCE.dpRecipeHookCallsCount(),
                    "dpTagHookCalls", Ledger.INSTANCE.dpTagHookCallsCount(),
                    "dpLastRecipePayloadClass", Ledger.INSTANCE.dpLastRecipePayloadClass(),
                    "dpLastError", Ledger.INSTANCE.dpLastError(),
                    // F4 warm-up del índice wcg.refs (lado-agente, no del ledger; expuesto
                    // aquí porque ledger.stats es la superficie de diag única del agente).
                    "refsWarmupStarted",  InProcess.INSTANCE.refsWarmupStarted(),
                    "refsWarmupReady",    InProcess.INSTANCE.refsWarmupReady(),
                    "refsWarmupAttempts", InProcess.INSTANCE.refsWarmupAttempts(),
                    "refsWarmupMs",       InProcess.INSTANCE.refsWarmupMs(),
                    "refsWarmupError",    InProcess.INSTANCE.refsWarmupError())));
        } else if ("ledger.sweepArm".equals(method)) {
            // Corte 3: arma el barrido dirigido. El launcher pasa la ruta del archivo
            // de restauración (el agente no tiene handle al servidor para conocer la
            // ruta del mundo). Tras armar, el save de cierre captura la huella.
            Map<?, ?> p = m.get("p") instanceof Map ? (Map<?, ?>) m.get("p") : null;
            String ns = p != null && p.get("ns") instanceof String ? (String) p.get("ns") : null;
            String path = p != null && p.get("path") instanceof String ? (String) p.get("path") : null;
            if (ns == null || ns.isEmpty() || path == null || path.isEmpty()) {
                c.send(err(id, "BAD_PARAMS", "ledger.sweepArm requiere ns y path", null));
                return;
            }
            Map<String, Object> r = Ledger.INSTANCE.arm(ns, path);
            if (Boolean.TRUE.equals(r.get("armed"))) c.send(res(id, r));
            else c.send(err(id, "INTERNAL", "no se pudo armar el barrido", r));
        } else if ("ledger.restoreArm".equals(method)) {
            // Corte 4: arma la re-inyección de bloques. El launcher pasa la ruta del
            // archivo de restauración; los chunks que C deserialice se restauran al leerse.
            Map<?, ?> p = m.get("p") instanceof Map ? (Map<?, ?>) m.get("p") : null;
            String path = p != null && p.get("path") instanceof String ? (String) p.get("path") : null;
            if (path == null || path.isEmpty()) {
                c.send(err(id, "BAD_PARAMS", "ledger.restoreArm requiere path", null));
                return;
            }
            Map<String, Object> r = Ledger.INSTANCE.restoreArm(path);
            if (Boolean.TRUE.equals(r.get("armed"))) c.send(res(id, r));
            else c.send(err(id, "INTERNAL", "no se pudo armar la re-inyección", r));
        } else if ("ledger.vetoArm".equals(method)) {
            // Tier 1 corte 3: filtro de registro vivo. Bloquea LevelChunk.setBlockState
            // para los namespaces dados (devuelve null como si MC hubiese hecho un no-op
            // por idempotencia). namespaces:[] = desarma. Aditivo, no rompe protocolo.
            Map<?, ?> p = m.get("p") instanceof Map ? (Map<?, ?>) m.get("p") : null;
            Object nsObj = p != null ? p.get("namespaces") : null;
            if (!(nsObj instanceof List)) {
                c.send(err(id, "BAD_PARAMS", "ledger.vetoArm requiere namespaces:[...]", null));
                return;
            }
            Set<String> ns = new HashSet<String>();
            for (Object x : (List<?>) nsObj) {
                if (x instanceof String && !((String) x).isEmpty()) ns.add((String) x);
            }
            c.send(res(id, Ledger.INSTANCE.armBlockVeto(ns)));
        } else if ("ledger.vetoDisarm".equals(method)) {
            c.send(res(id, Ledger.INSTANCE.disarmBlockVeto()));
        } else if ("ledger.dpFilterArm".equals(method)) {
            // Tier 1 corte 4: filtro de datapack. Arma el set de namespaces cuyos
            // recetas/tags se filtrarán in-place en el próximo reload. namespaces:[] = desarma.
            Map<?, ?> p = m.get("p") instanceof Map ? (Map<?, ?>) m.get("p") : null;
            Object nsObj = p != null ? p.get("namespaces") : null;
            if (!(nsObj instanceof List)) {
                c.send(err(id, "BAD_PARAMS", "ledger.dpFilterArm requiere namespaces:[...]", null));
                return;
            }
            Set<String> ns = new HashSet<String>();
            for (Object x : (List<?>) nsObj) {
                if (x instanceof String && !((String) x).isEmpty()) ns.add((String) x);
            }
            c.send(res(id, Ledger.INSTANCE.armDpFilter(ns)));
        } else if ("ledger.dpFilterDisarm".equals(method)) {
            c.send(res(id, Ledger.INSTANCE.disarmDpFilter()));
        } else if ("tx.probeSetBlock".equals(method)) {
            // In-process corte 1 (de-risk, §3.5): handle vivo + marshaling al hilo del
            // servidor + mutación reversible. Método interim de de-risk, NO parte del
            // contrato tx congelado. NOT_READY si el servidor aún no existe.
            Map<String, Object> r = InProcess.INSTANCE.probe();
            if (Boolean.TRUE.equals(r.get("ok"))) {
                c.send(res(id, r));
            } else {
                String code = r.get("code") instanceof String ? (String) r.get("code") : "INTERNAL";
                c.send(err(id, code, String.valueOf(r.get("error")), r));
            }
        } else if ("tx.tier1Sweep".equals(method)) {
            // Tier 1 corte 1 (in-process): barrido vivo del footprint de la víctima
            // con captura simétrica al corte 3 del relaunch (deuda técnica conocida:
            // saveAllChunks como disparador). Método interim de de-risk, NO parte del
            // contrato tx congelado, igual que tx.probeSetBlock.
            Map<?, ?> p = m.get("p") instanceof Map ? (Map<?, ?>) m.get("p") : null;
            String ns = p != null && p.get("ns") instanceof String ? (String) p.get("ns") : null;
            String restorePath = p != null && p.get("restorePath") instanceof String
                    ? (String) p.get("restorePath") : null;
            boolean dryRun = p != null && Boolean.TRUE.equals(p.get("dryRun"));
            if (ns == null || ns.isEmpty()) {
                c.send(err(id, "BAD_PARAMS", "tx.tier1Sweep requiere ns", null));
                return;
            }
            if (!dryRun && (restorePath == null || restorePath.isEmpty())) {
                c.send(err(id, "BAD_PARAMS", "tx.tier1Sweep requiere restorePath (salvo dryRun)", null));
                return;
            }
            Map<String, Object> r = InProcess.INSTANCE.tier1Sweep(ns, restorePath, dryRun);
            if (Boolean.TRUE.equals(r.get("ok"))) {
                c.send(res(id, r));
            } else {
                String code = r.get("code") instanceof String ? (String) r.get("code") : "INTERNAL";
                c.send(err(id, code, String.valueOf(r.get("error")), r));
            }
        } else if ("tx.tier1Disable".equals(method)) {
            // Tier 1 corte 5: envoltorio transaccional. Orquesta los tres mecanismos
            // probados (filter del setBlockState, sweep zero-IO, reload dirigido de
            // datapacks) como UNA operación atómica con TxReceipt explícito como
            // contrato de salida (txId, snapshot, steps con veredicto y métricas,
            // status agregado). Política: best-effort + reporte. Persistencia in-memory
            // cap 32 (vía TxJournal). Aditivo, NO parte del contrato tx congelado.
            Map<?, ?> p = m.get("p") instanceof Map ? (Map<?, ?>) m.get("p") : null;
            String ns = p != null && p.get("ns") instanceof String ? (String) p.get("ns") : null;
            String restorePath = p != null && p.get("restorePath") instanceof String
                    ? (String) p.get("restorePath") : null;
            if (ns == null || ns.isEmpty()) {
                c.send(err(id, "BAD_PARAMS", "tx.tier1Disable requiere ns", null));
                return;
            }
            if (restorePath == null || restorePath.isEmpty()) {
                c.send(err(id, "BAD_PARAMS", "tx.tier1Disable requiere restorePath", null));
                return;
            }
            Map<String, Object> r = InProcess.INSTANCE.tier1Disable(ns, restorePath);
            // tier1Disable siempre devuelve un receipt (incluso en FAILED): el status
            // del receipt es el veredicto; el IPC res ok lleva el receipt entero.
            if (Boolean.FALSE.equals(r.get("ok"))) {
                String code = r.get("code") instanceof String ? (String) r.get("code") : "INTERNAL";
                c.send(err(id, code, String.valueOf(r.get("error")), r));
            } else {
                c.send(res(id, r));
            }
        } else if ("tx.tier1Enable".equals(method)) {
            // Tier 1 corte 5b: envoltorio del enable. Pasos: vetoDisarm → restitución
            // viva → dpReload sin filtro. La restitución re-inyecta los bloques
            // capturados al mundo vivo reconstruyendo cada BlockState desde el NBT del
            // archivo MKSAR1 (param restorePath, opcional). Si restorePath es null el
            // paso de restitución se registra skipped (compat: solo desarmar veto +
            // recargar datapacks). La reversibilidad del DATO sigue además cubierta por
            // el camino de relaunch (cortes 4 + 4b).
            Map<?, ?> p = m.get("p") instanceof Map ? (Map<?, ?>) m.get("p") : null;
            String ns = p != null && p.get("ns") instanceof String ? (String) p.get("ns") : null;
            String restorePath = p != null && p.get("restorePath") instanceof String
                    ? (String) p.get("restorePath") : null;
            if (ns == null || ns.isEmpty()) {
                c.send(err(id, "BAD_PARAMS", "tx.tier1Enable requiere ns", null));
                return;
            }
            Map<String, Object> r = InProcess.INSTANCE.tier1Enable(ns, restorePath);
            if (Boolean.FALSE.equals(r.get("ok"))) {
                String code = r.get("code") instanceof String ? (String) r.get("code") : "INTERNAL";
                c.send(err(id, code, String.valueOf(r.get("error")), r));
            } else {
                c.send(res(id, r));
            }
        } else if ("tx.history".equals(method)) {
            // Tier 1 corte 5: historial de TxReceipts en memoria (cap 32). Útil para UI
            // futura (barra de progreso, lista de transacciones recientes, debug). No
            // persiste cross-restart; el archivo MKSAR1 es la verdad para restaurar.
            java.util.List<TxJournal.TxReceipt> all = TxJournal.INSTANCE.all();
            java.util.List<Map<String, Object>> items = new java.util.ArrayList<Map<String, Object>>();
            for (TxJournal.TxReceipt rcpt : all) items.add(rcpt.toMap());
            c.send(res(id, map("items", items, "count", (long) items.size())));
        } else if ("tx.tier1DpReload".equals(method)) {
            // Tier 1 corte 4 (in-process): reload dirigido de datapacks. Arma el filtro
            // por namespaces, dispara MinecraftServer.reloadResources(currentPacks) y
            // espera la CF; los hooks de RecipeManager.prepare y TagManagerLoader.build
            // descartan in-place las entradas con ns víctima ANTES de la fase apply.
            // Aditivo, NO parte del contrato tx congelado (igual que tier1Sweep / probeSetBlock).
            Map<?, ?> p = m.get("p") instanceof Map ? (Map<?, ?>) m.get("p") : null;
            Object nsObj = p != null ? p.get("namespaces") : null;
            if (!(nsObj instanceof List) || ((List<?>) nsObj).isEmpty()) {
                c.send(err(id, "BAD_PARAMS", "tx.tier1DpReload requiere namespaces:[...]", null));
                return;
            }
            Set<String> nss = new HashSet<String>();
            for (Object x : (List<?>) nsObj) {
                if (x instanceof String && !((String) x).isEmpty()) nss.add((String) x);
            }
            if (nss.isEmpty()) {
                c.send(err(id, "BAD_PARAMS", "namespaces vacío tras filtrar", null));
                return;
            }
            Map<String, Object> r = InProcess.INSTANCE.tier1DpReload(nss);
            if (Boolean.TRUE.equals(r.get("ok"))) {
                c.send(res(id, r));
            } else {
                String code = r.get("code") instanceof String ? (String) r.get("code") : "INTERNAL";
                c.send(err(id, code, String.valueOf(r.get("error")), r));
            }
        } else if ("tx.probeRecipes".equals(method)) {
            // F4 wcg.refs (de-risk): lee el mapa de recetas VIVO y extrae estructuralmente
            // los ids de item (ingredientes + resultado) de cada receta, reportando
            // cobertura + aristas cross-ns hacia el ns. Método interim de de-risk para
            // el grafo de aristas de referencia, NO parte del contrato tx congelado
            // (igual que tx.probeSetBlock).
            Map<?, ?> p = m.get("p") instanceof Map ? (Map<?, ?>) m.get("p") : null;
            String ns = p != null && p.get("ns") instanceof String ? (String) p.get("ns") : null;
            if (ns == null || ns.isEmpty()) {
                c.send(err(id, "BAD_PARAMS", "tx.probeRecipes requiere ns", null));
                return;
            }
            Map<String, Object> r = InProcess.INSTANCE.probeRecipes(ns);
            if (Boolean.TRUE.equals(r.get("ok"))) {
                c.send(res(id, r));
            } else {
                String code = r.get("code") instanceof String ? (String) r.get("code") : "INTERNAL";
                c.send(err(id, code, String.valueOf(r.get("error")), r));
            }
        } else if ("tx.probeTags".equals(method)) {
            // F4 wcg.refs (de-risk): lee los tags VIVOS (item+block) del registro y atribuye
            // tag-como-ingrediente sobre el RecipeManager vivo. Método interim de de-risk de
            // aristas de tag, NO parte del contrato tx congelado (igual que tx.probeRecipes).
            Map<?, ?> p = m.get("p") instanceof Map ? (Map<?, ?>) m.get("p") : null;
            String ns = p != null && p.get("ns") instanceof String ? (String) p.get("ns") : null;
            if (ns == null || ns.isEmpty()) {
                c.send(err(id, "BAD_PARAMS", "tx.probeTags requiere ns", null));
                return;
            }
            Map<String, Object> r = InProcess.INSTANCE.probeTags(ns);
            if (Boolean.TRUE.equals(r.get("ok"))) {
                c.send(res(id, r));
            } else {
                String code = r.get("code") instanceof String ? (String) r.get("code") : "INTERNAL";
                c.send(err(id, code, String.valueOf(r.get("error")), r));
            }
        } else if ("tx.probeLoot".equals(method)) {
            // F4 wcg.refs (de-risk): localiza el Registry<LootTable> VIVO (no está en
            // BuiltInRegistries — vive en los registros reloadable del servidor) por comportamiento
            // y cosecha los items que suelta cada tabla. Método interim de de-risk de aristas de
            // loot, NO parte del contrato tx congelado (igual que tx.probeRecipes/tx.probeTags).
            Map<?, ?> p = m.get("p") instanceof Map ? (Map<?, ?>) m.get("p") : null;
            String ns = p != null && p.get("ns") instanceof String ? (String) p.get("ns") : null;
            if (ns == null || ns.isEmpty()) {
                c.send(err(id, "BAD_PARAMS", "tx.probeLoot requiere ns", null));
                return;
            }
            Map<String, Object> r = InProcess.INSTANCE.probeLoot(ns);
            if (Boolean.TRUE.equals(r.get("ok"))) {
                c.send(res(id, r));
            } else {
                String code = r.get("code") instanceof String ? (String) r.get("code") : "INTERNAL";
                c.send(err(id, code, String.valueOf(r.get("error")), r));
            }
        } else {
            c.send(err(id, "METHOD_UNKNOWN", "método no soportado: " + method, null));
        }
    }

    static boolean isHello(Object msg) {
        if (!(msg instanceof Map)) return false;
        Map<?, ?> m = (Map<?, ?>) msg;
        return "req".equals(m.get("t")) && "hello".equals(m.get("m")) && m.get("p") instanceof Map;
    }

    static void authFail() {
        authFails++;
        if (authFails >= 3) {
            lockoutUntil = System.currentTimeMillis() + 60000;
            authFails = 0;
        }
    }

    // ---- helpers de mensaje ----

    static String res(Object id, Map<String, Object> ok) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "res"); m.put("id", id); m.put("ok", ok);
        return Json.write(m);
    }

    static String err(Object id, String code, String msg, Object data) {
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("code", code); e.put("msg", msg);
        if (data != null) e.put("data", data);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "res"); m.put("id", id); m.put("err", e);
        return Json.write(m);
    }

    static String evt(String name, Map<String, Object> p) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", "evt"); m.put("m", name); m.put("p", p);
        return Json.write(m);
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static long num(Object o, long dflt) {
        return o instanceof Number ? ((Number) o).longValue() : dflt;
    }

    /**
     * Abre TODOS los paquetes del módulo de cls a nuestro classloader bajo
     * JPMS, vía Instrumentation.redefineModule. Necesario en loaders con
     * módulos reales (NeoForge/Forge): exportado no basta para setAccessible
     * sobre miembros privados, y los registros, sus tipos de entrada y los
     * Identifier viven en paquetes distintos del mismo módulo 'minecraft'.
     * Todo por reflexión: compilamos a Java 8, donde Module no existe — allí
     * es no-op, igual que si el módulo no es modificable.
     */
    @SuppressWarnings("unchecked")
    static void openModule(Instrumentation inst, Class<?> cls) {
        try {
            Class<?> moduleCls = Class.forName("java.lang.Module");
            Object module = Class.class.getMethod("getModule").invoke(cls);
            if (!((Boolean) moduleCls.getMethod("isNamed").invoke(module))) return;
            Object unnamed = ClassLoader.class.getMethod("getUnnamedModule")
                    .invoke(Agent.class.getClassLoader());
            Set<String> pkgs = (Set<String>) moduleCls.getMethod("getPackages").invoke(module);
            Set<Object> toUs = Collections.singleton(unnamed);
            Map<String, Set<Object>> extraOpens = new LinkedHashMap<String, Set<Object>>();
            for (String pkg : pkgs) extraOpens.put(pkg, toUs);
            Instrumentation.class
                    .getMethod("redefineModule", moduleCls, Set.class, Map.class,
                            Map.class, Set.class, Map.class)
                    .invoke(inst, module, Collections.emptySet(), Collections.emptyMap(),
                            extraOpens, Collections.emptySet(), Collections.emptyMap());
        } catch (Throwable ignored) {
            // Java 8 (sin módulos), módulo no modificable o ya abierto: se sigue
        }
    }

    static long pid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        try { return Long.parseLong(at > 0 ? name.substring(0, at) : name); }
        catch (NumberFormatException e) { return -1; }
    }

    static String iso8601(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Conexión con escritura serializada; una línea = un mensaje (NDJSON). */
    static final class Conn {
        final Socket socket;
        final Writer out;

        Conn(Socket s) throws IOException {
            this.socket = s;
            this.out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
        }

        synchronized void send(String line) throws IOException {
            out.write(line);
            out.write('\n');
            out.flush();
        }

        void sendQuiet(String line) {
            try { send(line); } catch (IOException ignored) {}
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /** Parser/escritor JSON mínimo: suficiente para el protocolo, sin dependencias. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json j = new Json(text);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i < j.s.length()) throw new IllegalArgumentException("json sobrante en pos " + j.i);
            return v;
        }

        private Object value() {
            char c = peek();
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') { expect("true"); return Boolean.TRUE; }
            if (c == 'f') { expect("false"); return Boolean.FALSE; }
            if (c == 'n') { expect("null"); return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++; ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                String k = string();
                ws(); if (peek() != ':') throw bad(); i++; ws();
                m.put(k, value());
                ws();
                char c = peek();
                if (c == ',') { i++; ws(); continue; }
                if (c == '}') { i++; return m; }
                throw bad();
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<Object>();
            i++; ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                char c = peek();
                if (c == ',') { i++; ws(); continue; }
                if (c == ']') { i++; return l; }
                throw bad();
            }
        }

        private String string() {
            if (peek() != '"') throw bad();
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw bad();
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            if (n.isEmpty()) throw bad();
            if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
                try { return Long.parseLong(n); } catch (NumberFormatException ignored) {}
            }
            return Double.parseDouble(n);
        }

        private void expect(String word) {
            if (!s.startsWith(word, i)) throw bad();
            i += word.length();
        }

        private char peek() {
            if (i >= s.length()) throw bad();
            return s.charAt(i);
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private IllegalArgumentException bad() {
            return new IllegalArgumentException("json inválido en pos " + i);
        }

        static String write(Object v) {
            StringBuilder sb = new StringBuilder();
            writeTo(sb, v);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void writeTo(StringBuilder sb, Object v) {
            if (v == null) { sb.append("null"); return; }
            if (v instanceof String) { writeString(sb, (String) v); return; }
            if (v instanceof Number || v instanceof Boolean) { sb.append(v); return; }
            if (v instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeString(sb, e.getKey());
                    sb.append(':');
                    writeTo(sb, e.getValue());
                }
                sb.append('}');
                return;
            }
            if (v instanceof List) {
                sb.append('[');
                boolean first = true;
                for (Object o : (List<Object>) v) {
                    if (!first) sb.append(',');
                    first = false;
                    writeTo(sb, o);
                }
                sb.append(']');
                return;
            }
            writeString(sb, String.valueOf(v));
        }

        private static void writeString(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"');
        }
    }

    private Agent() {}
}
