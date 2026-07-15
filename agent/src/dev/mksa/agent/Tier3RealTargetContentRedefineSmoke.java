package dev.mksa.agent;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T3 corte O (Pilar 3, segundo corte real del aplicador): extiende corte N
 * (Tier3RealTargetRedefineSmoke, identity redefine) probando la hipotesis
 * inmediata siguiente -- "?un redefineClasses() real que SI cambia contenido
 * (no identity) tambien tiene EXITO sobre el mismo target congelado, sin
 * tocar el ciclo completo de demix (reset a base + replay)?" -- todavia NO es
 * el pipeline de demix: sigue evitando cualquier cambio de forma (agregar/
 * quitar campos o metodos), que es lo que arriesga UnsupportedOperationException
 * segun la especificacion de JVMTI redefineClasses (solo permite cambiar
 * cuerpos de metodo, constant pool y atributos).
 *
 * Mutacion elegida a proposito por ser mecanica, generica y 100% reversible
 * sin tocar comportamiento real del juego: el atributo de clase `SourceFile`
 * -- una constante UTF8 puramente informativa (usada solo por stack traces),
 * ignorada por el verificador de bytecode, sin relacion con ningun campo o
 * metodo del target. A diferencia de Tier3RedefSmoke (que localiza su literal
 * con una busqueda global de bytes, valida porque su literal de prueba es
 * unico por construccion), aqui NO se puede asumir que el valor de SourceFile
 * sea unico en todo el class file -- un primer intento con busqueda global
 * fallo en runtime real con "literal not unique" (el string del nombre de
 * clase aparece mas de una vez en el bytecode real, ej. en LocalVariableTable
 * u otros atributos de debug). Por eso el parseo minimo propio (sin reusar
 * Tier3MixinAudit.ClassFile, mismo criterio de autocontencion que
 * Tier3RedefSmoke) trackea el OFFSET EXACTO de la entrada UTF8 del constant
 * pool que el atributo SourceFile referencia especificamente (indice
 * `sourceFileIndex` del propio atributo), y hace un patch quirurgico SOLO en
 * ese rango de bytes -- no una busqueda-reemplazo ciega. El valor se reemplaza
 * por un mismo-largo mutado (flip de mayus/minuscula del primer caracter
 * alfabetico, o sustitucion de 1 caracter ASCII si no hay ninguno), y se hace
 * redefineClasses() con ese contenido real distinto sobre el target real.
 * Despues se hace un SEGUNDO redefineClasses() de vuelta a los bytes vivos
 * originales (round trip), igual que el patron patch->rollback de
 * Tier3RedefSmoke/Tier3ControlledDemix -- si ambas llamadas tienen exito, eso
 * prueba que el primitivo acepta REDEFINICIONES REPETIDAS con contenido
 * genuinamente distinto sobre una clase real ya tejida por Mixin, no solo una
 * unica llamada identity (corte N).
 *
 * Limite honesto explicito (igual que corte N): no hay forma segura y
 * generica de leer el bytecode ACTUALMENTE activo de una clase real ya
 * cargada sin re-disparar la cadena de ClassFileTransformer (corte H). Este
 * corte tampoco reclama que el contenido mutado haya quedado observable en
 * tiempo de ejecucion (no se dispara ningun stack trace real a proposito,
 * para no tocar comportamiento del juego) -- solo que ambas llamadas a
 * redefineClasses() con bytes real y genuinamente distintos retornan sin
 * excepcion, y que el conteo de metodos/campos declarados no cambia en
 * ninguna de las dos (schemaStable en mutacion y en rollback).
 *
 * Target: corte S retargetea este mismo smoke al candidato nuevo elegido tras
 * corte Q (interface_contributions cerro el punto ciego que hizo REFUTAR el
 * reset a base del candidato original en corte P) -- balm /
 * net.minecraft.class_826, mixin unico ChestRendererMixin. Ver javadoc de
 * Tier3RealTargetRedefineSmoke (corte R) para el detalle completo de la
 * transicion de candidato.
 *
 * Corte X retargetea este mismo smoke otra vez, ahora al candidato senalado
 * tras el cierre del catalogo completo de Mixin en corte V (cuatro
 * categorias confirmadas: interface/method/field/field_modifier_contributions)
 * -- fabric-block-view-api-v2 / net.minecraft.class_1922, mixin unico
 * net.fabricmc.fabric.mixin.blockview.BlockGetterMixin. Ver javadoc de
 * Tier3RealTargetRedefineSmoke (corte W) para el detalle completo de esta
 * segunda transicion de candidato.
 */
