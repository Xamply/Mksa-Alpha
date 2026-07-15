package dev.mksa.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Perfil semantico determinista por mod.
 *
 * No adivina con IA ni mira red externos. Solo combina:
 * - evidencia del WCG (definiciones registradas por namespace),
 * - metadata disponible del mod (nombre / descripcion / tier),
 * - y un conjunto pequeno de reglas explicitas para convertir eso en una
 *   lectura humana: que agrega, que toca y que tan util es para entender el mod.
 */
final class ModSemantics {

    private static final String[] BUILDING_HINTS = {
            "stairs", "slab", "wall", "fence", "roof", "pillar", "beam",
            "balcony", "railing", "trim", "brick", "plank", "log", "wood"
    };

    private static final String[] WORLDGEN_HINTS = {
            "biome", "worldgen", "terrain", "feature", "structure", "noise",
            "placed_feature", "configured_feature", "surface", "dimension"
    };

    private static final String[] MOBS_HINTS = {
            "mob", "mobs", "entity", "creature", "animal", "monster", "boss"
    };

    private static final String[] UI_HINTS = {
            "ui", "hud", "menu", "screen", "overlay", "config", "client",
            "render", "tooltip", "keybind", "pause"
    };

    private static final String[] TECH_HINTS = {
            "machine", "automation", "tech", "energy", "power", "cable",
            "transport", "pipe", "factory", "system"
    };

    private ModSemantics() {}

    static void annotate(List<Map<String, Object>> mods,
                         Map<String, Map<String, List<String>>> definitions) {
        if (mods == null || mods.isEmpty()) return;
        Map<String, Map<String, List<String>>> defs =
                definitions != null ? definitions : Collections.<String, Map<String, List<String>>>emptyMap();
        for (Map<String, Object> mod : mods) {
            if (mod == null) continue;
            String ns = firstNamespace(mod);
            if (ns == null || ns.isEmpty()) continue;
            Map<String, Object> profile = build(mod, ns, defs);
            if (!profile.isEmpty()) {
                mod.put("semantic", profile);
            }
        }
    }

    private static Map<String, Object> build(Map<String, Object> mod,
                                             String ns,
                                             Map<String, Map<String, List<String>>> defs) {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        Map<String, List<String>> evidence = new LinkedHashMap<String, List<String>>();

        for (Map.Entry<String, Map<String, List<String>>> regEntry : defs.entrySet()) {
            Map<String, List<String>> byNs = regEntry.getValue();
            if (byNs == null) continue;
            List<String> ids = byNs.get(ns);
            if (ids == null || ids.isEmpty()) continue;

            String family = familyOf(regEntry.getKey());
            long prev = counts.containsKey(family) ? counts.get(family).longValue() : 0L;
            counts.put(family, Long.valueOf(prev + ids.size()));
            if (!evidence.containsKey(family)) {
                evidence.put(family, new ArrayList<String>());
            }
            addSamples(evidence.get(family), regEntry.getKey(), ids);
        }

        String name = asString(mod.get("name"));
        String description = asString(mod.get("description"));
        Integer tier = mod.get("tier") instanceof Integer ? (Integer) mod.get("tier") : null;

        String focus = focus(counts, name, description, tier);
        List<String> capabilities = capabilities(counts, description, tier);
        List<String> signals = signals(counts, evidence, name, description);
        List<String> surfaces = surfaces(counts, name, description, tier);
        List<String> risks = disableRisks(counts, name, description, tier);
        List<String> evidenceNotes = evidenceNotes(counts, evidence, name, description, tier);
        String role = gameplayRole(counts, focus, name, description);
        String summary = summary(focus, counts, name, description, evidence);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("focus", focus);
        out.put("role", role);
        out.put("summary", summary);
        out.put("capabilities", capabilities);
        out.put("surfaces", surfaces);
        out.put("riskLabels", risks);
        out.put("signals", signals);
        out.put("evidenceNotes", evidenceNotes);
        out.put("counts", counts);
        out.put("evidence", evidence);
        return out;
    }

