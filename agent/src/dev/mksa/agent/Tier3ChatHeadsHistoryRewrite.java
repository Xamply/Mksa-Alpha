package dev.mksa.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * T3 corte AN: reescritura retroactiva del historial de chat de chat_heads al
 * des/activar el grupo -- cumple la regla universal guardada en memoria de
 * proyecto (feedback_retroactive_rerender_on_toggle): al desactivar un mod no
 * pueden quedar rastros visuales de su estado previo, y al reactivar deben
 * restaurarse por completo, incluso en objetos/historial ya existentes.
 *
 * Version 2 de este mecanismo (la v1, basada en cirugia sobre el Component
 * tree via ComponentProcessor.split/join, resulto estar resolviendo el
 * problema equivocado -- ver abajo). Confirmado por javap real contra el jar
 * del mod y el jar vainilla: el modo de render activo es BEFORE_LINE, y el
 * gate real que decide si se pinta el icono es exactamente
 *
 *   headData = ChatHeads.getHeadData(class_303$class_7590 linea)
 *   if (ChatHeads.guiGraphics != null && headData != HeadData.EMPTY) { ... }
 *
 * (igualdad de REFERENCIA contra HeadData.EMPTY, via checkcast incondicional
 * a HeadRenderable -- no instanceof). Esto vive en class_338$1 (clase interna
 * anonima de ChatComponent), que NO esta en CHAT_HEADS_TARGETS -- el
 * disable/enable de bytecode nunca toca ese call site, disable o no. El UNICO
 * lever real sobre si un mensaje ya existente muestra cabeza es el VALOR del
 * campo chatheads$headData en los objetos ya vivos: class_303 (field_2061 de
 * ChatHud, el mensaje fuente) y class_303$class_7590 (field_2064, la linea
 * ya envuelta que efectivamente se pinta).
 *
 * Por eso class_303 y class_303$class_7590 quedaron deliberadamente FUERA de
 * CHAT_HEADS_TARGETS (ver Tier3DemixApply): nunca se les resetea el bytecode,
 * asi que su campo/interfaz @Unique chatheads$headData jamas desaparece --
 * cero riesgo de ClassCastException en el checkcast incondicional de
 * class_338$1, y cero riesgo de que el campo vuelva con el default de Java
 * (null) en vez de HeadData.EMPTY al reactivar (la misma clase de bug que
 * corte AM resolvio para class_11228#rendererPools via PRESERVE_FIELDS, pero
 * evitado aca de raiz en vez de parcheado despues). En cambio, este mecanismo
 * toma control deterministico y directo del VALOR del campo via reflexion,
 * en ambas listas de ChatHud (fuente y ya-renderizada), sin depender de
 * disparar el pipeline de reconstruccion interno del mod (method_1817/
 * method_44813) -- ese pipeline resulto depender a su vez de que
 * ChatComponentMixin (sobre class_338, que SI se REPLAYea al desactivar
 * chat_heads) siga activo, lo cual no es una garantia estable entre
 * disable/enable.
 *
 * El campo chatheads$headData es publico en ambas clases (confirmado por
 * javap) y class_303 ademas expone chatheads$setHeadData(HeadData) -- pero
 * como class_303$class_7590 solo expone el getter, se usa Field.set()
 * directo de forma uniforme para ambas, en vez de mezclar metodo+reflexion.
 */
final class Tier3ChatHeadsHistoryRewrite {

    private static final String CHAT_HUD_CLASS = "net.minecraft.class_338";
    private static final String HEAD_DATA_FIELD = "chatheads$headData";

    /** HeadData original cacheado por identidad del objeto (class_303 o
     * class_303$class_7590) al que se le vacio el campo -- permite restaurar
     * exacto sin recalcular nada. Ambos tipos comparten el mismo mapa: la
     * identidad de referencia ya alcanza para desambiguar. */
    private static final IdentityHashMap<Object, Object> ORIGINAL_HEAD_DATA =
            new IdentityHashMap<Object, Object>();

    private Tier3ChatHeadsHistoryRewrite() {
    }

    /** Direccion de desactivar -- corre DESPUES de que redefineClasses() ya
     * tuvo exito para el resto del grupo (class_303/class_303$class_7590 no
     * forman parte de ese redefine, asi que no hay ventana de inconsistencia
     * que ordenar aca). */
    static void stripHeadsFromHistory(Map<String, Class<?>> classes, List<String> warningsOut, List<String> rewrittenOut) {
        stripHeadsFromHistory(classes, warningsOut, rewrittenOut, new ArrayList<String>());
    }

