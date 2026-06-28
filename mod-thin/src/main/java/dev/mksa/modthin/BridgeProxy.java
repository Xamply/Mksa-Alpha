package dev.mksa.modthin;

import dev.mksa.bridge.Bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Wrapper sobre {@link Bridge}. Llama a los metodos estaticos
 * {@code Bridge.modThinXxx} (estan en el bootstrap CL, visibles desde aqui sin
 * reflexion) y parsea la respuesta JSON con {@link JsonMini}.
 *
 * Las llamadas pueden bloquear hasta ~2s (sweep + dpReload se marshalizan al
 * server thread). Los callers DEBEN invocar desde un hilo distinto al hilo del
 * render del cliente — la UI provee {@code MinecraftClient.execute} para
 * volver al hilo de render con el resultado.
 */
final class BridgeProxy {

    private BridgeProxy() {}

    /** Devuelve la lista de mods o lista vacia si el agente aun no esta listo. */
    @SuppressWarnings("unchecked")
    static ListResult listMods() {
        String raw = Bridge.modThinListMods();
        Object parsed = safeParse(raw);
        ListResult r = new ListResult();
        r.raw = raw;
        if (!(parsed instanceof Map)) { r.error = "bad json"; return r; }
        Map<String, Object> m = (Map<String, Object>) parsed;
        r.ok = Boolean.TRUE.equals(m.get("ok"));
        r.epoch = asString(m.get("epoch"));
        if (!r.ok) { r.error = asString(m.get("error")); return r; }
        Object modsObj = m.get("mods");
        if (modsObj instanceof List) {
            List<Object> mods = (List<Object>) modsObj;
            for (Object mo : mods) {
                if (!(mo instanceof Map)) continue;
                Map<String, Object> mm = (Map<String, Object>) mo;
                ModEntry e = new ModEntry();
                e.id = asString(mm.get("id"));
                e.name = asString(mm.get("name"));
                e.version = asString(mm.get("version"));
                Object ns = mm.get("namespaces");
                e.namespaces = ns instanceof List ? toStringList((List<Object>) ns) : Collections.singletonList(e.id);
                e.tier = mm.get("tier") == null ? null : ((Number) mm.get("tier")).intValue();
                e.enabled = Boolean.TRUE.equals(mm.get("enabled"));
                // running = estado real para la UI. Si el agente no envia el campo
                // (version vieja del Bridge), caemos a enabled como en v0.
                Object running = mm.get("running");
                e.running = running == null ? e.enabled : Boolean.TRUE.equals(running);
                e.supportedDisable = Boolean.TRUE.equals(mm.get("supportedDisable"));
                Object files = mm.get("files");
                e.files = files instanceof List ? toStringList((List<Object>) files) : Collections.<String>emptyList();
                e.description = asString(mm.get("description"));
                // PNG en base64; decode una sola vez aquí para no decodificar en cada frame.
                String iconB64 = asString(mm.get("icon"));
                if (iconB64 != null && !iconB64.isEmpty()) {
                    try { e.iconPng = java.util.Base64.getDecoder().decode(iconB64); }
                    catch (Throwable ignored) { e.iconPng = null; }
                }
                r.mods.add(e);
            }
        }
        return r;
    }

    /** Desactiva el namespace. Bloquea hasta que el agente responde. */
    static ActionResult disable(String ns) { return actionReply(Bridge.modThinDisable(ns)); }

    /** Restaura el namespace. Bloquea hasta que el agente responde. */
    static ActionResult enable(String ns)  { return actionReply(Bridge.modThinEnable(ns)); }

    @SuppressWarnings("unchecked")
    private static ActionResult actionReply(String raw) {
        ActionResult r = new ActionResult();
        r.raw = raw;
        Object parsed = safeParse(raw);
        if (!(parsed instanceof Map)) { r.error = "bad json"; return r; }
        Map<String, Object> m = (Map<String, Object>) parsed;
        r.ok = Boolean.TRUE.equals(m.get("ok"));
        r.status = asString(m.get("status"));
        r.error = asString(m.get("error"));
        r.code = asString(m.get("code"));
        r.blocks = asLong(m.get("blocks"));
        r.chunks = asLong(m.get("chunks"));
        return r;
    }

    private static Object safeParse(String raw) {
        try { return JsonMini.parse(raw); }
        catch (Throwable t) { return null; }
    }

    private static String asString(Object o) { return o == null ? null : String.valueOf(o); }

    private static long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static List<String> toStringList(List<Object> in) {
        List<String> out = new ArrayList<String>(in.size());
        for (Object o : in) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    static final class ListResult {
        boolean ok;
        String epoch;
        String error;
        String raw;
        final List<ModEntry> mods = new ArrayList<ModEntry>();
    }

    static final class ActionResult {
        boolean ok;
        String status;
        String error;
        String code;
        long blocks;
        long chunks;
        String raw;
    }

    static final class ModEntry {
        String id;
        String name;
        String version;
        List<String> namespaces;
        Integer tier;             // null si no clasificado
        boolean enabled;          // loader-side (siempre true; compat)
        boolean running;          // true = activo; false = desactivado in-process
        boolean supportedDisable; // tier == 1 en este corte
        List<String> files;       // rutas absolutas de .jar; vacio si el agente no lo expone
        String description;       // null si el mod no la declara
        byte[] iconPng;           // PNG decodificado; null si no hay icono o falla decode
    }
}