    private static String focus(Map<String, Long> counts, String name, String description, Integer tier) {
        String text = joinText(name, description);
        long worldgen = score(counts, "biomes", 6, "worldgen", 4)
                + keywordScore(text, WORLDGEN_HINTS) * 2;
        long mobs = score(counts, "entities", 6) + keywordScore(text, MOBS_HINTS) * 2;
        long building = score(counts, "blocks", 4, "items", 2)
                + keywordScore(text, BUILDING_HINTS) * 2;
        long data = score(counts, "recipes", 4, "tags", 3, "loot", 3);
        long ui = keywordScore(text, UI_HINTS) * 3;
        long tech = keywordScore(text, TECH_HINTS) * 3;
        long runtime = tier != null && tier.intValue() >= 2 ? 4 : 0;

        if (worldgen >= mobs && worldgen >= building && worldgen >= data && worldgen >= ui && worldgen >= tech) {
            return "worldgen";
        }
        if (mobs >= building && mobs >= data && mobs >= ui && mobs >= tech) {
            return "mobs";
        }
        if (ui >= tech && ui >= data && ui >= building && ui >= mobs) {
            return "ui";
        }
        if (tech >= data && tech >= building && tech >= mobs) {
            return "tech";
        }
        if (data >= building && data >= mobs) {
            return "data";
        }
        if (building > 0) {
            return "building";
        }
        if (runtime > 0) {
            return "runtime";
        }
        return "mixed";
    }

    private static List<String> capabilities(Map<String, Long> counts, String description, Integer tier) {
        List<String> out = new ArrayList<String>();
        if (value(counts, "blocks") > 0) out.add("bloques");
        if (value(counts, "items") > 0) out.add("items");
        if (value(counts, "entities") > 0) out.add("mobs");
        if (value(counts, "biomes") > 0 || value(counts, "worldgen") > 0) out.add("biomas/worldgen");
        if (value(counts, "recipes") > 0 || value(counts, "tags") > 0 || value(counts, "loot") > 0) {
            out.add("datos/crafting");
        }
        if (tier != null && tier.intValue() >= 2) out.add("runtime");
        if (matchesAny(joinText(description), UI_HINTS)) out.add("ui");
        if (out.isEmpty()) out.add("mixto");
        return out;
    }

    private static List<String> surfaces(Map<String, Long> counts,
                                         String name,
                                         String description,
                                         Integer tier) {
        String text = joinText(name, description);
        List<String> out = new ArrayList<String>();
        if (value(counts, "blocks") > 0) out.add("registry:block");
        if (value(counts, "items") > 0) out.add("registry:item");
        if (value(counts, "entities") > 0) out.add("registry:entity");
        if (value(counts, "biomes") > 0 || value(counts, "worldgen") > 0
                || matchesAny(text, WORLDGEN_HINTS)) {
            out.add("worldgen/biomes");
        }
        if (value(counts, "recipes") > 0) out.add("datapack:recipes");
        if (value(counts, "tags") > 0) out.add("datapack:tags");
        if (value(counts, "loot") > 0) out.add("datapack:loot");
        if (matchesAny(text, UI_HINTS)) out.add("client/ui");
        if (tier != null && tier.intValue() >= 2) out.add("runtime hooks");
        if (tier != null && tier.intValue() >= 3) out.add("mixins/coremods");
        if (out.isEmpty()) out.add("sin superficie fuerte observada");
        return out;
    }

    private static List<String> disableRisks(Map<String, Long> counts,
                                             String name,
                                             String description,
                                             Integer tier) {
        String text = joinText(name, description);
        List<String> out = new ArrayList<String>();
        if (value(counts, "blocks") > 0) out.add("bloques colocados requieren restore");
        if (value(counts, "items") > 0) out.add("items/inventarios pueden requerir captura");
        if (value(counts, "entities") > 0) out.add("entidades vivas requieren politica de despawn/restore");
        if (value(counts, "biomes") > 0 || value(counts, "worldgen") > 0
                || matchesAny(text, WORLDGEN_HINTS)) {
            out.add("worldgen/biomas puede dejar impacto persistente");
        }
        if (value(counts, "recipes") > 0 || value(counts, "tags") > 0 || value(counts, "loot") > 0) {
            out.add("datapacks pueden afectar recetas/tags/loot ajenos");
        }
        if (tier != null && tier.intValue() == 2) out.add("runtime refs pueden quedar residuales");
        if (tier != null && tier.intValue() >= 3) out.add("Tier 3 requiere demixineo/hot-relaunch");
        if (out.isEmpty()) out.add("riesgo bajo observado; confirmar con DisablePlan");
        return out;
    }

