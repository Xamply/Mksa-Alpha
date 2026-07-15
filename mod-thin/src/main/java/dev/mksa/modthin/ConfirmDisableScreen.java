package dev.mksa.modthin;

import net.minecraft.class_310;     // MinecraftClient
import net.minecraft.class_332;     // DrawContext
import net.minecraft.class_4185;    // ButtonWidget
import net.minecraft.class_437;     // Screen
import net.minecraft.class_2561;    // Text

import java.util.ArrayList;
import java.util.List;

/**
 * Confirmación antes de desactivar un mod, con la vista previa de impacto en dos
 * capas (§3.30):
 *
 * <ol>
 *   <li><b>Hard-deps</b> — mods que DECLARAN depender de la víctima (inverso del
 *       grafo de dependencias del loader). El loader las ve; otros mods las
 *       esperan presentes.</li>
 *   <li><b>Cascada</b> — referencias cruzadas NO declaradas calculadas en vivo
 *       por wcg.refs: recetas rotas/debilitadas, tags afectados/vaciados, loot
 *       tables y los mods ajenos que las contienen. Es el daño silencioso que el
 *       loader no puede anticipar.</li>
 * </ol>
 *
 * <p>Sólo se abre cuando hay algo que advertir ({@link BridgeProxy.DepsResult#hasImpact()}).
 * Si el jugador confirma, se ejecuta {@code onConfirm} (que dispara el disable
 * real en el {@link ModsScreen}); si cancela, vuelve al panel sin tocar nada.
 * El botón "Restaurar" no pasa por aquí: reactivar nunca rompe nada.
 */
public final class ConfirmDisableScreen extends class_437 {

    private static final int PAD = 20;
    private static final int LINE_H = 11;

    private final class_437 parent;       // el ModsScreen que nos abrió
    private final String ns;
    private final String modName;
    private final BridgeProxy.PlanResult plan;
    private final BridgeProxy.DepsResult deps;
    private final Runnable onConfirm;

    public ConfirmDisableScreen(class_437 parent, String ns, String modName,
                                BridgeProxy.PlanResult plan, Runnable onConfirm) {
        super(class_2561.method_30163("MKSA · Confirmar desactivar"));
        this.parent = parent;
        this.ns = ns;
        this.modName = modName;
        this.plan = plan;
        this.deps = plan != null ? plan.deps : new BridgeProxy.DepsResult();
        this.onConfirm = onConfirm;
    }

    @Override
    protected void method_25426() {  // init()
        int btnW = 200;
        int gap = 8;
        int totalW = btnW * 2 + gap;
        int x0 = (this.field_22789 - totalW) / 2;
        int y = this.field_22790 - 36;

        class_4185 cancel = class_4185.method_46430(
                class_2561.method_30163("Cancelar"),
                b -> this.field_22787.method_1507(parent))
                .method_46434(x0, y, btnW, 20).method_46431();

        class_4185 confirm = class_4185.method_46430(
                class_2561.method_30163("Desactivar de todos modos"),
                b -> {
                    this.field_22787.method_1507(parent);
                    if (onConfirm != null) onConfirm.run();
                })
                .method_46434(x0 + btnW + gap, y, btnW, 20).method_46431();

        this.method_37063(cancel);
        this.method_37063(confirm);
    }

