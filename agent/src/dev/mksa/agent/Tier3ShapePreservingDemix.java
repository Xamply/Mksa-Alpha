package dev.mksa.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * T3 Plan Maestro Parte 1: Sintetizador de bytecode neutro seguro para el modo PRESERVE_SHAPE.
 * Mantiene la forma estructural (interfaces, campos, firmas de métodos) intacta para no
 * romper consumidores de forma externos ni reflexión, pero neutraliza el cuerpo de los métodos
 * agregados/inyectados por el mod víctima.
 */
public final class Tier3ShapePreservingDemix {

    private Tier3ShapePreservingDemix() {}

    /**
     * Resultado de la síntesis de bytecode PRESERVE_SHAPE.
     */
    public static final class Outcome {
        public final boolean ok;
        public final byte[] bytes;
        public final String error;
        public final List<String> neutralizedMethods;

        private Outcome(boolean ok, byte[] bytes, String error, List<String> neutralizedMethods) {
            this.ok = ok;
            this.bytes = bytes;
            this.error = error;
            this.neutralizedMethods = neutralizedMethods;
        }

        public static Outcome ok(byte[] bytes, List<String> neutralizedMethods) {
            return new Outcome(true, bytes, null, neutralizedMethods);
        }

        public static Outcome fail(String error) {
            return new Outcome(false, null, error, null);
        }
    }

    /**
     * Sintetiza behaviorOffBytes conservando la forma live pero eliminando inyecciones y neutralizando
     * el cuerpo de los métodos aportados por la víctima.
     */
    public static Outcome synthesize(String target, byte[] liveBytes, byte[] baseBytes,
                                     Set<String> victimMixins) {
        if (liveBytes == null || liveBytes.length == 0) {
            return Outcome.fail("liveBytes nulos o vacios para target " + target);
        }

        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(liveBytes));
            int magic = dis.readInt();
            if (magic != 0xCAFEBABE) {
                return Outcome.fail("Magic number invalido en liveBytes para target " + target);
            }
            int minor = dis.readUnsignedShort();
            int major = dis.readUnsignedShort();

            int cpCount = dis.readUnsignedShort();
            byte[][] cpEntries = new byte[cpCount][];
            for (int i = 1; i < cpCount; i++) {
                int tag = dis.readUnsignedByte();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeByte(tag);
                switch (tag) {
                    case 7: // Class
                    case 8: // String
                    case 16: // MethodType
                    case 19: // Module
                    case 20: // Package
                        dos.writeShort(dis.readUnsignedShort());
                        break;
                    case 9: // Fieldref
                    case 10: // Methodref
                    case 11: // InterfaceMethodref
                    case 12: // NameAndType
                    case 18: // InvokeDynamic
                        dos.writeShort(dis.readUnsignedShort());
                        dos.writeShort(dis.readUnsignedShort());
                        break;
                    case 3: // Integer
                    case 4: // Float
                        dos.writeInt(dis.readInt());
                        break;
                    case 5: // Long
                    case 6: // Double
                        dos.writeLong(dis.readLong());
                        cpEntries[i] = baos.toByteArray();
                        i++; // Long y Double toman dos slots en constant pool
                        continue;
                    case 15: // MethodHandle
                        dos.writeByte(dis.readUnsignedByte());
                        dos.writeShort(dis.readUnsignedShort());
                        break;
                    case 1: // Utf8
                        int len = dis.readUnsignedShort();
                        dos.writeShort(len);
                        byte[] buf = new byte[len];
                        dis.readFully(buf);
                        dos.write(buf);
                        break;
                    default:
                        return Outcome.fail("Constant pool tag no soportado " + tag + " en " + target);
                }
                cpEntries[i] = baos.toByteArray();
            }

            int accessFlags = dis.readUnsignedShort();
            int thisClass = dis.readUnsignedShort();
            int superClass = dis.readUnsignedShort();

            int interfacesCount = dis.readUnsignedShort();
            int[] interfaces = new int[interfacesCount];
            for (int i = 0; i < interfacesCount; i++) {
                interfaces[i] = dis.readUnsignedShort();
            }

