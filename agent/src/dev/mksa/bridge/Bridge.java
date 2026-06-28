package dev.mksa.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Puente del ledger, cargado en el BOOTSTRAP classloader
 * (Instrumentation.appendToBootstrapClassLoaderSearch).
 *
 * Razón de existir: el bytecode que el LedgerTransformer inyecta en clases de
 * Minecraft emite `INVOKESTATIC dev/mksa/bridge/Bridge.xxx`. Para que ese enlace
 * resuelva, la clase debe ser visible desde el classloader que cargó la clase
 * de MC (Knot de Fabric, TransformingClassLoader de NeoForge). El bootstrap es
 * padre de todos → visible en todas partes. Es la mitigación del riesgo nº1
 * (visibilidad entre classloaders) del plan del inc.5.
 *
 * Por vivir en el bootstrap, esta clase NO PUEDE referenciar ninguna clase de
 * Minecraft en compilación (el bootstrap no las ve). Todo se hace por reflexión
 * sobre los Object que recibe. Solo depende del JDK.
 *
 * Paso 1 (de-risk): probar inyección + persistencia + recarga. writeChunkProv
 * añade una sección `mksa:prov` mínima ({v, epoch}) al CompoundTag del chunk en
 * el momento de serializar; readChunkProv detecta la sección al deserializar un
 * chunk leído de disco. Los contadores los lee el agente por IPC (ledger.stats).
 * La carga real (live set, containers, agencia) llega en el Paso 2.
 */
public final class Bridge {

    /** Versión del esquema de la sección mksa:prov (modelo-de-datos §5.1). */
    private static final int PROV_V = 1;
    /** Nombre de la sección dentro del NBT del chunk. */
    private static final String PROV_KEY = "mksa:prov";

    private static final AtomicLong WRITTEN = new AtomicLong();
    private static final AtomicLong READ_TOTAL = new AtomicLong();
    private static final AtomicLong READ_WITH_PROV = new AtomicLong();

    /** Cerebro del ledger (agente). Lo fija el agente tras el arranque; null = Paso 1. */
    private static volatile LedgerSink sink;

    public static void setSink(LedgerSink s) { sink = s; }

    // Reflexión sobre CompoundTag, resuelta una vez (la clase es estable en runtime).
    private static volatile boolean inited;
    private static Constructor<?> compoundCtor;   // new CompoundTag()
    private static Method putInt;                  // putInt(String, int): void
    private static Method putLong;                 // putLong(String, long): void
    private static Method put;                     // put(String, Tag): Tag
    private static Method contains;                // contains(String): boolean

    /** Un solo aviso a stderr si la reflexión falla, para no inundar el log. */
    private static volatile boolean warned;

    private Bridge() {}

    // ---- entradas invocadas por el bytecode inyectado ----

    /** Inyectado al RETURN de SerializableChunkData.write(): recibe el CompoundTag del chunk. */
    public static void writeChunkProv(Object compoundTag) {
        if (compoundTag == null) return;
        try {
            init(compoundTag.getClass());
            // El sink (agente) escanea la palette y CONSTRUYE la sección entera
            // (v, epoch, agency); el Bridge solo la estampa. Sin sink (o si su
            // construcción falla) cae al mínimo {v, epoch:0}. El sink nunca lanza,
            // pero por si acaso va en try/catch.
            Object prov = null;
            LedgerSink s = sink;
            if (s != null) {
                try { prov = s.onChunkWrite(compoundTag); }
                catch (Throwable t) { warnOnce("onChunkWrite", t); }
            }
            if (prov == null) {
                prov = compoundCtor.newInstance();
                putInt.invoke(prov, "v", PROV_V);
                putLong.invoke(prov, "epoch", 0L);
            }
            put.invoke(compoundTag, PROV_KEY, prov);
            WRITTEN.incrementAndGet();
        } catch (Throwable t) {
            warnOnce("writeChunkProv", t);
        }
    }

    /**
     * Inyectado ANTES del ARETURN de LevelChunk.setBlockState(BlockPos, BlockState, …).
     * Recibe el {@code newState} (param de entrada) y el {@code prevState} (valor que
     * el método estaba a punto de devolver). {@code prevState} puede ser null cuando
     * el método retorna sin haber cambiado nada (chunk descargado, mismo state, etc.):
     * el sink debe tratarlo como no-op para el agregado. Tier 1 corte 2.
     */
    public static void onSetBlock(Object pos, Object newState, Object prevState) {
        LedgerSink s = sink;
        if (s == null) return;
        try { s.onSetBlock(pos, newState, prevState); }
        catch (Throwable t) { warnOnce("onSetBlock", t); }
    }

