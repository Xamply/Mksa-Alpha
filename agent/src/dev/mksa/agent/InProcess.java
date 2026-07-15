package dev.mksa.agent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Motor de mutación in-process (corte 1, proyecto §3.5) — el de-risk del end-goal.
 *
 * A diferencia del camino de relaunch (cortes 1–4b), que muta el mundo PARCHEANDO la
 * (de)serialización sin handle vivo, esta ruta hace lo que el relaunch evitaba por
 * diseño: <b>retiene un handle vivo</b> a {@code MinecraftServer}/{@code ServerLevel}
 * y <b>marshaliza al hilo del servidor</b> para mutar el mundo EN CALIENTE, sin
 * reiniciar el proceso.
 *
 * Alcance del corte 1: una mutación trivial, derivada del mundo y completamente
 * reversible — coloca {@code minecraft:stone} en una celda de AIRE sobre el spawn,
 * la relee en vivo y restaura el estado original, verificando ambos extremos. No es
 * aún el teardown Tier 1 (filtrado de registro + barrido en vivo del footprint +
 * reload de datapack); es la prueba de que las dos capacidades nuevas funcionan.
 *
 * Descubrimiento 100% estructural, sin números de método ni —salvo dos nombres de
 * clase ya estables en el transformer (BlockPos/BlockState)— nombres por versión:
 *  · El servidor es el campo del cliente cuyo valor es a la vez {@link Executor} y
 *    {@link Runnable} (MinecraftServer lo es; el cliente Minecraft, no — distingue
 *    sin nombres). Su propio {@code execute(Runnable)} es el canal de marshaling.
 *  · El {@code ServerLevel} es el objeto que un método no-arg del servidor devuelve y
 *    que expone {@code setBlock(BlockPos,BlockState,int):boolean} y
 *    {@code getBlockState(BlockPos):BlockState} — descubierto EN el hilo del servidor.
 *
 * Cero cambios en el transformer: nada de bytecode nuevo. Lado-agente puro (CL del
 * sistema), reusa {@link Wcg#liveRegistry} y {@link Agent#openModule}.
 */
public final class InProcess {

    public static final InProcess INSTANCE = new InProcess();

    private InProcess() {}

    /** Nombres de clase estables (los mismos que usa el transformer). */
    private static final String[] BLOCK_STATE_NAMES = {
            "net.minecraft.class_2680",
            "net.minecraft.world.level.block.state.BlockState",
    };
    private static final String[] BLOCK_POS_NAMES = {
            "net.minecraft.class_2338",
            "net.minecraft.core.BlockPos",
    };

    /** Flags de setBlock: UPDATE_ALL (BLOCK | CLIENTS) — comportamiento vanilla normal. */
    private static final int UPDATE_ALL = 3;
    /** Offsets de Y sobre el spawn donde buscar aire (orden = preferencia). */
    private static final int[] AIR_OFFSETS = { 64, 80, 96, 112, 48, 32 };
    private static final int MAX_Y = 312;   // tope conservador de altura del overworld 1.18+

    // ---- handles vivos, cacheados tras el primer descubrimiento ----
    private volatile Object server;              // MinecraftServer (Executor & Runnable)
    private volatile List<Object> levels;        // TODOS los ServerLevel (todas las dimensiones)
    private volatile Method mSetBlock;           // setBlock(BlockPos, BlockState, int): boolean
    private volatile Method mGetState;           // getBlockState(BlockPos): BlockState
    private volatile Method mAsLong;             // BlockPos.asLong(): long
    private volatile Constructor<?> posCtor;     // BlockPos(int,int,int)
    private volatile Method mDefaultState;       // Block.defaultBlockState(): BlockState
    private volatile Method mBlockOfState;       // BlockState.getBlock(): Block
    private volatile Object blockRegistry;       // Registry<Block> vivo
    private volatile Method blockGetId;          // getId(Block): Identifier
    private volatile Object stoneBlock;
    private volatile Object airBlock;
    private volatile String anchorClass;         // clase del GlobalPos del que salió el ancla (diag)

    // ---- instrumentación pasiva del fallback de niveles (tier1-cleanup) ----
    // El ancla del world spawn cuelga del servidor en 1.21.x; los niveles son fallback.
    // Si el ancla sale alguna vez del fallback (no del server), queremos saberlo: significa
    // que MC cambió la forma del campo o que algún mod añadió un GlobalPos record-shape en
    // un ServerLevel. Cuando este contador siga 0 tras varios cortes verdes de Tier 1,
    // spawnDiag y el fallback son candidatos a borrar juntos.
    private volatile long levelFallbackTriggered;
    private volatile String firstFallbackDim;     // nivel donde cayó el primer fallback
    private volatile boolean fallbackLoggedOnce;

    /**
     * Ejecuta el ciclo reversible y devuelve el veredicto. {@code {ok:true, …}} con el
     * detalle del ciclo, o {@code {ok:false, code, error}} (code = NOT_READY si el
     * servidor aún no existe, INTERNAL en cualquier otro fallo).
     */
    public Map<String, Object> probe() {
        Object client = Boot.clientInstance;
        if (client == null) {
            return fail("NOT_READY", "la instancia del cliente aún no existe");
        }
        try {
            ensureRegistry();
            if (stoneBlock == null || airBlock == null) {
                return fail("NOT_READY", "el registro de bloques aún no está listo");
            }
        } catch (Throwable t) {
            return fail("INTERNAL", "registro: " + t);
        }
        List<Object> candidates = serverCandidates(client);
        if (candidates.isEmpty()) {
            return fail("NOT_READY", "no hay servidor integrado vivo (mundo no cargado)");
        }
        // Probar cada candidato en SU hilo: el que halle un ServerLevel es el servidor.
        for (Object cand : candidates) {
            final Object candidate = cand;
            final Map<String, Object> holder = new HashMap<String, Object>();
            final CountDownLatch latch = new CountDownLatch(1);
            try {
                ((Executor) candidate).execute(new Runnable() {
                    public void run() {
                        try {
                            attempt(candidate, holder);
                        } catch (Throwable t) {
                            holder.put("error", String.valueOf(t));
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            } catch (Throwable t) {
                // executor caído o que rechaza tareas: probamos el siguiente candidato
                continue;
            }
            boolean done;
            try {
                done = latch.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return fail("INTERNAL", "interrumpido esperando al hilo del servidor");
            }
            if (!done) {
                return fail("INTERNAL", "el hilo del servidor no respondió en 15s");
            }
            if (Boolean.TRUE.equals(holder.get("ok"))) {
                return holder;   // este candidato era el servidor y completó el ciclo
            }
            // Candidato correcto (halló level) pero con un veredicto explícito: lo
            // propagamos tal cual — NOT_READY (transitorio, p. ej. jugador aún sin
            // entrar) o INTERNAL (error duro). El harness reintenta solo en NOT_READY.
            if (holder.get("code") instanceof String) {
                return fail((String) holder.get("code"), String.valueOf(holder.get("error")));
            }
            if (holder.containsKey("error") && server == candidate) {
                return fail("INTERNAL", String.valueOf(holder.get("error")));
            }
            // Sin level y sin veredicto: no era el servidor → siguiente candidato.
        }
        return fail("NOT_READY", "ningún executor del cliente expuso un ServerLevel");
    }

    /**
     * Corre EN el hilo del candidato. Localiza el ServerLevel; si no hay, retorna sin
     * tocar nada (no era el servidor). Si lo hay, hace el ciclo reversible y llena el
     * holder con el veredicto.
     */
    private void attempt(Object candidate, Map<String, Object> holder) throws Exception {
        List<Object> lvls = ensureLevels(candidate);
        if (lvls == null || lvls.isEmpty()) return;   // este executor no gobierna un mundo
        server = candidate;
        ensureLevelMethods(lvls.get(0));

        Object stoneState = mDefaultState.invoke(stoneBlock);
        Object airState = mDefaultState.invoke(airBlock);

        // Ancla derivada del mundo, SIN jugador: el world spawn. El dump estructural mostró
        // que en 1.21.x el GlobalPos del spawn NO cuelga del ServerLevel sino de la WorldData
        // del MinecraftServer (alcanzable solo desde el servidor: class_1132.field_5518 → …
        // → GlobalPos, paralelo al mapa de niveles field_4589). Por eso buscamos PRIMERO desde
        // el servidor; los niveles quedan de fallback. El path se recorre por campos planos
        // (§3.5 corte 1); el GlobalPos es un record de forma (dimension, pos) y skip-ZERO
        // descarta los sentinels BlockPos.ZERO. null en todos = spawn aún sin fijar:
        // TRANSITORIO → NOT_READY.
        Object anchorPos = null;
        String anchorSrc = "spawn";
        java.util.List<Object> searchRoots = new ArrayList<Object>();
        if (server != null) searchRoots.add(server);
        searchRoots.addAll(lvls);
        int hitIndex = -1;
        for (int i = 0; i < searchRoots.size(); i++) {
            Object r = searchRoots.get(i);
            Object sp = spawnPos(r);
            if (sp != null) { anchorPos = sp; hitIndex = i; break; }
        }
        // Si el ancla salió de un fallback de niveles (no del server, índice 0), señalarlo:
        // instrumentación pasiva. Resuelve la dimensión del nivel best-effort para diagnosticar
        // cuál nivel cubrió el caso (mod custom? dimensión vainilla?).
        if (anchorPos != null && server != null && hitIndex > 0) {
            levelFallbackTriggered++;
            Object lvlHit = searchRoots.get(hitIndex);
            String dim = resolveDimensionName(lvlHit);
            if (dim == null) dim = lvlHit.getClass().getSimpleName() + "@" + (hitIndex - 1);
            if (firstFallbackDim == null) firstFallbackDim = dim;
            if (!fallbackLoggedOnce) {
                fallbackLoggedOnce = true;
                System.err.println("[mksa] InProcess: ancla del spawn salió del fallback de niveles (dim=" + dim
                        + "). Si esto persiste, revisar la ruta principal (server-first) antes de retirar spawnDiag.");
            }
        }
        if (anchorPos == null) {
            // DIAGNÓSTICO (scaffolding temporal): estamos en el hilo del server con un
            // ServerLevel real y el spawn de un mundo existente ya está en level.dat → si
            // no lo hallamos es estructural, no timing. Volcamos los BlockPos-holders
            // alcanzables (clase + coords + nombres de campo, solo para entender la forma).
            java.util.List<Object> roots = new ArrayList<Object>(lvls);
            if (server != null) roots.add(0, server);
            holder.put("code", "INTERNAL");
            holder.put("error", spawnDiag(roots));
            return;
        }
        long sl = (Long) mAsLong.invoke(anchorPos);
        int sx = (int) (sl >> 38);
        int sy = (int) ((sl << 52) >> 52);
        int sz = (int) ((sl << 26) >> 38);

        // Subir hasta encontrar AIRE sobre la columna del ancla: disuelve toda suposición de
        // contenido (superficie, océano, estructura) y elimina el único caso no-reversible
        // (block entities, que el aire nunca tiene). Como el ancla salió del servidor y no de
        // un nivel concreto, probamos cada ServerLevel y nos quedamos con el primero que
        // exponga aire en esa columna — será el overworld, cuya columna de spawn está
        // force-loaded; no se llega a tocar el resto.
        Object lvl = null;
        Object pos = null;
        Object original = null;
        int ty = sy;
        for (Object L : lvls) {
            for (int off : AIR_OFFSETS) {
                int y = sy + off;
                if (y >= MAX_Y) continue;
                Object p = posCtor.newInstance(sx, y, sz);
                Object st = mGetState.invoke(L, p);
                if (st == airState) { lvl = L; pos = p; original = st; ty = y; break; }
            }
            if (pos != null) break;
        }
        if (pos == null) {
            holder.put("error", "no se halló una celda de aire sobre el ancla en ningún nivel");
            return;
        }

        // Ciclo reversible: leer (=aire) → stone → verificar → restaurar → verificar.
        mSetBlock.invoke(lvl, pos, stoneState, UPDATE_ALL);
        Object afterSet = mGetState.invoke(lvl, pos);
        mSetBlock.invoke(lvl, pos, original, UPDATE_ALL);
        Object afterRestore = mGetState.invoke(lvl, pos);

        String setName = blockName(stoneState);
        String afterSetName = blockName(afterSet);
        boolean matched = "minecraft:stone".equals(afterSetName);
        boolean restored = afterRestore == original;

        String thread = Thread.currentThread().getName();
        holder.put("ok", Boolean.TRUE);
        holder.put("anchor", anchorClass == null ? anchorSrc : anchorSrc + ":" + anchorClass);
        holder.put("rawSpawn", Arrays.asList(sx, sy, sz));
        holder.put("pos", Arrays.asList(sx, ty, sz));
        holder.put("original", blockName(original));
        holder.put("set", setName);
        holder.put("afterSet", afterSetName);
        holder.put("afterRestore", blockName(afterRestore));
        holder.put("matched", matched);
        holder.put("restored", restored);
        holder.put("onServerThread", thread != null && thread.toLowerCase().contains("server"));
        holder.put("thread", thread);
    }

    // ====================== descubrimiento del servidor ======================

    /**
     * Candidatos a servidor entre los campos del cliente: valores que son a la vez
     * {@link Executor} y {@link Runnable} (MinecraftServer lo es; el cliente no) van
     * primero; el resto de Executors quedan como fallback. Excluye al propio cliente.
     */
    private List<Object> serverCandidates(Object client) {
        List<Object> strong = new ArrayList<Object>();
        List<Object> weak = new ArrayList<Object>();
        try {
            Agent.openModule(Agent.instrumentation, client.getClass());
        } catch (Throwable ignored) {
            // Java 8 / módulo no abrible: setAccessible bastará en Fabric
        }
        for (Class<?> c = client.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(client);
                } catch (Throwable t) {
                    continue;
                }
                if (v == null || v == client) continue;
                if (!(v instanceof Executor)) continue;
                if (v instanceof Runnable) {
                    if (!contains(strong, v)) strong.add(v);
                } else {
                    if (!contains(weak, v)) weak.add(v);
                }
            }
        }
        strong.addAll(weak);
        return strong;
    }

    private static boolean contains(List<Object> l, Object v) {
        for (Object o : l) if (o == v) return true;
        return false;
    }

    /** Cota de elementos a inspeccionar por Iterable, para no recorrer colecciones grandes. */
    private static final int ITERABLE_SCAN_CAP = 256;

    /**
     * TODOS los ServerLevel de un servidor (cacheados): los objetos que devuelven sus
     * métodos no-arg —directamente o como elementos de un Iterable (p. ej. getAllLevels)—
     * y que exponen setBlock(BlockPos,BlockState,int) y getBlockState(BlockPos). Enumerar
     * todas las dimensiones es lo que permite hallar al jugador esté donde esté (corregido
     * tras el bug de cachear una sola dimensión). null/vacío = este executor no es servidor.
     * Corre en el hilo del servidor, donde invocar sus getters es seguro.
     */
    private List<Object> ensureLevels(Object srv) {
        if (server == srv && levels != null) return levels;
        try {
            Agent.openModule(Agent.instrumentation, srv.getClass());
        } catch (Throwable ignored) {
        }
        List<Object> out = new ArrayList<Object>();
        for (Method m : srv.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            Class<?> ret = m.getReturnType();
            if (ret.isPrimitive() || ret == void.class || ret == String.class) continue;
            Object v;
            try {
                m.setAccessible(true);
                v = m.invoke(srv);
            } catch (Throwable t) {
                continue;
            }
            if (v == null) continue;
            if (isServerLevel(v)) {
                if (!contains(out, v)) out.add(v);
            } else if (v instanceof Iterable) {
                int n = 0;
                for (Object e : (Iterable<?>) v) {
                    if (++n > ITERABLE_SCAN_CAP) break;
                    if (e != null && isServerLevel(e) && !contains(out, e)) out.add(e);
                }
            }
        }
        if (out.isEmpty()) return null;
        levels = out;
        return out;
    }

    /** ¿El objeto tiene setBlock(BlockPos,BlockState,int) y getBlockState(BlockPos)? */
    private boolean isServerLevel(Object lvl) {
        return findSetBlock(lvl.getClass()) != null && findGetState(lvl.getClass()) != null;
    }

    // ================= ancla derivada del mundo (world spawn) =================

    /** Profundidad máxima del deref Server → … → SpawnRecord → GlobalPos. */
    private static final int SPAWN_DEREF_DEPTH = 14;

    /**
     * BlockPos del world spawn: deref del path Server → … → SpawnRecord → GlobalPos →
     * BlockPos. El dump estructural confirmó que el GlobalPos del spawn cuelga del SERVIDOR
     * por CAMPOS PLANOS, mientras los sentinels {@code BlockPos.ZERO} viven todos detrás de
     * colecciones — así que NO descender a colecciones ya los excluye; el skip-ZERO de
     * {@link #derefSpawn} es solo red de seguridad. El GlobalPos es un record de forma (todo
     * {@code final}, un BlockPos + la dimensión); se lee su campo BlockPos sin nombres. Solo
     * lecturas de campo (cero efectos). El identitySet es guarda de ciclos. null = sin spawn.
     */
    private Object spawnPos(Object root) {
        return derefSpawn(root, SPAWN_DEREF_DEPTH,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>()));
    }

    private Object derefSpawn(Object node, int depth, java.util.Set<Object> seen) {
        if (node == null || depth < 0 || !seen.add(node)) return null;
        try { Agent.openModule(Agent.instrumentation, node.getClass()); } catch (Throwable ignored) {}
        // ¿este nodo ES el GlobalPos? = record de forma (dimension, pos): TODOS los campos
        // de instancia final y exactamente uno de tipo BlockPos. La exigencia de "todo
        // final" descarta los holders de sentinels (managers mutables con un BlockPos.ZERO
        // suelto), que era lo que el deref agarraba antes de llegar al SpawnRecord.
        if (looksLikeGlobalPos(node.getClass())) {
            Object pos = readBlockPos(node);
            if (pos != null && !isZeroPos(pos)) { anchorClass = node.getClass().getName(); return pos; }
        }
        // no lo es: baja por sus campos-objeto (sin colecciones ni java.*), un nivel más.
        for (Class<?> k = node.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.isArray()) continue;
                Object child;
                try { f.setAccessible(true); child = f.get(node); }
                catch (Throwable t) { continue; }
                child = unwrapOptional(child);
                if (!descendable(child)) continue;
                Object found = derefSpawn(child, depth - 1, seen);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Resuelve el nombre de la dimensión de un ServerLevel sin nombres por versión:
     * busca un método no-arg cuyo resultado se parezca a un ResourceKey (toString
     * con forma "ResourceKey[…]"). Best-effort; null si no resuelve. Solo para el
     * diagnóstico del fallback de niveles, no en la ruta crítica.
     */
    private String resolveDimensionName(Object level) {
        if (level == null) return null;
        try { Agent.openModule(Agent.instrumentation, level.getClass()); } catch (Throwable ignored) {}
        for (Method m : level.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            Class<?> ret = m.getReturnType();
            if (ret.isPrimitive() || ret == void.class || ret == String.class) continue;
            try {
                m.setAccessible(true);
                Object v = m.invoke(level);
                if (v == null) continue;
                String s = v.toString();
                if (s != null && s.startsWith("ResourceKey[") && s.endsWith("]")) return s;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ===================== DIAGNÓSTICO (scaffolding temporal) =====================
    // TODO(tier1-cleanup): retirar spawnDiag tras 1-2 cortes verdes con la ruta
    // principal (server-first) si levelFallbackTriggered == 0 en todos ellos.
    // El diagnóstico demostró su utilidad localizando el ancla en el server
    // (WorldData) cuando la búsqueda principal aún miraba en los niveles, pero ya
    // no aporta señal una vez confirmada esa ruta. Su existencia hoy es solo de
    // seguridad ante una regresión durante la maduración del barrido in-process.

    /** Vuelca los BlockPos-holders alcanzables desde los roots, para ver la forma real. */
    private String spawnDiag(java.util.List<Object> roots) {
        java.util.List<String> hits = new ArrayList<String>();
        java.util.Set<Object> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        for (Object r : roots) {
            try { diagScan(r, 12, seen, hits, r.getClass().getSimpleName()); }
            catch (Throwable ignored) {}
        }
        StringBuilder sb = new StringBuilder("DIAG sin GlobalPos record-shape; BlockPos-holders (clase [x,y,z] fields/final @ path):");
        int n = 0;
        for (String h : hits) {
            sb.append("\n  ").append(h);
            if (++n >= 40) { sb.append("\n  …(+).."); break; }
        }
        if (hits.isEmpty()) sb.append("\n  (ninguno — el BlockPos del spawn no es alcanzable por este traversal)");
        return sb.toString();
    }

    private void diagScan(Object node, int depth, java.util.Set<Object> seen,
                          java.util.List<String> hits, String path) {
        if (node == null || depth < 0 || !seen.add(node) || hits.size() >= 60) return;
        Class<?> c = node.getClass();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!isType(f.getType(), BLOCK_POS_NAMES)) continue;
                try {
                    f.setAccessible(true);
                    Object p = f.get(node);
                    if (p == null) continue;
                    long L = (Long) mAsLong.invoke(p);
                    int x = (int) (L >> 38), y = (int) ((L << 52) >> 52), z = (int) ((L << 26) >> 38);
                    int tot = 0, fin = 0;
                    for (Field g : c.getDeclaredFields()) {
                        if (Modifier.isStatic(g.getModifiers())) continue;
                        tot++;
                        if (Modifier.isFinal(g.getModifiers())) fin++;
                    }
                    hits.add(c.getName() + " [" + x + "," + y + "," + z + "] " + tot + "/" + fin
                            + " @ " + path + "." + f.getName());
                } catch (Throwable ignored) {}
            }
        }
        if (depth == 0) return;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().isPrimitive()) continue;
                Object v;
                try { f.setAccessible(true); v = f.get(node); }
                catch (Throwable t) { continue; }
                v = unwrapOptional(v);
                if (v == null) continue;
                String np = path + "." + f.getName();
                if (v instanceof java.util.Collection) {
                    int i = 0;
                    for (Object e : (java.util.Collection<?>) v) { if (i++ >= 32) break; diagScan(e, depth - 1, seen, hits, np + "[]"); }
                } else if (v instanceof java.util.Map) {
                    int i = 0;
                    for (Object e : ((java.util.Map<?, ?>) v).values()) { if (i++ >= 32) break; diagScan(e, depth - 1, seen, hits, np + "{}"); }
                } else if (!v.getClass().isArray()) {
                    String nm = v.getClass().getName();
                    if (!(nm.startsWith("java.") || nm.startsWith("jdk.")
                            || nm.startsWith("sun.") || nm.startsWith("javax.")))
                        diagScan(v, depth - 1, seen, hits, np);
                }
            }
        }
    }

    /** Optional → su contenido (o null); cualquier otra cosa, tal cual. */
    private static Object unwrapOptional(Object o) {
        if (o instanceof java.util.Optional) {
            java.util.Optional<?> opt = (java.util.Optional<?>) o;
            return opt.isPresent() ? opt.get() : null;
        }
        return o;
    }

    /**
     * ¿La clase tiene la forma de {@code GlobalPos} —record {@code (dimension, pos)}—?
     * Sin la API de records (release 8): todos los campos de instancia {@code final},
     * exactamente uno de tipo BlockPos, y al menos otra referencia (la dimensión), con
     * pocos campos en total. Esto es lo que separa un record de un manager mutable que
     * casualmente porta un {@code BlockPos.ZERO}.
     */
    private static boolean looksLikeGlobalPos(Class<?> c) {
        int total = 0, blockPos = 0, otherRef = 0;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!Modifier.isFinal(f.getModifiers())) return false;   // record ⇒ todo final
                total++;
                Class<?> ft = f.getType();
                if (isType(ft, BLOCK_POS_NAMES)) blockPos++;
                else if (!ft.isPrimitive()) otherRef++;
            }
        }
        return blockPos == 1 && otherRef >= 1 && total <= 3;
    }

    /** ¿Es el BlockPos.ZERO (sentinel "sin fijar")? Un spawn de overworld nunca es (0,0,0). */
    private boolean isZeroPos(Object pos) {
        try { return ((Long) mAsLong.invoke(pos)).longValue() == 0L; }
        catch (Throwable t) { return false; }
    }

    /** Lee el primer campo de tipo BlockPos de un nodo con forma de GlobalPos. */
    private Object readBlockPos(Object node) {
        for (Class<?> k = node.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!isType(f.getType(), BLOCK_POS_NAMES)) continue;
                try {
                    f.setAccessible(true);
                    Object pos = f.get(node);
                    if (pos != null) return pos;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** ¿Vale la pena bajar a este objeto? No-null, no colección/array, no {@code java.*}. */
    private static boolean descendable(Object o) {
        if (o == null) return false;
        Class<?> c = o.getClass();
        if (c.isArray()) return false;
        if (o instanceof java.util.Collection) return false;
        if (o instanceof java.util.Map) return false;
        if (o instanceof Iterable) return false;
        String n = c.getName();
        return !(n.startsWith("java.") || n.startsWith("javax.")
                || n.startsWith("sun.") || n.startsWith("jdk."));
    }

    // ====================== resolución de métodos del mundo ======================

    private void ensureLevelMethods(Object lvl) throws Exception {
        if (mSetBlock == null) {
            Method m = findSetBlock(lvl.getClass());
            if (m == null) throw new NoSuchMethodException("setBlock(BlockPos,BlockState,int)");
            m.setAccessible(true);
            mSetBlock = m;
        }
        if (mGetState == null) {
            Method m = findGetState(lvl.getClass());
            if (m == null) throw new NoSuchMethodException("getBlockState(BlockPos)");
            m.setAccessible(true);
            mGetState = m;
        }
        if (posCtor == null || mAsLong == null) {
            // La clase de BlockPos sale del 1er parámetro de setBlock (siempre presente).
            Class<?> posClass = mSetBlock.getParameterTypes()[0];
            Agent.openModule(Agent.instrumentation, posClass);
            if (posCtor == null) {
                for (Constructor<?> ctor : posClass.getDeclaredConstructors()) {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 3 && p[0] == int.class && p[1] == int.class && p[2] == int.class) {
                        ctor.setAccessible(true);
                        posCtor = ctor;
                        break;
                    }
                }
                if (posCtor == null) throw new NoSuchMethodException("BlockPos(int,int,int)");
            }
            if (mAsLong == null) {
                for (Method m : posClass.getMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    if (m.getReturnType() != long.class) continue;
                    m.setAccessible(true);
                    mAsLong = m;
                    break;
                }
                if (mAsLong == null) throw new NoSuchMethodException("BlockPos.asLong()");
            }
        }
        if (mDefaultState == null || mBlockOfState == null) {
            resolveStateMethods();   // valida el par contra el id conocido minecraft:stone
        }
    }

    /**
     * Resuelve {@code Block.defaultBlockState()} y {@code BlockState.getBlock()} JUNTOS,
     * validando el par con un round-trip contra el id conocido: el método-default elegido,
     * aplicado a {@code stoneBlock}, debe producir una state cuyo getBlock resuelva a
     * {@code minecraft:stone}. Confiar en "el primer método no-arg → BlockState" fallaba:
     * si ese método devolvía la state de otro bloque (p. ej. air), TODA lectura colapsaba
     * a air. La validación contra el id elimina esa ambigüedad sin nombres por versión.
     */
    private void resolveStateMethods() throws Exception {
        Agent.openModule(Agent.instrumentation, stoneBlock.getClass());
        for (Method dm : stoneBlock.getClass().getMethods()) {
            if (Modifier.isStatic(dm.getModifiers())) continue;
            if (dm.getParameterTypes().length != 0) continue;
            if (!isType(dm.getReturnType(), BLOCK_STATE_NAMES)) continue;
            Object state;
            try { dm.setAccessible(true); state = dm.invoke(stoneBlock); }
            catch (Throwable t) { continue; }
            if (state == null) continue;
            Method gm = findBlockOfMappingTo(state, "minecraft:stone");
            if (gm != null) { mDefaultState = dm; mBlockOfState = gm; return; }
        }
        throw new NoSuchMethodException("defaultBlockState()+getBlock() con round-trip a minecraft:stone");
    }

    /** Método no-arg de la state cuyo resultado es un bloque registrado con id {@code wantId}. */
    private Method findBlockOfMappingTo(Object state, String wantId) {
        Agent.openModule(Agent.instrumentation, state.getClass());
        for (Method gm : state.getClass().getMethods()) {
            if (Modifier.isStatic(gm.getModifiers())) continue;
            if (gm.getParameterTypes().length != 0) continue;
            Class<?> r = gm.getReturnType();
            if (r.isPrimitive() || r == String.class || r == void.class) continue;
            try {
                gm.setAccessible(true);
                Object block = gm.invoke(state);
                if (block == null) continue;
                Object id = blockGetId.invoke(blockRegistry, block);
                if (id != null && wantId.equals(id.toString())) return gm;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** setBlock(BlockPos, BlockState, int): boolean — por forma. */
    private static Method findSetBlock(Class<?> cls) {
        for (Method m : cls.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 3) continue;
            if (!isType(p[0], BLOCK_POS_NAMES)) continue;
            if (!isType(p[1], BLOCK_STATE_NAMES)) continue;
            if (p[2] != int.class) continue;
            if (m.getReturnType() != boolean.class) continue;
            return m;
        }
        return null;
    }

    /** getBlockState(BlockPos): BlockState — por forma. */
    private static Method findGetState(Class<?> cls) {
        for (Method m : cls.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1) continue;
            if (!isType(p[0], BLOCK_POS_NAMES)) continue;
            if (!isType(m.getReturnType(), BLOCK_STATE_NAMES)) continue;
            return m;
        }
        return null;
    }


    // ====================== registro de bloques ======================

    /** Localiza el registro de bloques y los bloques minecraft:stone y minecraft:air. */
    private void ensureRegistry() throws Exception {
        if (blockRegistry != null && stoneBlock != null && airBlock != null) return;
        Object[] reg = Wcg.liveRegistry(Agent.instrumentation, "minecraft:block");
        if (reg == null) return;
        Object registry = reg[0];
        Method getId = (Method) reg[1];
        Object stone = null, air = null;
        for (Object block : (Iterable<?>) registry) {
            Object idObj;
            try { idObj = getId.invoke(registry, block); } catch (Throwable t) { continue; }
            if (idObj == null) continue;
            String id = idObj.toString();
            if ("minecraft:stone".equals(id)) stone = block;
            else if ("minecraft:air".equals(id)) air = block;
            if (stone != null && air != null) break;
        }
        blockRegistry = registry;
        blockGetId = getId;
        stoneBlock = stone;
        airBlock = air;
    }

    /** id del bloque de un BlockState ("minecraft:stone"), o "?" si no se resuelve. */
    private String blockName(Object state) {
        try {
            Object block = mBlockOfState.invoke(state);
            Object id = blockGetId.invoke(blockRegistry, block);
            return id == null ? "?" : id.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ====================== Tier 1 corte 1: barrido vivo + captura simétrica ======================

    /** Bounds Y conservadores del overworld 1.18+ para el barrido por chunk. */
    private static final int CHUNK_Y_MIN = -64;
    private static final int CHUNK_Y_MAX = 320;

    // ====== Tier 1 corte 2: captura quirúrgica zero-IO ======
    // Reemplaza el binomio (force-dirty + saveAllChunks) del corte 1 por una llamada
    // directa a SerializableChunkData.copyOf(level, chunk).write() por cada LevelChunk
    // cargado del view distance. El write-hook ya inyectado del ledger dispara
    // naturalmente sobre el CompoundTag construido, sin tocar disco.
    private volatile Class<?> scdClass;            // SerializableChunkData (class_2852)
    private volatile Method mScdCopyOf;            // static SCD copyOf(ServerLevel, LevelChunk)
    private volatile Method mScdWrite;             // instance: SCD.write() → CompoundTag
    private volatile Class<?> levelChunkClass;     // class_2818 / LevelChunk ESTRICTO (no el ChunkAccess param[1] de copyOf)
    private volatile Method mGetChunkSource;       // ServerLevel.getChunkSource() → ChunkSource
    private volatile Method mGetChunkNow;          // ChunkSource.getChunkNow(int, int) → LevelChunk?

    /**
     * Barrido in-process del footprint de la víctima {@code ns}, con captura simétrica
     * al corte 3 del relaunch al archivo de restauración {@code restorePath}. Estrena
     * el ledger como mecanismo de mutación in-process (Tier 1, corte 1).
     *
     * Flujo (en el hilo del servidor, vía marshaling):
     *   1. {@code Ledger.sweepArm(ns, restorePath)} — armar la captura (corte 3).
     *   2. {@code MinecraftServer.saveAllChunks(true,true,false)} — disparar el hook
     *      ya inyectado en {@code SerializableChunkData.write()}. La captura corre por
     *      la ruta probada del corte 3; el {@code scan()} simultáneo puebla {@code byChunk}
     *      íntegro (cubre chunks recién generados sin save previo).
     *   3. {@code Ledger.disarm()} — defensivo, evita una segunda {@code arm()} que
     *      invalidaría el dedup del {@code RestoreWriter}.
     *   4. Para cada {@code (cx,cz)} de {@code Ledger.chunksWithNs(ns)}: iterar el
     *      cuboide del chunk (-64..320), leer {@code getBlockState(pos)}, casar por
     *      {@code Ledger.namespaceOf(state)}, y si coincide con {@code ns}: en
     *      {@code dryRun} solo contar; si no, {@code setBlock(pos, AIR, UPDATE_ALL)}.
     *   5. Re-escanear las mismas posiciones para validar {@code residualBlocks == 0}.
     *
     * <b>Tier 1 corte 2 (junio 2026):</b> el binomio (force-dirty + saveAllChunks) del
     * corte 1 fue sustituido por {@code SerializableChunkData.copyOf(level, chunk).write()}
     * invocado directamente sobre los {@code LevelChunk} cargados del view distance,
     * vía {@code ChunkSource.getChunkNow(cx, cz)}. El write-hook ya inyectado del ledger
     * dispara naturalmente sobre el CompoundTag construido en memoria — sin tocar disco,
     * sin reserializar level.dat ni playerdata, sin tick lag. Pieza 2 (agregado byChunk
     * in-vivo desde {@code onSetBlock}) hace que {@code wcg.counts} sea honesto en vivo
     * y elimina la deuda {@code aggregateStale=true} del corte 1.
     *
     * <b>Limitación de víctima (corte 1+2):</b> {@code mcwstairs} sin block entity ni
     * scheduled ticks → {@code setBlock(AIR, 3)} es seguro. Para Tier 1 general
     * (corte 3): inventarios de BE se perderían silenciosamente al barrer, scheduled
     * ticks huérfanos pueden lanzar sobre {@code air}. {@code TODO(tier1-corte3)}.
     *
     * <b>Frontera de verificación:</b> in-vivo (sweptBlocks, residualBlocks). NO audita
     * disco — esa es la frontera del corte 4 del relaunch ya probada. Con corte 2,
     * {@code aggregateStale} se reporta {@code false}: {@code byChunk} se mantiene
     * incrementalmente desde {@code onSetBlock} y {@code wcg.counts} es honesto en vivo.
     */
    public Map<String, Object> tier1Sweep(final String ns, final String restorePath, final boolean dryRun) {
        return tier1Sweep(ns, restorePath, dryRun, null);
    }

    /**
     * Overload con override explícito de la lista de chunks a visitar. Introducido
     * en Tier 1 corte 5 (junio 2026) tras el bug intermitente del envoltorio
     * transaccional: 3 fallos de 6 corridas donde el sweep terminaba en ~27-36ms
     * sin captura (vs ~1200-1500ms en runs OK). Causa raíz: race entre el snapshot
     * (en hilo IPC) y el sweep (en server thread). Entre encolar el Runnable del
     * sweep y su ejecución pueden pasar 0–N ticks, durante los cuales un autosave
     * de MC dispara {@link Ledger#onChunkWrite} → {@code scan()} → {@code byChunk.put}
     * reemplaza la entrada del chunk. Si esa reescritura deja la entrada sin el
     * namespace víctima (palette re-empaquetada, fallo intermitente en scan, etc.),
     * {@code chunksWithNs(ns)} devuelve {@code []} y el for-loop no captura nada.
     *
     * <p>Aplicación de §3.17 a la frontera "qué chunks visitar": {@code byChunk} no
     * es la fuente de verdad para el barrido. {@code tier1Disable} captura
     * atómicamente {@code chunkKeys} en el snapshot y se los pasa por aquí; el sweep
     * los usa tal cual, sin releer.
     *
     * @param chunkKeysOverride si no-null, el for-loop itera EXACTAMENTE estas
     *     claves; si null, fallback al lookup en vivo (compat con harnesses viejos).
     */
    public Map<String, Object> tier1Sweep(final String ns, final String restorePath,
                                          final boolean dryRun, final List<Long> chunkKeysOverride) {
        if (ns == null || ns.isEmpty()) {
            return fail("BAD_PARAMS", "tier1Sweep requiere ns");
        }
        if (!dryRun && (restorePath == null || restorePath.isEmpty())) {
            return fail("BAD_PARAMS", "tier1Sweep requiere restorePath (salvo dryRun)");
        }
        Object client = Boot.clientInstance;
        if (client == null) {
            return fail("NOT_READY", "la instancia del cliente aún no existe");
        }
        try {
            ensureRegistry();
            // stoneBlock + airBlock ambos necesarios: resolveStateMethods (vía
            // ensureLevelMethods) valida el par BlockState↔Block con un round-trip
            // contra minecraft:stone — sin stone no se inicializa mBlockOfState.
            if (airBlock == null || stoneBlock == null) {
                return fail("NOT_READY", "el registro de bloques aún no está listo");
            }
        } catch (Throwable t) {
            return fail("INTERNAL", "registro: " + t);
        }
        List<Object> candidates = serverCandidates(client);
        if (candidates.isEmpty()) {
            return fail("NOT_READY", "no hay servidor integrado vivo (mundo no cargado)");
        }
        for (Object cand : candidates) {
            final Object candidate = cand;
            final Map<String, Object> holder = new HashMap<String, Object>();
            final CountDownLatch latch = new CountDownLatch(1);
            try {
                ((Executor) candidate).execute(new Runnable() {
                    public void run() {
                        try {
                            attemptTier1Sweep(candidate, ns, restorePath, dryRun, holder, chunkKeysOverride);
                        } catch (Throwable t) {
                            holder.put("error", String.valueOf(t));
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            } catch (Throwable t) {
                continue;
            }
            boolean done;
            try {
                // saveAllChunks + barrido bloque-a-bloque pueden tardar varios segundos
                // en un mundo de prueba pequeño; 60s es cota conservadora.
                done = latch.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return fail("INTERNAL", "interrumpido esperando al hilo del servidor");
            }
            if (!done) {
                return fail("INTERNAL", "el hilo del servidor no respondió en 60s");
            }
            if (Boolean.TRUE.equals(holder.get("ok"))) {
                return holder;
            }
            if (holder.get("code") instanceof String) {
                return fail((String) holder.get("code"), String.valueOf(holder.get("error")));
            }
            if (holder.containsKey("error") && server == candidate) {
                return fail("INTERNAL", String.valueOf(holder.get("error")));
            }
        }
        return fail("NOT_READY", "ningún executor del cliente expuso un ServerLevel");
    }

    /** Corre EN el hilo del candidato; localiza el ServerLevel, captura y barre. */
    private void attemptTier1Sweep(Object candidate, String ns, String restorePath,
                                   boolean dryRun, Map<String, Object> holder,
                                   List<Long> chunkKeysOverride) throws Exception {
        List<Object> lvls = ensureLevels(candidate);
        if (lvls == null || lvls.isEmpty()) return;
        server = candidate;
        ensureLevelMethods(lvls.get(0));
        Object airState = mDefaultState.invoke(airBlock);

        long capturedBlocks = 0;
        long sweptBlocks = 0;
        long wouldSweep = 0;
        long residualBlocks = 0;
        long chunksAffected = 0;
        long staleAggregateChunks = 0;
        long sweepErrors = 0;          // setBlock falló en una pos (chunk no cargado, etc.)
        // Diagnóstico del 1er fallo "wouldSweep=0 con chunksAffected>=1": distinguir si
        // namespaceOf(state) devuelve null (blockIndex no construido) o "minecraft"
        // (matchea pero no es la víctima). Sin este desglose, ambos colapsan a "no match".
        long nsNullSamples = 0;
        long nsVanillaSamples = 0;
        long nsOtherModSamples = 0;
        String nsOtherExample = null;
        // Histograma de TODOS los namespaces vistos en chunks del agregado: si
        // "mcwstairs" sale 0 pero "mcwbridges" tiene N>0, el usuario colocó otro
        // mod sin querer. Se exporta como top-8 por frecuencia al final.
        java.util.Map<String, Long> nsHisto = new java.util.HashMap<String, Long>();

        // ---- Tier 1 corte 2: captura quirúrgica zero-IO ----
        // El corte 1 fuerza dirty cada chunk del view distance con un setBlock dummy y
        // luego invocaba saveAllChunks(forced=true), que persiste a disco un montón de
        // chunks sin la víctima + level.dat + playerdata (tick lag medido de 14s).
        // El corte 2 sustituye eso por SerializableChunkData.copyOf(level, chunk).write()
        // invocado directamente sobre los LevelChunk cargados del view distance, vía
        // ChunkSource.getChunkNow(cx, cz). El write-hook ya inyectado dispara naturalmente
        // sobre el CompoundTag construido en memoria — sin tocar disco. La captura
        // (capturedPositions / RestoreWriter) sigue siendo simétrica al corte 3.
        if (!ensureScd(lvls.get(0))) {
            holder.put("code", "INTERNAL");
            holder.put("error", "no se halló SerializableChunkData.copyOf(ServerLevel, LevelChunk) o .write()");
            return;
        }
        if (!ensureChunkSource(lvls.get(0))) {
            holder.put("code", "INTERNAL");
            holder.put("error", "no se halló ChunkSource.getChunkNow(int, int) → LevelChunk");
            return;
        }
        if (!dryRun) {
            Map<String, Object> armed = Ledger.INSTANCE.arm(ns, restorePath);
            if (!Boolean.TRUE.equals(armed.get("armed"))) {
                holder.put("code", "INTERNAL");
                holder.put("error", "no se pudo armar el barrido: " + armed.get("error"));
                return;
            }
        }
        long writtenPre = readWritten();
        long copyOfCalls = 0;        // copyOf invocados con éxito (chunks reserializados in-memory)
        long writesTriggered = 0;    // SCD.write() invocados — dispara write-hook
        long chunksProbed = 0;       // (cx,cz) probadas (excluyendo descargadas)
        long copyOfNull = 0;         // copyOf retornó null en el 1er intento (chunk no dirty)
        long dirtied = 0;            // chunks forzados dirty con mutación neta cero
        // Breadcrumbs corte 5 (fix force-dirty real, junio 2026):
        long forceDirtyAttempts = 0; // veces que entró a la rama force-dirty (copyOf null en 1er intento)
        long forceDirtyOk = 0;       // veces que el force-dirty logró marcar dirty Y copyOf retry devolvió no-null
        long forceDirtyFailed = 0;   // force-dirty no logró marcar dirty (probe no halló pos vanilla, o copyOf siguió null)
        int forceDirtyLastY = -1;    // Y donde el último force-dirty tuvo éxito (diag de hit rate del probe)
        // sweepTrace: lista ordenada de eventos del for-loop. Cada chunk genera
        // varios eventos (enter, copyOf:call, copyOf:return, opcional forceDirty:*,
        // copyOf2:return, write:call, write:return; excepciones se capturan también).
        // Sin esto el catch (Throwable ignored) traga el síntoma — esto lo hace visible.
        // Cap 100 entradas para no inflar el JSON del receipt en runs con muchos chunks.
        final java.util.List<String> sweepTrace = new java.util.ArrayList<String>();
        // sweepErrorsDetail: stack trace COMPLETO de la causa raíz de cada excepción
        // capturada en el for-loop de captura. El trace lleva solo el resumen (clase:msg
        // del root cause); aquí va el stack entero, SIN el cap de 100 del trace, para que
        // un copyOf que lance InvocationTargetException:null (NPE interno con msg null)
        // revele la línea exacta donde revienta. Acotado por la huella (1-10 chunks).
        final java.util.List<String> sweepErrorsDetail = new java.util.ArrayList<String>();
        try {
            if (!dryRun) {
                // Quirúrgico: iteramos SOLO los chunks que el agregado byChunk in-vivo
                // reporta con presencia de la víctima (pieza 2). Para una huella de 4-12
                // chunks, copyOf().write() se invoca 4-12 veces, no 289. Coste proporcional
                // al footprint, no al view distance.
                //
                // Hallazgo del intento 3 (Tier 1 corte 2 v1): SerializableChunkData.copyOf
                // retorna null si chunk.isUnsaved() == false. Si MC autoguardó (o ni siquiera
                // marcó dirty desde la generación), copyOf aborta silenciosamente y la
                // captura queda en cero. Mitigación: intento copyOf "limpio" primero; si
                // retorna null, fuerzo dirty con UN setBlock(currentState, UPDATE_ALL) en
                // una pos del chunk y reintento. dirtied es proporcional a la huella, no al
                // view distance — la promesa de "zero-IO cuando es posible" se mantiene.
                // Override del corte 5: si tier1Disable pasó la lista atómica desde el
                // snapshot (mismo instante que blocks+chunks), usarla. Elimina la race
                // condition con el autosave/scan() que puede reescribir byChunk entre
                // el snapshot (IPC thread) y este momento (server thread).
                List<Long> targets = chunkKeysOverride != null
                        ? chunkKeysOverride
                        : Ledger.INSTANCE.chunksWithNs(ns);
                traceAdd(sweepTrace, "targets.size=" + targets.size()
                        + " source=" + (chunkKeysOverride != null ? "snapshot" : "live"));
                for (Long ckey : targets) {
                    int cx = (int) (ckey.longValue() >> 32);
                    int cz = (int) (ckey.longValue() & 0xffffffffL);
                    final String ckStr = "\"" + cx + "," + cz + "\"";
                    traceAdd(sweepTrace, "loop:enter chunk=" + ckStr);
                    for (int li = 0; li < lvls.size(); li++) {
                        Object L = lvls.get(li);
                        try {
                            Object cs = mGetChunkSource.invoke(L);
                            if (cs == null) {
                                traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr + " getChunkSource=null");
                                continue;
                            }
                            Object chunk = mGetChunkNow.invoke(cs, Integer.valueOf(cx), Integer.valueOf(cz));
                            if (chunk == null) {
                                traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr + " getChunkNow=null (chunk no cargado)");
                                continue;
                            }
                            if (levelChunkClass != null && !levelChunkClass.isInstance(chunk)) {
                                traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr
                                        + " notLevelChunk class=" + chunk.getClass().getSimpleName());
                                continue;
                            }
                            chunksProbed++;
                            traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr + " copyOf:call");
                            Object scd = mScdCopyOf.invoke(null, L, chunk);
                            traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr
                                    + " copyOf:return=" + (scd == null ? "null" : "nonNull"));
                            if (scd == null) {
                                copyOfNull++;
                                forceDirtyAttempts++;
                                traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr + " forceDirty:enter");
                                // FIX corte 5 (junio 2026): el setBlock(currentState) del corte 2
                                // NO marca dirty — MC tiene `if (prev == newState) return null;`
                                // dentro de LevelChunk.setBlockState que sale antes de markUnsaved.
                                // Funcionaba "por suerte" porque el chunk casi siempre estaba dirty
                                // desde la colocación del usuario. Cuando un autosave intermedio lo
                                // dejaba clean (race del orden de ~50ms-por-tick), copyOf seguía
                                // null en el retry y el for-loop salía silencioso en ~30ms.
                                //
                                // Force-dirty real, mutación neta CERO: hallar una pos del chunk con
                                // namespace="minecraft" (descarta pos modded que el veto cortaría),
                                // setBlock(stateDiff) + setBlock(stateOriginal). Dos setBlock REALES
                                // (prev != newState ambas veces) → dos markUnsaved → chunk dirty;
                                // mundo queda exactamente igual.
                                Object airSt = mDefaultState.invoke(airBlock);
                                Object stoneSt = mDefaultState.invoke(stoneBlock);
                                int[] probeYs = { 70, 64, 100, 50, 120, 40, 16, 200 };
                                int wx = cx * 16 + 8;
                                int wz = cz * 16 + 8;
                                boolean dirtied1 = false;
                                for (int yp : probeYs) {
                                    Object dpos;
                                    try { dpos = posCtor.newInstance(wx, yp, wz); }
                                    catch (Throwable t) { continue; }
                                    Object cur;
                                    try { cur = mGetState.invoke(L, dpos); }
                                    catch (Throwable t) { continue; }
                                    if (cur == null) continue;
                                    String curNs = Ledger.INSTANCE.namespaceOf(cur);
                                    if (!"minecraft".equals(curNs)) {
                                        traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr
                                                + " forceDirty:probeY=" + yp + " skipped(ns=" + curNs + ")");
                                        continue;
                                    }
                                    Object diff = (cur == airSt) ? stoneSt : airSt;
                                    try {
                                        mSetBlock.invoke(L, dpos, diff, UPDATE_ALL);
                                        mSetBlock.invoke(L, dpos, cur, UPDATE_ALL);
                                        dirtied1 = true;
                                        forceDirtyLastY = yp;
                                        traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr
                                                + " forceDirty:probeY=" + yp + " ok(ns=minecraft)");
                                        break;
                                    } catch (Throwable t) {
                                        traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr
                                                + " forceDirty:probeY=" + yp + " threw=" + t.getClass().getSimpleName()
                                                + ":" + t.getMessage());
                                    }
                                }
                                if (dirtied1) {
                                    dirtied++;
                                    scd = mScdCopyOf.invoke(null, L, chunk);
                                    traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr
                                            + " copyOf2:return=" + (scd == null ? "null" : "nonNull"));
                                    if (scd != null) forceDirtyOk++;
                                    else forceDirtyFailed++;
                                } else {
                                    forceDirtyFailed++;
                                    traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr
                                            + " forceDirty:noVanillaPosFound");
                                }
                            }
                            if (scd == null) {
                                traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr + " skip(scd=null)");
                                continue;
                            }
                            copyOfCalls++;
                            traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr + " write:call");
                            Object tag = mScdWrite.invoke(scd);
                            traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr
                                    + " write:return=" + (tag == null ? "null" : "tagOk"));
                            if (tag != null) writesTriggered++;
                        } catch (Throwable t) {
                            // chunk descargado / nivel sin ese chunk: normal — pero registrar
                            // CADA excepción capturada, para que no se silencien fallos reales.
                            // InvocationTargetException envuelve el throw real de copyOf/getChunkNow
                            // con getMessage()==null → desenvolvemos la causa raíz para ver QUÉ
                            // y DÓNDE revienta (un NPE interno de copyOf trae msg null pero stack útil).
                            Throwable root = rootCause(t);
                            traceAdd(sweepTrace, "level[" + li + "] chunk=" + ckStr
                                    + " threw=" + t.getClass().getSimpleName()
                                    + " root=" + root.getClass().getName()
                                    + ":" + root.getMessage()
                                    + topFrames(root, 3));
                            if (sweepErrorsDetail.size() < 20) {
                                sweepErrorsDetail.add("level[" + li + "] chunk=" + ckStr
                                        + "\n" + fullStack(root));
                            }
                        }
                    }
                }
                capturedBlocks = Ledger.INSTANCE.capturedBlockCount();
            }
        } finally {
            if (!dryRun) Ledger.INSTANCE.disarm();
        }
        long writtenPost = readWritten();
        long writtenDelta = writtenPost - writtenPre;

        // ---- Paso 4: enumerar posiciones a barrer ----
        // Sweep real: usa las posiciones EXACTAS que captureChunk identificó durante
        // el armed save. Es preciso (no iteramos 73k cells por chunk) y robusto ante
        // races en byChunk (que en v10 vimos que sobreescribe perNs vacío después).
        // Dry run: cae al agregado byChunk porque captureChunk no corrió.
        List<long[]> sweepTargets;
        java.util.Set<Long> targetChunkKeys = new java.util.HashSet<Long>();
        if (!dryRun) {
            sweepTargets = Ledger.INSTANCE.capturedPositions();
            for (long[] pos : sweepTargets) {
                int tcx = (int) (pos[0] >> 4);
                int tcz = (int) (pos[2] >> 4);
                targetChunkKeys.add((long) tcx << 32 | (tcz & 0xffffffffL));
            }
            chunksAffected = targetChunkKeys.size();
        } else {
            List<Long> chunks = Ledger.INSTANCE.chunksWithNs(ns);
            sweepTargets = new ArrayList<long[]>();
            chunksAffected = chunks.size();
            for (Long ckey : chunks) targetChunkKeys.add(ckey);
        }
        List<String> chunkCoords = new ArrayList<String>();
        for (Long ckey : targetChunkKeys) {
            int dcx = (int) (ckey.longValue() >> 32);
            int dcz = (int) (ckey.longValue() & 0xffffffffL);
            chunkCoords.add("(" + dcx + "," + dcz + ")");
            if (chunkCoords.size() >= 6) break;
        }

        if (!dryRun && !sweepTargets.isEmpty()) {
            // Sweep real: itera EXACTAMENTE las posiciones capturadas. Cero ambigüedad
            // de chunk, cero dependencia de byChunk. Cuadra captura↔barrido 1:1.
            int sweepDiagCap = 0;  // limita el detalle por-posición a 8 entradas
            for (long[] target : sweepTargets) {
                int x = (int) target[0], y = (int) target[1], z = (int) target[2];
                boolean swept = false;
                StringBuilder reads = new StringBuilder();  // qué se leyó por nivel
                for (int li = 0; li < lvls.size(); li++) {
                    Object L = lvls.get(li);
                    try {
                        Object pos = posCtor.newInstance(x, y, z);
                        Object state = mGetState.invoke(L, pos);
                        String nsState = Ledger.INSTANCE.namespaceOf(state);
                        String hk = nsState == null ? "<null>" : nsState;
                        Long h = nsHisto.get(hk);
                        nsHisto.put(hk, h == null ? 1L : h + 1L);
                        reads.append(" L").append(li).append('=').append(hk);
                        if (ns.equals(nsState)) {
                            mSetBlock.invoke(L, pos, airState, UPDATE_ALL);
                            sweptBlocks++;
                            swept = true;
                            break;
                        }
                    } catch (Throwable t) {
                        sweepErrors++;
                        Throwable root = rootCause(t);
                        reads.append(" L").append(li).append("=threw:")
                             .append(root.getClass().getSimpleName()).append(':').append(root.getMessage());
                    }
                }
                if (!swept) {
                    sweepErrors++;
                    // Diag corte 5 (junio 2026): captured>0 pero swept=0 significa que las
                    // posiciones capturadas no releen como víctima. Trazamos (x,y,z) + lo
                    // leído por nivel para distinguir coordenada equivocada (lee minecraft/
                    // air) de resolución de namespace equivocada (lee la víctima pero no casa).
                    if (sweepDiagCap < 8) {
                        traceAdd(sweepTrace, "sweep:miss pos=" + x + "," + y + "," + z + reads.toString());
                        sweepDiagCap++;
                    }
                }
            }
        } else if (dryRun) {
            // Dry run: barrido por chunk vía agregado (para validar localización).
            for (Long ckey : targetChunkKeys) {
                int cx = (int) (ckey.longValue() >> 32);
                int cz = (int) (ckey.longValue() & 0xffffffffL);
                int matchedInChunk = 0;
                for (Object L : lvls) {
                    for (int y = CHUNK_Y_MIN; y < CHUNK_Y_MAX; y++) {
                        for (int x = cx * 16; x < cx * 16 + 16; x++) {
                            for (int z = cz * 16; z < cz * 16 + 16; z++) {
                                Object pos;
                                try { pos = posCtor.newInstance(x, y, z); }
                                catch (Throwable t) { continue; }
                                Object state;
                                try { state = mGetState.invoke(L, pos); }
                                catch (Throwable t) { continue; }
                                String nsState = Ledger.INSTANCE.namespaceOf(state);
                                String hk = nsState == null ? "<null>" : nsState;
                                Long h = nsHisto.get(hk);
                                nsHisto.put(hk, h == null ? 1L : h + 1L);
                                if (!ns.equals(nsState)) {
                                    if (matchedInChunk == 0 && nsNullSamples + nsVanillaSamples + nsOtherModSamples < 3) {
                                        if (nsState == null) nsNullSamples++;
                                        else if ("minecraft".equals(nsState)) nsVanillaSamples++;
                                        else {
                                            nsOtherModSamples++;
                                            if (nsOtherExample == null) nsOtherExample = nsState;
                                        }
                                    }
                                    continue;
                                }
                                matchedInChunk++;
                                wouldSweep++;
                            }
                        }
                    }
                    if (matchedInChunk > 0) break;
                }
                if (matchedInChunk == 0) staleAggregateChunks++;
            }
        }

        // ---- Paso 5: re-escanear residual (solo en mutación real) ----
        // Itera EXACTAMENTE las posiciones capturadas: cada una debe ser aire ahora.
        if (!dryRun) {
            for (long[] target : sweepTargets) {
                int x = (int) target[0], y = (int) target[1], z = (int) target[2];
                for (Object L : lvls) {
                    try {
                        Object pos = posCtor.newInstance(x, y, z);
                        Object state = mGetState.invoke(L, pos);
                        String nsState = Ledger.INSTANCE.namespaceOf(state);
                        if (ns.equals(nsState)) {
                            residualBlocks++;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }

        String thread = Thread.currentThread().getName();
        holder.put("ok", Boolean.TRUE);
        holder.put("ns", ns);
        holder.put("dryRun", dryRun);
        holder.put("capturedBlocks", capturedBlocks);
        holder.put("sweptBlocks", sweptBlocks);
        holder.put("wouldSweep", wouldSweep);
        holder.put("residualBlocks", residualBlocks);
        holder.put("chunksAffected", chunksAffected);
        holder.put("staleAggregateChunks", staleAggregateChunks);
        holder.put("sweepErrors", sweepErrors);
        // Corte 2: byChunk se actualiza in-vivo desde onSetBlock (pieza 2). Cada
        // setBlock(AIR) del sweep decrementa byChunk[ckey][ns], así que wcg.counts
        // post-sweep es honesto sin esperar al siguiente save. La deuda del corte 1
        // (aggregateStale=true) queda pagada.
        holder.put("aggregateStale", Boolean.FALSE);
        holder.put("onServerThread", thread != null && thread.toLowerCase().contains("server"));
        holder.put("thread", thread);
        // Instrumentación pasiva del fallback de niveles (tier1-cleanup):
        holder.put("levelFallbackTriggered", levelFallbackTriggered);
        if (firstFallbackDim != null) holder.put("firstFallbackDim", firstFallbackDim);
        // Diagnóstico de namespaceOf: distingue blockIndex-null (nsNullSamples) de
        // mismatch real (nsVanillaSamples / nsOtherModSamples). Si nsNullSamples > 0
        // sin vanilla/other, blockIndex no se construyó.
        holder.put("nsNullSamples", nsNullSamples);
        holder.put("nsVanillaSamples", nsVanillaSamples);
        holder.put("nsOtherModSamples", nsOtherModSamples);
        if (nsOtherExample != null) holder.put("nsOtherExample", nsOtherExample);
        holder.put("moddedBlockCount", Ledger.INSTANCE.moddedBlockCount());
        holder.put("chunkCoords", chunkCoords);
        holder.put("levels", lvls.size());
        // Histograma compactado a top-8 namespaces más populares (evita inflar el JSON).
        List<String> hist = new ArrayList<String>();
        List<Map.Entry<String, Long>> entries = new ArrayList<Map.Entry<String, Long>>(nsHisto.entrySet());
        java.util.Collections.sort(entries, new java.util.Comparator<Map.Entry<String, Long>>() {
            public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        for (int i = 0; i < Math.min(8, entries.size()); i++) {
            hist.add(entries.get(i).getKey() + "=" + entries.get(i).getValue());
        }
        holder.put("nsHisto", hist);
        // Diag de la captura quirúrgica del corte 2: cuántos chunks reserializamos
        // in-memory (copyOfCalls), cuántas .write() corrieron (writesTriggered) y el
        // delta de WRITTEN del Bridge (writtenDelta). En el corte 2 writtenDelta refleja
        // writes en RAM disparados por copyOf().write(); zero-IO es de diseño (copyOf
        // no toca disco), no se infiere del valor de writtenDelta.
        holder.put("writtenDelta", writtenDelta);
        holder.put("writtenPre", writtenPre);
        holder.put("writtenPost", writtenPost);
        holder.put("copyOfCalls", copyOfCalls);
        holder.put("writesTriggered", writesTriggered);
        holder.put("chunksProbed", chunksProbed);
        holder.put("copyOfNull", copyOfNull);
        // Diag scan vs captureChunk: si capturePalettesWithArmedNs > scanArmedNsHits,
        // hay un bug en scan() (no detecta lo que captureChunk sí detecta). Si ambos
        // contadores cuadran pero chunksAffected==0, el bug está entre scan() y el
        // estado final de byChunk (alguna ejecución posterior lo limpia).
        holder.put("scanCalls", Ledger.INSTANCE.scanCallsCount());
        holder.put("scanOk", Ledger.INSTANCE.scanOkCount());
        holder.put("scanFails", Ledger.INSTANCE.scanFailsCount());
        holder.put("scanArmedNsHits", Ledger.INSTANCE.scanArmedNsHitsCount());
        holder.put("captureCalls", Ledger.INSTANCE.captureCallsCount());
        holder.put("capturePalettesWithArmedNs", Ledger.INSTANCE.capturePalettesWithArmedNsCount());
        holder.put("lastScanError", Ledger.INSTANCE.lastScanError());
        // dirtied refleja chunks víctima forzados dirty con UN setBlock cuando copyOf
        // retornaba null. Proporcional a la huella (típicamente 1-10), no a las 867
        // dummies del corte 1. dirtied == 0 = todos los chunks estaban naturalmente dirty.
        holder.put("dirtied", dirtied);
        // Diag corte 5: de dónde vino la lista de chunks. "snapshot" = override
        // atómico de tier1Disable (fuente de verdad estable); "live" = lookup en
        // byChunk en el server thread (vulnerable a la race con autosave/scan).
        holder.put("chunkKeysSource", chunkKeysOverride != null ? "snapshot" : "live");
        // Diag corte 5 force-dirty real: cuántas veces hubo que forzar dirty y si
        // funcionó. En la ruta feliz (chunk dirty desde la colocación del usuario)
        // forceDirtyAttempts == 0. Cuando un autosave dejó el chunk clean,
        // forceDirtyAttempts > 0 y forceDirtyOk debe igualarlo (si forceDirtyFailed > 0
        // sin forceDirtyOk → el probe no halló pos vanilla, caso patológico).
        holder.put("forceDirtyAttempts", forceDirtyAttempts);
        holder.put("forceDirtyOk", forceDirtyOk);
        holder.put("forceDirtyFailed", forceDirtyFailed);
        holder.put("forceDirtyLastY", (long) forceDirtyLastY);
        // sweepTrace: serie ordenada de eventos del for-loop. Cubre los tres
        // breadcrumbs que faltaban ANTES de la rama force-dirty (loop:enter,
        // copyOf:call, copyOf:return) más los posteriores (forceDirty:*, write:*,
        // excepciones). Si en un run FAIL no aparece "copyOf:call" el problema
        // está en getChunkSource/getChunkNow/levelChunkClass; si aparece pero no
        // "copyOf:return" copyOf lanzó (capturado en catch+trace); si aparece
        // "copyOf:return=null" sin "forceDirty:enter" hubo otra guard inesperada.
        holder.put("sweepTrace", sweepTrace);
        // Stack traces completos de la causa raíz de cada excepción de captura. Vacío
        // en un run sano. Si copyOf lanza, aquí está la línea exacta del NPE interno.
        holder.put("sweepErrorsDetail", sweepErrorsDetail);
    }

    /**
     * Restitución viva (corte 5b): re-inyecta los bloques capturados en el archivo
     * MKSAR1 de vuelta al mundo vivo, sin reiniciar. Espejo exacto de
     * {@link #tier1Sweep}: valida params, asegura registro + servidor vivo, y por
     * cada candidato marshalea un Runnable al hilo del servidor con un
     * {@link CountDownLatch} (60s de cota). El Runnable reconstruye cada BlockState
     * desde su NBT y, si la celda viva sigue en aire (§7), llama {@code setBlock}.
     *
     * <p>Es el INVERSO del sweep: el sweep escribe AIR (borra), esta escribe el
     * estado reconstruido (restituye). El {@code setBlock} vivo dispara el observador
     * {@code onSetBlock} del corte 2 → {@code byChunk} sube → {@code wcg.counts} lo
     * refleja, que es el oráculo numérico de que los bloques reaparecieron.
     *
     * <p>Alcance: estados de bloque solamente (igual que corte 4). El contenido de
     * block-entity y los items del jugador quedan FUERA (límite documentado).
     */
    public Map<String, Object> tier1Restitution(final String ns, final String restorePath) {
        if (ns == null || ns.isEmpty()) {
            return fail("BAD_PARAMS", "tier1Restitution requiere ns");
        }
        if (restorePath == null || restorePath.isEmpty()) {
            return fail("BAD_PARAMS", "tier1Restitution requiere restorePath");
        }
        Object client = Boot.clientInstance;
        if (client == null) {
            return fail("NOT_READY", "la instancia del cliente aún no existe");
        }
        try {
            ensureRegistry();
            // Mismo gate que el sweep: resolveStateMethods valida el par
            // BlockState↔Block contra minecraft:stone; sin air/stone no hay métodos.
            if (airBlock == null || stoneBlock == null) {
                return fail("NOT_READY", "el registro de bloques aún no está listo");
            }
        } catch (Throwable t) {
            return fail("INTERNAL", "registro: " + t);
        }
        List<Object> candidates = serverCandidates(client);
        if (candidates.isEmpty()) {
            return fail("NOT_READY", "no hay servidor integrado vivo (mundo no cargado)");
        }
        for (Object cand : candidates) {
            final Object candidate = cand;
            final Map<String, Object> holder = new HashMap<String, Object>();
            final CountDownLatch latch = new CountDownLatch(1);
            try {
                ((Executor) candidate).execute(new Runnable() {
                    public void run() {
                        try {
                            attemptTier1Restitution(candidate, ns, restorePath, holder);
                        } catch (Throwable t) {
                            holder.put("error", String.valueOf(t));
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            } catch (Throwable t) {
                continue;
            }
            boolean done;
            try {
                done = latch.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return fail("INTERNAL", "interrumpido esperando al hilo del servidor");
            }
            if (!done) {
                return fail("INTERNAL", "el hilo del servidor no respondió en 60s");
            }
            if (Boolean.TRUE.equals(holder.get("ok"))) {
                return holder;
            }
            if (holder.get("code") instanceof String) {
                return fail((String) holder.get("code"), String.valueOf(holder.get("error")));
            }
            if (holder.containsKey("error") && server == candidate) {
                return fail("INTERNAL", String.valueOf(holder.get("error")));
            }
        }
        return fail("NOT_READY", "ningún executor del cliente expuso un ServerLevel");
    }

    /**
     * Corre EN el hilo del candidato; localiza el ServerLevel, lee los bloques del
     * archivo y los re-inyecta. Espejo del bucle de escritura viva de
     * {@link #attemptTier1Sweep}, cambiando solo el state escrito (reconstruido en
     * vez de AIR) y añadiendo la política §7 (inyectar solo si la celda viva está
     * en aire). Contadores LOCALES en InProcess — no toca el path offline de Ledger.
     */
    private void attemptTier1Restitution(Object candidate, String ns, String restorePath,
                                         Map<String, Object> holder) throws Exception {
        List<Object> lvls = ensureLevels(candidate);
        if (lvls == null || lvls.isEmpty()) {
            t1log("  attemptRest ABORT lvls vacío (candidate=" + (candidate == null ? "<null>" : candidate.getClass().getName()) + ")");
            return;
        }
        server = candidate;
        ensureLevelMethods(lvls.get(0));
        t1log("  attemptRest thread=" + Thread.currentThread().getName() + " levels=" + lvls.size());

        // restTrace / restErrorsDetail: espejos de sweepTrace / sweepErrorsDetail.
        final java.util.List<String> restTrace = new java.util.ArrayList<String>();
        final java.util.List<String> restErrorsDetail = new java.util.ArrayList<String>();

        long captured = 0;            // bloques en el archivo MKSAR1
        long restored = 0;            // setBlock vivo aplicado (celda estaba en aire)
        long conflicts = 0;           // celda ya ocupada por otro bloque → skip (§7)
        long misses = 0;              // reconstrucción NBT→BlockState devolvió null
        long restitutionErrors = 0;   // excepción dura por bloque
        java.util.Set<Long> touchedChunks = new java.util.HashSet<Long>();

        List<Object[]> blocks = Ledger.INSTANCE.readRestoreBlocks(restorePath);
        captured = blocks.size();
        t1log("  attemptRest readRestoreBlocks path=" + restorePath + " → captured=" + captured + " registros 'B'");
        int diagCap = 0;  // limita el detalle por-posición

        for (Object[] rec : blocks) {
            int x = ((Integer) rec[0]).intValue();
            int y = ((Integer) rec[1]).intValue();
            int z = ((Integer) rec[2]).intValue();
            byte[] nbt = (byte[]) rec[3];

            Object state = Ledger.INSTANCE.reconstructBlockState(nbt);
            if (state == null) {
                misses++;
                if (diagCap < 16) {
                    traceAdd(restTrace, "rest:miss pos=" + x + "," + y + "," + z + " reason=reconstruct=null");
                    diagCap++;
                }
                continue;
            }

            boolean handled = false;
            for (int li = 0; li < lvls.size(); li++) {
                Object L = lvls.get(li);
                try {
                    Object pos = posCtor.newInstance(x, y, z);
                    Object cur = mGetState.invoke(L, pos);
                    // §7: solo inyectar si la celda viva sigue en aire. Si otro bloque
                    // (jugador, otro mod) ocupa la celda, es conflicto → no pisar.
                    if (!Ledger.INSTANCE.isAirState(cur)) {
                        conflicts++;
                        handled = true;
                        if (diagCap < 16) {
                            traceAdd(restTrace, "rest:conflict pos=" + x + "," + y + "," + z
                                    + " L" + li + "=" + Ledger.INSTANCE.namespaceOf(cur));
                            diagCap++;
                        }
                        break;
                    }
                    // Celda en aire → re-inyectar. setBlock vivo dispara onSetBlock
                    // (corte 2) → byChunk sube → wcg.counts lo refleja (oráculo).
                    mSetBlock.invoke(L, pos, state, UPDATE_ALL);
                    restored++;
                    handled = true;
                    int tcx = x >> 4, tcz = z >> 4;
                    touchedChunks.add((long) tcx << 32 | (tcz & 0xffffffffL));
                    break;
                } catch (Throwable t) {
                    restitutionErrors++;
                    Throwable root = rootCause(t);
                    traceAdd(restTrace, "rest:error pos=" + x + "," + y + "," + z
                            + " L" + li + " threw=" + t.getClass().getSimpleName()
                            + " root=" + root.getClass().getName() + ":" + root.getMessage()
                            + topFrames(root, 3));
                    if (restErrorsDetail.size() < 20) {
                        restErrorsDetail.add("pos=" + x + "," + y + "," + z + " L" + li
                                + "\n" + fullStack(root));
                    }
                }
            }
            if (!handled && diagCap < 16) {
                traceAdd(restTrace, "rest:unhandled pos=" + x + "," + y + "," + z);
                diagCap++;
            }
        }

        String thread = Thread.currentThread().getName();
        holder.put("ok", Boolean.TRUE);
        holder.put("ns", ns);
        holder.put("captured", captured);
        holder.put("restored", restored);
        holder.put("conflicts", conflicts);
        holder.put("misses", misses);
        holder.put("restitutionErrors", restitutionErrors);
        holder.put("chunksAffected", (long) touchedChunks.size());
        holder.put("onServerThread", thread != null && thread.toLowerCase().contains("server"));
        holder.put("thread", thread);
        holder.put("levels", lvls.size());
        holder.put("restorePath", restorePath);
        holder.put("restTrace", restTrace);
        holder.put("restErrorsDetail", restErrorsDetail);
    }

    // ====================== F4 wcg.refs: sonda de extracción de recetas ======================
    // De-risk del grafo de aristas de referencia (modelo-de-datos §3.A): probar que se
    // extraen estructuralmente los ids de item (ingredientes + resultado) de UNA receta
    // viva, genéricamente, sin código por-tipo-de-receta. Lee el RecipeManager vivo
    // reusando el payload del corte 4 (Ledger.liveRecipeMap), sin disparar reload.
    // Método interim de de-risk (como tx.probeSetBlock), NO contrato tx congelado.

    private static final String[] ITEM_STACK_NAMES = {
            "net.minecraft.class_1799", "net.minecraft.world.item.ItemStack" };
    private static final String[] ITEM_NAMES = {
            "net.minecraft.class_1792", "net.minecraft.world.item.Item" };
    private static final String[] INGREDIENT_NAMES = {
            "net.minecraft.class_1856", "net.minecraft.world.item.crafting.Ingredient" };
    private static final String[] HOLDER_NAMES = {
            "net.minecraft.class_6880", "net.minecraft.core.Holder" };
    private static final String[] RECIPE_NAMES = {
            "net.minecraft.class_1860", "net.minecraft.world.item.crafting.Recipe" };
    private static final String[] RECIPE_MANAGER_NAMES = {
            "net.minecraft.class_1863", "net.minecraft.world.item.crafting.RecipeManager" };

    private static final int HARVEST_DEPTH_CAP = 12;

    private volatile Object itemRegistry;        // Registry<Item> vivo
    private volatile Method itemGetId;            // getId(Item): Identifier
    private volatile Method mGetItem;             // ItemStack.getItem(): Item
    private volatile Method mGetRecipeManager;    // MinecraftServer.getRecipeManager(): RecipeManager
    private volatile Method mRecipeValue;         // RecipeHolder.value(): Recipe
    private volatile String getRecipeManagerSig = "unresolved";

    /** Localiza el registro de items (mismo patrón que {@link #ensureRegistry}). */
    private void ensureItemRegistry() throws Exception {
        if (itemRegistry != null && itemGetId != null) return;
        Object[] reg = Wcg.liveRegistry(Agent.instrumentation, "minecraft:item");
        if (reg == null) return;
        itemRegistry = reg[0];
        itemGetId = (Method) reg[1];
    }

    /**
     * Sonda de-risk de aristas de referencia (F4 / {@code wcg.refs}). Recorre el mapa
     * de recetas VIVO del servidor (sin reload) y extrae estructuralmente los ids de
     * item de cada receta, reportando cobertura (qué fracción extrajo) y las aristas
     * cross-ns hacia {@code victimNs}. Mismo molde de coreografía que
     * {@link #tier1DpReload} / {@link #tier1Restitution}: marshalea al hilo del servidor.
     */
    public Map<String, Object> probeRecipes(final String victimNs) {
        if (victimNs == null || victimNs.isEmpty()) {
            return fail("BAD_PARAMS", "probeRecipes requiere ns");
        }
        Object client = Boot.clientInstance;
        if (client == null) {
            return fail("NOT_READY", "la instancia del cliente aún no existe");
        }
        if (server == null) {
            Map<String, Object> r = probe();
            if (!Boolean.TRUE.equals(r.get("ok"))) return r;
        }
        if (server == null) {
            return fail("NOT_READY", "no se pudo descubrir MinecraftServer");
        }
        try {
            ensureItemRegistry();
        } catch (Throwable t) {
            return fail("INTERNAL", "registro de items: " + t);
        }
        if (itemRegistry == null || itemGetId == null) {
            return fail("NOT_READY", "el registro de items aún no está listo");
        }

        final Object candidate = server;
        final Map<String, Object> holder = new HashMap<String, Object>();
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            ((Executor) candidate).execute(new Runnable() {
                public void run() {
                    try {
                        attemptProbeRecipes(victimNs, holder);
                    } catch (Throwable t) {
                        holder.put("error", String.valueOf(rootCause(t)));
                    } finally {
                        latch.countDown();
                    }
                }
            });
        } catch (Throwable t) {
            return fail("INTERNAL", "no se pudo marshalar al hilo del servidor: " + t);
        }
        boolean done;
        try {
            done = latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return fail("INTERNAL", "interrumpido esperando al hilo del servidor");
        }
        if (!done) {
            return fail("INTERNAL", "el hilo del servidor no respondió en 60s");
        }
        if (Boolean.TRUE.equals(holder.get("ok"))) {
            return holder;
        }
        if (holder.get("code") instanceof String) {
            return fail((String) holder.get("code"), String.valueOf(holder.get("error")));
        }
        return fail("INTERNAL", String.valueOf(holder.get("error")));
    }

    /** Corre EN el hilo del servidor: lee el mapa vivo y cosecha ids de cada receta. */
    private void attemptProbeRecipes(String victimNs, Map<String, Object> holder) throws Exception {
        long t0 = System.currentTimeMillis();
        Method getRm = resolveGetRecipeManager();
        if (getRm == null) {
            holder.put("code", "INTERNAL");
            holder.put("error", "no se resolvió getRecipeManager (" + getRecipeManagerSig + ")");
            return;
        }
        Object rm = getRm.invoke(server);
        if (rm == null) {
            holder.put("code", "INTERNAL");
            holder.put("error", "getRecipeManager devolvió null");
            return;
        }
        Map<Object, Object> map = Ledger.INSTANCE.liveRecipeMap(rm);
        if (map == null) {
            holder.put("code", "INTERNAL");
            holder.put("error", "liveRecipeMap null: " + Ledger.INSTANCE.dpLastError());
            return;
        }

        long scanned = 0, extractedResultOk = 0, extractedIngredientsOk = 0;
        long fullyExtracted = 0, walkFailures = 0, refsToVictim = 0, victimOwned = 0;
        Map<String, Long> recipeTypeCounts = new java.util.LinkedHashMap<String, Long>();
        java.util.List<Map<String, Object>> sampleEdges = new ArrayList<Map<String, Object>>();
        java.util.List<Map<String, Object>> sampleVictimRecipes = new ArrayList<Map<String, Object>>();
        java.util.Set<String> victimRefsOut = new java.util.TreeSet<String>();
        java.util.List<String> errors = new ArrayList<String>();

        for (Map.Entry<Object, Object> e : map.entrySet()) {
            scanned++;
            Object key = e.getKey();
            Object rh = e.getValue();
            String ownerNs = Ledger.INSTANCE.nsOfResourceKey(key);
            String recipeId = key == null ? "?" : key.toString();
            Object recipe = recipeValueOf(rh);
            String typeName = recipe == null ? "null" : recipe.getClass().getName();
            Long tc = recipeTypeCounts.get(typeName);
            recipeTypeCounts.put(typeName, tc == null ? 1L : tc + 1L);

            java.util.Set<String> ing = new java.util.LinkedHashSet<String>();
            java.util.Set<String> res = new java.util.LinkedHashSet<String>();
            boolean threw = false;
            try {
                harvestItems(recipe, ing, res, false, 0,
                        new java.util.IdentityHashMap<Object, Boolean>());
            } catch (Throwable t) {
                threw = true;
                if (errors.size() < 20) errors.add(recipeId + ": " + rootCause(t));
            }

            boolean hasRes = !res.isEmpty(), hasIng = !ing.isEmpty();
            if (hasRes) extractedResultOk++;
            if (hasIng) extractedIngredientsOk++;
            if (hasRes && hasIng) fullyExtracted++;
            if (!hasRes && !hasIng && threw) walkFailures++;

            boolean ownedByVictim = victimNs.equals(ownerNs);
            // Aristas cross-ns ENTRANTES: dueño ≠ víctima, referencia un item de la víctima.
            java.util.List<String> refItems = new ArrayList<String>();
            for (String id : ing) if (victimNs.equals(nsOfId(id)) && !refItems.contains(id)) refItems.add(id);
            for (String id : res) if (victimNs.equals(nsOfId(id)) && !refItems.contains(id)) refItems.add(id);
            if (!ownedByVictim && !refItems.isEmpty()) {
                refsToVictim++;
                if (sampleEdges.size() < 25) {
                    Map<String, Object> edge = new java.util.LinkedHashMap<String, Object>();
                    edge.put("recipe", recipeId);
                    edge.put("owner", ownerNs);
                    edge.put("type", typeName);
                    edge.put("refItems", refItems);
                    edge.put("result", new ArrayList<String>(res));
                    edge.put("ingredients", new ArrayList<String>(ing));
                    sampleEdges.add(edge);
                }
            }
            // Recetas propias de la víctima: prueba eyeball de extracción direccional
            // (mcwstairs SIEMPRE tiene recetas; sus referencias salientes son cross-ns).
            if (ownedByVictim) {
                victimOwned++;
                for (String id : ing) { String n = nsOfId(id); if (n != null && !victimNs.equals(n)) victimRefsOut.add(n); }
                for (String id : res) { String n = nsOfId(id); if (n != null && !victimNs.equals(n)) victimRefsOut.add(n); }
                if (sampleVictimRecipes.size() < 12) {
                    Map<String, Object> vr = new java.util.LinkedHashMap<String, Object>();
                    vr.put("recipe", recipeId);
                    vr.put("type", typeName);
                    vr.put("result", new ArrayList<String>(res));
                    vr.put("ingredients", new ArrayList<String>(ing));
                    sampleVictimRecipes.add(vr);
                }
            }
        }

        // Acotar recipeTypeCounts para no inflar el JSON.
        Map<String, Long> typesCapped = new java.util.LinkedHashMap<String, Long>();
        int tcap = 0;
        for (Map.Entry<String, Long> e : recipeTypeCounts.entrySet()) {
            if (tcap++ >= 40) break;
            typesCapped.put(e.getKey(), e.getValue());
        }

        String thread = Thread.currentThread().getName();
        holder.put("ok", Boolean.TRUE);
        holder.put("victimNs", victimNs);
        holder.put("recipeManagerClass", rm.getClass().getName());
        holder.put("getRecipeManagerSig", getRecipeManagerSig);
        holder.put("scanned", scanned);
        holder.put("extractedResultOk", extractedResultOk);
        holder.put("extractedIngredientsOk", extractedIngredientsOk);
        holder.put("fullyExtracted", fullyExtracted);
        holder.put("walkFailures", walkFailures);
        holder.put("distinctRecipeTypes", (long) recipeTypeCounts.size());
        holder.put("recipeTypeCounts", typesCapped);
        holder.put("refsToVictim", refsToVictim);
        holder.put("sampleEdges", sampleEdges);
        holder.put("victimOwned", victimOwned);
        holder.put("victimRefsOut", new ArrayList<String>(victimRefsOut));
        holder.put("sampleVictimRecipes", sampleVictimRecipes);
        holder.put("errors", errors);
        holder.put("onServerThread", thread != null && thread.toLowerCase().contains("server"));
        holder.put("durationMs", System.currentTimeMillis() - t0);
    }

    // ====================== F4 wcg.refs: sonda de aristas de TAGS (de-risk) ======================
    // Método interim de de-risk (como tx.probeRecipes / tx.probeSetBlock), NO contrato tx congelado.
    // Despeja las DOS incógnitas estructurales antes de plegar las aristas de tags en wcg.refs:
    //   (1) Tag → miembros: leer los bindings tag→items/bloques del REGISTRO vivo (class_2378) sin
    //       reload, genéricamente, sin código por-tag (análogo a Ledger.liveRecipeMap sobre el RM).
    //   (2) Tag como ingrediente: una receta con un Ingredient respaldado por un tag (#minecraft:planks)
    //       expone su TagKey en un HolderSet.Named; se atribuye la arista receta→tag (ya tenemos tag→items),
    //       de modo que la cascada diga "desactivar V vacía #T y rompe N recetas que usan #T".
    // Item + block tags, ambas mitades. Loot/SQLite/índice completo quedan al corte siguiente.

    private static final String[] TAG_KEY_NAMES = {
            "net.minecraft.class_6862", "net.minecraft.tags.TagKey" };
    private static final String[] IDENTIFIER_NAMES = {
            "net.minecraft.class_2960", "net.minecraft.resources.ResourceLocation" };
    private static final String[] HOLDER_SET_NAMES = {
            "net.minecraft.class_6885", "net.minecraft.core.HolderSet" };
    private static final String[] BLOCK_NAMES = {
            "net.minecraft.class_2248", "net.minecraft.world.level.block.Block" };

    private volatile Method mGetTags;             // Registry.getTags(): Stream/Iterable/Map de tags
    private volatile String getTagsSig = "unresolved";

    /**
     * Sonda de-risk de aristas de TAGS (F4 / {@code wcg.refs}). Mismo molde de coreografía
     * que {@link #probeRecipes}: marshalea al hilo del servidor. Asegura los registros de
     * items Y bloques (ambas mitades de la mitad-1) antes de delegar a {@link #attemptProbeTags}.
     */
    public Map<String, Object> probeTags(final String victimNs) {
        if (victimNs == null || victimNs.isEmpty()) {
            return fail("BAD_PARAMS", "probeTags requiere ns");
        }
        Object client = Boot.clientInstance;
        if (client == null) {
            return fail("NOT_READY", "la instancia del cliente aún no existe");
        }
        if (server == null) {
            Map<String, Object> r = probe();
            if (!Boolean.TRUE.equals(r.get("ok"))) return r;
        }
        if (server == null) {
            return fail("NOT_READY", "no se pudo descubrir MinecraftServer");
        }
        try {
            ensureItemRegistry();
            ensureRegistry();
        } catch (Throwable t) {
            return fail("INTERNAL", "registros (item/block): " + t);
        }
        if (itemRegistry == null || itemGetId == null) {
            return fail("NOT_READY", "el registro de items aún no está listo");
        }
        if (blockRegistry == null || blockGetId == null) {
            return fail("NOT_READY", "el registro de bloques aún no está listo");
        }

        final Object candidate = server;
        final Map<String, Object> holder = new HashMap<String, Object>();
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            ((Executor) candidate).execute(new Runnable() {
                public void run() {
                    try {
                        attemptProbeTags(victimNs, holder);
                    } catch (Throwable t) {
                        holder.put("error", String.valueOf(rootCause(t)));
                    } finally {
                        latch.countDown();
                    }
                }
            });
        } catch (Throwable t) {
            return fail("INTERNAL", "no se pudo marshalar al hilo del servidor: " + t);
        }
        boolean done;
        try {
            done = latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return fail("INTERNAL", "interrumpido esperando al hilo del servidor");
        }
        if (!done) {
            return fail("INTERNAL", "el hilo del servidor no respondió en 60s");
        }
        if (Boolean.TRUE.equals(holder.get("ok"))) {
            return holder;
        }
        if (holder.get("code") instanceof String) {
            return fail((String) holder.get("code"), String.valueOf(holder.get("error")));
        }
        return fail("INTERNAL", String.valueOf(holder.get("error")));
    }

    /** Corre EN el hilo del servidor: lee tags vivos (item+block) y atribuye tag-como-ingrediente. */
    private void attemptProbeTags(String victimNs, Map<String, Object> holder) throws Exception {
        long t0 = System.currentTimeMillis();
        long scannedTags = 0, tagsWithMembers = 0, tagWalkFailures = 0, distinctTagRegistries = 0;
        long victimTags = 0, tagsContainingVictim = 0;
        java.util.List<Map<String, Object>> sampleTags = new ArrayList<Map<String, Object>>();
        java.util.List<Map<String, Object>> sampleTagEdges = new ArrayList<Map<String, Object>>();
        java.util.List<String> errors = new ArrayList<String>();
        // tag (con ≥1 miembro de la víctima) → ids de esos miembros; alimenta la mitad-2.
        Map<String, java.util.List<String>> victimTagMembers =
                new java.util.LinkedHashMap<String, java.util.List<String>>();

        // ---- Mitad 1: tag → miembros, por registro {item, block} ----
        Object[] regs = { itemRegistry, blockRegistry };
        boolean[] regBlocks = { false, true };
        String[] regLabels = { "item", "block" };
        for (int ri = 0; ri < regs.length; ri++) {
            Object reg = regs[ri];
            if (reg == null) continue;
            Method gt = resolveGetTags(reg);
            if (gt == null) { errors.add(regLabels[ri] + ": getTags no resuelto"); continue; }
            Object res;
            try {
                res = gt.invoke(reg);
            } catch (Throwable t) {
                errors.add(regLabels[ri] + " getTags: " + rootCause(t));
                continue;
            }
            java.util.List<Object> els = materializeTags(res);
            if (els == null || els.isEmpty()) continue;
            distinctTagRegistries++;
            for (Object el : els) {
                scannedTags++;
                String tagId;
                try { tagId = tagIdOfElement(el); } catch (Throwable t) { tagId = null; }
                if (tagId == null) { tagWalkFailures++; continue; }
                String tagNs = nsOfId(tagId);
                java.util.Set<String> members = new java.util.LinkedHashSet<String>();
                try {
                    Iterable<?> named = namedOfElement(el);
                    if (named != null) harvestTagMembers(named, regBlocks[ri], members);
                } catch (Throwable t) {
                    tagWalkFailures++;
                    if (errors.size() < 20) errors.add(tagId + ": " + rootCause(t));
                }
                if (!members.isEmpty()) tagsWithMembers++;
                boolean ownedByVictim = victimNs.equals(tagNs);
                if (ownedByVictim) victimTags++;
                java.util.List<String> victimMembers = new ArrayList<String>();
                for (String mid : members) if (victimNs.equals(nsOfId(mid))) victimMembers.add(mid);
                if (!victimMembers.isEmpty()) {
                    victimTagMembers.put(tagId, victimMembers);
                    if (!ownedByVictim) {
                        tagsContainingVictim++;
                        if (sampleTagEdges.size() < 25) {
                            Map<String, Object> edge = new java.util.LinkedHashMap<String, Object>();
                            edge.put("tag", tagId);
                            edge.put("registry", regLabels[ri]);
                            edge.put("victimMembers", victimMembers);
                            sampleTagEdges.add(edge);
                        }
                    }
                }
                if (sampleTags.size() < 12) {
                    Map<String, Object> st = new java.util.LinkedHashMap<String, Object>();
                    st.put("tag", tagId);
                    st.put("registry", regLabels[ri]);
                    st.put("memberCount", (long) members.size());
                    java.util.List<String> sm = new ArrayList<String>();
                    for (String mid : members) { sm.add(mid); if (sm.size() >= 8) break; }
                    st.put("members", sm);
                    sampleTags.add(st);
                }
            }
        }

        // ---- Mitad 2: tag como ingrediente (recorre el RecipeManager vivo) ----
        long recipesUsingTags = 0, recipesUsingVictimTags = 0;
        java.util.List<Map<String, Object>> sampleRecipeTagEdges = new ArrayList<Map<String, Object>>();
        Method getRm = resolveGetRecipeManager();
        if (getRm == null) {
            errors.add("getRecipeManager no resuelto (" + getRecipeManagerSig + ")");
        } else {
            Object rm = getRm.invoke(server);
            Map<Object, Object> rmap = rm == null ? null : Ledger.INSTANCE.liveRecipeMap(rm);
            if (rmap == null) {
                errors.add("liveRecipeMap null: " + Ledger.INSTANCE.dpLastError());
            } else {
                for (Map.Entry<Object, Object> e : rmap.entrySet()) {
                    Object recipe = recipeValueOf(e.getValue());
                    String recipeId = e.getKey() == null ? "?" : e.getKey().toString();
                    java.util.Set<String> tagIds = new java.util.LinkedHashSet<String>();
                    try {
                        harvestTagRefs(recipe, tagIds, 0,
                                new java.util.IdentityHashMap<Object, Boolean>());
                    } catch (Throwable t) {
                        if (errors.size() < 20) errors.add(recipeId + ": " + rootCause(t));
                    }
                    if (tagIds.isEmpty()) continue;
                    recipesUsingTags++;
                    java.util.List<String> victimTagsUsed = new ArrayList<String>();
                    java.util.List<String> refItems = new ArrayList<String>();
                    for (String tid : tagIds) {
                        java.util.List<String> vm = victimTagMembers.get(tid);
                        if (vm != null) {
                            victimTagsUsed.add(tid);
                            for (String x : vm) if (!refItems.contains(x)) refItems.add(x);
                        }
                    }
                    if (!victimTagsUsed.isEmpty()) recipesUsingVictimTags++;
                    if (sampleRecipeTagEdges.size() < 25) {
                        Map<String, Object> edge = new java.util.LinkedHashMap<String, Object>();
                        edge.put("recipe", recipeId);
                        edge.put("tags", new ArrayList<String>(tagIds));
                        edge.put("victimTags", victimTagsUsed);
                        edge.put("victimRefItems", refItems);
                        sampleRecipeTagEdges.add(edge);
                    }
                }
            }
        }

        String thread = Thread.currentThread().getName();
        holder.put("ok", Boolean.TRUE);
        holder.put("victimNs", victimNs);
        holder.put("getTagsSig", getTagsSig);
        holder.put("scannedTags", scannedTags);
        holder.put("tagsWithMembers", tagsWithMembers);
        holder.put("tagWalkFailures", tagWalkFailures);
        holder.put("distinctTagRegistries", distinctTagRegistries);
        holder.put("victimTags", victimTags);
        holder.put("tagsContainingVictim", tagsContainingVictim);
        holder.put("sampleTags", sampleTags);
        holder.put("sampleTagEdges", sampleTagEdges);
        holder.put("recipesUsingTags", recipesUsingTags);
        holder.put("recipesUsingVictimTags", recipesUsingVictimTags);
        holder.put("sampleRecipeTagEdges", sampleRecipeTagEdges);
        holder.put("errors", errors);
        holder.put("onServerThread", thread != null && thread.toLowerCase().contains("server"));
        holder.put("durationMs", System.currentTimeMillis() - t0);
    }

    /**
     * {@code Registry.getTags()} resuelto por COMPORTAMIENTO (no por nombre): un no-arg de
     * {@code class_2378} cuyo resultado, materializado, itera a elementos que exponen un
     * {@code TagKey} (vía {@link #tagIdOfElement}) Y un iterable de {@code Holder}
     * (vía {@link #namedOfElement}). Esto descarta {@code getTagNames()} (solo TagKeys, sin
     * miembros) y {@code holders()} (Holders sin TagKey). Cacheado; firma en {@link #getTagsSig}.
     */
    private Method resolveGetTags(Object registry) {
        if (mGetTags != null) return mGetTags;
        try { Agent.openModule(Agent.instrumentation, registry.getClass()); } catch (Throwable ignored) {}
        for (Method m : registry.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt.isPrimitive() || rt == void.class || rt == String.class) continue;
            try {
                m.setAccessible(true);
                Object res = m.invoke(registry);
                if (res == null) continue;
                java.util.List<Object> els = materializeTags(res);
                if (els == null || els.isEmpty()) continue;
                boolean ok = false;
                for (int i = 0; i < els.size() && i < 8; i++) {
                    Object el = els.get(i);
                    if (tagIdOfElement(el) != null && namedOfElement(el) != null) { ok = true; break; }
                }
                if (ok) {
                    mGetTags = m;
                    getTagsSig = "byBehavior:" + m.getDeclaringClass().getSimpleName() + "."
                            + m.getName() + "→" + rt.getSimpleName();
                    return m;
                }
            } catch (Throwable ignored) {}
        }
        getTagsSig = "unresolved";
        return null;
    }

    /** Materializa el resultado de getTags (Stream de un solo uso / Iterable / Map) a una lista. */
    private java.util.List<Object> materializeTags(Object res) {
        java.util.List<Object> out = new ArrayList<Object>();
        if (res instanceof java.util.stream.Stream) {
            java.util.Iterator<?> it = ((java.util.stream.Stream<?>) res).iterator();
            int n = 0;
            while (it.hasNext() && n++ < 100000) out.add(it.next());
            return out;
        }
        if (res instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) res).entrySet()) out.add(e);
            return out;
        }
        if (res instanceof Iterable) {
            int n = 0;
            for (Object o : (Iterable<?>) res) { out.add(o); if (++n >= 100000) break; }
            return out;
        }
        return null;
    }

    /**
     * Saca el id del TagKey de un elemento de getTags, sea cual sea su forma:
     * el elemento ES un TagKey (Stream&lt;TagKey&gt;), o expone uno por un no-arg
     * (HolderSet.Named.key()), o por Map.Entry.getKey() / Pair.getFirst(). Devuelve "ns:path" o null.
     */
    private String tagIdOfElement(Object el) {
        if (el == null) return null;
        if (isNamed(el.getClass(), TAG_KEY_NAMES)) return locationId(el);
        for (Method m : el.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt.isPrimitive() || rt == void.class) continue;
            try {
                Object v = m.invoke(el);
                if (v != null && isNamed(v.getClass(), TAG_KEY_NAMES)) return locationId(v);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** TagKey.location(): no-arg cuyo retorno es un Identifier (class_2960). Devuelve "ns:path" o null. */
    private static String locationId(Object tagKey) {
        for (Method m : tagKey.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt.isPrimitive() || rt == void.class || rt == String.class) continue;
            try {
                Object v = m.invoke(tagKey);
                if (v != null && isNamed(v.getClass(), IDENTIFIER_NAMES)) {
                    String s = v.toString();
                    if (s != null && s.indexOf(':') > 0) return s;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * El iterable de {@code Holder} (el HolderSet.Named) de un elemento: el elemento mismo si
     * es Iterable (Stream&lt;Named&gt;), o un no-arg que devuelve un Iterable (Map.Entry.getValue()
     * / Pair.getSecond()). null si no lo expone (p.ej. un TagKey suelto sin miembros).
     */
    private Iterable<?> namedOfElement(Object el) {
        if (el == null) return null;
        if (el instanceof Iterable) return (Iterable<?>) el;
        for (Method m : el.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            try {
                Object v = m.invoke(el);
                if (v instanceof Iterable) return (Iterable<?>) v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Itera un HolderSet.Named (Iterable&lt;Holder&gt;) y cosecha los ids de su contenido. */
    private void harvestTagMembers(Iterable<?> named, boolean blocks, java.util.Set<String> out) {
        int count = 0;
        for (Object h : named) {
            if (count++ > 100000) break;
            if (h == null) continue;
            if (blocks) extractHolderBlock(h, out);
            else extractHolderItem(h, out);
        }
    }

    /** Desenvuelve un Holder&lt;Block&gt;: el no-arg que devuelve un Block (class_2248) es value(). */
    private void extractHolderBlock(Object holder, java.util.Set<String> bucket) {
        for (Method m : holder.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt.isPrimitive() || rt == void.class) continue;
            try {
                Object v = m.invoke(holder);
                if (v != null && isNamed(v.getClass(), BLOCK_NAMES)) { addBlockId(v, bucket); return; }
            } catch (Throwable ignored) {}
        }
    }

    /** Añade el id de un Block al bucket (omite minecraft:air). */
    private void addBlockId(Object block, java.util.Set<String> bucket) {
        try {
            Object ido = blockGetId.invoke(blockRegistry, block);
            if (ido == null) return;
            String id = ido.toString();
            if (!"minecraft:air".equals(id)) bucket.add(id);
        } catch (Throwable ignored) {}
    }

    /**
     * Walker acotado que recorre el grafo de una receta y cosecha los ids de los TAGS que usa
     * (un {@code Ingredient} respaldado por tag mantiene un {@code HolderSet.Named}). Al topar un
     * HolderSet (class_6885) saca su TagKey (Named lo expone; Direct devuelve null) y NO recursa
     * ese nodo — la back-ref al registro reventaría el walk, igual que con {@code Holder}.
     */
    private void harvestTagRefs(Object node, java.util.Set<String> tagIdsOut, int depth,
                                java.util.IdentityHashMap<Object, Boolean> visited) {
        if (node == null || depth > HARVEST_DEPTH_CAP) return;
        Class<?> cls = node.getClass();
        if (cls.isPrimitive() || node instanceof CharSequence || node instanceof Number
                || node instanceof Boolean || node instanceof Character || cls.isEnum()) {
            return;
        }
        if (isNamed(cls, HOLDER_SET_NAMES)) {
            String tid = tagIdOfElement(node);
            if (tid != null) tagIdsOut.add(tid);
            return;
        }
        if (isNamed(cls, HOLDER_NAMES)) return; // back-ref al registro: no recursar
        if (cls.isArray()) {
            if (!cls.getComponentType().isPrimitive()) {
                int len = java.lang.reflect.Array.getLength(node);
                for (int i = 0; i < len; i++)
                    harvestTagRefs(java.lang.reflect.Array.get(node, i), tagIdsOut, depth + 1, visited);
            }
            return;
        }
        if (node instanceof java.util.Optional) {
            java.util.Optional<?> o = (java.util.Optional<?>) node;
            if (o.isPresent()) harvestTagRefs(o.get(), tagIdsOut, depth + 1, visited);
            return;
        }
        if (node instanceof Map) {
            for (Object v : ((Map<?, ?>) node).values())
                harvestTagRefs(v, tagIdsOut, depth + 1, visited);
            return;
        }
        if (node instanceof Iterable) {
            try {
                for (Object v : (Iterable<?>) node)
                    harvestTagRefs(v, tagIdsOut, depth + 1, visited);
            } catch (Throwable ignored) {}
            return;
        }
        if (cls.getName().startsWith("net.minecraft.")) {
            if (visited.put(node, Boolean.TRUE) != null) return;
            try { Agent.openModule(Agent.instrumentation, cls); } catch (Throwable ignored) {}
            for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
                Field[] fields;
                try { fields = k.getDeclaredFields(); } catch (Throwable t) { continue; }
                for (Field f : fields) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        harvestTagRefs(f.get(node), tagIdsOut, depth + 1, visited);
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    // ====================== F4 wcg.refs: sonda de aristas de LOOT (de-risk) ======================
    // Método interim de de-risk (como tx.probeRecipes / tx.probeTags), NO contrato tx congelado.
    // Despeja la incógnita estructural antes de plegar las aristas loot→item en wcg.refs:
    //   las loot tables NO están en BuiltInRegistries (a diferencia de items/bloques) — viven en
    //   los registros RELOADABLE del servidor vivo. Hay que ALCANZARLAS sin reload, genéricamente,
    //   sin código por-tipo: localizar el Registry<LootTable> (class_2378 cuyos elementos son
    //   class_52) por COMPORTAMIENTO (no por nombre de método), y luego cosechar items con el
    //   mismo walker reflexivo (harvestItems) ya validado en recetas/tags.

    private static final String[] LOOT_TABLE_NAMES = {
            "net.minecraft.class_52", "net.minecraft.world.level.storage.loot.LootTable" };
    private static final String[] REGISTRY_NAMES = {
            "net.minecraft.class_2378", "net.minecraft.core.Registry" };
    // Anclas de "acceso a registros" para acotar la búsqueda al nivel del servidor (depth 0):
    // RegistryAccess (class_5455), ReloadableRegistries y su Lookup (class_9383 / class_9383$class_9385),
    // CombinedDynamicRegistries (class_7780), WrapperLookup (class_7225 y variantes).
    private static final String[] REGISTRY_ACCESS_NAMES = {
            "net.minecraft.class_5455", "net.minecraft.class_9383",
            "net.minecraft.class_9383$class_9385", "net.minecraft.class_7780",
            "net.minecraft.class_7225", "net.minecraft.class_7225$class_7874",
            "net.minecraft.core.RegistryAccess",
            "net.minecraft.server.ReloadableServerRegistries",
            "net.minecraft.core.HolderLookup$Provider" };

    private volatile Object lootRegistry;        // Registry<LootTable> vivo (descubierto por comportamiento)
    private volatile Method lootGetId;           // Registry.getId(LootTable): Identifier
    private volatile String lootRegistrySig = "unresolved";

    /**
     * Sonda de-risk de aristas de LOOT (F4 / {@code wcg.refs}). Mismo molde de coreografía que
     * {@link #probeRecipes}/{@link #probeTags}: marshalea al hilo del servidor. Asegura el registro
     * de items (las loot tables sueltan ItemStacks) antes de delegar a {@link #attemptProbeLoot}.
     */
    public Map<String, Object> probeLoot(final String victimNs) {
        if (victimNs == null || victimNs.isEmpty()) {
            return fail("BAD_PARAMS", "probeLoot requiere ns");
        }
        Object client = Boot.clientInstance;
        if (client == null) {
            return fail("NOT_READY", "la instancia del cliente aún no existe");
        }
        if (server == null) {
            Map<String, Object> r = probe();
            if (!Boolean.TRUE.equals(r.get("ok"))) return r;
        }
        if (server == null) {
            return fail("NOT_READY", "no se pudo descubrir MinecraftServer");
        }
        try {
            ensureItemRegistry();
        } catch (Throwable t) {
            return fail("INTERNAL", "registro de items: " + t);
        }
        if (itemRegistry == null || itemGetId == null) {
            return fail("NOT_READY", "el registro de items aún no está listo");
        }

        final Object candidate = server;
        final Map<String, Object> holder = new HashMap<String, Object>();
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            ((Executor) candidate).execute(new Runnable() {
                public void run() {
                    try {
                        attemptProbeLoot(victimNs, holder);
                    } catch (Throwable t) {
                        holder.put("error", String.valueOf(rootCause(t)));
                    } finally {
                        latch.countDown();
                    }
                }
            });
        } catch (Throwable t) {
            return fail("INTERNAL", "no se pudo marshalar al hilo del servidor: " + t);
        }
        boolean done;
        try {
            done = latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return fail("INTERNAL", "interrumpido esperando al hilo del servidor");
        }
        if (!done) {
            return fail("INTERNAL", "el hilo del servidor no respondió en 60s");
        }
        if (Boolean.TRUE.equals(holder.get("ok"))) {
            return holder;
        }
        if (holder.get("code") instanceof String) {
            return fail((String) holder.get("code"), String.valueOf(holder.get("error")));
        }
        return fail("INTERNAL", String.valueOf(holder.get("error")));
    }

    /** Corre EN el hilo del servidor: localiza el Registry<LootTable> vivo y cosecha items por tabla. */
    private void attemptProbeLoot(String victimNs, Map<String, Object> holder) throws Exception {
        long t0 = System.currentTimeMillis();
        Object reg = ensureLootRegistry();
        if (reg == null) {
            holder.put("code", "INTERNAL");
            holder.put("error", "no se localizó el Registry<LootTable> vivo (" + lootRegistrySig + ")");
            return;
        }
        Method getId = resolveLootGetId(reg);

        long scanned = 0, tablesWithItems = 0, walkFailures = 0, idsResolved = 0;
        long refsToVictim = 0, victimOwned = 0, totalItemRefs = 0;
        java.util.Set<String> distinctItems = new java.util.HashSet<String>();
        java.util.List<Map<String, Object>> sampleTables = new ArrayList<Map<String, Object>>();
        java.util.List<Map<String, Object>> sampleVictimEdges = new ArrayList<Map<String, Object>>();
        java.util.List<String> errors = new ArrayList<String>();

        for (Object table : (Iterable<?>) reg) {
            if (table == null) continue;
            scanned++;
            String tableId = null;
            if (getId != null) {
                try {
                    Object ido = getId.invoke(reg, table);
                    if (ido != null) { tableId = ido.toString(); idsResolved++; }
                } catch (Throwable ignored) {}
            }
            String ownerNs = nsOfId(tableId);
            java.util.Set<String> items = new java.util.LinkedHashSet<String>();
            try {
                // Las loot tables no tienen Ingredients: todo va a un único bucket de "drops".
                harvestItems(table, items, items, false, 0, new java.util.IdentityHashMap<Object, Boolean>());
            } catch (Throwable t) {
                walkFailures++;
                if (errors.size() < 20) errors.add((tableId == null ? "?" : tableId) + ": " + rootCause(t));
                continue;
            }
            if (!items.isEmpty()) tablesWithItems++;
            totalItemRefs += items.size();
            distinctItems.addAll(items);

            // Aristas cross-ns ENTRANTES: tabla de dueño ≠ víctima que suelta un item de la víctima.
            java.util.List<String> refItems = new ArrayList<String>();
            for (String id : items) if (victimNs.equals(nsOfId(id))) refItems.add(id);
            boolean ownedByVictim = victimNs.equals(ownerNs);
            if (!ownedByVictim && !refItems.isEmpty()) {
                refsToVictim++;
                if (sampleVictimEdges.size() < 25) {
                    Map<String, Object> edge = new java.util.LinkedHashMap<String, Object>();
                    edge.put("table", tableId);
                    edge.put("owner", ownerNs);
                    edge.put("refItems", refItems);
                    sampleVictimEdges.add(edge);
                }
            }
            if (ownedByVictim) victimOwned++;
            if (sampleTables.size() < 15 && !items.isEmpty()) {
                Map<String, Object> st = new java.util.LinkedHashMap<String, Object>();
                st.put("table", tableId);
                java.util.List<String> drops = new ArrayList<String>(items);
                if (drops.size() > 12) drops = drops.subList(0, 12);
                st.put("drops", drops);
                sampleTables.add(st);
            }
        }

        String thread = Thread.currentThread().getName();
        holder.put("ok", Boolean.TRUE);
        holder.put("victimNs", victimNs);
        holder.put("lootRegistryClass", reg.getClass().getName());
        holder.put("lootRegistrySig", lootRegistrySig);
        holder.put("lootGetIdResolved", getId != null);
        holder.put("scanned", scanned);
        holder.put("idsResolved", idsResolved);
        holder.put("tablesWithItems", tablesWithItems);
        holder.put("walkFailures", walkFailures);
        holder.put("totalItemRefs", totalItemRefs);
        holder.put("distinctItems", (long) distinctItems.size());
        holder.put("refsToVictim", refsToVictim);
        holder.put("victimOwned", victimOwned);
        holder.put("sampleTables", sampleTables);
        holder.put("sampleVictimEdges", sampleVictimEdges);
        holder.put("errors", errors);
        holder.put("onServerThread", thread != null && thread.toLowerCase().contains("server"));
        holder.put("durationMs", System.currentTimeMillis() - t0);
    }

    /** Localiza (y cachea) el {@code Registry<LootTable>} vivo por comportamiento; corre en el hilo del servidor. */
    private Object ensureLootRegistry() {
        if (lootRegistry != null) return lootRegistry;
        java.util.IdentityHashMap<Object, Boolean> visited = new java.util.IdentityHashMap<Object, Boolean>();
        int[] budget = { 6000 };
        StringBuilder path = new StringBuilder();
        Object found = scanForLootRegistry(server, 0, visited, budget, path);
        if (found != null) {
            lootRegistry = found;
            lootRegistrySig = "byBehavior:" + found.getClass().getSimpleName() + path;
        } else {
            lootRegistrySig = "unresolved (budgetLeft=" + budget[0] + ")";
        }
        return found;
    }

    /**
     * Búsqueda acotada (depth/budget/visited) de un {@code class_2378} cuyos elementos son
     * {@code class_52} (LootTable). Nunca recursa DENTRO de un registry (solo ojea su 1er
     * elemento) para no explotar. En depth 0 (el servidor) solo sigue retornos de tipo
     * "acceso a registros" para no invocar getters arbitrarios del servidor.
     */
    private Object scanForLootRegistry(Object node, int depth,
            java.util.IdentityHashMap<Object, Boolean> visited, int[] budget, StringBuilder path) {
        if (node == null || depth > 7 || budget[0] <= 0) return null;
        if (visited.put(node, Boolean.TRUE) != null) return null;
        budget[0]--;
        Class<?> cls = node.getClass();

        if (isNamed(cls, REGISTRY_NAMES)) {
            try {
                java.util.Iterator<?> it = ((Iterable<?>) node).iterator();
                if (it.hasNext()) {
                    Object first = it.next();
                    if (first != null && isNamed(first.getClass(), LOOT_TABLE_NAMES)) return node;
                }
            } catch (Throwable ignored) {}
            return null; // jamás recursar dentro de un registry
        }
        if (node instanceof java.util.Optional) {
            java.util.Optional<?> o = (java.util.Optional<?>) node;
            return o.isPresent() ? scanForLootRegistry(o.get(), depth + 1, visited, budget, path) : null;
        }
        if (node instanceof java.util.stream.Stream) {
            try {
                java.util.List<?> list = (java.util.List<?>) ((java.util.stream.Stream<?>) node)
                        .collect(java.util.stream.Collectors.toList());
                for (Object v : list) {
                    Object r = scanForLootRegistry(v, depth + 1, visited, budget, path);
                    if (r != null) return r;
                }
            } catch (Throwable ignored) {}
            return null;
        }
        if (node instanceof Map) {
            for (Object v : ((Map<?, ?>) node).values()) {
                Object r = scanForLootRegistry(v, depth + 1, visited, budget, path);
                if (r != null) return r;
            }
            return null;
        }
        if (node instanceof Map.Entry) {
            return scanForLootRegistry(((Map.Entry<?, ?>) node).getValue(), depth + 1, visited, budget, path);
        }
        if (node instanceof Iterable) {
            try {
                int n = 0;
                for (Object v : (Iterable<?>) node) {
                    if (n++ > 1024) break;
                    Object r = scanForLootRegistry(v, depth + 1, visited, budget, path);
                    if (r != null) return r;
                }
            } catch (Throwable ignored) {}
            return null;
        }
        if (!cls.getName().startsWith("net.minecraft.")) return null;
        try { Agent.openModule(Agent.instrumentation, cls); } catch (Throwable ignored) {}
        // No-arg methods cuyo retorno sea "containery" (gated en depth 0).
        for (Method m : cls.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (!isLootScanType(m.getReturnType(), depth)) continue;
            Object v;
            try { m.setAccessible(true); v = m.invoke(node); } catch (Throwable t) { continue; }
            Object r = scanForLootRegistry(v, depth + 1, visited, budget, path);
            if (r != null) return r;
        }
        // Campos de tipo containery (sin efectos colaterales; útil para impls que guardan Map de registros).
        for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fields;
            try { fields = k.getDeclaredFields(); } catch (Throwable t) { continue; }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!isLootScanType(f.getType(), depth)) continue;
                Object v;
                try { f.setAccessible(true); v = f.get(node); } catch (Throwable t) { continue; }
                Object r = scanForLootRegistry(v, depth + 1, visited, budget, path);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** ¿El tipo {@code t} vale la pena seguir en la búsqueda del registry de loot? */
    private static boolean isLootScanType(Class<?> t, int depth) {
        if (t == null || t.isPrimitive() || t == void.class || t == String.class
                || t.isArray() || Number.class.isAssignableFrom(t)
                || t == Boolean.class || t == Character.class || CharSequence.class.isAssignableFrom(t)) {
            return false;
        }
        if (isNamed(t, REGISTRY_NAMES)) return true;            // un Registry: candidato directo
        if (depth == 0) return isNamed(t, REGISTRY_ACCESS_NAMES); // en el servidor, solo accesos a registros
        if (isNamed(t, HOLDER_NAMES)) return false;             // evitar back-ref Holder→registro
        if (isNamed(t, LOOT_TABLE_NAMES)) return false;         // no descender DENTRO de una loot table
        if (java.util.Optional.class.isAssignableFrom(t)) return true;
        if (java.util.stream.BaseStream.class.isAssignableFrom(t)) return true;
        if (Map.class.isAssignableFrom(t) || Iterable.class.isAssignableFrom(t)) return true;
        if (isNamed(t, REGISTRY_ACCESS_NAMES)) return true;
        return t.getName().startsWith("net.minecraft.");
    }

    /** Resuelve (y cachea) Registry.getId(LootTable): 1 arg de referencia, retorna Identifier (class_2960). */
    private Method resolveLootGetId(Object reg) {
        if (lootGetId != null) return lootGetId;
        for (Method m : reg.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || ps[0].isPrimitive()) continue;
            if (!isNamed(m.getReturnType(), IDENTIFIER_NAMES)) continue;
            m.setAccessible(true);
            lootGetId = m;
            return m;
        }
        return null;
    }

    // ====================== F4 wcg.refs: query invertida de cascada (recetas) ======================
    // Método CONGELADO del protocolo (protocolo-ipc §wcg.refs; modelo-de-datos §refs): aristas
    // ENTRANTES — "¿qué definiciones ajenas referencian a este ns?" = "¿qué recetas de OTROS mods
    // rompo si desactivo V?". Sobre un índice invertido EN MEMORIA (Map<itemId, List<RefEdge>>)
    // construido una vez reusando el harvest de la sonda; la persistencia SQLite de la tabla refs
    // queda diferida (como index.db). Por ahora SOLO aristas de receta; tags/loot se añaden después
    // sin cambiar la forma del método. Romper = receta ajena con un INGREDIENTE que es item de V.

    private static final int KIND_ITEM_ING = 0; // ingrediente directo: item de V usado tal cual
    private static final int KIND_ITEM_RES = 1; // resultado: item de V producido por la receta
    private static final int KIND_TAG_ING = 2;  // ingrediente vía tag: la receta usa #T y M∈T es de V
    private static final int KIND_LOOT = 3;     // drop de loot: la tabla suelta un item de V (recipe=tableId)

    /** Una arista entrante: la receta {@code recipe} (de {@code owner}) referencia un item de V. */
    private static final class RefEdge {
        final String recipe; final String owner; final int kind; final String tag;
        RefEdge(String recipe, String owner, int kind, String tag) {
            this.recipe = recipe; this.owner = owner; this.kind = kind; this.tag = tag;
        }
    }

    private volatile Map<String, java.util.List<RefEdge>> refsIndex; // itemId → aristas entrantes
    private volatile Map<String, java.util.Set<String>> refsTagMembers; // tagId → todos sus items
    private volatile long refsIndexScanned;
    private volatile long refsLootScanned;
    private volatile long refsIndexBuiltMs;
    private volatile long refsIndexEpoch = Long.MIN_VALUE;
    private volatile String refsIndexSource = "none";     // memory | sqlite | rebuild
    private volatile long refsSqliteLoadedRows;
    private volatile long refsSqliteSavedRows;
    private volatile boolean refsSqliteDriver;
    private volatile boolean refsSqliteHit;
    private volatile boolean refsSqliteMiss;
    private volatile String refsSqlitePath = "";
    private volatile String refsSqliteError = "";

    /**
     * Query invertida de cascada (F4 / {@code wcg.refs}). Construye (lazy) el índice invertido
     * item→recetas-que-lo-usan y responde, para {@code ns}, qué recetas ajenas se romperían al
     * desactivarlo. Misma coreografía que {@link #probeRecipes}: marshalea al hilo del servidor.
     */
    public Map<String, Object> wcgRefs(final String ns, final boolean rebuild) {
        if (ns == null || ns.isEmpty()) {
            return fail("BAD_PARAMS", "wcg.refs requiere ns");
        }
        Object client = Boot.clientInstance;
        if (client == null) {
            return fail("NOT_READY", "la instancia del cliente aún no existe");
        }
        if (server == null) {
            Map<String, Object> r = probe();
            if (!Boolean.TRUE.equals(r.get("ok"))) return r;
        }
        if (server == null) {
            return fail("NOT_READY", "no se pudo descubrir MinecraftServer");
        }
        try {
            ensureItemRegistry();
        } catch (Throwable t) {
            return fail("INTERNAL", "registro de items: " + t);
        }
        if (itemRegistry == null || itemGetId == null) {
            return fail("NOT_READY", "el registro de items aún no está listo");
        }

        final Object candidate = server;
        final Map<String, Object> holder = new HashMap<String, Object>();
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            ((Executor) candidate).execute(new Runnable() {
                public void run() {
                    try {
                        if (rebuild) {
                            refsIndex = null;
                            refsIndexEpoch = Long.MIN_VALUE;
                            refsIndexSource = "rebuild-request";
                        }
                        boolean reused = ensureRefsIndex(!rebuild);
                        queryRefs(ns, reused, holder);
                    } catch (Throwable t) {
                        holder.put("error", String.valueOf(rootCause(t)));
                    } finally {
                        latch.countDown();
                    }
                }
            });
        } catch (Throwable t) {
            return fail("INTERNAL", "no se pudo marshalar al hilo del servidor: " + t);
        }
        boolean done;
        try {
            done = latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return fail("INTERNAL", "interrumpido esperando al hilo del servidor");
        }
        if (!done) {
            return fail("INTERNAL", "el hilo del servidor no respondió en 60s");
        }
        if (Boolean.TRUE.equals(holder.get("ok"))) {
            return holder;
        }
        if (holder.get("code") instanceof String) {
            return fail((String) holder.get("code"), String.valueOf(holder.get("error")));
        }
        return fail("INTERNAL", String.valueOf(holder.get("error")));
    }

    /**
     * Construye el índice invertido si falta o si el epoch cambió. Corre EN el hilo del
     * servidor. Devuelve {@code true} si reusó el caché vigente, {@code false} si reconstruyó.
     */
    private boolean ensureRefsIndex(boolean allowSqliteLoad) throws Exception {
        long epoch = Ledger.INSTANCE.epoch();
        Map<String, java.util.List<RefEdge>> idx = refsIndex;
        if (idx != null && refsIndexEpoch == epoch) {
            refsIndexSource = "memory";
            return true;
        }

        refsSqliteHit = false;
        refsSqliteMiss = false;
        refsSqliteLoadedRows = 0;
        refsSqliteSavedRows = 0;
        refsSqliteError = "";
        if (allowSqliteLoad) {
            RefsIndexSnapshot snap = RefsSqliteStore.load(epoch);
            refsSqliteDriver = snap.driverAvailable;
            refsSqlitePath = snap.path;
            refsSqliteError = snap.error;
            if (snap.ok) {
                refsIndex = snap.index;
                refsTagMembers = snap.tagMembers;
                refsIndexScanned = snap.recipeScanned;
                refsLootScanned = snap.lootScanned;
                refsIndexBuiltMs = 0;
                refsIndexEpoch = epoch;
                refsSqliteLoadedRows = snap.edgeRows + snap.tagRows;
                refsSqliteHit = true;
                refsSqliteMiss = false;
                refsIndexSource = "sqlite";
                return false;
            }
            refsSqliteMiss = snap.driverAvailable;
        }

        long t0 = System.currentTimeMillis();
        Method getRm = resolveGetRecipeManager();
        if (getRm == null) {
            throw new IllegalStateException("no se resolvió getRecipeManager (" + getRecipeManagerSig + ")");
        }
        Object rm = getRm.invoke(server);
        if (rm == null) throw new IllegalStateException("getRecipeManager devolvió null");
        Map<Object, Object> map = Ledger.INSTANCE.liveRecipeMap(rm);
        if (map == null) throw new IllegalStateException("liveRecipeMap null: " + Ledger.INSTANCE.dpLastError());

        // Membresía COMPLETA de tags de item (no solo los de V): la decisión roto-vs-debilitado
        // necesita saber si, al quitar los items de V, el tag aún conserva miembros foráneos.
        // Reusa los helpers ya validados de la sonda (resolveGetTags/materializeTags/...).
        Map<String, java.util.Set<String>> tagMembers = new HashMap<String, java.util.Set<String>>();
        Method gt = resolveGetTags(itemRegistry);
        if (gt != null) {
            java.util.List<Object> els = materializeTags(gt.invoke(itemRegistry));
            if (els != null) {
                for (Object el : els) {
                    String tagId;
                    try { tagId = tagIdOfElement(el); } catch (Throwable t) { tagId = null; }
                    if (tagId == null) continue;
                    java.util.Set<String> members = new java.util.LinkedHashSet<String>();
                    try {
                        Iterable<?> named = namedOfElement(el);
                        if (named != null) harvestTagMembers(named, false, members);
                    } catch (Throwable ignored) {}
                    tagMembers.put(tagId, members);
                }
            }
        }

        Map<String, java.util.List<RefEdge>> built = new HashMap<String, java.util.List<RefEdge>>();
        long scanned = 0;
        for (Map.Entry<Object, Object> e : map.entrySet()) {
            scanned++;
            Object key = e.getKey();
            String recipeId = key == null ? "?" : key.toString();
            String ownerNs = Ledger.INSTANCE.nsOfResourceKey(key);
            Object recipe = recipeValueOf(e.getValue());
            java.util.Set<String> ing = new java.util.LinkedHashSet<String>();
            java.util.Set<String> res = new java.util.LinkedHashSet<String>();
            try {
                harvestItems(recipe, ing, res, false, 0, new java.util.IdentityHashMap<Object, Boolean>());
            } catch (Throwable ignored) {}
            for (String id : ing) addRefEdge(built, id, new RefEdge(recipeId, ownerNs, KIND_ITEM_ING, null));
            for (String id : res) addRefEdge(built, id, new RefEdge(recipeId, ownerNs, KIND_ITEM_RES, null));
            // Aristas vía tag: por cada tag T usado por la receta, colgar (R,T) de cada miembro M de T.
            java.util.Set<String> tagIds = new java.util.LinkedHashSet<String>();
            try {
                harvestTagRefs(recipe, tagIds, 0, new java.util.IdentityHashMap<Object, Boolean>());
            } catch (Throwable ignored) {}
            for (String tid : tagIds) {
                java.util.Set<String> members = tagMembers.get(tid);
                if (members == null) continue;
                for (String m : members) addRefEdge(built, m, new RefEdge(recipeId, ownerNs, KIND_TAG_ING, tid));
            }
        }
        // Aristas de loot: la tabla T (de-riskeada por tx.probeLoot) suelta items; cuelga una
        // arista (T, drop) de CADA item soltado. Owner = ns del id de la tabla. Una loot table
        // ajena que suelta un item de V queda "afectada" al desactivar V (pierde ese drop) — un
        // drop ausente nunca es rotura dura, solo pérdida. Reusa ensureLootRegistry/harvestItems.
        long lootScanned = 0;
        try {
            Object lreg = ensureLootRegistry();
            if (lreg != null) {
                Method lid = resolveLootGetId(lreg);
                for (Object table : (Iterable<?>) lreg) {
                    if (table == null) continue;
                    lootScanned++;
                    String tableId = null;
                    if (lid != null) {
                        try { Object ido = lid.invoke(lreg, table); if (ido != null) tableId = ido.toString(); }
                        catch (Throwable ignored) {}
                    }
                    if (tableId == null) continue;
                    String tableNs = nsOfId(tableId);
                    java.util.Set<String> drops = new java.util.LinkedHashSet<String>();
                    try {
                        harvestItems(table, drops, drops, false, 0, new java.util.IdentityHashMap<Object, Boolean>());
                    } catch (Throwable ignored) {}
                    for (String m : drops) addRefEdge(built, m, new RefEdge(tableId, tableNs, KIND_LOOT, null));
                }
            }
        } catch (Throwable ignored) {}

        refsIndex = built;
        refsTagMembers = tagMembers;
        refsIndexScanned = scanned;
        refsLootScanned = lootScanned;
        refsIndexBuiltMs = System.currentTimeMillis() - t0;
        refsIndexEpoch = epoch;
        refsIndexSource = "rebuild";
        RefsIndexSnapshot snap = new RefsIndexSnapshot();
        snap.index = built;
        snap.tagMembers = tagMembers;
        snap.epoch = epoch;
        snap.recipeScanned = scanned;
        snap.lootScanned = lootScanned;
        snap.builtMs = refsIndexBuiltMs;
        RefsSqliteStore.SaveResult sr = RefsSqliteStore.save(snap);
        refsSqliteDriver = sr.driverAvailable;
        refsSqlitePath = sr.path;
        refsSqliteSavedRows = sr.rows;
        refsSqliteError = sr.error;
        refsSqliteHit = false;
        return false;
    }

    private static final class RefsIndexSnapshot {
        long epoch;
        Map<String, java.util.List<RefEdge>> index = new HashMap<String, java.util.List<RefEdge>>();
        Map<String, java.util.Set<String>> tagMembers = new HashMap<String, java.util.Set<String>>();
        long recipeScanned;
        long lootScanned;
        long builtMs;
        long edgeRows;
        long tagRows;
        boolean ok;
        boolean driverAvailable;
        String path = "";
        String error = "";
    }

    /**
     * SQLite cache rebuildable para wcg.refs. No es fuente de verdad: si falta el
     * driver, el archivo o cualquier lectura falla, el índice se reconstruye desde
     * los registros vivos y se intenta guardar otra vez. El driver vive en el
     * fable-agent.jar como org.sqlite.JDBC; Java no trae SQLite en stdlib.
     */
    private static final class RefsSqliteStore {
        private static final int SCHEMA = 1;
        private static volatile boolean driverChecked;
        private static volatile boolean driverAvailable;
        private static volatile String driverError = "";

        static final class SaveResult {
            boolean driverAvailable;
            String path = "";
            String error = "";
            long rows;
        }

        static RefsIndexSnapshot load(long epoch) {
            RefsIndexSnapshot out = new RefsIndexSnapshot();
            out.epoch = epoch;
            out.path = dbPath();
            if (out.path.length() == 0) { out.error = "mksa.dir no listo"; return out; }
            if (!ensureDriver()) {
                out.driverAvailable = false;
                out.error = driverError;
                return out;
            }
            out.driverAvailable = true;
            Connection c = null;
            try {
                c = DriverManager.getConnection("jdbc:sqlite:" + out.path);
                ensureSchema(c);
                PreparedStatement count = c.prepareStatement(
                        "SELECT COUNT(*) FROM refs_edges WHERE epoch=?");
                count.setLong(1, epoch);
                ResultSet cr = count.executeQuery();
                long rows = cr.next() ? cr.getLong(1) : 0;
                closeQuiet(cr); closeQuiet(count);
                if (rows <= 0) { out.error = "miss"; return out; }

                PreparedStatement meta = c.prepareStatement(
                        "SELECT recipe_scanned, loot_scanned, built_ms FROM refs_meta WHERE epoch=?");
                meta.setLong(1, epoch);
                ResultSet mr = meta.executeQuery();
                if (mr.next()) {
                    out.recipeScanned = mr.getLong(1);
                    out.lootScanned = mr.getLong(2);
                    out.builtMs = mr.getLong(3);
                }
                closeQuiet(mr); closeQuiet(meta);

                PreparedStatement edges = c.prepareStatement(
                        "SELECT dst_id, src_id, src_owner, kind, tag FROM refs_edges WHERE epoch=?");
                edges.setLong(1, epoch);
                ResultSet er = edges.executeQuery();
                while (er.next()) {
                    String dst = er.getString(1);
                    String src = er.getString(2);
                    String owner = er.getString(3);
                    int kind = er.getInt(4);
                    String tag = er.getString(5);
                    addRefEdge(out.index, dst, new RefEdge(src, owner, kind, tag));
                    out.edgeRows++;
                }
                closeQuiet(er); closeQuiet(edges);

                PreparedStatement tags = c.prepareStatement(
                        "SELECT tag_id, item_id FROM refs_tag_members WHERE epoch=?");
                tags.setLong(1, epoch);
                ResultSet tr = tags.executeQuery();
                while (tr.next()) {
                    addToSet(out.tagMembers, tr.getString(1), tr.getString(2));
                    out.tagRows++;
                }
                closeQuiet(tr); closeQuiet(tags);
                out.ok = true;
                return out;
            } catch (Throwable t) {
                out.error = t.getClass().getSimpleName() + ": " + t.getMessage();
                return out;
            } finally {
                closeQuiet(c);
            }
        }

        static SaveResult save(RefsIndexSnapshot snap) {
            SaveResult out = new SaveResult();
            out.path = dbPath();
            if (out.path.length() == 0) { out.error = "mksa.dir no listo"; return out; }
            if (!ensureDriver()) {
                out.driverAvailable = false;
                out.error = driverError;
                return out;
            }
            out.driverAvailable = true;
            Connection c = null;
            try {
                java.io.File f = new java.io.File(out.path);
                java.io.File parent = f.getParentFile();
                if (parent != null) parent.mkdirs();
                c = DriverManager.getConnection("jdbc:sqlite:" + out.path);
                ensureSchema(c);
                c.setAutoCommit(false);
                deleteEpoch(c, snap.epoch);

                PreparedStatement meta = c.prepareStatement(
                        "INSERT OR REPLACE INTO refs_meta(epoch, recipe_scanned, loot_scanned, built_ms, saved_at) VALUES(?,?,?,?,?)");
                meta.setLong(1, snap.epoch);
                meta.setLong(2, snap.recipeScanned);
                meta.setLong(3, snap.lootScanned);
                meta.setLong(4, snap.builtMs);
                meta.setLong(5, System.currentTimeMillis());
                meta.executeUpdate();
                closeQuiet(meta);

                PreparedStatement edge = c.prepareStatement(
                        "INSERT INTO refs_edges(epoch, src_reg, src_id, src_owner, dst_reg, dst_id, kind, tag) VALUES(?,?,?,?,?,?,?,?)");
                long edgeRows = 0;
                for (Map.Entry<String, java.util.List<RefEdge>> e : snap.index.entrySet()) {
                    String dst = e.getKey();
                    java.util.List<RefEdge> l = e.getValue();
                    if (l == null) continue;
                    for (RefEdge r : l) {
                        edge.setLong(1, snap.epoch);
                        edge.setString(2, r.kind == KIND_LOOT ? "loot_table" : "recipe");
                        edge.setString(3, r.recipe);
                        edge.setString(4, r.owner);
                        edge.setString(5, "item");
                        edge.setString(6, dst);
                        edge.setInt(7, r.kind);
                        edge.setString(8, r.tag);
                        edge.addBatch();
                        edgeRows++;
                    }
                }
                edge.executeBatch();
                closeQuiet(edge);

                PreparedStatement tag = c.prepareStatement(
                        "INSERT INTO refs_tag_members(epoch, tag_id, item_id) VALUES(?,?,?)");
                long tagRows = 0;
                for (Map.Entry<String, java.util.Set<String>> e : snap.tagMembers.entrySet()) {
                    java.util.Set<String> members = e.getValue();
                    if (members == null) continue;
                    for (String item : members) {
                        tag.setLong(1, snap.epoch);
                        tag.setString(2, e.getKey());
                        tag.setString(3, item);
                        tag.addBatch();
                        tagRows++;
                    }
                }
                tag.executeBatch();
                closeQuiet(tag);

                PreparedStatement m = c.prepareStatement(
                        "INSERT OR REPLACE INTO meta(key, value) VALUES(?,?)");
                m.setString(1, "schema");
                m.setString(2, String.valueOf(SCHEMA));
                m.executeUpdate();
                m.setString(1, "refs_epoch");
                m.setString(2, Long.toHexString(snap.epoch));
                m.executeUpdate();
                closeQuiet(m);

                c.commit();
                out.rows = edgeRows + tagRows;
                return out;
            } catch (Throwable t) {
                try { if (c != null) c.rollback(); } catch (Throwable ignored) {}
                out.error = t.getClass().getSimpleName() + ": " + t.getMessage();
                return out;
            } finally {
                closeQuiet(c);
            }
        }

        private static void ensureSchema(Connection c) throws SQLException {
            Statement s = c.createStatement();
            s.executeUpdate("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS refs_meta("
                    + "epoch INTEGER PRIMARY KEY, recipe_scanned INTEGER NOT NULL, "
                    + "loot_scanned INTEGER NOT NULL, built_ms INTEGER NOT NULL, saved_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS refs_edges("
                    + "epoch INTEGER NOT NULL, src_reg TEXT NOT NULL, src_id TEXT NOT NULL, "
                    + "src_owner TEXT, dst_reg TEXT NOT NULL, dst_id TEXT NOT NULL, "
                    + "kind INTEGER NOT NULL, tag TEXT)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_refs_edges_epoch_dst ON refs_edges(epoch, dst_id)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS refs_tag_members("
                    + "epoch INTEGER NOT NULL, tag_id TEXT NOT NULL, item_id TEXT NOT NULL, "
                    + "PRIMARY KEY(epoch, tag_id, item_id))");
            closeQuiet(s);
        }

        private static void deleteEpoch(Connection c, long epoch) throws SQLException {
            PreparedStatement p = c.prepareStatement("DELETE FROM refs_edges WHERE epoch=?");
            p.setLong(1, epoch); p.executeUpdate(); closeQuiet(p);
            p = c.prepareStatement("DELETE FROM refs_tag_members WHERE epoch=?");
            p.setLong(1, epoch); p.executeUpdate(); closeQuiet(p);
            p = c.prepareStatement("DELETE FROM refs_meta WHERE epoch=?");
            p.setLong(1, epoch); p.executeUpdate(); closeQuiet(p);
        }

        private static boolean ensureDriver() {
            if (driverChecked) return driverAvailable;
            synchronized (RefsSqliteStore.class) {
                if (driverChecked) return driverAvailable;
                try {
                    DriverManager.getDriver("jdbc:sqlite:");
                    driverAvailable = true;
                    driverError = "";
                } catch (Throwable t) {
                    driverAvailable = false;
                    driverError = "sqlite-jdbc no disponible: " + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : ": " + t.getMessage());
                }
                driverChecked = true;
                return driverAvailable;
            }
        }

        private static String dbPath() {
            try {
                if (Agent.mksaDir == null) return "";
                return Agent.mksaDir.resolve("index.db").toString();
            } catch (Throwable t) {
                return "";
            }
        }

        private static void closeQuiet(AutoCloseable c) {
            if (c == null) return;
            try { c.close(); } catch (Throwable ignored) {}
        }
    }

    private static void addRefEdge(Map<String, java.util.List<RefEdge>> idx, String itemId, RefEdge edge) {
        java.util.List<RefEdge> l = idx.get(itemId);
        if (l == null) { l = new ArrayList<RefEdge>(); idx.put(itemId, l); }
        l.add(edge);
    }

    /** Corre EN el hilo del servidor: responde la cascada entrante para {@code ns} desde el índice. */
    private void queryRefs(String ns, boolean reused, Map<String, Object> holder) {
        long t0 = System.currentTimeMillis();
        Map<String, java.util.List<RefEdge>> idx = refsIndex;

        // byOwner: dueño ajeno → {broken, affectedResult, items de V que referencia}.
        Map<String, Map<String, Object>> byOwner = new java.util.LinkedHashMap<String, Map<String, Object>>();
        Map<String, java.util.Set<String>> ownerItems = new HashMap<String, java.util.Set<String>>();
        Map<String, java.util.Set<String>> brokenByOwner = new HashMap<String, java.util.Set<String>>();
        Map<String, java.util.Set<String>> resultByOwner = new HashMap<String, java.util.Set<String>>();
        Map<String, java.util.Set<String>> weakenedByOwner = new HashMap<String, java.util.Set<String>>();
        java.util.Set<String> brokenRecipes = new java.util.HashSet<String>();
        java.util.Set<String> resultRecipes = new java.util.HashSet<String>();
        java.util.Set<String> weakenedRecipes = new java.util.HashSet<String>();
        java.util.List<Map<String, Object>> edges = new ArrayList<Map<String, Object>>();
        // recipe → {owner, role, refItems} para materializar aristas sin duplicar por item.
        Map<String, Map<String, Object>> edgeByRecipe = new java.util.LinkedHashMap<String, Map<String, Object>>();
        // Aristas vía tag, agregadas por receta (la decisión roto/debilitado es por-receta, no por-arista):
        // recipe → {owner, tags-de-V usados, items de V que aportan a esos tags}.
        Map<String, String> tagOwner = new java.util.LinkedHashMap<String, String>();
        Map<String, java.util.Set<String>> tagsByRecipe = new java.util.LinkedHashMap<String, java.util.Set<String>>();
        Map<String, java.util.Set<String>> tagItemsByRecipe = new java.util.LinkedHashMap<String, java.util.Set<String>>();
        // Aristas de loot: tabla ajena → items de V que suelta. "Afectada" (pierde el drop),
        // nunca rotura dura. tableId → {owner, items de V soltados}.
        Map<String, String> lootOwner = new java.util.LinkedHashMap<String, String>();
        Map<String, java.util.Set<String>> lootItemsByTable = new java.util.LinkedHashMap<String, java.util.Set<String>>();
        Map<String, java.util.Set<String>> lootByOwner = new HashMap<String, java.util.Set<String>>();
        java.util.Set<String> affectedLootTables = new java.util.HashSet<String>();
        long edgesTotal = 0;

        for (Map.Entry<String, java.util.List<RefEdge>> e : idx.entrySet()) {
            String itemId = e.getKey();
            if (!ns.equals(nsOfId(itemId))) continue;          // solo items de V
            for (RefEdge ed : e.getValue()) {
                if (ns.equals(ed.owner)) continue;             // ignorar recetas propias de V
                edgesTotal++;
                String owner = ed.owner == null ? "?" : ed.owner;
                addToSet(ownerItems, owner, itemId);
                if (ed.kind == KIND_TAG_ING) {
                    // V aporta itemId al tag ed.tag, que esta receta ajena usa como ingrediente.
                    tagOwner.put(ed.recipe, owner);
                    addToSet(tagsByRecipe, ed.recipe, ed.tag);
                    addToSet(tagItemsByRecipe, ed.recipe, itemId);
                    continue;
                }
                if (ed.kind == KIND_LOOT) {
                    // Una tabla de loot ajena (ed.recipe = tableId) suelta itemId de V. Afectada
                    // (pierde el drop), nunca rotura dura — un drop perdido no rompe craftabilidad.
                    affectedLootTables.add(ed.recipe);
                    lootOwner.put(ed.recipe, owner);
                    addToSet(lootItemsByTable, ed.recipe, itemId);
                    addToSet(lootByOwner, owner, ed.recipe);
                    continue;
                }
                if (ed.kind == KIND_ITEM_ING) { brokenRecipes.add(ed.recipe); addToSet(brokenByOwner, owner, ed.recipe); }
                else { resultRecipes.add(ed.recipe); addToSet(resultByOwner, owner, ed.recipe); }
                // arista materializada (cap 200), agrupada por receta + rol.
                boolean isIng = ed.kind == KIND_ITEM_ING;
                if (edgeByRecipe.size() < 200 || edgeByRecipe.containsKey(ed.recipe + (isIng ? "#i" : "#r"))) {
                    String ek = ed.recipe + (isIng ? "#i" : "#r");
                    Map<String, Object> em = edgeByRecipe.get(ek);
                    if (em == null) {
                        em = new java.util.LinkedHashMap<String, Object>();
                        em.put("recipe", ed.recipe);
                        em.put("owner", owner);
                        em.put("role", isIng ? "ingredient" : "result");
                        em.put("refItems", new java.util.LinkedHashSet<String>());
                        edgeByRecipe.put(ek, em);
                    }
                    @SuppressWarnings("unchecked")
                    java.util.Set<String> ri = (java.util.Set<String>) em.get("refItems");
                    ri.add(itemId);
                }
            }
        }

        // Post-proceso de aristas vía tag: una receta se ROMPE si algún tag-de-V que usa queda
        // vacío (todos sus miembros eran de V); si no, queda DEBILITADA (el tag sobrevive con
        // miembros foráneos). Precedencia: roto gana — una receta ya rota no se cuenta debilitada.
        java.util.List<Map<String, Object>> tagEdges = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, java.util.Set<String>> e : tagsByRecipe.entrySet()) {
            String recipe = e.getKey();
            String owner = tagOwner.get(recipe);
            java.util.Set<String> tags = e.getValue();
            boolean breaks = false;
            for (String tid : tags) {
                java.util.Set<String> mem = refsTagMembers == null ? null : refsTagMembers.get(tid);
                if (mem == null || mem.isEmpty()) continue; // membresía desconocida/vacía: no prueba vaciado
                boolean emptied = true;
                for (String m : mem) if (!ns.equals(nsOfId(m))) { emptied = false; break; }
                if (emptied) { breaks = true; break; }
            }
            if (breaks) { brokenRecipes.add(recipe); addToSet(brokenByOwner, owner, recipe); }
            if (tagEdges.size() < 200) {
                Map<String, Object> em = new java.util.LinkedHashMap<String, Object>();
                em.put("recipe", recipe);
                em.put("owner", owner);
                em.put("role", "ingredient-via-tag");
                em.put("tags", new ArrayList<String>(tags));
                em.put("breaks", breaks);
                em.put("refItems", new ArrayList<String>(tagItemsByRecipe.get(recipe)));
                tagEdges.add(em);
            }
        }
        // Debilitadas: tag-recetas no rotas (ni por item directo ni por tag vaciado).
        for (String recipe : tagsByRecipe.keySet()) {
            if (brokenRecipes.contains(recipe)) continue;
            weakenedRecipes.add(recipe);
            addToSet(weakenedByOwner, tagOwner.get(recipe), recipe);
        }

        // Tag-como-definición (affectedTags): la dirección que falta — qué tags PIERDEN miembros
        // al desactivar V, independientemente de quién los use. Para cada tag de item que contiene
        // ≥1 miembro de V: membersTotal, victimMembers (los de V), emptied (todos eran de V).
        // Reusa la membresía completa ya cacheada (refsTagMembers), sin barrer recetas.
        java.util.List<Map<String, Object>> affectedTags = new ArrayList<Map<String, Object>>();
        long affectedTagsTotal = 0;
        long emptiedTags = 0;
        if (refsTagMembers != null) {
            for (Map.Entry<String, java.util.Set<String>> e : refsTagMembers.entrySet()) {
                java.util.Set<String> mem = e.getValue();
                if (mem == null || mem.isEmpty()) continue;
                long victim = 0;
                for (String m : mem) if (ns.equals(nsOfId(m))) victim++;
                if (victim == 0) continue;                          // V no aporta a este tag
                boolean emptied = victim == mem.size();
                affectedTagsTotal++;
                if (emptied) emptiedTags++;
                if (affectedTags.size() < 200) {
                    Map<String, Object> tm = new java.util.LinkedHashMap<String, Object>();
                    tm.put("tag", e.getKey());
                    tm.put("membersTotal", (long) mem.size());
                    tm.put("victimMembers", victim);
                    tm.put("emptied", emptied);
                    affectedTags.add(tm);
                }
            }
        }

        // Aristas de loot materializadas (rol "loot-drop", cap 200): tabla ajena → items de V.
        java.util.List<Map<String, Object>> lootEdges = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, java.util.Set<String>> e : lootItemsByTable.entrySet()) {
            if (lootEdges.size() >= 200) break;
            Map<String, Object> em = new java.util.LinkedHashMap<String, Object>();
            em.put("table", e.getKey());
            em.put("owner", lootOwner.get(e.getKey()));
            em.put("role", "loot-drop");
            em.put("refItems", new ArrayList<String>(e.getValue()));
            lootEdges.add(em);
        }

        java.util.Set<String> owners = new java.util.LinkedHashSet<String>(ownerItems.keySet());
        for (String owner : owners) {
            Map<String, Object> info = new java.util.LinkedHashMap<String, Object>();
            java.util.Set<String> bk = brokenByOwner.get(owner);
            java.util.Set<String> rs = resultByOwner.get(owner);
            java.util.Set<String> wk = weakenedByOwner.get(owner);
            java.util.Set<String> lt = lootByOwner.get(owner);
            info.put("broken", (long) (bk == null ? 0 : bk.size()));
            info.put("affectedResult", (long) (rs == null ? 0 : rs.size()));
            info.put("weakened", (long) (wk == null ? 0 : wk.size()));
            info.put("lootAffected", (long) (lt == null ? 0 : lt.size()));
            java.util.List<String> its = new ArrayList<String>(ownerItems.get(owner));
            if (its.size() > 12) its = its.subList(0, 12);
            info.put("items", its);
            byOwner.put(owner, info);
        }

        for (Map<String, Object> em : edgeByRecipe.values()) {
            em.put("refItems", new ArrayList<String>((java.util.Set<String>) em.get("refItems")));
            edges.add(em);
        }
        edges.addAll(tagEdges);
        edges.addAll(lootEdges);

        String thread = Thread.currentThread().getName();
        Map<String, Object> index = new java.util.LinkedHashMap<String, Object>();
        index.put("scanned", refsIndexScanned);
        index.put("builtMs", refsIndexBuiltMs);
        index.put("epoch", Long.toHexString(refsIndexEpoch));
        index.put("reused", reused);
        index.put("keys", (long) idx.size());
        index.put("lootScanned", refsLootScanned);
        index.put("source", refsIndexSource);
        index.put("sqliteDriver", refsSqliteDriver);
        index.put("sqliteHit", refsSqliteHit);
        index.put("sqliteMiss", refsSqliteMiss);
        index.put("sqliteLoadedRows", refsSqliteLoadedRows);
        index.put("sqliteSavedRows", refsSqliteSavedRows);
        index.put("sqlitePath", refsSqlitePath);
        if (refsSqliteError != null && refsSqliteError.length() > 0) {
            index.put("sqliteError", refsSqliteError);
        }

        holder.put("ok", Boolean.TRUE);
        holder.put("ns", ns);
        holder.put("brokenRecipes", (long) brokenRecipes.size());
        holder.put("weakenedRecipes", (long) weakenedRecipes.size());
        holder.put("affectedResultRecipes", (long) resultRecipes.size());
        holder.put("affectedOwners", (long) byOwner.size());
        holder.put("affectedTags", affectedTags);
        holder.put("affectedTagsTotal", affectedTagsTotal);
        holder.put("emptiedTags", emptiedTags);
        holder.put("affectedLootTables", (long) affectedLootTables.size());
        holder.put("byOwner", byOwner);
        holder.put("edges", edges);
        holder.put("edgesTotal", edgesTotal);
        holder.put("index", index);
        holder.put("onServerThread", thread != null && thread.toLowerCase().contains("server"));
        holder.put("durationMs", System.currentTimeMillis() - t0);
    }

    private static void addToSet(Map<String, java.util.Set<String>> m, String k, String v) {
        java.util.Set<String> s = m.get(k);
        if (s == null) { s = new java.util.LinkedHashSet<String>(); m.put(k, s); }
        s.add(v);
    }

    /**
     * Walker reflexivo genérico — el de-risk núcleo. Recorre el grafo de objetos de una
     * receta cosechando ids de item, SIN conocimiento por-tipo-de-receta. Los ItemStack
     * dentro de un Ingredient son ingredientes; los sueltos, resultado. Acotado por
     * {@link #HARVEST_DEPTH_CAP} y {@code visited} (anti-ciclo).
     */
    private void harvestItems(Object node, java.util.Set<String> ingredientIds,
                              java.util.Set<String> resultIds, boolean inIngredient,
                              int depth, java.util.IdentityHashMap<Object, Boolean> visited) {
        if (node == null || depth > HARVEST_DEPTH_CAP) return;
        Class<?> cls = node.getClass();
        if (cls.isPrimitive() || node instanceof CharSequence || node instanceof Number
                || node instanceof Boolean || node instanceof Character || cls.isEnum()) {
            return;
        }
        java.util.Set<String> bucket = inIngredient ? ingredientIds : resultIds;

        // Hojas de item: un Item (class_1792) directo, o un ItemStack (class_1799).
        if (isNamed(cls, ITEM_NAMES)) { addItemId(node, bucket); return; }
        if (isNamed(cls, ITEM_STACK_NAMES)) {
            try {
                Method gi = resolveGetItem(cls);
                if (gi != null) {
                    Object item = gi.invoke(node);
                    if (item != null) addItemId(item, bucket);
                }
            } catch (Throwable ignored) {}
            return;
        }
        // Holder<Item>: NO recursar campos (apunta al registro → explosión). Desenvolver
        // por comportamiento: el no-arg que devuelve un Item es value().
        if (isNamed(cls, HOLDER_NAMES)) { extractHolderItem(node, bucket); return; }
        // Ingredient: cosechar su subárbol como ingredientes (HolderSet/ItemStack[] dentro).
        if (isNamed(cls, INGREDIENT_NAMES)) {
            if (visited.put(node, Boolean.TRUE) != null) return;
            recurseFields(node, cls, ingredientIds, resultIds, true, depth, visited);
            return;
        }

        if (cls.isArray()) {
            if (!cls.getComponentType().isPrimitive()) {
                int len = java.lang.reflect.Array.getLength(node);
                for (int i = 0; i < len; i++) {
                    harvestItems(java.lang.reflect.Array.get(node, i),
                            ingredientIds, resultIds, inIngredient, depth + 1, visited);
                }
            }
            return;
        }
        if (node instanceof java.util.Optional) {
            java.util.Optional<?> o = (java.util.Optional<?>) node;
            if (o.isPresent()) harvestItems(o.get(), ingredientIds, resultIds, inIngredient, depth + 1, visited);
            return;
        }
        if (node instanceof Map) {
            for (Object v : ((Map<?, ?>) node).values())
                harvestItems(v, ingredientIds, resultIds, inIngredient, depth + 1, visited);
            return;
        }
        if (node instanceof Iterable) {
            try {
                for (Object v : (Iterable<?>) node)
                    harvestItems(v, ingredientIds, resultIds, inIngredient, depth + 1, visited);
            } catch (Throwable ignored) {}
            return;
        }
        // POJO/record de Minecraft: recursar sus campos de instancia.
        if (cls.getName().startsWith("net.minecraft.")) {
            if (visited.put(node, Boolean.TRUE) != null) return;
            recurseFields(node, cls, ingredientIds, resultIds, inIngredient, depth, visited);
        }
    }

    private void recurseFields(Object node, Class<?> cls, java.util.Set<String> ingredientIds,
                               java.util.Set<String> resultIds, boolean inIngredient,
                               int depth, java.util.IdentityHashMap<Object, Boolean> visited) {
        try { Agent.openModule(Agent.instrumentation, cls); } catch (Throwable ignored) {}
        for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fields;
            try { fields = k.getDeclaredFields(); } catch (Throwable t) { continue; }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(node);
                    harvestItems(v, ingredientIds, resultIds, inIngredient, depth + 1, visited);
                } catch (Throwable ignored) {}
            }
        }
    }

    /** Añade el id de un Item al bucket (omite minecraft:air). */
    private void addItemId(Object item, java.util.Set<String> bucket) {
        try {
            Object ido = itemGetId.invoke(itemRegistry, item);
            if (ido == null) return;
            String id = ido.toString();
            if (!"minecraft:air".equals(id)) bucket.add(id);
        } catch (Throwable ignored) {}
    }

    /** Desenvuelve un Holder<Item>: el no-arg que devuelve un Item (class_1792) es value(). */
    private void extractHolderItem(Object holder, java.util.Set<String> bucket) {
        for (Method m : holder.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt.isPrimitive() || rt == void.class) continue;
            try {
                Object v = m.invoke(holder);
                if (v != null && isNamed(v.getClass(), ITEM_NAMES)) { addItemId(v, bucket); return; }
            } catch (Throwable ignored) {}
        }
    }

    /** ItemStack.getItem(): no-arg cuyo retorno es un Item (class_1792). Cacheado. */
    private Method resolveGetItem(Class<?> itemStackClass) {
        if (mGetItem != null) return mGetItem;
        for (Method m : itemStackClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (!isNamed(m.getReturnType(), ITEM_NAMES)) continue;
            m.setAccessible(true);
            mGetItem = m;
            return m;
        }
        return null;
    }

    /** RecipeHolder.value(): no-arg cuyo retorno implementa Recipe (class_1860). */
    private Object recipeValueOf(Object holder) {
        try {
            if (mRecipeValue == null) {
                for (Method m : holder.getClass().getMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    if (!isNamed(m.getReturnType(), RECIPE_NAMES)) continue;
                    m.setAccessible(true);
                    mRecipeValue = m;
                    break;
                }
            }
            if (mRecipeValue != null) return mRecipeValue.invoke(holder);
        } catch (Throwable ignored) {}
        return holder; // fallback: walkear el holder entero
    }

    /**
     * MinecraftServer.getRecipeManager(): primero por nombre de CLASE del retorno
     * (class_1863, estable); si falla, por comportamiento (el getter cuyo retorno
     * produce mapa vía {@link Ledger#liveRecipeMap}).
     */
    private Method resolveGetRecipeManager() throws Exception {
        if (mGetRecipeManager != null) return mGetRecipeManager;
        Agent.openModule(Agent.instrumentation, server.getClass());
        Method byName = null;
        for (Method m : server.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt.isPrimitive() || rt == void.class) continue;
            if (isNamed(rt, RECIPE_MANAGER_NAMES)) { byName = m; break; }
        }
        Method picked = byName;
        boolean viaBehavior = false;
        if (picked == null) {
            for (Method m : server.getClass().getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0) continue;
                Class<?> rt = m.getReturnType();
                if (rt.isPrimitive() || rt == void.class || rt == String.class) continue;
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(server);
                    if (v == null) continue;
                    if (Ledger.INSTANCE.liveRecipeMap(v) != null) { picked = m; viaBehavior = true; break; }
                } catch (Throwable ignored) {}
            }
        }
        if (picked != null) {
            picked.setAccessible(true);
            mGetRecipeManager = picked;
            getRecipeManagerSig = (viaBehavior ? "byBehavior:" : "byClass:")
                    + picked.getDeclaringClass().getSimpleName() + "." + picked.getName()
                    + "→" + picked.getReturnType().getSimpleName();
        } else {
            getRecipeManagerSig = "unresolved";
        }
        return picked;
    }

    /** Recorre la jerarquía de tipos de {@code c} buscando cualquier nombre de {@code names}. */
    private static boolean isNamed(Class<?> c, String[] names) {
        if (c == null) return false;
        String n = c.getName();
        for (String w : names) if (w.equals(n)) return true;
        if (isNamed(c.getSuperclass(), names)) return true;
        for (Class<?> i : c.getInterfaces()) if (isNamed(i, names)) return true;
        return false;
    }

    /** Namespace de un id "ns:path", o null. */
    private static String nsOfId(String id) {
        if (id == null) return null;
        int colon = id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : null;
    }

    /** Appende al trace con cap 100; mantiene el JSON del receipt acotado. */
    private static void traceAdd(java.util.List<String> trace, String evt) {
        if (trace.size() < 100) trace.add(evt);
        else if (trace.size() == 100) trace.add("…(truncated)");
    }

    /** Desenvuelve InvocationTargetException y cadenas de causas hasta el throw real. */
    private static Throwable rootCause(Throwable t) {
        Throwable r = t;
        int guard = 0;
        while (r.getCause() != null && r.getCause() != r && guard++ < 16) {
            r = r.getCause();
        }
        return r;
    }

    /** Las primeras {@code n} frames del stack, en una línea, para el trace acotado. */
    private static String topFrames(Throwable t, int n) {
        StackTraceElement[] st = t.getStackTrace();
        if (st == null || st.length == 0) return " @<no-stack>";
        StringBuilder sb = new StringBuilder(" @");
        int lim = Math.min(n, st.length);
        for (int i = 0; i < lim; i++) {
            if (i > 0) sb.append(" <- ");
            sb.append(st[i].getClassName()).append('.').append(st[i].getMethodName())
              .append(':').append(st[i].getLineNumber());
        }
        return sb.toString();
    }

    /** Stack trace completo del throwable como String multilínea. */
    private static String fullStack(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /**
     * Resuelve la clase {@code SerializableChunkData} y sus métodos {@code copyOf} y
     * {@code write} 100% por forma, sin nombres por versión. Carga la clase con ambos
     * nombres (intermediary 1.21.x: {@code class_2852}; mojmap:
     * {@code SerializableChunkData}) sobre el classloader del ServerLevel —
     * el mismo que cargó la clase y por tanto el único que la ve. {@code copyOf} se
     * filtra por: {@code static}, retorno = la propia SCD, 2 args, primer arg
     * asignable desde el ServerLevel actual. El 2º arg es la clase de {@code LevelChunk}
     * (la cacheamos en {@link #levelChunkClass} para la enumeración del corte 2).
     * {@code write} se filtra por: instance, 0 args, retorna {@code CompoundTag},
     * no es accesor de record ({@code comp_*}, intermediary). Cacheado.
     */
    private boolean ensureScd(Object serverLevel) {
        if (mScdCopyOf != null && mScdWrite != null) return true;
        try {
            ClassLoader cl = serverLevel.getClass().getClassLoader();
            Class<?> sl = serverLevel.getClass();
            Class<?> cls = loadByNames(cl,
                    "net.minecraft.class_2852",
                    "net.minecraft.world.level.chunk.storage.SerializableChunkData");
            if (cls == null) return false;
            Class<?> ctag = loadByNames(cl,
                    "net.minecraft.class_2487",
                    "net.minecraft.nbt.CompoundTag");
            if (ctag == null) return false;
            try { Agent.openModule(Agent.instrumentation, cls); } catch (Throwable ignored) {}

            Method cof = null;
            Class<?> lcClass = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != cls) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 2) continue;
                if (!p[0].isAssignableFrom(sl)) continue;
                m.setAccessible(true);
                cof = m;
                lcClass = p[1];
                break;
            }
            if (cof == null) return false;

            Method w = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0) continue;
                if (m.getReturnType() != ctag) continue;
                if (m.getName().startsWith("comp_")) continue;  // record accessor
                m.setAccessible(true);
                w = m;
                break;
            }
            if (w == null) return false;

            scdClass = cls;
            mScdCopyOf = cof;
            mScdWrite = w;
            // lcClass es el param[1] de copyOf = ChunkAccess (class_2791), el supertipo
            // ANCHO. Usarlo como levelChunkClass hace que el guard isInstance (sweep) y
            // la resolución de getChunkNow no distingan LevelChunk (class_2818,
            // serializable) de ImposterProtoChunk (class_2821, canBeSerialized()==false,
            // un wrapper que comparte el supertipo). Eso ataba getChunkNow al método
            // equivocado por "primer match" (§3.18, 5ª ocurrencia del patrón). Atamos la
            // clase ESTRICTA LevelChunk por nombre; si no carga, caemos al param ancho.
            Class<?> strictLc = loadByNames(cl,
                    "net.minecraft.class_2818",
                    "net.minecraft.world.level.chunk.LevelChunk");
            levelChunkClass = strictLc != null ? strictLc : lcClass;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Resuelve {@code ServerLevel.getChunkSource()} → {@code ChunkSource} y, sobre
     * éste, {@code getChunkNow(int, int)} → {@code LevelChunk} (o null si no está
     * cargado). 100% por forma. Estrategia: iterar métodos no-arg del ServerLevel,
     * invocar cada uno, y sobre el resultado buscar un método {@code (int, int) →
     * Object} cuyo retorno sea asignable a (o desde) {@link #levelChunkClass} —
     * supertipo aceptado (p. ej. ChunkAccess). El primero que case se cachea.
     */
    private boolean ensureChunkSource(Object serverLevel) {
        if (mGetChunkSource != null && mGetChunkNow != null) return true;
        try {
            Agent.openModule(Agent.instrumentation, serverLevel.getClass());
        } catch (Throwable ignored) {}
        // Dos fases: primero EXIGIENDO retorno exacto LevelChunk (el getChunkNow real,
        // que devuelve la versión desempaquetada y serializable), descartando hermanos
        // (int,int)→ChunkAccess que devuelven ImposterProtoChunk y reventarían copyOf.
        // Solo si ninguna casa en estricto caemos al match ancho histórico.
        for (boolean strict : new boolean[]{true, false}) {
            try {
                for (Method ms : serverLevel.getClass().getMethods()) {
                    if (Modifier.isStatic(ms.getModifiers())) continue;
                    if (ms.getParameterTypes().length != 0) continue;
                    Class<?> ret = ms.getReturnType();
                    if (ret.isPrimitive() || ret == String.class || ret == void.class) continue;
                    Object cs;
                    try { ms.setAccessible(true); cs = ms.invoke(serverLevel); }
                    catch (Throwable t) { continue; }
                    if (cs == null) continue;
                    Method gcn = findGetChunkNow(cs.getClass(), strict);
                    if (gcn != null) {
                        ms.setAccessible(true);
                        mGetChunkSource = ms;
                        mGetChunkNow = gcn;
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * Método (int, int) → Object cuyo retorno encaja con LevelChunk (= el propio
     * tipo o un supertipo del que sea subclase, p. ej. ChunkAccess). Devuelve el
     * método con {@code setAccessible(true)} o null si no se halla.
     */
    private Method findGetChunkNow(Class<?> chunkSourceClass, boolean strict) {
        try {
            Agent.openModule(Agent.instrumentation, chunkSourceClass);
        } catch (Throwable ignored) {}
        for (Method m : chunkSourceClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 2 || p[0] != int.class || p[1] != int.class) continue;
            Class<?> ret = m.getReturnType();
            if (ret.isPrimitive() || ret == String.class || ret == void.class) continue;
            if (levelChunkClass != null) {
                if (strict) {
                    // EXACTO: solo el método cuyo retorno es LevelChunk (class_2818).
                    // getChunkNow encaja; getChunk(int,int)→ChunkAccess (que puede dar
                    // ImposterProtoChunk) NO — y es ese hermano el que reventaba copyOf.
                    if (ret != levelChunkClass) continue;
                } else if (!ret.isAssignableFrom(levelChunkClass)   // ret supertipo de LevelChunk (acepta ChunkAccess)
                        && !levelChunkClass.isAssignableFrom(ret)) {
                    continue;
                }
            }
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    // ====================== Tier 1 corte 4: reload dirigido de datapacks ======================
    //
    // Tras el sweep (corte 2 — limpia el pasado) y el filter de setBlockState (corte 3 —
    // veta colocaciones futuras), el corte 4 completa el "modo desactivado" sobre las
    // recetas y tags de la víctima: dispara un reload programático con el filtro de
    // datapack armado, de modo que los hooks del transformer (RecipeManager.prepare,
    // TagManagerLoader.build) descarten in-place las entradas con ns víctima ANTES de
    // que la fase apply las publique al estado vivo. Patrón "filter, no des-registrar"
    // (§3.1) llevado al datapack.
    //
    // Reflexión por forma, cacheada (§3.18):
    //   · mReloadResources : instance, 1 arg Collection, retorna CompletableFuture.
    //     Única firma en MinecraftServer con esa shape (verificado en 1.21.11).
    //   · mGetPackRepo     : instance no-arg en MinecraftServer cuyo retorno tiene
    //                        la shape de PackRepository (clase con ≥2 getters de
    //                        Collection<String>).
    //   · mSelectedIds     : de los 2 getters Collection<String> de PackRepository,
    //                        el que devuelve un SUBCONJUNTO del otro (= selectedIds).
    //                        Si son iguales (todos los packs seleccionados), cualquiera
    //                        vale. Desambiguación por COMPORTAMIENTO, no por orden.
    //
    private volatile Method mReloadResources;   // server.reloadResources(Collection<String>):CF<Void>
    private volatile Method mGetPackRepo;       // server.getPackRepository():PackRepository
    private volatile Method mSelectedIds;       // packRepo.getSelectedIds():Collection<String>
    private volatile String reloadHandlesSig = "unresolved"; // diag para verify

    public String reloadHandlesSig() { return reloadHandlesSig; }

    /**
     * Dispara un reload de datapacks con el filtro armado para los namespaces
     * {@code nss}. Tras el reload, RecipeManager y TagManagerLoader no contienen
     * las recetas/tags propias de esos namespaces. Sin tocar el filesystem del mod
     * (los jars siguen donde están); el filtro corre en memoria al construir los
     * payloads. Mismo binomio sweep+filter+reload que dejará al jugador el mod
     * "como si no estuviese", sin reiniciar.
     *
     * Coreografía:
     *   1. Asegurar handle al servidor (puede requerir un probe inicial si aún no
     *      hay handle — air→stone→air sobre el spawn, reversible).
     *   2. Armar {@code Ledger.armDpFilter(nss)} ANTES de disparar el reload, para
     *      que los hooks del transformer vean el set armado cuando MC los invoque.
     *   3. Resolver y cachear (una vez por sesión) los tres handles de reflexión.
     *   4. Marshalear al hilo del servidor solo para invocar reloadResources
     *      (devuelve un CompletableFuture inmediatamente). El hilo del servidor
     *      queda libre para procesar el apply phase del reload.
     *   5. Esperar la CF (off-thread, sin bloquear al server thread).
     *   6. Desarmar (defensivo) y devolver stats: cuántas recetas/tags fueron
     *      filtradas (deltas), si los hooks corrieron (calls), duración.
     */
    public Map<String, Object> tier1DpReload(final java.util.Set<String> nss) {
        if (nss == null || nss.isEmpty()) {
            return fail("BAD_PARAMS", "tier1DpReload requiere namespaces no vacíos");
        }
        Object client = Boot.clientInstance;
        if (client == null) {
            return fail("NOT_READY", "la instancia del cliente aún no existe");
        }
        // Asegurar server handle. Si aún no se descubrió, hacer un probe (reversible).
        if (server == null) {
            Map<String, Object> r = probe();
            if (!Boolean.TRUE.equals(r.get("ok"))) return r;
        }
        if (server == null) {
            return fail("NOT_READY", "no se pudo descubrir MinecraftServer");
        }

        // Snapshot de contadores ANTES de armar — para deltas precisos.
        long baseRecipes = Ledger.INSTANCE.dpFilteredRecipesCount();
        long baseTags = Ledger.INSTANCE.dpFilteredTagsCount();
        long baseRecipeCalls = Ledger.INSTANCE.dpRecipeHookCallsCount();
        long baseTagCalls = Ledger.INSTANCE.dpTagHookCallsCount();
        long t0 = System.currentTimeMillis();

        Ledger.INSTANCE.armDpFilter(nss);
        java.util.Collection<String> packs;
        try {
            if (mReloadResources == null || mGetPackRepo == null || mSelectedIds == null) {
                resolveReloadHandles();
            }
            if (mReloadResources == null) {
                Ledger.INSTANCE.disarmDpFilter();
                return fail("INTERNAL", "no se pudo resolver MinecraftServer.reloadResources");
            }
            if (mGetPackRepo == null || mSelectedIds == null) {
                Ledger.INSTANCE.disarmDpFilter();
                return fail("INTERNAL", "no se pudo resolver PackRepository.getSelectedIds");
            }
            Object repo = mGetPackRepo.invoke(server);
            if (repo == null) {
                Ledger.INSTANCE.disarmDpFilter();
                return fail("INTERNAL", "PackRepository es null");
            }
            Object packsObj = mSelectedIds.invoke(repo);
            if (!(packsObj instanceof java.util.Collection)) {
                Ledger.INSTANCE.disarmDpFilter();
                return fail("INTERNAL", "selectedIds() no devolvió Collection");
            }
            @SuppressWarnings("unchecked")
            java.util.Collection<String> packsCast = (java.util.Collection<String>) packsObj;
            packs = packsCast;

            // Marshalear el kick-off al hilo del servidor (queda libre tras devolver la CF).
            final Object[] cfHolder = new Object[1];
            final Throwable[] err = new Throwable[1];
            final CountDownLatch kicked = new CountDownLatch(1);
            final java.util.Collection<String> packsFinal = packs;
            ((Executor) server).execute(new Runnable() {
                public void run() {
                    try { cfHolder[0] = mReloadResources.invoke(server, packsFinal); }
                    catch (Throwable t) { err[0] = t; }
                    finally { kicked.countDown(); }
                }
            });
            if (!kicked.await(15, TimeUnit.SECONDS)) {
                Ledger.INSTANCE.disarmDpFilter();
                return fail("INTERNAL", "el hilo del servidor no inició el reload en 15s");
            }
            if (err[0] != null) {
                Ledger.INSTANCE.disarmDpFilter();
                return fail("INTERNAL", "reloadResources lanzó: " + err[0]);
            }
            Object cf = cfHolder[0];
            if (cf == null) {
                Ledger.INSTANCE.disarmDpFilter();
                return fail("INTERNAL", "reloadResources devolvió null");
            }
            // Esperar la CF off-thread; el server thread puede procesar el apply phase.
            Method cfGet = cf.getClass().getMethod("get", long.class, TimeUnit.class);
            cfGet.invoke(cf, 120L, TimeUnit.SECONDS);
        } catch (Throwable t) {
            Ledger.INSTANCE.disarmDpFilter();
            return fail("INTERNAL", "tier1DpReload: " + t);
        }
        Ledger.INSTANCE.disarmDpFilter();

        long dt = System.currentTimeMillis() - t0;
        long recipes = Ledger.INSTANCE.dpFilteredRecipesCount() - baseRecipes;
        long tags = Ledger.INSTANCE.dpFilteredTagsCount() - baseTags;
        long rCalls = Ledger.INSTANCE.dpRecipeHookCallsCount() - baseRecipeCalls;
        long tCalls = Ledger.INSTANCE.dpTagHookCallsCount() - baseTagCalls;

        Map<String, Object> r = new java.util.LinkedHashMap<String, Object>();
        r.put("ok", Boolean.TRUE);
        r.put("namespaces", new ArrayList<String>(nss));
        r.put("packs", new ArrayList<String>(packs));
        r.put("recipesFiltered", recipes);
        r.put("tagsFiltered", tags);
        r.put("recipeHookCalls", rCalls);
        r.put("tagHookCalls", tCalls);
        r.put("durationMs", dt);
        r.put("handles", reloadHandlesSig);
        r.put("recipePayloadClass", Ledger.INSTANCE.dpLastRecipePayloadClass());
        r.put("dpLastError", Ledger.INSTANCE.dpLastError());
        return r;
    }

    /**
     * Resuelve y cachea {@link #mReloadResources}, {@link #mGetPackRepo} y
     * {@link #mSelectedIds}, todos por forma + comportamiento. Se llama una vez por
     * sesión bajo lock implícito (volatile + chequeo no estrictamente atómico — el
     * peor caso es resolver dos veces en una carrera, sin efecto observable).
     */
    private void resolveReloadHandles() throws Exception {
        Agent.openModule(Agent.instrumentation, server.getClass());
        Class<?> cfClass = Class.forName("java.util.concurrent.CompletableFuture");

        // 1) reloadResources(Collection):CF
        Method reload = null;
        for (Method m : server.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1) continue;
            if (!java.util.Collection.class.isAssignableFrom(p[0])) continue;
            if (m.getReturnType() != cfClass) continue;
            reload = m;
            break;
        }
        if (reload != null) {
            reload.setAccessible(true);
            mReloadResources = reload;
        }

        // 2) getPackRepository(): cualquier no-arg cuyo retorno parezca PackRepository
        //    (clase class_3283 / PackRepository por nombre estable, O ≥2 getters
        //    no-arg que devuelvan Collection<String>).
        Method getRepo = null;
        Object repo = null;
        for (Method m : server.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt.isPrimitive() || rt == void.class || rt == String.class
                    || rt == java.util.Collection.class || rt == java.util.List.class
                    || rt == java.util.Map.class || rt == java.util.Set.class) continue;
            try {
                m.setAccessible(true);
                Object v = m.invoke(server);
                if (v == null) continue;
                if (looksLikePackRepository(v)) {
                    getRepo = m;
                    repo = v;
                    break;
                }
            } catch (Throwable ignored) {}
        }
        if (getRepo != null) {
            getRepo.setAccessible(true);
            mGetPackRepo = getRepo;
        }

        // 3) selectedIds(): de los 2 getters Collection<String> no-arg de PackRepository,
        //    el que es subconjunto del otro (= selectedIds). Si iguales, cualquiera.
        if (repo != null) {
            Agent.openModule(Agent.instrumentation, repo.getClass());
            List<Method> stringColls = new ArrayList<Method>();
            for (Method m : repo.getClass().getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0) continue;
                if (!java.util.Collection.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(repo);
                    if (!(v instanceof java.util.Collection)) continue;
                    java.util.Collection<?> c = (java.util.Collection<?>) v;
                    if (c.isEmpty()) {
                        stringColls.add(m); // no podemos descartar — podría ser nuestra
                        continue;
                    }
                    if (!(c.iterator().next() instanceof String)) continue;
                    stringColls.add(m);
                } catch (Throwable ignored) {}
            }
            Method picked = null;
            if (stringColls.size() == 1) {
                picked = stringColls.get(0);
            } else if (stringColls.size() >= 2) {
                // Disambiguar por comportamiento: selectedIds ⊆ availableIds.
                Method a = stringColls.get(0);
                Method b = stringColls.get(1);
                try {
                    java.util.Collection<?> ca = (java.util.Collection<?>) a.invoke(repo);
                    java.util.Collection<?> cb = (java.util.Collection<?>) b.invoke(repo);
                    if (ca.size() < cb.size() && cb.containsAll(ca)) picked = a;
                    else if (cb.size() < ca.size() && ca.containsAll(cb)) picked = b;
                    else picked = a; // iguales: cualquiera vale (todos seleccionados)
                } catch (Throwable t) { picked = a; }
            }
            if (picked != null) {
                picked.setAccessible(true);
                mSelectedIds = picked;
            }
        }

        StringBuilder sig = new StringBuilder();
        sig.append("reload=").append(mReloadResources != null ? mReloadResources.getDeclaringClass().getSimpleName() + "." + mReloadResources.getName() : "?");
        sig.append(" repo=").append(mGetPackRepo != null ? mGetPackRepo.getDeclaringClass().getSimpleName() + "." + mGetPackRepo.getName() : "?");
        sig.append(" selectedIds=").append(mSelectedIds != null ? mSelectedIds.getDeclaringClass().getSimpleName() + "." + mSelectedIds.getName() : "?");
        reloadHandlesSig = sig.toString();
    }

    /**
     * Heurística PackRepository — endurecida tras el cuarto caso del patrón §3.18
     * (Tier 1 corte 5, junio 2026: fallo intermitente 1/4 contra Instancia 3 donde
     * {@code resolveReloadHandles} resolvió {@code repo=class_2168} (CommandSourceStack)
     * y {@code selectedIds=getOnlinePlayerNames()}; el reload se disparó con un set
     * de "packs" inválido, los 232 recetas de mcwstairs no se filtraron).
     *
     * <p>Causa raíz: {@link Class#getMethods()} no garantiza orden estable entre
     * invocaciones de JVM (detalle de HotSpot, §3.18); el primer match dependía de
     * suerte. {@code CommandSourceStack} cumple shape ≥2 getters {@code Collection<String>}
     * no-arg ({@code getOnlinePlayerNames} + {@code getAllTeams}), pasaba el heurístico.
     *
     * <p>Fix: añadir un segundo ancla por COMPORTAMIENTO. Además de ≥2 getters
     * {@code Collection<String>} no-arg (que ya tenemos), exigir ≥1 método
     * {@code (String) → boolean} — la firma de {@code PackRepository.isAvailable(String)},
     * estable desde 1.14.4. {@code CommandSourceStack} no tiene nada con esa firma;
     * la combinación es inequívoca sin nombres por versión.
     */
    private static boolean looksLikePackRepository(Object obj) {
        String n = obj.getClass().getName();
        if ("net.minecraft.class_3283".equals(n)
                || "net.minecraft.server.packs.repository.PackRepository".equals(n)) {
            return true;
        }
        int stringColls = 0;
        boolean hasStringToBool = false;
        for (Method m : obj.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] pp = m.getParameterTypes();
            Class<?> rt = m.getReturnType();
            // Ancla 1: ≥2 getters Collection<String> no-arg (selectedIds + availableIds).
            if (pp.length == 0 && java.util.Collection.class.isAssignableFrom(rt)) {
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(obj);
                    if (v instanceof java.util.Collection) {
                        java.util.Collection<?> c = (java.util.Collection<?>) v;
                        if (!c.isEmpty() && c.iterator().next() instanceof String) {
                            stringColls++;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            // Ancla 2: ≥1 método (String) → boolean (isAvailable(String)).
            // CommandSourceStack no expone esta firma; PackRepository sí.
            else if (pp.length == 1 && pp[0] == String.class
                    && (rt == boolean.class || rt == Boolean.class)) {
                hasStringToBool = true;
            }
            if (stringColls >= 2 && hasStringToBool) return true;
        }
        return false;
    }

    /** Carga la primera clase encontrada de la lista usando el classloader dado. */
    private static Class<?> loadByNames(ClassLoader cl, String... names) {
        for (String n : names) {
            try { return Class.forName(n, false, cl); }
            catch (Throwable ignored) {}
        }
        return null;
    }

    // ====================== util ======================

    private static boolean isType(Class<?> c, String[] names) {
        if (c == null) return false;
        String n = c.getName();
        for (String want : names) if (want.equals(n)) return true;
        return false;
    }

    /**
     * Centro del cuboide para forzar dirty: el world spawn (descubierto idénticamente
     * que en attempt()). Devuelve [x, y, z] o [0, 70, 0] como fallback razonable.
     */
    private int[] findSpawnCenter(List<Object> lvls) {
        java.util.List<Object> searchRoots = new ArrayList<Object>();
        if (server != null) searchRoots.add(server);
        searchRoots.addAll(lvls);
        for (Object r : searchRoots) {
            try {
                Object sp = spawnPos(r);
                if (sp != null) {
                    long L = (Long) mAsLong.invoke(sp);
                    int x = (int) (L >> 38);
                    int y = (int) ((L << 52) >> 52);
                    int z = (int) ((L << 26) >> 38);
                    return new int[] { x, y, z };
                }
            } catch (Throwable ignored) {}
        }
        return new int[] { 0, 70, 0 };
    }

    /** Lee Bridge.stats()[0] (= written, contador del write hook). 0 si Bridge no disponible. */
    private static long readWritten() {
        try {
            Class<?> b = Class.forName("dev.mksa.bridge.Bridge");
            long[] s = (long[]) b.getMethod("stats").invoke(null);
            return s != null && s.length > 0 ? s[0] : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static Map<String, Object> fail(String code, String error) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("ok", Boolean.FALSE);
        m.put("code", code);
        m.put("error", error);
        return m;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tier 1 corte 5 — envoltorio transaccional con TxReceipt explícito
    // ════════════════════════════════════════════════════════════════════════
    //
    // Orquesta sweep+veto+dpReload como UNA operación atómica desde el punto de
    // vista de la API (un solo IPC, un solo veredicto, una sola duración medida),
    // dejando un objeto de estado explícito (TxReceipt) que documenta cada paso.
    // El receipt es el contrato de salida — sirve a UI futura, historial, debug,
    // preview, todo sin tocar más arquitectura.
    //
    // Política de errores (decidida con el usuario):
    //   · Antes de mutar el mundo (vetoArm/snapshot fallan) → status FAILED.
    //   · Tras mutar pero antes de terminar (sweep OK, dpReload falla) → status
    //     PARTIAL, error apunta al paso roto, NO rollback automático. El usuario
    //     llama tier1Enable si quiere revertir.
    //
    // Orden de pasos en disable (veto-primero, decidido con el usuario):
    //   vetoArm → snapshot → sweep → dpReload. El veto es coste 0 (volatile Set)
    //   y elimina la race fina de "jugador coloca entre snapshot y sweep".

    /**
     * Envoltorio transaccional del disable Tier 1 in-process. Orquesta los tres
     * mecanismos probados (filter de setBlockState, sweep zero-IO, reload dirigido
     * de datapacks) como una sola operación y devuelve un {@link TxJournal.TxReceipt}
     * que documenta el resultado completo.
     *
     * @param ns           namespace víctima (e.g. "mcwstairs")
     * @param restorePath  ruta donde escribir el archivo MKSAR1 de captura
     * @return Map JSON-ready del receipt (forma estable, ver TxJournal.TxReceipt)
     */
    public Map<String, Object> tier1Disable(final String ns, final String restorePath) {
        if (ns == null || ns.isEmpty()) {
            return fail("BAD_PARAMS", "tier1Disable requiere ns");
        }
        if (restorePath == null || restorePath.isEmpty()) {
            return fail("BAD_PARAMS", "tier1Disable requiere restorePath");
        }
        long txId = TxJournal.INSTANCE.nextId();
        TxJournal.TxReceipt rcpt = new TxJournal.TxReceipt(
                txId, TxJournal.Op.disable, ns, Ledger.INSTANCE.hashHex());

        // ── paso 1: vetoArm. Antes de snapshot/sweep para eliminar race finita
        //    "jugador coloca entre snapshot y sweep". Coste 0 (volatile Set).
        if (!txStepVetoArm(rcpt, ns)) {
            return finalizeAndStore(rcpt, TxJournal.Status.FAILED, restorePath);
        }

        // ── paso 1b: tabVeto (corte tabs). Vacía la pestaña de inventario creativo
        //    del namespace y fuerza el rebuild — vainilla oculta sola el botón de una
        //    pestaña CATEGORY vacía. Best-effort: nunca bloquea la secuencia probada.
        txStepTabVeto(rcpt, ns);

        // ── paso 2: snapshot ATÓMICO de presencia en una sola pasada sobre byChunk.
        //    {blocks, chunks, chunkKeys} se calculan juntos: si counts() y
        //    chunksWithNs() se llamasen en dos lecturas separadas, un autosave +
        //    scan() entre ellas podría desincronizarlas. La lista chunkKeys es la
        //    fuente de verdad para el sweep (§3.17), pasada explícitamente.
        @SuppressWarnings("unchecked")
        Map<String, Object> snap = Ledger.INSTANCE.snapshotPresence(ns);
        long snapBlocks = ((Number) snap.get("blocks")).longValue();
        long snapChunks = ((Number) snap.get("chunks")).longValue();
        @SuppressWarnings("unchecked")
        java.util.List<Long> snapChunkKeys = (java.util.List<Long>) snap.get("chunkKeys");
        rcpt.snapshot.put("blocks", snapBlocks);
        rcpt.snapshot.put("chunks", snapChunks);
        rcpt.snapshot.put("items", 0L);   // playerdata scan: 0 hasta que se enganche en T1
        rcpt.snapshot.put("chunkKeys", chunkKeysToStrings(snapChunkKeys));

        Map<String, Object> snapDetail = new java.util.LinkedHashMap<String, Object>();
        snapDetail.put("blocks", snapBlocks);
        snapDetail.put("chunks", snapChunks);
        snapDetail.put("items", 0L);
        snapDetail.put("chunkKeys", chunkKeysToStrings(snapChunkKeys));
        rcpt.addStep("snapshot", TxJournal.Status.OK, 0L, snapDetail);

        // ── paso 3: sweep — recibe los chunkKeys del snapshot, NO los relee de
        //    byChunk en el server thread (fix race corte 5, junio 2026).
        if (!txStepSweep(rcpt, ns, restorePath, snapChunkKeys)) {
            txRollbackVeto(rcpt);
            return finalizeAndStore(rcpt, TxJournal.Status.FAILED, restorePath);
        }
        rcpt.restorePath = restorePath;

        // ── paso 3b/3c: items vivos (corte 6). El sweep de bloques captura/vacía
        //    contenido del mundo; aquí cerramos el binomio para mochilas y cofres.
        //    No-blockers: si falla, mundo de bloques ya está mutado → PARTIAL.
        txStepItemSweep(rcpt, ns, restorePath);
        txStepContainerSweep(rcpt, ns, restorePath);

        // ── paso 4: dpReload. Si falla, mundo ya está mutado → PARTIAL, sin
        //    rollback automático. El usuario decide si llama tier1Enable.
        if (!txStepDpReload(rcpt, ns)) {
            return finalizeAndStore(rcpt, TxJournal.Status.PARTIAL, restorePath);
        }

        return finalizeAndStore(rcpt, TxJournal.Status.OK, restorePath);
    }

    /**
     * Envoltorio transaccional del enable Tier 1 in-process. Alcance corte 5
     * (acordado con el usuario): solo {@code vetoDisarm + dpReload sin filtro}.
     * La <b>restitución de bloques al mundo vivo</b> (re-inyección por handle,
     * NBT→BlockState) queda para el <b>corte 5b</b> — es un de-risk nuevo, no
     * plumbing, y merece su propio corte por la misma razón que el relaunch
     * separó corte 4 (bloques) y corte 4b (items).
     *
     * Por tanto, en este corte enable revierte los efectos del disable que NO
     * tocan el mundo (registro vivo de recetas/tags y filtro de placement).
     * El receipt es honesto: {@code steps} no incluye {@code restore}.
     *
     * @param ns  namespace víctima (e.g. "mcwstairs")
     * @param restorePath  archivo MKSAR1 escrito por el disable; si null/vacío el
     *     paso de restitución se registra {@code skipped} (compat con callers viejos
     *     que solo quieren desarmar veto + recargar datapacks).
     */
    public Map<String, Object> tier1Enable(final String ns, final String restorePath) {
        if (ns == null || ns.isEmpty()) {
            return fail("BAD_PARAMS", "tier1Enable requiere ns");
        }
        long txId = TxJournal.INSTANCE.nextId();
        TxJournal.TxReceipt rcpt = new TxJournal.TxReceipt(
                txId, TxJournal.Op.enable, ns, Ledger.INSTANCE.hashHex());

        // Snapshot atómico al inicio de enable: lo que queda en el mundo ahora
        // (debería ser 0 tras un disable correcto, pero documentarlo es útil para
        // debug). Misma pasada única sobre byChunk que el disable.
        Map<String, Object> snap = Ledger.INSTANCE.snapshotPresence(ns);
        long snapBlocks = ((Number) snap.get("blocks")).longValue();
        long snapChunks = ((Number) snap.get("chunks")).longValue();
        rcpt.snapshot.put("blocks", snapBlocks);
        rcpt.snapshot.put("chunks", snapChunks);
        rcpt.snapshot.put("items", 0L);

        // ── paso 1: vetoDisarm. Idempotente, siempre OK. DEBE ir antes de la
        //    restitución: el setBlock vivo del paso 2 atraviesa LevelChunk.setBlockState,
        //    el mismo entrypoint que el hook del corte 3 anularía a null si el veto
        //    siguiera armado para la víctima. Desarmar primero deja pasar la inyección.
        long t0 = System.currentTimeMillis();
        Map<String, Object> dis = Ledger.INSTANCE.disarmBlockVeto();
        long ms = System.currentTimeMillis() - t0;
        Map<String, Object> d1 = new java.util.LinkedHashMap<String, Object>();
        d1.put("disarmed", Boolean.TRUE);
        d1.put("priorCount", dis.get("count"));
        rcpt.addStep("vetoDisarm", TxJournal.Status.OK, ms, d1);

        // ── paso 1b: tabRestore (corte tabs). Desarma el veto de pestaña y fuerza el
        //    rebuild — el botón reaparece con sus items reales. Best-effort: nunca
        //    bloquea la secuencia probada.
        txStepTabRestore(rcpt, ns);

        // ── paso 2: restitución viva (corte 5b). Reconstruye cada BlockState desde
        //    el NBT capturado y lo re-inyecta en el mundo vivo (solo si la celda
        //    sigue en aire, §7). Si falla DURO tras haber mutado parte del mundo →
        //    PARTIAL (semántica del disable: sin rollback automático, el usuario
        //    decide). Si restorePath vacío, el paso se registra skipped (no falla).
        if (!txStepRestitution(rcpt, ns, restorePath)) {
            return finalizeAndStore(rcpt, TxJournal.Status.PARTIAL, restorePath);
        }
        if (restorePath != null && !restorePath.isEmpty()) {
            rcpt.restorePath = restorePath;
        }

        // ── paso 2b/2c: restituir items vivos (corte 6). Mismo archivo MKSAR1.
        //    Política de overflow: slot original → primer libre del mismo
        //    contenedor → drop al suelo (inv) / lost (cofre).
        if (restorePath != null && !restorePath.isEmpty()) {
            txStepItemRestore(rcpt, ns, restorePath);
            txStepContainerRestore(rcpt, ns, restorePath);
        }

        // ── paso 3: dpReload SIN filtro armado. MC reconstruye recetas/tags
        //    desde los packs sin descartar nada — el reload limpio repuebla
        //    el RecipeManager / TagManager con todo lo que el filesystem provee.
        if (!txStepDpReloadClean(rcpt)) {
            return finalizeAndStore(rcpt, TxJournal.Status.PARTIAL, restorePath);
        }

        return finalizeAndStore(rcpt, TxJournal.Status.OK, restorePath);
    }

    // ── helpers internos del envoltorio transaccional ─────────────────────

    private boolean txStepVetoArm(TxJournal.TxReceipt rcpt, String ns) {
        long t0 = System.currentTimeMillis();
        try {
            java.util.Set<String> s = new java.util.HashSet<String>();
            s.add(ns);
            Map<String, Object> r = Ledger.INSTANCE.armBlockVeto(s);
            long ms = System.currentTimeMillis() - t0;
            Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
            d.put("armed", Boolean.TRUE);
            d.put("count", r.get("count"));
            rcpt.addStep("vetoArm", TxJournal.Status.OK, ms, d);
            return true;
        } catch (Throwable t) {
            long ms = System.currentTimeMillis() - t0;
            Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
            d.put("error", String.valueOf(t));
            rcpt.addStep("vetoArm", TxJournal.Status.FAILED, ms, d);
            rcpt.recordError("vetoArm", t);
            return false;
        }
    }

    /**
     * Corte tabs: arma el veto de pestaña de inventario creativo y fuerza el rebuild
     * de las pestañas ya construidas. Best-effort — siempre devuelve {@code true} (no
     * bloquea el resto de la secuencia Tier 1 ya probada, mismo espíritu que
     * {@link #txStepItemSweep}/{@link #txStepContainerSweep} tratando NOT_READY como
     * no-blocking). Cualquier excepción se registra {@code skipped} en el step.
     */
    private boolean txStepTabVeto(TxJournal.TxReceipt rcpt, String ns) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
        try {
            java.util.Set<String> s = new java.util.HashSet<String>();
            s.add(ns);
            Map<String, Object> r = Ledger.INSTANCE.armTabVeto(s);
            Ledger.INSTANCE.rebuildAllCreativeTabs();
            d.put("armed", Boolean.TRUE);
            d.put("count", r.get("count"));
            d.put("paramsCaptured", Ledger.INSTANCE.tabParamsCapturedCount());
            d.put("rebuildCalls", Ledger.INSTANCE.tabRebuildCallsCount());
            d.put("tabsSeen", Ledger.INSTANCE.tabRebuildTabsSeenCount());
            d.put("invoked", Ledger.INSTANCE.tabRebuildInvokedCount());
            d.put("invokeErrors", Ledger.INSTANCE.tabRebuildInvokeErrorsCount());
            d.put("skipReason", Ledger.INSTANCE.tabRebuildLastSkipReason());
            d.put("lastError", Ledger.INSTANCE.tabRebuildLastError());
        } catch (Throwable t) {
            d.put("skipped", Boolean.TRUE);
            d.put("error", String.valueOf(t));
        }
        long ms = System.currentTimeMillis() - t0;
        rcpt.addStep("tabVeto", TxJournal.Status.OK, ms, d);
        return true;
    }

    /**
     * Corte tabs: desarma el veto de pestaña y fuerza el rebuild (el botón reaparece
     * con sus items reales). Best-effort, mismo contrato que {@link #txStepTabVeto}.
     */
    private boolean txStepTabRestore(TxJournal.TxReceipt rcpt, String ns) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
        try {
            Ledger.INSTANCE.disarmTabVeto();
            Ledger.INSTANCE.rebuildAllCreativeTabs();
            d.put("disarmed", Boolean.TRUE);
            d.put("paramsCaptured", Ledger.INSTANCE.tabParamsCapturedCount());
            d.put("rebuildCalls", Ledger.INSTANCE.tabRebuildCallsCount());
            d.put("tabsSeen", Ledger.INSTANCE.tabRebuildTabsSeenCount());
            d.put("invoked", Ledger.INSTANCE.tabRebuildInvokedCount());
            d.put("invokeErrors", Ledger.INSTANCE.tabRebuildInvokeErrorsCount());
            d.put("skipReason", Ledger.INSTANCE.tabRebuildLastSkipReason());
            d.put("lastError", Ledger.INSTANCE.tabRebuildLastError());
        } catch (Throwable t) {
            d.put("skipped", Boolean.TRUE);
            d.put("error", String.valueOf(t));
        }
        long ms = System.currentTimeMillis() - t0;
        rcpt.addStep("tabRestore", TxJournal.Status.OK, ms, d);
        return true;
    }

    private boolean txStepSweep(TxJournal.TxReceipt rcpt, String ns, String restorePath,
                                List<Long> snapChunkKeys) {
        t1log("sweep START ns=" + ns + " restorePath=" + (restorePath == null ? "<null>" : restorePath)
                + " snapChunks=" + (snapChunkKeys == null ? "<null>" : snapChunkKeys.size()));
        long t0 = System.currentTimeMillis();
        Map<String, Object> r = tier1Sweep(ns, restorePath, false, snapChunkKeys);
        long ms = System.currentTimeMillis() - t0;
        if (!Boolean.TRUE.equals(r.get("ok"))) {
            t1log("sweep FAILED code=" + r.get("code") + " error=" + r.get("error") + " (" + ms + "ms)");
            Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
            d.put("code", r.get("code"));
            d.put("error", r.get("error"));
            rcpt.addStep("sweep", TxJournal.Status.FAILED, ms, d);
            Map<String, Object> e = new java.util.LinkedHashMap<String, Object>();
            e.put("step", "sweep");
            e.put("class", String.valueOf(r.get("code")));
            e.put("message", String.valueOf(r.get("error")));
            rcpt.error = e;
            return false;
        }
        t1log("sweep OK captured=" + r.get("capturedBlocks") + " swept=" + r.get("sweptBlocks")
                + " residual=" + r.get("residualBlocks") + " chunksAffected=" + r.get("chunksAffected")
                + " sweepErrors=" + r.get("sweepErrors") + " chunkKeysSource=" + r.get("chunkKeysSource")
                + " forceDirtyOk=" + r.get("forceDirtyOk") + " forceDirtyFailed=" + r.get("forceDirtyFailed")
                + " (" + ms + "ms)");
        Object st = r.get("sweepTrace");
        if (st instanceof java.util.List) {
            java.util.List<?> stl = (java.util.List<?>) st;
            int cap = Math.min(stl.size(), 60);
            for (int i = 0; i < cap; i++) t1log("  sweepTrace[" + i + "] " + stl.get(i));
            if (stl.size() > cap) t1log("  sweepTrace ... (" + (stl.size() - cap) + " más)");
        }
        Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
        // Métricas nativas del sweep (subset relevante; receipt no debe traer
        // todo el diag interno — eso sigue en ledger.stats si se necesita).
        d.put("captured", r.get("capturedBlocks"));
        d.put("swept", r.get("sweptBlocks"));
        d.put("residual", r.get("residualBlocks"));
        d.put("chunksAffected", r.get("chunksAffected"));
        d.put("sweepErrors", r.get("sweepErrors"));
        d.put("chunkKeysSource", r.get("chunkKeysSource"));   // "snapshot" en el flow normal
        // Breadcrumbs del fix force-dirty real (junio 2026):
        d.put("forceDirtyAttempts", r.get("forceDirtyAttempts"));
        d.put("forceDirtyOk", r.get("forceDirtyOk"));
        d.put("forceDirtyFailed", r.get("forceDirtyFailed"));
        d.put("forceDirtyLastY", r.get("forceDirtyLastY"));
        d.put("sweepTrace", r.get("sweepTrace"));
        d.put("restorePath", restorePath);
        rcpt.addStep("sweep", TxJournal.Status.OK, ms, d);
        return true;
    }

    /**
     * Paso transaccional de restitución viva (corte 5b). Espejo exacto de
     * {@link #txStepSweep}: mide tiempo, llama {@link #tier1Restitution}, desempaqueta
     * las métricas relevantes al detail del step. Si {@code restorePath} es vacío,
     * registra el paso como {@code skipped} (OK, sin tocar el mundo). Si la
     * restitución falla DURO devuelve false → el caller hace PARTIAL.
     */
    private boolean txStepRestitution(TxJournal.TxReceipt rcpt, String ns, String restorePath) {
        t1log("restitution START ns=" + ns + " restorePath=" + (restorePath == null ? "<null>" : restorePath));
        if (restorePath == null || restorePath.isEmpty()) {
            t1log("restitution SKIP sin restorePath (no se restituye nada)");
            Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
            d.put("skipped", Boolean.TRUE);
            d.put("reason", "sin restorePath");
            rcpt.addStep("restitution", TxJournal.Status.OK, 0L, d);
            return true;
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> r = tier1Restitution(ns, restorePath);
        long ms = System.currentTimeMillis() - t0;
        if (!Boolean.TRUE.equals(r.get("ok"))) {
            t1log("restitution FAILED code=" + r.get("code") + " error=" + r.get("error") + " (" + ms + "ms)");
            Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
            d.put("code", r.get("code"));
            d.put("error", r.get("error"));
            rcpt.addStep("restitution", TxJournal.Status.FAILED, ms, d);
            Map<String, Object> e = new java.util.LinkedHashMap<String, Object>();
            e.put("step", "restitution");
            e.put("class", String.valueOf(r.get("code")));
            e.put("message", String.valueOf(r.get("error")));
            rcpt.error = e;
            return false;
        }
        t1log("restitution OK captured=" + r.get("captured") + " restored=" + r.get("restored")
                + " conflicts=" + r.get("conflicts") + " misses=" + r.get("misses")
                + " chunksAffected=" + r.get("chunksAffected")
                + " restitutionErrors=" + r.get("restitutionErrors") + " (" + ms + "ms)");
        Object rt = r.get("restTrace");
        if (rt instanceof java.util.List) {
            java.util.List<?> rtl = (java.util.List<?>) rt;
            int cap = Math.min(rtl.size(), 40);
            for (int i = 0; i < cap; i++) t1log("  restTrace[" + i + "] " + rtl.get(i));
            if (rtl.size() > cap) t1log("  restTrace ... (" + (rtl.size() - cap) + " más)");
        }
        Object re = r.get("restitutionErrors");
        if (re instanceof java.util.List) {
            for (Object x : (java.util.List<?>) re) t1log("  restError " + x);
        }
        Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
        d.put("captured", r.get("captured"));
        d.put("restored", r.get("restored"));
        d.put("conflicts", r.get("conflicts"));
        d.put("misses", r.get("misses"));
        d.put("chunksAffected", r.get("chunksAffected"));
        d.put("restitutionErrors", r.get("restitutionErrors"));
        d.put("restTrace", r.get("restTrace"));
        d.put("restorePath", restorePath);
        rcpt.addStep("restitution", TxJournal.Status.OK, ms, d);
        return true;
    }

    /** Convierte una lista de claves de chunk empaquetadas a strings "cx,cz" para diag. */
    private static java.util.List<String> chunkKeysToStrings(java.util.List<Long> keys) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (keys == null) return out;
        for (Long k : keys) {
            if (k == null) continue;
            int cx = (int) (k.longValue() >> 32);
            int cz = (int) (k.longValue() & 0xffffffffL);
            out.add(cx + "," + cz);
        }
        return out;
    }

    private boolean txStepDpReload(TxJournal.TxReceipt rcpt, String ns) {
        long t0 = System.currentTimeMillis();
        java.util.Set<String> nss = new java.util.HashSet<String>();
        nss.add(ns);
        Map<String, Object> r = tier1DpReload(nss);
        long ms = System.currentTimeMillis() - t0;
        if (!Boolean.TRUE.equals(r.get("ok"))) {
            Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
            d.put("code", r.get("code"));
            d.put("error", r.get("error"));
            rcpt.addStep("dpReload", TxJournal.Status.FAILED, ms, d);
            Map<String, Object> e = new java.util.LinkedHashMap<String, Object>();
            e.put("step", "dpReload");
            e.put("class", String.valueOf(r.get("code")));
            e.put("message", String.valueOf(r.get("error")));
            rcpt.error = e;
            return false;
        }
        Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
        d.put("recipesFiltered", r.get("recipesFiltered"));
        d.put("tagsFiltered", r.get("tagsFiltered"));
        d.put("recipeHookCalls", r.get("recipeHookCalls"));
        d.put("tagHookCalls", r.get("tagHookCalls"));
        d.put("handlesSig", r.get("handles"));
        rcpt.addStep("dpReload", TxJournal.Status.OK, ms, d);
        return true;
    }

    /**
     * Versión de dpReload SIN filtro armado, para el {@code enable}. Reusa la
     * misma maquinaria de {@link #tier1DpReload(java.util.Set)} (resolver handles,
     * marshalear al server thread, esperar CF) pero deja {@link Ledger#armDpFilter}
     * sin tocar, así MC reconstruye recetas/tags completos desde los packs.
     */
    private boolean txStepDpReloadClean(TxJournal.TxReceipt rcpt) {
        long t0 = System.currentTimeMillis();
        Object client = Boot.clientInstance;
        if (client == null) {
            rcpt.addStep("dpReload", TxJournal.Status.FAILED, 0L,
                    detailErr("NOT_READY", "la instancia del cliente aún no existe"));
            rcpt.error = errMap("dpReload", "NOT_READY", "client null");
            return false;
        }
        if (server == null) {
            Map<String, Object> p = probe();
            if (!Boolean.TRUE.equals(p.get("ok"))) {
                rcpt.addStep("dpReload", TxJournal.Status.FAILED, 0L,
                        detailErr(String.valueOf(p.get("code")), String.valueOf(p.get("error"))));
                rcpt.error = errMap("dpReload", String.valueOf(p.get("code")), String.valueOf(p.get("error")));
                return false;
            }
        }
        try {
            if (mReloadResources == null || mGetPackRepo == null || mSelectedIds == null) {
                resolveReloadHandles();
            }
            if (mReloadResources == null || mGetPackRepo == null || mSelectedIds == null) {
                long ms = System.currentTimeMillis() - t0;
                rcpt.addStep("dpReload", TxJournal.Status.FAILED, ms,
                        detailErr("INTERNAL", "no se pudieron resolver handles de reload"));
                rcpt.error = errMap("dpReload", "INTERNAL", "handles");
                return false;
            }
            Object repo = mGetPackRepo.invoke(server);
            if (repo == null) {
                long ms = System.currentTimeMillis() - t0;
                rcpt.addStep("dpReload", TxJournal.Status.FAILED, ms,
                        detailErr("INTERNAL", "PackRepository es null"));
                rcpt.error = errMap("dpReload", "INTERNAL", "repo null");
                return false;
            }
            Object packsObj = mSelectedIds.invoke(repo);
            if (!(packsObj instanceof java.util.Collection)) {
                long ms = System.currentTimeMillis() - t0;
                rcpt.addStep("dpReload", TxJournal.Status.FAILED, ms,
                        detailErr("INTERNAL", "selectedIds() no devolvió Collection"));
                rcpt.error = errMap("dpReload", "INTERNAL", "selectedIds");
                return false;
            }
            @SuppressWarnings("unchecked")
            final java.util.Collection<String> packsFinal = (java.util.Collection<String>) packsObj;

            final Object[] cfHolder = new Object[1];
            final Throwable[] err = new Throwable[1];
            final CountDownLatch kicked = new CountDownLatch(1);
            ((Executor) server).execute(new Runnable() {
                public void run() {
                    try { cfHolder[0] = mReloadResources.invoke(server, packsFinal); }
                    catch (Throwable t) { err[0] = t; }
                    finally { kicked.countDown(); }
                }
            });
            if (!kicked.await(15, TimeUnit.SECONDS)) {
                long ms = System.currentTimeMillis() - t0;
                rcpt.addStep("dpReload", TxJournal.Status.FAILED, ms,
                        detailErr("INTERNAL", "server thread no inició reload en 15s"));
                rcpt.error = errMap("dpReload", "INTERNAL", "marshal timeout");
                return false;
            }
            if (err[0] != null) {
                long ms = System.currentTimeMillis() - t0;
                rcpt.addStep("dpReload", TxJournal.Status.FAILED, ms,
                        detailErr("INTERNAL", "reloadResources lanzó: " + err[0]));
                rcpt.recordError("dpReload", err[0]);
                return false;
            }
            Object cf = cfHolder[0];
            if (cf == null) {
                long ms = System.currentTimeMillis() - t0;
                rcpt.addStep("dpReload", TxJournal.Status.FAILED, ms,
                        detailErr("INTERNAL", "reloadResources devolvió null"));
                rcpt.error = errMap("dpReload", "INTERNAL", "cf null");
                return false;
            }
            Method cfGet = cf.getClass().getMethod("get", long.class, TimeUnit.class);
            cfGet.invoke(cf, 120L, TimeUnit.SECONDS);

            long ms = System.currentTimeMillis() - t0;
            Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
            d.put("packs", new ArrayList<String>(packsFinal));
            d.put("handlesSig", reloadHandlesSig);
            d.put("filtered", Boolean.FALSE);   // clean reload — no filter armed
            rcpt.addStep("dpReload", TxJournal.Status.OK, ms, d);
            return true;
        } catch (Throwable t) {
            long ms = System.currentTimeMillis() - t0;
            rcpt.addStep("dpReload", TxJournal.Status.FAILED, ms,
                    detailErr("INTERNAL", "dpReloadClean: " + t));
            rcpt.recordError("dpReload", t);
            return false;
        }
    }

    private void txRollbackVeto(TxJournal.TxReceipt rcpt) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> r = Ledger.INSTANCE.disarmBlockVeto();
        long ms = System.currentTimeMillis() - t0;
        Map<String, Object> rb = new java.util.LinkedHashMap<String, Object>();
        rb.put("attempted", Boolean.TRUE);
        rb.put("status", "OK");
        java.util.List<Map<String, Object>> steps = new java.util.ArrayList<Map<String, Object>>();
        Map<String, Object> s = new java.util.LinkedHashMap<String, Object>();
        s.put("name", "vetoDisarm");
        s.put("status", "OK");
        s.put("ms", ms);
        s.put("detail", r);
        steps.add(s);
        rb.put("steps", steps);
        rcpt.rollback = rb;
    }

    private Map<String, Object> finalizeAndStore(TxJournal.TxReceipt rcpt,
                                                 TxJournal.Status status,
                                                 String restorePath) {
        if (restorePath != null) rcpt.restorePath = restorePath;
        rcpt.seal(status);
        TxJournal.INSTANCE.complete(rcpt);
        return rcpt.toMap();
    }

    private static Map<String, Object> detailErr(String code, String message) {
        Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
        d.put("code", code);
        d.put("error", message);
        return d;
    }

    private static Map<String, Object> errMap(String step, String cls, String message) {
        Map<String, Object> e = new java.util.LinkedHashMap<String, Object>();
        e.put("step", step);
        e.put("class", cls);
        e.put("message", message);
        return e;
    }

    // ====================== F4 wcg.refs: warm-up del índice invertido ======================
    // El índice se construye lazy en el primer wcg.refs (~1.7 s sobre el modpack de prueba —
    // 2340 recetas + 1941 loot tables + membresía de tags). El warm-up amortiza ese coste:
    // tras game-ready, un daemon polea hasta que el servidor integrado vive y dispara la
    // construcción una vez. El índice queda EN MEMORIA (no SQLite §5.3 — decisión §3.29):
    // sellado con epoch, reconstruible, y la query subsiguiente es O(idx) sobre el caché.
    //
    // No bloquea ningún hilo del agente: ningún flujo de F4 depende del warm-up; si falla,
    // la query original sigue funcionando (paga el coste de construcción). Idempotente —
    // varias llamadas a warmRefsIndexAsync() en una sesión disparan un solo daemon.

    private volatile boolean refsWarmupStarted;
    private volatile boolean refsWarmupReady;
    private volatile long refsWarmupAttempts;
    private volatile long refsWarmupStartMs;
    private volatile long refsWarmupMs;          // duración total desde start hasta ready
    private volatile String refsWarmupError;     // último error visto (null tras success)

    /**
     * Arranca el warm-up en background. Devuelve inmediatamente. Idempotente: una sola
     * vez por sesión. El daemon polea wcgRefs("minecraft", false) cada 5 s hasta que el
     * servidor integrado esté vivo y el índice quede construido; máx ~30 min, luego se
     * rinde (el thread muere, futuras queries pagarán la construcción la primera vez).
     */
    public void warmRefsIndexAsync() {
        synchronized (this) {
            if (refsWarmupStarted) return;
            refsWarmupStarted = true;
        }
        refsWarmupStartMs = System.currentTimeMillis();
        Thread t = new Thread(new Runnable() {
            public void run() {
                final int maxAttempts = 360;     // 360 * 5 s = 30 min
                for (int i = 0; i < maxAttempts && !refsWarmupReady; i++) {
                    refsWarmupAttempts++;
                    try {
                        // Usa "minecraft" como ns sonda — siempre existe; el coste real
                        // es ensureRefsIndex(), la query subsiguiente es trivial.
                        Map<String, Object> r = wcgRefs("minecraft", false);
                        if (Boolean.TRUE.equals(r.get("ok"))) {
                            refsWarmupMs = System.currentTimeMillis() - refsWarmupStartMs;
                            refsWarmupError = null;
                            refsWarmupReady = true;
                            return;
                        }
                        // Cualquier no-OK queda como "último error" para diag; seguimos
                        // poleando — NOT_READY es lo esperado hasta cargar mundo, e
                        // INTERNAL puede ser transitorio (recipe manager aún reloading).
                        refsWarmupError = String.valueOf(r.get("code")) + ": " + r.get("error");
                    } catch (Throwable th) {
                        refsWarmupError = String.valueOf(th);
                    }
                    try { Thread.sleep(5000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "mksa-refs-warmup");
        t.setDaemon(true);
        t.start();
    }

    public boolean refsWarmupStarted()  { return refsWarmupStarted; }
    public boolean refsWarmupReady()    { return refsWarmupReady; }
    public long    refsWarmupAttempts() { return refsWarmupAttempts; }
    public long    refsWarmupMs()       { return refsWarmupMs; }
    public String  refsWarmupError()    { return refsWarmupError == null ? "" : refsWarmupError; }
    public String  refsIndexSource()    { return refsIndexSource == null ? "" : refsIndexSource; }
    public boolean refsSqliteDriver()   { return refsSqliteDriver; }
    public boolean refsSqliteHit()      { return refsSqliteHit; }
    public boolean refsSqliteMiss()     { return refsSqliteMiss; }
    public long    refsSqliteLoadedRows() { return refsSqliteLoadedRows; }
    public long    refsSqliteSavedRows()  { return refsSqliteSavedRows; }
    public String  refsSqlitePath()     { return refsSqlitePath == null ? "" : refsSqlitePath; }
    public String  refsSqliteError()    { return refsSqliteError == null ? "" : refsSqliteError; }

    // ════════════════════════════════════════════════════════════════════════
    // Tier 1 corte 6 — items vivos (jugador + cofres) + sonda creativa
    // ════════════════════════════════════════════════════════════════════════
    //
    // El corte 5b cerró bloques+recetas+tags+restitución viva. Faltaba el
    // binomio items: cuando el usuario desactiva un mod, los items del mod
    // SIGUEN visibles en su mochila / armadura / ender chest y en cofres del
    // mundo. La promesa "como si no existiera" exige que también desaparezcan.
    //
    // Alcance del corte:
    //   · 6a items del JUGADOR online (inventario + offhand + armadura + ender)
    //   · 6b items en BLOCK ENTITIES tipo Container en chunks CARGADOS
    //   · 6c SONDA del creativo (no muta, reporta estructura) → corte 6d el filtro
    //
    // Política de overflow en restore (acordada con el usuario):
    //   · Slot original libre → restaurar ahí (caso común).
    //   · Slot ocupado → primer slot libre del mismo contenedor.
    //   · Sin slot libre y es inventario de jugador → drop al suelo (vanilla pattern).
    //   · Sin slot libre y es cofre → reportar lost (no podemos dropear sin contexto).
    //
    // Frontera: chunks NO cargados se cubren por migración perezosa (§3.2):
    //   el write-hook del ledger los procesará al cargarse. Items en entidades
    //   (frames, armor stands, mules) — futuro corte 6e.

    // ---- resolvers reflexivos (lazy, cached, patrón §3.18) ----
    // Reusados de wcg.refs: itemRegistry (Object), itemGetId (Method), mGetItem (Method).
    private volatile Method mGetPlayerList;
    private volatile Method mPlayerListGetPlayers;
    private volatile Method mPlayerGetUuid;
    private volatile Field fPlayerInventory;
    private volatile Field fPlayerEnderChest;
    private volatile Method mPlayerDropOnFloor;
    private volatile Method mContainerGetSize;
    // Todos los métodos no-arg int de Container (getContainerSize Y getMaxStackSize, etc).
    // mContainerGetSize se desambigua por comportamiento (§3.18) sobre anclas reales.
    private final java.util.List<Method> containerSizeCandidates = new java.util.ArrayList<Method>();
    private volatile boolean containerSizeDisambiguated;
    private volatile Method mContainerGetItem;
    private volatile String containerGetItemSig = "unresolved";
    private volatile Method mContainerSetItem;
    private volatile Method mItemStackIsEmpty;
    private volatile Method mItemStackSave;          // ItemStack.save(HolderLookup.Provider): Tag
    private volatile Method mItemStackParseStatic;   // static ItemStack.parseOptional(HolderLookup.Provider, Tag): Optional
    private volatile Method mItemDefaultInstance;     // Item.getDefaultInstance(): ItemStack
    private volatile Object emptyItemStack;
    private volatile Class<?> itemStackClass;
    private volatile Class<?> containerInterface;    // class_1263
    private volatile Class<?> tagInterface;          // class_2520 (Tag)
    private volatile Method mServerRegistryAccess;   // server.registryAccess()
    private volatile Object cachedProvider;
    private volatile Method mLevelDimension;
    private volatile Method mResourceKeyLocation;
    private volatile Field fChunkMapInChunkSource;
    private volatile Method mChunkMapGetChunks;
    private volatile Method mChunkHolderGetChunk;
    private volatile Method mChunkGetBlockEntities;
    private volatile Method mBlockEntitySetChanged;
    private volatile Method mLevelGetBlockEntity;

    // ---- cache EN MEMORIA de items capturados (sesión actual, sin NBT codec) ----
    // En MC 1.21.11 ItemStack ya no tiene save(Provider):Tag — migró a Codec.
    // Workaround: guardamos la referencia VIVA al ItemStack tras vaciar el slot.
    // La JVM mantiene el objeto vivo mientras esté referenciado aquí; al restaurar,
    // simplemente lo volvemos a poner. Cross-sesión (cerrar/abrir juego) NO sobrevive
    // — eso requiere serialización por codec, futuro corte. Sesión-actual (disable+
    // reactivate sin reiniciar) sí funciona.
    private static final class CapturedItem {
        final String uuid; final int kindBit; final int slot; final Object stack;
        CapturedItem(String uuid, int kindBit, int slot, Object stack) {
            this.uuid = uuid; this.kindBit = kindBit; this.slot = slot; this.stack = stack;
        }
    }
    private static final class CapturedContainerItem {
        final String dimKey; final long packedPos; final int slot; final Object stack;
        CapturedContainerItem(String dimKey, long packedPos, int slot, Object stack) {
            this.dimKey = dimKey; this.packedPos = packedPos; this.slot = slot; this.stack = stack;
        }
    }
    private static final java.util.Map<String, java.util.List<CapturedItem>> itemMemCache =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.List<CapturedItem>>();
    private static final java.util.Map<String, java.util.List<CapturedContainerItem>> containerMemCache =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.List<CapturedContainerItem>>();

    // ---- log a archivo para diagnóstico (bypasea captura de stderr de Fabric) ----
    private static volatile java.nio.file.Path tier1LogPath;
    private static void t1log(String line) {
        try {
            if (tier1LogPath == null && Agent.mksaDir != null) {
                tier1LogPath = Agent.mksaDir.resolve("tier1.log");
                try { java.nio.file.Files.write(tier1LogPath, new byte[0]); } catch (Throwable ignored) {}
            }
            if (tier1LogPath == null) return;
            String full = "[" + java.time.LocalTime.now() + "] " + line + "\n";
            java.nio.file.Files.write(tier1LogPath, full.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {}
    }

    private static final String[] CONTAINER_CLASSES = {
            "net.minecraft.class_1263",
            "net.minecraft.world.Container",
    };
    private static final String[] TAG_CLASSES = {
            "net.minecraft.class_2520",
            "net.minecraft.nbt.Tag",
    };
    private static final String[] ITEMSTACK_CLASSES = {
            "net.minecraft.class_1799",
            "net.minecraft.world.item.ItemStack",
    };
    private static final String[] CREATIVE_TABS_CLASSES = {
            "net.minecraft.class_7706",
            "net.minecraft.world.item.CreativeModeTabs",
    };
    private static final String[] CREATIVE_TAB_CLASSES = {
            "net.minecraft.class_1761",
            "net.minecraft.world.item.CreativeModeTab",
    };

    /** Resolved lazily: MinecraftServer.saveAllChunks(boolean, boolean, boolean): boolean. */
    private volatile Method mSaveAllChunks;

    /**
     * Fuerza un save del mundo (saveAllChunks) marshalizado al hilo del servidor.
     * Provoca el mensaje "Saving world" del cliente. Best-effort: si no se puede
     * resolver el método o falla, registra paso skipped y sigue.
     */
    private void saveWorldBeforeTx(TxJournal.TxReceipt rcpt) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> r = runOnServer(new ServerJob() {
            public void run(Object srv, Map<String, Object> holder) throws Exception {
                if (mSaveAllChunks == null) {
                    for (Method m : srv.getClass().getMethods()) {
                        if (Modifier.isStatic(m.getModifiers())) continue;
                        Class<?>[] ps = m.getParameterTypes();
                        if (ps.length != 3) continue;
                        if (ps[0] != boolean.class || ps[1] != boolean.class || ps[2] != boolean.class) continue;
                        String n = m.getName();
                        if (n.equals("saveAllChunks") || n.equals("method_3729") || n.equals("method_3782")) {
                            mSaveAllChunks = m; m.setAccessible(true); break;
                        }
                    }
                    // Fallback: cualquier método de 3 booleans declarado en MinecraftServer
                    if (mSaveAllChunks == null) {
                        for (Method m : srv.getClass().getMethods()) {
                            if (Modifier.isStatic(m.getModifiers())) continue;
                            Class<?>[] ps = m.getParameterTypes();
                            if (ps.length != 3) continue;
                            if (ps[0] != boolean.class || ps[1] != boolean.class || ps[2] != boolean.class) continue;
                            if (m.getReturnType() != boolean.class) continue;
                            mSaveAllChunks = m; m.setAccessible(true);
                            t1log("saveAllChunks fallback resolved as " + m.getName());
                            break;
                        }
                    }
                }
                if (mSaveAllChunks == null) {
                    holder.put("ok", Boolean.TRUE); holder.put("skipped", "not resolved");
                    return;
                }
                Boolean res = (Boolean) mSaveAllChunks.invoke(srv, Boolean.TRUE, Boolean.TRUE, Boolean.FALSE);
                holder.put("ok", Boolean.TRUE);
                holder.put("saved", res);
            }
        });
        long ms = System.currentTimeMillis() - t0;
        Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
        d.put("saved", r.get("saved"));
        d.put("skipped", r.get("skipped"));
        rcpt.addStep("saveWorld", TxJournal.Status.OK, ms, d);
        t1log("saveWorld ms=" + ms + " ok=" + r.get("ok") + " saved=" + r.get("saved")
            + " skipped=" + r.get("skipped"));
    }

    // ====================== txStep wrappers (corte 6) ======================
    // Patrón idéntico a txStepSweep: ejecuta la operación, mide, llena el receipt
    // con el detalle. Retorna true/false aunque actualmente los callers ignoran el
    // valor (los pasos de items son best-effort: si fallan, queda detalle en el
    // receipt y el flujo continúa — el sweep de bloques YA mutó el mundo).

    private boolean txStepItemSweep(TxJournal.TxReceipt rcpt, String ns, String restorePath) {
        t1log("txStepItemSweep START ns=" + ns);
        long t0 = System.currentTimeMillis();
        Map<String, Object> r = itemSweep(ns, restorePath);
        long ms = System.currentTimeMillis() - t0;
        t1log("txStepItemSweep END ms=" + ms + " ok=" + r.get("ok") + " code=" + r.get("code")
            + " captured=" + r.get("captured") + " err=" + r.get("error"));
        Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
        if (Boolean.TRUE.equals(r.get("ok"))) {
            d.put("captured", r.get("captured"));
            d.put("players", r.get("players"));
            d.put("errors", r.get("errors"));
            rcpt.addStep("itemSweep", TxJournal.Status.OK, ms, d);
            return true;
        }
        d.put("code", r.get("code"));
        d.put("error", r.get("error"));
        // NOT_READY (sin servidor / sin jugadores) → skipped, no FAILED
        TxJournal.Status st = "NOT_READY".equals(String.valueOf(r.get("code")))
                ? TxJournal.Status.OK : TxJournal.Status.FAILED;
        d.put("skipped", "NOT_READY".equals(String.valueOf(r.get("code"))));
        rcpt.addStep("itemSweep", st, ms, d);
        return st == TxJournal.Status.OK;
    }

    private boolean txStepContainerSweep(TxJournal.TxReceipt rcpt, String ns, String restorePath) {
        t1log("txStepContainerSweep START ns=" + ns);
        long t0 = System.currentTimeMillis();
        Map<String, Object> r = containerSweep(ns, restorePath);
        long ms = System.currentTimeMillis() - t0;
        t1log("txStepContainerSweep END ms=" + ms + " ok=" + r.get("ok") + " code=" + r.get("code")
            + " captured=" + r.get("captured") + " err=" + r.get("error"));
        Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
        if (Boolean.TRUE.equals(r.get("ok"))) {
            d.put("captured", r.get("captured"));
            d.put("containers", r.get("containers"));
            d.put("chunksScanned", r.get("chunksScanned"));
            rcpt.addStep("containerSweep", TxJournal.Status.OK, ms, d);
            return true;
        }
        d.put("code", r.get("code"));
        d.put("error", r.get("error"));
        TxJournal.Status st = "NOT_READY".equals(String.valueOf(r.get("code")))
                ? TxJournal.Status.OK : TxJournal.Status.FAILED;
        d.put("skipped", "NOT_READY".equals(String.valueOf(r.get("code"))));
        rcpt.addStep("containerSweep", st, ms, d);
        return st == TxJournal.Status.OK;
    }

    private boolean txStepItemRestore(TxJournal.TxReceipt rcpt, String ns, String restorePath) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> r = itemRestore(ns, restorePath);
        long ms = System.currentTimeMillis() - t0;
        Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
        if (Boolean.TRUE.equals(r.get("ok"))) {
            d.put("restored", r.get("restored"));
            d.put("overflows", r.get("overflows"));
            d.put("dropped", r.get("dropped"));
            d.put("lost", r.get("lost"));
            d.put("misses", r.get("misses"));
            rcpt.addStep("itemRestore", TxJournal.Status.OK, ms, d);
            return true;
        }
        d.put("code", r.get("code"));
        d.put("error", r.get("error"));
        TxJournal.Status st = "NOT_READY".equals(String.valueOf(r.get("code")))
                ? TxJournal.Status.OK : TxJournal.Status.FAILED;
        d.put("skipped", "NOT_READY".equals(String.valueOf(r.get("code"))));
        rcpt.addStep("itemRestore", st, ms, d);
        return st == TxJournal.Status.OK;
    }

    private boolean txStepContainerRestore(TxJournal.TxReceipt rcpt, String ns, String restorePath) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> r = containerRestore(ns, restorePath);
        long ms = System.currentTimeMillis() - t0;
        Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
        if (Boolean.TRUE.equals(r.get("ok"))) {
            d.put("restored", r.get("restored"));
            d.put("overflows", r.get("overflows"));
            d.put("lost", r.get("lost"));
            d.put("misses", r.get("misses"));
            rcpt.addStep("containerRestore", TxJournal.Status.OK, ms, d);
            return true;
        }
        d.put("code", r.get("code"));
        d.put("error", r.get("error"));
        TxJournal.Status st = "NOT_READY".equals(String.valueOf(r.get("code")))
                ? TxJournal.Status.OK : TxJournal.Status.FAILED;
        d.put("skipped", "NOT_READY".equals(String.valueOf(r.get("code"))));
        rcpt.addStep("containerRestore", st, ms, d);
        return st == TxJournal.Status.OK;
    }

    // ====================== entry points ======================

    /**
     * Barre items del namespace víctima de inventarios de jugadores online.
     * Marshalizado al hilo del servidor. Devuelve {ok, captured, players, ...}.
     */
    public Map<String, Object> itemSweep(final String ns, final String restorePath) {
        if (ns == null || ns.isEmpty()) return fail("BAD_PARAMS", "itemSweep requiere ns");
        if (restorePath == null || restorePath.isEmpty()) return fail("BAD_PARAMS", "itemSweep requiere restorePath");
        return runOnServer((srv, holder) -> attemptItemSweep(srv, ns, restorePath, holder));
    }

    /** Restaura items del MKSAR1 al inventario de jugadores online. Política overflow §6 plan. */
    public Map<String, Object> itemRestore(final String ns, final String restorePath) {
        if (ns == null || ns.isEmpty()) return fail("BAD_PARAMS", "itemRestore requiere ns");
        if (restorePath == null || restorePath.isEmpty()) return fail("BAD_PARAMS", "itemRestore requiere restorePath");
        return runOnServer((srv, holder) -> attemptItemRestore(srv, ns, restorePath, holder));
    }

    /** Barre items del ns de cofres/contenedores en chunks cargados. */
    public Map<String, Object> containerSweep(final String ns, final String restorePath) {
        if (ns == null || ns.isEmpty()) return fail("BAD_PARAMS", "containerSweep requiere ns");
        if (restorePath == null || restorePath.isEmpty()) return fail("BAD_PARAMS", "containerSweep requiere restorePath");
        return runOnServer((srv, holder) -> attemptContainerSweep(srv, ns, restorePath, holder));
    }

    /** Restaura items 'C' del MKSAR1 a cofres del mundo vivo. */
    public Map<String, Object> containerRestore(final String ns, final String restorePath) {
        if (ns == null || ns.isEmpty()) return fail("BAD_PARAMS", "containerRestore requiere ns");
        if (restorePath == null || restorePath.isEmpty()) return fail("BAD_PARAMS", "containerRestore requiere restorePath");
        return runOnServer((srv, holder) -> attemptContainerRestore(srv, ns, restorePath, holder));
    }

    /**
     * Sonda del menú creativo. NO muta. Reporta la estructura de
     * CreativeModeTabs/CreativeModeTab para decidir el mecanismo del filtro
     * real en el corte 6d. Si la clase aún no está cargada (usuario no abrió
     * creativo todavía) → NOT_READY.
     */
    public Map<String, Object> probeCreative() {
        try {
            return attemptProbeCreative();
        } catch (Throwable t) {
            return fail("INTERNAL", "probeCreative: " + t);
        }
    }

    // ====================== marshal helper ======================

    private interface ServerJob {
        void run(Object server, Map<String, Object> holder) throws Exception;
    }

    /** Marshaliza un job al hilo del servidor; retorna {ok:false,code,error} o el holder lleno. */
    private Map<String, Object> runOnServer(final ServerJob job) {
        Object client = Boot.clientInstance;
        if (client == null) { t1log("runOnServer FAIL cliente null"); return fail("NOT_READY", "cliente aún no existe"); }
        List<Object> candidates = serverCandidates(client);
        t1log("runOnServer candidates=" + candidates.size() + " serverCached=" + (server != null));
        if (candidates.isEmpty()) return fail("NOT_READY", "sin servidor integrado vivo");
        for (Object cand : candidates) {
            final Object candidate = cand;
            final Map<String, Object> holder = new HashMap<String, Object>();
            final CountDownLatch latch = new CountDownLatch(1);
            try {
                ((Executor) candidate).execute(new Runnable() {
                    public void run() {
                        try { job.run(candidate, holder); }
                        catch (Throwable t) { holder.put("error", String.valueOf(t)); holder.put("code", "INTERNAL"); }
                        finally { latch.countDown(); }
                    }
                });
            } catch (Throwable t) { continue; }
            boolean done;
            try { done = latch.await(30, TimeUnit.SECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return fail("INTERNAL", "interrumpido"); }
            if (!done) return fail("INTERNAL", "server thread no respondió en 30s");
            if (Boolean.TRUE.equals(holder.get("ok"))) return holder;
            if (holder.get("code") instanceof String) return fail((String) holder.get("code"), String.valueOf(holder.get("error")));
            // sin level → siguiente candidato
        }
        return fail("NOT_READY", "ningún executor expuso un ServerLevel");
    }

    // ====================== 6a: player items ======================

    private void attemptItemSweep(Object candidate, String ns, String restorePath,
                                   Map<String, Object> holder) throws Exception {
        t1log("itemSweep ENTER ns=" + ns + " candidate=" + candidate.getClass().getSimpleName());
        List<Object> lvls = ensureLevels(candidate);
        if (lvls == null || lvls.isEmpty()) { t1log("itemSweep SKIP ensureLevels=null"); return; }
        server = candidate;
        ensureLevelMethods(lvls.get(0));
        try { ensureItemMethods(candidate); }
        catch (Throwable t) {
            t1log("itemSweep FAIL ensureItemMethods: " + t);
            holder.put("code", "INTERNAL"); holder.put("error", "ensureItemMethods: " + t); return;
        }
        t1log("itemSweep RESOLVED stack=" + (itemStackClass != null) + " container=" + (containerInterface != null)
            + " size=" + (mContainerGetSize != null) + " getItem=" + (mContainerGetItem != null)
            + " setItem=" + (mContainerSetItem != null) + " save=" + (mItemStackSave != null)
            + " parse=" + (mItemStackParseStatic != null) + " provider=" + (cachedProvider != null)
            + " empty=" + (emptyItemStack != null) + " itemReg=" + (itemRegistry != null));

        List<Object> players = livePlayers(candidate);
        t1log("itemSweep players=" + players.size() + " invField=" + (fPlayerInventory != null)
            + " enderField=" + (fPlayerEnderChest != null) + " uuid=" + (mPlayerGetUuid != null));
        Object writer = Ledger.INSTANCE.newRestoreWriter(restorePath, ns);
        long captured = 0;
        long playersScanned = 0;
        long errors = 0;
        List<String> errorDetail = new ArrayList<String>();

        try {
            for (Object p : players) {
                playersScanned++;
                String uuidStr;
                try { uuidStr = String.valueOf(mPlayerGetUuid.invoke(p)); }
                catch (Throwable t) { errors++; continue; }
                byte[] uuidBytes = uuidStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);

                Object inv = readField(fPlayerInventory, p);
                if (inv != null) {
                    captured += scanAndCaptureContainer(inv, ns, uuidBytes, 0, writer, errorDetail);
                }
                Object ender = readField(fPlayerEnderChest, p);
                if (ender != null) {
                    captured += scanAndCaptureContainer(ender, ns, uuidBytes, 1, writer, errorDetail);
                }
            }
        } finally {
            Ledger.INSTANCE.closeRestoreWriter(writer);
        }

        t1log("itemSweep DONE captured=" + captured + " players=" + playersScanned + " errors=" + errors);
        if (!errorDetail.isEmpty()) t1log("itemSweep ERRORS: " + errorDetail);
        holder.put("ok", Boolean.TRUE);
        holder.put("ns", ns);
        holder.put("captured", captured);
        holder.put("players", playersScanned);
        holder.put("errors", errors);
        holder.put("errorDetail", errorDetail);
        holder.put("restorePath", restorePath);
    }

    /** Escanea un Container (inv/ender), captura+vacía items del ns. Retorna nº capturados. */
    private long scanAndCaptureContainer(Object container, String ns, byte[] uuidBytes,
                                          int kindBit, Object writer, List<String> errorDetail) {
        long count = 0;
        int total = 0, nonEmpty = 0;
        java.util.Map<String, Integer> nsHist = new java.util.HashMap<String, Integer>();
        try {
            int size = ((Integer) mContainerGetSize.invoke(container)).intValue();
            total = size;
            for (int i = 0; i < size; i++) {
                Object stack;
                try { stack = mContainerGetItem.invoke(container, Integer.valueOf(i)); }
                catch (Throwable t) { continue; }
                if (stack == null) continue;
                if (isEmptyStack(stack)) continue;
                nonEmpty++;
                String stackNs = itemNamespace(stack);
                String key = stackNs == null ? "<null>" : stackNs;
                Integer prev = nsHist.get(key);
                nsHist.put(key, prev == null ? 1 : prev + 1);
                if (!ns.equals(stackNs)) continue;
                // 1) Intenta serializar a NBT (sobrevive cross-sesión). Si falla (MC 1.21.11 codec),
                //    no aborta — caemos al cache en memoria.
                byte[] nbt = serializeItemStack(stack);
                if (nbt != null && nbt.length > 0) {
                    int slotKey = (kindBit << 16) | (i & 0xffff);
                    try { Ledger.INSTANCE.writeItemRecord(writer, uuidBytes, slotKey, nbt); }
                    catch (Throwable t) {
                        if (errorDetail.size() < 8) errorDetail.add("slot=" + i + " write=" + t);
                    }
                }
                // 2) SIEMPRE cachea la referencia viva al ItemStack y vacía el slot.
                //    Aunque la serialización NBT falle, en-memoria preserva la restitución
                //    dentro de la misma sesión (que es el caso de uso primario).
                String uuidStr = new String(uuidBytes, java.nio.charset.StandardCharsets.UTF_8);
                java.util.List<CapturedItem> bucket = itemMemCache.get(ns);
                if (bucket == null) {
                    bucket = new java.util.concurrent.CopyOnWriteArrayList<CapturedItem>();
                    itemMemCache.put(ns, bucket);
                }
                bucket.add(new CapturedItem(uuidStr, kindBit, i, stack));
                try { mContainerSetItem.invoke(container, Integer.valueOf(i), emptyItemStack); count++; }
                catch (Throwable t) { if (errorDetail.size() < 8) errorDetail.add("slot=" + i + " setEmpty=" + t); }
            }
        } catch (Throwable t) {
            if (errorDetail.size() < 8) errorDetail.add("container.size=" + t);
        }
        t1log("scanContainer kind=" + kindBit + " size=" + total + " nonEmpty=" + nonEmpty
            + " captured=" + count + " ns=" + nsHist);
        return count;
    }

    private void attemptItemRestore(Object candidate, String ns, String restorePath,
                                     Map<String, Object> holder) throws Exception {
        t1log("itemRestore ENTER ns=" + ns);
        List<Object> lvls = ensureLevels(candidate);
        if (lvls == null || lvls.isEmpty()) return;
        server = candidate;
        ensureLevelMethods(lvls.get(0));
        try { ensureItemMethods(candidate); } catch (Throwable ignored) {}

        long restored = 0, overflows = 0, dropped = 0, lost = 0, misses = 0;
        List<String> diag = new ArrayList<String>();

        List<Object> players = livePlayers(candidate);
        Map<String, Object> byUuid = new HashMap<String, Object>();
        for (Object p : players) {
            try { if (mPlayerGetUuid != null) byUuid.put(String.valueOf(mPlayerGetUuid.invoke(p)), p); }
            catch (Throwable ignored) {}
        }

        // Fuente PRIMARIA: cache en memoria (sesión actual). Si vino vacía, intentamos
        // el archivo NBT como fallback (cross-sesión, requiere codec funcionando).
        java.util.List<CapturedItem> mem = itemMemCache.remove(ns);
        int memSize = mem == null ? 0 : mem.size();
        if (mem != null) {
            for (CapturedItem ci : mem) {
                Object p = byUuid.get(ci.uuid);
                if (p == null) { misses++; continue; }
                Object container = readField(ci.kindBit == 1 ? fPlayerEnderChest : fPlayerInventory, p);
                if (container == null) { misses++; continue; }
                int placed = tryPlaceWithOverflow(container, ci.slot, ci.stack);
                if (placed == 1) restored++;
                else if (placed == 2) { restored++; overflows++; }
                else {
                    if (ci.kindBit == 0 && mPlayerDropOnFloor != null) {
                        try { mPlayerDropOnFloor.invoke(p, ci.stack, Boolean.TRUE); dropped++; }
                        catch (Throwable t) { lost++; if (diag.size() < 8) diag.add("drop=" + t); }
                    } else { lost++; }
                }
            }
        }

        // Fallback NBT (cross-sesión, opcional — silenciosamente vacío si codec no funciona)
        int fileSize = 0;
        try {
            List<Object[]> items = Ledger.INSTANCE.readRestoreItems(restorePath);
            fileSize = items.size();
            for (Object[] rec : items) {
                byte[] uuidB = (byte[]) rec[0];
                int slotKey = ((Integer) rec[1]).intValue();
                byte[] nbt = (byte[]) rec[2];
                String uuid = new String(uuidB, java.nio.charset.StandardCharsets.UTF_8);
                Object p = byUuid.get(uuid);
                if (p == null) { misses++; continue; }
                int kindBit = (slotKey >> 16) & 0xffff;
                int slot = slotKey & 0xffff;
                Object stack = deserializeItemStack(nbt);
                if (stack == null || isEmptyStack(stack)) { misses++; continue; }
                Object container = readField(kindBit == 1 ? fPlayerEnderChest : fPlayerInventory, p);
                if (container == null) { misses++; continue; }
                int placed = tryPlaceWithOverflow(container, slot, stack);
                if (placed == 1) restored++;
                else if (placed == 2) { restored++; overflows++; }
                else lost++;
            }
        } catch (Throwable ignored) {}

        t1log("itemRestore DONE ns=" + ns + " memSize=" + memSize + " fileSize=" + fileSize
            + " restored=" + restored + " overflows=" + overflows + " dropped=" + dropped + " lost=" + lost);
        holder.put("ok", Boolean.TRUE);
        holder.put("ns", ns);
        holder.put("memSize", memSize);
        holder.put("restored", restored);
        holder.put("overflows", overflows);
        holder.put("dropped", dropped);
        holder.put("lost", lost);
        holder.put("misses", misses);
        holder.put("diag", diag);
    }

    /** 0 = no se pudo (lleno), 1 = en slot original, 2 = en overflow (otro slot libre). */
    private int tryPlaceWithOverflow(Object container, int slot, Object stack) {
        try {
            int size = ((Integer) mContainerGetSize.invoke(container)).intValue();
            if (slot >= 0 && slot < size) {
                Object cur = mContainerGetItem.invoke(container, Integer.valueOf(slot));
                if (cur == null || isEmptyStack(cur)) {
                    mContainerSetItem.invoke(container, Integer.valueOf(slot), stack);
                    return 1;
                }
            }
            for (int i = 0; i < size; i++) {
                Object cur = mContainerGetItem.invoke(container, Integer.valueOf(i));
                if (cur == null || isEmptyStack(cur)) {
                    mContainerSetItem.invoke(container, Integer.valueOf(i), stack);
                    return 2;
                }
            }
        } catch (Throwable t) { /* full or error */ }
        return 0;
    }

    // ====================== 6b: container items in loaded chunks ======================

    private void attemptContainerSweep(Object candidate, String ns, String restorePath,
                                        Map<String, Object> holder) throws Exception {
        t1log("containerSweep ENTER ns=" + ns);
        List<Object> lvls = ensureLevels(candidate);
        if (lvls == null || lvls.isEmpty()) { t1log("containerSweep SKIP ensureLevels=null"); return; }
        server = candidate;
        ensureLevelMethods(lvls.get(0));
        try { ensureItemMethods(candidate); }
        catch (Throwable t) { t1log("containerSweep FAIL ensureItemMethods: " + t);
            holder.put("code", "INTERNAL"); holder.put("error", String.valueOf(t)); return; }
        // RESET de mChunkGetBlockEntities: el discovery via ChunkMap es buggy (matcheaba
        // class_10582.comp_3477, un campo de record que no es getBlockEntities). Forzamos
        // re-resolución sobre el PRIMER LevelChunk real que encontremos en el grid.
        mChunkGetBlockEntities = null;
        // Reuso de mGetChunkNow (validado por tier1Sweep) — más confiable.
        if (!ensureChunkSource(lvls.get(0))) {
            t1log("containerSweep SKIP ensureChunkSource=false");
            holder.put("code", "INTERNAL"); holder.put("error", "no chunkSource"); return;
        }
        t1log("containerSweep PRE-RUN levelChunkClass=" + (levelChunkClass != null ? levelChunkClass.getSimpleName() : "null")
            + " getChunkNow=" + (mGetChunkNow != null) + " getChunkSource=" + (mGetChunkSource != null));

        Object writer = Ledger.INSTANCE.newRestoreWriter(restorePath, ns);
        long captured = 0, containersScanned = 0, chunksScanned = 0, chunksLoaded = 0, errors = 0;
        List<String> errorDetail = new ArrayList<String>();
        // Radio del barrido alrededor del origen. Antes era 32 (65×65=4225/nivel), pero con
        // un mundo muy explorado todos los chunks están cargados y la pasada tarda 30s
        // bloqueando el server thread. Con 8 son 17×17=289 chunks/nivel (~1s), suficiente
        // para el área de prueba típica en singleplayer. Si el usuario tiene cofres más
        // lejos quedan fuera de este barrido — se barrerán al cargarse de nuevo (migración
        // perezosa §3.2: el write-hook procesará el contenido al re-serializar).
        final int RADIUS = 8;

        try {
            for (Object lvl : lvls) {
                String dimKey = resolveLevelDimensionKey(lvl);
                Object cs;
                try { cs = mGetChunkSource.invoke(lvl); }
                catch (Throwable t) { continue; }
                if (cs == null) continue;
                for (int cx = -RADIUS; cx <= RADIUS; cx++) {
                    for (int cz = -RADIUS; cz <= RADIUS; cz++) {
                        chunksScanned++;
                        Object chunk;
                        try { chunk = mGetChunkNow.invoke(cs, Integer.valueOf(cx), Integer.valueOf(cz)); }
                        catch (Throwable t) { continue; }
                        if (chunk == null) continue;
                        if (levelChunkClass != null && !levelChunkClass.isInstance(chunk)) continue;
                        chunksLoaded++;
                        // Lazy-resolve sobre el PRIMER chunk real: getBlockEntities(): Map<BlockPos, BlockEntity>
                        if (mChunkGetBlockEntities == null) {
                            try { Agent.openModule(Agent.instrumentation, chunk.getClass()); }
                            catch (Throwable ignored) {}
                            mChunkGetBlockEntities = findBlockEntitiesMethod(chunk.getClass());
                            if (mChunkGetBlockEntities != null) {
                                t1log("getBlockEntities resolved on " + chunk.getClass().getSimpleName()
                                    + "." + mChunkGetBlockEntities.getName());
                            }
                        }
                        if (mChunkGetBlockEntities == null) continue;
                        Object beMap;
                        try { beMap = mChunkGetBlockEntities.invoke(chunk); }
                        catch (Throwable t) { continue; }
                        if (!(beMap instanceof Map)) continue;
                        Map<?, ?> mp = (Map<?, ?>) beMap;
                        if (mp.isEmpty()) continue;
                        java.util.List<java.util.Map.Entry<?, ?>> entries = new java.util.ArrayList<java.util.Map.Entry<?, ?>>(mp.entrySet());
                        for (java.util.Map.Entry<?, ?> e : entries) {
                            Object pos = e.getKey();
                            Object be = e.getValue();
                            if (be == null || pos == null) continue;
                            if (containerInterface != null && !containerInterface.isInstance(be)) continue;
                            containersScanned++;
                            long packed;
                            try { packed = ((Long) mAsLong.invoke(pos)).longValue(); }
                            catch (Throwable t) { continue; }
                            // Lazy-resolve setChanged sobre la PRIMERA BE encontrada
                            if (mBlockEntitySetChanged == null) {
                                for (Method m : be.getClass().getMethods()) {
                                    if (Modifier.isStatic(m.getModifiers())) continue;
                                    if (m.getParameterTypes().length != 0) continue;
                                    if (m.getReturnType() != void.class) continue;
                                    if (m.getName().equals("setChanged") || m.getName().equals("method_5431")) {
                                        mBlockEntitySetChanged = m; m.setAccessible(true); break;
                                    }
                                }
                            }
                            long got = scanAndCaptureBlockEntity(be, ns, dimKey, packed, writer, errorDetail);
                            captured += got;
                            if (got > 0 && mBlockEntitySetChanged != null) {
                                try { mBlockEntitySetChanged.invoke(be); } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            }
        } finally {
            Ledger.INSTANCE.closeRestoreWriter(writer);
        }
        t1log("containerSweep DONE captured=" + captured + " containers=" + containersScanned
            + " chunksScanned=" + chunksScanned + " chunksLoaded=" + chunksLoaded);
        if (!errorDetail.isEmpty()) t1log("containerSweep ERRORS: " + errorDetail);
        holder.put("ok", Boolean.TRUE);
        holder.put("ns", ns);
        holder.put("captured", captured);
        holder.put("containers", containersScanned);
        holder.put("chunksScanned", chunksScanned);
        holder.put("chunksLoaded", chunksLoaded);
        holder.put("errors", errors);
        holder.put("errorDetail", errorDetail);
        holder.put("restorePath", restorePath);
    }

    private long scanAndCaptureBlockEntity(Object be, String ns, String dimKey, long packed,
                                            Object writer, List<String> errorDetail) {
        long count = 0;
        try {
            int size = ((Integer) mContainerGetSize.invoke(be)).intValue();
            for (int i = 0; i < size; i++) {
                Object stack;
                try { stack = mContainerGetItem.invoke(be, Integer.valueOf(i)); }
                catch (Throwable t) { continue; }
                if (stack == null) continue;
                if (isEmptyStack(stack)) continue;
                String stackNs = itemNamespace(stack);
                if (!ns.equals(stackNs)) continue;
                // 1) Intenta NBT (cross-sesión). 2) SIEMPRE cachea ref en memoria + vacía.
                byte[] nbt = serializeItemStack(stack);
                if (nbt != null && nbt.length > 0) {
                    try { Ledger.INSTANCE.writeContainerRecord(writer, dimKey, packed, i, nbt); }
                    catch (Throwable t) { if (errorDetail.size() < 8) errorDetail.add("slot=" + i + " write=" + t); }
                }
                java.util.List<CapturedContainerItem> bucket = containerMemCache.get(ns);
                if (bucket == null) {
                    bucket = new java.util.concurrent.CopyOnWriteArrayList<CapturedContainerItem>();
                    containerMemCache.put(ns, bucket);
                }
                bucket.add(new CapturedContainerItem(dimKey, packed, i, stack));
                try { mContainerSetItem.invoke(be, Integer.valueOf(i), emptyItemStack); count++; }
                catch (Throwable t) { if (errorDetail.size() < 8) errorDetail.add("slot=" + i + " setEmpty=" + t); }
            }
        } catch (Throwable t) {
            if (errorDetail.size() < 8) errorDetail.add("size=" + t);
        }
        return count;
    }

    private void attemptContainerRestore(Object candidate, String ns, String restorePath,
                                          Map<String, Object> holder) throws Exception {
        t1log("containerRestore ENTER ns=" + ns);
        List<Object> lvls = ensureLevels(candidate);
        if (lvls == null || lvls.isEmpty()) return;
        server = candidate;
        ensureLevelMethods(lvls.get(0));
        try { ensureItemMethods(candidate); } catch (Throwable ignored) {}
        // mLevelGetBlockEntity es lo único que necesitamos para restore (no chunk iteration).
        // Resuélvelo aquí si no está; level.getBlockEntity(BlockPos) es estable por forma.
        if (mLevelGetBlockEntity == null) {
            for (Method m : lvls.get(0).getClass().getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1) continue;
                String pn = ps[0].getName();
                if (!pn.equals("net.minecraft.class_2338") && !pn.equals("net.minecraft.core.BlockPos")) continue;
                String n = m.getName();
                if (n.equals("getBlockEntity") || n.equals("method_8321")) {
                    mLevelGetBlockEntity = m; m.setAccessible(true); break;
                }
            }
        }

        // Index niveles por dim key para lookup
        Map<String, Object> lvlByDim = new HashMap<String, Object>();
        for (Object lvl : lvls) {
            String dk = resolveLevelDimensionKey(lvl);
            if (dk != null) lvlByDim.put(dk, lvl);
        }

        long restored = 0, overflows = 0, lost = 0, misses = 0;
        List<String> diag = new ArrayList<String>();

        // Fuente PRIMARIA: cache en memoria (sesión actual).
        java.util.List<CapturedContainerItem> mem = containerMemCache.remove(ns);
        int memSize = mem == null ? 0 : mem.size();
        if (mem != null && mLevelGetBlockEntity != null) {
            for (CapturedContainerItem ci : mem) {
                Object lvl = lvlByDim.get(ci.dimKey);
                if (lvl == null && !lvls.isEmpty()) lvl = lvls.get(0);
                if (lvl == null) { lost++; continue; }
                int x = (int) (ci.packedPos >> 38);
                int y = (int) ((ci.packedPos << 52) >> 52);
                int z = (int) ((ci.packedPos << 26) >> 38);
                Object pos;
                try { pos = posCtor.newInstance(x, y, z); } catch (Throwable t) { lost++; continue; }
                Object be;
                try { be = mLevelGetBlockEntity.invoke(lvl, pos); } catch (Throwable t) { lost++; continue; }
                if (be == null || (containerInterface != null && !containerInterface.isInstance(be))) {
                    lost++; continue;
                }
                int placed = tryPlaceWithOverflow(be, ci.slot, ci.stack);
                if (placed == 1) restored++;
                else if (placed == 2) { restored++; overflows++; }
                else lost++;
                if (placed > 0 && mBlockEntitySetChanged != null) {
                    try { mBlockEntitySetChanged.invoke(be); } catch (Throwable ignored) {}
                }
            }
        }

        // Fallback NBT del MKSAR1 (cross-sesión)
        List<Object[]> records;
        try { records = Ledger.INSTANCE.readRestoreContainers(restorePath); }
        catch (Throwable t) { records = new ArrayList<Object[]>(); }
        int fileSize = records.size();
        for (Object[] rec : records) {
            String dimKey = (String) rec[0];
            long packed = ((Long) rec[1]).longValue();
            int slot = ((Integer) rec[2]).intValue();
            byte[] nbt = (byte[]) rec[3];
            Object lvl = lvlByDim.get(dimKey);
            if (lvl == null && !lvls.isEmpty()) lvl = lvls.get(0);   // fallback al primer nivel
            if (lvl == null) { lost++; continue; }
            int x = (int) (packed << 26 >> 38);
            int y = (int) (packed << 52 >> 52);
            int z = (int) (packed << 0 >> 38);
            // Decode BlockPos.asLong: x[0..25] | y[26..37] | z[38..63]? Actually MC uses
            // BlockPos.asLong = (y & 0xFFF) | ((z & 0x3FFFFFF) << 12) | ((x & 0x3FFFFFF) << 38)
            // Reverse: x = packed >> 38, y = (packed << 52) >> 52, z = (packed << 26) >> 38
            x = (int) (packed >> 38);
            y = (int) ((packed << 52) >> 52);
            z = (int) ((packed << 26) >> 38);
            Object pos;
            try { pos = posCtor.newInstance(x, y, z); }
            catch (Throwable t) { lost++; continue; }
            Object be;
            try { be = mLevelGetBlockEntity.invoke(lvl, pos); }
            catch (Throwable t) { lost++; continue; }
            if (be == null || (containerInterface != null && !containerInterface.isInstance(be))) {
                lost++; if (diag.size() < 8) diag.add("noContainer dim=" + dimKey + " pos=" + x + "," + y + "," + z);
                continue;
            }
            Object stack = deserializeItemStack(nbt);
            if (stack == null || isEmptyStack(stack)) { misses++; continue; }
            int placed = tryPlaceWithOverflow(be, slot, stack);
            if (placed == 1) restored++;
            else if (placed == 2) { restored++; overflows++; }
            else { lost++; if (diag.size() < 8) diag.add("fullContainer pos=" + x + "," + y + "," + z); }
            if (placed > 0) {
                try { if (mBlockEntitySetChanged != null) mBlockEntitySetChanged.invoke(be); }
                catch (Throwable ignored) {}
            }
        }

        t1log("containerRestore DONE ns=" + ns + " memSize=" + memSize + " fileSize=" + fileSize
            + " restored=" + restored + " overflows=" + overflows + " lost=" + lost + " misses=" + misses);
        holder.put("ok", Boolean.TRUE);
        holder.put("ns", ns);
        holder.put("memSize", memSize);
        holder.put("restored", restored);
        holder.put("overflows", overflows);
        holder.put("lost", lost);
        holder.put("misses", misses);
        holder.put("diag", diag);
    }

    // ====================== 6c: creative probe ======================

    @SuppressWarnings("unchecked")
    private Map<String, Object> attemptProbeCreative() throws Exception {
        Class<?> tabsCls = findLoadedClass(CREATIVE_TABS_CLASSES);
        if (tabsCls == null) return fail("NOT_READY", "CreativeModeTabs no cargado (abre el menú creativo y reintenta)");
        Class<?> tabCls = findLoadedClass(CREATIVE_TAB_CLASSES);
        if (tabCls == null) return fail("NOT_READY", "CreativeModeTab no cargado");

        Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        result.put("ok", Boolean.TRUE);
        result.put("tabsClass", tabsCls.getName());
        result.put("tabClass", tabCls.getName());

        // allTabs() static, no-arg, returns List
        Method allTabs = null;
        for (Method m : tabsCls.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (!List.class.isAssignableFrom(m.getReturnType())) continue;
            allTabs = m; break;
        }
        if (allTabs == null) {
            result.put("allTabsMethod", "NOT_FOUND");
            return result;
        }
        result.put("allTabsMethod", allTabs.getName());
        List<Object> tabs = (List<Object>) allTabs.invoke(null);
        result.put("tabsCount", tabs == null ? 0 : tabs.size());

        // Candidate rebuilders en CreativeModeTabs: estáticos que tomen featureFlags y/o registries
        List<String> rebuilders = new ArrayList<String>();
        for (Method m : tabsCls.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length < 1) continue;
            String sig = m.getName() + "(" + m.getParameterTypes().length + " args, ret=" + m.getReturnType().getSimpleName() + ")";
            rebuilders.add(sig);
            if (rebuilders.size() >= 12) break;
        }
        result.put("candidateRebuilders", rebuilders);

        // Inspeccionar el primer tab: campos Collection y Set
        if (tabs != null && !tabs.isEmpty()) {
            Object sample = tabs.get(0);
            Map<String, Object> sampleInfo = new java.util.LinkedHashMap<String, Object>();
            sampleInfo.put("tabClass", sample.getClass().getName());
            List<Map<String, Object>> collectionFields = new ArrayList<Map<String, Object>>();
            for (Field f : tabCls.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!java.util.Collection.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Map<String, Object> fi = new java.util.LinkedHashMap<String, Object>();
                fi.put("name", f.getName());
                fi.put("declaredType", f.getType().getName());
                try {
                    Object v = f.get(sample);
                    if (v != null) {
                        fi.put("runtimeType", v.getClass().getName());
                        fi.put("size", ((java.util.Collection<?>) v).size());
                    } else {
                        fi.put("runtimeType", "null");
                        fi.put("size", -1);
                    }
                } catch (Throwable t) { fi.put("error", String.valueOf(t)); }
                collectionFields.add(fi);
            }
            sampleInfo.put("collectionFields", collectionFields);
            result.put("sampleTab", sampleInfo);
        }
        // Heurística: si encontramos al menos un campo Collection mutable (ArrayList/LinkedHashSet/FastUtil) → mutable
        // si todos son Immutable* o de Collections$Unmodifiable* → immutable; si no se halló nada → unknown
        result.put("inferredMutability", inferCreativeMutability(result));
        return result;
    }

    @SuppressWarnings("unchecked")
    private String inferCreativeMutability(Map<String, Object> result) {
        Object s = result.get("sampleTab");
        if (!(s instanceof Map)) return "unknown";
        Object fields = ((Map<String, Object>) s).get("collectionFields");
        if (!(fields instanceof List)) return "unknown";
        boolean anyKnownMutable = false;
        boolean anyKnownImmutable = false;
        for (Object f : (List<Object>) fields) {
            if (!(f instanceof Map)) continue;
            Object rt = ((Map<String, Object>) f).get("runtimeType");
            if (!(rt instanceof String)) continue;
            String s2 = (String) rt;
            if (s2.contains("Immutable") || s2.contains("Unmodifiable")) anyKnownImmutable = true;
            else if (s2.contains("ArrayList") || s2.contains("LinkedHash") || s2.contains("HashMap")
                  || s2.contains("HashSet") || s2.contains("ObjectLinked") || s2.contains("ObjectOpen")
                  || s2.contains("ObjectArray")) anyKnownMutable = true;
        }
        if (anyKnownMutable && !anyKnownImmutable) return "mutable";
        if (anyKnownImmutable && !anyKnownMutable) return "immutable";
        if (anyKnownMutable && anyKnownImmutable) return "mixed";
        return "unknown";
    }

    private Class<?> findLoadedClass(String[] names) {
        java.lang.instrument.Instrumentation inst = Agent.instrumentation;
        if (inst == null) return null;
        java.util.Set<String> want = new java.util.HashSet<String>(java.util.Arrays.asList(names));
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (want.contains(c.getName())) return c;
        }
        return null;
    }

    // ====================== reflection setup ======================

    private void ensureItemMethods(Object srv) throws Exception {
        if (mItemStackIsEmpty != null && mContainerGetItem != null && itemRegistry != null) return;
        // ItemStack class
        itemStackClass = findLoadedClass(ITEMSTACK_CLASSES);
        if (itemStackClass == null) throw new RuntimeException("ItemStack no cargado");
        Agent.openModule(Agent.instrumentation, itemStackClass);
        // mGetItem ya existe (declarado en sección de wcg.refs). Si está null, resolverlo aquí.
        for (Method m : itemStackClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (mItemStackIsEmpty == null && m.getReturnType() == boolean.class
                    && (m.getName().equals("isEmpty") || m.getName().equals("method_7960"))) {
                mItemStackIsEmpty = m; m.setAccessible(true);
            }
        }
        if (mItemStackIsEmpty == null) {
            try { mItemStackIsEmpty = itemStackClass.getMethod("method_7960"); mItemStackIsEmpty.setAccessible(true); } catch (Throwable t) {}
        }
        if (mGetItem == null || !isNamed(mGetItem.getReturnType(), ITEM_NAMES)) {
            mGetItem = null;
            resolveGetItem(itemStackClass);
        }
        // ItemStack.EMPTY static field
        for (Field f : itemStackClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() != itemStackClass) continue;
            f.setAccessible(true);
            try {
                Object v = f.get(null);
                if (v != null) {
                    // verificar isEmpty
                    Object b = mItemStackIsEmpty.invoke(v);
                    if (Boolean.TRUE.equals(b)) { emptyItemStack = v; break; }
                }
            } catch (Throwable ignored) {}
        }
        // Container interface
        containerInterface = findLoadedClass(CONTAINER_CLASSES);
        if (containerInterface == null) throw new RuntimeException("Container no cargado");
        java.util.List<Method> getItemCandidates = new java.util.ArrayList<Method>();
        java.util.List<Method> setItemCandidates = new java.util.ArrayList<Method>();
        for (Method m : containerInterface.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 0 && m.getReturnType() == int.class) {
                // Container tiene VARIOS no-arg int (getContainerSize, getMaxStackSize, ...).
                // No atar el primero: recolectar todos; se desambigua por comportamiento
                // (livePlayers, sobre inventario+enderchest reales). mContainerGetSize queda
                // provisional con el primero para que los null-checks pasen.
                m.setAccessible(true);
                containerSizeCandidates.add(m);
                if (mContainerGetSize == null) mContainerGetSize = m;
            } else if (ps.length == 1 && ps[0] == int.class && itemStackClass.isAssignableFrom(m.getReturnType())) {
                getItemCandidates.add(m);
            } else if (ps.length == 2 && ps[0] == int.class && itemStackClass.isAssignableFrom(ps[1]) && m.getReturnType() == void.class) {
                setItemCandidates.add(m);
            }
        }
        mContainerGetItem = resolveContainerGetItem(getItemCandidates);
        mContainerSetItem = resolveContainerSetItem(setItemCandidates);
        if (mContainerGetSize == null || mContainerGetItem == null || mContainerSetItem == null) {
            throw new RuntimeException("Container methods no resueltos: size=" + (mContainerGetSize != null)
                    + " get=" + (mContainerGetItem != null) + " set=" + (mContainerSetItem != null)
                    + " getSig=" + containerGetItemSig);
        }

        // server.registryAccess() / provider
        for (Method m : srv.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            String n = m.getName();
            if (n.equals("registryAccess") || n.equals("method_30611") || n.equals("method_5780")
                    || n.equals("method_43571")) {
                try { m.setAccessible(true); Object p = m.invoke(srv);
                    if (p != null) { mServerRegistryAccess = m; cachedProvider = p; break; } }
                catch (Throwable ignored) {}
            }
        }
        if (cachedProvider == null) {
            // fallback: cualquier no-arg cuyo retorno tenga lookupOrThrow
            for (Method m : srv.getClass().getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0) continue;
                if (m.getReturnType().isPrimitive()) continue;
                Object v;
                try { m.setAccessible(true); v = m.invoke(srv); }
                catch (Throwable t) { continue; }
                if (v == null) continue;
                boolean hasLookup = false;
                for (Method m2 : v.getClass().getMethods()) {
                    if (m2.getName().equals("lookupOrThrow") || m2.getName().equals("lookup")) { hasLookup = true; break; }
                }
                if (hasLookup) { cachedProvider = v; break; }
            }
        }
        // Dump COMPLETO de métodos de ItemStack al log para diagnosticar save/parse.
        // Solo la primera vez (cuando mItemStackSave es null).
        if (mItemStackSave == null) {
            StringBuilder dump = new StringBuilder();
            dump.append("ItemStack methods (").append(itemStackClass.getName()).append("):\n");
            for (Method m : itemStackClass.getMethods()) {
                if (m.getDeclaringClass() == Object.class) continue;
                dump.append("  ").append(Modifier.isStatic(m.getModifiers()) ? "static " : "       ");
                dump.append(m.getName()).append("(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) dump.append(", ");
                    dump.append(ps[i].getSimpleName());
                }
                dump.append("): ").append(m.getReturnType().getSimpleName()).append("\n");
            }
            t1log(dump.toString());
        }

        // ItemStack.save: instance, 1+ params, returns Tag-typed thing (CompoundTag = class_2487 ⊂ Tag).
        // Acepta ANY método que devuelva una clase con nombre que sugiera Tag, sin importar nº de params.
        // Si hay overloads, preferimos el de 1 param (= save(Provider): Tag). Cualquier param tipo
        // class_7225$Lookup (HolderLookup.Provider) o class_5455 (RegistryAccess) cuenta como Provider.
        Method saveSingle = null;
        Method saveDouble = null;
        for (Method m : itemStackClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            String rtn = m.getReturnType().getName();
            if (!isTagTypeName(rtn)) continue;
            Class<?>[] ps = m.getParameterTypes();
            // Filtra: primer parámetro debe ser un Provider o RegistryAccess
            if (ps.length == 0) continue;
            if (!isProviderTypeName(ps[0].getName())) continue;
            if (ps.length == 1) { saveSingle = m; }
            else if (ps.length == 2) { saveDouble = m; }
        }
        // Preferimos saveSingle (save(Provider): Tag), porque saveDouble (save(Provider, Tag prefix)) requiere
        // proveer un prefix vacío. Si no hay saveSingle, usar saveDouble con un CompoundTag nuevo.
        if (saveSingle != null) { mItemStackSave = saveSingle; saveSingle.setAccessible(true); }
        else if (saveDouble != null) { mItemStackSave = saveDouble; saveDouble.setAccessible(true); }

        // ItemStack.parse: static, 2 params (Provider, Tag), returns ItemStack | Optional<ItemStack>.
        for (Method m : itemStackClass.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 2) continue;
            if (!isProviderTypeName(ps[0].getName())) continue;
            if (!isTagTypeName(ps[1].getName())) continue;
            String rtn = m.getReturnType().getName();
            if (rtn.equals(itemStackClass.getName()) || java.util.Optional.class.getName().equals(rtn)) {
                mItemStackParseStatic = m; m.setAccessible(true);
                if (rtn.equals(itemStackClass.getName())) break;  // parseOptional preferido
            }
        }

        // Item registry → getId(item) → ResourceLocation (reusa ensureItemRegistry de wcg.refs)
        try { ensureItemRegistry(); } catch (Throwable ignored) {}

        // Player drop on floor: player.drop(ItemStack, boolean): ItemEntity
        Object somePlayer = livePlayers(srv).isEmpty() ? null : livePlayers(srv).get(0);
        if (somePlayer != null) {
            for (Method m : somePlayer.getClass().getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 2 && itemStackClass.isAssignableFrom(ps[0]) && ps[1] == boolean.class
                        && !m.getReturnType().isPrimitive()) {
                    if (m.getName().equals("drop") || m.getName().equals("method_7328") || m.getName().equals("method_7329")) {
                        mPlayerDropOnFloor = m; m.setAccessible(true); break;
                    }
                }
            }
            // fallback: any 2-arg (ItemStack, boolean) with non-primitive return
            if (mPlayerDropOnFloor == null) {
                for (Method m : somePlayer.getClass().getMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (ps.length == 2 && itemStackClass.isAssignableFrom(ps[0]) && ps[1] == boolean.class
                            && !m.getReturnType().isPrimitive()) {
                        mPlayerDropOnFloor = m; m.setAccessible(true); break;
                    }
                }
            }
        }
    }

    private Method resolveContainerGetItem(java.util.List<Method> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            containerGetItemSig = "unresolved:no-candidates";
            return null;
        }
        Method fallback = null;
        int fallbackCount = 0;
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (Method m : candidates) {
            String n = m.getName();
            names.add(n);
            if (isSafeContainerGetItemName(n)) {
                m.setAccessible(true);
                containerGetItemSig = "safe-name:" + m.getDeclaringClass().getSimpleName() + "." + n;
                return m;
            }
            if (!isDestructiveContainerGetterName(n)) {
                fallback = m;
                fallbackCount++;
            }
        }
        if (fallbackCount == 1) {
            fallback.setAccessible(true);
            containerGetItemSig = "single-nonsuspect:" + fallback.getDeclaringClass().getSimpleName()
                    + "." + fallback.getName();
            return fallback;
        }
        containerGetItemSig = "ambiguous:" + names;
        return null;
    }

    private Method resolveContainerSetItem(java.util.List<Method> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        for (Method m : candidates) {
            String n = m.getName();
            if (n.equals("setItem") || n.equals("setStack") || n.equals("setStackInSlot")
                    || n.equals("method_5447")) {
                m.setAccessible(true);
                return m;
            }
        }
        Method m = candidates.get(0);
        m.setAccessible(true);
        return m;
    }

    private static boolean isSafeContainerGetItemName(String n) {
        return n != null && (n.equals("getItem") || n.equals("getStack")
                || n.equals("getStackInSlot") || n.equals("method_5438"));
    }

    private static boolean isDestructiveContainerGetterName(String n) {
        if (n == null) return true;
        String s = n.toLowerCase(java.util.Locale.ROOT);
        return s.contains("remove") || s.contains("take") || s.contains("extract")
                || s.contains("clear") || s.contains("split") || s.contains("pop")
                || s.equals("method_5441") || s.equals("method_5434");
    }

    /**
     * Desambigua {@link #mContainerGetSize} por COMPORTAMIENTO (§3.18) sobre instancias
     * reales de Container. {@code Container} expone varios no-arg int — al menos
     * {@code getContainerSize()} y {@code getMaxStackSize()}. El último devuelve una
     * constante (64/99) idéntica en todo container; el primero es el conteo de slots, que
     * varía entre inventario (41) y enderchest (27). El método correcto es el que produce
     * valores DISTINTOS entre las instancias. Con una sola instancia no se puede distinguir
     * por varianza → se descarta el candidato cuyo valor parece constante de stack (64/99).
     * Idempotente: una vez fijado, no reevalúa.
     */
    private void disambiguateContainerSize(java.util.List<Object> instances) {
        if (containerSizeDisambiguated) return;
        if (containerSizeCandidates.size() <= 1) { containerSizeDisambiguated = true; return; }
        if (instances == null || instances.isEmpty()) return;
        Method varying = null;        // da valores distintos entre instancias
        Method nonStacky = null;      // único cuyo valor no es 64/99 (fallback 1 instancia)
        for (Method cand : containerSizeCandidates) {
            java.util.List<Integer> vals = new java.util.ArrayList<Integer>();
            boolean ok = true;
            for (Object inst : instances) {
                try { vals.add(((Integer) cand.invoke(inst)).intValue()); }
                catch (Throwable t) { ok = false; break; }
            }
            if (!ok || vals.isEmpty()) continue;
            boolean distinct = false;
            for (int i = 1; i < vals.size(); i++) {
                if (!vals.get(i).equals(vals.get(0))) { distinct = true; break; }
            }
            if (distinct && varying == null) varying = cand;
            int v0 = vals.get(0).intValue();
            if (v0 != 64 && v0 != 99 && v0 > 0 && v0 < 256 && nonStacky == null) nonStacky = cand;
        }
        Method chosen = varying != null ? varying : (nonStacky != null ? nonStacky : mContainerGetSize);
        if (chosen != null) {
            chosen.setAccessible(true);
            mContainerGetSize = chosen;
            // stats/diagnóstico: cachear la firma elegida (patrón §3.18).
            t1log("containerSize disambiguated → " + chosen.getName()
                + " (candidates=" + containerSizeCandidates.size()
                + " instances=" + instances.size()
                + " by=" + (varying != null ? "variance" : nonStacky != null ? "non-stack" : "fallback") + ")");
        }
        // Solo marcar resuelto si lo decidimos por varianza (>=2 instancias distintas),
        // que es la señal fuerte. Con fallback dejamos abierta una reevaluación futura.
        if (varying != null) containerSizeDisambiguated = true;
    }

    private List<Object> livePlayers(Object srv) {
        try {
            if (mGetPlayerList == null) {
                // SIEMPRE por COMPORTAMIENTO: el nombre no es confiable entre versiones de 1.21.x.
                // Buscamos cualquier no-arg cuyo resultado tenga un no-arg que devuelve List<?>
                // con elementos que exponen getUUID(): UUID. Sobrevive a cambios de mapeo.
                if (mGetPlayerList == null) {
                    try { Agent.openModule(Agent.instrumentation, srv.getClass()); } catch (Throwable ignored) {}
                    for (Method m : srv.getClass().getMethods()) {
                        if (Modifier.isStatic(m.getModifiers())) continue;
                        if (m.getParameterTypes().length != 0) continue;
                        Class<?> rt = m.getReturnType();
                        if (rt.isPrimitive() || rt == void.class || rt == String.class) continue;
                        Object pl;
                        try { m.setAccessible(true); pl = m.invoke(srv); }
                        catch (Throwable t) { continue; }
                        if (pl == null) continue;
                        // ¿pl tiene un no-arg que devuelve List<?> con elementos con getUUID()?
                        for (Method gp : pl.getClass().getMethods()) {
                            if (Modifier.isStatic(gp.getModifiers())) continue;
                            if (gp.getParameterTypes().length != 0) continue;
                            if (!List.class.isAssignableFrom(gp.getReturnType())) continue;
                            try {
                                gp.setAccessible(true);
                                Object l = gp.invoke(pl);
                                if (!(l instanceof List)) continue;
                                List<?> lst = (List<?>) l;
                                if (lst.isEmpty()) {
                                    // List vacía — heurística: si los nombres encajan, lo acepto tentativamente
                                    String gn = gp.getName();
                                    if (gn.equals("getPlayers") || gn.equals("method_14571")) {
                                        mGetPlayerList = m; mPlayerListGetPlayers = gp;
                                        t1log("playerList found by form (empty list): " + m.getName() + "().<" + gn + ">");
                                        break;
                                    }
                                    continue;
                                }
                                Object first = lst.get(0);
                                if (first == null) continue;
                                boolean hasUuid = false;
                                for (Method um : first.getClass().getMethods()) {
                                    if (um.getParameterTypes().length == 0 && um.getReturnType() == java.util.UUID.class) {
                                        hasUuid = true; break;
                                    }
                                }
                                if (hasUuid) {
                                    mGetPlayerList = m; mPlayerListGetPlayers = gp;
                                    t1log("playerList found by form: " + m.getName() + "().<" + gp.getName() + ">"
                                        + " firstElement=" + first.getClass().getSimpleName());
                                    break;
                                }
                            } catch (Throwable ignored) {}
                        }
                        if (mGetPlayerList != null) break;
                    }
                }
            }
            if (mGetPlayerList == null) { t1log("livePlayers: NO getPlayerList found"); return java.util.Collections.emptyList(); }
            Object pl = mGetPlayerList.invoke(srv);
            if (pl == null) { t1log("livePlayers: getPlayerList returned null"); return java.util.Collections.emptyList(); }
            if (mPlayerListGetPlayers == null) {
                for (Method m : pl.getClass().getMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                    String n = m.getName();
                    if (n.equals("getPlayers") || n.equals("method_14571")) {
                        mPlayerListGetPlayers = m; m.setAccessible(true); break;
                    }
                }
                if (mPlayerListGetPlayers == null) {
                    // form-based: first no-arg returning List whose elements have getUUID
                    for (Method m : pl.getClass().getMethods()) {
                        if (Modifier.isStatic(m.getModifiers())) continue;
                        if (m.getParameterTypes().length != 0) continue;
                        if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                        try { m.setAccessible(true); Object r = m.invoke(pl);
                            if (r instanceof List && !((List<?>) r).isEmpty()) {
                                Object first = ((List<?>) r).get(0);
                                for (Method mm : first.getClass().getMethods()) {
                                    if (mm.getParameterTypes().length == 0 && mm.getReturnType() == java.util.UUID.class) {
                                        mPlayerListGetPlayers = m;
                                        if (mPlayerGetUuid == null) { mPlayerGetUuid = mm; mm.setAccessible(true); }
                                        break;
                                    }
                                }
                                if (mPlayerListGetPlayers != null) break;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
            @SuppressWarnings("unchecked")
            List<Object> players = mPlayerListGetPlayers != null
                    ? (List<Object>) mPlayerListGetPlayers.invoke(pl) : java.util.Collections.<Object>emptyList();
            if (!players.isEmpty()) {
                Object p0 = players.get(0);
                Agent.openModule(Agent.instrumentation, p0.getClass());
                if (mPlayerGetUuid == null) {
                    for (Method m : p0.getClass().getMethods()) {
                        if (Modifier.isStatic(m.getModifiers())) continue;
                        if (m.getParameterTypes().length == 0 && m.getReturnType() == java.util.UUID.class) {
                            mPlayerGetUuid = m; m.setAccessible(true); break;
                        }
                    }
                }
                // getInventory / getEnderChest: descubrir POR FIELDS en la jerarquía del player.
                // Los métodos getInventory() / getEnderChestInventory() son delgados — devuelven
                // un field privado. En 1.21.x los métodos tienen nombres intermediary (method_*)
                // impredecibles, pero los FIELDS tipados son estables: Inventory tiene 41 slots,
                // PlayerEnderChestContainer tiene 27. Iteramos la jerarquía completa con
                // getDeclaredFields() y buscamos por comportamiento (Container con size esperado).
                if (fPlayerInventory == null || fPlayerEnderChest == null) {
                    // Paso 1: recolectar TODOS los fields tipados Container de la jerarquía,
                    // sin clasificar todavía (necesitamos un mContainerGetSize fiable primero).
                    java.util.List<Field> containerFields = new java.util.ArrayList<Field>();
                    java.util.List<Object> containerInstances = new java.util.ArrayList<Object>();
                    Class<?> c = p0.getClass();
                    while (c != null && c != Object.class) {
                        for (Field f : c.getDeclaredFields()) {
                            if (Modifier.isStatic(f.getModifiers())) continue;
                            try {
                                f.setAccessible(true);
                                Object v = f.get(p0);
                                if (v == null) continue;
                                if (containerInterface == null || !containerInterface.isInstance(v)) continue;
                                containerFields.add(f);
                                containerInstances.add(v);
                            } catch (Throwable ignored) {}
                        }
                        c = c.getSuperclass();
                    }
                    // Paso 2: desambiguar mContainerGetSize POR COMPORTAMIENTO (§3.18).
                    // Container expone getContainerSize() y getMaxStackSize(); ambos son no-arg int.
                    // getMaxStackSize devuelve la MISMA constante (64/99) en todo container;
                    // getContainerSize varía (inventario=41, enderchest=27). El método correcto es
                    // el que da valores DISTINTOS entre las instancias del jugador.
                    disambiguateContainerSize(containerInstances);
                    // Paso 3: clasificar con el método ya fiable. enderchest=27, inventario 36..60.
                    java.util.List<String> diag = new java.util.ArrayList<String>();
                    for (int i = 0; i < containerFields.size(); i++) {
                        Field f = containerFields.get(i);
                        Object v = containerInstances.get(i);
                        int size;
                        try { size = ((Integer) mContainerGetSize.invoke(v)).intValue(); }
                        catch (Throwable t) { continue; }
                        diag.add(f.getDeclaringClass().getSimpleName() + "." + f.getName() + " size=" + size
                            + " type=" + v.getClass().getSimpleName());
                        if (size == 27 && fPlayerEnderChest == null) {
                            fPlayerEnderChest = f;
                        } else if (size >= 36 && size <= 60 && fPlayerInventory == null) {
                            fPlayerInventory = f;
                        }
                    }
                    t1log("player fields scan: " + diag + " sizeMethod="
                        + (mContainerGetSize != null ? mContainerGetSize.getName() : "<null>"));
                }
            }
            return players;
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    private void ensureChunkIteration(Object lvl) throws Exception {
        if (fChunkMapInChunkSource != null && mChunkMapGetChunks != null && mChunkHolderGetChunk != null
                && mChunkGetBlockEntities != null && mLevelGetBlockEntity != null) return;
        Object cs;
        try { cs = mGetChunkSource.invoke(lvl); } catch (Throwable t) { return; }
        if (cs == null) return;
        // ChunkMap field: in ServerChunkCache, the field whose Iterable elements are ChunkHolders
        // (no players, no entidades — eso era el bug previo). Validamos por COMPORTAMIENTO:
        // tomamos el primer elemento del Iterable, y verificamos que tiene un no-arg que devuelve
        // un objeto con getBlockEntities() o equivalente.
        for (Field f : cs.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> ft = f.getType();
            if (ft.isPrimitive() || ft == String.class || ft == void.class) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(cs);
                if (v == null) continue;
                // Buscar cualquier no-arg que devuelva Iterable
                for (Method m : v.getClass().getMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    if (!Iterable.class.isAssignableFrom(m.getReturnType())) continue;
                    try {
                        m.setAccessible(true);
                        Iterable<?> it = (Iterable<?>) m.invoke(v);
                        if (it == null) continue;
                        // Sampling: ¿el primer elemento parece un ChunkHolder?
                        // Un ChunkHolder es algo que tiene un no-arg que devuelve un objeto con
                        // getBlockEntities() (= LevelChunk). Si encontramos eso, este es el método correcto.
                        Object first = null;
                        for (Object o : it) { if (o != null) { first = o; break; } }
                        if (first == null) continue;
                        // Test: ¿first.<no-arg>() → algo con getBlockEntities?
                        boolean isChunkHolder = false;
                        for (Method hm : first.getClass().getMethods()) {
                            if (Modifier.isStatic(hm.getModifiers())) continue;
                            if (hm.getParameterTypes().length != 0) continue;
                            Class<?> hrt = hm.getReturnType();
                            if (hrt.isPrimitive() || hrt == void.class || hrt == String.class) continue;
                            try {
                                hm.setAccessible(true);
                                Object c = hm.invoke(first);
                                if (c == null) continue;
                                if (findBlockEntitiesMethod(c.getClass()) != null) {
                                    isChunkHolder = true;
                                    break;
                                }
                            } catch (Throwable ignored) {}
                        }
                        if (isChunkHolder) {
                            fChunkMapInChunkSource = f;
                            mChunkMapGetChunks = m;
                            t1log("chunkMap discovered: " + cs.getClass().getSimpleName() + "." + f.getName()
                                + " → " + v.getClass().getSimpleName() + "." + m.getName()
                                + " (firstHolder=" + first.getClass().getSimpleName() + ")");
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
                if (fChunkMapInChunkSource != null) break;
            } catch (Throwable ignored) {}
        }
        // ChunkHolder.getChunk → LevelChunk: descubrimiento POR FORMA (no por nombre).
        // Iteramos cada no-arg de ChunkHolder, lo invocamos, y si el resultado tiene un
        // no-arg que devuelve Map cuyas claves son BlockPos → es getBlockEntities.
        if (fChunkMapInChunkSource != null) {
            try {
                Object chunkMap = fChunkMapInChunkSource.get(cs);
                Iterable<?> holders = (Iterable<?>) mChunkMapGetChunks.invoke(chunkMap);
                Object firstHolder = null;
                for (Object h : holders) { if (h != null) { firstHolder = h; break; } }
                if (firstHolder != null) {
                    Agent.openModule(Agent.instrumentation, firstHolder.getClass());
                    for (Method m : firstHolder.getClass().getMethods()) {
                        if (Modifier.isStatic(m.getModifiers())) continue;
                        if (m.getParameterTypes().length != 0) continue;
                        Class<?> rt = m.getReturnType();
                        if (rt.isPrimitive() || rt == void.class || rt == String.class) continue;
                        Object c;
                        try { m.setAccessible(true); c = m.invoke(firstHolder); }
                        catch (Throwable t) { continue; }
                        if (c == null) continue;
                        // ¿tiene getBlockEntities(): Map?
                        Method beMethod = findBlockEntitiesMethod(c.getClass());
                        if (beMethod != null) {
                            mChunkHolderGetChunk = m;
                            mChunkGetBlockEntities = beMethod;
                            t1log("chunkHolder.getChunk resolved as " + firstHolder.getClass().getSimpleName() + "." + m.getName()
                                + " → " + c.getClass().getSimpleName() + "." + beMethod.getName());
                            break;
                        }
                    }
                }
            } catch (Throwable t) { t1log("chunkHolder discovery threw: " + t); }
        }
        // Level.getBlockEntity(BlockPos): BlockEntity
        for (Method m : lvl.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1) continue;
            if (!ps[0].getName().equals("net.minecraft.class_2338")
                    && !ps[0].getName().equals("net.minecraft.core.BlockPos")) continue;
            String rn = m.getReturnType().getName();
            String n = m.getName();
            if (n.equals("getBlockEntity") || n.equals("method_8321")) {
                mLevelGetBlockEntity = m; m.setAccessible(true); break;
            }
        }
        // BlockEntity.setChanged()
        if (mBlockEntitySetChanged == null && mChunkHolderGetChunk != null) {
            try {
                Object chunkMap = fChunkMapInChunkSource.get(cs);
                Iterable<?> holders = (Iterable<?>) mChunkMapGetChunks.invoke(chunkMap);
                outer:
                for (Object h : holders) {
                    Object c = mChunkHolderGetChunk.invoke(h);
                    if (c == null) continue;
                    Object bem = mChunkGetBlockEntities.invoke(c);
                    if (!(bem instanceof Map)) continue;
                    for (Object be : ((Map<?, ?>) bem).values()) {
                        if (be == null) continue;
                        for (Method m : be.getClass().getMethods()) {
                            if (Modifier.isStatic(m.getModifiers())) continue;
                            if (m.getParameterTypes().length != 0) continue;
                            if (m.getReturnType() != void.class) continue;
                            if (m.getName().equals("setChanged") || m.getName().equals("method_5431")) {
                                mBlockEntitySetChanged = m; m.setAccessible(true);
                                break outer;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private String resolveLevelDimensionKey(Object lvl) {
        try {
            if (mLevelDimension == null) {
                for (Method m : lvl.getClass().getMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    String n = m.getName();
                    if (n.equals("dimension") || n.equals("method_27983")) {
                        mLevelDimension = m; m.setAccessible(true); break;
                    }
                }
            }
            if (mLevelDimension == null) return "minecraft:overworld";
            Object key = mLevelDimension.invoke(lvl);
            if (key == null) return "minecraft:overworld";
            if (mResourceKeyLocation == null) {
                for (Method m : key.getClass().getMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    String rn = m.getReturnType().getName();
                    String n = m.getName();
                    if ((n.equals("location") || n.equals("method_29177")) &&
                            (rn.endsWith("ResourceLocation") || rn.endsWith("Identifier") || rn.endsWith("class_2960"))) {
                        mResourceKeyLocation = m; m.setAccessible(true); break;
                    }
                }
            }
            if (mResourceKeyLocation == null) return key.toString();
            Object loc = mResourceKeyLocation.invoke(key);
            return loc == null ? "minecraft:overworld" : loc.toString();
        } catch (Throwable t) {
            return "minecraft:overworld";
        }
    }

    // ====================== item helpers ======================

    private boolean isEmptyStack(Object stack) {
        if (stack == null) return true;
        if (emptyItemStack != null && stack == emptyItemStack) return true;
        try { return Boolean.TRUE.equals(mItemStackIsEmpty.invoke(stack)); }
        catch (Throwable t) { return false; }
    }

    private String itemNamespace(Object stack) {
        try {
            Object item = mGetItem.invoke(stack);
            if (item == null) return null;
            if (itemGetId == null || itemRegistry == null) return null;
            Object id = itemGetId.invoke(itemRegistry, item);
            if (id == null) return null;
            String s = id.toString();
            int colon = s.indexOf(':');
            return colon > 0 ? s.substring(0, colon) : null;
        } catch (Throwable t) { return null; }
    }

    private byte[] serializeItemStack(Object stack) {
        try {
            if (mItemStackSave == null || cachedProvider == null) return null;
            Object tag = mItemStackSave.invoke(stack, cachedProvider);
            if (tag == null) return null;
            return Ledger.INSTANCE.nbtBytes(tag);
        } catch (Throwable t) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Object deserializeItemStack(byte[] nbt) {
        try {
            if (mItemStackParseStatic == null || cachedProvider == null) return null;
            Class<?> cc = Ledger.INSTANCE.compoundTagClass();
            if (cc == null) return null;
            Object tag = Ledger.INSTANCE.tagFromBytes(nbt, cc);
            if (tag == null) return null;
            Object opt = mItemStackParseStatic.invoke(null, cachedProvider, tag);
            if (opt instanceof java.util.Optional) {
                java.util.Optional<Object> o = (java.util.Optional<Object>) opt;
                return o.orElse(null);
            }
            return null;
        } catch (Throwable t) { return null; }
    }

    private String itemId(Object stack) {
        try {
            Object item = mGetItem.invoke(stack);
            if (item == null || itemGetId == null || itemRegistry == null) return null;
            Object id = itemGetId.invoke(itemRegistry, item);
            return id == null ? null : id.toString();
        } catch (Throwable t) { return null; }
    }

    private Object resolveItemById(String id) {
        try {
            ensureItemRegistry();
            if (itemRegistry == null || itemGetId == null || id == null || id.isEmpty()) return null;
            for (Object item : (Iterable<?>) itemRegistry) {
                Object itemId = itemGetId.invoke(itemRegistry, item);
                if (itemId != null && id.equals(itemId.toString())) return item;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Object defaultStackForItem(Object item) {
        if (item == null || itemStackClass == null) return null;
        try {
            if (mItemDefaultInstance == null || !itemStackClass.isAssignableFrom(mItemDefaultInstance.getReturnType())) {
                mItemDefaultInstance = null;
                for (Method m : item.getClass().getMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    if (!itemStackClass.isAssignableFrom(m.getReturnType())) continue;
                    String n = m.getName();
                    if (n.equals("getDefaultInstance") || n.equals("defaultInstance")
                            || n.equals("method_7854") || n.equals("method_7935")) {
                        m.setAccessible(true);
                        mItemDefaultInstance = m;
                        break;
                    }
                    if (mItemDefaultInstance == null) {
                        m.setAccessible(true);
                        mItemDefaultInstance = m;
                    }
                }
            }
            if (mItemDefaultInstance != null) {
                Object stack = mItemDefaultInstance.invoke(item);
                if (stack != null) return stack;
            }
        } catch (Throwable ignored) {}
        try {
            for (Constructor<?> c : itemStackClass.getDeclaredConstructors()) {
                Class<?>[] ps = c.getParameterTypes();
                if (ps.length != 1) continue;
                if (!ps[0].isAssignableFrom(item.getClass()) && !item.getClass().isAssignableFrom(ps[0])) continue;
                c.setAccessible(true);
                Object stack = c.newInstance(item);
                if (stack != null) return stack;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Object makeItemStack(String itemId) {
        try {
            ensureItemMethods(server != null ? server : Boot.clientInstance);
            Object item = resolveItemById(itemId);
            return defaultStackForItem(item);
        } catch (Throwable t) {
            return null;
        }
    }

    public Map<String, Object> inventoryFixture(final String vanillaItemId, final String victimItemId) {
        if (vanillaItemId == null || vanillaItemId.isEmpty()) {
            return fail("BAD_PARAMS", "inventoryFixture requiere vanillaItemId");
        }
        if (victimItemId == null || victimItemId.isEmpty()) {
            return fail("BAD_PARAMS", "inventoryFixture requiere victimItemId");
        }
        return runOnServer((srv, holder) -> attemptInventoryFixture(srv, vanillaItemId, victimItemId, holder));
    }

    public Map<String, Object> inventorySnapshot() {
        return runOnServer((srv, holder) -> attemptInventorySnapshot(srv, holder));
    }

    public String modThinInventoryRegression(String victimNs, String vanillaItemId, String victimItemId) {
        Map<String, Object> r = runOnServer((srv, holder) ->
                attemptInventoryRegression(srv, victimNs, vanillaItemId, victimItemId, holder));
        return Agent.Json.write(r);
    }

    private void attemptInventoryFixture(Object candidate, String vanillaItemId, String victimItemId,
                                         Map<String, Object> holder) throws Exception {
        List<Object> lvls = ensureLevels(candidate);
        if (lvls == null || lvls.isEmpty()) return;
        server = candidate;
        ensureLevelMethods(lvls.get(0));
        ensureItemMethods(candidate);
        List<Object> players = livePlayers(candidate);
        if (players.isEmpty()) {
            holder.put("code", "NOT_READY");
            holder.put("error", "no hay jugador vivo para sembrar inventario");
            return;
        }
        Object p = players.get(0);
        Object inv = readField(fPlayerInventory, p);
        if (inv == null) {
            holder.put("code", "INTERNAL");
            holder.put("error", "no se pudo leer el inventario del jugador");
            return;
        }
        Object vanilla = makeItemStack(vanillaItemId);
        Object victim = makeItemStack(victimItemId);
        if (vanilla == null || victim == null) {
            holder.put("code", "INTERNAL");
            holder.put("error", "no se pudieron construir stacks: vanilla=" + (vanilla != null)
                    + " victim=" + (victim != null));
            return;
        }
        int size = ((Integer) mContainerGetSize.invoke(inv)).intValue();
        int vanillaSlot = -1;
        int victimSlot = -1;
        for (int i = 0; i < size; i++) {
            Object cur = mContainerGetItem.invoke(inv, Integer.valueOf(i));
            if (cur == null || isEmptyStack(cur)) {
                if (vanillaSlot < 0) vanillaSlot = i;
                else if (victimSlot < 0) { victimSlot = i; break; }
            }
        }
        if (vanillaSlot < 0 || victimSlot < 0) {
            holder.put("code", "INTERNAL");
            holder.put("error", "no hay dos slots vacios para la fixture");
            return;
        }
        mContainerSetItem.invoke(inv, Integer.valueOf(vanillaSlot), vanilla);
        mContainerSetItem.invoke(inv, Integer.valueOf(victimSlot), victim);

        java.util.List<Map<String, Object>> snapshot = snapshotInventory(inv);
        holder.put("ok", Boolean.TRUE);
        holder.put("vanillaItemId", vanillaItemId);
        holder.put("victimItemId", victimItemId);
        holder.put("vanillaSlot", vanillaSlot);
        holder.put("victimSlot", victimSlot);
        holder.put("snapshot", snapshot);
    }

    private void attemptInventorySnapshot(Object candidate, Map<String, Object> holder) throws Exception {
        List<Object> lvls = ensureLevels(candidate);
        if (lvls == null || lvls.isEmpty()) return;
        server = candidate;
        ensureLevelMethods(lvls.get(0));
        ensureItemMethods(candidate);
        List<Object> players = livePlayers(candidate);
        if (players.isEmpty()) {
            holder.put("code", "NOT_READY");
            holder.put("error", "no hay jugador vivo para leer inventario");
            return;
        }
        Object inv = readField(fPlayerInventory, players.get(0));
        if (inv == null) {
            holder.put("code", "INTERNAL");
            holder.put("error", "no se pudo leer el inventario del jugador");
            return;
        }
        holder.put("ok", Boolean.TRUE);
        holder.put("snapshot", snapshotInventory(inv));
    }

    private void attemptInventoryRegression(Object candidate, String victimNs, String vanillaItemId,
                                            String victimItemId, Map<String, Object> holder) throws Exception {
        List<Object> lvls = ensureLevels(candidate);
        if (lvls == null || lvls.isEmpty()) return;
        server = candidate;
        ensureLevelMethods(lvls.get(0));
        ensureItemMethods(candidate);
        List<Object> players = livePlayers(candidate);
        if (players.isEmpty()) {
            holder.put("code", "NOT_READY");
            holder.put("error", "no hay jugador vivo para la regresion");
            return;
        }
        Object inv = readField(fPlayerInventory, players.get(0));
        if (inv == null) {
            holder.put("code", "INTERNAL");
            holder.put("error", "no se pudo leer el inventario del jugador");
            return;
        }

        Object vanilla = makeItemStack(vanillaItemId);
        Object victim = makeItemStack(victimItemId);
        if (vanilla == null || victim == null) {
            holder.put("code", "INTERNAL");
            holder.put("error", "no se pudieron construir stacks");
            return;
        }

        int size = ((Integer) mContainerGetSize.invoke(inv)).intValue();
        int vanillaSlot = -1;
        int victimSlot = -1;
        for (int i = 0; i < size; i++) {
            Object cur = mContainerGetItem.invoke(inv, Integer.valueOf(i));
            if (cur == null || isEmptyStack(cur)) {
                if (vanillaSlot < 0) vanillaSlot = i;
                else if (victimSlot < 0) { victimSlot = i; break; }
            }
        }
        if (vanillaSlot < 0 || victimSlot < 0) {
            holder.put("code", "INTERNAL");
            holder.put("error", "no hay dos slots vacios para la fixture");
            return;
        }

        mContainerSetItem.invoke(inv, Integer.valueOf(vanillaSlot), vanilla);
        mContainerSetItem.invoke(inv, Integer.valueOf(victimSlot), victim);

        Map<String, Object> sweep = new java.util.HashMap<String, Object>();
        attemptItemSweep(candidate, victimNs, "mksa-test-inventory-regression", sweep);
        if (!Boolean.TRUE.equals(sweep.get("ok"))) {
            holder.put("code", String.valueOf(sweep.get("code")));
            holder.put("error", String.valueOf(sweep.get("error")));
            return;
        }

        java.util.List<Map<String, Object>> snapshot = snapshotInventory(inv);
        Map<String, Object> vanillaRow = null;
        Map<String, Object> victimRow = null;
        for (Map<String, Object> row : snapshot) {
            Object slotObj = row.get("slot");
            if (!(slotObj instanceof Integer)) continue;
            int slot = ((Integer) slotObj).intValue();
            if (slot == vanillaSlot) vanillaRow = row;
            if (slot == victimSlot) victimRow = row;
        }

        boolean vanillaStillThere = vanillaRow != null && !Boolean.TRUE.equals(vanillaRow.get("empty"))
                && "minecraft:air".equals(String.valueOf(vanillaRow.get("itemId"))) == false;
        boolean victimGone = victimRow != null && Boolean.TRUE.equals(victimRow.get("empty"));

        holder.put("ok", Boolean.valueOf(vanillaStillThere && victimGone));
        holder.put("victimNs", victimNs);
        holder.put("vanillaItemId", vanillaItemId);
        holder.put("victimItemId", victimItemId);
        holder.put("vanillaSlot", vanillaSlot);
        holder.put("victimSlot", victimSlot);
        holder.put("vanillaAfter", vanillaRow);
        holder.put("victimAfter", victimRow);
        holder.put("sweep", sweep);
        holder.put("vanillaPreserved", Boolean.valueOf(vanillaStillThere));
        holder.put("victimRemoved", Boolean.valueOf(victimGone));
        if (!vanillaStillThere) {
            holder.put("code", "REGRESSION");
            holder.put("error", "la pila vanilla fue borrada");
        } else if (!victimGone) {
            holder.put("code", "REGRESSION");
            holder.put("error", "la pila victima no fue borrada");
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.List<Map<String, Object>> snapshotInventory(Object container) {
        java.util.List<Map<String, Object>> slots = new java.util.ArrayList<Map<String, Object>>();
        try {
            int size = ((Integer) mContainerGetSize.invoke(container)).intValue();
            for (int i = 0; i < size; i++) {
                Object stack = mContainerGetItem.invoke(container, Integer.valueOf(i));
                Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
                row.put("slot", Integer.valueOf(i));
                row.put("empty", Boolean.valueOf(stack == null || isEmptyStack(stack)));
                row.put("itemId", stack == null || isEmptyStack(stack) ? "minecraft:air" : itemId(stack));
                slots.add(row);
            }
        } catch (Throwable t) {
            Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
            row.put("error", String.valueOf(t));
            slots.add(row);
        }
        return slots;
    }

    private static Object invoke(Method m, Object target) {
        if (m == null) return null;
        try { return m.invoke(target); }
        catch (Throwable t) { return null; }
    }

    /** Encuentra el método getBlockEntities(): Map<BlockPos,BlockEntity> en una clase de Chunk. */
    private static Method findBlockEntitiesMethod(Class<?> chunkClass) {
        // Iteramos toda la jerarquía (incluyendo padres) por si el método está heredado de Chunk/LevelChunk.
        for (Method m : chunkClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (!Map.class.isAssignableFrom(m.getReturnType())) continue;
            String n = m.getName();
            // Nombres conocidos (mojmap+intermediary). Si no, comprobamos forma del Map al
            // invocarlo desde el caller (claves BlockPos), pero aquí ya es buen filtro.
            if (n.equals("getBlockEntities") || n.equals("method_12214") || n.equals("method_38299")) {
                m.setAccessible(true);
                return m;
            }
        }
        // Fallback agnóstico de nombre: primer no-arg que retorne Map<?,?>.
        // Lo aceptamos solo si no hay candidato con nombre conocido.
        for (Method m : chunkClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (!Map.class.isAssignableFrom(m.getReturnType())) continue;
            // skip cosas obvias del JDK
            if (m.getDeclaringClass() == Object.class) continue;
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    /** Lee un Field de un target (con setAccessible). null si falla. */
    private static Object readField(Field f, Object target) {
        if (f == null) return null;
        try { f.setAccessible(true); return f.get(target); }
        catch (Throwable t) { return null; }
    }

    /** True si el nombre de clase corresponde a un Tag (class_2520 / net.minecraft.nbt.Tag o subclase). */
    private static boolean isTagTypeName(String name) {
        if (name == null) return false;
        return name.equals("net.minecraft.class_2520") || name.equals("net.minecraft.nbt.Tag")
            || name.equals("net.minecraft.class_2487") || name.equals("net.minecraft.nbt.CompoundTag")
            || name.startsWith("net.minecraft.class_25") || name.startsWith("net.minecraft.nbt.");
    }

    /** True si el nombre de clase corresponde a HolderLookup.Provider / RegistryAccess.* */
    private static boolean isProviderTypeName(String name) {
        if (name == null) return false;
        return name.equals("net.minecraft.class_7225$class_7874")     // HolderLookup.Provider intermediary
            || name.startsWith("net.minecraft.class_7225")             // HolderLookup / HolderLookup$Provider
            || name.startsWith("net.minecraft.class_5455")             // RegistryAccess + RegistryAccess$Frozen
            || name.startsWith("net.minecraft.class_6903")             // RegistryOps
            || name.equals("net.minecraft.core.HolderLookup$Provider")
            || name.equals("net.minecraft.core.RegistryAccess")
            || name.equals("net.minecraft.core.RegistryAccess$Frozen");
    }
}