    /** Sobrecarga con diagnostico explicito -- corte AN-2: instrumentacion para
     * aislar por que un rewrite reportado como exitoso (rewrittenOut no vacio,
     * cero warnings) no produce el efecto visual esperado. Cruza el ChatHud
     * hallado por BFS contra el unico camino canonico deterministico
     * (Boot.clientInstance.field_1705.method_1743()), verifica con una
     * relectura inmediata que cada escritura de campo realmente prendio, y
     * adjunta una vista previa del texto del mensaje tocado. */
    static void stripHeadsFromHistory(Map<String, Class<?>> classes, List<String> warningsOut, List<String> rewrittenOut, List<String> diagnosticsOut) {
        Object chatHud = locateChatHud(classes, warningsOut, diagnosticsOut);
        if (chatHud == null) {
            return;
        }
        stripList(readListField(chatHud, "field_2061", warningsOut), "field_2061", warningsOut, rewrittenOut, diagnosticsOut);
        stripList(readListField(chatHud, "field_2064", warningsOut), "field_2064", warningsOut, rewrittenOut, diagnosticsOut);
    }

    /** Direccion de reactivar -- simetrica, corre DESPUES de redefineClasses(). */
    static void restoreHeadsToHistory(Map<String, Class<?>> classes, List<String> warningsOut, List<String> rewrittenOut) {
        restoreHeadsToHistory(classes, warningsOut, rewrittenOut, new ArrayList<String>());
    }

    static void restoreHeadsToHistory(Map<String, Class<?>> classes, List<String> warningsOut, List<String> rewrittenOut, List<String> diagnosticsOut) {
        Object chatHud = locateChatHud(classes, warningsOut, diagnosticsOut);
        if (chatHud == null) {
            return;
        }
        restoreList(readListField(chatHud, "field_2061", warningsOut), "field_2061", warningsOut, rewrittenOut, diagnosticsOut);
        restoreList(readListField(chatHud, "field_2064", warningsOut), "field_2064", warningsOut, rewrittenOut, diagnosticsOut);
    }

    private static Object locateChatHud(Map<String, Class<?>> classes, List<String> warningsOut, List<String> diagnosticsOut) {
        Class<?> chatHudClass = classes.get(CHAT_HUD_CLASS);
        if (chatHudClass == null) {
            return null; // el grupo no incluye chat_heads (otro namespace) -- no es advertencia
        }
        Object chatHud = Tier3InstanceLocator.locate(chatHudClass);
        if (chatHud == null) {
            warningsOut.add("historyRewrite: no se pudo localizar una instancia viva de " + CHAT_HUD_CLASS);
            return null;
        }
        diagnoseCanonicalMatch(chatHud, diagnosticsOut);
        return chatHud;
    }

