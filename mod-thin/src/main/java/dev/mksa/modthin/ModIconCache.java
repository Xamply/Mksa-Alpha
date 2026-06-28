package dev.mksa.modthin;

import net.minecraft.class_1011;   // NativeImage
import net.minecraft.class_1043;   // NativeImageBackedTexture
import net.minecraft.class_1060;   // TextureManager
import net.minecraft.class_2960;   // Identifier
import net.minecraft.class_310;    // MinecraftClient

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Cachea las texturas dinamicas de los iconos de mods, indexadas por modId.
 *
 * <p>El icono viene del agente como PNG en bytes (Boot.java extrae el path
 * relativo de la metadata del loader y lo lee del jar montado). Aqui lo
 * decodificamos a {@link class_1011} (NativeImage), lo envolvemos en un
 * {@link class_1043} (NativeImageBackedTexture) y lo registramos en el
 * {@link class_1060} (TextureManager) bajo un {@link class_2960} estable por
 * modId. La identidad la dibuja {@code ctx.method_25290} con el pipeline
 * GUI_TEXTURED.
 *
 * <p>Vida: la cache es proceso-larga (se reusan entre aperturas del panel);
 * los TextureManager.registerTexture sobreescriben sin liberar, asi que
 * no recreamos texturas para el mismo modId.
 */
final class ModIconCache {

    static final class Entry {
        final class_2960 id;
        final int width;
        final int height;
        Entry(class_2960 id, int w, int h) { this.id = id; this.width = w; this.height = h; }
    }

    private static final Map<String, Entry> CACHE = new HashMap<String, Entry>();
    /** Marcador para evitar reintentar mods cuyo PNG falla decode. */
    private static final Entry FAILED = new Entry(null, 0, 0);
    private static volatile Path logFile;
    private static volatile boolean logInitialized;

    private ModIconCache() {}

    /**
     * Inicia (si hace falta) el archivo de log para diagnostico de iconos del
     * cliente. Usa la system property {@code mksa.dir} que el launcher inyecta,
     * misma carpeta donde el agente escribe {@code mod-icons.log}.
     */
    private static synchronized void initLog() {
        if (logInitialized) return;
        logInitialized = true;
        String dir = System.getProperty("mksa.dir");
        if (dir == null || dir.isEmpty()) return;
        logFile = Paths.get(dir).resolve("mod-icons-client.log");
        try { Files.write(logFile, new byte[0]); } catch (Throwable ignored) {}
    }

    private static synchronized void log(String line) {
        initLog();
        if (logFile == null) return;
        String full = "[" + java.time.LocalTime.now() + "] " + line + "\n";
        try {
            Files.write(logFile, full.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {}
    }

    /**
     * Devuelve la entrada cacheada o registra una nueva si pngBytes es valido.
     * Llamar siempre desde el hilo del render (TextureManager no es thread-safe).
     */
    static Entry getOrRegister(String modId, byte[] pngBytes) {
        if (modId == null || modId.isEmpty()) return null;
        Entry e = CACHE.get(modId);
        if (e != null) return e == FAILED ? null : e;
        if (pngBytes == null || pngBytes.length == 0) {
            CACHE.put(modId, FAILED);
            log("SKIP '" + modId + "' pngBytes=" + (pngBytes == null ? "null" : "empty"));
            return null;
        }
        try {
            class_1011 img = class_1011.method_4309(new ByteArrayInputStream(pngBytes));
            int w = img.method_4307();
            int h = img.method_4323();
            class_2960 id = class_2960.method_60655("mksa", "mod-icon/" + sanitize(modId));
            class_1043 tex = new class_1043(() -> "mksa/" + modId, img);
            class_310 mc = class_310.method_1551();
            if (mc == null) {
                // Render llamado fuera del hilo del cliente: no registramos, reintentamos despues.
                img.close();
                log("DEFER '" + modId + "' mc=null");
                return null;
            }
            class_1060 tm = mc.method_1531();
            tm.method_4616(id, tex);
            Entry ne = new Entry(id, w, h);
            CACHE.put(modId, ne);
            log("OK '" + modId + "' " + w + "x" + h + " bytes=" + pngBytes.length + " id=" + id);
            return ne;
        } catch (Throwable t) {
            CACHE.put(modId, FAILED);
            log("FAIL '" + modId + "' bytes=" + pngBytes.length + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    /** modId → caracteres validos para class_2960 path ([a-z0-9_./-]). */
    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '/') {
                sb.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c + 32));
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
