package dev.mksa.modthin;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.class_2561;   // Text
import net.minecraft.class_2960;   // Identifier
import net.minecraft.class_339;    // ClickableWidget
import net.minecraft.class_433;    // PauseScreen (extiende Screen directo en 1.21.11)
import net.minecraft.class_7919;   // Tooltip
import net.minecraft.class_8662;   // TextIconButtonWidget

import java.util.List;

/**
 * Engancha {@link ScreenEvents#AFTER_INIT}. Cuando la pantalla es PauseScreen
 * ({@code class_433}), inyecta un boton dogito 20×20 a la DERECHA del boton
 * "Open to LAN" (mismo y, x = ese.right + 4). Es la columna derecha de la fila
 * inferior del menu en layout vanilla + Mod Menu.
 *
 * <p>Para no hardcodear el y (que depende de si Mod Menu insertó la fila "Mods"
 * o no, y de cualquier otro mod que toque el layout), localizamos
 * dinamicamente el boton de la columna derecha (x ≈ width/2 + 4) con la y mas
 * grande (=fila mas baja) tras AFTER_INIT — ese es "Open to LAN" en vanilla,
 * la fila inferior derecha en cualquier layout razonable. Si no hay candidatos
 * (raro), fallback a la esquina superior derecha.
 */
public final class PauseScreenHook {

    private static final class_2960 DOGITO_SPRITE = class_2960.method_60655("mksa", "dogito");

    private PauseScreenHook() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof class_433)) return;
            try {
                class_8662 btn = new class_8662.class_8663(
                        class_2561.method_30163(""),
                        b -> client.method_1507(new ModsScreen(screen)),
                        true
                )
                        .method_52727(DOGITO_SPRITE, 16, 16)
                        .method_52725(20)
                        .method_52724();

                int[] pos = nextToOpenToLan(screen, w, h);
                btn.method_46421(pos[0]);
                btn.method_46419(pos[1]);
                btn.method_47400(class_7919.method_47407(
                        class_2561.method_30163("MKSA · Mods")));
                Screens.getButtons(screen).add(btn);
            } catch (Throwable t) {
                System.err.println("[mksa_thin] fallo creando el boton de pausa: " + t);
                t.printStackTrace();
            }
        });
    }

    /**
     * Devuelve {x, y} junto al "Open to LAN" (columna derecha, fila inferior).
     * Fallback: esquina superior derecha si no hay ningun candidato.
     */
    private static int[] nextToOpenToLan(Object screen, int w, int h) {
        // Boton vanilla de columna derecha empieza en width/2 + 2 (98px ancho).
        // Aceptamos ±8px de tolerancia por si algun mod lo desplaza.
        int rightColTarget = w / 2 + 2;
        class_339 best = null;
        for (class_339 child : Screens.getButtons((net.minecraft.class_437) screen)) {
            int x = child.method_46426();
            if (Math.abs(x - rightColTarget) > 8) continue;       // no es columna derecha
            int width = child.method_25368();
            if (width < 60 || width > 110) continue;              // no es boton estandar de 98
            if (best == null || child.method_46427() > best.method_46427()) best = child;
        }
        if (best == null) return new int[]{ w - 28, 8 };          // fallback esquina
        int x = best.method_46426() + best.method_25368() + 4;
        int y = best.method_46427();
        return new int[]{ x, y };
    }
}