    private static List<String> evidenceNotes(Map<String, Long> counts,
                                              Map<String, List<String>> evidence,
                                              String name,
                                              String description,
                                              Integer tier) {
        List<String> out = new ArrayList<String>();
        if (!counts.isEmpty()) out.add("WCG definitions: " + joinCounts(counts, 5));
        String text = joinText(name, description);
        if (matchesAny(text, BUILDING_HINTS)) out.add("metadata keyword: building variants");
        if (matchesAny(text, WORLDGEN_HINTS)) out.add("metadata keyword: worldgen/biome");
        if (matchesAny(text, MOBS_HINTS)) out.add("metadata keyword: mob/entity");
        if (matchesAny(text, UI_HINTS)) out.add("metadata keyword: client/ui");
        if (matchesAny(text, TECH_HINTS)) out.add("metadata keyword: tech/automation");
        if (tier != null) out.add("tier evidence: T" + tier.intValue());
        for (Map.Entry<String, List<String>> e : evidence.entrySet()) {
            if (!e.getValue().isEmpty()) {
                out.add("sample " + e.getKey() + ": " + joinLimited(e.getValue(), 2));
            }
            if (out.size() >= 8) break;
        }
        if (out.isEmpty()) out.add("sin evidencia suficiente fuera de metadata minima");
        return out;
    }

    private static String gameplayRole(Map<String, Long> counts,
                                       String focus,
                                       String name,
                                       String description) {
        String text = joinText(name, description);
        if ("worldgen".equals(focus)) return "world generation / exploration";
        if ("mobs".equals(focus)) return "mobs and encounters";
        if ("building".equals(focus)) {
            if (matchesAny(text, BUILDING_HINTS)) return "decorative building variants";
            return "building/content pack";
        }
        if ("data".equals(focus)) return "data/crafting integration";
        if ("ui".equals(focus)) return "client utility / interface";
        if ("tech".equals(focus)) return "automation / technical systems";
        if ("runtime".equals(focus)) return "runtime behavior";
        if (value(counts, "blocks") > 0 || value(counts, "items") > 0) return "mixed content";
        return "insufficient evidence";
    }

    private static List<String> signals(Map<String, Long> counts,
                                        Map<String, List<String>> evidence,
                                        String name,
                                        String description) {
        List<String> out = new ArrayList<String>();
        addSignal(out, "blocks", counts);
        addSignal(out, "items", counts);
        addSignal(out, "entities", counts);
        addSignal(out, "biomes", counts);
        addSignal(out, "worldgen", counts);
        addSignal(out, "recipes", counts);
        addSignal(out, "loot", counts);
        if (matchesAny(joinText(name, description), BUILDING_HINTS)) {
            out.add("variants: stairs/slab/wall/fence");
        }
        if (matchesAny(joinText(name, description), WORLDGEN_HINTS)) {
            out.add("worldgen keywords presentes");
        }
        if (matchesAny(joinText(name, description), MOBS_HINTS)) {
            out.add("mob/entity keywords presentes");
        }
        for (Map.Entry<String, List<String>> e : evidence.entrySet()) {
            if (!e.getValue().isEmpty()) {
                out.add(e.getKey() + ": " + joinLimited(e.getValue(), 3));
            }
            if (out.size() >= 8) break;
        }
        return out;
    }

    private static String summary(String focus,
                                  Map<String, Long> counts,
                                  String name,
                                  String description,
                                  Map<String, List<String>> evidence) {
        long blocks = value(counts, "blocks");
        long items = value(counts, "items");
        long entities = value(counts, "entities");
        long biomes = value(counts, "biomes");
        long worldgen = value(counts, "worldgen");
        long recipes = value(counts, "recipes");
        long loot = value(counts, "loot");
        long tags = value(counts, "tags");

        String text = joinText(name, description);
        boolean buildingVariants = matchesAny(text, BUILDING_HINTS) && blocks > 0;

        if ("worldgen".equals(focus)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Mueve biomas y worldgen");
            appendCounts(sb, blocks, items, entities, biomes, worldgen, recipes, loot, tags);
            return sb.toString();
        }
        if ("mobs".equals(focus)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Agrega mobs/entidades y su contenido asociado");
            appendCounts(sb, blocks, items, entities, biomes, worldgen, recipes, loot, tags);
            return sb.toString();
        }
        if ("building".equals(focus)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Agrega bloques de construccion y variantes relacionadas");
            if (buildingVariants) sb.append(" (stairs/slab/wall/fence)");
            appendCounts(sb, blocks, items, entities, biomes, worldgen, recipes, loot, tags);
            return sb.toString();
        }
        if ("data".equals(focus)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Ajusta recetas, tags y loot mas que el contenido base");
            appendCounts(sb, blocks, items, entities, biomes, worldgen, recipes, loot, tags);
            return sb.toString();
        }
        if ("ui".equals(focus)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Se centra en interfaz o utilidades de cliente");
            appendCounts(sb, blocks, items, entities, biomes, worldgen, recipes, loot, tags);
            return sb.toString();
        }
        if ("tech".equals(focus)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Apunta a automatizacion o sistemas tecnicos");
            appendCounts(sb, blocks, items, entities, biomes, worldgen, recipes, loot, tags);
            return sb.toString();
        }
        if ("runtime".equals(focus)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Su peso visible es pequeno; la huella real esta en runtime");
            appendCounts(sb, blocks, items, entities, biomes, worldgen, recipes, loot, tags);
            return sb.toString();
        }

        if (blocks > 0 || items > 0 || entities > 0 || biomes > 0 || worldgen > 0 || recipes > 0 || loot > 0 || tags > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("Contenido mixto");
            appendCounts(sb, blocks, items, entities, biomes, worldgen, recipes, loot, tags);
            return sb.toString();
        }

        if (description != null && !description.isEmpty()) {
            return description;
        }
        return "Sin señales semanticas suficientes";
    }