final class Tier3RealTargetContentRedefineSmoke {
    private static final String TARGET_DOTTED = "net.minecraft.class_1922";
    private static final String FROZEN_NS = "fabric-block-view-api-v2";
    private static final String FROZEN_MIXIN =
            "net.fabricmc.fabric.mixin.blockview.BlockGetterMixin";

    static Map<String, Object> run(Instrumentation inst) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("model", "real_target_content_redefine_smoke_v1");
        out.put("receiptModel", "tier3_demix_tx_receipt_v1");
        out.put("targetClass", TARGET_DOTTED);
        out.put("targetScope", "real_minecraft_mixin_target");
        out.put("touchesMinecraftClasses", Boolean.TRUE);
        out.put("touchesMixinTargets", Boolean.TRUE);
        out.put("frozenCandidateNs", FROZEN_NS);
        out.put("frozenCandidateMixin", FROZEN_MIXIN);
        out.put("hypothesis",
                "real_content_redefine_non_identity_succeeds_and_rolls_back_on_real_target");
        out.put("verificationModel",
                "redefine_roundtrip_content_mutation_plus_schema_stability_no_active_bytecode_probe");

        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        List<Object> steps = new ArrayList<Object>();
        receipt.put("model", "tier3_demix_tx_receipt_v1");
        receipt.put("txId", "real-target-content-redefine-smoke-corte-x");
        receipt.put("targetClass", TARGET_DOTTED);
        receipt.put("steps", steps);
        out.put("receipt", receipt);

        if (inst == null) {
            out.put("status", "unsupported");
            out.put("redefineSupported", Boolean.FALSE);
            out.put("reason", "instrumentation_missing");
            receipt.put("status", "UNSUPPORTED");
            return out;
        }

        Class<?> target = resolveLoadedClass(inst, TARGET_DOTTED);
        out.put("resolved", Boolean.valueOf(target != null));
        if (target == null) {
            out.put("status", "unsupported");
            out.put("reason", "target_not_loaded");
            receipt.put("status", "UNSUPPORTED");
            return out;
        }
        steps.add(step("resolve_loaded_class", "OK",
                "Class<?> real resuelta via Instrumentation.getAllLoadedClasses()."));

        boolean redefineSupported = inst.isRedefineClassesSupported();
        boolean modifiable = false;
        try {
            modifiable = inst.isModifiableClass(target);
        } catch (Throwable ignored) {
        }
        out.put("redefineSupported", Boolean.valueOf(redefineSupported));
        out.put("modifiableClass", Boolean.valueOf(modifiable));
        if (!redefineSupported || !modifiable) {
            out.put("status", "unsupported");
            out.put("reason", !redefineSupported ? "redefine_classes_not_supported" : "target_not_modifiable");
            receipt.put("status", "UNSUPPORTED");
            return out;
        }

        byte[] liveBytes = Tier3LiveCapture.get(TARGET_DOTTED);
        out.put("liveBytesAvailable", Boolean.valueOf(liveBytes != null));
        if (liveBytes == null) {
            out.put("status", "unsupported");
            out.put("reason", "live_bytes_missing");
            receipt.put("status", "UNSUPPORTED");
            return out;
        }
        out.put("liveSha256", sha256(liveBytes));
        out.put("liveSize", Long.valueOf(liveBytes.length));

