package dev.mksa.modthin;

import net.fabricmc.api.ClientModInitializer;

/**
 * Punto de entrada del mod thin. Solo registra los hooks; toda la logica vive
 * en {@link PauseScreenHook} y {@link ModsScreen}. La inteligencia real (Tier 1
 * in-process: sweep + filter + dpReload + restitucion viva) vive en el agente,
 * a un Bridge.modThinXxx() de distancia (proyecto.md §4, modelo IPC).
 */
public final class ModThinClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PauseScreenHook.register();
    }
}