    /** Diagnostico corte AN-2: el BFS de Tier3InstanceLocator es una heuristica
     * (primer match por clase, acotado por profundidad/visitados) -- esto
     * cruza su resultado contra el UNICO camino canonico y deterministico
     * hacia el mismo objeto (Boot.clientInstance -&gt; field_1705/InGameHud
     * -&gt; method_1743()/getChatHud()) para descartar de raiz que el BFS este
     * mutando una instancia de ChatHud distinta a la que realmente se
     * renderiza en pantalla. */
    private static void diagnoseCanonicalMatch(Object bfsChatHud, List<String> diagnosticsOut) {
        try {
            Object client = Boot.clientInstance;
            if (client == null) {
                diagnosticsOut.add("historyRewrite/diag: Boot.clientInstance es null, no se pudo cruzar contra el canonico");
                return;
            }
            Field hudField = findDeclaredField(client.getClass(), "field_1705");
            hudField.setAccessible(true);
            Object inGameHud = hudField.get(client);
            if (inGameHud == null) {
                diagnosticsOut.add("historyRewrite/diag: field_1705 (InGameHud) es null en Boot.clientInstance");
                return;
            }
            Method getChatHud = findMethod(inGameHud.getClass(), "method_1743");
            Object canonical = getChatHud.invoke(inGameHud);
            boolean same = canonical == bfsChatHud;
            diagnosticsOut.add("historyRewrite/diag: ChatHud BFS vs canonico "
                    + (same ? "COINCIDEN (misma identidad)" : "NO COINCIDEN -- posible instancia equivocada")
                    + " [bfsIdentity=" + System.identityHashCode(bfsChatHud)
                    + ", canonicalIdentity=" + (canonical == null ? "null" : String.valueOf(System.identityHashCode(canonical))) + "]");
        } catch (Throwable t) {
            diagnosticsOut.add("historyRewrite/diag: fallo cruzando contra el ChatHud canonico ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }
    }

    private static void stripList(List<Object> list, String label, List<String> warningsOut, List<String> rewrittenOut, List<String> diagnosticsOut) {
        if (list == null) {
            return;
        }
        int idx = 0;
        for (Object entry : list) {
            try {
                if (!ORIGINAL_HEAD_DATA.containsKey(entry)) {
                    Object headData = getHeadData(entry);
                    if (headData != null && !isEmptyHeadData(headData)) {
                        ORIGINAL_HEAD_DATA.put(entry, headData);
                        Object empty = emptyHeadData(headData.getClass());
                        setHeadData(entry, empty);
                        rewrittenOut.add(label + "#" + idx);
                        Object readBack = getHeadData(entry);
                        diagnosticsOut.add("historyRewrite/diag: strip " + label + "#" + idx
                                + " readBackOk=" + (readBack == empty) + previewText(entry));
                    }
                }
            } catch (Throwable t) {
                warningsOut.add("historyRewrite: fallo quitando cabeza de " + label + "#" + idx + " ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            }
            idx++;
        }
    }

    private static void restoreList(List<Object> list, String label, List<String> warningsOut, List<String> rewrittenOut, List<String> diagnosticsOut) {
        if (list == null) {
            return;
        }
        int idx = 0;
        for (Object entry : list) {
            try {
                Object original = ORIGINAL_HEAD_DATA.remove(entry);
                if (original != null) {
                    setHeadData(entry, original);
                    rewrittenOut.add(label + "#" + idx);
                    Object readBack = getHeadData(entry);
                    diagnosticsOut.add("historyRewrite/diag: restore " + label + "#" + idx
                            + " readBackOk=" + (readBack == original) + previewText(entry));
                }
            } catch (Throwable t) {
                warningsOut.add("historyRewrite: fallo restaurando cabeza de " + label + "#" + idx + " ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            }
            idx++;
        }
    }

    /** Vista previa del texto del mensaje tocado -- comp_893() en class_303,
     * comp_896() en class_303$class_7590 (la version ya envuelta en linea).
     * Puramente diagnostico: cualquier fallo se reporta inline y nunca aborta
     * el rewrite. */
    private static String previewText(Object entry) {
        try {
            Object content;
            try {
                content = findMethod(entry.getClass(), "comp_893").invoke(entry);
            } catch (NoSuchMethodException nsme) {
                content = findMethod(entry.getClass(), "comp_896").invoke(entry);
            }
            String text;
            try {
                text = String.valueOf(findMethod(content.getClass(), "getString").invoke(content));
            } catch (NoSuchMethodException nsme) {
                text = String.valueOf(content);
            }
            if (text.length() > 60) {
                text = text.substring(0, 60) + "...";
            }
            return " text=\"" + text + "\"";
        } catch (Throwable t) {
            return " text=<no-disponible:" + t.getClass().getSimpleName() + ">";
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> readListField(Object chatHud, String fieldName, List<String> warningsOut) {
        try {
            Field f = findDeclaredField(chatHud.getClass(), fieldName);
            f.setAccessible(true);
            Object listObj = f.get(chatHud);
            if (!(listObj instanceof List)) {
                warningsOut.add("historyRewrite: " + fieldName + " no es un List (tipo real: "
                        + (listObj == null ? "null" : listObj.getClass().getName()) + ")");
                return null;
            }
            return (List<Object>) listObj;
        } catch (Throwable t) {
            warningsOut.add("historyRewrite: no se pudo leer " + fieldName + " ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            return null;
        }
    }

    private static Object getHeadData(Object entry) throws Exception {
        Field f = findDeclaredField(entry.getClass(), HEAD_DATA_FIELD);
        f.setAccessible(true);
        return unsafeGetObjectVolatile(entry, f);
    }

    /** Corte AN-3: escritura con semantica volatile via sun.misc.Unsafe, no
     * Field.set() plano. Motivo (root cause real, no hipotesis): este
     * rewrite corre en el hilo de la conexion IPC/bridge (ver Agent.dispatch,
     * cada Conn tiene su propio Thread), mientras que quien LEE
     * chatheads$headData es el hilo de render de Minecraft -- un hilo
     * completamente distinto. redefineClasses() SI fuerza un safepoint (por
     * tanto una barrera de memoria global), pero ese safepoint ocurre ANTES
     * de este metodo, no despues -- la escritura reflexiva del campo pasa a
     * ser un Field.set() ordinario, sin ningun happens-before hacia el hilo
     * de render. El JMM no garantiza que esa escritura se vuelva visible para
     * otro hilo en ningun plazo determinado (ni el read-back same-thread de
     * los diagnosticos lo detecta, porque un mismo hilo siempre ve sus
     * propias escrituras en orden). putObjectVolatile fuerza una barrera
     * store/load real en el hilo que escribe, dandole al valor una
     * publicacion cross-thread fiable sin depender de que el campo del mod
     * (chatheads$headData) haya sido declarado volatile -- no lo controlamos,
     * es bytecode generado por Mixin. */
    private static void setHeadData(Object entry, Object headData) throws Exception {
        Field f = findDeclaredField(entry.getClass(), HEAD_DATA_FIELD);
        f.setAccessible(true);
        unsafePutObjectVolatile(entry, f, headData);
    }

    private static Object unsafeInstance;
    private static Method objectFieldOffsetMethod;
    private static Method putObjectVolatileMethod;
    private static Method getObjectVolatileMethod;

    /** sun.misc.Unsafe se obtiene 100% por reflexion (Class.forName +
     * campos/metodos genericos) a proposito -- el build compila con
     * --release 8, que no expone sun.misc en el ct.sym restringido, asi que
     * una referencia directa al tipo Unsafe no compilaria. Este mismo patron
     * (nunca declarar el tipo real en una variable, solo Object/Method) ya se
     * usa en todo este archivo para las clases obfuscadas de Minecraft. */
    private static synchronized void ensureUnsafe() throws Exception {
        if (unsafeInstance != null) {
            return;
        }
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        unsafeInstance = theUnsafe.get(null);
        objectFieldOffsetMethod = unsafeClass.getMethod("objectFieldOffset", Field.class);
        putObjectVolatileMethod = unsafeClass.getMethod("putObjectVolatile", Object.class, long.class, Object.class);
        getObjectVolatileMethod = unsafeClass.getMethod("getObjectVolatile", Object.class, long.class);
    }

    private static void unsafePutObjectVolatile(Object target, Field f, Object value) throws Exception {
        ensureUnsafe();
        long offset = ((Long) objectFieldOffsetMethod.invoke(unsafeInstance, f)).longValue();
        putObjectVolatileMethod.invoke(unsafeInstance, target, Long.valueOf(offset), value);
    }

    private static Object unsafeGetObjectVolatile(Object target, Field f) throws Exception {
        ensureUnsafe();
        long offset = ((Long) objectFieldOffsetMethod.invoke(unsafeInstance, f)).longValue();
        return getObjectVolatileMethod.invoke(unsafeInstance, target, Long.valueOf(offset));
    }

    /** Replica exacta del gate de render confirmado por bytecode: igualdad de
     * REFERENCIA contra HeadData.EMPTY, no .equals() ni un campo derivado
     * como hasHeadPosition() (que en modo BEFORE_LINE es irrelevante). */
    private static boolean isEmptyHeadData(Object headData) throws Exception {
        return headData == emptyHeadData(headData.getClass());
    }

    private static Object emptyHeadData(Class<?> headDataClass) throws Exception {
        return headDataClass.getField("EMPTY").get(null);
    }

    private static Field findDeclaredField(Class<?> start, String name) throws NoSuchFieldException {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // sigue subiendo por la jerarquia
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Igual que findDeclaredField pero para metodos sin parametros -- prueba
     * getMethod() primero (camino comun, publico) y si falla busca por la
     * jerarquia con getDeclaredMethod()+setAccessible (por si el metodo real
     * no es public). */
    private static Method findMethod(Class<?> start, String name) throws NoSuchMethodException {
        try {
            return start.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            for (Class<?> c = start; c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored2) {
                    // sigue subiendo por la jerarquia
                }
            }
            throw new NoSuchMethodException(name);
        }
    }
}