        SourceFileLocation loc;
        try {
            loc = locateSourceFile(liveBytes);
        } catch (IOException e) {
            out.put("status", "unsupported");
            out.put("reason", "source_file_parse_failed");
            out.put("message", String.valueOf(e.getMessage()));
            receipt.put("status", "UNSUPPORTED");
            return out;
        }
        if (loc == null) {
            out.put("status", "unsupported");
            out.put("reason", "source_file_attribute_missing");
            receipt.put("status", "UNSUPPORTED");
            return out;
        }
        out.put("sourceFileValue", loc.value);
        if (!isAscii(loc.value)) {
            out.put("status", "unsupported");
            out.put("reason", "source_file_non_ascii");
            receipt.put("status", "UNSUPPORTED");
            return out;
        }
        String mutatedValue = mutateSameLength(loc.value);
        if (mutatedValue == null) {
            out.put("status", "unsupported");
            out.put("reason", "source_file_not_mutable");
            receipt.put("status", "UNSUPPORTED");
            return out;
        }
        out.put("mutatedSourceFileValue", mutatedValue);
        steps.add(step("extract_source_file_attribute", "OK",
                "Valor real de SourceFile leido del bytecode vivo (offset exacto del UTF8 en el constant pool): "
                        + loc.value));

        // Patch quirurgico: solo los bytes UTF8 en [loc.utf8BytesOffset,
        // loc.utf8BytesOffset+length) cambian, mismo largo -- ningun otro byte
        // del class file se toca, y ninguna otra entrada del constant pool que
        // comparta el mismo string (si la hubiera) se ve afectada porque no se
        // hace busqueda-reemplazo global.
        byte[] mutatedBytes = liveBytes.clone();
        byte[] mutatedAscii = ascii(mutatedValue);
        System.arraycopy(mutatedAscii, 0, mutatedBytes, loc.utf8BytesOffset, mutatedAscii.length);
        out.put("mutatedSha256", sha256(mutatedBytes));
        out.put("mutatedSize", Long.valueOf(mutatedBytes.length));
        out.put("contentDiffersFromLive",
                Boolean.valueOf(!out.get("mutatedSha256").equals(out.get("liveSha256"))));

        int preMethods = target.getDeclaredMethods().length;
        int preFields = target.getDeclaredFields().length;
        out.put("preDeclaredMethods", Long.valueOf(preMethods));
        out.put("preDeclaredFields", Long.valueOf(preFields));

