import { defineConfig } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";

// Config estándar de Tauri: puerto fijo para devUrl, sin limpiar pantalla
// para no tapar los errores de Rust.
export default defineConfig({
  plugins: [svelte()],
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
  },
});