    /**
     * Inyectado a la entrada de NbtIo.writeCompressed(CompoundTag, &lt;sink io&gt;):
     * recibe el CompoundTag que se va a escribir a disco (playerdata, level.dat).
     * Delega al sink, que decide si es un jugador y si captura (corte 3).
     */
    public static void onCompressedWrite(Object compoundTag) {
        LedgerSink s = sink;
        if (s == null) return;
        try { s.onPlayerWrite(compoundTag); }
        catch (Throwable t) { warnOnce("onPlayerWrite", t); }
    }

    /**
     * Inyectado a la entrada de TagValueInput.create(..., CompoundTag): recibe el
     * CompoundTag del jugador (host via level.dat, .dat por fallback) ANTES de que se
     * envuelva en un ValueInput (solo-lectura) y lo decodifique el codec. Permite al
     * sink mutarlo (reinyectar los items de la victima). Es el punto de convergencia
     * universal del lado-lectura (corte 4b); reemplaza al hook de NbtIo.readCompressed,
     * que el host nunca recorria.
     */
    public static void onValueInputCreate(Object compoundTag) {
        LedgerSink s = sink;
        if (s == null) return;
        try { s.onPlayerRead(compoundTag); }
        catch (Throwable t) { warnOnce("onPlayerRead", t); }
    }

    /**
     * Inyectado a la ENTRADA de LevelChunk.setBlockState(BlockPos, BlockState, …) —
     * Tier 1 corte 3. Si devuelve {@code true}, la inyección hace
     * {@code ACONST_NULL; ARETURN} sin mutar el chunk. {@code null} es la señal
     * canónica del método de "no hubo cambio" (MC mismo lo emite cuando
     * {@code prev == state}); los callers ya saben tratarlo. Hot path: sin sink, sin
     * armado y sin excepción, una rama (la JIT la inlinea trivialmente).
     */
    public static boolean shouldVeto(Object newState) {
        LedgerSink s = sink;
        if (s == null) return false;
        try { return s.shouldVeto(newState); }
        catch (Throwable t) { warnOnce("shouldVeto", t); return false; }
    }

    /**
     * Inyectado al RETURN del prepare tipado de RecipeManager (class_1863.method_64680):
     * recibe el payload {@code class_10289} con todas las recetas del reload. Si hay
     * filtro de datapack armado, el sink filtra in-place (reemplaza campos finales
     * Multimap/Map por copias sin las entradas con ns víctima). Tier 1 corte 4.
     */
    public static void dpFilterRecipePayload(Object payload) {
        LedgerSink s = sink;
        if (s == null) return;
        try { s.onRecipePayload(payload); }
        catch (Throwable t) { warnOnce("dpFilterRecipePayload", t); }
    }

    /**
     * Inyectado a la entrada del build tipado de TagManagerLoader
     * (class_3503.method_18242): recibe el {@code Map<Identifier, List<Tag>>} mutable.
     * Si hay filtro de datapack armado, el sink elimina las entradas cuyo id tenga
     * ns víctima. Tier 1 corte 4.
     */
    public static void dpFilterTagBuild(Object tagMap) {
        LedgerSink s = sink;
        if (s == null) return;
        try { s.onTagBuild(tagMap); }
        catch (Throwable t) { warnOnce("dpFilterTagBuild", t); }
    }

    /** Inyectado a la entrada de SerializableChunkData.parse(...): recibe el CompoundTag de disco. */
    public static void readChunkProv(Object compoundTag) {
        if (compoundTag == null) return;
        READ_TOTAL.incrementAndGet();
        try {
            init(compoundTag.getClass());
            Object has = contains.invoke(compoundTag, PROV_KEY);
            if (Boolean.TRUE.equals(has)) READ_WITH_PROV.incrementAndGet();
            LedgerSink s = sink;
            if (s != null) {
                try { s.onChunkRead(compoundTag); }
                catch (Throwable t) { warnOnce("onChunkRead", t); }
            }
        } catch (Throwable t) {
            warnOnce("readChunkProv", t);
        }
    }