            int fieldsCount = dis.readUnsignedShort();
            byte[][] fieldsData = new byte[fieldsCount][];
            for (int i = 0; i < fieldsCount; i++) {
                fieldsData[i] = readMember(dis);
            }

            int methodsCount = dis.readUnsignedShort();
            List<byte[]> neutralizedMethodsData = new ArrayList<byte[]>();
            List<String> neutralizedNames = new ArrayList<String>();

            for (int i = 0; i < methodsCount; i++) {
                MemberParsed m = parseMember(dis);
                String name = getUtf8(cpEntries, m.nameIdx);
                String desc = getUtf8(cpEntries, m.descIdx);

                // Si el método fue agregado/inyectado por el mod víctima (o es synthetic/bridge de interfaz víctima),
                // neutralizar su cuerpo con un retorno seguro neutro.
                if (isVictimInjectedMethod(name, desc, victimMixins)) {
                    byte[] neutralized = buildNeutralizedMethod(m, desc, cpEntries);
                    neutralizedMethodsData.add(neutralized);
                    neutralizedNames.add(name + desc);
                } else {
                    neutralizedMethodsData.add(m.rawBytes);
                }
            }

            int attributesCount = dis.readUnsignedShort();
            byte[][] attributesData = new byte[attributesCount][];
            for (int i = 0; i < attributesCount; i++) {
                attributesData[i] = readAttribute(dis);
            }

            // Reconstruir classfile final
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(magic);
            dos.writeShort(minor);
            dos.writeShort(major);
            dos.writeShort(cpCount);
            for (int i = 1; i < cpCount; i++) {
                if (cpEntries[i] != null) {
                    dos.write(cpEntries[i]);
                }
            }
            dos.writeShort(accessFlags);
            dos.writeShort(thisClass);
            dos.writeShort(superClass);
            dos.writeShort(interfacesCount);
            for (int i = 0; i < interfacesCount; i++) {
                dos.writeShort(interfaces[i]);
            }
            dos.writeShort(fieldsCount);
            for (int i = 0; i < fieldsCount; i++) {
                dos.write(fieldsData[i]);
            }
            dos.writeShort(neutralizedMethodsData.size());
            for (byte[] mData : neutralizedMethodsData) {
                dos.write(mData);
            }
            dos.writeShort(attributesCount);
            for (int i = 0; i < attributesCount; i++) {
                dos.write(attributesData[i]);
            }