    /**
     * Construye las líneas de texto de la advertencia. Cada línea es {text, color}.
     * Se cortan las listas largas con "y N más" para no desbordar la pantalla.
     */
    private List<Line> buildLines() {
        List<Line> out = new ArrayList<Line>();
        out.add(new Line("Desactivar \"" + modName + "\" afectará:", 0xFFFFFFFF));
        out.add(Line.blank());

        // Capa 1 — dependencias declaradas.
        if (!deps.hardDeps.isEmpty()) {
            out.add(new Line("Mods que lo declaran como dependencia:", 0xFFFFD24A));
            int shown = 0;
            for (BridgeProxy.NamedMod m : deps.hardDeps) {
                if (shown >= 8) break;
                String name = (m.name != null && !m.name.isEmpty()) ? m.name : m.id;
                out.add(new Line("  · " + name, 0xFFE0C060));
                shown++;
            }
            int rest = deps.hardDeps.size() - shown;
            if (rest > 0) out.add(new Line("  · y " + rest + " más", 0xFFB0B0B0));
            out.add(Line.blank());
        }

        // Capa 2 — cascada wcg.refs.
        if (!deps.cascadeReady) {
            out.add(new Line("Impacto en contenido: no disponible todavía", 0xFFB0B0B0));
            String why = deps.cascadeError != null ? deps.cascadeError : "el mundo aún no está cargado";
            out.add(new Line("  (" + why + ")", 0xFF909090));
        } else {
            out.add(new Line("Impacto en contenido (recetas, tags, loot):", 0xFFFFD24A));
            if (deps.brokenRecipes > 0)
                out.add(new Line("  · Recetas que se rompen: " + deps.brokenRecipes, 0xFFE08080));
            if (deps.weakenedRecipes > 0)
                out.add(new Line("  · Recetas debilitadas: " + deps.weakenedRecipes, 0xFFE0C060));
            if (deps.affectedResultRecipes > 0)
                out.add(new Line("  · Recetas cuyo resultado cambia: " + deps.affectedResultRecipes, 0xFFE0C060));
            if (deps.affectedTagsTotal > 0) {
                String tags = "  · Tags afectados: " + deps.affectedTagsTotal;
                if (deps.emptiedTags > 0) tags += " (" + deps.emptiedTags + " vaciados)";
                out.add(new Line(tags, 0xFFC0C0E0));
            }
            if (deps.affectedLootTables > 0)
                out.add(new Line("  · Tablas de loot afectadas: " + deps.affectedLootTables, 0xFFC0C0E0));
            if (deps.brokenRecipes == 0 && deps.weakenedRecipes == 0
                    && deps.affectedResultRecipes == 0 && deps.affectedTagsTotal == 0
                    && deps.affectedLootTables == 0)
                out.add(new Line("  · Sin referencias cruzadas detectadas", 0xFF90C090));

            if (!deps.owners.isEmpty()) {
                out.add(Line.blank());
                out.add(new Line("Mods con contenido afectado:", 0xFFFFD24A));
                int shown = 0;
                for (BridgeProxy.OwnerImpact o : deps.owners) {
                    if (shown >= 8) break;
                    String name = (o.name != null && !o.name.isEmpty()) ? o.name : o.ns;
                    List<String> parts = new ArrayList<String>();
                    if (o.broken > 0) parts.add("rompe " + o.broken);
                    if (o.weakened > 0) parts.add("debilita " + o.weakened);
                    if (o.affectedResult > 0) parts.add("resultado " + o.affectedResult);
                    if (o.lootAffected > 0) parts.add("loot " + o.lootAffected);
                    String detail = parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
                    out.add(new Line("  · " + name + detail, 0xFFC0C0C0));
                    shown++;
                }
                int rest = deps.owners.size() - shown;
                if (rest > 0) out.add(new Line("  · y " + rest + " mods más", 0xFFB0B0B0));
            }
        }

        out.add(Line.blank());
        out.add(new Line("Podrás restaurarlo cuando quieras, sin perder nada.", 0xFF90C090));
        return out;
    }

    @Override
    public void method_25394(class_332 ctx, int mouseX, int mouseY, float delta) {
        super.method_25394(ctx, mouseX, mouseY, delta);

        String title = "¿Desactivar este mod?";
        int tw = this.field_22793.method_1727(title);
        ctx.method_25303(this.field_22793, title, (this.field_22789 - tw) / 2, 14, 0xFFFFFFFF);

        int y = 40;
        for (Line ln : buildLines()) {
            if (ln.text != null && !ln.text.isEmpty()) {
                ctx.method_25303(this.field_22793, ln.text, PAD, y, ln.color);
            }
            y += LINE_H;
        }
    }

    @Override
    public boolean method_25422() {  // shouldPause
        return false;
    }

    private static final class Line {
        final String text;
        final int color;
        Line(String text, int color) { this.text = text; this.color = color; }
        static Line blank() { return new Line("", 0xFFFFFFFF); }
    }
}
