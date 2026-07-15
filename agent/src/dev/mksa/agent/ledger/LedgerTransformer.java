package dev.mksa.agent.ledger;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Instrumentación de bytecode del ledger (inc.5, Paso 1).
 *
 * Inyecta llamadas a {@code dev.mksa.bridge.Bridge} en el (de)serializador de
 * chunk de Minecraft. El Bridge vive en el bootstrap classloader, así que el
 * INVOKESTATIC inyectado resuelve desde el classloader de MC sea cual sea.
 *
 * Objetivos (descubiertos estructuralmente, sin hardcodear números de método):
 *
 *   SerializableChunkData — intermediary net/minecraft/class_2852,
 *                           mojmap .../storage/SerializableChunkData. (Paso 1/2)
 *     - write()  : instance, sin parametros, devuelve CompoundTag (class_2487).
 *                  Se distingue de structureData() (tambien ()CompoundTag) porque
 *                  el accesor de record se llama comp_ en intermediary; write no.
 *                  Se inyecta Bridge.writeChunkProv(tag) en cada RETURN.
 *     - parse()  : static, devuelve SerializableChunkData, toma un CompoundTag.
 *                  (copyOf tambien devuelve SCD pero sin parametro CompoundTag.)
 *                  Se inyecta Bridge.readChunkProv(tag) a la entrada.
 *
 *   LevelChunk — intermediary net/minecraft/class_2818,
 *                mojmap .../world/level/chunk/LevelChunk. (Paso 3, ledger en vivo)
 *     - setBlockState(BlockPos, BlockState, …): instance, sus dos primeros
 *                  parametros son BlockPos (class_2338) y BlockState (class_2680),
 *                  devuelve BlockState. Captura mutaciones POST-generacion, en el
 *                  mundo (no ProtoChunk → se excluye el worldgen). Se inyecta
 *                  Bridge.onSetBlock(pos, newState, prevState) ANTES de cada ARETURN
 *                  — la agencia se resuelve en el Ledger por la clase llamante, y el
 *                  prevState (valor devuelto) habilita la actualizacion incremental
 *                  de byChunk en pieza 2 del Tier 1 corte 2.
 *
 *   NbtIo — intermediary net/minecraft/class_2507, mojmap net/minecraft/nbt/NbtIo.
 *           (Corte 3, captura de playerdata por el barrido dirigido)
 *     - writeCompressed(CompoundTag, <sink io>): static, void, el 1er parametro es
 *                  CompoundTag y algun parametro posterior es un sink del JDK
 *                  (java.io.File / java.nio.file.Path / java.io.OutputStream). Es la
 *                  forma de persistir playerdata y level.dat a disco (gzip NBT). Se
 *                  excluye write(CompoundTag, DataOutput) — el writer SIN comprimir
 *                  del RegionFile — porque su sink es DataOutput, no del set. Se
 *                  inyecta Bridge.onCompressedWrite(tag) a la entrada; el Ledger
 *                  filtra por "Inventory" (jugador) y captura si el barrido esta
 *                  armado. Punto de serializacion estable frente a 1.21.5+
 *                  (ValueOutput): el CompoundTag final a disco pasa siempre por aqui.
 *
 *   TagValueInput — intermediary net/minecraft/class_11352,
 *                   mojmap net/minecraft/world/level/storage/TagValueInput.
 *                   (Corte 4b, re-inyeccion de items del playerdata)
 *     - create(...): static, devuelve ValueInput (class_11368 / mojmap ValueInput) y
 *                  toma un CompoundTag. Es el punto de convergencia UNIVERSAL del
 *                  lado-lectura: el tag del jugador —host (embebido en level.dat via
 *                  WorldData.getLoadedPlayerTag) y .dat por fallback— se envuelve aqui
 *                  en un ValueInput antes de decodificarse por codec (1.20.5+). El
 *                  CompoundTag aun es crudo y mutable a la entrada; el ValueInput es
 *                  solo-lectura. Se inyecta Bridge.onValueInputCreate(tag) a la entrada;
 *                  el Ledger filtra por jugador y reinyecta los items de la victima ANTES
 *                  de que el codec lea el tag. Simetrico inverso de onCompressedWrite.
 *                  Reemplaza al hook de NbtIo.readCompressed (el host NO lee el .dat:
 *                  ese hook nunca veia su tag).
 *
 * Se usa COMPUTE_MAXS (no COMPUTE_FRAMES): las inyecciones no alteran los frames
 * (DUP+INVOKESTATIC antes del return; ALOAD+INVOKESTATIC al entrar), así que no
 * hace falta resolver la jerarquía de clases de MC desde nuestro classloader.
 */
public final class LedgerTransformer implements ClassFileTransformer {

    private static final String BRIDGE = "dev/mksa/bridge/Bridge";

    /** Nombres internos del serializador de chunk por familia de nombres. */
    private static final String[] CHUNK_DATA_CLASSES = {
            "net/minecraft/class_2852",                                   // intermediary
            "net/minecraft/world/level/chunk/storage/SerializableChunkData", // mojmap
    };

    private static final String[] COMPOUND_TAG_CLASSES = {
            "net/minecraft/class_2487",                                   // intermediary
            "net/minecraft/nbt/CompoundTag",                              // mojmap
    };