    private static void appendCounts(StringBuilder sb, long blocks, long items, long entities,
                                     long biomes, long worldgen, long recipes, long loot, long tags) {
        List<String> parts = new ArrayList<String>();
        if (blocks > 0) parts.add("bloques " + blocks);
        if (items > 0) parts.add("items " + items);
        if (entities > 0) parts.add("mobs " + entities);
        if (biomes > 0) parts.add("biomas " + biomes);
        if (worldgen > 0) parts.add("worldgen " + worldgen);
        if (recipes > 0) parts.add("recetas " + recipes);
        if (loot > 0) parts.add("loot " + loot);
        if (tags > 0) parts.add("tags " + tags);
        if (!parts.isEmpty()) {
            sb.append(" | ");
            sb.append(joinLimited(parts, 4));
        }
    }

    private static void addSignal(List<String> out, String key, Map<String, Long> counts) {
        long v = value(counts, key);
        if (v > 0) out.add(key + ": " + v);
    }

    private static long value(Map<String, Long> counts, String key) {
        Long v = counts.get(key);
        return v == null ? 0L : v.longValue();
    }

    private static long score(Map<String, Long> counts, Object... keyWeightPairs) {
        long out = 0L;
        for (int i = 0; i + 1 < keyWeightPairs.length; i += 2) {
            String key = String.valueOf(keyWeightPairs[i]);
            long weight = ((Number) keyWeightPairs[i + 1]).longValue();
            out += value(counts, key) * weight;
        }
        return out;
    }

    private static String familyOf(String reg) {
        String lower = reg == null ? "" : reg.toLowerCase(Locale.ROOT);
        if (lower.contains("biome")) return "biomes";
        if (lower.contains("configured_feature") || lower.contains("placed_feature") || lower.endsWith("feature")) {
            return "worldgen";
        }
        if (lower.contains("structure")) return "worldgen";
        if (lower.contains("entity")) return "entities";
        if (lower.contains("block")) return "blocks";
        if (lower.contains("item")) return "items";
        if (lower.contains("recipe")) return "recipes";
        if (lower.contains("loot")) return "loot";
        if (lower.contains("tag")) return "tags";
        return "other";
    }

    private static void addSamples(List<String> out, String reg, List<String> ids) {
        int room = Math.max(0, 4 - out.size());
        if (room == 0) return;
        int limit = Math.min(room, ids.size());
        for (int i = 0; i < limit; i++) {
            String id = ids.get(i);
            if (id == null || id.isEmpty()) continue;
            out.add(reg + "=" + id);
        }
    }

    private static int keywordScore(String text, String[] hints) {
        if (text == null || text.isEmpty()) return 0;
        int score = 0;
        for (String hint : hints) {
            if (text.contains(hint)) score++;
        }
        return score;
    }

    private static boolean matchesAny(String text, String[] hints) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String hint : hints) {
            if (lower.contains(hint)) return true;
        }
        return false;
    }

    private static String joinText(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(part.toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static String joinCounts(Map<String, Long> counts, int max) {
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (e.getValue() != null && e.getValue().longValue() > 0) {
                parts.add(e.getKey() + "=" + e.getValue().longValue());
            }
            if (parts.size() >= max) break;
        }
        return joinLimited(parts, max);
    }

    private static String joinLimited(List<String> items, int max) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(max, items.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        if (items.size() > limit) sb.append(" +").append(items.size() - limit);
        return sb.toString();
    }

    private static String firstNamespace(Map<String, Object> mod) {
        Object nsList = mod.get("namespaces");
        if (nsList instanceof List) {
            for (Object o : (List<?>) nsList) {
                if (o != null) {
                    String s = String.valueOf(o);
                    if (!s.isEmpty()) return s;
                }
            }
        }
        Object id = mod.get("id");
        return id == null ? null : String.valueOf(id);
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
