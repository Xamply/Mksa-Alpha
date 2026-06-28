package dev.mksa.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plano A del WCG: qué define cada namespace, por registro (proyecto §4).
 * Inmutable por época; esta primera versión se construye una vez, cuando los
 * registros ya están congelados (la instancia del cliente existe y el conteo
 * se ha estabilizado; ver Boot).
 *
 * Descubrimiento 100% estructural — sin nombres de campos ni métodos por
 * versión, que cambian; solo dos nombres de clase estables por diseño:
 * el contenedor de registros (class_7923 / class_2378 en intermediary, o sus
 * equivalentes mojmap) y el tipo Identifier (class_2960 / ResourceLocation).
 * El método getId se reconoce por su firma: 1 parámetro, devuelve Identifier.
 * El registro raíz se reconoce porque sus entradas son a su vez registros.
 */
final class Wcg {

    /** reg ("minecraft:block") → namespace → paths. */
    static volatile Map<String, Map<String, List<String>>> definitions;
    static volatile int totalEntries;
    /** true solo tras la estabilización: hay builds intermedios durante el arranque. */
    static volatile boolean ready;

    private static final String[] HOLDER_CLASSES = {
            "net.minecraft.class_7923",                       // BuiltInRegistries (1.19.3+)
            "net.minecraft.class_2378",                       // Registry (los estáticos vivían aquí antes)
            "net.minecraft.core.registries.BuiltInRegistries",// mojmap (forge/neoforge moderno)
            "net.minecraft.core.Registry",
    };

    private static final String[] ID_CLASSES = {
            "net.minecraft.class_2960",                       // Identifier (intermediary)
            "net.minecraft.resources.ResourceLocation",       // mojmap
    };

    /** Construye el Plano A. Lanza si no encuentra los registros (boot.failed arriba). */
    static void build(Instrumentation inst) throws Exception {
        Object[] r = findRoot(inst);
        if (r == null) throw new IllegalStateException("registro raíz no identificado");
        Object root = r[0];
        Method rootGetId = (Method) r[1];

        Map<String, Map<String, List<String>>> defs =
                new LinkedHashMap<String, Map<String, List<String>>>();
        int total = 0;
        Map<Class<?>, Method> getIdCache = new LinkedHashMap<Class<?>, Method>();
        for (Object reg : (Iterable<?>) root) {
            Object regIdObj = rootGetId.invoke(root, reg);
            if (regIdObj == null) continue;
            String regId = regIdObj.toString();
            Method getId = getIdCache.get(reg.getClass());
            if (getId == null) {
                getId = findGetId(reg.getClass());
                if (getId == null) continue;
                getIdCache.put(reg.getClass(), getId);
            }
            Map<String, List<String>> byNs = new LinkedHashMap<String, List<String>>();
            for (Object entry : (Iterable<?>) reg) {
                Object idObj;
                try { idObj = getId.invoke(reg, entry); } catch (Throwable t) { continue; }
                if (idObj == null) continue;
                String id = idObj.toString();
                int colon = id.indexOf(':');
                String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
                String path = colon < 0 ? id : id.substring(colon + 1);
                List<String> paths = byNs.get(ns);
                if (paths == null) { paths = new ArrayList<String>(); byNs.put(ns, paths); }
                paths.add(path);
                total++;
            }
            if (!byNs.isEmpty()) defs.put(regId, byNs);
        }
        totalEntries = total;
        definitions = defs;
    }

    /**
     * Registro raíz vivo + su getId. El raíz es el registro cuyas entradas son a
     * su vez registros (iterables con getId). Devuelve {root, rootGetId} o null.
     * Varias clases marcadoras pueden estar cargadas a la vez (p. ej. en mojmap,
     * la interfaz Registry y la clase BuiltInRegistries): solo una declara de
     * verdad los campos, así que se prueban todas.
     */
    private static Object[] findRoot(Instrumentation inst) {
        List<Class<?>> holders = findLoaded(inst, HOLDER_CLASSES);
        for (Class<?> holder : holders) {
            // Bajo loaders con módulos (NeoForge/Forge) los registros viven en el
            // módulo 'minecraft', no abierto a nadie: sin esto setAccessible lanza.
            Agent.openModule(inst, holder);
            for (java.lang.reflect.Field f : holder.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v;
                try { v = f.get(null); } catch (Throwable t) { continue; }
                if (!(v instanceof Iterable)) continue;
                Method getId = findGetId(v.getClass());
                if (getId == null) continue;
                Object first = firstOf((Iterable<?>) v);
                if (first instanceof Iterable && findGetId(first.getClass()) != null) {
                    return new Object[] { v, getId };
                }
            }
        }
        return null;
    }

    /**
     * Registro vivo con id {@code wantRegId} (p. ej. "minecraft:block") + su getId,
     * o null. Lo usa el {@link Ledger} para construir el índice de bloques modded
     * y resolver BlockState→Block. Reutiliza el descubrimiento estructural del raíz.
     */
    static Object[] liveRegistry(Instrumentation inst, String wantRegId) throws Exception {
        Object[] r = findRoot(inst);
        if (r == null) return null;
        Object root = r[0];
        Method rootGetId = (Method) r[1];
        for (Object reg : (Iterable<?>) root) {
            Object idObj = rootGetId.invoke(root, reg);
            if (idObj == null || !wantRegId.equals(idObj.toString())) continue;
            Method getId = findGetId(reg.getClass());
            if (getId == null) return null;
            return new Object[] { reg, getId };
        }
        return null;
    }

    /** getId estructural: público, 1 parámetro, devuelve Identifier/ResourceLocation. */
    private static Method findGetId(Class<?> cls) {
        for (Method m : cls.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 1) continue;
            String ret = m.getReturnType().getName();
            for (String idc : ID_CLASSES) {
                if (ret.equals(idc)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static Object firstOf(Iterable<?> it) {
        for (Object o : it) return o;
        return null;
    }

    private static List<Class<?>> findLoaded(Instrumentation inst, String[] names) {
        List<Class<?>> out = new ArrayList<Class<?>>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName();
            for (String want : names) {
                if (n.equals(want)) { out.add(c); break; }
            }
        }
        return out;
    }

    /**
     * wcg.definitions { ns, reg?, page? } → { items: [{reg,id}…], next } (§4.2, §4.6).
     * Cursor opaco = índice plano en la enumeración determinista del mapa.
     */
    static Map<String, Object> query(String ns, String reg, String cursor, long limit) {
        Map<String, Map<String, List<String>>> defs = definitions;
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        long skip = 0;
        try { if (cursor != null) skip = Long.parseLong(cursor); } catch (NumberFormatException ignored) {}
        if (limit <= 0 || limit > 1000) limit = limit <= 0 ? 500 : 1000;

        long index = 0;
        boolean more = false;
        outer:
        for (Map.Entry<String, Map<String, List<String>>> r : defs.entrySet()) {
            if (reg != null && !reg.equals(r.getKey())) continue;
            List<String> paths = r.getValue().get(ns);
            if (paths == null) continue;
            for (String p : paths) {
                if (index++ < skip) continue;
                if (items.size() >= limit) { more = true; break outer; }
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("reg", r.getKey());
                item.put("id", ns + ":" + p);
                items.add(item);
            }
        }
        Map<String, Object> ok = new LinkedHashMap<String, Object>();
        ok.put("items", items);
        ok.put("next", more ? String.valueOf(skip + items.size()) : null);
        return ok;
    }

    private Wcg() {}
}