    /** LevelChunk (chunk en el mundo, post-generación) por familia de nombres. */
    private static final String[] LEVEL_CHUNK_CLASSES = {
            "net/minecraft/class_2818",                                   // intermediary
            "net/minecraft/world/level/chunk/LevelChunk",                 // mojmap
    };

    private static final String[] BLOCK_POS_CLASSES = {
            "net/minecraft/class_2338",                                   // intermediary
            "net/minecraft/core/BlockPos",                                // mojmap
    };

    private static final String[] BLOCK_STATE_CLASSES = {
            "net/minecraft/class_2680",                                   // intermediary
            "net/minecraft/world/level/block/state/BlockState",           // mojmap
    };

    /** NbtIo (escritor de NBT a disco) por familia de nombres. (Corte 3) */
    private static final String[] NBT_IO_CLASSES = {
            "net/minecraft/class_2507",                                   // intermediary
            "net/minecraft/nbt/NbtIo",                                    // mojmap
    };

    /** TagValueInput (envoltorio de lectura por codec) por familia de nombres. (Corte 4b) */
    private static final String[] TAG_VALUE_INPUT_CLASSES = {
            "net/minecraft/class_11352",                                  // intermediary
            "net/minecraft/world/level/storage/TagValueInput",            // mojmap
    };

    /** ValueInput (tipo de retorno distintivo de TagValueInput.create) por familia. (Corte 4b) */
    private static final String[] VALUE_INPUT_CLASSES = {
            "net/minecraft/class_11368",                                  // intermediary
            "net/minecraft/world/level/storage/ValueInput",               // mojmap
    };

    /** RecipeManager (carga de recetas tras /reload) por familia de nombres. (Tier 1 corte 4) */
    private static final String[] RECIPE_MANAGER_CLASSES = {
            "net/minecraft/class_1863",                                   // intermediary
            "net/minecraft/world/item/crafting/RecipeManager",            // mojmap
    };

    /** TagManagerLoader (build de tags durante el reload) por familia. (Tier 1 corte 4) */
    private static final String[] TAG_MANAGER_LOADER_CLASSES = {
            "net/minecraft/class_3503",                                   // intermediary
            "net/minecraft/server/packs/resources/TagManager",            // mojmap (TagManagerLoader)
            "net/minecraft/server/packs/resources/TagManagerLoader",      // mojmap alterno por versión
            "net/minecraft/tags/TagLoader",                               // por si MC mueve
    };

    /** CreativeModeTab (pestaña de inventario creativo) por familia. (corte tabs, Paso A) */
    private static final String[] CREATIVE_MODE_TAB_CLASSES = {
            "net/minecraft/class_1761",                                   // intermediary
            "net/minecraft/world/item/CreativeModeTab",                   // mojmap
    };

    /** CreativeModeTab$ItemDisplayBuilder (Output.accept, punto canónico de población) por familia. (corte tabs, Paso B) */
    private static final String[] CREATIVE_TAB_OUTPUT_CLASSES = {
            "net/minecraft/class_1761$class_7703",                          // intermediary
            "net/minecraft/world/item/CreativeModeTab$ItemDisplayBuilder",  // mojmap
    };

    /** CreativeModeTab$ItemDisplayParameters (contexto de buildContents) por familia. (corte tabs, Paso A) */
    private static final String[] ITEM_DISPLAY_PARAMETERS_CLASSES = {
            "net/minecraft/class_1761$class_8128",                            // intermediary
            "net/minecraft/world/item/CreativeModeTab$ItemDisplayParameters", // mojmap
    };

    /** ItemStack por familia de nombres. (corte tabs) */
    private static final String[] ITEM_STACK_CLASSES = {
            "net/minecraft/class_1799",                                   // intermediary
            "net/minecraft/world/item/ItemStack",                         // mojmap
    };

    private static final String[] FABRIC_EVENT_CLASSES = {
            "net/fabricmc/fabric/impl/base/event/ArrayBackedEvent",
    };

    private static final String[] FABRIC_GLOBAL_RECEIVER_CLASSES = {
            "net/fabricmc/fabric/impl/networking/GlobalReceiverRegistry",
    };

    private static final String[] FABRIC_NETWORK_ADDON_CLASSES = {
            "net/fabricmc/fabric/impl/networking/AbstractNetworkAddon",
    };

    /**
     * Sinks de E/S del JDK que distinguen writeCompressed (gzip a disco) del
     * write(CompoundTag, DataOutput) sin comprimir del RegionFile. Son tipos del
     * JDK → identificables sin mapeos por versión.
     */
    private static final String[] IO_SINK_TYPES = {
            "java/io/File",
            "java/nio/file/Path",
            "java/io/OutputStream",
    };

    public static void install(Instrumentation inst) {
        inst.addTransformer(new LedgerTransformer(), true);
        System.err.println("[mksa] ledger transformer instalado");
    }

