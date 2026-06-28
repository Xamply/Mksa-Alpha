package dev.mksa.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Caché en memoria + disco de iconos de mods, alimentada por Modrinth.
 *
 * <p><b>Memoria:</b> al {@link #init} se levanta un mapa modId→bytes
 * leyendo del cache de disco. {@link #loadCached} devuelve el byte[] del
 * mapa sin tocar disco — lo que evita los ~70 stat-de-disco que sufría
 * {@link Ledger#modThinListMods} cada vez que la UI pedía la lista.
 *
 * <p><b>Disco:</b> {@code <instance>/mksa/mod-icons/<modId>.png} para
 * persistencia entre arranques; {@code <modId>.miss} para mods que
 * Modrinth no tiene (no reintentar en cada arranque).
 *
 * <p><b>Fetch:</b> {@link #scheduleFetch} encola un job en un pool de 2
 * hilos daemon. El job:
 * <ol>
 *   <li>{@code GET https://api.modrinth.com/v2/search?query=<NAME>&limit=1}</li>
 *   <li>{@code hits[0].icon_url} → {@code GET icon_url} → bytes</li>
 *   <li>Escribe al disco Y al mapa en memoria. La próxima llamada a
 *       {@link Ledger#modThinListMods} ya lo ve.</li>
 * </ol>
 *
 * <p><b>Test de conectividad:</b> {@link #init} hace un GET de prueba con
 * logging completo (código HTTP, head de respuesta, excepciones). Si la
 * red está rota lo verás en stderr SIN TENER QUE ABRIR EL PANEL.
 */
final class ModIconStore {

    /** Modrinth recomienda un UA identificable. Se cumple ese formato. */
    private static final String USER_AGENT = "mksa-launcher/0.1 (https://github.com/mksa)";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_BYTES = 1024 * 1024;   // 1 MB tope por PNG remoto

    private static volatile Path cacheDir;
    private static volatile Path logFile;
    private static volatile ExecutorService pool;
    /** Cache en memoria: modId → bytes del PNG. Updated por loadFromDisk y cacheBytes. */
    private static final ConcurrentHashMap<String, byte[]> memory = new ConcurrentHashMap<String, byte[]>();
    /** Conjunto de modIds marcados como .miss en disco (no reintentar). */
    private static final Set<String> missed = ConcurrentHashMap.newKeySet();
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private static int fetchesScheduled;
    private static int fetchesOk;
    private static int fetchesMissed;
    private static int fetchesFailed;

    private ModIconStore() {}

    /** Llamado una vez por Boot antes de la enumeración. Idempotente. */
    static synchronized void init(Path base) {
        if (cacheDir != null) return;
        cacheDir = base.resolve("mod-icons");
        logFile = base.resolve("mod-icons.log");
        try { Files.createDirectories(cacheDir); }
        catch (Throwable t) { log("WARN no se pudo crear " + cacheDir + ": " + t); }
        // Limpia el log de runs anteriores para que cada arranque empiece fresco.
        try { Files.write(logFile, new byte[0]); } catch (Throwable ignored) {}
        log("INIT base=" + base + " cacheDir=" + cacheDir);
        pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "mksa-icon-fetch");
            t.setDaemon(true);
            return t;
        });
        loadFromDisk();
        pool.submit(ModIconStore::connectivityTest);
    }

    /**
     * Loguea a {@code mksa/mod-icons.log} además de stderr. Fabric captura
     * stderr TARDE en su pipeline, así que líneas tempranas (init, enumerate)
     * no llegan al latest.log — pero al archivo siempre llegan.
     */
    static synchronized void log(String line) {
        String full = "[" + java.time.LocalTime.now() + "] " + line + "\n";
        System.err.println("[mksa] mod-icons " + line);
        if (logFile == null) return;
        try {
            Files.write(logFile, full.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {}
    }

    /**
     * Slurp inicial del cache de disco al mapa en memoria. Valida el magic
     * de PNG: archivos del cache antiguo que en realidad son WebP/etc se
     * BORRAN para que el próximo fetch los baje y los convierta. Así una
     * sesión vieja con cache "corrupto" se autoarregla en el siguiente boot.
     */
    private static void loadFromDisk() {
        int loaded = 0, misses = 0, evicted = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.endsWith(".png")) {
                    String modId = name.substring(0, name.length() - 4);
                    try {
                        byte[] bytes = Files.readAllBytes(p);
                        if (bytes.length == 0) continue;
                        if (!isPng(bytes)) {
                            // No es PNG real (lo más común: WebP) → borrar para re-fetch.
                            try { Files.delete(p); } catch (Throwable ignored) {}
                            evicted++;
                            continue;
                        }
                        memory.put(modId, bytes);
                        loaded++;
                    } catch (Throwable ignored) {}
                } else if (name.endsWith(".miss")) {
                    String modId = name.substring(0, name.length() - 5);
                    missed.add(modId);
                    misses++;
                }
            }
        } catch (Throwable t) {
            log("ERR loadFromDisk: " + t);
        }
        log("LOAD cargados=" + loaded + " misses=" + misses + " evicted=" + evicted + " memSize=" + memory.size());
    }

    /**
     * Test de conectividad explícito. Se ejecuta en el pool justo tras
     * {@link #init} y reporta el resultado completo en stderr — si esto
     * falla, sabemos que la red está rota antes de que el usuario abra el
     * panel y se quede mirando placeholders.
     */
    private static void connectivityTest() {
        String url = "https://api.modrinth.com/v2/search?query=fabric&limit=1";
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] body = is == null ? new byte[0] : readBytes(is, MAX_BYTES);
            String head = body.length == 0 ? "" :
                    new String(body, 0, Math.min(body.length, 200), java.nio.charset.StandardCharsets.UTF_8);
            log("CONNECTIVITY HTTP " + code + " bytes=" + body.length + " head=" + head);
            c.disconnect();
        } catch (Throwable t) {
            log("CONNECTIVITY FAIL " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** True si hay un .miss marker (Modrinth ya respondió sin hits antes). */
    static boolean isMissed(String modId) {
        return modId != null && missed.contains(safe(modId));
    }

    /** Devuelve los bytes cacheados del PNG. Null si no hay icono cacheado. */
    static byte[] loadCached(String modId) {
        if (modId == null) return null;
        return memory.get(safe(modId));
    }

    /**
     * Encola un fetch desde Modrinth POR NOMBRE. No-op si ya hay uno en vuelo,
     * si ya está en cache o si ya está marcado como miss.
     */
    static boolean scheduleFetch(String modId, String modName) {
        if (cacheDir == null || pool == null || modId == null) return false;
        if (modName == null || modName.isEmpty()) modName = modId;
        String key = safe(modId);
        if (memory.containsKey(key)) return false;
        if (missed.contains(key)) return false;
        if (!inFlight.add(key)) return false;
        fetchesScheduled++;
        final String name = modName;
        final String fid = modId;
        pool.submit(() -> fetchOne(fid, name));
        return true;
    }

    /** Bloquea hasta que el pool quede idle o se agote {@code timeoutMs}. */
    static void waitForFetches(long timeoutMs) {
        if (pool == null) return;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (inFlight.isEmpty()) return;
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** Resumen para log. */
    static String stats() {
        return "scheduled=" + fetchesScheduled
                + " ok=" + fetchesOk
                + " missed=" + fetchesMissed
                + " failed=" + fetchesFailed
                + " inFlight=" + inFlight.size()
                + " mem=" + memory.size();
    }

    /** Worker: search por nombre → top hit → descarga del icon_url → cache. */
    private static void fetchOne(String modId, String modName) {
        String key = safe(modId);
        try {
            String iconUrl;
            try {
                iconUrl = searchTopHitIconUrl(modName);
            } catch (Throwable t) {
                fetchesFailed++;
                log("FAIL search '" + modName + "' -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
                return;
            }
            if (iconUrl == null || iconUrl.isEmpty()) {
                touchMiss(modId);
                fetchesMissed++;
                return;
            }
            byte[] bytes;
            try {
                bytes = httpGetBytes(iconUrl);
            } catch (Throwable t) {
                fetchesFailed++;
                log("FAIL download '" + modId + "' " + iconUrl + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
                return;
            }
            if (bytes == null || bytes.length == 0) {
                fetchesFailed++;
                log("FAIL empty body '" + modId + "' " + iconUrl);
                return;
            }
            // Modrinth sirve los iconos en el formato original que subió el autor.
            // Si es WebP, lo convertimos a PNG aquí — NativeImage de MC solo lee PNG.
            // PNGs auténticos pasan tal cual.
            byte[] png = ensurePng(bytes, modId, iconUrl);
            if (png == null) {
                fetchesFailed++;
                touchMiss(modId);
                return;
            }
            cacheBytes(modId, png);
            fetchesOk++;
            String fmt = png == bytes ? "PNG" : "converted";
            log("OK '" + modId + "' (" + png.length + " bytes, " + fmt + ") via '" + modName + "'");
        } finally {
            inFlight.remove(key);
        }
    }

    /**
     * GET /v2/search?query={name}&amp;limit=1, devuelve {@code hits[0].icon_url}.
     * Null si 0 hits o si el primer hit no tiene icon_url.
     */
    @SuppressWarnings("unchecked")
    private static String searchTopHitIconUrl(String query) throws Exception {
        String url = "https://api.modrinth.com/v2/search?query=" + urlEncode(query) + "&limit=1";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            int code = c.getResponseCode();
            if (code != 200) throw new RuntimeException("HTTP " + code);
            String json;
            try (InputStream is = c.getInputStream()) {
                json = new String(readBytes(is, MAX_BYTES), java.nio.charset.StandardCharsets.UTF_8);
            }
            Object parsed = Agent.Json.parse(json);
            if (!(parsed instanceof Map)) return null;
            Object hits = ((Map<String, Object>) parsed).get("hits");
            if (!(hits instanceof List)) return null;
            List<Object> hl = (List<Object>) hits;
            if (hl.isEmpty()) return null;
            Object top = hl.get(0);
            if (!(top instanceof Map)) return null;
            Object v = ((Map<String, Object>) top).get("icon_url");
            return v == null ? null : String.valueOf(v);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] httpGetBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            int code = c.getResponseCode();
            if (code != 200) throw new RuntimeException("HTTP " + code);
            try (InputStream is = c.getInputStream()) {
                return readBytes(is, MAX_BYTES);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readBytes(InputStream is, int cap) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            baos.write(buf, 0, n);
            if (baos.size() > cap) throw new java.io.IOException("respuesta excede " + cap + " bytes");
        }
        return baos.toByteArray();
    }

    /** Magic bytes de PNG: 89 50 4E 47 0D 0A 1A 0A. */
    private static boolean isPng(byte[] b) {
        return b != null && b.length >= 8
                && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A;
    }

    /** Magic de WebP: "RIFF" + 4 bytes tamaño + "WEBP". */
    private static boolean isWebp(byte[] b) {
        return b != null && b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    /**
     * Devuelve los bytes como PNG: si ya son PNG, los mismos; si son WebP/JPG/
     * otros formatos soportados por ImageIO, decodifica y re-encodea a PNG.
     * Null si el formato es desconocido y no se puede convertir.
     */
    private static byte[] ensurePng(byte[] bytes, String modId, String iconUrl) {
        if (isPng(bytes)) return bytes;
        String detected = isWebp(bytes) ? "WEBP" : "UNKNOWN";
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(bytes));
            if (img == null) {
                log("CONVERT-FAIL '" + modId + "' formato=" + detected + " (ImageIO sin lector)");
                return null;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(img, "png", out)) {
                log("CONVERT-FAIL '" + modId + "' (ImageIO no escribió PNG)");
                return null;
            }
            byte[] png = out.toByteArray();
            log("CONVERT '" + modId + "' " + detected + " " + bytes.length + "B -> PNG " + png.length + "B");
            return png;
        } catch (Throwable t) {
            log("CONVERT-FAIL '" + modId + "' formato=" + detected
                    + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    /** Escribe los bytes al cache (disco + memoria). Atómico en disco. */
    private static void cacheBytes(String modId, byte[] bytes) {
        if (cacheDir == null) return;
        String key = safe(modId);
        memory.put(key, bytes);
        Path p = cacheDir.resolve(key + ".png");
        Path tmp = cacheDir.resolve(key + ".png.tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            try { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING); } catch (Throwable ignored) {}
        }
    }

    private static void touchMiss(String modId) {
        if (cacheDir == null) return;
        String key = safe(modId);
        missed.add(key);
        try { Files.write(cacheDir.resolve(key + ".miss"), new byte[0]); }
        catch (Throwable ignored) {}
    }

    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Throwable t) { return s; }
    }

    /** modId tal cual debería ser file-system safe; sanitizamos por si acaso. */
    private static String safe(String modId) {
        StringBuilder sb = new StringBuilder(modId.length());
        for (int i = 0; i < modId.length(); i++) {
            char c = modId.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.') {
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
