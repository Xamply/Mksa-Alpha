package dev.mksa.agent;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Primer corte Tier 3: auditoria de mixins/coremods. No redefine clases y no
 * habilita mutacion; solo produce el diagnostico que necesita DisablePlan.
 */
final class Tier3MixinAudit {
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    // T3 corte J (Pilar 1a): anotacion RUNTIME propia de Mixin, escrita por
    // MixinTargetContext.methodMerged/fieldMerged -- evidencia autoritativa de que
    // mixin realmente mergeo cada miembro, no una inferencia nuestra.
    private static final String MIXIN_MERGED_DESC =
            "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    // T3 corte tier3 end-to-end §1: @Implements(@Interface(iface=Foo.class,...))
    // declara una interfaz que Mixin agrega al implements del target -- mismo
    // efecto estructural que accessor_invoker_interfaces (isInterface) y
    // declaredInterfaces (corte Q), tercera via distinta. Necesaria para el
    // escaner de consumidores externos de forma (generaliza la leccion de
    // corte AN: class_303/HeadRenderable).
    private static final String MIXIN_IMPLEMENTS_DESC =
            "Lorg/spongepowered/asm/mixin/Implements;";
    private static final int CAP = 64;
    // T3 corte lambda-blindness: Class.isHidden() (JEP 371) es API Java 15+, pero
    // este archivo compila con --release 8 (ver build.sh) -- mismo patron ya
    // establecido en Agent.java (Class.getModule()/Module.isNamed() reflexivos)
    // para llamar APIs mas nuevas que el nivel de compilacion sin romper el build.
    // Resuelto una sola vez y cacheado; null (JVM sin hidden classes, ej. <15) hace
    // que isHiddenClass devuelva false siempre -- correcto, no fail-open, porque en
    // esa JVM ninguna clase puede ser hidden.
    private static final java.lang.reflect.Method CLASS_IS_HIDDEN = resolveClassIsHidden();