    private LedgerTransformer() {}

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (className == null) return null;
        boolean chunkData = isChunkData(className);
        boolean levelChunk = isLevelChunk(className);
        boolean nbtIo = isNbtIo(className);
        boolean tagValueInput = isTagValueInput(className);
        boolean recipeManager = isRecipeManager(className);
        boolean tagManagerLoader = isTagManagerLoader(className);
        boolean fabricEvent = isFabricEvent(className);
        boolean fabricGlobalReceiver = isFabricGlobalReceiver(className);
        boolean fabricNetworkAddon = isFabricNetworkAddon(className);
        boolean creativeModeTab = isCreativeModeTab(className);
        boolean creativeTabOutput = isCreativeTabOutput(className);
        if (!chunkData && !levelChunk && !nbtIo && !tagValueInput
                && !recipeManager && !tagManagerLoader && !fabricEvent
                && !fabricGlobalReceiver && !fabricNetworkAddon
                && !creativeModeTab && !creativeTabOutput) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            ClassVisitor v = chunkData ? new ChunkDataVisitor(cw)
                    : levelChunk ? new LevelChunkVisitor(cw)
                    : nbtIo ? new NbtIoVisitor(cw)
                    : tagValueInput ? new TagValueInputVisitor(cw)
                    : recipeManager ? new RecipeManagerVisitor(cw)
                    : tagManagerLoader ? new TagManagerLoaderVisitor(cw)
                    : fabricEvent ? new FabricEventVisitor(cw)
                    : fabricGlobalReceiver ? new FabricGlobalReceiverVisitor(cw)
                    : fabricNetworkAddon ? new FabricNetworkAddonVisitor(cw)
                    : creativeModeTab ? new CreativeModeTabVisitor(cw)
                    : new CreativeTabOutputVisitor(cw);
            cr.accept(v, 0);
            System.err.println("[mksa] ledger: instrumentado " + className);
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[mksa] ledger: fallo instrumentando " + className + ": " + t);
            return null; // null = sin transformar; el juego sigue intacto
        }
    }

    private static boolean isChunkData(String internalName) {
        for (String c : CHUNK_DATA_CLASSES) if (c.equals(internalName)) return true;
        return false;
    }

    private static boolean isLevelChunk(String internalName) {
        for (String c : LEVEL_CHUNK_CLASSES) if (c.equals(internalName)) return true;
        return false;
    }

    private static boolean isNbtIo(String internalName) {
        for (String c : NBT_IO_CLASSES) if (c.equals(internalName)) return true;
        return false;
    }

    private static boolean isTagValueInput(String internalName) {
        for (String c : TAG_VALUE_INPUT_CLASSES) if (c.equals(internalName)) return true;
        return false;
    }

    private static boolean isRecipeManager(String internalName) {
        for (String c : RECIPE_MANAGER_CLASSES) if (c.equals(internalName)) return true;
        return false;
    }

    private static boolean isTagManagerLoader(String internalName) {
        for (String c : TAG_MANAGER_LOADER_CLASSES) if (c.equals(internalName)) return true;
        return false;
    }

    private static boolean isFabricEvent(String internalName) {
        for (String c : FABRIC_EVENT_CLASSES) if (c.equals(internalName)) return true;
        return false;
    }

    private static boolean isFabricGlobalReceiver(String internalName) {
        for (String c : FABRIC_GLOBAL_RECEIVER_CLASSES) if (c.equals(internalName)) return true;
        return false;
    }

    private static boolean isFabricNetworkAddon(String internalName) {
        for (String c : FABRIC_NETWORK_ADDON_CLASSES) if (c.equals(internalName)) return true;
        return false;
    }

    private static boolean isCreativeModeTab(String internalName) {
        for (String c : CREATIVE_MODE_TAB_CLASSES) if (c.equals(internalName)) return true;
        return false;
    }

    private static boolean isCreativeTabOutput(String internalName) {
        for (String c : CREATIVE_TAB_OUTPUT_CLASSES) if (c.equals(internalName)) return true;
        return false;
    }

    private static boolean returnsValueInput(String descriptor) {
        Type ret = Type.getReturnType(descriptor);
        if (ret.getSort() != Type.OBJECT) return false;
        return isOneOf(VALUE_INPUT_CLASSES, ret.getInternalName());
    }

    private static boolean isOneOf(String[] set, String internalName) {
        for (String c : set) if (c.equals(internalName)) return true;
        return false;
    }

    private static boolean returnsCompoundTag(String descriptor) {
        Type ret = Type.getReturnType(descriptor);
        if (ret.getSort() != Type.OBJECT) return false;
        String n = ret.getInternalName();
        for (String c : COMPOUND_TAG_CLASSES) if (c.equals(n)) return true;
        return false;
    }

    private static boolean returnsChunkData(String descriptor) {
        Type ret = Type.getReturnType(descriptor);
        if (ret.getSort() != Type.OBJECT) return false;
        return isChunkData(ret.getInternalName());
    }

    /** Índice de variable local del primer parámetro CompoundTag, o -1. */
    private static int compoundParamSlot(String descriptor, boolean isStatic) {
        Type[] args = Type.getArgumentTypes(descriptor);
        int slot = isStatic ? 0 : 1;
        for (Type a : args) {
            if (a.getSort() == Type.OBJECT) {
                String n = a.getInternalName();
                for (String c : COMPOUND_TAG_CLASSES) {
                    if (c.equals(n)) return slot;
                }
            }
            slot += a.getSize();
        }
        return -1;
    }

    /** El nombre de un accesor de record en intermediary es comp_*; un método real, method_*. */
    private static boolean isRecordAccessorName(String name) {
        return name.startsWith("comp_");
    }

    private static boolean returnsBlockState(String descriptor) {
        Type ret = Type.getReturnType(descriptor);
        if (ret.getSort() != Type.OBJECT) return false;
        return isOneOf(BLOCK_STATE_CLASSES, ret.getInternalName());
    }

    /**
     * Slots de variable local de los dos primeros parámetros (BlockPos, BlockState)
     * de un método de instancia, en ese orden, o null si no encajan. Reconoce
     * setBlockState(BlockPos, BlockState, …) sin depender del número de método ni
     * de parámetros extra (flags) que vengan después.
     */
    private static int[] blockPosStateSlots(String descriptor) {
        Type[] args = Type.getArgumentTypes(descriptor);
        if (args.length < 2) return null;
        if (!(args[0].getSort() == Type.OBJECT && isOneOf(BLOCK_POS_CLASSES, args[0].getInternalName())))
            return null;
        if (!(args[1].getSort() == Type.OBJECT && isOneOf(BLOCK_STATE_CLASSES, args[1].getInternalName())))
            return null;
        int posSlot = 1;                       // slot 0 = this (método de instancia)
        int stateSlot = posSlot + args[0].getSize();
        return new int[] { posSlot, stateSlot };
    }

    /**
     * Slot del parámetro CompoundTag de writeCompressed(CompoundTag, …): un método
     * static cuyo 1er parámetro es CompoundTag y algún parámetro posterior es un
     * sink de E/S del JDK (File/Path/OutputStream). Devuelve el slot del CompoundTag
     * (0, al ser static) o -1 si no encaja. Excluye write(CompoundTag, DataOutput).
     */
    private static int compressedWriteSlot(String descriptor) {
        Type[] args = Type.getArgumentTypes(descriptor);
        if (args.length < 2) return -1;
        if (!(args[0].getSort() == Type.OBJECT
                && isOneOf(COMPOUND_TAG_CLASSES, args[0].getInternalName()))) return -1;
        for (int i = 1; i < args.length; i++) {
            if (args[i].getSort() == Type.OBJECT
                    && isOneOf(IO_SINK_TYPES, args[i].getInternalName())) {
                return 0; // static → el CompoundTag (1er param) está en el slot 0
            }
        }
        return -1;
    }

    private static final class ChunkDataVisitor extends ClassVisitor {
        ChunkDataVisitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            int argc = Type.getArgumentTypes(descriptor).length;

            // write(): instance, 0 params, devuelve CompoundTag, no es accesor de record.
            if (!isStatic && argc == 0 && returnsCompoundTag(descriptor)
                    && !isRecordAccessorName(name)) {
                System.err.println("[mksa] ledger: inyectando WriteHook en " + name + descriptor);
                return new WriteHookVisitor(mv);
            }
            // parse(...): static, devuelve SerializableChunkData, toma un CompoundTag.
            if (isStatic && returnsChunkData(descriptor)) {
                int slot = compoundParamSlot(descriptor, true);
                if (slot >= 0) {
                    System.err.println("[mksa] ledger: inyectando ReadHook en " + name + descriptor);
                    return new ReadHookVisitor(mv, slot);
                }
            }
            // Diagnóstico v4: enumerar candidatos rechazados para destapar si write() en
            // 1.21.11 ya no tiene la firma esperada. Limitado a métodos plausibles
            // (no record accessors comp_*, no static getters).
            if (!isStatic && argc == 0 && !isRecordAccessorName(name)
                    && Type.getReturnType(descriptor).getSort() == Type.OBJECT) {
                System.err.println("[mksa] ledger DIAG: candidato write rechazado: " + name + descriptor);
            }
            return mv;
        }
    }

    /** Inyecta Bridge.writeChunkProv(returnValue) antes de cada *RETURN de objeto. */
    private static final class WriteHookVisitor extends MethodVisitor {
        WriteHookVisitor(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.ARETURN) {
                super.visitInsn(Opcodes.DUP); // duplica el CompoundTag a retornar
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "writeChunkProv",
                        "(Ljava/lang/Object;)V", false);
            }
            super.visitInsn(opcode);
        }
    }

    /** Inyecta Bridge.readChunkProv(compoundParam) a la entrada del método. */
    private static final class ReadHookVisitor extends MethodVisitor {
        private final int slot;
        ReadHookVisitor(MethodVisitor mv, int slot) { super(Opcodes.ASM9, mv); this.slot = slot; }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, slot);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "readChunkProv",
                    "(Ljava/lang/Object;)V", false);
        }
    }

    /**
     * Visita LevelChunk. Engancha cualquier método de instancia cuyos dos primeros
     * parámetros sean (BlockPos, BlockState) y devuelva BlockState — setBlockState.
     */
    private static final class LevelChunkVisitor extends ClassVisitor {
        LevelChunkVisitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (!isStatic && returnsBlockState(descriptor)) {
                int[] slots = blockPosStateSlots(descriptor);
                if (slots != null) return new SetBlockHookVisitor(mv, slots[0], slots[1]);
            }
            return mv;
        }
    }

    /**
     * Inyecta DOS hooks en setBlockState:
     *
     * <ol>
     *   <li><b>Veto al entry (Tier 1 corte 3, filtro de registro vivo).</b> Antes de
     *       cualquier código del método:
     *       <pre>
     *         ALOAD newState
     *         INVOKESTATIC Bridge.shouldVeto(Object): boolean
     *         IFEQ skipVeto
     *         ACONST_NULL
     *         ARETURN
     *         skipVeto:
     *       </pre>
     *       {@code null} es la señal canónica del método de "no hubo cambio" (MC mismo
     *       lo emite cuando {@code prev == state}); los callers (Level.setBlock,
     *       BlockItem.useOn, pistones, dispensadores) ya saben tratarla — no envían
     *       packet, no disparan neighbor updates, el jugador conserva el ítem. Si
     *       vetamos, el ARETURN del código original nunca se ejecuta, así que
     *       {@code Bridge.onSetBlock} (pieza 2 del corte 2) tampoco — los contadores
     *       no se inflan con mutaciones que no ocurrieron.</li>
     *
     *   <li><b>Observación al ARETURN (Tier 1 corte 2, pieza 2, byChunk in-vivo).</b>
     *       Stack en ARETURN: {@code [prevState]} (un único ref en el tope). Para
     *       llamar al Bridge con (pos, newState, prevState) sin gastar un slot local,
     *       dejamos el prevState debajo y reorganizamos con DUP+SWAP:
     *       <pre>
     *         DUP           ; [prevState, prevState]
     *         ALOAD pos     ; [prevState, prevState, pos]
     *         SWAP          ; [prevState, pos, prevState]
     *         ALOAD state   ; [prevState, pos, prevState, newState]
     *         SWAP          ; [prevState, pos, newState, prevState]
     *         INVOKESTATIC Bridge.onSetBlock(Object,Object,Object)V  ; [prevState]
     *         ARETURN
     *       </pre>
     *       Las referencias de objeto son category-1, así que DUP/SWAP funcionan.</li>
     * </ol>
     *
     * COMPUTE_MAXS recalcula maxStack; no se usan locales nuevos.
     */
    private static final class SetBlockHookVisitor extends MethodVisitor {
        private final int posSlot;
        private final int stateSlot;
        SetBlockHookVisitor(MethodVisitor mv, int posSlot, int stateSlot) {
            super(Opcodes.ASM9, mv);
            this.posSlot = posSlot;
            this.stateSlot = stateSlot;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            // Veto al entry (corte 3): si Bridge.shouldVeto(newState) → return null.
            //
            // Cuidado con los stack map frames: este es el único hook del transformer
            // que introduce un branch target NUEVO (skipVeto). En class files Java 6+
            // todo jump target exige un frame, y usamos COMPUTE_MAXS (no COMPUTE_FRAMES,
            // que no podemos porque exigiría resolver la jerarquía de clases de MC
            // desde nuestro classloader). Sin frame en skipVeto, MC arranca con
            // VerifyError al cargar LevelChunk y el juego cierra al iniciar el mundo.
            //
            // F_SAME = "mismo estado que el frame anterior" (= frame implícito de
            // entry del método: locals = parámetros, stack vacío). Es exactamente lo
            // que tenemos en skipVeto: el boolean que pusimos lo consume IFEQ, no
            // hemos tocado locals, no quedan cosas en stack. Tres llamadas extra,
            // bytecode válido.
            Label skipVeto = new Label();
            super.visitVarInsn(Opcodes.ALOAD, stateSlot);          // [newState]
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "shouldVeto",
                    "(Ljava/lang/Object;)Z", false);                // [Z]
            super.visitJumpInsn(Opcodes.IFEQ, skipVeto);            // []
            super.visitInsn(Opcodes.ACONST_NULL);                   // [null]
            super.visitInsn(Opcodes.ARETURN);                       // (return)
            super.visitLabel(skipVeto);
            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.ARETURN) {
                super.visitInsn(Opcodes.DUP);              // [prev, prev]
                super.visitVarInsn(Opcodes.ALOAD, posSlot); // [prev, prev, pos]
                super.visitInsn(Opcodes.SWAP);              // [prev, pos, prev]
                super.visitVarInsn(Opcodes.ALOAD, stateSlot); // [prev, pos, prev, new]
                super.visitInsn(Opcodes.SWAP);              // [prev, pos, new, prev]
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "onSetBlock",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
            }
            super.visitInsn(opcode);
        }
    }

    /**
     * Visita NbtIo. Engancha cualquier método static que escriba un CompoundTag a
     * un sink de E/S del JDK — writeCompressed (gzip a disco: playerdata, level.dat).
     */
    private static final class NbtIoVisitor extends ClassVisitor {
        NbtIoVisitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (isStatic && Type.getReturnType(descriptor).getSort() == Type.VOID) {
                int slot = compressedWriteSlot(descriptor);
                if (slot >= 0) return new CompressedWriteHookVisitor(mv, slot);
            }
            return mv;
        }
    }

    /** Inyecta Bridge.onCompressedWrite(compound) a la entrada del método. */
    private static final class CompressedWriteHookVisitor extends MethodVisitor {
        private final int slot;
        CompressedWriteHookVisitor(MethodVisitor mv, int slot) {
            super(Opcodes.ASM9, mv);
            this.slot = slot;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, slot);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "onCompressedWrite",
                    "(Ljava/lang/Object;)V", false);
        }
    }

    /**
     * Visita TagValueInput. Engancha cualquier método static que devuelva ValueInput y
     * tome un CompoundTag — create(...): el punto de envoltura del tag del jugador antes
     * del decode por codec. (Corte 4b)
     */
    private static final class TagValueInputVisitor extends ClassVisitor {
        TagValueInputVisitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (isStatic && returnsValueInput(descriptor)) {
                int slot = compoundParamSlot(descriptor, true);
                if (slot >= 0) return new ValueInputCreateHookVisitor(mv, slot);
            }
            return mv;
        }
    }

    /** Inyecta Bridge.onValueInputCreate(compound) a la entrada del método. */
    private static final class ValueInputCreateHookVisitor extends MethodVisitor {
        private final int slot;
        ValueInputCreateHookVisitor(MethodVisitor mv, int slot) {
            super(Opcodes.ASM9, mv);
            this.slot = slot;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, slot);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "onValueInputCreate",
                    "(Ljava/lang/Object;)V", false);
        }
    }

    // ====================== Tier 1 corte 4: filtro de datapack ======================
    //
    // RecipeManager y TagManagerLoader: hook al punto canónico de cada superficie del
    // reload donde el contenido está completo y aún no es global. El Bridge delega al
    // Ledger; este filtra in-place por namespace armado (ledger.dpFilterArm). Patrón
    // simétrico al corte 3 (filter, no des-registrar): lo del namespace no se construye
    // como parte del estado vivo, lo del resto pasa intacto.

    /**
     * Visita {@code RecipeManager} (class_1863). Engancha el RETURN del prepare tipado:
     * método protegido, instance, 2 parámetros (ResourceManager + ProfilerFiller), que
     * devuelve un objeto de {@code net/minecraft/*} (= class_10289, el payload). El
     * erased bridge ({@code Object} return) lo evitamos para no duplicar el hook — la
     * impl tipada es la que tiene la lógica real, el bridge solo casta y devuelve.
     */
    private static final class RecipeManagerVisitor extends ClassVisitor {
        RecipeManagerVisitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (isStatic) return mv;
            Type[] args = Type.getArgumentTypes(descriptor);
            if (args.length != 2) return mv;
            // Ambos parámetros deben ser tipos net/minecraft (ResourceManager + ProfilerFiller).
            if (!isNetMinecraftType(args[0]) || !isNetMinecraftType(args[1])) return mv;
            Type ret = Type.getReturnType(descriptor);
            if (ret.getSort() != Type.OBJECT) return mv;
            String rn = ret.getInternalName();
            // El bridge erased devuelve java/lang/Object; el tipado devuelve la clase real.
            // Filtrar el typed → no duplicamos contadores.
            if ("java/lang/Object".equals(rn)) return mv;
            if (!rn.startsWith("net/minecraft/")) return mv;
            System.err.println("[mksa] ledger: inyectando RecipePrepareHook en " + name + descriptor);
            return new RecipePrepareHookVisitor(mv);
        }
    }

    private static boolean isNetMinecraftType(Type t) {
        return t.getSort() == Type.OBJECT && t.getInternalName().startsWith("net/minecraft/");
    }

    /** Inyecta DUP + INVOKESTATIC Bridge.dpFilterRecipePayload antes de cada ARETURN. */
    private static final class RecipePrepareHookVisitor extends MethodVisitor {
        RecipePrepareHookVisitor(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.ARETURN) {
                super.visitInsn(Opcodes.DUP);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "dpFilterRecipePayload",
                        "(Ljava/lang/Object;)V", false);
            }
            super.visitInsn(opcode);
        }
    }

    /**
     * Visita {@code TagManagerLoader} (class_3503). Engancha la ENTRADA del build
     * tipado: método instance, 1 parámetro {@code java.util.Map}, devuelve
     * {@code java.util.Map}. Única firma con esa forma en class_3503 (los otros
     * candidatos toman ResourceManager, son static, o devuelven otros tipos). El
     * filtro del Bridge muta in-place el {@code Map<Identifier, List<Tag>>}: elimina
     * las entradas cuyo id tenga ns armado.
     */
    private static final class TagManagerLoaderVisitor extends ClassVisitor {
        TagManagerLoaderVisitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (isStatic) return mv;
            Type[] args = Type.getArgumentTypes(descriptor);
            if (args.length != 1) return mv;
            if (args[0].getSort() != Type.OBJECT
                    || !"java/util/Map".equals(args[0].getInternalName())) return mv;
            Type ret = Type.getReturnType(descriptor);
            if (ret.getSort() != Type.OBJECT
                    || !"java/util/Map".equals(ret.getInternalName())) return mv;
            System.err.println("[mksa] ledger: inyectando TagBuildHook en " + name + descriptor);
            return new TagBuildHookVisitor(mv);
        }
    }

    /** Inyecta ALOAD 1 + INVOKESTATIC Bridge.dpFilterTagBuild a la entrada del método. */
    private static final class TagBuildHookVisitor extends MethodVisitor {
        TagBuildHookVisitor(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 1); // slot 0 = this; el Map es slot 1
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "dpFilterTagBuild",
                    "(Ljava/lang/Object;)V", false);
        }
    }

    // ====================== Tier 2: runtime plane (Fabric) ======================

    private static final class FabricEventVisitor extends ClassVisitor {
        FabricEventVisitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (isStatic) return mv;
            if (!"register".equals(name)) return mv;
            if (Type.getReturnType(descriptor).getSort() != Type.VOID) return mv;
            Type[] args = Type.getArgumentTypes(descriptor);
            if (args.length != 2) return mv;
            if (args[args.length - 1].getSort() != Type.OBJECT) return mv;
            int listenerSlot = 1;
            for (int i = 0; i < args.length - 1; i++) listenerSlot += args[i].getSize();
            int phaseSlot = args.length == 2 ? 1 : 0;
            System.err.println("[mksa] ledger: inyectando FabricEventRegisterHook en " + name + descriptor);
            return new FabricEventRegisterHookVisitor(mv, phaseSlot, listenerSlot);
        }
    }

    private static final class FabricEventRegisterHookVisitor extends MethodVisitor {
        private final int phaseSlot;
        private final int listenerSlot;
        FabricEventRegisterHookVisitor(MethodVisitor mv, int phaseSlot, int listenerSlot) {
            super(Opcodes.ASM9, mv);
            this.phaseSlot = phaseSlot;
            this.listenerSlot = listenerSlot;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitLdcInsn("fabric-event");
            super.visitVarInsn(Opcodes.ALOAD, 0);
            if (phaseSlot > 0) super.visitVarInsn(Opcodes.ALOAD, phaseSlot);
            else super.visitInsn(Opcodes.ACONST_NULL);
            super.visitVarInsn(Opcodes.ALOAD, listenerSlot);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "runtimeSubscribe",
                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
        }
    }

    /**
     * Fabric networking global receivers. The public API delegates to
     * GlobalReceiverRegistry.registerGlobalReceiver(Identifier, handler), and the
     * registry also exposes unregisterGlobalReceiver(Identifier). That gives us the
     * same reversible shape as ArrayBackedEvent: keep the registry, channel id and
     * handler object, then remove/restore through Fabric's own implementation.
     */
    private static final class FabricGlobalReceiverVisitor extends ClassVisitor {
        FabricGlobalReceiverVisitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (isStatic) return mv;
            if (!"registerGlobalReceiver".equals(name)) return mv;
            if (Type.getReturnType(descriptor).getSort() != Type.BOOLEAN) return mv;
            Type[] args = Type.getArgumentTypes(descriptor);
            if (args.length != 2) return mv;
            if (args[0].getSort() != Type.OBJECT || args[1].getSort() != Type.OBJECT) return mv;
            System.err.println("[mksa] ledger: inyectando FabricGlobalReceiverHook en " + name + descriptor);
            return new FabricGlobalReceiverHookVisitor(mv);
        }
    }

    private static final class FabricGlobalReceiverHookVisitor extends MethodVisitor {
        FabricGlobalReceiverHookVisitor(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.IRETURN) {
                Label skip = new Label();
                super.visitInsn(Opcodes.DUP);                 // [ok, ok]
                super.visitJumpInsn(Opcodes.IFEQ, skip);      // [ok]
                super.visitLdcInsn("fabric-networking-global");
                super.visitVarInsn(Opcodes.ALOAD, 0);         // GlobalReceiverRegistry
                super.visitVarInsn(Opcodes.ALOAD, 1);         // Identifier channel
                super.visitVarInsn(Opcodes.ALOAD, 2);         // handler
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "runtimeSubscribe",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
                super.visitLabel(skip);
                super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] { Opcodes.INTEGER });
            }
            super.visitInsn(opcode);
        }
    }

    /**
     * Fabric per-connection receivers. ClientPlayNetworking.registerReceiver and
     * the server/configuration variants delegate to AbstractNetworkAddon.registerChannel
     * on the live connection addon. The matching unregisterChannel returns the
     * removed handler, so this is reversible while that connection remains alive.
     */
    private static final class FabricNetworkAddonVisitor extends ClassVisitor {
        FabricNetworkAddonVisitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (isStatic) return mv;
            if (!"registerChannel".equals(name)) return mv;
            if (Type.getReturnType(descriptor).getSort() != Type.BOOLEAN) return mv;
            Type[] args = Type.getArgumentTypes(descriptor);
            if (args.length != 2) return mv;
            if (args[0].getSort() != Type.OBJECT || args[1].getSort() != Type.OBJECT) return mv;
            System.err.println("[mksa] ledger: inyectando FabricNetworkAddonRegisterHook en " + name + descriptor);
            return new FabricNetworkAddonRegisterHookVisitor(mv);
        }
    }

    private static final class FabricNetworkAddonRegisterHookVisitor extends MethodVisitor {
        FabricNetworkAddonRegisterHookVisitor(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.IRETURN) {
                Label skip = new Label();
                super.visitInsn(Opcodes.DUP);                 // [ok, ok]
                super.visitJumpInsn(Opcodes.IFEQ, skip);      // [ok]
                super.visitLdcInsn("fabric-networking-connection");
                super.visitVarInsn(Opcodes.ALOAD, 0);         // AbstractNetworkAddon
                super.visitVarInsn(Opcodes.ALOAD, 1);         // Identifier channel
                super.visitVarInsn(Opcodes.ALOAD, 2);         // handler
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "runtimeSubscribe",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
                super.visitLabel(skip);
                super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] { Opcodes.INTEGER });
            }
            super.visitInsn(opcode);
        }
    }

    // ====================== corte tabs: toggle de pestaña de inventario creativo ======================
    //
    // Dos superficies de CreativeModeTab (class_1761):
    //   - CreativeModeTab.buildContents(ItemDisplayParameters): captura pasiva (Paso A)
    //     del contexto real, para poder forzar un rebuild reflectivo más tarde.
    //   - CreativeModeTab$ItemDisplayBuilder.accept(ItemStack, TabVisibility): el único
    //     punto por el que pasa CUALQUIER item de CUALQUIER mod al entrar a CUALQUIER
    //     pestaña (confirmado por javap: único método no-constructor de la clase). Vetar
    //     ahí basta para vaciar la pestaña; vainilla ya oculta el botón de una pestaña
    //     CATEGORY vacía (shouldDisplay()→hasStacks()), así que no hace falta tocar
    //     Registries.ITEM_GROUP ni la GUI. Patrón filter, no des-registrar (§3.1).

    /**
     * Visita CreativeModeTab. Engancha buildContents(ItemDisplayParameters): instance,
     * 1 parámetro cuyo tipo está en ITEM_DISPLAY_PARAMETERS_CLASSES, devuelve void.
     * Única firma con esa forma.
     */
    private static final class CreativeModeTabVisitor extends ClassVisitor {
        CreativeModeTabVisitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (isStatic) return mv;
            if (Type.getReturnType(descriptor).getSort() != Type.VOID) return mv;
            Type[] args = Type.getArgumentTypes(descriptor);
            if (args.length != 1) return mv;
            if (args[0].getSort() != Type.OBJECT
                    || !isOneOf(ITEM_DISPLAY_PARAMETERS_CLASSES, args[0].getInternalName())) return mv;
            System.err.println("[mksa] ledger: inyectando TabBuildContentsHook en " + name + descriptor);
            return new TabBuildContentsHookVisitor(mv);
        }
    }

    /**
     * Inyecta Bridge.captureTabDisplayParams(params) a la entrada del método. Paso A,
     * captura pasiva sin branching → sin frame nuevo requerido.
     */
    private static final class TabBuildContentsHookVisitor extends MethodVisitor {
        TabBuildContentsHookVisitor(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 1); // slot 0 = this; ItemDisplayParameters es slot 1
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "captureTabDisplayParams",
                    "(Ljava/lang/Object;)V", false);
        }
    }

    /**
     * Visita CreativeModeTab$ItemDisplayBuilder. Engancha accept(ItemStack,
     * TabVisibility): instance, 2 parámetros, el primero ItemStack, devuelve void —
     * el único método no-constructor de la clase (confirmado por javap), sin
     * ambigüedad estructural.
     */
    private static final class CreativeTabOutputVisitor extends ClassVisitor {
        CreativeTabOutputVisitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (isStatic) return mv;
            if (Type.getReturnType(descriptor).getSort() != Type.VOID) return mv;
            Type[] args = Type.getArgumentTypes(descriptor);
            if (args.length != 2) return mv;
            if (args[0].getSort() != Type.OBJECT
                    || !isOneOf(ITEM_STACK_CLASSES, args[0].getInternalName())) return mv;
            System.err.println("[mksa] ledger: inyectando TabItemVetoHook en " + name + descriptor);
            return new TabItemVetoHookVisitor(mv);
        }
    }

    /**
     * Inyecta el veto al entry de accept(ItemStack, TabVisibility):
     * <pre>
     *   ALOAD itemStack
     *   INVOKESTATIC Bridge.shouldVetoTabItem(Object): boolean
     *   IFEQ skipVeto
     *   RETURN
     *   skipVeto:
     * </pre>
     * A diferencia del veto de setBlockState (devuelve BlockState, necesita
     * ACONST_NULL antes de ARETURN), este método es void: el corto-circuito es un
     * RETURN simple. Introduce un branch target nuevo → requiere visitFrame manual
     * (mismo motivo que SetBlockHookVisitor; COMPUTE_MAXS no calcula frames).
     */
    private static final class TabItemVetoHookVisitor extends MethodVisitor {
        TabItemVetoHookVisitor(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitCode() {
            super.visitCode();
            Label skipVeto = new Label();
            super.visitVarInsn(Opcodes.ALOAD, 1); // slot 0 = this; el ItemStack es slot 1
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "shouldVetoTabItem",
                    "(Ljava/lang/Object;)Z", false);
            super.visitJumpInsn(Opcodes.IFEQ, skipVeto);
            super.visitInsn(Opcodes.RETURN);
            super.visitLabel(skipVeto);
            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }
    }
}
