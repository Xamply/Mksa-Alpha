package dev.mksa.agent.ledger;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Corte K (Pilar 1b): hook ASM sobre {@code MixinInfo.createContextFor(TargetClassContext)},
 * el punto real donde {@code MixinApplicatorStandard.apply(SortedSet<MixinInfo>)} invoca
 * cada mixin en el orden EXACTO ya ordenado por prioridad -- la fuente de verdad de orden
 * runtime que Pilar 1a (@MixinMerged, corte J) no podia dar (esa solo confirma pertenencia
 * de miembro, no secuencia).
 *
 * Vive en dev.mksa.agent.ledger (NO en dev.mksa.agent), igual que LedgerTransformer: es
 * el unico paquete con ASM en el classpath, cargado por Agent.installLedger en un
 * URLClassLoader aislado (ASM no puede aparecer en el classloader del sistema, ver
 * javadoc de Agent.installLedger). Instalado por reflexion, mismo idioma que
 * LedgerTransformer.install, inmediatamente despues de este en Agent.installLedger.
 *
 * A diferencia de Tier3LiveCapture (observacional puro, via el callback nativo de
 * ClassFileTransformer, vive en dev.mksa.agent porque no usa ASM), este transformer
 * SI muta bytecode: inyecta, a la entrada del metodo, una llamada straight-line (sin
 * branches nuevos, COMPUTE_MAXS basta) a Bridge.onMixinApplyOrder(targetClassName,
 * mixinName, priority). Dispatcha por nombre interno EXACTO de clase
 * (org/spongepowered/asm/mixin/transformer/MixinInfo) -- evita la ambiguedad con
 * MixinPreProcessorStandard, que define un metodo con el MISMO nombre pero es una
 * clase distinta y no relacionada (fase de pre-proceso, no la aplicacion real).
 *
 * Instalado en premain junto al LedgerTransformer, antes de que Fabric/Forge carguen
 * MixinInfo.
 */
public final class Tier3MixinOrderCapture implements ClassFileTransformer {

    private static final String BRIDGE = "dev/mksa/bridge/Bridge";
    private static final String MIXIN_INFO = "org/spongepowered/asm/mixin/transformer/MixinInfo";
    private static final String TARGET_CLASS_CONTEXT = "org/spongepowered/asm/mixin/transformer/TargetClassContext";
    private static final String CREATE_CONTEXT_FOR_DESC =
            "(Lorg/spongepowered/asm/mixin/transformer/TargetClassContext;)"
                    + "Lorg/spongepowered/asm/mixin/transformer/MixinTargetContext;";

    public static void install(Instrumentation inst) {
        inst.addTransformer(new Tier3MixinOrderCapture(), false);
        System.err.println("[mksa] tier3 mixin-order-capture transformer instalado");
    }

    private Tier3MixinOrderCapture() {}

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] classfileBuffer) {
        if (!MIXIN_INFO.equals(className)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            cr.accept(new MixinInfoVisitor(cw), 0);
            System.err.println("[mksa] tier3 mixin-order-capture: instrumentado " + className);
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[mksa] tier3 mixin-order-capture: fallo instrumentando " + className + ": " + t);
            return null; // null = sin transformar; el juego sigue intacto
        }
    }

    private static final class MixinInfoVisitor extends ClassVisitor {
        MixinInfoVisitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("createContextFor".equals(name) && CREATE_CONTEXT_FOR_DESC.equals(descriptor)) {
                System.err.println("[mksa] tier3 mixin-order-capture: inyectando en " + name + descriptor);
                return new CreateContextHookVisitor(mv);
            }
            return mv;
        }
    }

    /**
     * Inyecta, a la entrada de createContextFor(TargetClassContext), la llamada
     * Bridge.onMixinApplyOrder(targetClassName, this.getClassName(), this.getPriority()).
     * Straight-line: no introduce branch targets nuevos, no requiere visitFrame manual.
     *
     * Nota (fix post-corte K, descubierto durante el scout de seleccion de target de
     * Pilar 3): originalmente esto llamaba this.getName(), que en MixinInfo es un campo
     * DISTINTO de getClassName() -- getName() devuelve el nombre corto del mixin (el que
     * usa Mixin en sus propios mensajes de error), NO el FQN. El lado estatico
     * (Tier3MixinAudit.MixinContributor.mixin, poblado desde el escaneo de .mixins.json
     * via pkg+"."+name) SIEMPRE es FQN dotted. Comparar nombre-corto (runtime) contra
     * FQN (estatico) en buildRuntimeOrder hacia que "verified" saliera false para
     * practicamente cualquier target con al menos un mixin declarado, incluso cuando el
     * mixin aplicado era exactamente el declarado -- no era cobertura parcial honesta,
     * era un mismatch de formato. Confirmado con javap contra sponge-mixin-0.17.0+mixin.0.8.7.jar:
     * MixinInfo tiene los dos campos/getters separados (name vs className). getClassName()
     * es el que corresponde comparar contra MixinContributor.mixin.
     */
    private static final class CreateContextHookVisitor extends MethodVisitor {
        CreateContextHookVisitor(MethodVisitor mv) { super(Opcodes.ASM9, mv); }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 1); // TargetClassContext param
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET_CLASS_CONTEXT, "getClassName",
                    "()Ljava/lang/String;", false);
            super.visitVarInsn(Opcodes.ALOAD, 0); // this (MixinInfo)
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MIXIN_INFO, "getClassName",
                    "()Ljava/lang/String;", false);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MIXIN_INFO, "getPriority",
                    "()I", false);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "onMixinApplyOrder",
                    "(Ljava/lang/String;Ljava/lang/String;I)V", false);
        }
    }
}
