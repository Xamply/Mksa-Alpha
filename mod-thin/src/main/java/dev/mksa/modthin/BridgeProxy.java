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
                e.supportedDisable = Boolean.TRUE.equals(mm.get("supportedDisable"));
                e.toggleState = asString(mm.get("toggleState"));
                if (e.toggleState == null) e.toggleState = e.running ? "active" : "inactive_verified";
                e.toggleCapability = asString(mm.get("toggleCapability"));
                if (e.toggleCapability == null) e.toggleCapability = "ready";
                e.mechanism = asString(mm.get("mechanism"));
                e.semantic = parseSemantic(mm.get("semantic"));
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

    /**
     * Cascade disable (§forward): dependencias PROPIAS de {@code ns} que quedarian
     * huerfanas si se apaga. Bloquea; llamar fuera del hilo de render.
     */
    @SuppressWarnings("unchecked")
    static CascadeResult cascadeTargets(String ns) {
        CascadeResult r = new CascadeResult();
        String raw;
        try { raw = Bridge.modThinCascadeTargets(ns); }
        catch (Throwable t) { r.error = "no_cascade_api"; return r; }
        r.raw = raw;
        Object parsed = safeParse(raw);
        if (!(parsed instanceof Map)) { r.error = "bad json"; return r; }
        Map<String, Object> m = (Map<String, Object>) parsed;
        r.ok = Boolean.TRUE.equals(m.get("ok"));
        if (!r.ok) { r.error = asString(m.get("error")); return r; }
        Object targetsObj = m.get("targets");
        if (targetsObj instanceof List) {
            for (Object o : (List<Object>) targetsObj) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> tm = (Map<String, Object>) o;
                CascadeTarget t = new CascadeTarget();
                t.ns = asString(tm.get("ns"));
                t.id = asString(tm.get("id"));
                t.name = asString(tm.get("name"));
                Object tier = tm.get("tier");
                t.tier = tier instanceof Number ? Integer.valueOf(((Number) tier).intValue()) : null;
                t.executable = Boolean.TRUE.equals(tm.get("executable"));
                r.targets.add(t);
            }
        }
        return r;
    }

    /**
     * Desactiva {@code ns} (raiz primero) + sus dependencias huerfanas como una
     * sola operacion. Bloquea; llamar fuera del hilo de render.
     */
    static ActionResult disableGroup(List<String> nsList) {
        return actionReply(Bridge.modThinDisableGroup(String.join(",", nsList)));
    }

    /**
     * Vista previa de impacto (§3.30): hard-deps declaradas + cascada wcg.refs.
     * Bloquea (la cascada se marshaliza al server thread); llamar fuera del hilo
     * de render. Si el Bridge es viejo y no expone modThinDeps, devuelve un
     * resultado vacío (sin deps, cascada no lista) en vez de explotar.
     */
    @SuppressWarnings("unchecked")
    static DepsResult deps(String ns) {
        DepsResult r = new DepsResult();
        String raw;
        try { raw = Bridge.modThinDeps(ns); }
        catch (Throwable t) { r.error = "no_deps_api"; return r; }
        r.raw = raw;
        Object parsed = safeParse(raw);
        if (!(parsed instanceof Map)) { r.error = "bad json"; return r; }
        Map<String, Object> m = (Map<String, Object>) parsed;
        r.ok = Boolean.TRUE.equals(m.get("ok"));
        if (!r.ok) { r.error = asString(m.get("error")); return r; }
        Object hd = m.get("hardDeps");
        if (hd instanceof List) {
            for (Object o : (List<Object>) hd) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> hm = (Map<String, Object>) o;
                NamedMod nm = new NamedMod();
                nm.id = asString(hm.get("id"));
                nm.name = asString(hm.get("name"));
                r.hardDeps.add(nm);
            }
        }
        Object cObj = m.get("cascade");
        if (cObj instanceof Map) {
            Map<String, Object> c = (Map<String, Object>) cObj;
            r.cascadeReady = Boolean.TRUE.equals(c.get("ready"));
            r.cascadeError = asString(c.get("error"));
            r.brokenRecipes = asLong(c.get("brokenRecipes"));
            r.weakenedRecipes = asLong(c.get("weakenedRecipes"));
            r.affectedResultRecipes = asLong(c.get("affectedResultRecipes"));
            r.affectedTagsTotal = asLong(c.get("affectedTagsTotal"));
            r.emptiedTags = asLong(c.get("emptiedTags"));
            r.affectedLootTables = asLong(c.get("affectedLootTables"));
            r.affectedOwners = asLong(c.get("affectedOwners"));
            Object ow = c.get("owners");
            if (ow instanceof List) {
                for (Object o : (List<Object>) ow) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> om = (Map<String, Object>) o;
                    OwnerImpact oi = new OwnerImpact();
                    oi.ns = asString(om.get("ns"));
                    oi.name = asString(om.get("name"));
                    oi.broken = asLong(om.get("broken"));
                    oi.weakened = asLong(om.get("weakened"));
                    oi.affectedResult = asLong(om.get("affectedResult"));
                    oi.lootAffected = asLong(om.get("lootAffected"));
                    r.owners.add(oi);
                }
            }
        }
        return r;
    }

    /** Corte 5: DisablePlan compacto calculado por el agente antes de mutar. */
    @SuppressWarnings("unchecked")
    static PlanResult plan(String ns) {
        PlanResult r = new PlanResult();
        String raw;
        try {
            java.lang.reflect.Method m = Bridge.class.getMethod("modThinPlan", String.class);
            raw = String.valueOf(m.invoke(null, ns));
        }
        catch (Throwable t) { r.error = "no_plan_api"; return r; }
        r.raw = raw;
        Object parsed = safeParse(raw);
        if (!(parsed instanceof Map)) { r.error = "bad json"; return r; }
        Map<String, Object> m = (Map<String, Object>) parsed;
        r.ok = Boolean.TRUE.equals(m.get("ok"));
        if (!r.ok) { r.error = asString(m.get("error")); r.code = asString(m.get("code")); return r; }
        r.ns = asString(m.get("ns"));
        r.decision = asString(m.get("decision"));
        r.requiresRestart = Boolean.TRUE.equals(m.get("requiresRestart"));
        r.restorePath = asString(m.get("restorePath"));
        r.rollbackAvailable = Boolean.TRUE.equals(m.get("rollbackAvailable"));
        Object tier = m.get("tier");
        r.tier = tier instanceof Number ? Integer.valueOf(((Number) tier).intValue()) : null;
        Object counts = m.get("counts");
        if (counts instanceof Map) {
            Map<String, Object> cm = (Map<String, Object>) counts;
            r.blocks = asLong(cm.get("blocks"));
            r.chunks = asLong(cm.get("chunks"));
            r.runtimeActiveRefs = asLong(cm.get("runtimeActiveRefs"));
        }
        readReasons(m.get("askReasons"), r.askReasons);
        readReasons(m.get("blockedReasons"), r.blockedReasons);
        readReasons(m.get("requiresRestartReasons"), r.requiresRestartReasons);
        readSurfaces(m.get("coveredSurfaces"), r.coveredSurfaces);
        readSurfaces(m.get("residualSurfaces"), r.residualSurfaces);
        Object depsObj = m.get("deps");
        if (depsObj instanceof Map) parseDeps((Map<String, Object>) depsObj, r.deps);
        Object t3 = m.get("t3MixinPlan");
        if (t3 instanceof Map) r.t3MixinPlan = (Map<String, Object>) t3;
        return r;
    }

    @SuppressWarnings("unchecked")
    private static void parseDeps(Map<String, Object> m, DepsResult r) {
        r.ok = Boolean.TRUE.equals(m.get("ok"));
        Object hd = m.get("hardDeps");
        if (hd instanceof List) {
            for (Object o : (List<Object>) hd) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> hm = (Map<String, Object>) o;
                NamedMod nm = new NamedMod();
                nm.id = asString(hm.get("id"));
                nm.name = asString(hm.get("name"));
                r.hardDeps.add(nm);
            }
        }
        Object cObj = m.get("cascade");
        if (cObj instanceof Map) {
            Map<String, Object> c = (Map<String, Object>) cObj;
            r.cascadeReady = Boolean.TRUE.equals(c.get("ready"));
            r.cascadeError = asString(c.get("error"));
            r.brokenRecipes = asLong(c.get("brokenRecipes"));
            r.weakenedRecipes = asLong(c.get("weakenedRecipes"));
            r.affectedResultRecipes = asLong(c.get("affectedResultRecipes"));
            r.affectedTagsTotal = asLong(c.get("affectedTagsTotal"));
            r.emptiedTags = asLong(c.get("emptiedTags"));
            r.affectedLootTables = asLong(c.get("affectedLootTables"));
            r.affectedOwners = asLong(c.get("affectedOwners"));
            Object ow = c.get("owners");
            if (ow instanceof List) {
                for (Object o : (List<Object>) ow) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> om = (Map<String, Object>) o;
                    OwnerImpact oi = new OwnerImpact();
                    oi.ns = asString(om.get("ns"));
                    oi.name = asString(om.get("name"));
                    oi.broken = asLong(om.get("broken"));
                    oi.weakened = asLong(om.get("weakened"));
                    oi.affectedResult = asLong(om.get("affectedResult"));
                    oi.lootAffected = asLong(om.get("lootAffected"));
                    r.owners.add(oi);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void readReasons(Object obj, List<Reason> out) {
        if (!(obj instanceof List)) return;
        for (Object o : (List<Object>) obj) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            Reason r = new Reason();
            r.code = asString(m.get("code"));
            r.detail = asString(m.get("detail"));
            out.add(r);
        }
    }

    @SuppressWarnings("unchecked")
    private static void readSurfaces(Object obj, List<Surface> out) {
        if (!(obj instanceof List)) return;
        for (Object o : (List<Object>) obj) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            Surface s = new Surface();
            s.id = asString(m.get("id"));
            s.detail = asString(m.get("detail"));
            out.add(s);
        }
    }

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
        r.runtimeRemoved = asLong(m.get("runtimeRemoved"));
        r.runtimeRestored = asLong(m.get("runtimeRestored"));
        r.runtimeErrors = asLong(m.get("runtimeErrors"));
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

    @SuppressWarnings("unchecked")
    private static SemanticProfile parseSemantic(Object obj) {
        if (!(obj instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) obj;
        SemanticProfile p = new SemanticProfile();
        p.focus = asString(m.get("focus"));
        p.summary = asString(m.get("summary"));
        p.role = asString(m.get("role"));
        Object caps = m.get("capabilities");
        if (caps instanceof List) p.capabilities = toStringList((List<Object>) caps);
        Object surfaces = m.get("surfaces");
        if (surfaces instanceof List) p.surfaces = toStringList((List<Object>) surfaces);
        Object risks = m.get("riskLabels");
        if (risks instanceof List) p.riskLabels = toStringList((List<Object>) risks);
        Object sigs = m.get("signals");
        if (sigs instanceof List) p.signals = toStringList((List<Object>) sigs);
        Object notes = m.get("evidenceNotes");
        if (notes instanceof List) p.evidenceNotes = toStringList((List<Object>) notes);
        Object counts = m.get("counts");
        if (counts instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) counts).entrySet()) {
                p.counts.put(e.getKey(), asLong(e.getValue()));
            }
        }
        return p;
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
        long runtimeRemoved;
        long runtimeRestored;
        long runtimeErrors;
        String raw;
    }

    static final class NamedMod {
        String id;
        String name;
    }

    static final class CascadeTarget {
        String ns;
        String id;
        String name;
        Integer tier;
        boolean executable;
    }

    static final class CascadeResult {
        boolean ok;
        String error;
        String raw;
        final List<CascadeTarget> targets = new ArrayList<CascadeTarget>();
    }

    static final class OwnerImpact {
        String ns;
        String name;
        long broken;
        long weakened;
        long affectedResult;
        long lootAffected;
    }

    static final class DepsResult {
        boolean ok;
        String error;
        String raw;
        final List<NamedMod> hardDeps = new ArrayList<NamedMod>();
        boolean cascadeReady;
        String cascadeError;
        long brokenRecipes;
        long weakenedRecipes;
        long affectedResultRecipes;
        long affectedTagsTotal;
        long emptiedTags;
        long affectedLootTables;
        long affectedOwners;
        final List<OwnerImpact> owners = new ArrayList<OwnerImpact>();

        /** true si hay algo que mostrar al jugador (deps duras o cascada con impacto). */
        boolean hasImpact() {
            if (!hardDeps.isEmpty()) return true;
            return cascadeReady && (brokenRecipes > 0 || weakenedRecipes > 0
                    || affectedResultRecipes > 0 || affectedTagsTotal > 0
                    || affectedLootTables > 0 || !owners.isEmpty());
        }
    }

    static final class Reason {
        String code;
        String detail;
    }

    static final class Surface {
        String id;
        String detail;
    }

    static final class PlanResult {
        boolean ok;
        String error;
        String code;
        String raw;
        String ns;
        String decision;
        Integer tier;
        boolean requiresRestart;
        boolean rollbackAvailable;
        String restorePath;
        long blocks;
        long chunks;
        long runtimeActiveRefs;
        final List<Reason> askReasons = new ArrayList<Reason>();
        final List<Reason> blockedReasons = new ArrayList<Reason>();
        final List<Reason> requiresRestartReasons = new ArrayList<Reason>();
        final List<Surface> coveredSurfaces = new ArrayList<Surface>();
        final List<Surface> residualSurfaces = new ArrayList<Surface>();
        final DepsResult deps = new DepsResult();
        Map<String, Object> t3MixinPlan;

        boolean isAuto() { return "auto".equals(decision); }
        boolean isAsk() { return "ask".equals(decision); }
        boolean executable() { return isAuto() || isAsk(); }
    }

    static final class ModEntry {
        String id;
        String name;
        String version;
        List<String> namespaces;
        Integer tier;             // null si no clasificado
        boolean enabled;          // loader-side (siempre true; compat)
        boolean running;          // true = activo; false = desactivado in-process
        boolean supportedDisable; // compat
        String toggleState;       // active, inactive_verified, etc.
        String toggleCapability;  // ready, analyzing, needs_adapter, runtime_unsupported
        String mechanism;         // tier3_shape_preserving_demix, etc.
        List<String> files;       // rutas absolutas de .jar; vacio si el agente no lo expone
        String description;       // null si el mod no la declara
        SemanticProfile semantic; // perfil semantico derivado del WCG
        byte[] iconPng;           // PNG decodificado; null si no hay icono o falla decode
    }

    static final class SemanticProfile {
        String focus;
        String role;
        String summary;
        List<String> capabilities = Collections.emptyList();
        List<String> surfaces = Collections.emptyList();
        List<String> riskLabels = Collections.emptyList();
        List<String> signals = Collections.emptyList();
        List<String> evidenceNotes = Collections.emptyList();
        final java.util.Map<String, Long> counts = new java.util.LinkedHashMap<String, Long>();
    }
}