            return Outcome.ok(baos.toByteArray(), neutralizedNames);

        } catch (Throwable t) {
            return Outcome.fail("Error sintetizando PRESERVE_SHAPE para " + target + ": "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean isVictimInjectedMethod(String name, String desc, Set<String> victimMixins) {
        if (name.startsWith("chatheads$") || name.startsWith("chat_heads$")) return true;
        // Métodos de interfaz inyectados típicos de chat_heads
        if ("getHeadRenderable".equals(name) || "getHeadData".equals(name) || "setHeadData".equals(name)) return true;
        return false;
    }

    private static byte[] buildNeutralizedMethod(MemberParsed m, String desc, byte[][] cpEntries) throws IOException {
        // Encontrar índice de Utf8 "Code" en el cp
        int codeUtf8Idx = findUtf8(cpEntries, "Code");
        if (codeUtf8Idx <= 0) {
            return m.rawBytes; // Si no hay Utf8 "Code", devolver intacto
        }

        byte[] codeInsn = getNeutralReturnInstructions(desc);

        ByteArrayOutputStream codeAttrBaos = new ByteArrayOutputStream();
        DataOutputStream codeAttrDos = new DataOutputStream(codeAttrBaos);
        codeAttrDos.writeShort(2); // max_stack
        codeAttrDos.writeShort(8); // max_locals
        codeAttrDos.writeInt(codeInsn.length); // code_length
        codeAttrDos.write(codeInsn);
        codeAttrDos.writeShort(0); // exception_table_length
        codeAttrDos.writeShort(0); // attributes_count (sin LineNumberTable / LocalVariableTable para cuerpo neutro)

        byte[] codeAttrBytes = codeAttrBaos.toByteArray();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeShort(m.accessFlags);
        dos.writeShort(m.nameIdx);
        dos.writeShort(m.descIdx);
        dos.writeShort(1); // attributes_count = 1 (Code)
        dos.writeShort(codeUtf8Idx);
        dos.writeInt(codeAttrBytes.length);
        dos.write(codeAttrBytes);

        return baos.toByteArray();
    }

    private static byte[] getNeutralReturnInstructions(String desc) {
        char returnTypeChar = desc.charAt(desc.indexOf(')') + 1);
        switch (returnTypeChar) {
            case 'V':
                return new byte[] { (byte) 177 }; // return
            case 'Z': // boolean -> false
            case 'B': // byte -> 0
            case 'C': // char -> 0
            case 'S': // short -> 0
            case 'I': // int -> 0
                return new byte[] { (byte) 3, (byte) 172 }; // iconst_0, ireturn
            case 'J': // long -> 0L
                return new byte[] { (byte) 9, (byte) 173 }; // lconst_0, lreturn
            case 'F': // float -> 0.0f
                return new byte[] { (byte) 11, (byte) 174 }; // fconst_0, freturn
            case 'D': // double -> 0.0d
                return new byte[] { (byte) 14, (byte) 175 }; // dconst_0, dreturn
            case 'L': // Object -> null
            case '[': // Array -> null
            default:
                return new byte[] { (byte) 1, (byte) 176 }; // aconst_null, areturn
        }
    }

    private static byte[] readMember(DataInputStream dis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        int access = dis.readUnsignedShort();
        int name = dis.readUnsignedShort();
        int desc = dis.readUnsignedShort();
        dos.writeShort(access);
        dos.writeShort(name);
        dos.writeShort(desc);
        int attrCount = dis.readUnsignedShort();
        dos.writeShort(attrCount);
        for (int i = 0; i < attrCount; i++) {
            byte[] attr = readAttribute(dis);
            dos.write(attr);
        }
        return baos.toByteArray();
    }

    private static final class MemberParsed {
        final int accessFlags;
        final int nameIdx;
        final int descIdx;
        final byte[] rawBytes;

        MemberParsed(int accessFlags, int nameIdx, int descIdx, byte[] rawBytes) {
            this.accessFlags = accessFlags;
            this.nameIdx = nameIdx;
            this.descIdx = descIdx;
            this.rawBytes = rawBytes;
        }
    }

    private static MemberParsed parseMember(DataInputStream dis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        int access = dis.readUnsignedShort();
        int name = dis.readUnsignedShort();
        int desc = dis.readUnsignedShort();
        dos.writeShort(access);
        dos.writeShort(name);
        dos.writeShort(desc);
        int attrCount = dis.readUnsignedShort();
        dos.writeShort(attrCount);
        for (int i = 0; i < attrCount; i++) {
            byte[] attr = readAttribute(dis);
            dos.write(attr);
        }
        return new MemberParsed(access, name, desc, baos.toByteArray());
    }

    private static byte[] readAttribute(DataInputStream dis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        int nameIdx = dis.readUnsignedShort();
        int len = dis.readInt();
        dos.writeShort(nameIdx);
        dos.writeInt(len);
        byte[] buf = new byte[len];
        dis.readFully(buf);
        dos.write(buf);
        return baos.toByteArray();
    }

    private static String getUtf8(byte[][] cpEntries, int idx) {
        if (idx <= 0 || idx >= cpEntries.length || cpEntries[idx] == null) return "";
        byte[] entry = cpEntries[idx];
        if ((entry[0] & 0xFF) != 1) return "";
        int len = ((entry[1] & 0xFF) << 8) | (entry[2] & 0xFF);
        return new String(entry, 3, len);
    }

    private static int findUtf8(byte[][] cpEntries, String targetStr) {
        for (int i = 1; i < cpEntries.length; i++) {
            if (cpEntries[i] != null && (cpEntries[i][0] & 0xFF) == 1) {
                String str = getUtf8(cpEntries, i);
                if (targetStr.equals(str)) return i;
            }
        }
        return -1;
    }
}
