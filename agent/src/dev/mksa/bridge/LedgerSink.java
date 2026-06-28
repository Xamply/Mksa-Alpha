package dev.mksa.bridge;

/**
 * Sumidero del ledger, implementado por el agente (CL del sistema) y llamado por
 * el {@link Bridge} (bootstrap) desde el bytecode inyectado en el serializador de
 * chunk. Vive en el bootstrap junto al Bridge para que una impl de cualquier
 * classloader la implemente (la interfaz es visible por delegación al padre) y el
 * Bridge la invoque por dispatch virtual entre classloaders.
 *
 * Contrato: ninguno de estos métodos debe lanzar — corren en el hilo del servidor
 * dentro de la (de)serialización; la impl envuelve todo en try/catch.
 */
public interface LedgerSink {

    /**
     * Invocado al serializar un chunk, con su {@code CompoundTag}. Actualiza el
     * agregado de presencia y CONSTRUYE la sección {@code mksa:prov} completa
     * ({@code v, epoch, agency}) — la agencia vive en el agente, así que el Bridge
     * solo la estampa. Devuelve el {@code CompoundTag} de la sección, o
     * {@code null} si no pudo construirla (el Bridge cae a un {@code {v, epoch:0}}).
     */
    Object onChunkWrite(Object compoundTag);

    /** Invocado al deserializar un chunk de disco; actualiza el agregado y
     *  rehidrata el mapa de agencia desde la sección {@code mksa:prov} si existe. */
    void onChunkRead(Object compoundTag);

    /**
     * Invocado por el bytecode inyectado en {@code LevelChunk.setBlockState}, con
     * el {@code BlockPos}, el {@code BlockState} NUEVO (parámetro de entrada) y el
     * {@code BlockState} PREVIO (valor devuelto por el método; {@code null} si no
     * hubo cambio o si el chunk no estaba cargado). Resuelve el actor (clase
     * llamante → mod → namespace) y registra agencia (§3.7, §4.4), y actualiza
     * incrementalmente el agregado por chunk (§5.3) cuando el namespace del
     * contenido cambia — pieza 2 del Tier 1 corte 2, que sustituye al scan post-save
     * como fuente de verdad de {@code byChunk}.
     */
    void onSetBlock(Object pos, Object newState, Object prevState);

    /**
     * Invocado por el bytecode inyectado a la entrada de
     * {@code NbtIo.writeCompressed(CompoundTag, <sink io>)} — el playerdata y
     * level.dat se persisten por ahí (corte 3). La impl filtra por presencia de la
     * clave {@code "Inventory"} (es un jugador) y, si el barrido está armado
     * ({@code ledger.sweepArm}), captura al archivo de restauración los items de la
     * víctima. Es el punto de serialización ESTABLE: el {@code CompoundTag} final a
     * disco, sea cual sea cómo se construyó (CompoundTag directo o ValueOutput de
     * 1.21.5+). No lanza (corre en el hilo del servidor dentro de la escritura).
     */
    void onPlayerWrite(Object compoundTag);

    /**
     * Invocado por el bytecode inyectado a la entrada de
     * {@code TagValueInput.create(..., CompoundTag): ValueInput} — el punto de
     * convergencia universal del lado-lectura del jugador (host vía level.dat y
     * {@code .dat} por fallback), antes de que el codec lo decodifique (el
     * {@code ValueInput} es solo-lectura). La impl filtra por jugador (claves
     * exclusivas de player NBT, no solo {@code "Inventory"}) y, si la restauración
     * está armada, reinyecta los ItemStack de la víctima en el NBT crudo. Reemplaza
     * al hook de {@code NbtIo.readCompressed}, que el host nunca recorría. No lanza.
     */
    void onPlayerRead(Object compoundTag);

