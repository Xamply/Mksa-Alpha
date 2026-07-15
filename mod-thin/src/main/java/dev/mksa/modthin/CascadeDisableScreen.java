package dev.mksa.modthin;

import net.minecraft.class_310;     // MinecraftClient
import net.minecraft.class_332;     // DrawContext
import net.minecraft.class_4185;    // ButtonWidget
import net.minecraft.class_437;     // Screen
import net.minecraft.class_2561;    // Text

import java.util.ArrayList;
import java.util.List;

/**
 * Confirmación de cascada (§forward): al desactivar un mod, sus dependencias
 * PROPIAS que quedarían huérfanas (sin ningún otro mod corriendo que las
 * necesite) se muestran aquí como parte OBLIGATORIA de la acción — no son
 * casillas que el jugador pueda desmarcar, se apagan siempre junto al mod.
 *
 * <p>Distinto de {@link ConfirmDisableScreen} (que advierte sobre impacto —
 * hard-deps inversas y cascada de contenido — y permite igual seguir
 * adelante): aquí no hay "de todos modos", solo confirmar el grupo completo o
 * cancelar. Si alguna dependencia del grupo no es ejecutable todavía (p.ej.
 * Tier 3 sin auditar), el botón de confirmar queda deshabilitado — nada se
 * aplica a medias.
 */
public final class CascadeDisableScreen extends class_437 {

    private static final int PAD = 20;
    private static final int LINE_H = 11;

    private final class_437 parent;
    private final String ns;
    private final String modName;
    private final List<BridgeProxy.CascadeTarget> targets;
    private final Runnable onConfirm;
    private final boolean allExecutable;

    public CascadeDisableScreen(class_437 parent, String ns, String modName,
                                List<BridgeProxy.CascadeTarget> targets, Runnable onConfirm) {
        super(class_2561.method_30163("MKSA · Confirmar dependencias"));
        this.parent = parent;
        this.ns = ns;
        this.modName = modName;
        this.targets = targets;
        this.onConfirm = onConfirm;
        boolean all = true;
        for (BridgeProxy.CascadeTarget t : targets) if (!t.executable) { all = false; break; }
        this.allExecutable = all;
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
                class_2561.method_30163("Desactivar todo junto"),
                b -> {
                    this.field_22787.method_1507(parent);
                    if (onConfirm != null) onConfirm.run();
                })
                .method_46434(x0 + btnW + gap, y, btnW, 20).method_46431();
        confirm.field_22763 = allExecutable;

        this.method_37063(cancel);
        this.method_37063(confirm);
    }

    private List<Line> buildLines() {
        List<Line> out = new ArrayList<Line>();
        out.add(new Line("Desactivar \"" + modName + "\" tambien apagara:", 0xFFFFFFFF));
        out.add(Line.blank());
        out.add(new Line("Requerido, no se puede omitir:", 0xFFFFD24A));
        for (BridgeProxy.CascadeTarget t : targets) {
            String name = (t.name != null && !t.name.isEmpty()) ? t.name : t.ns;
            if (t.executable) {
                out.add(new Line("  [x] " + name, 0xFFB0B0B0));
            } else {
                out.add(new Line("  [x] " + name + " (bloqueado: requiere auditoria Tier 3 aun no disponible)",
                        0xFFE08080));
            }
        }
        out.add(Line.blank());
        if (allExecutable) {
            out.add(new Line("Podras restaurarlos cuando quieras, sin perder nada.", 0xFF90C090));
        } else {
            out.add(new Line("No se puede continuar: al menos una dependencia esta bloqueada.", 0xFFE08080));
        }
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