    // ---- lectura de contadores (la usa el agente por IPC, app→bootstrap es visible) ----

    /** {written, readWithProv, readTotal} para ledger.stats. */
    public static long[] stats() {
        return new long[] { WRITTEN.get(), READ_WITH_PROV.get(), READ_TOTAL.get() };
    }

    // ---- API in-process para el mod thin (Fabric CL invoca aquí directamente) ----
    // El bootstrap es padre de todos los CL → cualquier clase del CL de Fabric puede
    // hacer `dev.mksa.bridge.Bridge.modThinXxx(...)` sin reflexión. Las llamadas
    // viajan: Fabric CL → Bridge (bootstrap) → sink (system CL, Ledger) →
    // InProcess.tier1Disable/Enable. Sin TCP, sin protocolo §2 (proyecto.md §4).

    private static String agentNotReady() {
        return "{\"ok\":false,\"error\":\"agent_not_ready\"}";
    }

    public static String modThinListMods() {
        LedgerSink s = sink;
        if (s == null) return agentNotReady();
        try { return s.modThinListMods(); }
        catch (Throwable t) { warnOnce("modThinListMods", t); return "{\"ok\":false,\"error\":\"" + t.getClass().getSimpleName() + "\"}"; }
    }

    public static String modThinDisable(String ns) {
        LedgerSink s = sink;
        if (s == null) return agentNotReady();
        try { return s.modThinDisable(ns); }
        catch (Throwable t) { warnOnce("modThinDisable", t); return "{\"ok\":false,\"error\":\"" + t.getClass().getSimpleName() + "\"}"; }
    }

    public static String modThinEnable(String ns) {
        LedgerSink s = sink;
        if (s == null) return agentNotReady();
        try { return s.modThinEnable(ns); }
        catch (Throwable t) { warnOnce("modThinEnable", t); return "{\"ok\":false,\"error\":\"" + t.getClass().getSimpleName() + "\"}"; }
    }

    public static String modThinEpoch() {
        LedgerSink s = sink;
        if (s == null) return agentNotReady();
        try { return s.modThinEpoch(); }
        catch (Throwable t) { warnOnce("modThinEpoch", t); return agentNotReady(); }
    }

    // ---- reflexión estructural sobre CompoundTag ----

    private static void init(Class<?> compound) throws Exception {
        if (inited) return;
        synchronized (Bridge.class) {
            if (inited) return;
            Constructor<?> ctor = compound.getDeclaredConstructor();
            ctor.setAccessible(true);

            Method mPutInt = null, mPut = null, mContains = null, mPutLong = null;
            for (Method m : compound.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                Class<?> ret = m.getReturnType();
                // putInt(String, int): void  — firma única
                if (mPutInt == null && p.length == 2
                        && p[0] == String.class && p[1] == int.class && ret == void.class) {
                    mPutInt = m;
                }
                // putLong(String, long): void  — firma única
                if (mPutLong == null && p.length == 2
                        && p[0] == String.class && p[1] == long.class && ret == void.class) {
                    mPutLong = m;
                }
                // put(String, Tag): Tag  — 2 params, devuelve el tipo del 2º, que
                // es supertipo de CompoundTag (Tag). Único con esa forma.
                if (mPut == null && p.length == 2
                        && p[0] == String.class && ret == p[1]
                        && p[1].isAssignableFrom(compound) && p[1] != String.class) {
                    mPut = m;
                }
                // contains(String): boolean  — único (getBoolean toma 2 params)
                if (mContains == null && p.length == 1
                        && p[0] == String.class && ret == boolean.class) {
                    mContains = m;
                }
            }
            if (mPutInt == null || mPutLong == null || mPut == null || mContains == null) {
                throw new NoSuchMethodException("CompoundTag putInt/putLong/put/contains no resueltos en " + compound);
            }
            mPutInt.setAccessible(true);
            mPutLong.setAccessible(true);
            mPut.setAccessible(true);
            mContains.setAccessible(true);
            compoundCtor = ctor;
            putInt = mPutInt;
            putLong = mPutLong;
            put = mPut;
            contains = mContains;
            inited = true;
        }
    }

    private static void warnOnce(String where, Throwable t) {
        if (warned) return;
        warned = true;
        System.err.println("[mksa] ledger bridge " + where + " falló: " + t);
    }
}