    /**
     * Invocado por el bytecode inyectado a la entrada de
     * {@code LevelChunk.setBlockState(BlockPos, BlockState, …)} — Tier 1 corte 3.
     * Recibe el {@code BlockState} NUEVO (parámetro de entrada). Si la impl devuelve
     * {@code true}, la inyección hace {@code ACONST_NULL; ARETURN} — corto-circuita
     * el método ANTES de mutar. {@code null} es la señal canónica de "no hubo cambio"
     * que MC mismo emite cuando {@code prev == state}; los callers (Level.setBlock,
     * BlockItem.useOn, pistones, dispensadores) ya saben tratarla — no envían packet,
     * no disparan neighbor updates, el jugador conserva el ítem.
     *
     * Hot path: corre en cada {@code setBlockState}. La impl debe ser rauda cuando no
     * hay veto armado (lectura volatile + isEmpty). Falla cerrado: ante cualquier
     * excepción devuelve {@code false} (no vetar > silenciar bug).
     */
    boolean shouldVeto(Object newState);

    /**
     * Invocado al RETURN del prepare tipado de {@code RecipeManager} (intermediary
     * {@code class_1863.method_64680}). Recibe el {@code class_10289} (payload de
     * recetas) recién construido por la fase prepare del reload. Si hay namespace(s)
     * armado(s) por {@code dpFilterArm}, la impl filtra IN-PLACE el payload —
     * reemplazando los campos {@code field_54644}/{@code field_54645} (ImmutableMultimap
     * y ImmutableMap, ambos {@code private final}) por copias sin las entradas cuyo
     * id tenga ns víctima. La fase apply (method_20705) recibe el payload filtrado y
     * sus contenidos nunca llegan al registro vivo — sin reload roto, sin removeAll
     * post-hoc, sin packets espurios. Tier 1 corte 4 (filter dirigido a datapacks).
     *
     * Patrón "filter, no des-registrar" (§3.1) llevado al datapack: lo del namespace
     * no se construye; lo del resto pasa intacto. No lanza (corre en el hilo del
     * servidor dentro del reload).
     */
    void onRecipePayload(Object payload);

    /**
     * Invocado a la ENTRADA del build tipado de {@code TagManagerLoader} (intermediary
     * {@code class_3503.method_18242(Map<Identifier, List<Tag>>): Map<Identifier, List<T>>}).
     * El argumento es un {@code Map<class_2960, List<class_3503$class_5145>>} mutable
     * — la impl filtra in-place sus entradas cuyas keys tengan ns armado. Los tags
     * propios del namespace víctima (id = "ns:foo") se descartan; los tags vanilla
     * que contienen entradas del namespace víctima se conservan (referencias cross-ns
     * son cascade — pertenecen a F4/wcg.refs, fuera del corte 4). Tier 1 corte 4.
     */
    void onTagBuild(Object tagMap);

    // ---- API in-process para el mod thin (Fabric CL → bootstrap CL → system CL) ----
    // Todas devuelven JSON serializado (String) porque no podemos exponer tipos del
    // system CL al Fabric CL sin compartir interfaces; String es el mínimo común.
    // El mod thin parsea con su mini-parser local.

    /**
     * Lista los mods cargados con su tier y estado. Shape JSON:
     * {ok:true, mods:[{id,name,version,namespaces:[..],tier,enabled,supportedDisable}]}
     * — supportedDisable=true solo para Tier 1 (lo único que el binomio in-process
     * cubre hoy; tiers 0/2/3 mostrarán botón gris en el panel).
     */
    String modThinListMods();

    /**
     * Desactiva el namespace in-process (sweep + filter + dpReload, §3.17–§3.21).
     * Sintetiza el restorePath dentro de la instancia y delega a
     * {@code InProcess.tier1Disable}. Shape JSON: {ok,status,blocks,chunks,error?}.
     */
    String modThinDisable(String ns);

    /**
     * Restaura el namespace in-process (vetoDisarm + restitución viva + dpReload,
     * §3.22). Lee el mismo restorePath que escribió el disable; si no existe,
     * devuelve {ok:false,error:"no restore file"}. Shape JSON espejo de disable.
     */
    String modThinEnable(String ns);

    /** Hex del epoch actual (hash del modset). Para que el panel invalide caché. */
    String modThinEpoch();
}