    private static java.lang.reflect.Method resolveClassIsHidden() {
        try {
            return Class.class.getMethod("isHidden");
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isHiddenClass(Class<?> c) {
        if (CLASS_IS_HIDDEN == null || c == null) return false;
        try {
            return Boolean.TRUE.equals(CLASS_IS_HIDDEN.invoke(c));
        } catch (Throwable t) {
            return false;
        }
    }
    private static final ConcurrentHashMap<String, byte[]> BASE_BYTES =
            new ConcurrentHashMap<String, byte[]>();

    /**
     * T3 corte "auditoria librerias base": las librerias fundacionales que
     * cualquier instancia real de Fabric/Minecraft carga siempre (Netty
     * incl. jctools empaquetado, LWJGL, JOML, Guava) llaman a sun.misc.Unsafe
     * o reflexion (Class.getDeclaredField*, MethodHandles$Lookup.find* /
     * unreflect*) en 31 clases -- isReflectionRisk las marca risk=true de
     * forma indiscriminada, igual que a cualquier mixin desconocido, aunque
     * estas llamadas jamas puedan tocar una clase del redefineGroup ni una
     * interfaz/campo inyectado por Mixin (esas librerias no conocen a Mixin,
     * solo manipulan sus propios buffers/nodos/punteros internos).
     *
     * Decision del usuario (2026-07-12, ver memoria
     * project_mksa_base_library_audit_decision): NO se acepta una exclusion
     * generica "esta libreria es de confianza" -- cada clase de esta lista
     * fue auditada sitio por sitio (javap real sobre netty-common-4.2.7.Final,
     * lwjgl/lwjgl-glfw-3.3.3, joml-1.10.8, guava-33.5.0-jre, las versiones
     * exactas fijadas por mmc-pack.json de las instancias reales) confirmando
     * que el tipo de objeto que reciben TODOS sus Unsafe/reflection call
     * sites esta fijado por la firma del metodo o por garantia del
     * verificador de la JVM -- nunca puede ser un Object generico que un
     * llamador externo controle. De las 34 clases originalmente marcadas,
     * 4 (PlatformDependent0 en sus metodos genericos Object/long,
     * UnsafeRefArrayAccess sobre Object[], ReferenceCountUpdater&lt;T&gt; con
     * parametro de tipo generico, APIUtil.apiClassTokens(Class[]) de LWJGL)
     * solo tienen evidencia mas debil (se verifico que TODOS los llamadores
     * actuales dentro del jar pasan tipos seguros, pero la firma en si no lo
     * garantiza para siempre) -- decision explicita del usuario: quedan
     * FUERA de esta lista, siguen bloqueadas como cualquier clase no
     * auditada.
     *
     * La exclusion esta atada al HASH exacto (SHA-256) del bytecode
     * auditado, no solo al nombre -- si la libreria cambia de version y el
     * bytecode cambia, el hash no coincide y la clase vuelve a caer en el
     * flujo normal fail-closed (isAuditedBaseLibraryClass devuelve false),
     * forzando re-auditoria en vez de heredar confianza vieja a ciegas.
     */
    private static final Map<String, String> AUDITED_BASE_LIBRARY_HASHES = buildAuditedBaseLibraryHashes();

    private static Map<String, String> buildAuditedBaseLibraryHashes() {
        Map<String, String> m = new HashMap<String, String>();
        m.put("com.google.common.util.concurrent.AbstractFutureState$UnsafeAtomicHelper",
                "1ff3d705cdd94949f8e1ca0054ca8e8a8017f6144303b97da639b89e7f96401c");
        m.put("com.google.common.util.concurrent.AbstractFutureState$VarHandleAtomicHelper",
                "0b4fe9c30a93785ba8b6be1bebf2461b478b77b3426b8b07d4c8783b33d0f371");
        m.put("io.netty.util.internal.CleanerJava9$1",
                "e11b4201cfd3554812f86d098c66e7b702dbd73b5a8cf80491f6a8070b79e117");
        m.put("io.netty.util.internal.PlatformDependent",
                "6a98616fa4680196f113b3873633ad3d34558b8665c253812e783f68c9e2f5d2");
        m.put("io.netty.util.internal.PlatformDependent0$1",
                "4a974ac3e96099c49b28d5188394cce6b91675c6d250fe61a456ae1ab83c12e9");
        m.put("io.netty.util.internal.PlatformDependent0$2",
                "230db665f7f04d75b0b18e6c50ae5a06f3240f3844c2d285449093e82f1620b0");
        m.put("io.netty.util.internal.PlatformDependent0$3",
                "da7d9b16e0f90e903b8738703695758d953b5d9a4296e5f17c2d731cf72e7d52");
        m.put("io.netty.util.internal.PlatformDependent0$4",
                "19c4e65568954a44d66bd569c55450451223af9c8b731e3d7d50a63d347562f9");
        m.put("io.netty.util.internal.PlatformDependent0$5",
                "376a9fafbb97284a43397d17f22a75af0383735d8b97359a2a78a9f82bff362b");
        m.put("io.netty.util.internal.PlatformDependent0$6",
                "efa51f24c1f7ff0f12fae2e4523a03338a140a4d8ec773c22668a322ceb8081f");
        m.put("io.netty.util.internal.PlatformDependent0$8",
                "95cea3cbf148a072d794667f21fea306823aed794847ab3ed604559b6b183baf");
        m.put("io.netty.util.internal.PlatformDependent0$9",
                "073c6d4963f79d4c4eb68502ca9a85008beda0b6982113334a4734d4fb9da043");
        m.put("io.netty.util.internal.shaded.org.jctools.queues.BaseLinkedQueueConsumerNodeRef",
                "88136e9060694724bcc2d427cee9ff3a35b9a9f2eec0f2f6f13b66281f2fed5c");
        m.put("io.netty.util.internal.shaded.org.jctools.queues.BaseLinkedQueueProducerNodeRef",
                "e657b04f551e8026fd5091ec6eac68c481807232456ac087a890f0e457bd1c5d");
        m.put("io.netty.util.internal.shaded.org.jctools.queues.BaseMpscLinkedArrayQueueColdProducerFields",
                "2bd2fc48bdea58dd9aa73913635a1dbd1b11d10186060a700ed655a6d67e5f05");
        m.put("io.netty.util.internal.shaded.org.jctools.queues.BaseMpscLinkedArrayQueueConsumerFields",
                "dd2e30e07b27e5ded9269ac3510c6312ddc483b81ea084399bfd25bc42bc0a5f");
        m.put("io.netty.util.internal.shaded.org.jctools.queues.BaseMpscLinkedArrayQueueProducerFields",
                "1cb84284fa43186ab79acc1e691ebe3b66fccaafa31fbcaf233fc762c6e17e42");
        m.put("io.netty.util.internal.shaded.org.jctools.queues.LinkedQueueNode",
                "45605a986ca5accc0f0870a6aed233318cf485e958280812a3a201c8de028583");
        m.put("io.netty.util.internal.shaded.org.jctools.queues.MpmcArrayQueueConsumerIndexField",
                "1b81b0db604e9b14c0fa0a28f39e95b5da48ed5ae0927bd97af5541abe5746d3");
        m.put("io.netty.util.internal.shaded.org.jctools.queues.MpmcArrayQueueProducerIndexField",
                "74c768833db70f3b711009dc4485e08597168d0d3e55637a9d73593afa5bbc96");
        m.put("io.netty.util.internal.shaded.org.jctools.util.UnsafeAccess",
                "be192d0d86e6acb8aed4556fff4fdde450b0c15343aa2a2efe4f211d8884cfaf");
        m.put("io.netty.util.internal.shaded.org.jctools.util.UnsafeLongArrayAccess",
                "79b9554af6c82872fdf936e4c40b30c1cb66a62af15516ee6010366c7315a981");
        m.put("org.joml.MemUtil$MemUtilUnsafe",
                "74fc83cf086413b5ed8b5a63b64f245876ba1ed155532857221035f888ec9030");
        m.put("org.lwjgl.glfw.GLFWImage",
                "5eb4c161f86aad38350db6588082239bdaa0764c0bef66e9f7239980942d7668");
        m.put("org.lwjgl.glfw.GLFWVidMode",
                "5c5a67cf67420d008080c2a23c62de017798da8630f014f5ffacce85956c1348");
        m.put("org.lwjgl.system.CustomBuffer",
                "d2a8c8138e7f66f3ac8e41e72baca2eda69e96ea0db4c447fde9db68a197bfb2");
        m.put("org.lwjgl.system.MemoryUtil",
                "6e1465f18fea857fac2607c4a6001f2fce8a592c633c3c25c6ab2f2c2f9e5e1b");
        // T3 corte "auditoria librerias base" (fix post-verificacion en vivo):
        // lwjgl-3.3.3.jar es un Multi-Release JAR (Multi-Release: true en su
        // manifest) -- bajo JBR21 (Java 21, el UNICO JVM de produccion que
        // MKSA usa, ver corte Z) el classloader siempre resuelve
        // org/lwjgl/system/MultiReleaseMemCopy.class a la variante de
        // META-INF/versions/16/, nunca a la entrada base del jar (confirmado
        // via el primer intento de este corte: con el hash de la entrada base
        // la clase seguia cayendo "unknown" en un regreso real in-game -- el
        // hash nunca coincidia porque nunca es esa la que carga la JVM). Solo
        // se registra el hash de la variante v16 realmente cargada -- auditada
        // por separado (javap real): mismo patron categoricamente seguro que
        // la base habria sido -- Unsafe.copyMemory(Object,long,Object,long,long)
        // recibe aconst_null como AMBOS receivers (src/dst), nunca un objeto
        // que un llamador externo controle, solo cambia que usa un unico
        // intrinsic de copia en vez de la logica alineada de la variante base.
        m.put("org.lwjgl.system.MultiReleaseMemCopy",
                "30e8ea061547888c2536401c851a1d14e0ab6410c2404e4b9199692190a5fc3f");
        m.put("org.lwjgl.system.Pointer$Default",
                "8aecc3bf09fa22c7bf510eba32426eea42f88559068f389a0d5e199da3600906");
        m.put("org.lwjgl.system.libffi.FFICIF",
                "28b74e636ed6711efcabbbb56bcbd2e251772ed42ea67294d2ac666efc94fd79");
        m.put("org.lwjgl.system.libffi.FFIType",
                "6f0faeacb7d3b069d1c724f7798c21ac0be9cec9db24f7f96098d96b0ca882f4");
        // T3 categoria 5 (icu4j-77.1.jar, pinned por 1.21.11.json): los 8 sitios
        // getDeclaredField en DecimalFormat.readObject usan siempre el mismo
        // patron -- ldc de la clase literal NumberFormat (constante de pool,
        // nunca un parametro) + nombre de campo hardcodeado, y Field.get siempre
        // recibe aload_0 (la instancia propia) como receiver. Nada externamente
        // controlable -- mas fuerte que varias de las entradas ya auditadas.
        m.put("com.ibm.icu.text.DecimalFormat",
                "d415fd6410457a962da6b5173e205c9d7b858c11f46252087ac072424ac97ad0");
        // T3 categoria 8 (log4j-core-2.25.2.jar, pinned por 1.21.11.json):
        // toPatternFlags opera exclusivamente sobre la clase literal
        // java.util.regex.Pattern (ldc de constant-pool, jamas un parametro) --
        // tanto getDeclaredFields() como el receiver de Field.getInt() son esa
        // misma clase, nunca un tipo externamente controlable. Mismo patron
        // categoricamente seguro que DecimalFormat (categoria 5).
        m.put("org.apache.logging.log4j.core.filter.RegexFilter",
                "47d30831f04e7695927df427a3f24af874aad2ad23362ef28082f55aca4a5377");
        // T3 categoria 9 (mixinextras-0.5.0-1f6627383f457848.jar, jar exacto
        // cargado por Instancia 3, hash de jar confirmado igual al extraido):
        // ClassGenUtils.<clinit> hace ldc de la clase literal sun.misc.Unsafe
        // (constante de pool, jamas un parametro) + ldc del nombre de campo
        // "theUnsafe" hardcodeado -- receiver fijo, mismo patron ya auditado
        // en Netty PlatformDependent0. Los otros dos sitios de esta categoria
        // (InternalField.of(Class,String) e InternalField.of(String,String) ->
        // getBetaVersion via Class.forName con nombre de paquete construido en
        // runtime) reciben la Class como parametro de una API generica publica
        // o construyen el nombre dinamicamente -- excluidos, no pinneables,
        // ver docs/log.txt.
        m.put("com.llamalad7.mixinextras.utils.ClassGenUtils",
                "dcb95243deb993d2e5dcf6f9431135e0c6ce508e29bbd5768009471b3f1fb76d");
        // T3 categoria 10 (jodah-typetools 0.6.3, jar en produccion:
        // net_jodah_typetools-0.6.3-642073fc7c1a731e.jar dentro de Instancia 3/
        // .fabric/processedMods/, remapeado por Fabric Loader -- distinto del jar
        // "crudo" en PrismLauncher/libraries, se audito el jar de produccion real):
        // TypeResolver.<clinit> y TypeResolver$1.run() son el bootstrap clasico
        // de acceso a sun.misc.Unsafe/MethodHandles.Lookup.IMPL_LOOKUP -- todos los
        // receivers de getDeclaredField/findSetter son literales de constant-pool
        // (ldc class sun/misc/Unsafe + ldc String "theUnsafe"; ldc class
        // java/lang/reflect/AccessibleObject + ldc String "override"; ldc class
        // java/lang/invoke/MethodHandles$Lookup + ldc String "IMPL_LOOKUP"),
        // jamas un parametro externo. Mismo patron categoricamente seguro que
        // Netty PlatformDependent0 y MixinExtras ClassGenUtils (categoria 9).
        // Categoria CERRADA completa por hash-pin, sin exclusiones.
        m.put("net.jodah.typetools.TypeResolver",
                "d5114a278fa59fe01846ed242dd92b6cc36313e0915613c0159f791df92bce54");
        m.put("net.jodah.typetools.TypeResolver$1",
                "82fd23a0e523e96268a9efb3e01aee9c3d5e209f51fd8e0926ba6cb0c2fccfc6");
        // T3 categoria 12 (vanilla Minecraft 1.21.11, client-intermediary.jar
        // remapeado en Instancia 3/.fabric/remappedJars/minecraft-1.21.11-0.18.4/):
        // class_301.method_1408() detecta en runtime si LWJGL trae el
        // DebugAllocator opcional -- Class.forName("org.lwjgl.system.
        // MemoryManage$DebugAllocator") + getDeclaredMethod("untrack", long) y
        // Class.forName("org.lwjgl.system.MemoryUtil$LazyInit") +
        // getDeclaredField("ALLOCATOR"), todos nombres literales hardcodeados en
        // el propio bytecode vanilla, nunca un parametro. class_3675.<clinit>
        // detecta si GLFW soporta raw mouse motion -- MethodHandles.Lookup.
        // findStatic/findStaticGetter sobre la clase literal org/lwjgl/glfw/GLFW
        // con nombres "glfwRawMouseMotionSupported"/"GLFW_RAW_MOUSE_MOTION"
        // igualmente hardcodeados. Mismo patron de deteccion de features
        // opcionales de libreria nativa ya auditado en categorias base (LWJGL/
        // Netty) -- codigo propio de Mojang, no de un mod, nada externamente
        // controlable. Categoria CERRADA completa por hash-pin, sin exclusiones.
        m.put("net.minecraft.class_301",
                "039c5da9003d242d1964da51904cfffca970a192a3eca93435caa076dc74c57e");
        m.put("net.minecraft.class_3675",
                "681a3f225ddcf14d07b1cee382237ac730e3ea2536df170f528911de7f2035eb");
        // T3 categoria 14 (GlitchCore-fabric-1.21.11-21.11.0.4.jar, Instancia 3/
        // minecraft/mods/): onInitialize() hace ldc de la clase literal vanilla
        // net/minecraft/class_7923 (constant-pool, jamas un parametro) +
        // Class.getFields() -- recorre los campos publicos estaticos de
        // class_7923 (Registries), filtra por Field.getType().isAssignableFrom
        // (FabricRegistry, tambien literal) y hace Field.get(null) sobre los
        // que matchean. El literal es infraestructura vanilla estable, no
        // contenido propio de GlitchCore -- mismo patron ya hash-pineado en
        // categoria 12 (class_301/class_3675), no el patron "clase propia del
        // mod" que motivo excluir el resto de la categoria 14 (BOPItems,
        // ModMenuConfig, JsonConfig, etc). Unico hash-pin de la categoria 14;
        // los otros 9 sitios quedan excluidos, ver docs/log.txt.
        m.put("glitchcore.fabric.core.GlitchCoreFabric",
                "1d8808e9d365777503d6f32e9ca9a5e2c8b1aa92b280ca463f57643d216f73ca");
        // T3 categoria 15 (sodium-fabric-0.8.12+mc1.21.11.jar, Instancia 3/
        // minecraft/mods/): MemoryIntrinsics.<clinit> es el bootstrap clasico
        // de acceso a sun.misc.Unsafe -- ldc de la clase literal sun/misc/Unsafe
        // (constant-pool, jamas un parametro) + ldc del nombre de campo
        // "theUnsafe" hardcodeado + Field.get(null) -- mismo patron ya
        // hash-pineado en Netty PlatformDependent0, MixinExtras ClassGenUtils
        // y jodah-typetools TypeResolver (categorias base/9/10).
        // copyMemory(long,long,int) solo delega a ese campo estatico ya
        // resuelto, nunca reflexiona con datos externos. SimpleFrustum.<clinit>
        // es el mismo patron con MethodHandles.Lookup.unreflectGetter en vez de
        // Field.get -- ldc de la clase literal org/joml/FrustumIntersection +
        // ldc del nombre de campo "planes" hardcodeado, mismo patron ya
        // hash-pineado en org.joml.MemUtil$MemUtilUnsafe (categoria base). En
        // ambos casos el literal es una libreria de terceros estable (JDK
        // Unsafe, JOML), no contenido propio de Sodium -- misma logica ya
        // aplicada al hash-pin de GlitchCoreFabric en esta categoria.
        m.put("net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics",
                "4b0fda5f4624dff6cc2d88e89c2790d99ef7a05725351075569a11ec47ef4faf");
        m.put("net.caffeinemc.mods.sodium.client.render.viewport.frustum.SimpleFrustum",
                "28b7dbbdad80156dd130c51a89283cecfb8a01958850f3be8e2bfd570a5b68d3");
        // Hash-pins para consumidores de forma externos auditados para destrabar chat_heads (corte 2026-07-23)
        m.put("com.supermartijn642.core.registry.RegistryOverrideHandlers",
                "a06c59e8068ad3fa47b8cc0c41492ec566a73cd60498d9900e2bf989b17cf8a8");
        m.put("com.supermartijn642.core.registry.RegistryEntryAcceptor$Handler",
                "f6c070ff2d2cd0b425da032c66a76a467dd78b01f4eb8edacb56022f465b0f58");
        m.put("com.supermartijn642.core.CoreLibPreLaunch",
                "9b74b035d5e627acac58280f6d00ef43967045550c1168aadc3934585670231e");
        m.put("org.ladysnake.cca.internal.base.asm.StaticComponentPluginBase",
                "85893cc6849b88c96a117fe8e32e07c4b59716b3a7e889ae403ca0a98f952e57");
        m.put("org.ladysnake.cca.internal.base.ComponentsInternals",
                "f983364aaa59e924ea6a5ecf567fb689b9fba7de1eaeaa5e5fdcfd810c54d849");
        m.put("net.fabricmc.fabric.impl.base.event.EventFactoryImpl",
                "a17f0753cc8c6a64e1120227efaa17cf0305f19c1fb2d675c0ecf4cd9a7636a0");
        m.put("com.sun.jna.Native",
                "3a21f042c323125ee2c123c9c0ce560abfd0116d6935b03405783d96bfa71c31");
        m.put("com.sun.jna.Structure",
                "7e7a17cb42ffc5b8ddad8b6c651f8458feb0f223599d249ef022a27a071629cb");
        m.put("com.google.gson.internal.bind.ReflectiveTypeAdapterFactory",
                "69fd9f9c79a86e6ee4778a1fe7c7337432500f5844d0e6e1a4ee52c39b426d59");
        m.put("com.google.gson.internal.bind.EnumTypeAdapter",
                "266408908c52ddca0c3b9b2647bf653d8f8f0b18d53e7507bc52530d93ad6bfb");
        m.put("com.llamalad7.mixinextras.utils.InternalField",
                "d99eb56ea010248bf275723089488fb2fc9e35274cb15e1eb2b7961ae2f30d4f");
        m.put("com.llamalad7.mixinextras.service.MixinExtrasServiceImpl",
                "9e8ed25b149d8007f7fcb13e474a9b5e0bfc7356a3992869f5d05093eb2b363d");
        m.put("org.apache.logging.log4j.core.util.TypeUtil",
                "42cd90f2093910810a69420f76a3d9452eedc0e6f555d755dc724a0a4f1c4d25");
        m.put("net.fabricmc.loader.impl.game.minecraft.Log4jLogHandler",
                "e985e11a7b8e9b21afcdbab55842147ad33f36c868a76a8f73d3b23fd3a7553e");
        m.put("com.terraformersmc.modmenu.config.ModMenuConfig",
                "5dffdb2b8b8ae7ff33efeeb991ce0d5eb31884012041ccff84ed9e9860b4191c");
        m.put("com.terraformersmc.modmenu.config.ModMenuConfigManager",
                "41dc2d21b12a4cc4c34dfa18ff6d4d05502a1a77fac4559e55e2fcb5f5f28560");
        m.put("immersive_aircraft.config.JsonConfig",
                "f09928f5477fb779bd6025428f59712e91683ca3cda4fba1a18d12e3aa3cd202");
        m.put("net.blay09.mods.balm.platform.config.reflection.internal.ConfigReflection",
                "48a0c91c10d5d34f07092ea24a4144a69bdbe92972f02ebb01973da31e7eed4a");
        m.put("net.blay09.mods.balm.platform.config.reflection.internal.LoadedReflectionConfig",
                "f236643c2211089338d2787e79eeacf10c4cbded0daa25641e7f57e869e75e2d");
        m.put("io.netty.util.internal.PlatformDependent0",
                "0b57b4e47cb9fd510bdaf00fc9846366da114754e53667d48a28be23232635cb");
        m.put("io.netty.util.internal.ReferenceCountUpdater",
                "3117c7c1193421b2f314c24536431a5fda0e7d209546ac66084a157e4f807414");
        m.put("io.netty.util.internal.shaded.org.jctools.util.UnsafeRefArrayAccess",
                "6e3ede21ae47966e2807d938a0b9c36de71ae96849bec4b0571aeb87642f9285");
        m.put("org.lwjgl.system.APIUtil",
                "2960bf29ff3db725820d75d335c6dc0d89c827c7786b4764d89d125d1c27ea7e");
        m.put("biomesoplenty.init.ModCreativeTab",
                "0c1160aea536c3e2311e0e86c9738e6627fa96c7a0f812607d7bc6348e3c5b61");
        return m;
    }

    /** T3 corte "auditoria librerias base": true solo si {@code dotted} esta en la
     * lista auditada Y el hash SHA-256 de {@code bytes} coincide EXACTO con el
     * bytecode auditado -- cualquier discrepancia (version distinta de la
     * libreria, clase con el mismo nombre pero contenido distinto) cae a false,
     * fail-closed, sin excepcion. */
    private static boolean isAuditedBaseLibraryClass(String dotted, byte[] bytes) {
        String expected = AUDITED_BASE_LIBRARY_HASHES.get(dotted);
        if (expected == null) return false;
        String actual = sha256(bytes);
        if (!expected.equals(actual)) {
            System.err.println("[mksa] AUDITED_BASE_LIBRARY_HASH_MISMATCH: " + dotted + " expected=" + expected + " actual=" + actual);
        }
        return expected.equals(actual);
    }

    /** Accesor de paquete (mismo patron que Tier3LiveCapture.get) para que un smoke real (corte P) pueda leer el bytecode base ya capturado por captureBaseBytecode. */
    static byte[] baseBytes(String target) {
        return BASE_BYTES.get(target);
    }

    /** T3 corte AH-fix: cuenta de miembros declarados de un target a partir de
     * SUS BYTES (los mismos que se le van a instalar via redefineClasses),
     * excluyendo &lt;init&gt;/&lt;clinit&gt; para ser comparable con
     * Class.getDeclaredMethods()/getDeclaredFields() (la reflexion ya los
     * excluye). Reutiliza parseClassSummary()/ClassSummary ya existentes --
     * ClassSummary.methods SI incluye &lt;init&gt;/&lt;clinit&gt;
     * (classSummary() no filtra, a diferencia de otros call-sites de este
     * archivo que si filtran sobre info.methods de mixinInfo()). Devuelve
     * null si los bytes no parsean (nunca lanza -- el llamador no debe
     * bloquear la operacion por esto, solo degradar el chequeo a "no
     * verificable"). Usado por Tier3DemixApply para verificar el schema tras
     * redefineClasses() contra los bytes REALMENTE instalados, no contra un
     * snapshot pre-redefine (ese invariante era incorrecto para targets cuyo
     * mixin agrega miembros declarados, ej. class_4538 -- corte AH-fix). */
    static int[] expectedDeclaredCounts(byte[] classBytes) {
        try {
            ClassSummary cs = parseClassSummary(classBytes);
            int methodCount = 0;
            for (String m : cs.methods) {
                if (m.startsWith("<init>") || m.startsWith("<clinit>")) continue;
                methodCount++;
            }
            return new int[] { methodCount, cs.fields.size() };
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * T3 corte L (Pilar 2): espejo estatico de las familias de clases que
     * LedgerTransformer (dev.mksa.agent.ledger, aislado por ASM, no importable
     * desde aqui -- ver leccion de corte K en Agent.installLedger) muta
     * activamente (canRetransform=true). Si esas familias cambian alla, hay que
     * sincronizar esta tabla a mano. Nombres en formato dotted (mismo formato
     * que victimTargets/target strings en este archivo).
     */
    private static final Map<String, String[]> OWN_TRANSFORMER_TARGET_FAMILIES = ownTransformerFamilies();

    private static Map<String, String[]> ownTransformerFamilies() {
        Map<String, String[]> m = new LinkedHashMap<String, String[]>();
        m.put("ChunkData", new String[] {
                "net.minecraft.class_2852", "net.minecraft.world.level.chunk.storage.SerializableChunkData" });
        m.put("LevelChunk", new String[] {
                "net.minecraft.class_2818", "net.minecraft.world.level.chunk.LevelChunk" });
        m.put("NbtIo", new String[] {
                "net.minecraft.class_2507", "net.minecraft.nbt.NbtIo" });
        m.put("TagValueInput", new String[] {
                "net.minecraft.class_11352", "net.minecraft.world.level.storage.TagValueInput" });
        m.put("RecipeManager", new String[] {
                "net.minecraft.class_1863", "net.minecraft.world.item.crafting.RecipeManager" });
        m.put("TagManagerLoader", new String[] {
                "net.minecraft.class_3503", "net.minecraft.server.packs.resources.TagManager",
                "net.minecraft.server.packs.resources.TagManagerLoader", "net.minecraft.tags.TagLoader" });
        m.put("FabricEvent", new String[] { "net.fabricmc.fabric.impl.base.event.ArrayBackedEvent" });
        m.put("FabricGlobalReceiver", new String[] { "net.fabricmc.fabric.impl.networking.GlobalReceiverRegistry" });
        m.put("FabricNetworkAddon", new String[] { "net.fabricmc.fabric.impl.networking.AbstractNetworkAddon" });
        return m;
    }

    /** Resultado crudo del escaneo de mixins/coremods bajo un set de roots. */
    static final class ScanResult {
        final List<Object> mixinConfigs = new ArrayList<Object>();
        final List<Object> coremods = new ArrayList<Object>();
        final List<Object> blocked = new ArrayList<Object>();
        final Set<String> mixinSet = new LinkedHashSet<String>();
        final Set<String> targetSet = new LinkedHashSet<String>();
        final Map<String, List<String>> configsByMixin = new LinkedHashMap<String, List<String>>();
        final Map<String, MixinInfo> mixinInfoByClass = new LinkedHashMap<String, MixinInfo>();
        // T3 corte AE: ruta de config (.mixins.json) -> plugin FQCN declarado
        // (IMixinConfigPlugin), solo entradas con plugin no vacio. Hermano de
        // configsByMixin -- mismo threading, resuelto en readMixinConfig.
        final Map<String, String> pluginByConfig = new LinkedHashMap<String, String>();
    }

    /**
     * Escanea .mixins.json/coremod manifests bajo roots y resuelve los targets
     * declarados por @Mixin. Fuente unica de verdad reusada por plan() (auditoria
     * on-demand, t3.mixinPlan) y por Boot.run (watch-set global de
     * Tier3LiveCapture, corte I) -- cero divergencia de parseo entre ambos caminos.
     */
    static ScanResult scanMixins(List<Path> roots) {
        ScanResult r = new ScanResult();
        if (roots == null || roots.isEmpty()) {
            r.blocked.add(reason("no_roots", "No hay raices legibles del mod para auditar mixins."));
            return r;
        }
        for (Path root : roots) {
            inspectRoot(root, r.mixinConfigs, r.mixinSet, r.configsByMixin, r.pluginByConfig, r.coremods, r.blocked);
        }
        for (String mixin : r.mixinSet) {
            byte[] bytes = readClassBytes(roots, mixin);
            if (bytes == null) {
                r.blocked.add(reason("mixin_class_missing", "No se pudo leer la clase mixin " + mixin));
                continue;
            }
            try {
                MixinInfo info = parseMixinInfo(bytes);
                r.mixinInfoByClass.put(mixin, info);
                r.targetSet.addAll(info.targets);
            } catch (Throwable t) {
                r.blocked.add(reason("mixin_annotation_unreadable",
                        "No se pudo leer @Mixin de " + mixin + ": " + t.getClass().getSimpleName()));
            }
        }
        return r;
    }

    /** T3 corte AL: FQCN de los mixins de {@code ns} que declaran {@code target}
     * como uno de sus @Mixin targets. Reusa scanMixins (fuente unica de verdad,
     * cero parseo nuevo) sobre las raices reales de esa ns. Usado por
     * Tier3DemixApply para resolver el exclude-set de un replay selectivo
     * (Tier3MixinReplay) sin reconstruir el escaneo de .mixins.json. */
    static Set<String> mixinClassNamesForTarget(String ns, String target,
            Map<String, List<Path>> rootsByNs) {
        Set<String> out = new LinkedHashSet<String>();
        if (ns == null || target == null || rootsByNs == null) return out;
        List<Path> roots = rootsByNs.get(ns);
        if (roots == null || roots.isEmpty()) return out;
        ScanResult scan = scanMixins(roots);
        for (Map.Entry<String, MixinInfo> e : scan.mixinInfoByClass.entrySet()) {
            if (e.getValue().targets.contains(target)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Solo los targets (dotted) declarados por @Mixin bajo roots -- usado por Boot.run para el watch-set global. */
    static Set<String> scanTargets(List<Path> roots) {
        return scanMixins(roots).targetSet;
    }

    /**
     * T3 corte M (Pilar 3, universo estructural -- SOLO diagnostico, no aplicador):
     * responde unicamente "?cual es el universo estructural de la instancia?", es
     * decir el conjunto de cambios de FORMA (jerarquia/miembros) que TODOS los mods
     * tier>=3 instalados necesitarian sobre sus targets, calculado una sola vez en
     * boot con el mismo escaneo que ya usa computeTier3WatchSet. Deliberadamente NO
     * responde "?como lo aplico?": no hay IMixinConfigPlugin, no hay preApply, no
     * hay mixin dummy, no hay inyeccion en runtime -- eso es responsabilidad del
     * futuro aplicador (Pilar 3), que podra confiar en que este modelo ya fue
     * probado de forma independiente (spike empirico Fase 5, docs/proyecto.md
     * S3.32).
     *
     * Forma deliberadamente declarativa: "categories" es un mapa abierto por
     * nombre de categoria, cada una con la MISMA forma (available/model/total/
     * byTarget). "accessor_invoker_interfaces" es la primera categoria confirmada
     * (interfaces que Mixin agrega al implements del target -- el caso de
     * jerarquia real). Categorias futuras (unique_members, synthetic_bridges,
     * ...) se agregan como entradas nuevas de este mismo mapa, sin cambiar la
     * forma del modelo ni el codigo que ya lee las categorias existentes.
     *
     * T3 corte Q: corte P revelo que "accessor_invoker_interfaces" describia
     * solo UN mecanismo (el mixin declarado como interface) de un problema mas
     * amplio -- un mixin-CLASE normal con "implements AlgunaInterface" tambien
     * cambia la jerarquia del target, y ese caso era invisible para el scout
     * (fue la causa real del fallo de redefineClasses() en corte P). En vez de
     * nombrar la categoria nueva por el mecanismo Mixin que la produce, se
     * nombra por el EFECTO estructural que describe: "interface_contributions"
     * es la union de ambos mecanismos (accessor/invoker + implements directo),
     * la categoria que el scout debe consultar para saber si un target tiene
     * CUALQUIER cambio de jerarquia por interfaces. "accessor_invoker_interfaces"
     * se conserva sin cambios (mismo dato, con el detalle fino de metodos
     * accessor/invoker que interface_contributions no necesita repetir).
     */
    static Map<String, Object> buildStructuralUniverse(List<Map<String, Object>> mods,
                                                         Map<String, List<Path>> modRoots) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("model", "structural_universe_v1");
        List<Map<String, Object>> modsSafe = mods == null ? Collections.<Map<String, Object>>emptyList() : mods;
        Map<String, List<Path>> modRootsSafe = modRoots == null
                ? Collections.<String, List<Path>>emptyMap() : modRoots;
        // T3 corte U: el universo estructural deja de pensarse como una coleccion
        // de casos especiales y pasa a responder una unica pregunta -- que
        // elementos de la forma de esta clase deben existir desde el primer
        // defineClass() para que Mixin nunca necesite redefineClasses() para
        // cambiar su forma. interface_contributions (corte M/Q), method_contributions
        // y field_contributions (corte U) son tres respuestas HERMANAS a esa misma
        // pregunta -- cada una cubre una categoria distinta de miembro ABI
        // (interfaz/metodo/campo), pero conceptualmente son la misma familia
        // "structural_contributions". Se mantienen como entradas planas de
        // "categories" (no anidadas bajo un padre nuevo) porque ese es el
        // invariante fijado en corte M: ningun lector existente (scout,
        // --tier3-audit) cambia su forma de lectura cuando se agrega una
        // categoria nueva. accessor_invoker_interfaces se conserva sin cambios,
        // subsumido por interface_contributions.
        // T3 corte V: catalogo CERRADO, confirmado por lectura directa del
        // codigo fuente de Mixin (MixinApplicatorStandard/MixinTargetContext/
        // MixinInfo) -- las tres categorias de arriba agotan toda alta de
        // miembro nuevo (interfaz/metodo/campo); no existe en Mixin 0.8.x
        // ningun mecanismo que cambie el superName o los access flags de la
        // CLASE del target. field_modifier_contributions es una cuarta
        // respuesta a la misma pregunta ABI, pero de una naturaleza distinta:
        // no es un miembro nuevo, es un miembro YA EXISTENTE (@Shadow) cuyo
        // modificador cambia (@Mutable quita ACC_FINAL) -- JVMTI redefineClasses
        // tambien rechaza cambios de modificador, no solo altas/bajas.
        Map<String, Object> categories = new LinkedHashMap<String, Object>();
        categories.put("accessor_invoker_interfaces", buildAccessorInvokerCategory(modsSafe, modRootsSafe));
        categories.put("interface_contributions", buildInterfaceContributionsCategory(modsSafe, modRootsSafe));
        categories.put("method_contributions", buildMethodContributionsCategory(modsSafe, modRootsSafe));
        categories.put("field_contributions", buildFieldContributionsCategory(modsSafe, modRootsSafe));
        categories.put("field_modifier_contributions", buildFieldModifierContributionsCategory(modsSafe, modRootsSafe));
        out.put("categories", categories);
        return out;
    }

    private static Map<String, Object> buildAccessorInvokerCategory(List<Map<String, Object>> mods,
                                                                      Map<String, List<Path>> modRoots) {
        Map<String, Object> category = new LinkedHashMap<String, Object>();
        category.put("model", "accessor_invoker_interfaces_v1");
        Map<String, List<Object>> byTarget = new LinkedHashMap<String, List<Object>>();
        long total = 0;
        for (Map<String, Object> mod : mods) {
            Object tierObj = mod.get("tier");
            if (!(tierObj instanceof Number) || ((Number) tierObj).intValue() < 3) continue;
            Object idObj = mod.get("id");
            if (!(idObj instanceof String)) continue;
            String ownerNs = (String) idObj;
            List<Path> roots = modRoots.get(ownerNs);
            if (roots == null || roots.isEmpty()) continue;
            ScanResult scan = scanMixins(roots);
            for (Map.Entry<String, MixinInfo> e : scan.mixinInfoByClass.entrySet()) {
                String mixinClass = e.getKey();
                MixinInfo info = e.getValue();
                if (!info.isInterface) continue;
                List<Object> accessorMethods = accessorInvokerMethods(info.methods);
                if (accessorMethods.isEmpty()) continue;
                total++;
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("ownerNs", ownerNs);
                entry.put("interfaceMixin", mixinClass);
                entry.put("methods", accessorMethods);
                for (String target : info.targets) {
                    List<Object> list = byTarget.get(target);
                    if (list == null) {
                        list = new ArrayList<Object>();
                        byTarget.put(target, list);
                    }
                    list.add(entry);
                }
            }
        }
        category.put("available", Boolean.valueOf(!byTarget.isEmpty()));
        category.put("total", Long.valueOf(total));
        category.put("byTarget", byTarget);
        return category;
    }

    /**
     * T3 corte Q: union de los dos mecanismos Mixin confirmados que cambian el
     * implements del target (ver comentario de buildStructuralUniverse). Cada
     * entrada trae "mechanism" ("accessor_invoker_interface" | "class_implements")
     * para trazabilidad, pero el scout solo necesita available/total/byTarget
     * para decidir si un target es candidato viable a redefineClasses().
     */
    private static Map<String, Object> buildInterfaceContributionsCategory(List<Map<String, Object>> mods,
                                                                             Map<String, List<Path>> modRoots) {
        Map<String, Object> category = new LinkedHashMap<String, Object>();
        category.put("model", "interface_contributions_v1");
        Map<String, List<Object>> byTarget = new LinkedHashMap<String, List<Object>>();
        long total = 0;
        for (Map<String, Object> mod : mods) {
            Object tierObj = mod.get("tier");
            if (!(tierObj instanceof Number) || ((Number) tierObj).intValue() < 3) continue;
            Object idObj = mod.get("id");
            if (!(idObj instanceof String)) continue;
            String ownerNs = (String) idObj;
            List<Path> roots = modRoots.get(ownerNs);
            if (roots == null || roots.isEmpty()) continue;
            ScanResult scan = scanMixins(roots);
            for (Map.Entry<String, MixinInfo> e : scan.mixinInfoByClass.entrySet()) {
                String mixinClass = e.getKey();
                MixinInfo info = e.getValue();
                Map<String, Object> entry;
                if (info.isInterface) {
                    List<Object> accessorMethods = accessorInvokerMethods(info.methods);
                    if (accessorMethods.isEmpty()) continue;
                    entry = new LinkedHashMap<String, Object>();
                    entry.put("ownerNs", ownerNs);
                    entry.put("mixin", mixinClass);
                    entry.put("mechanism", "accessor_invoker_interface");
                    entry.put("interfaces", Collections.singletonList((Object) mixinClass));
                } else {
                    if (info.declaredInterfaces.isEmpty()) continue;
                    entry = new LinkedHashMap<String, Object>();
                    entry.put("ownerNs", ownerNs);
                    entry.put("mixin", mixinClass);
                    entry.put("mechanism", "class_implements");
                    entry.put("interfaces", new ArrayList<Object>(info.declaredInterfaces));
                }
                total++;
                for (String target : info.targets) {
                    List<Object> list = byTarget.get(target);
                    if (list == null) {
                        list = new ArrayList<Object>();
                        byTarget.put(target, list);
                    }
                    list.add(entry);
                }
            }
        }
        category.put("available", Boolean.valueOf(!byTarget.isEmpty()));
        category.put("total", Long.valueOf(total));
        category.put("byTarget", byTarget);
        return category;
    }

    /**
     * T3 corte U: cierra para metodos el mismo hueco que corte Q cerro para
     * interfaces. Corte T REFUTO un reset a base real (redefineClasses()) sobre
     * balm/net.minecraft.class_826 porque el mixin agrega un metodo que no
     * existe en la clase base -- JVMTI no permite anadir miembros al redefinir.
     * buildStructuralUniverse corre en boot, ANTES de que cargue ninguna clase
     * de Minecraft (analisis puramente estatico sobre los jars de los mods), asi
     * que aqui no hay bytecode base disponible: se clasifica cada metodo con
     * methodOperation(m, null), que por anotacion Mixin ya distingue lo ADITIVO
     * (el miembro debe existir desde el primer defineClass(): method_transform,
     * method_accessor_bridge, method_add_unique, method_add_declared) de lo
     * NO-aditivo (solo cambia cuerpo o es referencia a algo que ya existe:
     * method_overwrite, method_shadow_reference) -- ver el javadoc de
     * methodOperation. A diferencia de interface_contributions (una entrada por
     * mixin), aqui la entrada es por MIEMBRO individual: el reset falla por
     * metodo concreto, no por el mixin completo.
     */
    private static Map<String, Object> buildMethodContributionsCategory(List<Map<String, Object>> mods,
                                                                          Map<String, List<Path>> modRoots) {
        Map<String, Object> category = new LinkedHashMap<String, Object>();
        category.put("model", "method_contributions_v1");
        Map<String, List<Object>> byTarget = new LinkedHashMap<String, List<Object>>();
        long total = 0;
        for (Map<String, Object> mod : mods) {
            Object tierObj = mod.get("tier");
            if (!(tierObj instanceof Number) || ((Number) tierObj).intValue() < 3) continue;
            Object idObj = mod.get("id");
            if (!(idObj instanceof String)) continue;
            String ownerNs = (String) idObj;
            List<Path> roots = modRoots.get(ownerNs);
            if (roots == null || roots.isEmpty()) continue;
            ScanResult scan = scanMixins(roots);
            for (Map.Entry<String, MixinInfo> e : scan.mixinInfoByClass.entrySet()) {
                String mixinClass = e.getKey();
                MixinInfo info = e.getValue();
                for (MemberInfo m : info.methods) {
                    if ("<init>".equals(m.name) || "<clinit>".equals(m.name)) continue;
                    String op = methodOperation(m, null);
                    if (!isAdditiveMethodOperation(op)) continue;
                    total++;
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("ownerNs", ownerNs);
                    entry.put("mixin", mixinClass);
                    Map<String, Object> member = new LinkedHashMap<String, Object>();
                    member.put("name", m.name);
                    member.put("desc", m.desc);
                    entry.put("member", member);
                    entry.put("operation", op);
                    for (String target : info.targets) {
                        List<Object> list = byTarget.get(target);
                        if (list == null) {
                            list = new ArrayList<Object>();
                            byTarget.put(target, list);
                        }
                        list.add(entry);
                    }
                }
            }
        }
        category.put("available", Boolean.valueOf(!byTarget.isEmpty()));
        category.put("total", Long.valueOf(total));
        category.put("byTarget", byTarget);
        return category;
    }

    /** T3 corte U: mismo tratamiento que buildMethodContributionsCategory, para campos. */
    private static Map<String, Object> buildFieldContributionsCategory(List<Map<String, Object>> mods,
                                                                         Map<String, List<Path>> modRoots) {
        Map<String, Object> category = new LinkedHashMap<String, Object>();
        category.put("model", "field_contributions_v1");
        Map<String, List<Object>> byTarget = new LinkedHashMap<String, List<Object>>();
        long total = 0;
        for (Map<String, Object> mod : mods) {
            Object tierObj = mod.get("tier");
            if (!(tierObj instanceof Number) || ((Number) tierObj).intValue() < 3) continue;
            Object idObj = mod.get("id");
            if (!(idObj instanceof String)) continue;
            String ownerNs = (String) idObj;
            List<Path> roots = modRoots.get(ownerNs);
            if (roots == null || roots.isEmpty()) continue;
            ScanResult scan = scanMixins(roots);
            for (Map.Entry<String, MixinInfo> e : scan.mixinInfoByClass.entrySet()) {
                String mixinClass = e.getKey();
                MixinInfo info = e.getValue();
                for (MemberInfo f : info.fields) {
                    String op = fieldOperation(f, null);
                    if (!isAdditiveFieldOperation(op)) continue;
                    total++;
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("ownerNs", ownerNs);
                    entry.put("mixin", mixinClass);
                    Map<String, Object> member = new LinkedHashMap<String, Object>();
                    member.put("name", f.name);
                    member.put("desc", f.desc);
                    entry.put("member", member);
                    entry.put("operation", op);
                    for (String target : info.targets) {
                        List<Object> list = byTarget.get(target);
                        if (list == null) {
                            list = new ArrayList<Object>();
                            byTarget.put(target, list);
                        }
                        list.add(entry);
                    }
                }
            }
        }
        category.put("available", Boolean.valueOf(!byTarget.isEmpty()));
        category.put("total", Long.valueOf(total));
        category.put("byTarget", byTarget);
        return category;
    }

    /** true si, con base=null, el metodo debe existir desde el primer defineClass(). */
    private static boolean isAdditiveMethodOperation(String op) {
        return "method_transform".equals(op) || "method_accessor_bridge".equals(op)
                || "method_add_unique".equals(op) || "method_add_declared".equals(op);
    }

    /** true si, con base=null, el campo debe existir desde el primer defineClass(). */
    private static boolean isAdditiveFieldOperation(String op) {
        return "field_add_unique".equals(op) || "field_add_declared".equals(op);
    }

    /**
     * T3 corte V: cierre del catalogo confirmado por lectura del codigo fuente
     * de Mixin (MixinApplicatorStandard/MixinTargetContext/MixinInfo, jar real
     * sponge-mixin-0.17.0+mixin.0.8.7.jar) -- interface/method/field_contributions
     * agotan las altas de miembros nuevos; la unica mutacion estructural
     * restante sobre un miembro EXISTENTE es @Shadow+@Mutable quitando
     * ACC_FINAL a un campo. A diferencia de las tres categorias anteriores
     * (el miembro debe existir desde el primer defineClass()), aqui lo que
     * debe ser cierto desde el primer defineClass() es que el campo YA sea
     * no-final -- mismo principio ABI, variante de modificador en vez de alta.
     */
    private static Map<String, Object> buildFieldModifierContributionsCategory(List<Map<String, Object>> mods,
                                                                                  Map<String, List<Path>> modRoots) {
        Map<String, Object> category = new LinkedHashMap<String, Object>();
        category.put("model", "field_modifier_contributions_v1");
        Map<String, List<Object>> byTarget = new LinkedHashMap<String, List<Object>>();
        long total = 0;
        for (Map<String, Object> mod : mods) {
            Object tierObj = mod.get("tier");
            if (!(tierObj instanceof Number) || ((Number) tierObj).intValue() < 3) continue;
            Object idObj = mod.get("id");
            if (!(idObj instanceof String)) continue;
            String ownerNs = (String) idObj;
            List<Path> roots = modRoots.get(ownerNs);
            if (roots == null || roots.isEmpty()) continue;
            ScanResult scan = scanMixins(roots);
            for (Map.Entry<String, MixinInfo> e : scan.mixinInfoByClass.entrySet()) {
                String mixinClass = e.getKey();
                MixinInfo info = e.getValue();
                for (MemberInfo f : info.fields) {
                    String op = fieldOperation(f, null);
                    if (!"field_shadow_mutable".equals(op)) continue;
                    total++;
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("ownerNs", ownerNs);
                    entry.put("mixin", mixinClass);
                    Map<String, Object> member = new LinkedHashMap<String, Object>();
                    member.put("name", f.name);
                    member.put("desc", f.desc);
                    entry.put("member", member);
                    entry.put("operation", op);
                    for (String target : info.targets) {
                        List<Object> list = byTarget.get(target);
                        if (list == null) {
                            list = new ArrayList<Object>();
                            byTarget.put(target, list);
                        }
                        list.add(entry);
                    }
                }
            }
        }
        category.put("available", Boolean.valueOf(!byTarget.isEmpty()));
        category.put("total", Long.valueOf(total));
        category.put("byTarget", byTarget);
        return category;
    }

    private static List<Object> accessorInvokerMethods(List<MemberInfo> methods) {
        List<Object> out = new ArrayList<Object>();
        for (MemberInfo m : methods) {
            String kind = accessorInvokerKind(m);
            if (kind == null) continue;
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("name", m.name);
            entry.put("desc", m.desc);
            entry.put("kind", kind);
            out.add(entry);
        }
        return out;
    }

    /** "accessor" | "invoker" | null (no es un miembro Accessor/Invoker). */
    private static String accessorInvokerKind(MemberInfo m) {
        if (hasAnnotation(m, "org.spongepowered.asm.mixin.gen.Accessor")) return "accessor";
        if (hasAnnotation(m, "org.spongepowered.asm.mixin.gen.Invoker")) return "invoker";
        return null;
    }

    static Map<String, Object> plan(String ns, Map<String, Object> mod,
                                    Map<String, List<Path>> rootsByNs,
                                    java.lang.instrument.Instrumentation inst) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("ns", ns);
        out.put("modId", mod == null ? ns : mod.get("id"));
        out.put("name", mod == null ? ns : mod.get("name"));
        out.put("tier", mod == null ? null : mod.get("tier"));

        List<Path> roots = resolveRoots(ns, mod, rootsByNs);
        ScanResult scan = scanMixins(roots);
        List<Object> mixinConfigs = scan.mixinConfigs;
        List<Object> mixinClasses = cappedStrings(scan.mixinSet);
        List<Object> targetClasses = cappedStrings(scan.targetSet);
        List<Object> coremods = scan.coremods;
        List<Object> blocked = scan.blocked;
        Set<String> mixinSet = scan.mixinSet;
        Set<String> targetSet = scan.targetSet;
        Map<String, List<String>> configsByMixin = scan.configsByMixin;
        Map<String, MixinInfo> mixinInfoByClass = scan.mixinInfoByClass;

        Map<String, Class<?>> loaded = loadedTargets(inst, targetSet);
        int loadedTargets = loaded.size();
        List<Object> baseBytecode = new ArrayList<Object>();
        List<Object> missingBase = new ArrayList<Object>();
        int baseCaptured = captureBaseBytecode(targetSet, loaded, baseBytecode, missingBase);
        boolean baseBytesCaptured = !targetSet.isEmpty() && baseCaptured == targetSet.size();
        // T3 corte I: bytecode vivo -- ya tejido por Mixin en su primera definicion
        // real, capturado pasivamente por Tier3LiveCapture (instalado en premain,
        // watch-set poblado por Boot.computeTier3WatchSet). No via retransform
        // (corte H probo que eso no entrega el snapshot exacto).
        List<Object> liveBytecode = new ArrayList<Object>();
        List<Object> missingLive = new ArrayList<Object>();
        int liveCapturedCount = captureLiveBytecode(targetSet, liveBytecode, missingLive);
        boolean liveBytesCaptured = !targetSet.isEmpty() && liveCapturedCount == targetSet.size();
        // T3 corte AC: fuente de respaldo para targets que nunca cargaron
        // organicamente -- transformClassBytes sintetico (corte AB, confirmado
        // byte-identico). liveBytesCaptured NO cambia de significado (sigue
        // midiendo solo captura organica); transformedBytesCaptured es el campo
        // combinado que alimenta el gate real.
        Set<String> missingLiveTargets = new LinkedHashSet<String>();
        for (String t : targetSet) if (Tier3LiveCapture.get(t) == null) missingLiveTargets.add(t);
        List<Object> syntheticBytecode = new ArrayList<Object>();
        List<Object> missingSynthetic = new ArrayList<Object>();
        int syntheticCapturedCount = captureSyntheticBytecode(missingLiveTargets, loaded, inst,
                syntheticBytecode, missingSynthetic);
        long transformedBytesCapturedCount = liveCapturedCount + syntheticCapturedCount;
        boolean transformedBytesCaptured = !targetSet.isEmpty()
                && transformedBytesCapturedCount == targetSet.size();
        List<Object> attributions = new ArrayList<Object>();
        List<Object> diffs = new ArrayList<Object>();
        long[] attributionCounts = buildAttributionDiffs(ns, mixinInfoByClass, configsByMixin, attributions, diffs);
        DemixPlan demix = buildDemixPlan(ns, rootsByNs, mixinInfoByClass, configsByMixin,
                scan.pluginByConfig, targetSet, loaded, inst);
        if (!missingBase.isEmpty()) {
            Map<String, Object> r = reason("base_bytecode_missing",
                    "No se pudo leer bytecode base para " + missingBase.size() + " targets.");
            r.put("targets", missingBase);
            blocked.add(r);
        }
        if (!missingLive.isEmpty()) {
            Map<String, Object> r = reason("live_bytecode_missing",
                    "No se pudo capturar bytecode vivo (primera carga real) para " + missingLive.size() + " targets.");
            r.put("targets", missingLive);
            blocked.add(r);
        }
        blocked.add(reason("demix_pipeline_pending",
                "Bytecode base capturado; aun falta calcular/reaplicar la pipeline Mixin sin la victima y redefinir."));
        if (!transformedBytesCaptured) {
            blocked.add(reason("runtime_transformed_bytes_missing",
                    "Falta snapshot exacto de los bytes transformados (vivos u obtenidos por transformacion sintetica offline) para al menos un target del namespace."));
        }
        blocked.add(reason("demix_plan_diagnostic_only",
                "Plan de demix calculado como replay diagnostico; el orden runtime real de Mixin aun debe verificarse antes de ejecutar."));
        if (!coremods.isEmpty()) {
            blocked.add(reason("coremod_present",
                    "El mod declara coremod/transformer; queda fuera del demix minimo."));
        }
        if (targetSet.isEmpty() && !mixinSet.isEmpty()) {
            blocked.add(reason("targets_unknown",
                    "Hay clases mixin, pero sus targets no pudieron resolverse todavia."));
        }
        boolean runtimeAttributionEvidenceAvailable = attributionCounts[4] > 0;
        Map<String, Object> txGate = buildTxGate(baseBytesCaptured, transformedBytesCaptured, inst, demix, blocked,
                coremods, mixinSet, targetSet, runtimeAttributionEvidenceAvailable,
                liveCapturedCount, syntheticCapturedCount);

        boolean bytecodeDiffAvailable = attributionCounts[3] > 0;
        String bytecodeDiffReason = bytecodeDiffAvailable
                ? (liveBytesCaptured ? null : "runtime_transformed_bytes_partial_capture")
                : "runtime_transformed_bytes_not_captured_exactly";

        out.put("mixinConfigs", mixinConfigs);
        out.put("mixinClasses", mixinClasses);
        out.put("mixinClassesTotal", Long.valueOf(mixinSet.size()));
        out.put("targetClasses", targetClasses);
        out.put("targetClassesTotal", Long.valueOf(targetSet.size()));
        out.put("targetClassesLoaded", Long.valueOf(loadedTargets));
        out.put("coremods", coremods);
        out.put("baseBytesCaptured", Boolean.valueOf(baseBytesCaptured));
        out.put("baseBytesCapturedCount", Long.valueOf(baseCaptured));
        out.put("baseBytesMissingCount", Long.valueOf(Math.max(0, targetSet.size() - baseCaptured)));
        out.put("baseBytecode", baseBytecode);
        out.put("baseBytecodeStoredTotal", Long.valueOf(BASE_BYTES.size()));
        out.put("baseBytecodeTrust", "classpath_resource_predefine");
        out.put("liveBytesCaptured", Boolean.valueOf(liveBytesCaptured));
        out.put("liveBytesCapturedCount", Long.valueOf(liveCapturedCount));
        out.put("liveBytesMissingCount", Long.valueOf(Math.max(0, targetSet.size() - liveCapturedCount)));
        out.put("liveBytecode", liveBytecode);
        out.put("liveBytecodeStoredTotal", Long.valueOf(Tier3LiveCapture.capturedCount()));
        out.put("liveBytecodeTrust", "live_first_load_capture");
        out.put("syntheticBytecode", syntheticBytecode);
        out.put("syntheticBytesCapturedCount", Long.valueOf(syntheticCapturedCount));
        out.put("syntheticBytecodeTrust", "synthetic_offline_transform_v1");
        out.put("transformedBytesCaptured", Boolean.valueOf(transformedBytesCaptured));
        out.put("transformedBytesCapturedCount", Long.valueOf(transformedBytesCapturedCount));
        out.put("transformedBytesMissingCount",
                Long.valueOf(Math.max(0, targetSet.size() - transformedBytesCapturedCount)));
        out.put("mixinAttributions", attributions);
        out.put("mixinAttributionsTotal", Long.valueOf(attributionCounts[0]));
        out.put("mixinDiffs", diffs);
        out.put("mixinDiffsTotal", Long.valueOf(attributionCounts[1]));
        out.put("mixinDiffModel", "declaration_structural_v1");
        out.put("mixinDiffsLiveConfirmedTotal", Long.valueOf(attributionCounts[2]));
        out.put("mixinDiffsLiveAvailableTotal", Long.valueOf(attributionCounts[3]));
        // T3 corte J (Pilar 1a): evidencia real @MixinMerged, no reemplaza el orden
        // secuencial (Pilar 1b sigue pendiente) -- solo confirma pertenencia real.
        out.put("mixinDiffsRuntimeAttributionConfirmedTotal", Long.valueOf(attributionCounts[4]));
        out.put("mixinDiffsRuntimeAttributionAvailableTotal", Long.valueOf(attributionCounts[5]));
        out.put("runtimeAttributionModel", "mixin_merged_runtime_evidence_v1");
        out.put("demixPlan", demix.map);
        out.put("demixPlanModel", "target_replay_plan_v1");
        out.put("demixPlanTargetsTotal", Long.valueOf(demix.targetsTotal));
        out.put("demixPlanSharedTargetsTotal", Long.valueOf(demix.sharedTargetsTotal));
        out.put("bytecodeDiffAvailable", Boolean.valueOf(bytecodeDiffAvailable));
        out.put("bytecodeDiffReason", bytecodeDiffReason);
        out.put("txGate", txGate);
        out.put("hotEligible", txGate.get("canLowerDecision"));
        out.put("blockedReasons", blocked);
        return out;
    }

    /**
     * T3 corte Parte 2: entry point liviano para Tier3DemixApply -- resuelve QUE
     * campos @Unique de {@code namespace} sobre {@code target} deben preservarse
     * (snapshot/restore), sin depender de que un plan() completo ya se haya
     * calculado (Tier3DemixApply.disableGroup/enableGroup se invocan hoy sin ese
     * gate previo -- ver Agent.java t3.demixGroupDisable/Enable). Recalcula solo
     * lo minimo: mixins del ns victima + contributors para UN target, reusando
     * el mismo escaneo que buildDemixPlan. Devuelve mapa vacio ante cualquier
     * incertidumbre (mod no encontrado, target sin contributors, excepcion
     * interna) -- equivalente al PRESERVE_FIELDS.get(target) de hoy, que
     * tambien da null/vacio por defecto; la decision fail-closed real ya ocurrio
     * antes, en canLowerDecision -- este metodo asume que el caller ya paso ese
     * gate y solo resuelve "que preservar", no "si es seguro proceder".
     */
    public static Map<String, String[]> preserveFieldsForTarget(String namespace, String target) {
        try {
            Map<String, Object> mod = null;
            for (Map<String, Object> m : Boot.mods) {
                if (namespace.equals(m.get("id"))) { mod = m; break; }
            }
            Map<String, List<Path>> rootsByNs = Boot.modRoots;
            List<Path> roots = resolveRoots(namespace, mod, rootsByNs);
            ScanResult scan = scanMixins(roots);

            Map<String, List<MixinContributor>> byTarget = new LinkedHashMap<String, List<MixinContributor>>();
            Set<String> targetFilter = Collections.singleton(target);
            long[] ordinal = new long[] { 0 };
            for (Map.Entry<String, List<Path>> e : rootsByNs.entrySet()) {
                String owner = e.getKey();
                List<Path> ownerRoots = e.getValue();
                if (owner == null || ownerRoots == null || ownerRoots.isEmpty()) continue;
                scanContributors(owner, ownerRoots, targetFilter, byTarget, ordinal);
            }
            addKnownVictimContributors(namespace, scan.mixinInfoByClass, scan.configsByMixin,
                    scan.pluginByConfig, targetFilter, byTarget);

            List<MixinContributor> contributors = dedupeContributors(
                    byTarget.containsKey(target) ? byTarget.get(target) : Collections.<MixinContributor>emptyList());
            Map<String, Object> fieldSafety = buildFieldSafety(namespace, target, contributors, rootsByNs);

            List<String> preserve = new ArrayList<String>();
            for (Object o : (List<?>) fieldSafety.get("fields")) {
                Map<?, ?> f = (Map<?, ?>) o;
                if ("preserve".equals(f.get("classification"))) preserve.add(String.valueOf(f.get("field")));
            }
            Map<String, String[]> out = new LinkedHashMap<String, String[]>();
            if (!preserve.isEmpty()) out.put(target, preserve.toArray(new String[0]));
            return out;
        } catch (Throwable t) {
            return Collections.emptyMap();
        }
    }

    /**
     * T3 corte tender-seeking-fern (tarea #33): resuelve el conjunto COMPLETO
     * (sin el CAP=64 de {@code plan()}/{@code targetClasses}, que es solo para
     * mostrar en el diagnostico) de target classes declarados por los mixins de
     * {@code namespace}. Usado por Ledger.disableOne para armar la lista real de
     * targets a pasar a Tier3DemixApply.disableGroup -- generaliza a CUALQUIER
     * namespace, reemplazando Tier3DemixApply.targetsForNamespace (hardcodeado
     * solo a "chat_heads"). Mismo patron que preserveFieldsForTarget: recalcula
     * lo minimo (resolveRoots + scanMixins), sin depender de que plan() ya haya
     * corrido antes. Devuelve set vacio ante cualquier incertidumbre (mod no
     * encontrado, excepcion interna) -- fail-closed, el caller ya decidio
     * proceder via canLowerDecision antes de llegar aca, esto solo resuelve
     * "cuales son los targets", nunca "si es seguro".
     */
    public static Set<String> scanTargets(String namespace) {
        try {
            Map<String, Object> mod = null;
            for (Map<String, Object> m : Boot.mods) {
                if (namespace.equals(m.get("id"))) { mod = m; break; }
            }
            Map<String, List<Path>> rootsByNs = Boot.modRoots;
            List<Path> roots = resolveRoots(namespace, mod, rootsByNs);
            ScanResult scan = scanMixins(roots);
            return new LinkedHashSet<String>(scan.targetSet);
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    /** S6: Modo de aplicación derivado en vivo para un target:
     * <ul>
     *   <li>PRESERVE_SHAPE: Conserva interfaces, campos y firmas, neutraliza métodos inyectados.</li>
     *   <li>REPLAY: Re-aplica mixins de co-owners sobre bytes base sin el mod víctima.</li>
     *   <li>RESET: Restaura bytes base originales (sin co-owners ni consumidores de forma).</li>
     *   <li>ADAPTER: Cierre y restauración explícita para mods con threads, JNI o recursos nativos.</li>
     *   <li>BLOCKED: Solo cuando se demuestre corrupción o violación de invariantes.</li>
     * </ul>
     */
    public enum DemixMode { RESET, REPLAY, PRESERVE_SHAPE, ADAPTER, BLOCKED }

    /** S7: Clasificación de consumidores externos para acotar auditoría. */
    public enum ExternalConsumerClassification {
        NO_REFERENCE,
        REFERENCE_DIRECTA_A_LA_VICTIMA,
        REFLECTION_RELACIONADA_CON_LA_VICTIMA,
        REFLECTION_NO_RELACIONADA,
        DINAMICA_NO_RESUELTA
    }

    /** Resultado de clasificar un target: modo + (si BLOCKED) las razones
     * concretas, nunca solo un booleano -- mismo principio de honestidad que
     * el resto del audit (ver blockers de buildTxGate). */
    public static final class TargetClassification {
        public final DemixMode mode;
        public final List<String> blockedReasons;
        TargetClassification(DemixMode mode, List<String> blockedReasons) {
            this.mode = mode;
            this.blockedReasons = blockedReasons;
        }
    }

    /**
     * T3 corte tier3 end-to-end §2: clasificacion en vivo RESET/REPLAY/BLOCKED
     * por target, generaliza {@code Tier3DemixApply.TARGETS}/{@code isEligible}
     * (whitelist estatica tipeada a mano, corte a corte, solo 2 namespaces) a
     * CUALQUIER namespace/target -- recalculada en cada llamada sobre estado
     * real, mismo espiritu liviano que {@link #preserveFieldsForTarget}
     * (no exige que un {@link #plan} completo ya haya corrido).
     *
     * <p>Reglas de clasificacion (mismo criterio "peor caso" que
     * buildFieldSafety/buildDemixPlan en todo este archivo):
     * <ul>
     *   <li>Sin co-owners (solo mixins de {@code namespace} sobre este target)
     *       -&gt; RESET.</li>
     *   <li>Con co-owners, Y el escaner de consumidores externos de forma
     *       (§1) no encontro ninguno Y no pudo completarse sin resolver, Y
     *       ningun contribuyente declara IMixinConfigPlugin, Y existe al
     *       menos un mixin real de {@code namespace} declarando este target
     *       (excludeSet no vacio, replay tiene algo que excluir) -&gt; REPLAY.</li>
     *   <li>Cualquier otro caso, incluida CUALQUIER excepcion durante el
     *       escaneo -&gt; BLOCKED, con la(s) razon(es) explicitas. Fail-closed:
     *       nunca se asume RESET/REPLAY por ausencia de evidencia.</li>
     * </ul>
     *
     * <p>El escaneo de clases externas (§1) se comparte entre TODOS los
     * targets pedidos via un solo {@link #scanExternalLoadedClasses}, mismo
     * patron de costo acotado que {@link #buildDemixPlan}.
     */
    public static Map<String, TargetClassification> classifyDemixTargets(String namespace,
            Set<String> targets, java.lang.instrument.Instrumentation inst) {
        Map<String, TargetClassification> out = new LinkedHashMap<String, TargetClassification>();
        if (namespace == null || namespace.isEmpty() || targets == null || targets.isEmpty()) return out;
        try {
            Map<String, List<Path>> rootsByNs = Boot.modRoots;
            if (rootsByNs == null) rootsByNs = Collections.emptyMap();
            Map<String, List<MixinContributor>> byTarget = new LinkedHashMap<String, List<MixinContributor>>();
            long[] ordinal = new long[] { 0 };
            for (Map.Entry<String, List<Path>> e : rootsByNs.entrySet()) {
                String owner = e.getKey();
                List<Path> roots = e.getValue();
                if (owner == null || roots == null || roots.isEmpty()) continue;
                scanContributors(owner, roots, targets, byTarget, ordinal);
            }
            Map<String, Object> mod = null;
            for (Map<String, Object> m : Boot.mods) {
                if (namespace.equals(m.get("id"))) { mod = m; break; }
            }
            List<Path> victimRoots = resolveRoots(namespace, mod, rootsByNs);
            if (victimRoots != null && !victimRoots.isEmpty()) {
                ScanResult victimScan = scanMixins(victimRoots);
                addKnownVictimContributors(namespace, victimScan.mixinInfoByClass, victimScan.configsByMixin,
                        victimScan.pluginByConfig, targets, byTarget);
            }

            Map<String, Class<?>> loaded = loadedTargets(inst, targets);
            Map<String, ShapeRefScanResult> externalClassShapes =
                    scanExternalLoadedClasses(inst, targets, loaded);

            for (String target : targets) {
                List<MixinContributor> contributors = dedupeContributors(
                        byTarget.containsKey(target) ? byTarget.get(target) : Collections.<MixinContributor>emptyList());
                boolean hasCoOwner = false;
                for (MixinContributor c : contributors) {
                    if (!namespace.equals(c.ownerNs)) { hasCoOwner = true; break; }
                }
                // T3 corte tender-seeking-fern (fix post-verificacion live, class_303):
                // estas 3 senales aplican SIEMPRE, con o sin co-owner -- el peligro de
                // corte AN (class_338$1 sobre class_303) es un consumidor EXTERNO no-mixin,
                // nunca aparece como co-owner, asi que "sin co-owner" NUNCA puede implicar
                // "seguro sin mas chequeo". Antes esta rama devolvia RESET incondicional
                // e ignoraba por completo estas 3 senales -- confirmado en vivo: class_303
                // tenia fieldSafety.allKnown=false, externalShapeConsumers.allKnown=false y
                // configPluginPresent=true simultaneamente y aun asi clasificaba RESET.
                List<String> reasons = new ArrayList<String>();
                Map<String, Object> externalShapeConsumers =
                        buildExternalShapeConsumers(namespace, target, contributors, externalClassShapes, rootsByNs);
                if (!boolObj(externalShapeConsumers.get("allKnown"))) {
                    reasons.add("external_shape_consumer_unverified");
                }
                Map<String, Object> fieldSafety = buildFieldSafety(namespace, target, contributors, rootsByNs);
                if (!boolObj(fieldSafety.get("allKnown"))) {
                    reasons.add("unique_field_safety_unverified");
                }
                // T3 corte "acotar riesgo de configPlugin al target especifico":
                // un IMixinConfigPlugin.shouldApplyMixin YA NO bloquea aqui, en ningun
                // caso (ni RESET ni REPLAY) -- confirmado por javap real contra
                // sponge-mixin-0.17.0+mixin.0.8.7 que Mixin invoca shouldApplyMixin
                // UNA sola vez, durante MixinConfig.prepare() al boot, para decidir que
                // pares (mixin,target) entran en mixinMapping; ese resultado ya fijo es
                // exactamente lo que getMixinsFor(target) devuelve de ahi en adelante.
                // Consecuencia real para cada modo:
                // - REPLAY: Tier3MixinReplay.replay ya llama getMixinsFor real (ver
                //   Tier3MixinReplay.java) -- lee la decision fija del plugin en vivo,
                //   nunca asume un conjunto estatico. disableViaReplay verifica
                //   ademas verify_excluded_set_matches_requested antes de tocar un
                //   solo byte: si el plugin vetara algo que el escaneo estatico no
                //   previo, el apply aborta sin mutar nada (REPLAY_EXCLUDE_SET_MISMATCH),
                //   no se confia ciegamente en el narrowing.
                // - RESET/ambos modos: buildFieldSafety/buildExternalShapeConsumers ya
                //   tratan TODO contributor declarado como candidato de peor caso, con
                //   o sin plugin -- un plugin solo puede VETAR una entrada declarada
                //   (nunca agregar una no declarada), asi que su dinamismo hace esas
                //   verificaciones mas conservadoras que la realidad, jamas menos.
                // No hay entonces ningun consumidor downstream que dependa de probar
                // estaticamente que el plugin es un predicado simple -- el bloqueador
                // quedaba mas conservador de lo necesario, no solo mal-acotado.
                // configPluginPresent/configPluginSimple se siguen exponiendo como
                // diagnostico informativo (ver buildDemixPlan), nunca escondidos.
                if (!reasons.isEmpty()) {
                    // S7 & S8: Si el problema es una interfaz o campo @Unique sin verificar,
                    // usar PRESERVE_SHAPE como modo preferido para conservar la forma sin bloquear.
                    boolean canUsePreserveShape = true;
                    for (String r : reasons) {
                        if (!"unique_field_safety_unverified".equals(r) &&
                            !"external_shape_consumer_unverified".equals(r)) {
                            canUsePreserveShape = false;
                            break;
                        }
                    }
                    if (canUsePreserveShape) {
                        out.put(target, new TargetClassification(DemixMode.PRESERVE_SHAPE, Collections.<String>emptyList()));
                        continue;
                    }
                }

                if (!hasCoOwner) {
                    out.put(target, reasons.isEmpty()
                            ? new TargetClassification(DemixMode.RESET, Collections.<String>emptyList())
                            : new TargetClassification(DemixMode.BLOCKED, reasons));
                    continue;
                }
                Set<String> excludeSet = mixinClassNamesForTarget(namespace, target, rootsByNs);
                if (excludeSet.isEmpty()) reasons.add("replay_victim_mixins_not_found");
                out.put(target, reasons.isEmpty()
                        ? new TargetClassification(DemixMode.REPLAY, Collections.<String>emptyList())
                        : new TargetClassification(DemixMode.BLOCKED, reasons));
            }
        } catch (Throwable t) {
            // Fail-closed: cualquier excepcion durante la clasificacion bloquea
            // TODOS los targets pedidos con una razon explicita, en vez de
            // dejarlos sin clasificar (lo que el llamador podria interpretar
            // silenciosamente como "no elegible" sin motivo).
            out.clear();
            List<String> reasons = Collections.singletonList("classification_scan_failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            for (String target : targets) {
                out.put(target, new TargetClassification(DemixMode.BLOCKED, reasons));
            }
        }
        return out;
    }

    /**
     * T3 corte Parte 2: entry point de solo-diagnostico para Tier3FieldScanSmoke
     * -- expone el escaneo crudo de un .class arbitrario sin pasar por
     * buildFieldSafety/preserveFieldsForTarget (que requieren un mixin real +
     * contributors). Confirma que scanFieldReferences completa sin
     * IOException de "opcode desconocido"/"desincronizado" sobre bytecode real
     * con invokeinterface -- el riesgo de implementacion explicito del plan.
     */
    static Set<String> scanFieldReferencesForSmoke(byte[] classBytes, String ownerDotted) throws IOException {
        return new ClassFile(classBytes).scanFieldReferences(ownerDotted).fieldNames;
    }

    /**
     * T3 corte Parte 2: muestra hasta {@code cap} clases mixin reales (bytes
     * crudos, nombre dotted) de los mods actualmente instalados, para que
     * Tier3FieldScanSmoke pueda ejercitar scanFieldReferences contra
     * bytecode de mixin genuino ademas de las clases JDK garantizadas.
     * Mapa vacio (no excepcion) si no hay mods/roots disponibles todavia --
     * best-effort, el smoke test no depende de esto para pasar.
     */
    static Map<String, byte[]> sampleMixinBytesForSmoke(int cap) {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        try {
            Map<String, List<Path>> rootsByNs = Boot.modRoots;
            if (rootsByNs == null) return out;
            for (Map.Entry<String, List<Path>> e : rootsByNs.entrySet()) {
                if (out.size() >= cap) break;
                List<Path> roots = e.getValue();
                if (roots == null || roots.isEmpty()) continue;
                ScanResult scan = scanMixins(roots);
                for (String mixin : scan.mixinInfoByClass.keySet()) {
                    if (out.size() >= cap) break;
                    if (out.containsKey(mixin)) continue;
                    byte[] bytes = readClassBytes(roots, mixin);
                    if (bytes != null) out.put(mixin, bytes);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static Map<String, Object> buildTxGate(boolean baseBytesCaptured,
                                                   boolean transformedBytesCaptured,
                                                   java.lang.instrument.Instrumentation inst,
                                                   DemixPlan demix,
                                                   List<Object> auditBlockers,
                                                   List<Object> coremods,
                                                   Set<String> mixinSet,
                                                   Set<String> targetSet,
                                                   boolean runtimeAttributionEvidenceAvailable,
                                                   long liveBytesCapturedCount,
                                                   long syntheticBytesCapturedCount) {
        Map<String, Object> gate = new LinkedHashMap<String, Object>();
        List<Object> blockers = new ArrayList<Object>();

        boolean redefineSupported = false;
        try {
            redefineSupported = inst != null && inst.isRedefineClassesSupported();
        } catch (Throwable ignored) {
        }
        Map<String, Object> demixMap = demix == null ? Collections.<String, Object>emptyMap() : demix.map;
        boolean demixSafe = boolObj(demixMap.get("safeToExecute"));
        boolean demixExecutable = boolObj(demixMap.get("executable"));
        // T3 corte K: orderTrust sube a "runtime_mixin_order_verified" en
        // buildDemixPlan solo cuando TODOS los targets del ns tienen secuencia real
        // capturada por el hook ASM sobre MixinInfo.createContextFor (Pilar 1b) que
        // coincide exacto con los contributors declarados. runtimeOrderTrusted sigue
        // sin derivarse de runtimeAttributionEvidenceAvailable (Pilar 1a, pertenencia
        // de miembro, no secuencia) -- son evidencias independientes.
        boolean runtimeOrderTrusted = "runtime_mixin_order_verified".equals(String.valueOf(demixMap.get("orderTrust")));
        // T3 corte L (Pilar 2): ya no deriva de requiresTransformerQuiescence (que es
        // una constante fija TRUE, siempre da false). Deriva del agregado real:
        // verified solo si NINGUN target del ns solapa con las 9 familias que
        // LedgerTransformer muta -- mismo patron de auto-derivacion por string que
        // runtimeOrderTrusted (corte K).
        boolean transformerQuiesced = "no_own_transformer_overlap_detected"
                .equals(String.valueOf(demixMap.get("transformerQuiescenceTrust")));
        // T3 corte Parte 2: verified solo si buildDemixPlan no encontro ningun campo
        // @Unique de clasificacion "unknown" en ningun target del ns -- mismo patron
        // de auto-derivacion por string que runtimeOrderTrusted/transformerQuiesced.
        boolean fieldSafetyVerified = "no_unresolved_unique_fields"
                .equals(String.valueOf(demixMap.get("fieldSafetyTrust")));
        // T3 corte tier3 end-to-end §1: generaliza corte AN -- verified solo si
        // buildDemixPlan no encontro ninguna clase cargada fuera del grupo de
        // redefine que referencie una interfaz/campo inyectado por mixin sobre
        // algun target del ns, ni ningun escaneo sin completar.
        boolean externalShapeConsumerVerified = "no_external_shape_consumer_found"
                .equals(String.valueOf(demixMap.get("externalShapeConsumerTrust")));
        // T3 corte "acotar riesgo de configPlugin al target especifico": ya NO es un
        // predicado de canLowerDecision -- confirmado por javap real contra
        // sponge-mixin-0.17.0+mixin.0.8.7 que shouldApplyMixin se invoca una sola vez
        // al boot (MixinConfig.prepare) y que el resto del pipeline (REPLAY via
        // getMixinsFor real + verify_excluded_set_matches_requested; fieldSafety/
        // externalShapeConsumers ya worst-case) es seguro sin importar el plugin.
        // Se sigue calculando y exponiendo como diagnostico informativo
        // (mixinConfigPluginAbsent), nunca escondido, solo que ya no bloquea.
        String configPluginTrust = String.valueOf(demixMap.get("mixinConfigPluginTrust"));
        boolean noConfigPluginPresent = "no_config_plugin_declared".equals(configPluginTrust)
                || "config_plugin_provably_simple_string_predicate".equals(configPluginTrust);
        boolean targetsKnown = targetSet != null && (!targetSet.isEmpty() || mixinSet == null || mixinSet.isEmpty());
        boolean noCoremods = coremods == null || coremods.isEmpty();
        boolean rollbackMechanismAvailable = redefineSupported && baseBytesCaptured;
        // T3 corte I (Pilar 0): ya no hardcodeado. Refleja el agregado real de
        // Tier3LiveCapture -- captura pasiva en la primera definicion real de cada
        // target, ya tejida por Mixin (ver Tier3LiveCapture, Boot.computeTier3WatchSet).
        // T3 corte AC: el agregado ahora admite una segunda fuente valida --
        // transformClassBytes sintetico offline (corte AB) como respaldo para
        // targets que nunca cargan organicamente. transformedBytesCaptured ya
        // combina ambas fuentes sin esconder cual aporto cada target (ver
        // syntheticBytecode/liveBytecode por separado en la salida de plan()).
        boolean runtimeTransformedBytesCaptured = transformedBytesCaptured;
        boolean runtimeTransformedBytesFullyOrganic = runtimeTransformedBytesCaptured
                && syntheticBytesCapturedCount == 0;

        if (!redefineSupported) blockers.add(reason("redefine_classes_not_supported",
                "La JVM/agent no reporta soporte para Instrumentation.redefineClasses."));
        if (!baseBytesCaptured) blockers.add(reason("base_bytes_incomplete",
                "No hay snapshot completo de bytecode base para todos los targets."));
        if (!runtimeTransformedBytesCaptured) blockers.add(reason("runtime_transformed_bytes_missing",
                "No hay snapshot exacto de los bytes transformados vivos para rollback; retransform no entrega el bytecode redefinido actual."));
        if (!demixSafe || !demixExecutable) blockers.add(reason("demix_not_executable",
                "El plan de demix sigue siendo diagnostico y no ejecutable."));
        if (!runtimeOrderTrusted) blockers.add(reason("runtime_mixin_order_unverified",
                "Falta verificar el orden runtime real de Mixin/plugin/priority."));
        if (!transformerQuiesced) blockers.add(reason("transformer_quiescence_unverified",
                "Falta garantizar quiescencia de transformers antes de redefinir."));
        if (!fieldSafetyVerified) blockers.add(reason("unique_field_safety_unverified",
                "Hay campos @Unique con clasificacion desconocida (bytecode no concluyente, posible "
                        + "reflexion/lambda) sin resolver."));
        if (!externalShapeConsumerVerified) blockers.add(reason("external_shape_consumer_unverified",
                "Hay clases cargadas fuera del grupo de redefine que podrian depender de una interfaz o "
                        + "campo inyectado por mixin sobre al menos un target (o el escaneo no pudo "
                        + "completarse), sin resolver."));
        if (!targetsKnown) blockers.add(reason("targets_unknown",
                "Hay mixins cuyos targets no se resolvieron de forma confiable."));
        if (!noCoremods) blockers.add(reason("coremod_present",
                "El mod declara coremod/transformer fuera del demix minimo."));

        boolean receiptReady = true;
        // T3 corte AL: ya no hardcodeados. txImplementationReady refleja que
        // Tier3DemixApply es ahora un aplicador real conectado (disable/enable con
        // receipt/rollback), incluyendo el camino de replay para targets co-owned
        // (Tier3MixinReplay, cortes AK/AL). rollbackAvailable deriva del mismo
        // hecho: enable() restaura siempre a los bytes vivos capturados,
        // independientemente de que exclude-set se uso en el disable.
        boolean txImplementationReady = true;
        boolean rollbackAvailable = txImplementationReady;
        if (!txImplementationReady) blockers.add(reason("tier3_tx_apply_not_implemented",
                "Aun no existe aplicador transaccional de demix con receipt/rollback sobre targets reales."));
        if (!rollbackMechanismAvailable) {
            blockers.add(reason("rollback_prereq_missing",
                    "Rollback requiere redefineClasses y bytes base completos."));
        } else if (!rollbackAvailable) {
            blockers.add(reason("rollback_pipeline_pending",
                    "Rollback de demix real requiere snapshot de bytes transformados vivos y preservar/replay de mixins no victima."));
        }

        gate.put("model", "tier3_tx_gate_v1");
        gate.put("decisionContract", "requires_restart_until_all_predicates_true");
        gate.put("canLowerDecision", Boolean.valueOf(blockers.isEmpty()));
        gate.put("eligibleDecisionWhenViable", "ask");
        gate.put("autoEligible", Boolean.FALSE);
        gate.put("receiptRequired", Boolean.TRUE);
        gate.put("receiptModel", "tier3_demix_tx_receipt_v1");
        gate.put("receiptReady", Boolean.valueOf(receiptReady));
        gate.put("rollbackRequired", Boolean.TRUE);
        gate.put("rollbackAvailable", Boolean.valueOf(rollbackAvailable));
        gate.put("rollbackMechanismAvailable", Boolean.valueOf(rollbackMechanismAvailable));
        gate.put("runtimeTransformedBytesCaptured", Boolean.valueOf(runtimeTransformedBytesCaptured));
        gate.put("runtimeTransformedBytesFullyOrganic", Boolean.valueOf(runtimeTransformedBytesFullyOrganic));
        gate.put("liveBytesCapturedCount", Long.valueOf(liveBytesCapturedCount));
        gate.put("syntheticBytesCapturedCount", Long.valueOf(syntheticBytesCapturedCount));
        gate.put("txImplementationReady", Boolean.valueOf(txImplementationReady));
        gate.put("redefineSupported", Boolean.valueOf(redefineSupported));
        gate.put("baseBytesCaptured", Boolean.valueOf(baseBytesCaptured));
        gate.put("demixSafeToExecute", Boolean.valueOf(demixSafe));
        gate.put("demixExecutable", Boolean.valueOf(demixExecutable));
        gate.put("runtimeMixinOrderTrusted", Boolean.valueOf(runtimeOrderTrusted));
        gate.put("transformerQuiescenceVerified", Boolean.valueOf(transformerQuiesced));
        gate.put("transformerQuiescenceEvidenceAvailable",
                Boolean.valueOf(boolObj(demixMap.get("transformerQuiescenceEvidenceAvailable"))));
        gate.put("uniqueFieldSafetyVerified", Boolean.valueOf(fieldSafetyVerified));
        gate.put("externalShapeConsumerVerified", Boolean.valueOf(externalShapeConsumerVerified));
        gate.put("mixinConfigPluginAbsent", Boolean.valueOf(noConfigPluginPresent));
        gate.put("targetsKnown", Boolean.valueOf(targetsKnown));
        gate.put("coremodsSupported", Boolean.valueOf(noCoremods));
        // T3 corte J: informativo, no alimenta ningun predicado de canLowerDecision.
        // Deja rastro para el Pilar 1b (hook ASM) sin prometer orden secuencial.
        gate.put("runtimeAttributionEvidenceAvailable", Boolean.valueOf(runtimeAttributionEvidenceAvailable));
        gate.put("blockers", blockers);
        gate.put("auditBlockers", auditBlockers == null ? Collections.emptyList() : new ArrayList<Object>(auditBlockers));
        return gate;
    }

    private static boolean boolObj(Object o) {
        return Boolean.TRUE.equals(o);
    }

    private static List<Path> resolveRoots(String ns, Map<String, Object> mod,
                                           Map<String, List<Path>> rootsByNs) {
        if (rootsByNs == null || rootsByNs.isEmpty()) return null;
        LinkedHashSet<String> keys = new LinkedHashSet<String>();
        if (ns != null && !ns.isEmpty()) keys.add(ns);
        if (mod != null) {
            Object id = mod.get("id");
            if (id != null) keys.add(String.valueOf(id));
            Object nsList = mod.get("namespaces");
            if (nsList instanceof List) {
                for (Object o : (List<?>) nsList) {
                    if (o != null) keys.add(String.valueOf(o));
                }
            }
        }
        for (String key : keys) {
            List<Path> roots = rootsByNs.get(key);
            if (roots != null && !roots.isEmpty()) return roots;
        }
        return null;
    }

    private static void inspectRoot(Path root, List<Object> configs, Set<String> mixins,
                                    Map<String, List<String>> configsByMixin,
                                    Map<String, String> pluginByConfig,
                                    List<Object> coremods, List<Object> blocked) {
        if (root == null) return;
        if (Files.isRegularFile(root)) {
            try (FileSystem fs = FileSystems.newFileSystem(root, (ClassLoader) null)) {
                for (Path fsRoot : fs.getRootDirectories()) {
                    inspectTree(fsRoot, fsRoot, configs, mixins, configsByMixin, pluginByConfig, coremods, blocked);
                }
            } catch (Throwable t) {
                blocked.add(reason("root_unreadable", "No se pudo montar " + root + ": " + t.getClass().getSimpleName()));
            }
            return;
        }
        inspectTree(root, root, configs, mixins, configsByMixin, pluginByConfig, coremods, blocked);
    }

    private static void inspectTree(Path walkRoot, Path relRoot, List<Object> configs,
                                    Set<String> mixins, Map<String, List<String>> configsByMixin,
                                    Map<String, String> pluginByConfig,
                                    List<Object> coremods, List<Object> blocked) {
        try (Stream<Path> walk = Files.walk(walkRoot)) {
            for (java.util.Iterator<Path> it = walk.iterator(); it.hasNext(); ) {
                Path p = it.next();
                if (Files.isDirectory(p)) continue;
                String rel = relativize(relRoot, p);
                String lower = rel.toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".mixins.json")) {
                    Map<String, Object> cfg = readMixinConfig(rel, p, mixins, configsByMixin, pluginByConfig, blocked);
                    if (configs.size() < CAP) configs.add(cfg);
                } else if (lower.equals("meta-inf/coremods.json")
                        || lower.equals("meta-inf/services/cpw.mods.modlauncher.api.itransformationservice")) {
                    if (coremods.size() < CAP) coremods.add(rel);
                } else if (lower.equals("meta-inf/manifest.mf")) {
                    readManifestSignals(rel, p, configs, coremods);
                }
            }
        } catch (Throwable t) {
            blocked.add(reason("tree_unreadable", "No se pudo recorrer " + walkRoot + ": " + t.getClass().getSimpleName()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMixinConfig(String rel, Path p, Set<String> mixins,
                                                       Map<String, List<String>> configsByMixin,
                                                       Map<String, String> pluginByConfig,
                                                       List<Object> blocked) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("path", rel);
        try {
            String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            Object parsed = Agent.Json.parse(json);
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("config no es objeto");
            Map<String, Object> m = (Map<String, Object>) parsed;
            String pkg = m.get("package") instanceof String ? (String) m.get("package") : "";
            List<Object> found = new ArrayList<Object>();
            addMixins(m.get("mixins"), pkg, rel, mixins, configsByMixin, found);
            addMixins(m.get("client"), pkg, rel, mixins, configsByMixin, found);
            addMixins(m.get("server"), pkg, rel, mixins, configsByMixin, found);
            out.put("mixins", found);
            out.put("count", Long.valueOf(found.size()));
            Object plugin = m.get("plugin");
            if (plugin instanceof String && !((String) plugin).isEmpty()) {
                out.put("plugin", plugin);
                // T3 corte AE: indexado por ruta de config para que
                // MixinContributor pueda resolver, por mixin, si su config
                // declara un IMixinConfigPlugin condicional (ej. Indigo se
                // autodesactiva si Sodium esta presente -- ver corte AD).
                pluginByConfig.put(rel, (String) plugin);
            }
        } catch (Throwable t) {
            out.put("error", t.getClass().getSimpleName());
            blocked.add(reason("mixin_config_unreadable", "No se pudo leer " + rel + ": " + t.getClass().getSimpleName()));
        }
        return out;
    }

    private static void readManifestSignals(String rel, Path p, List<Object> configs, List<Object> coremods) {
        try (java.io.InputStream in = Files.newInputStream(p)) {
            java.util.jar.Manifest mf = new java.util.jar.Manifest(in);
            java.util.jar.Attributes main = mf.getMainAttributes();
            String mixinConfigs = main.getValue("MixinConfigs");
            if (mixinConfigs != null && !mixinConfigs.trim().isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("path", rel + ":MixinConfigs");
                m.put("declared", mixinConfigs);
                if (configs.size() < CAP) configs.add(m);
            }
            String core = main.getValue("FMLCorePlugin");
            if (core != null && !core.trim().isEmpty() && coremods.size() < CAP) {
                coremods.add(rel + ":FMLCorePlugin=" + core);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void addMixins(Object value, String pkg, String configPath, Set<String> mixins,
                                  Map<String, List<String>> configsByMixin, List<Object> found) {
        if (!(value instanceof List)) return;
        for (Object o : (List<?>) value) {
            if (!(o instanceof String)) continue;
            String name = (String) o;
            // Corte AJ: Mixin real (MixinInfo/MixinConfig, confirmado via javap) concatena
            // paquete+nombre sin excepcion para nombres con punto; no hay "ya calificado".
            String fqcn = pkg.isEmpty() ? name : pkg.endsWith(".") ? pkg + name : pkg + "." + name;
            List<String> cfgs = configsByMixin.get(fqcn);
            if (cfgs == null) {
                cfgs = new ArrayList<String>();
                configsByMixin.put(fqcn, cfgs);
            }
            if (!cfgs.contains(configPath)) cfgs.add(configPath);
            if (mixins.add(fqcn) && found.size() < CAP) found.add(fqcn);
        }
    }

    private static byte[] readClassBytes(List<Path> roots, String className) {
        if (roots == null) return null;
        String rel = className.replace('.', '/') + ".class";
        for (Path root : roots) {
            byte[] b = readClassBytes(root, rel);
            if (b != null) return b;
        }
        return null;
    }

    // T3 corte Parte 2 (verificacion): buildFieldSafety llama readClassBytes una vez
    // por (target x co-owner), sin CAP -- sobre un modpack grande (77 mods, cientos de
    // targets) eso es cientos/miles de remontajes de FileSystem del mismo jar. Los jars
    // de mods no cambian durante una sesion en vivo, asi que memoizar por (root,rel) es
    // seguro y elimina el remontaje redundante sin relajar el escaneo sin-CAP.
    private static final java.util.concurrent.ConcurrentHashMap<String, byte[]> CLASS_BYTES_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
    private static final byte[] CLASS_BYTES_NOT_FOUND = new byte[0];

    private static byte[] readClassBytes(Path root, String rel) {
        if (root == null) return null;
        String cacheKey = root.toAbsolutePath() + "!" + rel;
        byte[] cached = CLASS_BYTES_CACHE.get(cacheKey);
        if (cached != null) return cached.length == 0 ? null : cached;
        byte[] result = readClassBytesUncached(root, rel);
        CLASS_BYTES_CACHE.put(cacheKey, result == null ? CLASS_BYTES_NOT_FOUND : result);
        return result;
    }

    private static byte[] readClassBytesUncached(Path root, String rel) {
        if (Files.isRegularFile(root)) {
            try (FileSystem fs = FileSystems.newFileSystem(root, (ClassLoader) null)) {
                for (Path fsRoot : fs.getRootDirectories()) {
                    Path p = fsRoot.resolve(rel);
                    if (Files.isRegularFile(p)) return Files.readAllBytes(p);
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
        try {
            Path p = root.resolve(rel);
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MixinInfo parseMixinInfo(byte[] classBytes) throws IOException {
        ClassFile cf = new ClassFile(classBytes);
        return cf.mixinInfo();
    }

    private static ClassSummary parseClassSummary(byte[] classBytes) throws IOException {
        ClassFile cf = new ClassFile(classBytes);
        return cf.classSummary();
    }

    /** T3 corte J: miembros con evidencia @MixinMerged real sobre bytecode vivo. */
    private static List<MergedMember> parseRuntimeAttribution(byte[] liveBytes) throws IOException {
        ClassFile cf = new ClassFile(liveBytes);
        return cf.mixinMergedMembers();
    }

    private static long[] buildAttributionDiffs(String ns, Map<String, MixinInfo> mixins,
                                                Map<String, List<String>> configsByMixin,
                                                List<Object> attributions, List<Object> diffs) {
        long attributionTotal = 0;
        long diffTotal = 0;
        long liveDiffConfirmedTotal = 0;
        long liveDiffAvailableTotal = 0;
        long runtimeAttributionConfirmedTotal = 0;
        long runtimeAttributionAvailableTotal = 0;
        // T3 corte J: cachear por target -- varios mixins pueden declarar el mismo
        // target, no repetir el parseo de @MixinMerged una vez por cada uno.
        Map<String, List<MergedMember>> runtimeAttributionByTarget =
                new LinkedHashMap<String, List<MergedMember>>();
        for (Map.Entry<String, MixinInfo> e : mixins.entrySet()) {
            String mixin = e.getKey();
            MixinInfo info = e.getValue();
            List<String> configs = configsByMixin.get(mixin);
            if (configs == null) configs = Collections.emptyList();
            Map<String, Long> ops = operationCounts(info, null);
            attributionTotal += Math.max(1, info.targets.size());
            if (attributions.size() < CAP) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("ns", ns);
                m.put("mixin", mixin);
                m.put("configs", new ArrayList<String>(configs));
                m.put("targets", cappedStrings(info.targets));
                m.put("targetCount", Long.valueOf(info.targets.size()));
                m.put("declaredFields", Long.valueOf(info.fields.size()));
                m.put("declaredMethods", Long.valueOf(info.methods.size()));
                m.put("operationCounts", ops);
                attributions.add(m);
            }
            for (String target : info.targets) {
                diffTotal++;
                if (diffs.size() >= CAP) continue;
                byte[] baseBytes = BASE_BYTES.get(target);
                ClassSummary base = null;
                String baseError = null;
                if (baseBytes != null) {
                    try {
                        base = parseClassSummary(baseBytes);
                    } catch (Throwable t) {
                        baseError = t.getClass().getSimpleName();
                    }
                }
                ChangeSet changes = buildChanges(info, base);
                Map<String, Object> d = new LinkedHashMap<String, Object>();
                d.put("ns", ns);
                d.put("target", target);
                d.put("mixin", mixin);
                d.put("configs", new ArrayList<String>(configs));
                d.put("model", "mixin_declaration_vs_base_v1");
                d.put("baseCaptured", Boolean.valueOf(baseBytes != null));
                String baseSha256 = null;
                if (baseBytes != null) {
                    baseSha256 = sha256(baseBytes);
                    d.put("baseSize", Long.valueOf(baseBytes.length));
                    d.put("baseSha256", baseSha256);
                }
                if (base != null) {
                    d.put("baseClass", base.name);
                    d.put("baseFieldCount", Long.valueOf(base.fields.size()));
                    d.put("baseMethodCount", Long.valueOf(base.methods.size()));
                } else if (baseError != null) {
                    d.put("baseParseError", baseError);
                }
                // T3 corte I: bytecode vivo capturado pasivamente (Tier3LiveCapture) --
                // el mismo target puede ser capturado por otro mixin/entry en la misma
                // llamada; get() es idempotente. Cuando hay base Y vivo, la comparacion
                // de hash es un diff REAL sobre bytecode, no declarativo.
                byte[] liveBytes = Tier3LiveCapture.get(target);
                d.put("liveCaptured", Boolean.valueOf(liveBytes != null));
                if (liveBytes != null) {
                    String liveSha256 = sha256(liveBytes);
                    d.put("liveSize", Long.valueOf(liveBytes.length));
                    d.put("liveSha256", liveSha256);
                    d.put("liveSource", "live_first_load_capture");
                    if (baseSha256 != null) {
                        boolean differs = !liveSha256.equals(baseSha256);
                        d.put("liveDiffersFromBase", Boolean.valueOf(differs));
                        liveDiffAvailableTotal++;
                        if (differs) liveDiffConfirmedTotal++;
                    }
                    // T3 corte J (Pilar 1a): @MixinMerged es evidencia RUNTIME real de
                    // que este mixin efectivamente mergeo un miembro en este target --
                    // no reemplaza el orden secuencial (Pilar 1b, hook ASM pendiente),
                    // solo confirma pertenencia real en vez de declarativa.
                    List<MergedMember> merged = runtimeAttributionByTarget.get(target);
                    if (merged == null) {
                        try {
                            merged = parseRuntimeAttribution(liveBytes);
                        } catch (Throwable t) {
                            merged = Collections.emptyList();
                        }
                        runtimeAttributionByTarget.put(target, merged);
                    }
                    d.put("runtimeMergedMembersTotal", Long.valueOf(merged.size()));
                    List<Object> samples = new ArrayList<Object>();
                    boolean confirmed = false;
                    for (MergedMember mm : merged) {
                        if (!mixin.equals(mm.mixin)) continue;
                        confirmed = true;
                        if (samples.size() < 8) {
                            Map<String, Object> sample = new LinkedHashMap<String, Object>();
                            sample.put("member", mm.kind + " " + mm.name + mm.desc);
                            sample.put("priority", Long.valueOf(mm.priority));
                            samples.add(sample);
                        }
                    }
                    d.put("runtimeAttributionConfirmed", Boolean.valueOf(confirmed));
                    d.put("runtimeAttributionSamples", samples);
                    d.put("runtimeAttributionModel", "mixin_merged_runtime_evidence_v1");
                    runtimeAttributionAvailableTotal++;
                    if (confirmed) runtimeAttributionConfirmedTotal++;
                }
                d.put("changes", changes.samples);
                d.put("changesTotal", Long.valueOf(changes.total));
                d.put("operationCounts", operationCounts(info, base));
                diffs.add(d);
            }
        }
        return new long[] { attributionTotal, diffTotal, liveDiffConfirmedTotal, liveDiffAvailableTotal,
                runtimeAttributionConfirmedTotal, runtimeAttributionAvailableTotal };
    }

    private static DemixPlan buildDemixPlan(String victimNs, Map<String, List<Path>> rootsByNs,
                                            Map<String, MixinInfo> victimMixins,
                                            Map<String, List<String>> victimConfigsByMixin,
                                            Map<String, String> victimPluginByConfig,
                                            Set<String> victimTargets,
                                            Map<String, Class<?>> loadedTargets,
                                            java.lang.instrument.Instrumentation inst) {
        Map<String, List<MixinContributor>> byTarget =
                new LinkedHashMap<String, List<MixinContributor>>();
        DemixPlan plan = new DemixPlan();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("model", "target_replay_plan_v1");
        out.put("ns", victimNs);
        out.put("orderTrust", "discovered_config_declaration_order_not_mixin_runtime_order");
        out.put("baseStrategy", "reset_target_to_captured_base_then_replay_non_victim_mixins");
        if (victimTargets == null || victimTargets.isEmpty()) {
            out.put("safeToExecute", Boolean.FALSE);
            out.put("executable", Boolean.FALSE);
            out.put("decision", "diagnostic_only");
            out.put("reason", "Sin targets declarados para este ns -- nada que ejecutar.");
            out.put("targetPlans", Collections.emptyList());
            out.put("targetPlansTotal", Long.valueOf(0));
            out.put("wouldRemoveMixinsTotal", Long.valueOf(0));
            out.put("wouldReplayMixinsTotal", Long.valueOf(0));
            plan.map = out;
            return plan;
        }

        if (rootsByNs != null && !rootsByNs.isEmpty()) {
            long[] ordinal = new long[] { 0 };
            for (Map.Entry<String, List<Path>> e : rootsByNs.entrySet()) {
                String owner = e.getKey();
                List<Path> roots = e.getValue();
                if (owner == null || roots == null || roots.isEmpty()) continue;
                scanContributors(owner, roots, victimTargets, byTarget, ordinal);
            }
        }
        addKnownVictimContributors(victimNs, victimMixins, victimConfigsByMixin, victimPluginByConfig,
                victimTargets, byTarget);

        List<Object> targetPlans = new ArrayList<Object>();
        long removeTotal = 0;
        long replayTotal = 0;
        long sharedTargets = 0;
        long coMixinTargets = 0;
        boolean anyTargetHasOrderEvidence = false;
        boolean allTargetsOrderVerified = true;
        boolean allTargetsTransformerQuiesced = true;
        // T3 corte Parte 2: campos @Unique sin clasificacion segura/preserve
        // (unknown) bloquean igual que las otras dos agregaciones de este loop --
        // mismo patron, misma honestidad fail-closed (ver buildFieldSafety).
        boolean allTargetsFieldSafetyVerified = true;
        // T3 corte tier3 end-to-end §2: elegibilidad real del aplicador, derivada
        // en vivo (RESET sin co-owner, REPLAY con co-owner + evidencia limpia) --
        // ya no delega en una whitelist estatica tipeada a mano. Se acumula igual
        // que las otras agregaciones de este loop, sin depender del CAP de
        // targetPlans (ver el propio calculo mas abajo, tras externalShapeConsumers/
        // targetHasConfigPlugin).
        boolean allTargetsEligible = true;
        // T3 corte tier3 end-to-end §1: generaliza la leccion de corte AN
        // (class_303/HeadRenderable) a CUALQUIER namespace/target -- escaneada
        // UNA sola vez por llamada a buildDemixPlan, reusada por todos los
        // targets via buildExternalShapeConsumers (busqueda pura, sin re-scan).
        boolean allTargetsExternalShapeVerified = true;
        // T3 corte tier3 end-to-end §1: un IMixinConfigPlugin puede alterar el
        // conjunto de targets/mixins en runtime -- no se puede probar invariante
        // por escaneo estatico, se bloquea igual que las demas agregaciones.
        boolean allTargetsNoConfigPlugin = true;
        // T3 corte narrowing configPlugin: distingue "sin plugin" de "con plugin
        // pero todos simples" en el trust string -- necesario para emitir el tercer
        // valor diferenciado "config_plugin_provably_simple_string_predicate".
        boolean anyTargetHadPlugin = false;
        Map<String, ShapeRefScanResult> externalClassShapes =
                scanExternalLoadedClasses(inst, victimTargets, loadedTargets);
        for (String target : victimTargets) {
            List<MixinContributor> contributors = byTarget.get(target);
            if (contributors == null) contributors = Collections.emptyList();
            contributors = dedupeContributors(contributors);
            long victimCount = 0;
            long replayCount = 0;
            LinkedHashSet<String> coOwners = new LinkedHashSet<String>();
            for (MixinContributor c : contributors) {
                if (victimNs.equals(c.ownerNs)) victimCount++;
                else {
                    replayCount++;
                    coOwners.add(c.ownerNs);
                }
            }
            removeTotal += victimCount;
            replayTotal += replayCount;
            if (!coOwners.isEmpty()) {
                sharedTargets++;
                coMixinTargets += replayCount;
            }
            plan.targetsTotal++;
            // T3 corte K: evidencia de orden se acumula para TODOS los targets, igual
            // que removeTotal/replayTotal arriba, sin depender del CAP de targetPlans.
            List<Ledger.MixinOrderEntry> captured = Ledger.INSTANCE.mixinApplyOrder(target);
            Map<String, Object> runtimeOrder = buildRuntimeOrder(contributors, captured);
            if (boolObj(runtimeOrder.get("available"))) anyTargetHasOrderEvidence = true;
            if (!boolObj(runtimeOrder.get("verified"))) allTargetsOrderVerified = false;
            // T3 corte L (Pilar 2): auto-quiescencia se acumula para TODOS los targets,
            // mismo patron que runtimeOrder arriba -- sin depender del CAP de targetPlans.
            Map<String, Object> transformerQuiescence = buildTransformerQuiescence(target);
            if (boolObj(transformerQuiescence.get("overlapsOwnTransformer"))) allTargetsTransformerQuiesced = false;
            // T3 corte Parte 2: se acumula para TODOS los targets, mismo patron que
            // runtimeOrder/transformerQuiescence arriba -- sin depender del CAP de targetPlans.
            Map<String, Object> fieldSafety = buildFieldSafety(victimNs, target, contributors, rootsByNs);
            if (!boolObj(fieldSafety.get("allKnown"))) allTargetsFieldSafetyVerified = false;
            // T3 corte tier3 end-to-end §1: se acumula para TODOS los targets, mismo
            // patron que fieldSafety arriba -- sin depender del CAP de targetPlans.
            Map<String, Object> externalShapeConsumers =
                    buildExternalShapeConsumers(victimNs, target, contributors, externalClassShapes, rootsByNs);
            if (!boolObj(externalShapeConsumers.get("allKnown"))) allTargetsExternalShapeVerified = false;
            boolean targetHasConfigPlugin = false;
            for (MixinContributor c : contributors) {
                if (c.configPlugin != null) { targetHasConfigPlugin = true; break; }
            }
            if (targetHasConfigPlugin) anyTargetHadPlugin = true;
            // T3 corte "acotar riesgo de configPlugin al target especifico": ya NO
            // bloquea aqui en ningun caso -- ver el comentario extenso en
            // classifyDemixTargets con la justificacion completa (confirmada por
            // javap real contra sponge-mixin-0.17.0+mixin.0.8.7: shouldApplyMixin se
            // invoca una sola vez al boot, REPLAY lee la decision fija via getMixinsFor
            // real + verify_excluded_set_matches_requested, y fieldSafety/
            // externalShapeConsumers ya asumen peor caso para todo contributor
            // declarado con o sin plugin). targetConfigPluginSimple se sigue calculando
            // SOLO para el diagnostico informativo configPluginSimple/
            // mixinConfigPluginTrust (mas abajo) -- ya no participa en commonSignalsOk
            // ni en safeToExecute.
            boolean targetConfigPluginSimple = !targetHasConfigPlugin
                    || allPluginsForContributorsAreSimplePredicates(contributors, rootsByNs);
            if (!targetConfigPluginSimple) allTargetsNoConfigPlugin = false;
            // T3 corte tier3 end-to-end §2: elegibilidad real ya no delega en una
            // whitelist estatica (Tier3DemixApply.TARGETS/isEligible, retirada) --
            // se deriva de las mismas agregaciones que este loop ya calcula: sin
            // co-owners es RESET (siempre elegible); con co-owners requiere ademas
            // que el escaneo de consumidores externos de forma no haya encontrado
            // ni dejado sin resolver ninguna referencia, y que exista al menos un
            // mixin real de este ns declarando el target (sin eso el replay no tiene
            // nada que excluir). Mismo criterio exacto que
            // Tier3MixinAudit.classifyDemixTargets (Tier3DemixApply), sin re-escanear:
            // reusa coOwners/externalShapeConsumers ya calculados arriba en este mismo loop.
            // T3 corte tender-seeking-fern (fix post-verificacion live, class_303):
            // fieldSafety/externalShapeConsumers aplican SIEMPRE, con o sin
            // co-owners -- ver el mismo comentario en classifyDemixTargets. Antes, sin
            // co-owners, targetEligible era true incondicional y estas senales se
            // ignoraban por completo pese a estar ya calculadas arriba en este mismo loop.
            boolean commonSignalsOk = boolObj(fieldSafety.get("allKnown"))
                    && boolObj(externalShapeConsumers.get("allKnown"));
            boolean targetEligible;
            if (coOwners.isEmpty()) {
                targetEligible = commonSignalsOk;
            } else {
                Set<String> excludeSet = mixinClassNamesForTarget(victimNs, target, rootsByNs);
                targetEligible = commonSignalsOk && !excludeSet.isEmpty();
            }
            if (!targetEligible) allTargetsEligible = false;
            // T3 corte tender-seeking-fern (tarea #32): expone el modo decidido AQUI
            // MISMO (gate-time) para que el caller (Ledger.disableOne) pueda pasarlo
            // como "modo esperado" a Tier3DemixApply.disableGroup -- que lo re-verifica
            // en vivo en el momento de aplicar (anti-TOCTOU, ver DemixMode/classifyDemixTargets).
            // Mismo criterio exacto que classifyDemixTargets: coOwners vacio y senales OK ->
            // RESET; con coOwners y targetEligible -> REPLAY; si no, BLOCKED.
            String mode = !targetEligible ? "BLOCKED" : coOwners.isEmpty() ? "RESET" : "REPLAY";
            if (targetPlans.size() >= CAP) continue;
            Map<String, Object> tp = new LinkedHashMap<String, Object>();
            tp.put("target", target);
            tp.put("mode", mode);
            tp.put("baseCaptured", Boolean.valueOf(BASE_BYTES.containsKey(target)));
            tp.put("loaded", Boolean.valueOf(loadedTargets != null && loadedTargets.containsKey(target)));
            tp.put("sharedWithOtherMods", Boolean.valueOf(!coOwners.isEmpty()));
            tp.put("coOwners", cappedStrings(coOwners));
            tp.put("currentMixinsCount", Long.valueOf(contributors.size()));
            tp.put("victimMixinsCount", Long.valueOf(victimCount));
            tp.put("replayMixinsCount", Long.valueOf(replayCount));
            tp.put("removeMixins", contributorMaps(contributors, victimNs, true));
            tp.put("replayMixins", contributorMaps(contributors, victimNs, false));
            tp.put("steps", demixSteps(target, contributors, victimNs));
            // T3 corte J (Pilar 1a): evidencia real @MixinMerged sobre bytecode vivo --
            // atribucion/pertenencia confirmada o divergente, NO orden secuencial
            // (eso sigue siendo Pilar 1b, hook ASM pendiente).
            Map<String, Object> runtimeAttribution = buildRuntimeAttribution(target, contributors);
            tp.put("runtimeAttribution", runtimeAttribution);
            // T3 corte K (Pilar 1b): secuencia real capturada por el hook ASM sobre
            // MixinInfo.createContextFor -- orden secuencial completo, no solo
            // pertenencia de miembro (eso ya lo daba Pilar 1a).
            tp.put("runtimeOrder", runtimeOrder);
            // T3 corte L (Pilar 2): evidencia estatica de auto-solapamiento con las
            // clases que LedgerTransformer ya muta -- riesgo de re-entrada bajo un
            // futuro redefineClasses (Pilar 3), no cobertura de terceros ni de fase.
            tp.put("transformerQuiescence", transformerQuiescence);
            // T3 corte Parte 2: clasificacion automatica safe/preserve/unknown de cada
            // campo @Unique que un mixin del ns victima agrega a este target.
            tp.put("fieldSafety", fieldSafety);
            // T3 corte tier3 end-to-end §1: consumidores externos de la forma que
            // este target gana por mixin (interfaces via las 3 vias confirmadas +
            // campos @Unique), cruzados contra el indice de clases cargadas fuera
            // del grupo de redefine.
            tp.put("externalShapeConsumers", externalShapeConsumers);
            tp.put("configPluginPresent", Boolean.valueOf(targetHasConfigPlugin));
            // T3 corte narrowing configPlugin: expone si el analisis de bytecode
            // pudo probar que todos los plugins del target son predicados simples
            // de comparacion de String (true = bloqueador levantado por narrowing;
            // false o ausente = plugin presente y NO analizable estaticamente).
            if (targetHasConfigPlugin) {
                tp.put("configPluginSimple", Boolean.valueOf(targetConfigPluginSimple));
            }
            tp.put("risks", demixRisks(target, contributors, victimNs, coOwners, runtimeAttribution, runtimeOrder,
                    transformerQuiescence));
            // T3 corte AD: diagnostico estrictamente aditivo, solo para targets con
            // co-owners (donde ya sabemos que hay divergencia u orden por confirmar).
            // No sustituye ni cambia runtimeOrder/allTargetsOrderVerified/buildTxGate.
            if (!coOwners.isEmpty()) {
                tp.put("mixinOrderDiagnostics", diagnoseMixinRegistration(target, contributors, loadedTargets));
            }
            targetPlans.add(tp);
        }
        // T3 corte AL: safeToExecute/executable/decision dejan de ser constantes
        // -- se derivan de las tres agregaciones reales de este loop (elegibilidad
        // del aplicador, orden runtime verificado, quiescencia del transformer
        // propio). No se asume el resultado: si alguna agregacion quedo en false,
        // decision explica cual.
        // T3 corte "acotar riesgo de configPlugin al target especifico": allTargetsNoConfigPlugin
        // ya no participa aqui -- se retiro como precondicion de safeToExecute (ver
        // justificacion completa en classifyDemixTargets). Se sigue calculando arriba
        // solo para el diagnostico mixinConfigPluginTrust (mas abajo en esta funcion).
        boolean safeToExecute = allTargetsEligible && allTargetsOrderVerified
                && allTargetsTransformerQuiesced && allTargetsFieldSafetyVerified
                && allTargetsExternalShapeVerified;
        out.put("safeToExecute", Boolean.valueOf(safeToExecute));
        out.put("executable", Boolean.valueOf(safeToExecute));
        if (safeToExecute) {
            out.put("decision", "tx_apply_ready");
            out.put("reason", "Todos los targets son elegibles en Tier3DemixApply, con orden runtime "
                    + "verificado, sin solapamiento con el propio transformer, sin campos @Unique "
                    + "de clasificacion desconocida y sin consumidores externos de forma sin resolver.");
        } else {
            List<String> reasons = new ArrayList<String>();
            if (!allTargetsEligible) {
                reasons.add("uno o mas targets con co-owners no calzan en modo REPLAY (consumidor externo "
                        + "de forma sin resolver, o sin mixin real de este ns que excluir)");
            }
            if (!allTargetsOrderVerified) {
                reasons.add("orden runtime de Mixin no verificado para todos los targets");
            }
            if (!allTargetsTransformerQuiesced) {
                reasons.add("solapamiento sin resolver con el propio transformer (LedgerTransformer)");
            }
            if (!allTargetsFieldSafetyVerified) {
                reasons.add("hay campos @Unique con clasificacion desconocida (posible reflexion/lambda) sin resolver");
            }
            if (!allTargetsExternalShapeVerified) {
                reasons.add("hay clases fuera del grupo de redefine que referencian una interfaz o campo "
                        + "@Unique inyectado por mixin (o el escaneo no pudo completarse) sin resolver");
            }
            out.put("decision", "diagnostic_only");
            out.put("reason", String.join("; ", reasons));
        }
        out.put("targetPlans", targetPlans);
        out.put("targetPlansTotal", Long.valueOf(plan.targetsTotal));
        out.put("sharedTargetsTotal", Long.valueOf(sharedTargets));
        out.put("coMixinTargetsTotal", Long.valueOf(coMixinTargets));
        out.put("wouldRemoveMixinsTotal", Long.valueOf(removeTotal));
        out.put("wouldReplayMixinsTotal", Long.valueOf(replayTotal));
        out.put("requiresBaseBytes", Boolean.TRUE);
        out.put("requiresRuntimeMixinOrder", Boolean.TRUE);
        out.put("requiresRedefineClasses", Boolean.TRUE);
        out.put("requiresTransformerQuiescence", Boolean.TRUE);
        // T3 corte K: solo se sube a verificado si TODOS los targets de este ns
        // tienen secuencia capturada Y esa secuencia coincide exactamente con los
        // contributors declarados -- sin forzar cobertura parcial (mismo principio
        // de honestidad que corte I con liveBytesCaptured).
        if (allTargetsOrderVerified) {
            out.put("orderTrust", "runtime_mixin_order_verified");
            out.put("orderTrustModel", "mixin_info_create_context_entry_hook_v1");
        }
        out.put("runtimeOrderEvidenceAvailable", Boolean.valueOf(anyTargetHasOrderEvidence));
        // T3 corte L (Pilar 2): auto-quiescencia MKSA vs MKSA -- verified solo si
        // NINGUN target del ns solapa con las 9 familias que LedgerTransformer muta.
        // A diferencia de runtimeOrderEvidenceAvailable (Pilar 1b, puede dar false si
        // el hook ASM no capturo nada en la ventana del smoke), esta evidencia es
        // estatica y no depende de timing de captura -- por eso siempre true si el
        // ns tiene al menos un target.
        out.put("transformerQuiescenceTrust", allTargetsTransformerQuiesced
                ? "no_own_transformer_overlap_detected"
                : "own_transformer_overlap_unresolved");
        out.put("transformerQuiescenceModel", "own_transformer_overlap_static_v1");
        out.put("transformerQuiescenceEvidenceAvailable", Boolean.TRUE);
        // T3 corte Parte 2: verified solo si NINGUN target del ns tiene campos
        // @Unique de clasificacion desconocida -- mismo patron por-string que
        // orderTrust/transformerQuiescenceTrust arriba.
        out.put("fieldSafetyTrust", allTargetsFieldSafetyVerified
                ? "no_unresolved_unique_fields"
                : "unresolved_unique_fields_present");
        out.put("fieldSafetyModel", "static_bytecode_reference_scan_v1");
        // T3 corte tier3 end-to-end §1: generaliza corte AN -- verified solo si
        // NINGUNA clase cargada fuera del grupo de redefine (dentro del alcance
        // de classloader de los targets) referencia una interfaz/campo inyectado
        // por mixin sobre algun target de este ns, y ningun escaneo quedo sin
        // completar.
        out.put("externalShapeConsumerTrust", allTargetsExternalShapeVerified
                ? "no_external_shape_consumer_found"
                : "external_shape_consumer_unverified");
        out.put("externalShapeConsumerModel", "loaded_class_shape_reference_scan_v1");
        // T3 corte narrowing configPlugin: tres casos posibles:
        // - Sin plugin en ningun target -> "no_config_plugin_declared"
        // - Habia plugin(s) pero todos pasaron el narrowing (predicado simple de String)
        //   -> "config_plugin_provably_simple_string_predicate" (equivalente al gate)
        // - Habia plugin(s) y al menos uno no paso el narrowing
        //   -> "config_plugin_present_dynamic_targets_possible" (bloquea)
        // La variable allTargetsNoConfigPlugin ya acumula ambos casos OK (sin plugin +
        // narrowing exitoso); anyTargetHadPlugin distingue cual de los dos fue.
        out.put("mixinConfigPluginTrust", allTargetsNoConfigPlugin
                ? (anyTargetHadPlugin
                        ? "config_plugin_provably_simple_string_predicate"
                        : "no_config_plugin_declared")
                : "config_plugin_present_dynamic_targets_possible");
        out.put("mixinConfigPluginModel", "static_mixins_json_plugin_declaration_v1");
        plan.sharedTargetsTotal = sharedTargets;
        plan.map = out;
        return plan;
    }

    private static void scanContributors(String ownerNs, List<Path> roots, Set<String> targetFilter,
                                         Map<String, List<MixinContributor>> byTarget,
                                         long[] ordinal) {
        List<Object> configs = new ArrayList<Object>();
        List<Object> coremods = new ArrayList<Object>();
        List<Object> blocked = new ArrayList<Object>();
        Set<String> mixins = new LinkedHashSet<String>();
        Map<String, List<String>> configsByMixin = new LinkedHashMap<String, List<String>>();
        Map<String, String> pluginByConfig = new LinkedHashMap<String, String>();
        for (Path root : roots) {
            inspectRoot(root, configs, mixins, configsByMixin, pluginByConfig, coremods, blocked);
        }
        for (String mixin : mixins) {
            byte[] bytes = readClassBytes(roots, mixin);
            if (bytes == null) continue;
            try {
                MixinInfo info = parseMixinInfo(bytes);
                List<String> cfgs = configsByMixin.get(mixin);
                if (cfgs == null) cfgs = Collections.emptyList();
                for (String target : info.targets) {
                    if (!targetFilter.contains(target)) continue;
                    addContributor(byTarget, target,
                            new MixinContributor(ownerNs, mixin, cfgs, info, ordinal[0]++,
                                    resolveConfigPlugin(cfgs, pluginByConfig)));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void addKnownVictimContributors(String victimNs, Map<String, MixinInfo> victimMixins,
                                                   Map<String, List<String>> configsByMixin,
                                                   Map<String, String> pluginByConfig,
                                                   Set<String> targetFilter,
                                                   Map<String, List<MixinContributor>> byTarget) {
        long ordinal = 0;
        for (Map.Entry<String, MixinInfo> e : victimMixins.entrySet()) {
            String mixin = e.getKey();
            MixinInfo info = e.getValue();
            List<String> cfgs = configsByMixin.get(mixin);
            if (cfgs == null) cfgs = Collections.emptyList();
            for (String target : info.targets) {
                if (!targetFilter.contains(target)) continue;
                addContributor(byTarget, target,
                        new MixinContributor(victimNs, mixin, cfgs, info, ordinal++,
                                resolveConfigPlugin(cfgs, pluginByConfig)));
            }
        }
    }

    /**
     * T3 corte AE: resuelve el plugin (IMixinConfigPlugin) declarado por
     * cualquiera de las configs que declaran este mixin -- en la practica un
     * mixin pertenece a una sola config, no hace falta merge; primer match
     * no nulo alcanza.
     */
    private static String resolveConfigPlugin(List<String> configs, Map<String, String> pluginByConfig) {
        if (configs == null || pluginByConfig == null) return null;
        for (String cfg : configs) {
            String plugin = pluginByConfig.get(cfg);
            if (plugin != null) return plugin;
        }
        return null;
    }

    private static void addContributor(Map<String, List<MixinContributor>> byTarget,
                                       String target, MixinContributor c) {
        List<MixinContributor> list = byTarget.get(target);
        if (list == null) {
            list = new ArrayList<MixinContributor>();
            byTarget.put(target, list);
        }
        list.add(c);
    }

    private static List<MixinContributor> dedupeContributors(List<MixinContributor> in) {
        List<MixinContributor> out = new ArrayList<MixinContributor>();
        Set<String> seen = new LinkedHashSet<String>();
        for (MixinContributor c : in) {
            String key = c.ownerNs + "\n" + c.mixin;
            if (seen.add(key)) out.add(c);
        }
        Collections.sort(out, new java.util.Comparator<MixinContributor>() {
            public int compare(MixinContributor a, MixinContributor b) {
                if (a.ordinal < b.ordinal) return -1;
                if (a.ordinal > b.ordinal) return 1;
                return a.mixin.compareTo(b.mixin);
            }
        });
        return out;
    }

    private static List<Object> contributorMaps(List<MixinContributor> contributors,
                                                String victimNs, boolean victimOnly) {
        List<Object> out = new ArrayList<Object>();
        for (MixinContributor c : contributors) {
            boolean victim = victimNs.equals(c.ownerNs);
            if (victimOnly != victim) continue;
            if (out.size() >= CAP) break;
            out.add(contributorMap(c, victim));
        }
        return out;
    }

    private static Map<String, Object> contributorMap(MixinContributor c, boolean victim) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ns", c.ownerNs);
        m.put("mixin", c.mixin);
        m.put("configs", new ArrayList<String>(c.configs));
        m.put("victim", Boolean.valueOf(victim));
        m.put("order", Long.valueOf(c.ordinal));
        m.put("operationCounts", operationCounts(c.info, null));
        // T3 corte AE: aditivo -- null si la config de este mixin no declara
        // IMixinConfigPlugin, FQCN del plugin si si lo declara.
        m.put("configPlugin", c.configPlugin);
        return m;
    }

    private static List<Object> demixSteps(String target, List<MixinContributor> contributors,
                                           String victimNs) {
        List<Object> steps = new ArrayList<Object>();
        Map<String, Object> capture = new LinkedHashMap<String, Object>();
        capture.put("index", Long.valueOf(0));
        capture.put("action", "capture_base");
        capture.put("target", target);
        capture.put("source", "BASE_BYTES");
        steps.add(capture);
        Map<String, Object> reset = new LinkedHashMap<String, Object>();
        reset.put("index", Long.valueOf(1));
        reset.put("action", "reset_target_to_base");
        reset.put("target", target);
        reset.put("requiresRedefineClasses", Boolean.TRUE);
        steps.add(reset);
        long idx = 2;
        for (MixinContributor c : contributors) {
            if (victimNs.equals(c.ownerNs)) continue;
            if (steps.size() >= CAP) break;
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("index", Long.valueOf(idx++));
            s.put("action", "replay_mixin");
            s.put("target", target);
            s.put("ns", c.ownerNs);
            s.put("mixin", c.mixin);
            s.put("configs", new ArrayList<String>(c.configs));
            s.put("order", Long.valueOf(c.ordinal));
            s.put("orderTrust", "discovered_declaration_order");
            steps.add(s);
        }
        return steps;
    }

    private static List<Object> demixRisks(String target, List<MixinContributor> contributors,
                                           String victimNs, Set<String> coOwners,
                                           Map<String, Object> runtimeAttribution,
                                           Map<String, Object> runtimeOrder,
                                           Map<String, Object> transformerQuiescence) {
        List<Object> risks = new ArrayList<Object>();
        if (!BASE_BYTES.containsKey(target)) {
            risks.add(reason("base_missing", "Sin bytecode base no se puede reconstruir " + target + "."));
        }
        if (!coOwners.isEmpty()) {
            Map<String, Object> r = reason("shared_target",
                    "Otros namespaces tambien declaran mixins contra " + target + ".");
            r.put("coOwners", cappedStrings(coOwners));
            risks.add(r);
        }
        risks.add(reason("runtime_order_unverified",
                "El orden usa declaracion descubierta; falta confirmar prioridad/config/plugin reales de Mixin."));
        for (MixinContributor c : contributors) {
            if (!victimNs.equals(c.ownerNs) && hasDestructiveOperation(c.info)) {
                Map<String, Object> r = reason("preserved_mixin_deep_transform",
                        "Mixin preservado usa overwrite/injection; replay requiere pipeline real.");
                r.put("ns", c.ownerNs);
                r.put("mixin", c.mixin);
                risks.add(r);
                break;
            }
        }
        // T3 corte J: divergencia honesta entre lo declarado (scan de .mixins.json) y
        // lo que @MixinMerged confirma en vivo -- informativo, no bloquea por si solo.
        Object unexpected = runtimeAttribution == null ? null : runtimeAttribution.get("unexpectedRuntimeMixins");
        if (unexpected instanceof List && !((List<?>) unexpected).isEmpty()) {
            Map<String, Object> r = reason("runtime_attribution_diverges_from_declared",
                    "Bytecode vivo confirma mixins via @MixinMerged que no aparecen en los contributors declarados.");
            r.put("unexpectedRuntimeMixins", unexpected);
            risks.add(r);
        }
        // T3 corte K: divergencia honesta entre la secuencia real capturada por el
        // hook ASM y los contributors declarados -- informativo, no bloquea por si solo.
        Object unexpectedOrder = runtimeOrder == null ? null : runtimeOrder.get("unexpectedMixinsInSequence");
        Object missingOrder = runtimeOrder == null ? null : runtimeOrder.get("missingMixinsInSequence");
        boolean hasUnexpectedOrder = unexpectedOrder instanceof List && !((List<?>) unexpectedOrder).isEmpty();
        boolean hasMissingOrder = missingOrder instanceof List && !((List<?>) missingOrder).isEmpty();
        if (hasUnexpectedOrder || hasMissingOrder) {
            Map<String, Object> r = reason("runtime_order_diverges_from_declared",
                    "Secuencia real capturada por MixinInfo.createContextFor no coincide con los contributors declarados.");
            if (hasUnexpectedOrder) r.put("unexpectedMixinsInSequence", unexpectedOrder);
            if (hasMissingOrder) {
                r.put("missingMixinsInSequence", missingOrder);
                // T3 corte AE: aditivo -- de los missing, cuales tienen un
                // IMixinConfigPlugin condicional declarado (ver corte AD).
                r.put("missingMixinsPluginGated", runtimeOrder.get("missingMixinsPluginGated"));
            }
            risks.add(r);
        }
        // T3 corte L (Pilar 2): riesgo de re-entrada -- este target coincide con clases
        // que LedgerTransformer ya muta; un redefineClasses futuro (Pilar 3) re-dispara
        // ese transformer sobre bytes ya modificados.
        if (transformerQuiescence != null && boolObj(transformerQuiescence.get("overlapsOwnTransformer"))) {
            Map<String, Object> r = reason("own_transformer_overlap_risk",
                    "Este target coincide con clases que LedgerTransformer ya muta; "
                    + "un redefineClasses futuro (Pilar 3) re-dispara ese transformer.");
            r.put("overlappingFamilies", transformerQuiescence.get("overlappingFamilies"));
            risks.add(r);
        }
        return risks;
    }

    /**
     * T3 corte J (Pilar 1a): atribucion real via @MixinMerged sobre bytecode vivo --
     * confirma pertenencia de miembro, no orden secuencial (Pilar 1b pendiente).
     */
    private static Map<String, Object> buildRuntimeAttribution(String target, List<MixinContributor> contributors) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        byte[] liveBytes = Tier3LiveCapture.get(target);
        if (liveBytes == null) {
            out.put("available", Boolean.FALSE);
            return out;
        }
        List<MergedMember> merged;
        try {
            merged = parseRuntimeAttribution(liveBytes);
        } catch (Throwable t) {
            out.put("available", Boolean.FALSE);
            out.put("parseError", t.getClass().getSimpleName());
            return out;
        }
        LinkedHashSet<String> distinctRuntimeMixins = new LinkedHashSet<String>();
        for (MergedMember mm : merged) distinctRuntimeMixins.add(mm.mixin);
        LinkedHashSet<String> declared = new LinkedHashSet<String>();
        for (MixinContributor c : contributors) declared.add(c.mixin);
        LinkedHashSet<String> unexpected = new LinkedHashSet<String>(distinctRuntimeMixins);
        unexpected.removeAll(declared);
        out.put("available", Boolean.TRUE);
        out.put("model", "mixin_merged_runtime_evidence_v1");
        out.put("mergedMembersTotal", Long.valueOf(merged.size()));
        out.put("distinctRuntimeMixins", cappedStrings(distinctRuntimeMixins));
        out.put("distinctRuntimeMixinsTotal", Long.valueOf(distinctRuntimeMixins.size()));
        out.put("matchesDeclaredContributors", Boolean.valueOf(unexpected.isEmpty()));
        out.put("unexpectedRuntimeMixins", cappedStrings(unexpected));
        return out;
    }

    /**
     * T3 corte K (Pilar 1b): secuencia real de aplicacion capturada por el hook ASM
     * sobre MixinInfo.createContextFor -- orden secuencial completo, la fuente de
     * verdad que Pilar 1a (@MixinMerged) no podia dar (esa solo confirma pertenencia
     * de miembro). "verified" solo es true si la secuencia capturada coincide EXACTO
     * con los contributors declarados y estos no estan vacios -- sin forzar
     * cobertura parcial a verificado (honestidad, mismo criterio que corte I/J).
     */
    private static Map<String, Object> buildRuntimeOrder(List<MixinContributor> contributors,
                                                          List<Ledger.MixinOrderEntry> captured) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (captured == null || captured.isEmpty()) {
            out.put("available", Boolean.FALSE);
            out.put("verified", Boolean.FALSE);
            return out;
        }
        List<Object> sequence = new ArrayList<Object>();
        LinkedHashSet<String> distinctInSequence = new LinkedHashSet<String>();
        for (Ledger.MixinOrderEntry e : captured) {
            distinctInSequence.add(e.mixin);
            if (sequence.size() >= CAP) continue;
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("mixin", e.mixin);
            s.put("priority", Long.valueOf(e.priority));
            sequence.add(s);
        }
        LinkedHashSet<String> declared = new LinkedHashSet<String>();
        Map<String, String> pluginByMixin = new LinkedHashMap<String, String>();
        for (MixinContributor c : contributors) {
            declared.add(c.mixin);
            if (c.configPlugin != null) pluginByMixin.put(c.mixin, c.configPlugin);
        }
        LinkedHashSet<String> unexpected = new LinkedHashSet<String>(distinctInSequence);
        unexpected.removeAll(declared);
        LinkedHashSet<String> missing = new LinkedHashSet<String>(declared);
        missing.removeAll(distinctInSequence);
        boolean matches = unexpected.isEmpty() && missing.isEmpty();
        out.put("available", Boolean.TRUE);
        out.put("model", "mixin_info_create_context_entry_hook_v1");
        out.put("sequence", sequence);
        out.put("sequenceTotal", Long.valueOf(captured.size()));
        out.put("distinctMixinsInSequenceTotal", Long.valueOf(distinctInSequence.size()));
        out.put("unexpectedMixinsInSequence", cappedStrings(unexpected));
        out.put("missingMixinsInSequence", cappedStrings(missing));
        // T3 corte AE: desglose aditivo de "missing" -- no cambia matchesDeclaredContributors
        // ni verified. Un mixin declarado cuya config tiene IMixinConfigPlugin puede
        // estar ausente de la secuencia real porque el plugin lo excluyo condicionalmente
        // (mecanismo confirmado en corte AD, fabric-renderer-indigo + Sodium), no porque
        // el hook de captura (corte K) haya fallado. missingMixinsUnexplained es el resto
        // -- ausencias sin config plugin conocido que las justifique.
        List<Object> missingPluginGated = new ArrayList<Object>();
        List<Object> missingUnexplained = new ArrayList<Object>();
        for (String m : missing) {
            String plugin = pluginByMixin.get(m);
            if (plugin != null) {
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("mixin", m);
                entry.put("configPlugin", plugin);
                if (missingPluginGated.size() < CAP) missingPluginGated.add(entry);
            } else if (missingUnexplained.size() < CAP) {
                missingUnexplained.add(m);
            }
        }
        out.put("missingMixinsPluginGated", missingPluginGated);
        out.put("missingMixinsUnexplained", missingUnexplained);
        out.put("matchesDeclaredContributors", Boolean.valueOf(matches));
        // T3 corte AF: decision de politica de gate -- una ausencia EXPLICADA por
        // IMixinConfigPlugin declarado (ya excluida de missingUnexplained arriba) ya
        // no cuenta como orden no verificado (hipotesis de estabilidad de esa
        // exclusion durante toda la sesion JVM, confirmada leyendo el bytecode real
        // de IndigoMixinConfigPlugin -- decision cacheada una sola vez al boot).
        // matchesDeclaredContributors arriba sigue siendo el hecho crudo sin esta
        // politica; puede divergir de verified a proposito cuando
        // missingMixinsPluginGated no esta vacio -- no es un bug, es la señal
        // visible de que la politica esta actuando.
        boolean verifiedWithPolicy = unexpected.isEmpty() && missingUnexplained.isEmpty()
                && !declared.isEmpty();
        out.put("verified", Boolean.valueOf(verifiedWithPolicy));
        return out;
    }

    /**
     * T3 corte L (Pilar 2): auto-quiescencia MKSA vs MKSA -- verifica si este target
     * coincide con alguna de las familias de clases que LedgerTransformer ya muta
     * (canRetransform=true). Por especificacion de Instrumentation.redefineClasses,
     * cualquier redefine futuro de Pilar 3 sobre un target solapado re-dispara ese
     * transformer, con riesgo de doble inyeccion si los bytes ya lo llevaban. A
     * diferencia de runtimeOrder/runtimeAttribution (Pilares 0/1, dependientes de
     * captura en vivo con cobertura parcial posible), esta evidencia es comparacion
     * de strings pura -- "available" es SIEMPRE true, no hay cobertura parcial que
     * reportar. No cubre transformers de terceros ni fase de Mixin (fuera de alcance,
     * decision explicita del usuario en corte L).
     */
    private static Map<String, Object> buildTransformerQuiescence(String target) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Object> overlapping = new ArrayList<Object>();
        for (Map.Entry<String, String[]> e : OWN_TRANSFORMER_TARGET_FAMILIES.entrySet()) {
            for (String candidate : e.getValue()) {
                if (candidate.equals(target)) { overlapping.add(e.getKey()); break; }
            }
        }
        out.put("available", Boolean.TRUE);
        out.put("model", "own_transformer_overlap_static_v1");
        out.put("overlapsOwnTransformer", Boolean.valueOf(!overlapping.isEmpty()));
        out.put("overlappingFamilies", overlapping);
        return out;
    }

    /**
     * T3 corte Parte 2: clasifica cada campo @Unique que un mixin del namespace
     * victima agrega a {@code target} en safe/preserve/unknown, por escaneo
     * estatico de bytecode -- nunca investigacion manual. "Safe" exige que NINGUN
     * bytecode que sobrevive a apagar el mod (vanilla base o un co-owner del mismo
     * target) referencie el campo por nombre; si no se puede afirmar eso con
     * certeza (bytes base no capturados, fallo de parseo, reflexion/lambda/
     * invokedynamic detectado) el campo cae en "unknown" -- fail closed, nunca se
     * asume seguro. El caso @Accessor/@Invoker de un co-owner se resuelve ANTES
     * del escaneo de bytecode porque su cuerpo real recien lo sintetiza Mixin al
     * tejer sobre el target -- no existe en el .class del co-owner para escanear.
     */
    private static Map<String, Object> buildFieldSafety(String victimNs, String target,
                                                         List<MixinContributor> contributors,
                                                         Map<String, List<Path>> rootsByNs) {
        // T3 corte Parte 2 (fix post-verificacion): candidatos = campos @Unique
        // agregados por CUALQUIER contribuyente sobre este target, no solo el
        // namespace que se apaga. RESET (el modo real que usa disableGroup, ver
        // corte AM) redefine la clase entera a BASE_BYTES puro -- borra TODOS los
        // mixins aplicados sobre el target, incluidos los de co-owners que siguen
        // activos, no solo los del namespace victima. Confirmado con un caso real:
        // rendererPools en class_11228 lo agrega fabric-rendering-v1 (co-owner),
        // no chat_heads (el namespace que se apaga) -- con el filtro viejo ese
        // campo nunca entraba a la lista de candidatos y el escaneo automatico no
        // lo encontraba, aunque el registro a mano (PRESERVE_FIELDS, ya eliminado)
        // si lo tenia. La logica de "survivors" mas abajo ya escaneaba el
        // bytecode de TODOS los co-owners (no solo victimNs) -- ese lado ya
        // estaba bien, el hueco era solo en la seleccion de candidatos.
        List<String[]> candidates = new ArrayList<String[]>(); // {fieldName, addedByMixin}
        for (MixinContributor c : contributors) {
            for (MemberInfo f : c.info.fields) {
                if ("field_add_unique".equals(fieldOperation(f, null))) {
                    candidates.add(new String[] { f.name, c.mixin });
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("model", "static_bytecode_reference_scan_v1");
        if (candidates.isEmpty()) {
            out.put("fields", Collections.emptyList());
            out.put("allKnown", Boolean.TRUE);
            out.put("unknownTotal", Long.valueOf(0));
            return out;
        }

        boolean baseAvailable = BASE_BYTES.containsKey(target);
        List<byte[]> survivors = new ArrayList<byte[]>();
        if (baseAvailable) survivors.add(BASE_BYTES.get(target));
        Set<String> accessorFields = new LinkedHashSet<String>();
        for (MixinContributor c : contributors) {
            if (victimNs.equals(c.ownerNs)) continue; // solo co-owners sobreviven al apagar
            for (MemberInfo m : c.info.methods) {
                if ("method_accessor_bridge".equals(methodOperation(m, null))) {
                    accessorFields.add(accessorFieldName(m));
                }
            }
            List<Path> roots = rootsByNs == null ? null : rootsByNs.get(c.ownerNs);
            if (roots == null) continue;
            byte[] bytes = readClassBytes(roots, c.mixin);
            if (bytes != null) survivors.add(bytes);
        }

        Set<String> referenced = new LinkedHashSet<String>();
        boolean reflectionRisk = !baseAvailable; // sin bytecode base -- no se puede afirmar nada
        for (byte[] bytes : survivors) {
            try {
                FieldScanResult r = new ClassFile(bytes).scanFieldReferences(target);
                referenced.addAll(r.fieldNames);
                if (r.risk) reflectionRisk = true;
            } catch (Throwable t) {
                reflectionRisk = true; // fallo de parseo -- fail closed, no asumir seguro
            }
        }

        List<Object> fieldsOut = new ArrayList<Object>();
        long unknownTotal = 0;
        for (String[] cand : candidates) {
            String fieldName = cand[0];
            Map<String, Object> fEntry = new LinkedHashMap<String, Object>();
            fEntry.put("field", fieldName);
            fEntry.put("addedBy", cand[1]);
            if (referenced.contains(fieldName) || accessorFields.contains(fieldName)) {
                fEntry.put("classification", "preserve");
                fEntry.put("evidence", accessorFields.contains(fieldName)
                        ? "co_owner_accessor_invoker" : "direct_bytecode_reference");
            } else if (reflectionRisk) {
                fEntry.put("classification", "unknown");
                fEntry.put("evidence", "reflection_lambda_or_missing_base");
                unknownTotal++;
            } else {
                fEntry.put("classification", "safe");
                fEntry.put("evidence", "no_surviving_reference_found");
            }
            fieldsOut.add(fEntry);
        }
        out.put("fields", fieldsOut);
        out.put("allKnown", Boolean.valueOf(unknownTotal == 0));
        out.put("unknownTotal", Long.valueOf(unknownTotal));
        return out;
    }

    /** T3 corte tier3 end-to-end §1: marcador de "clase externa cuyo bytecode no
     * se pudo leer/parsear" -- risk=true con typeRefs/fieldNames vacios, para no
     * fabricar coincidencias sobre datos que nunca se obtuvieron. Usado por
     * scanExternalLoadedClasses cuando readLoadedClassBytes o el parseo fallan;
     * buildExternalShapeConsumers lo trata igual que cualquier otro risk=true. */
    private static final ShapeRefScanResult UNREADABLE_SHAPE_REF =
            unreadableShapeRef("bytecode_unreadable");

    /** T3 corte "root-cause diagnostico": variante de UNREADABLE_SHAPE_REF con un
     * motivo especifico (ej. "getClassLoader_threw", "parse_exception:&lt;msg&gt;")
     * en vez del generico -- mismo risk=true incondicional, solo cambia que se
     * reporta en buildExternalShapeConsumers para poder distinguir, sin re-
     * decompilar a mano, entre bytes nunca obtenidos vs. bytes obtenidos pero que
     * el parser rechazo. */
    private static ShapeRefScanResult unreadableShapeRef(String reason) {
        return new ShapeRefScanResult(Collections.<String>emptySet(), Collections.<String>emptySet(),
                true, Collections.singleton(reason));
    }

    /** T3 corte "auditoria librerias base": risk=false, typeRefs/fieldNames
     * vacios -- resultado para una clase de Netty/LWJGL/JOML/Guava cuyo hash de
     * bytecode coincide EXACTO con AUDITED_BASE_LIBRARY_HASHES (ver
     * isAuditedBaseLibraryClass). typeRefs vacio es correcto, no una omision:
     * la auditoria confirmo que estas clases nunca hacen CHECKCAST/INSTANCEOF/
     * INVOKEINTERFACE contra un tipo que Tier3 pueda inyectar (no conocen a
     * Mixin), asi que no hay ningun tipo real que reportar. */
    private static final ShapeRefScanResult AUDITED_BASE_LIBRARY_SAFE =
            new ShapeRefScanResult(Collections.<String>emptySet(), Collections.<String>emptySet(),
                    false, Collections.<String>emptySet());

    /**
     * T3 corte tier3 end-to-end §1: escanea, en UNA sola pasada reusada por
     * TODOS los targets de este namespace, las clases actualmente cargadas que
     * podrian ser consumidores externos de una forma inyectada por mixin --
     * generaliza la leccion de corte AN (class_303/HeadRenderable: class_338$1
     * hace un checkcast incondicional contra una interfaz que solo existe por
     * el mixin, nunca tocado por el redefine porque no es el mismo target ni
     * un contribuyente conocido).
     *
     * <p>Alcance deliberadamente acotado a clases cargadas por el MISMO
     * classloader que ya usan los targets de este namespace (mismo patron que
     * targetLoaders en captureBaseBytecode): un checkcast/instanceof contra una
     * interfaz inyectada por Mixin solo puede resolver en runtime si la clase
     * que lo ejecuta comparte el classloader del juego/mods (regla real de
     * resolucion de CONSTANT_Class de la JVM, no una suposicion) -- limitar el
     * universo a ese classloader excluye honestamente el resto de la JVM (JDK,
     * herramientas, el propio agente) sin fabricar seguridad: esas clases NO
     * PUEDEN referenciar una interfaz cargada por un classloader distinto.
     *
     * <p>Fail-closed: cualquier clase en ese alcance cuyo bytecode no se pueda
     * leer o parsear se registra como {@link #UNREADABLE_SHAPE_REF}
     * (risk=true) -- buildExternalShapeConsumers la trata como bloqueante para
     * CUALQUIER target cuya forma no pueda descartarse contra ella.
     */
    private static Map<String, ShapeRefScanResult> scanExternalLoadedClasses(
            java.lang.instrument.Instrumentation inst, Set<String> redefineGroup,
            Map<String, Class<?>> loadedTargets) {
        Map<String, ShapeRefScanResult> out = new LinkedHashMap<String, ShapeRefScanResult>();
        if (inst == null) return out;
        List<ClassLoader> scopeLoaders = new ArrayList<ClassLoader>();
        if (loadedTargets != null) {
            for (Class<?> c : loadedTargets.values()) {
                if (c == null) continue;
                ClassLoader cl = c.getClassLoader();
                if (cl != null && !scopeLoaders.contains(cl)) scopeLoaders.add(cl);
            }
        }
        if (scopeLoaders.isEmpty()) return out;
        Class<?>[] loaded;
        try {
            loaded = inst.getAllLoadedClasses();
        } catch (Throwable t) {
            return out;
        }
        for (Class<?> c : loaded) {
            if (c == null || c.isArray() || c.isPrimitive()) continue;
            String dotted = c.getName();
            if (dotted == null || dotted.isEmpty()) continue;
            if (redefineGroup != null && redefineGroup.contains(dotted)) continue;
            ClassLoader cl;
            try {
                cl = c.getClassLoader();
            } catch (Throwable t) {
                out.put(dotted, unreadableShapeRef("getClassLoader_threw:" + t.getClass().getName()));
                continue;
            }
            if (cl == null || !scopeLoaders.contains(cl)) continue;
            // T3 corte lambda-blindness: una clase "hidden" (JEP 371 --
            // Class.isHidden(), ej. la clase proxy que la JVM sintetiza para cada
            // lambda via LambdaMetafactory) nunca tiene un recurso .class en el
            // classloader -- readLoadedClassBytes siempre fallaria y esto la marcaria
            // UNREADABLE_SHAPE_REF (risk=true incondicional) sin excepcion. Se
            // saltea deliberadamente en vez de marcarla insegura porque su riesgo ya
            // esta cubierto en otro lado sin necesidad de leer sus bytes: (1) toda
            // clase hidden en este scope fue creada por Lookup.defineHiddenClass
            // invocado desde una clase legible cargada por el MISMO classloader
            // (garantia dura de JEP 371, no una suposicion) -- ese nest host SI se
            // escanea (aca mismo si no es del redefineGroup, o via el escaneo directo
            // de contribuyentes si lo es), y scanCode ya resuelve el narrowing real de
            // cualquier invokedynamic de LambdaMetafactory que contenga (ver case 186
            // en scanCode); (2) el unico productor realista de hidden classes en un
            // JVM Fabric/Minecraft es LambdaMetafactory -- StringConcatFactory por
            // defecto en HotSpot no genera clase (combinadores de MethodHandle,
            // estrategia INDY); la estrategia BC_SB que si generaria clase requiere
            // una JVM property explicita que MKSA no setea. Si algun dia aparece un
            // hidden class de otro origen, este carve-out lo saltearia sin la
            // cobertura de (1)/(2) -- limite de alcance conocido, no cubierto aqui.
            if (isHiddenClass(c)) continue;
            // T3 corte "ceguera a lambdas y clases externas": Tier3LiveCapture ahora
            // captura de forma incondicional (ya no solo watchSet) el bytecode que la
            // JVM le paso a defineClass para CUALQUIER clase no-hidden -- fuente
            // primaria, correcta por construccion, sin depender de que el classloader
            // exponga un recurso de classpath legible. readLoadedClassBytes
            // (cl.getResource) queda como fallback de segunda linea (defensa en
            // profundidad) para el caso raro de una clase que existiera antes de que
            // este agente se instalara en premain.
            byte[] bytes = Tier3LiveCapture.get(dotted);
            if (bytes == null) bytes = readLoadedClassBytes(c, cl);
            if (bytes == null) {
                out.put(dotted, unreadableShapeRef("bytes_never_obtained"));
                continue;
            }
            // T3 corte "auditoria librerias base": clases de Netty/jctools/LWJGL/
            // JOML/Guava cuyo bytecode Unsafe/reflection ya se audito sitio por
            // sitio (ver AUDITED_BASE_LIBRARY_HASHES) -- si el hash del bytecode
            // REALMENTE cargado coincide exacto con el auditado, se reporta
            // directamente como sin riesgo en vez de pasar por scanShapeReferences/
            // isReflectionRisk (que las marcaria "unknown" solo por invocar
            // Unsafe/reflexion, sin poder saber que esas llamadas nunca tocan una
            // clase del redefineGroup). Si el hash no coincide (libreria
            // actualizada, clase con el mismo nombre pero contenido distinto) esto
            // no aplica y cae al flujo normal fail-closed de abajo.
            if (isAuditedBaseLibraryClass(dotted, bytes)) {
                out.put(dotted, AUDITED_BASE_LIBRARY_SAFE);
                continue;
            }
            try {
                out.put(dotted, new ClassFile(bytes).scanShapeReferences(dotted));
            } catch (Throwable t) {
                out.put(dotted, unreadableShapeRef("parse_exception:" + t.getClass().getName() + ":" + t.getMessage()));
            }
        }
        return out;
    }

    /** Lee el bytecode de una clase YA cargada via el recurso .class de su
     * propio classloader (mismo patron que readResource/captureOne) -- no es
     * el bytecode "vivo" post-redefine (eso exigiria retransform, que corte H
     * ya probo poco confiable), pero para una clase de terceros que MKSA nunca
     * redefine, el recurso en classpath es exactamente el bytecode que corrio
     * al cargar la clase. */
    private static byte[] readLoadedClassBytes(Class<?> c, ClassLoader cl) {
        InputStream in = null;
        try {
            String resource = c.getName().replace('.', '/') + ".class";
            URL url = cl.getResource(resource);
            if (url == null) return null;
            in = url.openStream();
            byte[] bytes = readAll(in);
            if (bytes == null || bytes.length < 4) return null;
            if ((bytes[0] & 0xff) != 0xca || (bytes[1] & 0xff) != 0xfe
                    || (bytes[2] & 0xff) != 0xba || (bytes[3] & 0xff) != 0xbe) return null;
            return bytes;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * T3 corte tier3 end-to-end §1: cruza la forma que CUALQUIER contribuyente
     * (propio o co-owner -- mismo criterio "peor caso" que buildFieldSafety,
     * ver su comentario sobre RESET) agrega a este target -- interfaces via
     * las 3 vias confirmadas (accessor/invoker declarado como interface,
     * class_implements, @Implements/@Interface) mas campos @Unique -- contra
     * el indice ya escaneado de clases externas (scanExternalLoadedClasses).
     * Cualquier clase externa cuyo scan no se pudo completar, o que referencia
     * una interfaz/campo de esta forma, marca allKnown=false: la referencia
     * encontrada es tan bloqueante como la incertidumbre, nunca se asume
     * segura por omision.
     */
    private static Map<String, Object> buildExternalShapeConsumers(String victimNs,
                                                                    String target,
                                                                    List<MixinContributor> contributors,
                                                                    Map<String, ShapeRefScanResult> externalClassShapes,
                                                                    Map<String, List<Path>> rootsByNs) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("model", "loaded_class_shape_reference_scan_v1");

        Set<String> candidateInterfaces = new LinkedHashSet<String>();
        Set<String> candidateFields = new LinkedHashSet<String>();
        for (MixinContributor c : contributors) {
            if (victimNs != null && !victimNs.equals(c.ownerNs)) continue; // solo la forma inyectada por el ns victima se altera al apagar victima
            if (c.info.isInterface) candidateInterfaces.add(c.mixin);
            candidateInterfaces.addAll(c.info.declaredInterfaces);
            candidateInterfaces.addAll(c.info.implementsInterfaces);
            for (MemberInfo f : c.info.fields) {
                if ("field_add_unique".equals(fieldOperation(f, null))) candidateFields.add(f.name);
            }
        }
        if (candidateInterfaces.isEmpty() && candidateFields.isEmpty()) {
            out.put("candidateInterfaces", Collections.emptyList());
            out.put("candidateFields", Collections.emptyList());
            out.put("consumers", Collections.emptyList());
            out.put("allKnown", Boolean.TRUE);
            out.put("unknownTotal", Long.valueOf(0));
            return out;
        }

        List<Path> victimRoots = (rootsByNs != null && victimNs != null) ? rootsByNs.get(victimNs) : null;
        List<Object> consumers = new ArrayList<Object>();
        long unknownTotal = 0;
        for (Map.Entry<String, ShapeRefScanResult> e : externalClassShapes.entrySet()) {
            String consumerClass = e.getKey();
            if (victimNs != null && (consumerClass.equals(victimNs) || consumerClass.startsWith(victimNs + "."))) {
                continue; // clases del propio namespace victima no son consumidores EXTERNOS
            }
            if (victimRoots != null && readClassBytes(victimRoots, consumerClass) != null) {
                continue; // clases contenidas en los JARs del propio mod victima no son consumidores EXTERNOS
            }
            ShapeRefScanResult r = e.getValue();
            if (r.risk) {
                unknownTotal++;
                if (consumers.size() < CAP) {
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("consumerClass", consumerClass);
                    entry.put("classification", "unknown");
                    entry.put("evidence", "class_bytecode_unreadable_or_ambiguous");
                    // T3 corte "root-cause diagnostico": motivo(s) exacto(s) -- opcode/
                    // owner.name que puso risk=true, o el motivo a nivel-clase
                    // (bytes_never_obtained/parse_exception/getClassLoader_threw) --
                    // permite auditar los 65 "unknown" restantes sin re-decompilar cada
                    // uno a mano, ver plan "root-cause remaining unknown shape consumers".
                    if (r.riskReasons != null && !r.riskReasons.isEmpty()) {
                        entry.put("riskReasons", new ArrayList<String>(r.riskReasons));
                    }
                    consumers.add(entry);
                }
                continue;
            }
            String ifaceHit = null;
            for (String t : r.typeRefs) {
                if (candidateInterfaces.contains(t)) { ifaceHit = t; break; }
            }
            boolean fieldHit = false;
            for (String f : r.fieldNames) {
                if (candidateFields.contains(f)) { fieldHit = true; break; }
            }
            if (ifaceHit != null || fieldHit) {
                unknownTotal++; // referencia externa real -- bloquea igual que "unknown"
                if (consumers.size() < CAP) {
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("consumerClass", consumerClass);
                    entry.put("classification", "external_reference_found");
                    if (ifaceHit != null) entry.put("interfaceReferenced", ifaceHit);
                    if (fieldHit) entry.put("fieldReferenced", Boolean.TRUE);
                    consumers.add(entry);
                }
            }
        }
        out.put("candidateInterfaces", new ArrayList<String>(candidateInterfaces));
        out.put("candidateFields", new ArrayList<String>(candidateFields));
        out.put("consumers", consumers);
        out.put("consumersTotal", Long.valueOf(consumers.size()));
        out.put("allKnown", Boolean.valueOf(unknownTotal == 0));
        out.put("unknownTotal", Long.valueOf(unknownTotal));
        return out;
    }

    /**
     * T3 corte Parte 2: deriva el nombre de campo real que un @Accessor/@Invoker
     * expone -- primero por el valor explicito de la anotacion ({@code value=}),
     * si no por convencion get/set/is sobre el nombre del metodo (misma
     * convencion que usa Mixin AccessorInfo para resolver el target real).
     */
    private static String accessorFieldName(MemberInfo m) {
        for (AnnotationInfo a : m.annotations) {
            if (!a.desc.endsWith("Accessor;") && !a.desc.endsWith("Invoker;")) continue;
            List<String> v = a.values.get("value");
            if (v != null && !v.isEmpty() && !v.get(0).isEmpty()) return v.get(0);
        }
        String n = m.name;
        if (n.startsWith("get") && n.length() > 3) return Character.toLowerCase(n.charAt(3)) + n.substring(4);
        if (n.startsWith("set") && n.length() > 3) return Character.toLowerCase(n.charAt(3)) + n.substring(4);
        if (n.startsWith("is") && n.length() > 2) return Character.toLowerCase(n.charAt(2)) + n.substring(3);
        return n;
    }

    private static boolean hasDestructiveOperation(MixinInfo info) {
        for (MemberInfo m : info.methods) {
            String op = methodOperation(m, null);
            if ("method_transform".equals(op) || "method_overwrite".equals(op)
                    || "method_collision_or_override".equals(op)) return true;
        }
        for (MemberInfo f : info.fields) {
            String op = fieldOperation(f, null);
            if ("field_collision_or_merge".equals(op) || "field_shadow_mutable".equals(op)) return true;
        }
        return false;
    }

    private static ChangeSet buildChanges(MixinInfo info, ClassSummary base) {
        ChangeSet out = new ChangeSet();
        for (MemberInfo f : info.fields) {
            addChange(out, f, fieldOperation(f, base), base);
        }
        for (MemberInfo m : info.methods) {
            if ("<init>".equals(m.name) || "<clinit>".equals(m.name)) continue;
            addChange(out, m, methodOperation(m, base), base);
        }
        return out;
    }

    private static void addChange(ChangeSet out, MemberInfo member, String op, ClassSummary base) {
        out.total++;
        if (out.samples.size() >= CAP) return;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("kind", member.kind);
        m.put("operation", op);
        m.put("name", member.name);
        m.put("desc", member.desc);
        m.put("access", Long.valueOf(member.access));
        m.put("baseMemberPresent", Boolean.valueOf(base != null && base.has(member)));
        m.put("annotations", annotationNames(member.annotations));
        Map<String, Object> selectors = selectors(member);
        if (!selectors.isEmpty()) m.put("selectors", selectors);
        Map<String, Object> values = operationAnnotationValues(member);
        if (!values.isEmpty()) m.put("annotationValues", values);
        out.samples.add(m);
    }

    private static Map<String, Long> operationCounts(MixinInfo info, ClassSummary base) {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        for (MemberInfo f : info.fields) inc(out, fieldOperation(f, base));
        for (MemberInfo m : info.methods) {
            if ("<init>".equals(m.name) || "<clinit>".equals(m.name)) continue;
            inc(out, methodOperation(m, base));
        }
        return out;
    }

    private static void inc(Map<String, Long> m, String key) {
        Long v = m.get(key);
        m.put(key, Long.valueOf(v == null ? 1 : v.longValue() + 1));
    }

    /**
     * T3 corte V: @Shadow+@Mutable es el unico caso confirmado por lectura del
     * codigo fuente de Mixin (MixinTargetContext.mergeShadowFields, que hace
     * target.access &= ~ACC_FINAL) donde un campo YA EXISTENTE en el target
     * cambia de forma sin ser un miembro nuevo -- pierde su modificador final.
     * Se distingue de field_shadow_reference (sin @Mutable, pura referencia sin
     * cambio de forma) porque JVMTI redefineClasses tambien rechaza cambios de
     * modificadores de campo/metodo, no solo altas/bajas de miembros.
     */
    private static String fieldOperation(MemberInfo f, ClassSummary base) {
        if (hasAnnotation(f, "org.spongepowered.asm.mixin.Shadow")
                && hasAnnotation(f, "org.spongepowered.asm.mixin.Mutable")) return "field_shadow_mutable";
        if (hasAnnotation(f, "org.spongepowered.asm.mixin.Shadow")) return "field_shadow_reference";
        if (hasAnnotation(f, "org.spongepowered.asm.mixin.Unique")) return "field_add_unique";
        if (base != null && base.has(f)) return "field_collision_or_merge";
        return "field_add_declared";
    }

    private static String methodOperation(MemberInfo m, ClassSummary base) {
        if (hasAnyAnnotation(m, INJECTION_ANNOTATIONS)) return "method_transform";
        if (hasAnnotation(m, "org.spongepowered.asm.mixin.Overwrite")) return "method_overwrite";
        if (hasAnnotation(m, "org.spongepowered.asm.mixin.gen.Accessor")
                || hasAnnotation(m, "org.spongepowered.asm.mixin.gen.Invoker")) return "method_accessor_bridge";
        if (hasAnnotation(m, "org.spongepowered.asm.mixin.Shadow")) return "method_shadow_reference";
        if (hasAnnotation(m, "org.spongepowered.asm.mixin.Unique")) return "method_add_unique";
        if (base != null && base.has(m)) return "method_collision_or_override";
        return "method_add_declared";
    }

    private static final String[] INJECTION_ANNOTATIONS = new String[] {
            "org.spongepowered.asm.mixin.injection.Inject",
            "org.spongepowered.asm.mixin.injection.Redirect",
            "org.spongepowered.asm.mixin.injection.ModifyArg",
            "org.spongepowered.asm.mixin.injection.ModifyArgs",
            "org.spongepowered.asm.mixin.injection.ModifyVariable",
            "org.spongepowered.asm.mixin.injection.ModifyConstant"
    };

    private static boolean hasAnyAnnotation(MemberInfo m, String[] names) {
        for (String n : names) if (hasAnnotation(m, n)) return true;
        return false;
    }

    private static boolean hasAnnotation(MemberInfo m, String dottedName) {
        String desc = "L" + dottedName.replace('.', '/') + ";";
        for (AnnotationInfo a : m.annotations) {
            if (desc.equals(a.desc)) return true;
        }
        return false;
    }

    private static List<Object> annotationNames(List<AnnotationInfo> anns) {
        List<Object> out = new ArrayList<Object>();
        for (AnnotationInfo a : anns) {
            if (out.size() >= CAP) break;
            out.add(dottedAnnotation(a.desc));
        }
        return out;
    }

    private static Map<String, Object> selectors(MemberInfo member) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (AnnotationInfo a : member.annotations) {
            if (!isOperationAnnotation(a)) continue;
            putValues(out, "method", a.values.get("method"));
            putValues(out, "target", a.values.get("target"));
            putValues(out, "at", a.values.get("at"));
            putValues(out, "slice", a.values.get("slice"));
        }
        return out;
    }

    private static Map<String, Object> operationAnnotationValues(MemberInfo member) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (AnnotationInfo a : member.annotations) {
            if (!isOperationAnnotation(a)) continue;
            out.put(dottedAnnotation(a.desc), valuesMap(a.values));
        }
        return out;
    }

    private static boolean isOperationAnnotation(AnnotationInfo a) {
        String dotted = dottedAnnotation(a.desc);
        if ("org.spongepowered.asm.mixin.Overwrite".equals(dotted)
                || "org.spongepowered.asm.mixin.Shadow".equals(dotted)
                || "org.spongepowered.asm.mixin.Unique".equals(dotted)
                || "org.spongepowered.asm.mixin.gen.Accessor".equals(dotted)
                || "org.spongepowered.asm.mixin.gen.Invoker".equals(dotted)) return true;
        for (String n : INJECTION_ANNOTATIONS) {
            if (n.equals(dotted)) return true;
        }
        return false;
    }

    private static void putValues(Map<String, Object> out, String key, List<String> values) {
        if (values == null || values.isEmpty()) return;
        out.put(key, new ArrayList<String>(values));
    }

    private static Map<String, Object> valuesMap(Map<String, List<String>> values) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, List<String>> e : values.entrySet()) {
            out.put(e.getKey(), new ArrayList<String>(e.getValue()));
        }
        return out;
    }

    private static List<Object> cappedStrings(Set<String> values) {
        List<Object> out = new ArrayList<Object>();
        for (String v : values) {
            if (out.size() >= CAP) break;
            out.add(v);
        }
        return out;
    }

    private static String dottedAnnotation(String desc) {
        if (desc == null) return "";
        String s = desc;
        if (s.startsWith("L") && s.endsWith(";")) s = s.substring(1, s.length() - 1);
        return s.replace('/', '.');
    }

    private static Map<String, Class<?>> loadedTargets(java.lang.instrument.Instrumentation inst, Set<String> targets) {
        Map<String, Class<?>> out = new LinkedHashMap<String, Class<?>>();
        if (inst == null || targets.isEmpty()) return out;
        Set<String> dotted = new LinkedHashSet<String>(targets);
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c != null && dotted.contains(c.getName())) out.put(c.getName(), c);
        }
        return out;
    }

    private static int captureBaseBytecode(Set<String> targets, Map<String, Class<?>> loaded,
                                           List<Object> samples, List<Object> missing) {
        int captured = 0;
        List<ClassLoader> targetLoaders = new ArrayList<ClassLoader>();
        for (Class<?> c : loaded.values()) {
            if (c == null) continue;
            ClassLoader cl = c.getClassLoader();
            if (!targetLoaders.contains(cl)) targetLoaders.add(cl);
        }
        for (String target : targets) {
            Capture cap = captureOne(target, loaded.get(target), targetLoaders);
            if (cap != null) {
                BASE_BYTES.putIfAbsent(target, cap.bytes);
                captured++;
                if (samples.size() < CAP) {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("class", target);
                    m.put("size", Long.valueOf(cap.bytes.length));
                    m.put("sha256", sha256(cap.bytes));
                    m.put("source", cap.source);
                    m.put("loaded", Boolean.valueOf(loaded.containsKey(target)));
                    samples.add(m);
                }
            } else if (missing.size() < CAP) {
                missing.add(target);
            }
        }
        return captured;
    }

    /**
     * Lee de Tier3LiveCapture (ya poblado pasivamente, sin bloquear aqui) los
     * bytes vividos por cada target en su primera definicion real. A diferencia
     * de captureBaseBytecode, no hay fallback de lectura -- si el target no
     * cargo todavia (o cargo antes de que Boot.computeTier3WatchSet lo agregara
     * al watch-set), simplemente no hay bytes vivos aun; se reporta honesto.
     */
    private static int captureLiveBytecode(Set<String> targets, List<Object> samples, List<Object> missing) {
        int captured = 0;
        for (String target : targets) {
            byte[] bytes = Tier3LiveCapture.get(target);
            if (bytes != null) {
                captured++;
                if (samples.size() < CAP) {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("class", target);
                    m.put("size", Long.valueOf(bytes.length));
                    m.put("sha256", sha256(bytes));
                    m.put("source", "live_first_load_capture");
                    samples.add(m);
                }
            } else if (missing.size() < CAP) {
                missing.add(target);
            }
        }
        return captured;
    }

    /**
     * T3 corte AC: fuente de RESPALDO para targets que nunca cargaron
     * organicamente en esta sesion. Invoca IMixinTransformer.transformClassBytes
     * sinteticamente sobre bytes base (mismo camino de codigo interno que Mixin
     * usa en la carga organica real -- confirmado byte-identico via sha256 en
     * corte AB). Nunca reemplaza bytes vivos organicos: solo se llama sobre
     * targetsMissingLive (los que ya fallaron captureLiveBytecode). Si ningun
     * target del ns cargo nunca, no hay anchor con acceso al classloader de
     * Mixin y el metodo devuelve 0 de forma honesta (no fuerza cobertura).
     */
    private static int captureSyntheticBytecode(Set<String> targetsMissingLive,
                                                Map<String, Class<?>> loaded,
                                                java.lang.instrument.Instrumentation inst,
                                                List<Object> samples, List<Object> missing) {
        if (targetsMissingLive.isEmpty()) return 0;
        Object transformer = null;
        java.lang.reflect.Method transformClassBytes = null;
        for (Class<?> anchor : loaded.values()) {
            if (anchor == null) continue;
            try {
                ClassLoader cl = anchor.getClassLoader();
                Class<?> menvClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", true, cl);
                Object currentEnv = menvClass.getMethod("getCurrentEnvironment").invoke(null);
                Object candidateTransformer = menvClass.getMethod("getActiveTransformer").invoke(currentEnv);
                if (candidateTransformer == null) continue;
                Class<?> ifaceClass = Class.forName(
                        "org.spongepowered.asm.mixin.transformer.IMixinTransformer", true, cl);
                java.lang.reflect.Method candidateMethod = ifaceClass.getMethod(
                        "transformClassBytes", String.class, String.class, byte[].class);
                transformer = candidateTransformer;
                transformClassBytes = candidateMethod;
                break;
            } catch (Throwable ignored) {
                // probar el siguiente anchor
            }
        }
        if (transformer == null || transformClassBytes == null) return 0;

        int captured = 0;
        for (String target : targetsMissingLive) {
            byte[] base = BASE_BYTES.get(target);
            if (base == null) {
                if (missing.size() < CAP) missing.add(target);
                continue;
            }
            try {
                byte[] synthetic = (byte[]) transformClassBytes.invoke(transformer, target, target, base);
                captured++;
                if (samples.size() < CAP) {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("class", target);
                    m.put("size", Long.valueOf(synthetic.length));
                    m.put("sha256", sha256(synthetic));
                    m.put("source", "synthetic_offline_transform_v1");
                    samples.add(m);
                }
            } catch (Throwable t) {
                if (missing.size() < CAP) missing.add(target);
            }
        }
        return captured;
    }

    /**
     * T3 corte AD: diagnostico de solo lectura, estrictamente aditivo. Consulta
     * Mixins.getMixinsForClass(target) (API publica de Mixin, firma confirmada
     * via javap contra sponge-mixin-0.17.3+mixin.0.8.7.jar) para saber que
     * mixins CREE Mixin que aplican a un target en el momento de la llamada, y
     * lo compara contra los contributors ya declarados estaticamente (mismo
     * dato que ya usa buildRuntimeOrder/buildRuntimeAttribution). No escribe en
     * Ledger, no toca runtimeOrder/allTargetsOrderVerified/buildTxGate -- solo
     * aisla si una secuencia de orden incompleta se debe a que Mixin mismo no
     * tiene el mixin registrado en ese momento, o a que si lo tiene registrado
     * pero el hook/captura posterior (corte K/AC) no lo ve.
     */
    private static Map<String, Object> diagnoseMixinRegistration(String target,
                                                                  List<MixinContributor> contributors,
                                                                  Map<String, Class<?>> loaded) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("target", target);
        List<String> declared = new ArrayList<String>();
        for (MixinContributor c : contributors) declared.add(c.mixin);
        out.put("declaredContributors", new ArrayList<Object>(declared));

        Object registeredSet = null;
        for (Class<?> anchor : loaded.values()) {
            if (anchor == null) continue;
            try {
                ClassLoader cl = anchor.getClassLoader();
                Class<?> mixinsClass = Class.forName("org.spongepowered.asm.mixin.Mixins", true, cl);
                java.lang.reflect.Method getMixinsForClass =
                        mixinsClass.getMethod("getMixinsForClass", String.class);
                registeredSet = getMixinsForClass.invoke(null, target);
                break;
            } catch (Throwable ignored) {
                // probar el siguiente anchor
            }
        }
        if (registeredSet == null) {
            out.put("queryAvailable", Boolean.FALSE);
            out.put("registeredMixinsAtSyntheticCall", Collections.emptyList());
            out.put("missingFromRegistered", new ArrayList<Object>(declared));
            return out;
        }

        List<Object> registered = new ArrayList<Object>();
        Set<String> registeredClassNames = new LinkedHashSet<String>();
        for (Object mixinInfo : (Iterable<?>) registeredSet) {
            try {
                String className = String.valueOf(
                        mixinInfo.getClass().getMethod("getClassName").invoke(mixinInfo));
                Object config = mixinInfo.getClass().getMethod("getConfig").invoke(mixinInfo);
                String configName = config == null ? null
                        : String.valueOf(config.getClass().getMethod("getName").invoke(config));
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("mixinClass", className);
                m.put("config", configName);
                registered.add(m);
                registeredClassNames.add(className);
            } catch (Throwable ignored) {
                // entrada no legible; se omite, no se fuerza
            }
        }
        out.put("queryAvailable", Boolean.TRUE);
        out.put("registeredMixinsAtSyntheticCall", registered);
        List<Object> missing = new ArrayList<Object>();
        for (String d : declared) if (!registeredClassNames.contains(d)) missing.add(d);
        out.put("missingFromRegistered", missing);

        // T3 corte AD (extension): getMixinsForClass quedo refutado (vacio en
        // los 3 casos, incluidos los que si funcionan) -- via alterna elegida
        // por el usuario: leer el estado DECLARADO y ESTABLE de cada config
        // (no el estado transitorio de seleccion) via Mixins.getConfigs() ->
        // Config.getConfig() (IMixinConfig) -> getTargets(). Reflexion
        // resuelta contra las clases publicas (Config, IMixinConfig) via
        // Class.forName, no contra instance.getClass() de MixinInfo (clase
        // concreta no-publica), para evitar IllegalAccessException bajo el
        // sistema de modulos de Java.
        Set<String> declaredConfigs = new LinkedHashSet<String>();
        for (MixinContributor c : contributors) declaredConfigs.addAll(c.configs);
        out.put("declaredConfigsSearched", new ArrayList<Object>(declaredConfigs));
        List<Object> configTargetsCheck = new ArrayList<Object>();
        List<Object> allConfigsObserved = new ArrayList<Object>();
        String configsQueryError = null;
        boolean configsQuerySucceeded = false;
        for (Class<?> anchor : loaded.values()) {
            if (anchor == null) continue;
            try {
                ClassLoader cl = anchor.getClassLoader();
                Class<?> mixinsClass = Class.forName("org.spongepowered.asm.mixin.Mixins", true, cl);
                Class<?> configClass =
                        Class.forName("org.spongepowered.asm.mixin.transformer.Config", true, cl);
                Class<?> iMixinConfigClass =
                        Class.forName("org.spongepowered.asm.mixin.extensibility.IMixinConfig", true, cl);
                java.lang.reflect.Method getConfigs = mixinsClass.getMethod("getConfigs");
                java.lang.reflect.Method getName = configClass.getMethod("getName");
                java.lang.reflect.Method getConfig = configClass.getMethod("getConfig");
                java.lang.reflect.Method getTargets = iMixinConfigClass.getMethod("getTargets");
                Object configsSet = getConfigs.invoke(null);
                for (Object configWrapper : (Iterable<?>) configsSet) {
                    String configName = String.valueOf(getName.invoke(configWrapper));
                    if (allConfigsObserved.size() < CAP) allConfigsObserved.add(configName);
                    if (!declaredConfigs.contains(configName)) continue;
                    Object iMixinConfig = getConfig.invoke(configWrapper);
                    Object targetsObj = iMixinConfig == null ? null : getTargets.invoke(iMixinConfig);
                    Set<String> targets = new LinkedHashSet<String>();
                    if (targetsObj instanceof Iterable) {
                        for (Object t : (Iterable<?>) targetsObj) targets.add(String.valueOf(t));
                    }
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("config", configName);
                    m.put("declaredTargetsCount", Integer.valueOf(targets.size()));
                    m.put("containsThisTarget", Boolean.valueOf(targets.contains(target)));
                    configTargetsCheck.add(m);
                }
                configsQuerySucceeded = true;
                break;
            } catch (Throwable t) {
                configsQueryError = t.getClass().getName() + ": " + t.getMessage();
                // probar el siguiente anchor
            }
        }
        out.put("configsQuerySucceeded", Boolean.valueOf(configsQuerySucceeded));
        out.put("configsQueryError", configsQueryError);
        out.put("allConfigsObservedTotal", Integer.valueOf(allConfigsObserved.size()));
        out.put("allConfigsObserved", allConfigsObserved);
        out.put("configTargetsCheck", configTargetsCheck);
        return out;
    }

    private static Capture captureOne(String className, Class<?> loadedClass,
                                      List<ClassLoader> fallbackLoaders) {
        String resource = className.replace('.', '/') + ".class";
        for (ClassLoader cl : candidateLoaders(loadedClass, fallbackLoaders)) {
            Capture c = readResource(cl, resource);
            if (c != null) return c;
        }
        return null;
    }

    private static List<ClassLoader> candidateLoaders(Class<?> loadedClass,
                                                      List<ClassLoader> fallbackLoaders) {
        List<ClassLoader> out = new ArrayList<ClassLoader>();
        addLoader(out, loadedClass == null ? null : loadedClass.getClassLoader());
        if (fallbackLoaders != null) {
            for (ClassLoader cl : fallbackLoaders) addLoader(out, cl);
        }
        addLoader(out, Thread.currentThread().getContextClassLoader());
        addLoader(out, Tier3MixinAudit.class.getClassLoader());
        addLoader(out, ClassLoader.getSystemClassLoader());
        return out;
    }

    private static void addLoader(List<ClassLoader> out, ClassLoader cl) {
        if (!out.contains(cl)) out.add(cl);
    }

    private static Capture readResource(ClassLoader cl, String resource) {
        InputStream in = null;
        try {
            URL url = cl == null ? ClassLoader.getSystemResource(resource) : cl.getResource(resource);
            if (url == null) return null;
            in = url.openStream();
            byte[] bytes = readAll(in);
            if (bytes == null || bytes.length < 4) return null;
            if ((bytes[0] & 0xff) != 0xca || (bytes[1] & 0xff) != 0xfe
                    || (bytes[2] & 0xff) != 0xba || (bytes[3] & 0xff) != 0xbe) return null;
            return new Capture(bytes, url.toString());
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(32768);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static final class Capture {
        final byte[] bytes;
        final String source;
        Capture(byte[] bytes, String source) {
            this.bytes = bytes;
            this.source = source;
        }
    }

    private static Map<String, Object> reason(String code, String detail) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("code", code);
        m.put("detail", detail);
        return m;
    }

    private static String relativize(Path root, Path child) {
        try {
            return root.relativize(child).toString().replace('\\', '/');
        } catch (Throwable t) {
        return child.toString().replace('\\', '/');
        }
    }

    private static final class DemixPlan {
        Map<String, Object> map = Collections.emptyMap();
        long targetsTotal;
        long sharedTargetsTotal;
    }

    private static final class MixinContributor {
        final String ownerNs;
        final String mixin;
        final List<String> configs;
        final MixinInfo info;
        final long ordinal;
        // T3 corte AE: plugin FQCN (IMixinConfigPlugin) declarado por la config
        // que aporta este mixin, si existe -- null si la config no declara
        // "plugin". Dato estatico puro (leido del .mixins.json), no simula
        // shouldApplyMixin() del plugin real: solo marca que existe un
        // mecanismo condicional de terceros que PODRIA excluir este mixin en
        // runtime por razones ajenas a MKSA (ver corte AD, fabric-renderer-indigo).
        final String configPlugin;

        MixinContributor(String ownerNs, String mixin, List<String> configs,
                         MixinInfo info, long ordinal, String configPlugin) {
            this.ownerNs = ownerNs;
            this.mixin = mixin;
            this.configs = configs == null ? Collections.<String>emptyList() : configs;
            this.info = info;
            this.ordinal = ordinal;
            this.configPlugin = configPlugin;
        }
    }

    private static final class MixinInfo {
        final Set<String> targets;
        final List<MemberInfo> fields;
        final List<MemberInfo> methods;
        // T3 corte M (universo estructural): la clase mixin en si es una interface
        // (ACC_INTERFACE, 0x0200) -- caso Accessor/Invoker declarado como
        // "interface FooAccessor", que Mixin agrega al implements del target
        // (cambio de jerarquia, no de cuerpo).
        final boolean isInterface;
        // T3 corte Q (universo estructural, extension): interfaces declaradas
        // directamente en la tabla "interfaces" del classfile del mixin -- para un
        // mixin-clase normal ("class FooMixin implements AlgunaInterface") estas
        // son las interfaces que Mixin mergea directamente al implements del
        // target. Mismo efecto estructural que accessor_invoker_interfaces
        // (cambio de jerarquia), mecanismo distinto -- descubierto en corte P via
        // redefineClasses() real ("attempted to change superclass or interfaces").
        final List<String> declaredInterfaces;
        // T3 corte tier3 end-to-end §1: interfaces declaradas via
        // @Implements(@Interface(iface=X.class, prefix="...")) -- tercera via de
        // inyeccion de interfaz (junto a isInterface/declaredInterfaces), la que
        // corte AN identifico como caso real (HeadRenderable sobre class_303).
        final List<String> implementsInterfaces;

        MixinInfo(Set<String> targets, List<MemberInfo> fields, List<MemberInfo> methods,
                  boolean isInterface, List<String> declaredInterfaces, List<String> implementsInterfaces) {
            this.targets = targets;
            this.fields = fields;
            this.methods = methods;
            this.isInterface = isInterface;
            this.declaredInterfaces = declaredInterfaces == null
                    ? Collections.<String>emptyList() : declaredInterfaces;
            this.implementsInterfaces = implementsInterfaces == null
                    ? Collections.<String>emptyList() : implementsInterfaces;
        }
    }

    private static final class ClassSummary {
        final String name;
        final Set<String> fields;
        final Set<String> methods;

        ClassSummary(String name, Set<String> fields, Set<String> methods) {
            this.name = name;
            this.fields = fields;
            this.methods = methods;
        }

        boolean has(MemberInfo m) {
            String key = m.name + m.desc;
            return "field".equals(m.kind) ? fields.contains(key) : methods.contains(key);
        }
    }

    private static final class MemberInfo {
        final String kind;
        final int access;
        final String name;
        final String desc;
        final List<AnnotationInfo> annotations;
        // T3 corte Parte 2: bytes crudos del atributo Code (metodo unicamente); null
        // si no se pidio captura (captureCode=false) o el miembro es un campo.
        final byte[] code;

        MemberInfo(String kind, int access, String name, String desc, List<AnnotationInfo> annotations) {
            this(kind, access, name, desc, annotations, null);
        }

        MemberInfo(String kind, int access, String name, String desc, List<AnnotationInfo> annotations,
                   byte[] code) {
            this.kind = kind;
            this.access = access;
            this.name = name;
            this.desc = desc;
            this.annotations = annotations;
            this.code = code;
        }
    }

    private static final class AnnotationInfo {
        final String desc;
        final Map<String, List<String>> values = new LinkedHashMap<String, List<String>>();
        // T3 corte tier3 end-to-end §1: anotaciones anidadas (tag '@' de un
        // element_value), preservadas ademas de su forma stringificada en
        // "values" -- necesarias para leer @Implements(@Interface(iface=X.class))
        // sin perder la estructura (annotationSignature ya las aplana a String).
        final Map<String, List<AnnotationInfo>> nested = new LinkedHashMap<String, List<AnnotationInfo>>();

        AnnotationInfo(String desc) {
            this.desc = desc;
        }
    }

    /** T3 corte J: un miembro (campo/metodo) con evidencia @MixinMerged real. */
    private static final class MergedMember {
        final String kind;
        final String name;
        final String desc;
        final String mixin;
        final int priority;

        MergedMember(String kind, String name, String desc, String mixin, int priority) {
            this.kind = kind;
            this.name = name;
            this.desc = desc;
            this.mixin = mixin;
            this.priority = priority;
        }
    }

    private static final class ChangeSet {
        final List<Object> samples = new ArrayList<Object>();
        long total;
    }

    /** T3 corte Parte 2: campos referenciados por bytecode que sigue existiendo, mas riesgo. */
    private static final class FieldScanResult {
        final Set<String> fieldNames;
        final boolean risk;
        FieldScanResult(Set<String> fieldNames, boolean risk) {
            this.fieldNames = fieldNames;
            this.risk = risk;
        }
    }

    /** T3 corte Parte 2: resultado del escaneo de instrucciones de un solo metodo.
     * T3 corte tier3 end-to-end §1: agrega typeRefs -- nombres dotted resueltos de
     * CHECKCAST/INSTANCEOF/INVOKEINTERFACE encontrados en el metodo, sin filtrar
     * por relevancia (el llamador decide si algun typeRef coincide con una
     * interfaz inyectada por mixin que le interesa). */
    private static final class CodeScanResult {
        final Set<String> fieldNames;
        final Set<String> typeRefs;
        final boolean risk;
        /** T3 corte "root-cause diagnostico": una entrada por CADA sitio que puso
         * risk=true en este metodo (opcode + owner.name/motivo) -- nunca se usa para
         * decidir nada, solo para que buildExternalShapeConsumers pueda exponer,
         * junto a "unknown", la causa exacta y verificable sin tener que re-decompilar
         * la clase a mano cada vez. Vacio si risk=false. */
        final Set<String> riskReasons;
        CodeScanResult(Set<String> fieldNames, Set<String> typeRefs, boolean risk, Set<String> riskReasons) {
            this.fieldNames = fieldNames;
            this.typeRefs = typeRefs;
            this.risk = risk;
            this.riskReasons = riskReasons;
        }
    }

    /** T3 corte lambda-blindness: una entrada BootstrapMethods ya resuelta -- la
     * clase dueña del metodo de bootstrap (igual que antes) MAS los indices crudos
     * de constant pool de sus argumentos estaticos (antes se leian y se
     * descartaban). Para bootstraps de LambdaMetafactory, argIndices[0]/argIndices[2]
     * apuntan a samMethodType/instantiatedMethodType (CONSTANT_MethodType) -- ver
     * lambdaNarrowedParamTypes. */
    private static final class BootstrapEntry {
        final String owner;
        final int[] argIndices;
        BootstrapEntry(String owner, int[] argIndices) {
            this.owner = owner;
            this.argIndices = argIndices;
        }
    }

    /** T3 corte tier3 end-to-end §1: resultado agregado (todos los metodos de una
     * clase) del escaneo de referencias de forma -- campos @Unique referenciados
     * por nombre (mismo criterio que FieldScanResult) mas tipos referenciados por
     * CHECKCAST/INSTANCEOF/INVOKEINTERFACE. risk=true fail-closed ante reflexion/
     * lambda/invokedynamic no resuelto, igual que FieldScanResult. */
    private static final class ShapeRefScanResult {
        final Set<String> fieldNames;
        final Set<String> typeRefs;
        final boolean risk;
        /** T3 corte "root-cause diagnostico": union de CodeScanResult.riskReasons de
         * todos los metodos de la clase, mas motivos a nivel-clase (bytes ilegibles,
         * excepcion de parseo) cuando risk=true se origina fuera de scanCode -- ver
         * UNREADABLE_SHAPE_REF y los catch de scanExternalLoadedClasses. */
        final Set<String> riskReasons;
        ShapeRefScanResult(Set<String> fieldNames, Set<String> typeRefs, boolean risk, Set<String> riskReasons) {
            this.fieldNames = fieldNames;
            this.typeRefs = typeRefs;
            this.risk = risk;
            this.riskReasons = riskReasons;
        }
    }

    /**
     * T3 corte Parte 2: ancho de operando (bytes tras el opcode) por cada opcode JVM
     * de ancho FIJO que no ya tiene manejo explicito en scanCode (170/171/196 son
     * variables; 178-186 se leen ahi mismo con prioridad sobre esta tabla). -1 =
     * opcode no enumerado -- scanCode hace throw en vez de asumir ancho 0 (fail
     * closed: un solo ancho mal contado desincroniza el resto del metodo en
     * silencio). Poblada exhaustivamente contra la especificacion JVM (JVMS SS6.5).
     */
    private static final int[] FIXED_OPERAND_BYTES = buildFixedOperandBytes();

    private static int[] buildFixedOperandBytes() {
        int[] w = new int[256];
        java.util.Arrays.fill(w, -1);
        int[] zero = {
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41,
                42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53,
                59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74,
                75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86,
                87, 88, 89, 90, 91, 92, 93, 94, 95,
                96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111,
                112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127,
                128, 129, 130, 131,
                133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147,
                148, 149, 150, 151, 152,
                172, 173, 174, 175, 176, 177,
                190, 191, 194, 195
        };
        for (int op : zero) w[op] = 0;
        int[] one = { 16, 18, 21, 22, 23, 24, 25, 54, 55, 56, 57, 58, 169, 188 };
        for (int op : one) w[op] = 1;
        int[] two = {
                17, 19, 20, 132,
                153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166,
                167, 168, 187, 189, 192, 193, 198, 199
        };
        for (int op : two) w[op] = 2;
        w[197] = 3; // multianewarray
        w[200] = 4; w[201] = 4; // goto_w, jsr_w
        return w;
    }

    /** T3 corte Parte 2: denylist conservador -- reflexion/MethodHandles/Unsafe no delatan el campo real. */
    private static boolean isReflectionRisk(String[] ref) {
        if (ref == null) return true;
        String owner = ref[0];
        String name = ref[1];
        if ("java.lang.Class".equals(owner)
                && (name.startsWith("getDeclaredField") || name.startsWith("getField"))) return true;
        if ("java.lang.invoke.MethodHandles$Lookup".equals(owner)
                && (name.startsWith("find") || name.startsWith("unreflect"))) return true;
        if ("sun.misc.Unsafe".equals(owner) || "jdk.internal.misc.Unsafe".equals(owner)) return true;
        return false;
    }

    private static final class ClassFile {
        private final DataInputStream in;
        private Object[] cp;
        private int[] tags;

        ClassFile(byte[] bytes) {
            this.in = new DataInputStream(new ByteArrayInputStream(bytes));
        }

        MixinInfo mixinInfo() throws IOException {
            Header h = readHeader();
            List<MemberInfo> fields = readMembers("field", true, null);
            List<MemberInfo> methods = readMembers("method", true, null);
            Set<String> targets = new LinkedHashSet<String>();
            List<AnnotationInfo> anns = readClassAnnotations();
            List<String> implementsInterfaces = new ArrayList<String>();
            for (AnnotationInfo a : anns) {
                if (MIXIN_DESC.equals(a.desc)) {
                    addTargets(a.values.get("value"), targets);
                    addTargets(a.values.get("targets"), targets);
                } else if (MIXIN_IMPLEMENTS_DESC.equals(a.desc)) {
                    // T3 corte tier3 end-to-end §1: @Implements(value={@Interface(...)}) --
                    // cada @Interface anidada trae iface=X.class en su propio elemento
                    // "iface" (nested.get("value") -> lista de AnnotationInfo @Interface).
                    List<AnnotationInfo> ifaceAnns = a.nested.get("value");
                    if (ifaceAnns != null) {
                        for (AnnotationInfo ifaceAnn : ifaceAnns) {
                            List<String> ifaceValues = ifaceAnn.values.get("iface");
                            if (ifaceValues != null && !ifaceValues.isEmpty()) {
                                implementsInterfaces.add(ifaceValues.get(0));
                            }
                        }
                    }
                }
            }
            boolean isInterface = (h.accessFlags & 0x0200) != 0;
            return new MixinInfo(targets, fields, methods, isInterface, h.interfaceNames, implementsInterfaces);
        }

        ClassSummary classSummary() throws IOException {
            Header h = readHeader();
            Set<String> fields = new LinkedHashSet<String>();
            Set<String> methods = new LinkedHashSet<String>();
            readMembers("field", false, fields);
            readMembers("method", false, methods);
            skipAttributes();
            return new ClassSummary(h.name, fields, methods);
        }

        /**
         * T3 corte J: miembros con @MixinMerged real -- solo cubre lo que Mixin
         * mergea (overwrites, miembros nuevos, handlers de injector copiados), no
         * call-sites tejidos dentro de un metodo vanilla ya existente.
         */
        List<MergedMember> mixinMergedMembers() throws IOException {
            Header h = readHeader();
            List<MemberInfo> fields = readMembers("field", true, null);
            List<MemberInfo> methods = readMembers("method", true, null);
            List<MergedMember> out = new ArrayList<MergedMember>();
            collectMergedMembers(fields, out);
            collectMergedMembers(methods, out);
            return out;
        }

        private void collectMergedMembers(List<MemberInfo> members, List<MergedMember> out) {
            for (MemberInfo m : members) {
                for (AnnotationInfo a : m.annotations) {
                    if (!MIXIN_MERGED_DESC.equals(a.desc)) continue;
                    List<String> mixinValues = a.values.get("mixin");
                    String mixin = mixinValues == null || mixinValues.isEmpty() ? null : mixinValues.get(0);
                    if (mixin == null) continue;
                    List<String> priorityValues = a.values.get("priority");
                    int priority = -1;
                    if (priorityValues != null && !priorityValues.isEmpty()) {
                        try {
                            priority = Integer.parseInt(priorityValues.get(0));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    out.add(new MergedMember(m.kind, m.name, m.desc, mixin, priority));
                }
            }
        }

        /**
         * T3 corte Parte 2: escanea los cuerpos de metodo de esta clase en busca de
         * referencias (GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC) a un campo de
         * {@code ownerDotted}, mas riesgo de reflexion/lambda que el bytecode no
         * puede resolver con certeza. Usado por buildFieldSafety para decidir si un
         * campo @Unique sobrevive referenciado por bytecode que persiste tras
         * apagar el mod (vanilla base o un co-owner).
         */
        FieldScanResult scanFieldReferences(String ownerDotted) throws IOException {
            Header h = readHeader();
            readMembers("field", false, null);
            List<MemberInfo> methods = readMembers("method", false, null, true);
            // T3 corte Parte 2 (fix post-verificacion, bug B): BootstrapMethods es un
            // atributo de clase que en el formato .class viene DESPUES de methods --
            // hay que leerlo aca (recien ahora esta en la posicion correcta del stream)
            // para poder resolver, mas abajo, a que bootstrap apunta cada invokedynamic
            // encontrado en los Code ya capturados.
            Map<Integer, BootstrapEntry> bootstrapOwners = readClassBootstrapMethods();
            Set<String> refFields = new LinkedHashSet<String>();
            boolean risk = false;
            for (MemberInfo m : methods) {
                if (m.code == null) continue;
                // T3 corte Parte 2 (fix post-verificacion, bug C): un mixin que
                // declara un campo @Unique referencia ESE campo, dentro de su propio
                // .class pre-weave, con owner = el propio mixin (h.name) -- asi
                // compila javac cualquier "this.campo" dentro de la clase que lo
                // declara, porque recien Mixin re-apunta esas referencias a
                // ownerDotted (el target) al tejer. Sin esto, un campo @Unique
                // activamente usado por su propio mixin (ej. rendererPools en
                // fabric-rendering-v1's GuiRendererMixin) nunca se veia referenciado
                // -- se contaban solo referencias con owner==target, que solo
                // aparecen en clases YA tejidas (vanilla base con el mixin aplicado),
                // nunca en el .class crudo del mixin mismo.
                CodeScanResult r = scanCode(m.code, ownerDotted, h.name, bootstrapOwners);
                refFields.addAll(r.fieldNames);
                if (r.risk) risk = true;
            }
            return new FieldScanResult(refFields, risk);
        }

        /**
         * T3 corte tier3 end-to-end §1: variante de scanFieldReferences que ademas
         * acumula tipos referenciados por CHECKCAST/INSTANCEOF/INVOKEINTERFACE
         * (typeRefs) en toda la clase -- usado por el escaner de consumidores
         * externos de forma para detectar clases fuera del grupo de redefine que
         * dependen de una interfaz inyectada por @Implements en ownerDotted.
         */
        ShapeRefScanResult scanShapeReferences(String ownerDotted) throws IOException {
            Header h = readHeader();
            readMembers("field", false, null);
            List<MemberInfo> methods = readMembers("method", false, null, true);
            Map<Integer, BootstrapEntry> bootstrapOwners = readClassBootstrapMethods();
            Set<String> refFields = new LinkedHashSet<String>();
            Set<String> refTypes = new LinkedHashSet<String>();
            Set<String> riskReasons = new LinkedHashSet<String>();
            boolean risk = false;
            for (MemberInfo m : methods) {
                if (m.code == null) continue;
                CodeScanResult r = scanCode(m.code, ownerDotted, h.name, bootstrapOwners);
                refFields.addAll(r.fieldNames);
                refTypes.addAll(r.typeRefs);
                if (r.risk) {
                    risk = true;
                    for (String reason : r.riskReasons) riskReasons.add(m.name + m.desc + ":" + reason);
                }
            }
            return new ShapeRefScanResult(refFields, refTypes, risk, riskReasons);
        }

        /**
         * T3 corte "narrowing configPlugin": determina, por LECTURA de bytecode
         * unicamente -- nunca ejecutandolo -- si el metodo {@code name}{@code desc}
         * de esta clase es un predicado puro de comparacion de String: solo carga
         * sus parametros/constantes String, invoca java.lang.String.equals(IgnoreCase)/
         * contains/startsWith/endsWith/isEmpty/trim/toLowerCase/toUpperCase, ramifica
         * sobre ese resultado, y retorna. Whitelist de opcodes deliberadamente angosta
         * (basada en la misma FIXED_OPERAND_BYTES ya usada por scanCode) -- CUALQUIER
         * opcode fuera de ella (GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC, INVOKESTATIC/
         * SPECIAL/INTERFACE/DYNAMIC, NEW, ATHROW, arrays, MONITORENTER/EXIT,
         * tableswitch/lookupswitch/wide) hace fallar la clasificacion (false,
         * fail-closed), igual que el metodo no encontrado o encontrado mas de una vez.
         * Usado exclusivamente para narrowear IMixinConfigPlugin.shouldApplyMixin
         * (ver allConfigPluginsProvablySimple) -- nunca se instancia ni se invoca el
         * plugin real, solo se lee su .class ya capturado (Tier3LiveCapture/
         * readLoadedClassBytes).
         */
        boolean methodIsPureStringPredicate(String name, String desc) throws IOException {
            readHeader();
            readMembers("field", false, null);
            List<MemberInfo> methods = readMembers("method", false, null, true);
            MemberInfo target = null;
            int matches = 0;
            for (MemberInfo m : methods) {
                if ("method".equals(m.kind) && name.equals(m.name) && desc.equals(m.desc)) {
                    target = m;
                    matches++;
                }
            }
            if (matches != 1 || target == null || target.code == null) return false;
            return scanPureStringPredicateCode(target.code);
        }

        private boolean scanPureStringPredicateCode(byte[] codeAttr) throws IOException {
            DataInputStream c = new DataInputStream(new ByteArrayInputStream(codeAttr));
            c.readUnsignedShort();
            c.readUnsignedShort();
            int codeLen = c.readInt();
            byte[] insns = new byte[codeLen];
            c.readFully(insns);
            int pc = 0;
            while (pc < insns.length) {
                int opcode = insns[pc] & 0xFF;
                pc++;
                switch (opcode) {
                    case 0: case 1: case 2: case 3: case 4: case 5: case 6: case 7: case 8:
                        // nop, aconst_null, iconst_m1..iconst_5 -- sin operando, sin efecto
                        break;
                    case 42: case 43: case 44: case 45:
                        // aload_0..aload_3 -- sin operando
                        break;
                    case 87:
                        // pop -- sin operando
                        break;
                    case 172: case 176:
                        // ireturn, areturn -- sin operando
                        break;
                    case 25:
                        // aload -- 1 byte de indice local
                        pc += 1;
                        break;
                    case 18:
                        // ldc -- 1 byte de indice de constant pool
                        pc += 1;
                        break;
                    case 19:
                        // ldc_w -- 2 bytes de indice
                        pc += 2;
                        break;
                    case 153: case 154: case 165: case 166: case 167: case 198: case 199:
                        // ifeq, ifne, if_acmpeq, if_acmpne, goto, ifnull, ifnonnull -- 2 bytes de offset
                        pc += 2;
                        break;
                    case 182: { // invokevirtual -- solo java.lang.String, metodos de comparacion puros
                        int idx = readU2(insns, pc);
                        pc += 2;
                        String[] ref = refAt(idx);
                        if (ref == null || !"java.lang.String".equals(ref[0])) return false;
                        String mn = ref[1];
                        if (!("equals".equals(mn) || "equalsIgnoreCase".equals(mn)
                                || "contains".equals(mn) || "startsWith".equals(mn)
                                || "endsWith".equals(mn) || "isEmpty".equals(mn)
                                || "trim".equals(mn) || "toLowerCase".equals(mn)
                                || "toUpperCase".equals(mn))) return false;
                        break;
                    }
                    default:
                        // fail-closed: cualquier otro opcode (acceso a campo, invocacion no
                        // whitelisteada, control de flujo complejo, etc.) descarta la
                        // clasificacion de "predicado puro" sin excepcion.
                        return false;
                }
            }
            return true;
        }

        /**
         * Camina las instrucciones de un atributo Code crudo (max_stack(2) +
         * max_locals(2) + code_length(4) + instrucciones) buscando GETFIELD/PUTFIELD/
         * GETSTATIC/PUTSTATIC sobre {@code ownerDotted}, CHECKCAST/INSTANCEOF/
         * INVOKEINTERFACE (tipos referenciados, para el escaner de consumidores
         * externos de forma) y llamadas que puedan resolver el campo solo en
         * runtime (reflexion, MethodHandles, Unsafe, invokedynamic). Fail-closed:
         * cualquier opcode fuera de FIXED_OPERAND_BYTES lanza en vez de asumir
         * ancho 0 -- un solo instruccion mal contada desincroniza todo lo que sigue
         * en silencio.
         */
        private CodeScanResult scanCode(byte[] codeAttr, String ownerDotted, String declaringClassDotted,
                                        Map<Integer, BootstrapEntry> bootstrapOwners) throws IOException {
            DataInputStream c = new DataInputStream(new ByteArrayInputStream(codeAttr));
            c.readUnsignedShort();
            c.readUnsignedShort();
            int codeLen = c.readInt();
            byte[] insns = new byte[codeLen];
            c.readFully(insns);
            Set<String> fields = new LinkedHashSet<String>();
            Set<String> typeRefs = new LinkedHashSet<String>();
            Set<String> riskReasons = new LinkedHashSet<String>();
            boolean risk = false;
            int pc = 0;
            while (pc < insns.length) {
                int opcode = insns[pc] & 0xFF;
                int opStart = pc;
                pc++;
                switch (opcode) {
                    case 170: { // tableswitch
                        while (pc % 4 != 0) pc++;
                        pc += 4; // default
                        int low = readInt(insns, pc); pc += 4;
                        int high = readInt(insns, pc); pc += 4;
                        pc += 4L * (high - low + 1);
                        break;
                    }
                    case 171: { // lookupswitch
                        while (pc % 4 != 0) pc++;
                        pc += 4; // default
                        int npairs = readInt(insns, pc); pc += 4;
                        pc += 8L * npairs;
                        break;
                    }
                    case 196: { // wide
                        int widened = insns[pc] & 0xFF; pc++;
                        pc += (widened == 132 /* iinc */) ? 4 : 2;
                        break;
                    }
                    case 178: case 179: case 180: case 181: { // getstatic/putstatic/getfield/putfield
                        int idx = readU2(insns, pc); pc += 2;
                        String[] ref = refAt(idx);
                        if (ref == null) { risk = true; riskReasons.add("unresolved_fieldref_cp" + idx); break; }
                        // T3 corte Parte 2 (fix post-verificacion, bug C): ademas del
                        // owner==target (clase ya tejida, vanilla base con el mixin
                        // aplicado), contar tambien owner==declaringClassDotted (el
                        // .class crudo del propio mixin, pre-weave) -- ver comentario
                        // en scanFieldReferences.
                        if (ownerDotted.equals(ref[0]) || (declaringClassDotted != null
                                && declaringClassDotted.equals(ref[0]))) {
                            fields.add(ref[1]);
                        }
                        break;
                    }
                    case 182: case 183: case 184: { // invokevirtual/special/static
                        int idx = readU2(insns, pc); pc += 2;
                        String[] ref = refAt(idx);
                        if (isReflectionRisk(ref)) {
                            risk = true;
                            riskReasons.add("reflection_risk:" + (ref == null ? "unresolved_cp" + idx
                                    : ref[0] + "." + ref[1]));
                        }
                        break;
                    }
                    case 185: { // invokeinterface -- 4 bytes: index(2) + count(1) + 0(1)
                        int idx = readU2(insns, pc); pc += 4;
                        String[] ref = refAt(idx);
                        if (isReflectionRisk(ref)) {
                            risk = true;
                            riskReasons.add("reflection_risk:" + (ref == null ? "unresolved_cp" + idx
                                    : ref[0] + "." + ref[1]));
                        }
                        // T3 corte tier3 end-to-end §1: el owner de un
                        // invokeinterface es la interfaz llamada -- si es una
                        // interfaz @Implements inyectada por mixin, esto es una
                        // dependencia real de forma desde fuera del grupo.
                        if (ref != null && ref[0] != null && !ref[0].isEmpty()) typeRefs.add(ref[0]);
                        break;
                    }
                    case 192: case 193: { // checkcast / instanceof -- operando: indice CONSTANT_Class
                        int idx = readU2(insns, pc); pc += 2;
                        String t = className(idx).replace('/', '.');
                        if (!t.isEmpty()) typeRefs.add(t);
                        break;
                    }
                    case 186: { // invokedynamic -- lambdas/method refs: bytecode no delata el target real
                        int idx = readU2(insns, pc); pc += 4;
                        // T3 corte Parte 2 (fix post-verificacion, bug B): no todo
                        // invokedynamic es una lambda/method-ref real -- desde Java 9
                        // la concatenacion comun de strings ("a" + b) tambien compila a
                        // invokedynamic, via java.lang.invoke.StringConcatFactory. Si es
                        // StringConcatFactory, no es riesgo (el bootstrap no puede
                        // acceder campos privados ajenos ni encapsular una referencia
                        // arbitraria).
                        //
                        // T3 corte lambda-blindness: si el bootstrap es
                        // LambdaMetafactory (lambda/method-ref real), antes esto
                        // marcaba risk=true incondicional -- la clase proxy que la JVM
                        // sintetiza para esta lambda es un "hidden class" (JEP 371)
                        // que Instrumentation nunca expone (confirmado empiricamente:
                        // ni retransform ni un ClassFileTransformer de definicion la
                        // ven), asi que nunca se podia leer su bytecode para saber si
                        // hace CHECKCAST contra un tipo inyectado por mixin. Ahora se
                        // resuelve sin leer esa clase: samMethodType (tipo erased de la
                        // interfaz funcional) vs instantiatedMethodType (tipo real del
                        // call site), ambos ya disponibles como argumentos estaticos
                        // del bootstrap en ESTE constant pool (siempre legible, nunca
                        // hidden), determinan EXACTAMENTE que tipo(s), si alguno, el
                        // proxy debe castear -- ver lambdaNarrowedParamTypes. GETFIELD/
                        // PUTFIELD nunca se agregan aqui: un proxy de LambdaMetafactory
                        // solo reenvia argumentos e invoca el MethodHandle capturado,
                        // acceso a campo arbitrario es categoricamente imposible.
                        // Narrowing no resuelto (null) sigue fail-closed como riesgo;
                        // cualquier otro bootstrap (sintetico de otro tipo, o si no se
                        // puede resolver el owner) tambien sigue fail-closed.
                        //
                        // T3 corte "ceguera a records": java.lang.runtime.ObjectMethods
                        // (JEP 395, mecanismo estandar del JDK desde 14 para equals/
                        // hashCode/toString de CUALQUIER record -- confirmado via javap
                        // real sobre net.minecraft.class_9259$class_9260/class_9240/
                        // class_6760, los 3 records que disparaban risk=true en TODOS
                        // los targets escaneados, por ser omnipresentes en bytecode
                        // moderno) genera esos 3 metodos enteramente invocando
                        // ObjectMethods.bootstrap con MethodHandle REF_getField sobre
                        // los propios componentes del record -- el codigo real corre
                        // dentro de ObjectMethods (JDK), nunca aparece como bytecode en
                        // esta clase. No hay, ni puede haber, ningun CHECKCAST/
                        // INVOKEINTERFACE/GETFIELD adicional que este scanCode se este
                        // perdiendo: la unica instruccion visible en estos metodos es el
                        // propio invokedynamic. Mismo nivel de certeza categorica que
                        // StringConcatFactory (no requiere narrowing como Lambda-
                        // Metafactory porque no hay ningun tipo de parametro que pueda
                        // resultar mas angosto -- el bootstrap no expone ninguno).
                        BootstrapEntry entry = bootstrapEntryFor(idx, bootstrapOwners);
                        String owner = entry == null ? null : entry.owner;
                        if ("java.lang.invoke.StringConcatFactory".equals(owner)
                                || "java.lang.runtime.ObjectMethods".equals(owner)) {
                            // sin riesgo
                        } else if ("java.lang.invoke.LambdaMetafactory".equals(owner)) {
                            List<String> narrowed = lambdaNarrowedParamTypes(entry);
                            if (narrowed == null) {
                                risk = true;
                                riskReasons.add("lambda_narrowing_unresolved");
                            } else {
                                typeRefs.addAll(narrowed);
                            }
                        } else if ("java.lang.runtime.SwitchBootstraps".equals(owner)) {
                            // T3 corte "ceguera a pattern-matching switch": confirmado
                            // via javap real (net.minecraft.class_1927/class_3164/
                            // class_11352/class_2512/class_8496/class_815/class_279,
                            // los 7 casos restantes que disparaban risk=true en TODOS
                            // los targets escaneados tras el fix de records) que
                            // SwitchBootstraps.typeSwitch (JEP 441, switch de patrones
                            // sobre tipo, Java 21) codifica cada label de tipo del
                            // switch como argumento estatico CONSTANT_Class del
                            // bootstrap -- exactamente la misma informacion que
                            // CHECKCAST/INSTANCEOF (case 192/193) expone via operando,
                            // solo que aqui el chequeo de tipo no es una instruccion
                            // sino un argumento de invokedynamic. Extraerlos como
                            // typeRefs (ver switchLabelTypes) es tan preciso como leer
                            // un checkcast real -- no es narrowing especulativo, es la
                            // misma lista de tipos que la JVM usa para el
                            // Object.getClass()==label.class de cada rama. Labels no-
                            // Class (constantes enum, null, default) no representan
                            // ninguna referencia de tipo y se omiten sin riesgo. El
                            // bootstrap nunca encapsula acceso a campo (sus argumentos
                            // son solo Class/enum-desc/String, nunca un MethodHandle
                            // hacia un campo) -- mismo nivel de certeza categorica que
                            // LambdaMetafactory/ObjectMethods.
                            typeRefs.addAll(switchLabelTypes(entry));
                        } else {
                            risk = true;
                            riskReasons.add("unhandled_bootstrap_owner:" + (owner == null ? "unresolved" : owner));
                        }
                        break;
                    }
                    default: {
                        int extra = FIXED_OPERAND_BYTES[opcode];
                        if (extra < 0) {
                            throw new IOException("scanCode: opcode desconocido " + opcode + " en pc " + opStart);
                        }
                        pc += extra;
                    }
                }
            }
            if (pc != insns.length) {
                // Fail-closed: un ancho de operando mal contado deberia desincronizar
                // el cursor y NO terminar exacto al final de las instrucciones -- si
                // eso pasa, no confiar en lo ya recolectado (podria haber leido campos
                // de offsets equivocados en silencio).
                throw new IOException("scanCode: desincronizado, pc=" + pc + " code_length=" + insns.length);
            }
            return new CodeScanResult(fields, typeRefs, risk, riskReasons);
        }

        private int readInt(byte[] b, int off) {
            return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                    | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
        }

        private int readU2(byte[] b, int off) {
            return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
        }

        private Header readHeader() throws IOException {
            if (in.readInt() != 0xCAFEBABE) throw new IOException("bad magic");
            in.readUnsignedShort();
            in.readUnsignedShort();
            readCp();
            int accessFlags = in.readUnsignedShort();
            int thisClass = in.readUnsignedShort();
            in.readUnsignedShort();
            int ifaceCount = in.readUnsignedShort();
            // T3 corte Q: antes esto solo avanzaba el cursor (skipInterfaces) sin
            // resolver los nombres -- necesarios para detectar mixins-clase que
            // declaran "implements AlgunaInterface" directamente (ver
            // buildInterfaceContributionsCategory).
            List<String> ifaceNames = new ArrayList<String>(ifaceCount);
            for (int i = 0; i < ifaceCount; i++) {
                int idx = in.readUnsignedShort();
                String n = className(idx);
                if (!n.isEmpty()) ifaceNames.add(n.replace('/', '.'));
            }
            return new Header(className(thisClass).replace('/', '.'), ifaceNames, accessFlags);
        }

        private void readCp() throws IOException {
            int count = in.readUnsignedShort();
            cp = new Object[count];
            tags = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                tags[i] = tag;
                switch (tag) {
                    case 1: cp[i] = in.readUTF(); break;
                    case 3: cp[i] = Integer.valueOf(in.readInt()); break;
                    case 4: cp[i] = Float.valueOf(in.readFloat()); break;
                    case 5: cp[i] = Long.valueOf(in.readLong()); i++; break;
                    case 6: cp[i] = Double.valueOf(in.readDouble()); i++; break;
                    case 7: case 8: case 16: cp[i] = Integer.valueOf(in.readUnsignedShort()); break;
                    // T3 corte Parte 2: Fieldref/Methodref/InterfaceMethodref/NameAndType ya
                    // no se tiran -- se guardan como {idx1,idx2} para que refAt() pueda
                    // resolver a que campo/metodo apunta un GETFIELD/INVOKEVIRTUAL real
                    // (scanCode).
                    case 9: case 10: case 11: case 12:
                        cp[i] = new int[] { in.readUnsignedShort(), in.readUnsignedShort() };
                        break;
                    // T3 corte Parte 2 (fix post-verificacion, bug B): Dynamic/InvokeDynamic
                    // (bootstrap_method_attr_index, name_and_type_index) y MethodHandle
                    // (reference_kind, reference_index) ya no se tiran -- hacen falta para
                    // resolver a que fabrica de bootstrap apunta un invokedynamic real
                    // (distinguir StringConcatFactory, inocuo, de LambdaMetafactory/otros).
                    case 17: case 18:
                        cp[i] = new int[] { in.readUnsignedShort(), in.readUnsignedShort() };
                        break;
                    case 15:
                        cp[i] = new int[] { in.readUnsignedByte(), in.readUnsignedShort() };
                        break;
                    case 19: case 20: cp[i] = Integer.valueOf(in.readUnsignedShort()); break;
                    default: throw new IOException("cp tag " + tag);
                }
            }
        }

        private List<MemberInfo> readMembers(String kind, boolean annotations,
                                             Set<String> summary) throws IOException {
            return readMembers(kind, annotations, summary, false);
        }

        // T3 corte Parte 2: overload que ademas captura los bytes crudos del atributo
        // Code de cada metodo (captureCode=true) -- necesario para scanFieldReferences.
        // El overload de 3 args delega aca con captureCode=false: cero cambio de
        // comportamiento en mixinInfo()/classSummary()/mixinMergedMembers().
        private List<MemberInfo> readMembers(String kind, boolean annotations,
                                             Set<String> summary, boolean captureCode) throws IOException {
            List<MemberInfo> out = (annotations || captureCode)
                    ? new ArrayList<MemberInfo>() : Collections.<MemberInfo>emptyList();
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                int access = in.readUnsignedShort();
                String name = utf(in.readUnsignedShort());
                String desc = utf(in.readUnsignedShort());
                if (summary != null) summary.add(name + desc);
                List<AnnotationInfo> anns = annotations
                        ? new ArrayList<AnnotationInfo>()
                        : Collections.<AnnotationInfo>emptyList();
                byte[][] codeBox = captureCode ? new byte[1][] : null;
                int attrs = in.readUnsignedShort();
                for (int j = 0; j < attrs; j++) {
                    readMemberAttribute(anns, annotations, codeBox);
                }
                if (annotations || captureCode) {
                    out.add(new MemberInfo(kind, access, name, desc, anns,
                            codeBox == null ? null : codeBox[0]));
                }
            }
            return out;
        }

        private void readMemberAttribute(List<AnnotationInfo> out, boolean collect) throws IOException {
            readMemberAttribute(out, collect, null);
        }

        private void readMemberAttribute(List<AnnotationInfo> out, boolean collect,
                                         byte[][] codeBox) throws IOException {
            String name = utf(in.readUnsignedShort());
            int len = in.readInt();
            if (codeBox != null && "Code".equals(name)) {
                byte[] raw = new byte[len];
                in.readFully(raw);
                codeBox[0] = raw;
                return;
            }
            if (!collect || (!"RuntimeVisibleAnnotations".equals(name)
                    && !"RuntimeInvisibleAnnotations".equals(name))) {
                skip(len);
                return;
            }
            readAnnotations(out);
        }

        private List<AnnotationInfo> readClassAnnotations() throws IOException {
            List<AnnotationInfo> out = new ArrayList<AnnotationInfo>();
            int attrs = in.readUnsignedShort();
            for (int i = 0; i < attrs; i++) {
                String name = utf(in.readUnsignedShort());
                int len = in.readInt();
                if ("RuntimeVisibleAnnotations".equals(name)
                        || "RuntimeInvisibleAnnotations".equals(name)) {
                    readAnnotations(out);
                } else {
                    skip(len);
                }
            }
            return out;
        }

        private void skipAttributes() throws IOException {
            int attrs = in.readUnsignedShort();
            for (int i = 0; i < attrs; i++) {
                in.readUnsignedShort();
                skip(in.readInt());
            }
        }

        /**
         * T3 corte Parte 2 (fix post-verificacion, bug B): lee el atributo de clase
         * BootstrapMethods (mismo patron que readClassAnnotations -- intercepta por
         * nombre, el resto se saltea intacto) y devuelve, por cada entrada,
         * bootstrap_method_attr_index -&gt; {@link BootstrapEntry} (clase dueña del
         * metodo de bootstrap, resuelto via el MethodHandle referenciado, MAS los
         * indices crudos de constant pool de sus argumentos estaticos). Usado por
         * scanCode para distinguir invokedynamic de StringConcatFactory
         * (concatenacion de strings, inocuo) de LambdaMetafactory (resuelto via
         * narrowing de samMethodType/instantiatedMethodType, ver
         * lambdaNarrowedParamTypes) de cualquier otro bootstrap (riesgo real).
         *
         * <p>T3 corte lambda-blindness: antes los argIndices se leian y se
         * descartaban (el unico uso era resolver el owner) -- ahora se retienen
         * porque LambdaMetafactory codifica samMethodType/instantiatedMethodType
         * como argumentos estaticos, no en el NameAndType del invokedynamic.
         */
        private Map<Integer, BootstrapEntry> readClassBootstrapMethods() throws IOException {
            Map<Integer, BootstrapEntry> out = new LinkedHashMap<Integer, BootstrapEntry>();
            int attrs = in.readUnsignedShort();
            for (int i = 0; i < attrs; i++) {
                String name = utf(in.readUnsignedShort());
                int len = in.readInt();
                if (!"BootstrapMethods".equals(name)) {
                    skip(len);
                    continue;
                }
                int num = in.readUnsignedShort();
                for (int j = 0; j < num; j++) {
                    int methodRefIdx = in.readUnsignedShort();
                    int numArgs = in.readUnsignedShort();
                    int[] argIdx = new int[numArgs];
                    for (int k = 0; k < numArgs; k++) argIdx[k] = in.readUnsignedShort();
                    out.put(Integer.valueOf(j), new BootstrapEntry(methodHandleOwner(methodRefIdx), argIdx));
                }
            }
            return out;
        }

        /**
         * T3 corte Parte 2 (fix post-verificacion, bug B): resuelve un indice
         * CONSTANT_MethodHandle a la clase dueña del metodo/campo referenciado
         * (via reference_index, que apunta a un Fieldref/Methodref/InterfaceMethodref
         * ya parseado por refAt). null si no resuelve -- tratado fail-closed aguas
         * arriba (no StringConcatFactory == riesgo).
         */
        private String methodHandleOwner(int cpIndex) {
            if (cp == null || cpIndex <= 0 || cpIndex >= cp.length || !(cp[cpIndex] instanceof int[])) return null;
            int[] mh = (int[]) cp[cpIndex]; // {reference_kind, reference_index}
            String[] ref = refAt(mh[1]);
            return ref == null ? null : ref[0];
        }

        /**
         * T3 corte Parte 2 (fix post-verificacion, bug B); renombrada corte
         * lambda-blindness (antes bootstrapOwnerFor, devolvia solo el owner string):
         * resuelve un indice CONSTANT_InvokeDynamic (operando de una instruccion
         * invokedynamic real) a su {@link BootstrapEntry} completa (owner + args),
         * usando la tabla ya armada por readClassBootstrapMethods. null si no
         * resuelve (constant pool inconsistente o BootstrapMethods ausente) --
         * fail-closed aguas arriba.
         */
        private BootstrapEntry bootstrapEntryFor(int cpIndex, Map<Integer, BootstrapEntry> bootstrapOwners) {
            if (bootstrapOwners == null || cp == null || cpIndex <= 0 || cpIndex >= cp.length
                    || !(cp[cpIndex] instanceof int[])) return null;
            int[] invokeDynamic = (int[]) cp[cpIndex]; // {bootstrap_method_attr_index, name_and_type_index}
            return bootstrapOwners.get(Integer.valueOf(invokeDynamic[0]));
        }

        /**
         * T3 corte lambda-blindness: para un bootstrap de
         * LambdaMetafactory.metafactory/altMetafactory (ambas variantes comparten
         * este prefijo de argumentos estaticos: samMethodType, implMethod,
         * instantiatedMethodType -- altMetafactory solo agrega flags despues),
         * compara samMethodType (tipo erased de la interfaz funcional) contra
         * instantiatedMethodType (tipo real del call site) parametro a parametro
         * (el retorno se ignora deliberadamente: un retorno mas especifico que el
         * erased nunca requiere checkcast, solo upcast) y devuelve, para cada
         * posicion donde difieren, el tipo instanciado (mas angosto) -- ese es
         * exactamente el tipo contra el que la clase proxy sintetizada por la JVM
         * (nunca legible via Instrumentation, ver UNREADABLE_SHAPE_REF/isHidden)
         * debe hacer CHECKCAST para poder invocar el metodo real.
         *
         * <p>Fail-closed: null si argIndices no tiene al menos 3 entradas, si los
         * indices esperados no son CONSTANT_MethodType (tag 16) en el constant pool,
         * si los descriptores no parsean, o si la cantidad de parametros difiere
         * entre samMethodType e instantiatedMethodType (no deberia pasar en bytecode
         * valido generado por javac, pero no se asume) -- tratado aguas arriba
         * exactamente igual que cualquier otro riesgo no resuelto.
         */
        private List<String> lambdaNarrowedParamTypes(BootstrapEntry entry) {
            if (entry == null || entry.argIndices == null || entry.argIndices.length < 3) return null;
            String samDesc = methodTypeDescriptorAt(entry.argIndices[0]);
            String instDesc = methodTypeDescriptorAt(entry.argIndices[2]);
            if (samDesc == null || instDesc == null) return null;
            List<String> samParams = descriptorParamTypesDotted(samDesc);
            List<String> instParams = descriptorParamTypesDotted(instDesc);
            if (samParams == null || instParams == null || samParams.size() != instParams.size()) return null;
            List<String> narrowed = new ArrayList<String>();
            for (int i = 0; i < samParams.size(); i++) {
                String s = samParams.get(i);
                String t = instParams.get(i);
                if (!s.equals(t)) narrowed.add(t);
            }
            return narrowed;
        }

        /** T3 corte "ceguera a pattern-matching switch": extrae, de los argumentos
         * estaticos de un bootstrap SwitchBootstraps.typeSwitch, cada label que sea
         * CONSTANT_Class (tag 7) -- confirmado via javap real que estos son
         * exactamente los tipos contra los que el switch de patrones compara (mismo
         * dato que expondria un CHECKCAST/INSTANCEOF equivalente). Labels de otro tag
         * (String para enum, Integer para null, ConstantDynamic para otros patrones
         * de record) no son referencias de tipo y se omiten sin riesgo -- no
         * representan ningun chequeo de forma sobre una interfaz/campo inyectado por
         * mixin. Lista vacia (nunca null) si entry no tiene argumentos: fail-open
         * deliberado porque un typeSwitch sin labels de Class no puede, por
         * construccion, referenciar ninguna forma. */
        private List<String> switchLabelTypes(BootstrapEntry entry) {
            List<String> out = new ArrayList<String>();
            if (entry == null || entry.argIndices == null) return out;
            for (int idx : entry.argIndices) {
                if (tags == null || idx <= 0 || idx >= tags.length || tags[idx] != 7) continue;
                String t = className(idx).replace('/', '.');
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        }

        /** T3 corte lambda-blindness: resuelve un indice de constant pool que debe
         * ser CONSTANT_MethodType (tag 16) a su descriptor UTF8 crudo (ej.
         * "(Ljava/lang/Object;)V") -- null (fail-closed) si el tag no calza o el
         * indice no resuelve, nunca asume. */
        private String methodTypeDescriptorAt(int idx) {
            if (tags == null || idx <= 0 || idx >= tags.length || tags[idx] != 16) return null;
            String desc = className(idx);
            return desc.isEmpty() ? null : desc;
        }

        /** T3 corte lambda-blindness: parser minimo de la lista de tipos de
         * parametro de un descriptor de metodo JVM ("(...)ret"), ignorando el tipo
         * de retorno -- devuelve cada tipo con notacion de clase dotted (ej.
         * "java.lang.Object", "[I" se deja tal cual para arrays). null si el
         * descriptor no arranca con '(' o esta mal formado (fail-closed). */
        private List<String> descriptorParamTypesDotted(String desc) {
            if (desc == null || desc.isEmpty() || desc.charAt(0) != '(') return null;
            List<String> out = new ArrayList<String>();
            int i = 1;
            while (i < desc.length() && desc.charAt(i) != ')') {
                int start = i;
                while (i < desc.length() && desc.charAt(i) == '[') i++;
                if (i >= desc.length()) return null;
                char c = desc.charAt(i);
                if (c == 'L') {
                    int semi = desc.indexOf(';', i);
                    if (semi < 0) return null;
                    i = semi + 1;
                } else {
                    i++;
                }
                out.add(desc.substring(start, i).replace('/', '.'));
            }
            return i < desc.length() ? out : null;
        }

        private void readAnnotations(List<AnnotationInfo> out) throws IOException {
            int num = in.readUnsignedShort();
            for (int i = 0; i < num; i++) {
                out.add(readAnnotation());
            }
        }

        private AnnotationInfo readAnnotation() throws IOException {
            AnnotationInfo out = new AnnotationInfo(utf(in.readUnsignedShort()));
            int pairs = in.readUnsignedShort();
            for (int i = 0; i < pairs; i++) {
                String element = utf(in.readUnsignedShort());
                List<String> values = new ArrayList<String>();
                readElementValue(values, out, element);
                out.values.put(element, values);
            }
            return out;
        }

        private void readElementValue(List<String> out) throws IOException {
            readElementValue(out, null, null);
        }

        // T3 corte tier3 end-to-end §1: parent/element no null captura ademas la
        // AnnotationInfo anidada real (tag '@') en parent.nested -- necesario para
        // leer @Implements(@Interface(iface=X.class)) sin perder estructura. El
        // overload de 1 arg (usado por todos los call-sites previos a este corte)
        // sigue con comportamiento identico: pasa null, nested nunca se puebla.
        private void readElementValue(List<String> out, AnnotationInfo parent, String element) throws IOException {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 'B': case 'C': case 'D': case 'F': case 'I': case 'J':
                case 'S': case 'Z': case 's': {
                    int idx = in.readUnsignedShort();
                    out.add(constantString(idx));
                    return;
                }
                case 'e':
                    out.add(dottedAnnotation(utf(in.readUnsignedShort())) + "." + utf(in.readUnsignedShort()));
                    return;
                case 'c':
                    out.add(normalizeClassValue(utf(in.readUnsignedShort())));
                    return;
                case '@': {
                    AnnotationInfo nested = readAnnotation();
                    out.add(annotationSignature(nested));
                    if (parent != null && element != null) {
                        List<AnnotationInfo> list = parent.nested.get(element);
                        if (list == null) {
                            list = new ArrayList<AnnotationInfo>();
                            parent.nested.put(element, list);
                        }
                        list.add(nested);
                    }
                    return;
                }
                case '[': {
                    int n = in.readUnsignedShort();
                    for (int i = 0; i < n; i++) readElementValue(out, parent, element);
                    return;
                }
                default:
                    throw new IOException("annotation tag " + tag);
            }
        }

        private void addTargets(List<String> values, Set<String> out) {
            if (values == null) return;
            for (String v : values) addTarget(v, out);
        }

        private void addTarget(String target, Set<String> out) {
            if (target == null || target.isEmpty() || target.startsWith("[")) return;
            String s = normalizeClassValue(target);
            if (!s.isEmpty() && !s.startsWith("[")) out.add(s.replace('/', '.'));
        }

        private String normalizeClassValue(String desc) {
            if (desc == null) return "";
            String s = desc;
            if (s.startsWith("L") && s.endsWith(";")) s = s.substring(1, s.length() - 1);
            return s.replace('/', '.');
        }

        private String annotationSignature(AnnotationInfo a) {
            StringBuilder sb = new StringBuilder();
            sb.append('@').append(dottedAnnotation(a.desc));
            if (!a.values.isEmpty()) {
                sb.append('(');
                boolean first = true;
                for (Map.Entry<String, List<String>> e : a.values.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(e.getKey()).append('=').append(e.getValue());
                }
                sb.append(')');
            }
            return sb.toString();
        }

        private String constantString(int idx) {
            Object v = idx > 0 && idx < cp.length ? cp[idx] : null;
            if (v instanceof String) return (String) v;
            if (v instanceof Integer && tags[idx] == 8) return utf(((Integer) v).intValue());
            if (v instanceof Number || v instanceof Boolean || v instanceof Character) return String.valueOf(v);
            return v == null ? "" : String.valueOf(v);
        }

        private String utf(int idx) {
            Object v = idx > 0 && idx < cp.length ? cp[idx] : null;
            return v instanceof String ? (String) v : "";
        }

        private String className(int idx) {
            Object v = idx > 0 && idx < cp.length ? cp[idx] : null;
            if (v instanceof Integer) return utf(((Integer) v).intValue());
            return "";
        }

        /**
         * T3 corte Parte 2: resuelve un indice de Fieldref/Methodref/InterfaceMethodref
         * a {owner, name, desc} dotted -- usado por scanCode para saber a que campo o
         * metodo apunta una instruccion GETFIELD/PUTFIELD/INVOKEXXX real. null si el
         * indice no resuelve a una referencia (constant pool inconsistente).
         */
        private String[] refAt(int cpIndex) {
            if (cp == null || cpIndex <= 0 || cpIndex >= cp.length || !(cp[cpIndex] instanceof int[])) return null;
            int[] ref = (int[]) cp[cpIndex];
            String owner = className(ref[0]).replace('/', '.');
            Object natObj = ref[1] > 0 && ref[1] < cp.length ? cp[ref[1]] : null;
            if (!(natObj instanceof int[])) return null;
            int[] nat = (int[]) natObj;
            return new String[] { owner, utf(nat[0]), utf(nat[1]) };
        }

        private void skip(int n) throws IOException {
            int left = n;
            while (left > 0) {
                int skipped = in.skipBytes(left);
                if (skipped <= 0) {
                    if (in.read() < 0) throw new IOException("eof");
                    skipped = 1;
                }
                left -= skipped;
            }
        }

        private final class Header {
            final String name;
            final List<String> interfaceNames;
            final int accessFlags;

            Header(String name, List<String> interfaceNames, int accessFlags) {
                this.name = name;
                this.interfaceNames = interfaceNames;
                this.accessFlags = accessFlags;
            }
        }
    }

    // -------------------------------------------------------------------------
    // T3 corte narrowing configPlugin: helpers estaticos para el analisis de
    // bytecode de IMixinConfigPlugin.shouldApplyMixin sin ejecutar el plugin.
    // -------------------------------------------------------------------------

    /**
     * Intenta obtener los bytes crudos (.class) de un IMixinConfigPlugin por
     * nombre dotted. Estrategia: Tier3LiveCapture (captura pasiva, sin bloquear)
     * primero; luego busqueda en los jars de todos los namespaces conocidos
     * (rootsByNs), ya que el plugin puede venir de cualquier mod. null si no se
     * resuelve por ninguna via -- tratado fail-closed aguas arriba.
     */
    private static byte[] configPluginBytesFor(String pluginDotted,
                                               Map<String, List<Path>> rootsByNs) {
        // Fuente 1: captura pasiva (Tier3LiveCapture), la mas confiable (bytecode vivo).
        byte[] live = Tier3LiveCapture.get(pluginDotted);
        if (live != null) return live;
        // Fuente 2: lectura desde los jars de mods (mismo patron que readClassBytes
        // usado por buildFieldSafety) -- cubre plugins que nunca cargaron organicamente.
        if (rootsByNs == null) return null;
        for (List<Path> roots : rootsByNs.values()) {
            byte[] b = readClassBytes(roots, pluginDotted);
            if (b != null) return b;
        }
        return null;
    }

    /**
     * Verifica estaticamente si IMixinConfigPlugin.shouldApplyMixin en la clase
     * {@code pluginDotted} es un predicado puro de comparacion de String -- esto
     * es, solo carga parametros/constantes String, invoca metodos whitelisteados
     * de java.lang.String (equals/contains/startsWith/endsWith/isEmpty/trim/
     * toLowerCase/toUpperCase), ramifica sobre el resultado y retorna. CUALQUIER
     * opcode fuera de la whitelist (acceso a campo, invocacion arbitraria, etc.)
     * devuelve false (fail-closed).
     *
     * <p>La firma de shouldApplyMixin en IMixinConfigPlugin es:
     * {@code boolean shouldApplyMixin(String targetClassName, String mixinClassName)}
     * = descriptor {@code (Ljava/lang/String;Ljava/lang/String;)Z}.
     */
    private static boolean pluginShouldApplyMixinIsSimple(String pluginDotted,
                                                          Map<String, List<Path>> rootsByNs) {
        try {
            byte[] bytes = configPluginBytesFor(pluginDotted, rootsByNs);
            if (bytes == null) return false;
            return new ClassFile(bytes).methodIsPureStringPredicate(
                    "shouldApplyMixin", "(Ljava/lang/String;Ljava/lang/String;)Z");
        } catch (Throwable ignored) {
            // Fail-closed: cualquier excepcion (parseo, opcode desconocido, etc.)
            // conserva el bloqueador.
            return false;
        }
    }

    /**
     * Devuelve true si y solo si TODOS los IMixinConfigPlugin de los
     * contributors tienen shouldApplyMixin verificado como predicado puro de
     * String por analisis estatico de bytecode. Un solo plugin no analizable
     * (bytes no disponibles, opcode fuera de whitelist, excepcion) hace fallar
     * el conjunto entero (fail-closed). Lista vacia o sin plugins -> true
     * (sin plugin no hay riesgo, patron identidad).
     */
    private static boolean allPluginsForContributorsAreSimplePredicates(
            List<MixinContributor> contributors,
            Map<String, List<Path>> rootsByNs) {
        Set<String> seen = new LinkedHashSet<String>();
        for (MixinContributor c : contributors) {
            if (c.configPlugin == null) continue;
            if (seen.contains(c.configPlugin)) continue; // ya verificado
            seen.add(c.configPlugin);
            if (!pluginShouldApplyMixinIsSimple(c.configPlugin, rootsByNs)) {
                return false;
            }
        }
        return true;
    }

    private Tier3MixinAudit() {}
}