        boolean mutationApplied = false;
        boolean rollbackApplied = false;
        boolean rollbackOk = false;
        try {
            redefine(inst, target, mutatedBytes);
            mutationApplied = true;
            steps.add(step("redefine_content_mutation_on_real_target", "OK",
                    "redefineClasses() real sobre " + TARGET_DOTTED
                            + " con contenido distinto (SourceFile mutado, no identity)."));
            int midMethods = target.getDeclaredMethods().length;
            int midFields = target.getDeclaredFields().length;
            out.put("midDeclaredMethods", Long.valueOf(midMethods));
            out.put("midDeclaredFields", Long.valueOf(midFields));
            boolean schemaStableAfterMutation = midMethods == preMethods && midFields == preFields;
            out.put("schemaStableAfterMutation", Boolean.valueOf(schemaStableAfterMutation));
            steps.add(step("verify_schema_stable_after_mutation",
                    schemaStableAfterMutation ? "OK" : "FAILED",
                    "Conteo de metodos/campos declarados no cambio tras el redefine de contenido."));

            rollbackApplied = true;
            redefine(inst, target, liveBytes);
            rollbackOk = true;
            steps.add(step("redefine_rollback_to_live_on_real_target", "OK",
                    "redefineClasses() real de vuelta a los bytes vivos originales (round trip)."));
            int postMethods = target.getDeclaredMethods().length;
            int postFields = target.getDeclaredFields().length;
            out.put("postDeclaredMethods", Long.valueOf(postMethods));
            out.put("postDeclaredFields", Long.valueOf(postFields));
            boolean schemaStableAfterRollback = postMethods == preMethods && postFields == preFields;
            out.put("schemaStableAfterRollback", Boolean.valueOf(schemaStableAfterRollback));
            steps.add(step("verify_schema_stable_after_rollback",
                    schemaStableAfterRollback ? "OK" : "FAILED",
                    "Conteo de metodos/campos declarados no cambio tras el rollback."));

            out.put("rollbackApplied", Boolean.valueOf(true));
            out.put("rollbackOk", Boolean.valueOf(true));
            receipt.put("status", "OK");
            out.put("status", "ok");
            return out;
        } catch (Throwable t) {
            out.put("status", "failed");
            out.put("error", t.getClass().getSimpleName());
            out.put("message", String.valueOf(t.getMessage()));
            out.put("mutationApplied", Boolean.valueOf(mutationApplied));
            out.put("rollbackApplied", Boolean.valueOf(rollbackApplied));
            out.put("rollbackOk", Boolean.valueOf(rollbackOk));
            receipt.put("status", "FAILED");
            steps.add(step(mutationApplied ? "redefine_rollback_to_live_on_real_target"
                            : "redefine_content_mutation_on_real_target",
                    "FAILED", t.getClass().getSimpleName() + ": " + t.getMessage()));
            if (mutationApplied && !rollbackApplied) {
                try {
                    redefine(inst, target, liveBytes);
                    out.put("bestEffortRollbackOk", Boolean.TRUE);
                    steps.add(step("best_effort_rollback_to_live_on_real_target", "OK",
                            "Rollback de mejor esfuerzo tras excepcion en la mutacion."));
                } catch (Throwable t2) {
                    out.put("bestEffortRollbackOk", Boolean.FALSE);
                    out.put("bestEffortRollbackError", t2.getClass().getSimpleName() + ": " + t2.getMessage());
                    steps.add(step("best_effort_rollback_to_live_on_real_target", "FAILED",
                            t2.getClass().getSimpleName() + ": " + t2.getMessage()));
                }
            }
            return out;
        }
    }

    private static Map<String, Object> step(String action, String status, String detail) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("action", action);
        m.put("status", status);
        m.put("detail", detail);
        return m;
    }

    private static Class<?> resolveLoadedClass(Instrumentation inst, String dottedName) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c != null && dottedName.equals(c.getName())) return c;
        }
        return null;
    }

    private static void redefine(Instrumentation inst, Class<?> target, byte[] bytes) throws Exception {
        inst.redefineClasses(new ClassDefinition(target, bytes));
    }

    private static final class SourceFileLocation {
        final String value;
        /** offset absoluto en el array original donde empiezan los bytes UTF8 (tras el prefijo de 2 bytes de largo). */
        final int utf8BytesOffset;

        SourceFileLocation(String value, int utf8BytesOffset) {
            this.value = value;
            this.utf8BytesOffset = utf8BytesOffset;
        }
    }

    /**
     * Cursor manual sobre el array de bytes (en vez de DataInputStream) para
     * poder registrar el OFFSET absoluto de cada entrada UTF8 del constant
     * pool -- necesario para el patch quirurgico (ver javadoc de la clase).
     * Parseo minimo, propio y autocontenido: a proposito no reusa
     * Tier3MixinAudit.ClassFile (mismo criterio de aislamiento que
     * Tier3RedefSmoke). Devuelve null si el atributo SourceFile no existe
     * (bytecode sin informacion de debug, posible en builds de produccion).
     */
    private static SourceFileLocation locateSourceFile(byte[] bytes) throws IOException {
        Cursor c = new Cursor(bytes);
        if (c.u4() != 0xCAFEBABEL) throw new IOException("bad magic");
        c.u2(); // minor
        c.u2(); // major

        int cpCount = c.u2();
        String[] utf8 = new String[cpCount];
        int[] utf8Offsets = new int[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = c.u1();
            switch (tag) {
                case 1: {
                    int len = c.u2();
                    utf8Offsets[i] = c.pos;
                    utf8[i] = c.utf8(len);
                    break;
                }
                case 3: c.skip(4); break; // Integer
                case 4: c.skip(4); break; // Float
                case 5: c.skip(8); i++; break; // Long
                case 6: c.skip(8); i++; break; // Double
                case 7: case 8: case 16: c.skip(2); break; // Class/String/MethodType
                case 9: case 10: case 11: case 12: case 17: case 18: c.skip(4); break; // ref/NameAndType/Dynamic
                case 15: c.skip(3); break; // MethodHandle
                case 19: case 20: c.skip(2); break; // Module/Package
                default: throw new IOException("cp tag " + tag);
            }
        }

        c.u2(); // access_flags
        c.u2(); // this_class
        c.u2(); // super_class
        int ifaces = c.u2();
        c.skip(2 * ifaces);

        skipMembers(c);
        skipMembers(c);

        int classAttrs = c.u2();
        for (int i = 0; i < classAttrs; i++) {
            int nameIdx = c.u2();
            long len = c.u4();
            String attrName = nameIdx > 0 && nameIdx < utf8.length ? utf8[nameIdx] : null;
            if ("SourceFile".equals(attrName) && len == 2) {
                int sfIdx = c.u2();
                if (sfIdx <= 0 || sfIdx >= utf8.length || utf8[sfIdx] == null) return null;
                return new SourceFileLocation(utf8[sfIdx], utf8Offsets[sfIdx]);
            }
            c.skip((int) len);
        }
        return null;
    }

    private static void skipMembers(Cursor c) throws IOException {
        int count = c.u2();
        for (int i = 0; i < count; i++) {
            c.u2(); // access_flags
            c.u2(); // name_index
            c.u2(); // descriptor_index
            int attrs = c.u2();
            for (int j = 0; j < attrs; j++) {
                c.u2(); // attribute name_index
                long len = c.u4();
                c.skip((int) len);
            }
        }
    }

    /** Cursor de lectura big-endian sobre un array de bytes, exponiendo la posicion absoluta. */
    private static final class Cursor {
        final byte[] data;
        int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        int u1() throws IOException {
            require(1);
            return data[pos++] & 0xFF;
        }

        int u2() throws IOException {
            require(2);
            int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        long u4() throws IOException {
            require(4);
            long v = ((long) (data[pos] & 0xFF) << 24)
                    | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8)
                    | (data[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        String utf8(int len) throws IOException {
            require(len);
            String s = new String(data, pos, len, java.nio.charset.StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        void skip(int n) throws IOException {
            require(n);
            pos += n;
        }

        private void require(int n) throws IOException {
            if (pos + n > data.length || n < 0) throw new IOException("eof at " + pos);
        }
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    /** Same-length mutation: flip case del primer caracter alfabetico, o
     * sustituye el ultimo caracter por uno distinto si no hay ninguna letra. */
    private static String mutateSameLength(String s) {
        if (s.isEmpty()) return null;
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (Character.isLetter(c) && c < 0x80) {
                char flipped = Character.isUpperCase(c)
                        ? Character.toLowerCase(c)
                        : Character.toUpperCase(c);
                if (flipped != c) {
                    chars[i] = flipped;
                    return new String(chars);
                }
            }
        }
        char last = chars[chars.length - 1];
        char replacement = last == '_' ? '~' : '_';
        chars[chars.length - 1] = replacement;
        return new String(chars);
    }

    private static byte[] ascii(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) out[i] = (byte) s.charAt(i);
        return out;
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

    private Tier3RealTargetContentRedefineSmoke() {}
}
