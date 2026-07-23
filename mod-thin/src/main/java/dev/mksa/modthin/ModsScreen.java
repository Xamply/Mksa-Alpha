package dev.mksa.modthin;

import net.minecraft.class_10799;   // RenderPipelines (GUI_TEXTURED en field_56883)
import net.minecraft.class_11909;   // ClickEvent (record con x/y/button)
import net.minecraft.class_310;     // MinecraftClient
import net.minecraft.class_332;     // DrawContext
import net.minecraft.class_342;     // EditBox / TextFieldWidget
import net.minecraft.class_4185;    // ButtonWidget
import net.minecraft.class_437;     // Screen
import net.minecraft.class_2561;    // Text
import org.joml.Matrix3x2fStack;    // pose stack para escalar texto

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Panel de mods, abierto desde el boton dogito del menu de pausa.
 *
 * <p>Cambios v0.3 (sobre v0.2):
 *  · Filas mas altas (40px) con icono 32×32 a la izquierda, nombre+version
 *    encima y descripcion debajo. Antes el nombre+id se duplicaba; ahora el
 *    id queda fuera y la descripcion ocupa la segunda linea.
 *  · {@code visibleRows} se recalcula desde {@code field_22790} (alto del
 *    viewport) en cada rebuild — antes era constante 10, las filas que no
 *    cabian en pantalla quedaban cortadas y el scroll no alcanzaba el final.
 *  · Iconos de mod renderizados via {@link ModIconCache} (NativeImage →
 *    NativeImageBackedTexture registrada en el TextureManager bajo un
 *    class_2960 por modId, dibujada con DrawContext.method_25290 y el
 *    pipeline GUI_TEXTURED).
 *  · Filtro "solo mods en mods/", busqueda y toggle de tiers conservados.
 */
public final class ModsScreen extends class_437 {

    private static final int ROW_HEIGHT = 40;
    private static final int ICON_SIZE = 32;
    private static final int LIST_TOP = 56;     // 36 + 20 (search bar + padding)
    private static final int LIST_PAD = 10;
    private static final int BOTTOM_RESERVED = 56;  // back button + status messages
    private static final int SEARCH_Y = 30;
    private static final int SEARCH_H = 18;
    /** Ancho reservado para la scrollbar a la derecha de la lista. */
    private static final int SCROLLBAR_W = 6;
    /** Factor de escala para el texto de las filas — cabe ~17% más de descripción. */
    private static final float TEXT_SCALE = 0.85f;
    private static final int INFO_LINE_H = 10;
    private static final int INFO_ICON_SIZE = INFO_LINE_H * 2;

    private final class_437 parent;
    private List<BridgeProxy.ModEntry> allEntries = Collections.emptyList();  // sin filtrar (excepto mods/)
    private List<BridgeProxy.ModEntry> entries = Collections.emptyList();     // filtrado activo
    private String query = "";
    private int selected = -1;            // indice en `entries` (no en allEntries)
    private String selectedId;            // para preservar seleccion tras refilter
    private int scroll = 0;
    private int visibleRows = 1;          // recomputado al rebuild en funcion de field_22790
    private boolean draggingScrollbar;
    /** Para auto-refresh silencioso mientras quedan iconos pendientes. */
    private int autoRefreshTickAcc;
    private int autoRefreshAttempts;
    private static final int AUTO_REFRESH_PERIOD_TICKS = 40;   // 2s @ 20tps
    private static final int AUTO_REFRESH_MAX_ATTEMPTS = 30;   // ~1 min total
    private String banner;
    private boolean bannerError;
    private boolean busy;
    private String loadError;

    /** Persiste entre aperturas del panel — power user enciende y se queda. */
    private static boolean showTiers = false;

    private class_342 searchBox;
    private class_4185 disableBtn, enableBtn, backBtn, refreshBtn, tierToggleBtn;

    public ModsScreen(class_437 parent) {
        super(class_2561.method_30163("MKSA · Mods"));
        this.parent = parent;
    }

    @Override
    protected void method_25426() {  // init()
        if (allEntries.isEmpty() && loadError == null && !busy) {
            loadModsAsync();
        }
        rebuild();
    }

    /**
     * Cuantas filas caben de verdad entre LIST_TOP y la zona reservada para
     * botones de abajo. Antes era constante 10 → si la ventana de juego era
     * baja, las ultimas filas se dibujaban fuera del viewport y el scroll no
     * podia alcanzarlas.
     */
    private void recomputeVisibleRows() {
        int available = Math.max(ROW_HEIGHT, this.field_22790 - LIST_TOP - BOTTOM_RESERVED);
        this.visibleRows = Math.max(1, available / ROW_HEIGHT);
    }

    private void rebuild() {
        // Preserva texto del search box al rebuild (la rebuild ocurre tras cada
        // accion del agente, no queremos perder lo que el usuario tecleo).
        String prevSearch = searchBox != null ? searchBox.method_1882() : "";

        recomputeVisibleRows();
        // Clampear scroll a los limites actuales antes de pintar (el viewport
        // pudo haberse achicado).
        int maxScroll = Math.max(0, entries.size() - visibleRows);
        if (scroll > maxScroll) scroll = maxScroll;
        if (scroll < 0) scroll = 0;

        method_37067();   // clearWidgets

        int zoneW = (this.field_22789 - 2 * LIST_PAD) / 2;
        int listW = zoneW;
        int sideX = LIST_PAD + zoneW + LIST_PAD;
        int sideW = this.field_22789 - sideX - LIST_PAD;

        // Search bar (arriba del listado).
        int topBtnW = 24;
        int topBtnGap = 4;
        int topBtnRight = LIST_PAD + listW - LIST_PAD;
        int topBtnX = topBtnRight - (topBtnW * 2 + topBtnGap);
        this.searchBox = new class_342(this.field_22793,
                LIST_PAD, SEARCH_Y, topBtnX - LIST_PAD - 4, SEARCH_H,
                class_2561.method_30163("Buscar"));
        this.searchBox.method_1852(prevSearch);
        this.searchBox.method_1863(s -> {
            this.query = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
            refilter();
            rebuild();
        });
        this.method_37063(this.searchBox);

        // Filas como botones transparentes — onPress = seleccionar.
        // El ancho clickable termina antes de la scrollbar para no comer su zona.
        int rowsTop = LIST_TOP;
        int rowW = listW - 2 * LIST_PAD - SCROLLBAR_W - 2;
        for (int r = 0; r < visibleRows; r++) {
            final int idx = scroll + r;
            class_4185 row = class_4185.method_46430(
                    class_2561.method_30163(""),
                    b -> {
                        if (idx < entries.size()) {
                            this.selected = idx;
                            this.selectedId = entries.get(idx).id;
                            rebuild();
                        }
                    }
            ).method_46434(LIST_PAD, rowsTop + r * ROW_HEIGHT,
                    rowW, ROW_HEIGHT - 2)
             .method_46431();
            this.method_37063(row);
        }

        // Refresh / Tier-toggle — arriba a la derecha, dejando el search recortado.
        int navY = SEARCH_Y;
        this.refreshBtn = class_4185.method_46430(class_2561.method_30163("⟳"), b -> loadModsAsync())
                .method_46434(topBtnX, navY, topBtnW, 20).method_46431();
        // Toggle tiers: muestra/oculta los chips T0..T3. Por defecto OFF — chips
        // son ruido para el usuario final; util para diagnostico (power user).
        this.tierToggleBtn = class_4185.method_46430(
                class_2561.method_30163(showTiers ? "T✓" : "T"),
                b -> { showTiers = !showTiers; rebuild(); })
                .method_46434(topBtnX + topBtnW + topBtnGap, navY, topBtnW, 20).method_46431();
        this.method_37063(this.refreshBtn);
        this.method_37063(this.tierToggleBtn);

        BridgeProxy.ModEntry sel = (selected >= 0 && selected < entries.size()) ? entries.get(selected) : null;
        boolean isRuntimeUnsupported = sel != null && "runtime_unsupported".equalsIgnoreCase(sel.toggleCapability);
        boolean disableActive = !busy && sel != null && "active".equalsIgnoreCase(sel.toggleState) && !isRuntimeUnsupported;
        boolean enableActive  = !busy && sel != null && "inactive_verified".equalsIgnoreCase(sel.toggleState) && !isRuntimeUnsupported;

        int panelBottom = LIST_TOP + visibleRows * ROW_HEIGHT - 22;
        int actY = panelBottom + 6;
        int actionGap = 6;
        int actionW = (sideW - actionGap) / 2;
        this.disableBtn = class_4185.method_46430(class_2561.method_30163("Desactivar"), b -> onDisable())
                .method_46434(sideX, actY, actionW, 22).method_46431();
        this.disableBtn.field_22763 = disableActive;

        this.enableBtn = class_4185.method_46430(class_2561.method_30163("Restaurar"), b -> onEnable())
                .method_46434(sideX + actionW + actionGap, actY, sideW - actionW - actionGap, 22).method_46431();
        this.enableBtn.field_22763 = enableActive;

        this.backBtn = class_4185.method_46430(class_2561.method_30163("Volver"), b -> this.field_22787.method_1507(parent))
                .method_46434((this.field_22789 - 100) / 2, this.field_22790 - 30, 100, 20).method_46431();

        this.method_37063(this.disableBtn);
        this.method_37063(this.enableBtn);
        this.method_37063(this.backBtn);

        // Focus inicial al search box para teclear directo.
        method_25395(this.searchBox);
    }

    @Override
    public void method_25394(class_332 ctx, int mouseX, int mouseY, float delta) {
        super.method_25394(ctx, mouseX, mouseY, delta);

        // Titulo + contador
        String title = "MKSA · Mods  (" + entries.size() + "/" + allEntries.size() + ")";
        ctx.method_25303(this.field_22793, title, LIST_PAD, 12, 0xFFFFFFFF);

        int zoneW = (this.field_22789 - 2 * LIST_PAD) / 2;
        int listW = zoneW;
        int rowRight = LIST_PAD + listW - 2 * LIST_PAD;
        int scrollbarX = rowRight - SCROLLBAR_W;
        int contentRight = scrollbarX - 4;
        int sideX = LIST_PAD + zoneW + LIST_PAD;
        int sideW = this.field_22789 - sideX - LIST_PAD;
        BridgeProxy.ModEntry sel = (selected >= 0 && selected < entries.size()) ? entries.get(selected) : null;

        // Contenido de cada fila (texto + icono encima del fondo del ButtonWidget).
        int rowCount = visibleRows;
        for (int r = 0; r < rowCount; r++) {
            int idx = scroll + r;
            int y = LIST_TOP + r * ROW_HEIGHT;

            // Recuadro de seleccion (sin invadir la zona de scrollbar).
            if (idx < entries.size() && idx == selected) {
                ctx.method_25294(LIST_PAD - 1, y - 1,
                        contentRight + 1, y + ROW_HEIGHT - 3 + 1,
                        0x6066AAFF);
            }

            if (idx >= entries.size()) {
                continue;
            }

            BridgeProxy.ModEntry e = entries.get(idx);

            // Icono 32×32 (centrado verticalmente). Si el agente entrego PNG y
            // decodifica OK, lo registramos en el TextureManager la primera vez
            // y reusamos su class_2960 desde la cache. Si no hay icono, placeholder
            // con la inicial del nombre.
            int iconX = LIST_PAD + 4;
            int iconY = y + (ROW_HEIGHT - 2 - ICON_SIZE) / 2;
            ModIconCache.Entry icon = ModIconCache.getOrRegister(e.id, e.iconPng);
            if (icon != null) {
                // method_25290 dibuja 1:1 source-to-dest (sin escalar): pasarle
                // ICON_SIZE como w/h tomaría solo los primeros 32×32 píxeles de
                // un icono grande (la esquina superior izquierda). Para mostrar
                // el icono COMPLETO escalado a ICON_SIZE, escalamos la matriz
                // y dibujamos el texture a tamaño natural (icon.width × icon.height).
                // Preserva aspect ratio centrando en el cuadrado.
                float fitScale = (float) ICON_SIZE / Math.max(icon.width, icon.height);
                int drawW = Math.round(icon.width * fitScale);
                int drawH = Math.round(icon.height * fitScale);
                int padX = (ICON_SIZE - drawW) / 2;
                int padY = (ICON_SIZE - drawH) / 2;
                Matrix3x2fStack iconPose = ctx.method_51448();
                iconPose.pushMatrix();
                iconPose.translate(iconX + padX, iconY + padY);
                iconPose.scale(fitScale, fitScale);
                ctx.method_25290(class_10799.field_56883, icon.id,
                        0, 0, 0f, 0f, icon.width, icon.height, icon.width, icon.height);
                iconPose.popMatrix();
            } else {
                int colorBg = placeholderBg(e.id);
                ctx.method_25294(iconX, iconY, iconX + ICON_SIZE, iconY + ICON_SIZE, colorBg);
                String initial = initialOf(e);
                int tw = this.field_22793.method_1727(initial);
                ctx.method_25303(this.field_22793, initial,
                        iconX + (ICON_SIZE - tw) / 2,
                        iconY + (ICON_SIZE - 8) / 2,
                        0xFFFFFFFF);
            }

            // Texto: nombre arriba, descripcion abajo. La version queda en el
            // panel derecho.
            int textX = iconX + ICON_SIZE + 8;
            int chipsW = showTiers ? 80 : 36;     // ON/OFF ~28; con T?? ~28+padding
            int textMaxWPx = contentRight - textX - chipsW - 8;
            if (textMaxWPx < 20) textMaxWPx = 20;
            int textMaxWLogical = (int) (textMaxWPx / TEXT_SCALE);

            String name = e.name != null ? e.name : (e.id != null ? e.id : "?");
            String nameTrim = this.field_22793.method_27523(name, textMaxWLogical);
            String desc = e.description != null ? e.description : "";
            String descTrim = desc.isEmpty() ? null : this.field_22793.method_27523(desc, textMaxWLogical);

            Matrix3x2fStack pose = ctx.method_51448();
            pose.pushMatrix();
            pose.translate(textX, y + 6);
            pose.scale(TEXT_SCALE, TEXT_SCALE);
            ctx.method_25303(this.field_22793, nameTrim, 0, 0, 0xFFFFFFFF);
            pose.popMatrix();
            if (descTrim != null) {
                pose.pushMatrix();
                pose.translate(textX, y + 18);
                pose.scale(TEXT_SCALE, TEXT_SCALE);
                ctx.method_25303(this.field_22793, descTrim, 0, 0, 0xFFB0B0B0);
                pose.popMatrix();
            }

            // Chips a la derecha del contenido — centrados verticalmente.
            int chipY = y + (ROW_HEIGHT - 2 - 12) / 2;
            int chipX = contentRight - 32;        // ON/OFF a la derecha del contenido
            String stateLabel = e.running ? "ON" : "OFF";
            int stateColor = e.running ? 0xFF3FAF55 : 0xFFAF553F;
            drawChip(ctx, chipX, chipY, stateLabel, stateColor);
            if (showTiers) {
                String tierLabel = e.tier == null ? "T?" : ("T" + e.tier);
                drawChip(ctx, chipX - 36, chipY, tierLabel, tierColor(e.tier));
            }
        }

        // Panel derecho: informacion del mod seleccionado.
        int panelBottom = LIST_TOP + visibleRows * ROW_HEIGHT - 22;
        ctx.method_25294(sideX, LIST_TOP - 2, sideX + sideW, panelBottom, 0x90000000);
        ctx.method_25303(this.field_22793, "Informacion del mod", sideX + 8, LIST_TOP + 6, 0xFFFFFFFF);

        if (sel != null) {
            int infoTop = LIST_TOP + 22;
            int iconX = sideX + 8;
            int iconY = infoTop;
            ModIconCache.Entry icon = ModIconCache.getOrRegister(sel.id, sel.iconPng);
            if (icon != null) {
                float fitScale = (float) INFO_ICON_SIZE / Math.max(icon.width, icon.height);
                int drawW = Math.round(icon.width * fitScale);
                int drawH = Math.round(icon.height * fitScale);
                int padX = (INFO_ICON_SIZE - drawW) / 2;
                int padY = (INFO_ICON_SIZE - drawH) / 2;
                Matrix3x2fStack iconPose = ctx.method_51448();
                iconPose.pushMatrix();
                iconPose.translate(iconX + padX, iconY + padY);
                iconPose.scale(fitScale, fitScale);
                ctx.method_25290(class_10799.field_56883, icon.id,
                        0, 0, 0f, 0f, icon.width, icon.height, icon.width, icon.height);
                iconPose.popMatrix();
            } else {
                int colorBg = placeholderBg(sel.id);
                ctx.method_25294(iconX, iconY, iconX + INFO_ICON_SIZE, iconY + INFO_ICON_SIZE, colorBg);
                String initial = initialOf(sel);
                int tw = this.field_22793.method_1727(initial);
                ctx.method_25303(this.field_22793, initial,
                        iconX + (INFO_ICON_SIZE - tw) / 2,
                        iconY + (INFO_ICON_SIZE - 8) / 2,
                        0xFFFFFFFF);
            }

            int infoTextX = sideX + 8 + INFO_ICON_SIZE + 8;
            int infoTextW = sideW - (INFO_ICON_SIZE + 24);
            if (infoTextW < 20) infoTextW = 20;
            String infoName = sel.name != null ? sel.name : (sel.id != null ? sel.id : "?");
            String infoVersion = sel.version != null ? sel.version : "?";
            ctx.method_25303(this.field_22793,
                    this.field_22793.method_27523(infoName, infoTextW),
                    infoTextX, infoTop + 1, 0xFFFFFFFF);
            ctx.method_25303(this.field_22793,
                    this.field_22793.method_27523("v" + infoVersion, infoTextW),
                    infoTextX, infoTop + INFO_LINE_H + 1, 0xFFB0B0B0);
        } else {
            ctx.method_25303(this.field_22793, "Selecciona un mod", sideX + 8, LIST_TOP + 28, 0xFFB0B0B0);
        }

        // Scrollbar: aparece solo si hay más mods que caben en visibleRows.
        // Track: rail tenue a lo largo de toda la zona de filas.
        // Thumb: proporcional a visibleRows/total, posicionado por scroll/max.
        if (entries.size() > visibleRows) {
            int trackTop = LIST_TOP;
            int trackBottom = LIST_TOP + visibleRows * ROW_HEIGHT - 2;
            int trackH = trackBottom - trackTop;
            int maxScroll = Math.max(1, entries.size() - visibleRows);
            int thumbH = Math.max(20, trackH * visibleRows / Math.max(1, entries.size()));
            int thumbY = trackTop + (trackH - thumbH) * scroll / maxScroll;
            // track
            ctx.method_25294(scrollbarX, trackTop, scrollbarX + SCROLLBAR_W, trackBottom, 0x40FFFFFF);
            // thumb
            ctx.method_25294(scrollbarX, thumbY, scrollbarX + SCROLLBAR_W, thumbY + thumbH, 0xC0FFFFFF);
        }

        // Estado
        if (busy) {
            ctx.method_25303(this.field_22793, "Trabajando…",
                    LIST_PAD, this.field_22790 - 50, 0xFFFFFF66);
        }
        if (loadError != null) {
            ctx.method_25303(this.field_22793, loadError,
                    LIST_PAD, LIST_TOP + 4, 0xFFFF5555);
        } else if (entries.isEmpty() && !busy) {
            String msg = allEntries.isEmpty()
                    ? "(sin datos — agente no listo)"
                    : "(no hay coincidencias para '" + query + "')";
            ctx.method_25303(this.field_22793, msg, LIST_PAD, LIST_TOP + 4, 0xFFAAAAAA);
        }
        if (banner != null) {
            int col = bannerError ? 0xFFFF6666 : 0xFF66FF99;
            ctx.method_25303(this.field_22793, banner,
                    LIST_PAD, this.field_22790 - 38, col);
        }


    }

    private void drawChip(class_332 ctx, int x, int y, String label, int color) {
        int w = this.field_22793.method_1727(label) + 8;
        int h = 12;
        ctx.method_25294(x, y, x + w, y + h, (color & 0x00FFFFFF) | 0x70000000);
        ctx.method_25294(x, y, x + w, y + 1, color);
        ctx.method_25294(x, y + h - 1, x + w, y + h, color);
        ctx.method_25303(this.field_22793, label, x + 4, y + 2, 0xFFFFFFFF);
    }

    private int tierColor(Integer tier) {
        if (tier == null) return 0xFF999999;
        switch (tier.intValue()) {
            case 0: return 0xFF4FB050;
            case 1: return 0xFF66AAFF;
            case 2: return 0xFFE0A030;
            case 3: return 0xFFC04040;
            default: return 0xFF999999;
        }
    }

    /** Inicial del nombre del mod para el placeholder cuando no hay icono. */
    private static String initialOf(BridgeProxy.ModEntry e) {
        String s = e.name != null && !e.name.isEmpty() ? e.name
                 : (e.id != null && !e.id.isEmpty() ? e.id : "?");
        return s.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    /** Color de fondo del placeholder estable por id (hash → matiz). */
    private static int placeholderBg(String id) {
        if (id == null || id.isEmpty()) return 0xFF606060;
        int h = id.hashCode();
        int r = 0x40 + (Math.abs(h) % 0x40);
        int g = 0x40 + (Math.abs(h >> 8) % 0x40);
        int b = 0x40 + (Math.abs(h >> 16) % 0x40);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ---- filtrado ----

    /**
     * Recalcula {@link #entries} desde {@link #allEntries} aplicando el query
     * y restaura {@link #selected} apuntando al mismo {@link #selectedId} si
     * sigue visible. {@link #scroll} se clampea.
     */
    private void refilter() {
        String q = query == null ? "" : query;
        List<BridgeProxy.ModEntry> out = new ArrayList<BridgeProxy.ModEntry>();
        for (BridgeProxy.ModEntry e : allEntries) {
            if (q.isEmpty() || matches(e, q)) out.add(e);
        }
        this.entries = out;
        int newSel = -1;
        if (selectedId != null) {
            for (int i = 0; i < entries.size(); i++) {
                if (selectedId.equals(entries.get(i).id)) { newSel = i; break; }
            }
        }
        this.selected = newSel;
        int max = Math.max(0, entries.size() - visibleRows);
        if (scroll > max) scroll = max;
    }

    private static boolean matches(BridgeProxy.ModEntry e, String q) {
        String name = e.name == null ? "" : e.name.toLowerCase(Locale.ROOT);
        String id = e.id == null ? "" : e.id.toLowerCase(Locale.ROOT);
        String desc = e.description == null ? "" : e.description.toLowerCase(Locale.ROOT);
        return name.contains(q) || id.contains(q) || desc.contains(q);
    }

    /**
     * Filtra mods que vienen directamente de {@code <instance>/mods/}. Esconde
     * submodulos jar-in-jar (fabric-api-base, etc.), mods anidados, y los
     * pseudo-mods del loader (minecraft, java, fabricloader) — que no tienen
     * archivos en disco bajo mods/.
     */
    private static List<BridgeProxy.ModEntry> filterToMods(List<BridgeProxy.ModEntry> raw) {
        List<BridgeProxy.ModEntry> out = new ArrayList<BridgeProxy.ModEntry>();
        for (BridgeProxy.ModEntry e : raw) {
            if (livesInModsFolder(e)) out.add(e);
        }
        return out;
    }

    private static boolean livesInModsFolder(BridgeProxy.ModEntry e) {
        if (e.files == null || e.files.isEmpty()) return false;
        for (String pathStr : e.files) {
            if (pathStr == null) continue;
            File f = new File(pathStr);
            File parent = f.getParentFile();
            if (parent != null && "mods".equalsIgnoreCase(parent.getName())) {
                return true;
            }
        }
        return false;
    }

    // ---- I/O asincrono con el agente ----

    private void loadModsAsync() { loadModsAsync(false); }

    /**
     * Recarga la lista. {@code silent=true} no enseña "Trabajando…" y no toca
     * banner/loadError — pensado para el auto-refresh tick que polea iconos
     * pendientes sin distraer al usuario.
     */
    private void loadModsAsync(boolean silent) {
        if (!silent) {
            busy = true; banner = null; loadError = null;
            // Cualquier refresh manual (botón ⟳ o post-acción) reactiva el polling
            // por si llegan más iconos en los próximos segundos.
            autoRefreshAttempts = 0;
            autoRefreshTickAcc = 0;
            rebuild();
        }
        final class_310 client = this.field_22787;
        new Thread(() -> {
            BridgeProxy.ListResult r = BridgeProxy.listMods();
            client.execute(() -> {
                if (!silent) busy = false;
                if (!r.ok) {
                    if (!silent) {
                        loadError = "Error: " + (r.error != null ? r.error : "agente no responde");
                        allEntries = Collections.emptyList();
                        entries = Collections.emptyList();
                    }
                } else {
                    if (!silent) loadError = null;
                    allEntries = filterToMods(r.mods);
                    refilter();
                }
                rebuild();
            });
        }, silent ? "MksaThin-poll" : "MksaThin-listMods").start();
    }

    /**
     * Tick del screen. Mientras queden mods sin icono cargado, se polea el
     * agente cada {@link #AUTO_REFRESH_PERIOD_TICKS} ticks para recoger
     * iconos que Modrinth haya entregado entretanto. Se rinde tras
     * {@link #AUTO_REFRESH_MAX_ATTEMPTS} intentos para no quedarnos
     * eternamente machacando al agente con mods que no están en Modrinth.
     */
    @Override
    public void method_25393() {
        super.method_25393();
        if (busy || allEntries.isEmpty()) return;
        if (autoRefreshAttempts >= AUTO_REFRESH_MAX_ATTEMPTS) return;
        boolean anyMissing = false;
        for (BridgeProxy.ModEntry e : allEntries) {
            if (e.iconPng == null || e.iconPng.length == 0) { anyMissing = true; break; }
        }
        if (!anyMissing) return;
        autoRefreshTickAcc++;
        if (autoRefreshTickAcc >= AUTO_REFRESH_PERIOD_TICKS) {
            autoRefreshTickAcc = 0;
            autoRefreshAttempts++;
            loadModsAsync(true);
        }
    }

    private void onDisable() {
        BridgeProxy.ModEntry sel = (selected >= 0 && selected < entries.size()) ? entries.get(selected) : null;
        if (sel == null || busy || "runtime_unsupported".equalsIgnoreCase(sel.toggleCapability)) return;
        if (!"active".equalsIgnoreCase(sel.toggleState)) return;
        final String ns = sel.id;
        final String name = sel.name;
        busy = true; rebuild();
        final class_310 client = this.field_22787;
        new Thread(() -> {
            BridgeProxy.CascadeResult cr = BridgeProxy.cascadeTargets(ns);
            client.execute(() -> {
                busy = false;
                if (!cr.ok || cr.targets.isEmpty()) {
                    // Camino rapido: sin dependencias huerfanas, comportamiento identico a hoy.
                    runAction(ns, true);
                    return;
                }
                rebuild();
                client.method_1507(new CascadeDisableScreen(this, ns, name, cr.targets,
                        () -> runGroupAction(ns, cr.targets)));
            });
        }, "MksaThin-cascade").start();
    }

    private void onEnable() {
        BridgeProxy.ModEntry sel = (selected >= 0 && selected < entries.size()) ? entries.get(selected) : null;
        if (sel == null || busy || "runtime_unsupported".equalsIgnoreCase(sel.toggleCapability)) return;
        if (!"inactive_verified".equalsIgnoreCase(sel.toggleState)) return;
        runAction(sel.id, false);
    }

    /**
     * Scroll con la rueda del raton sobre la lista. La signature
     * {@code (mouseX, mouseY, horizontalAmount, verticalAmount)} es la estable de
     * la API de input — no cambia entre 1.21.x. Si el cursor esta sobre el area
     * de filas, consumimos el evento y movemos {@code scroll}. Fuera del area,
     * delegamos al super para que widgets enfocables (search box) reciban scroll
     * propio si en el futuro lo soportan.
     */
    /**
     * Drag de la scrollbar. La firma {@code method_25402(class_11909, boolean)}
     * es la de 1.21.11 (record que empaqueta x/y/botón); el botón izquierdo viene
     * en {@code click.comp_4800().comp_4797() == 0}. Click sobre la zona de la
     * scrollbar entra en modo drag y mapea la Y del ratón a {@link #scroll}.
     * Devolvemos {@code true} para CONSUMIR el evento y que no llegue a las filas.
     */
    @Override
    public boolean method_25402(class_11909 click, boolean doubleClick) {
        double mx = click.comp_4798();
        double my = click.comp_4799();
        int button = click.comp_4800() != null ? click.comp_4800().comp_4797() : 0;
        if (button == 0 && isOnScrollbar(mx, my)) {
            draggingScrollbar = true;
            scrollFromMouseY(my);
            return true;
        }
        return super.method_25402(click, doubleClick);
    }

    @Override
    public boolean method_25406(class_11909 click) {
        if (draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.method_25406(click);
    }

    @Override
    public boolean method_25403(class_11909 click, double dx, double dy) {
        if (draggingScrollbar) {
            scrollFromMouseY(click.comp_4799());
            return true;
        }
        return super.method_25403(click, dx, dy);
    }

    /** True si (mouseX, mouseY) está dentro del rail de la scrollbar y hay overflow. */
    private boolean isOnScrollbar(double mouseX, double mouseY) {
        if (entries.size() <= visibleRows) return false;
        int listW = (int) (this.field_22789 * 0.65);
        int rowRight = LIST_PAD + listW - 2 * LIST_PAD;
        int scrollbarX = rowRight - SCROLLBAR_W;
        int trackTop = LIST_TOP;
        int trackBottom = LIST_TOP + visibleRows * ROW_HEIGHT - 2;
        return mouseX >= scrollbarX - 2 && mouseX < scrollbarX + SCROLLBAR_W + 2
                && mouseY >= trackTop && mouseY < trackBottom;
    }

    /**
     * Mapea la Y absoluta del ratón a un valor de scroll. La mitad del thumb se
     * descuenta para que el thumb quede CENTRADO en el cursor mientras dura el
     * drag (sensación natural). Clamp a [0, maxScroll] y rebuild si cambia.
     */
    private void scrollFromMouseY(double mouseY) {
        int maxScroll = Math.max(0, entries.size() - visibleRows);
        if (maxScroll == 0) return;
        int trackTop = LIST_TOP;
        int trackH = visibleRows * ROW_HEIGHT - 2;
        int total = Math.max(1, entries.size());
        int thumbH = Math.max(20, trackH * visibleRows / total);
        int dragSpan = Math.max(1, trackH - thumbH);
        double thumbY = mouseY - thumbH / 2.0;
        int rel = (int) Math.round(thumbY - trackTop);
        if (rel < 0) rel = 0;
        if (rel > dragSpan) rel = dragSpan;
        int next = (int) Math.round((double) rel * maxScroll / dragSpan);
        if (next != scroll) {
            scroll = next;
            rebuild();
        }
    }

    @Override
    public boolean method_25401(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int listW = (int) (this.field_22789 * 0.65);
        int listLeft = LIST_PAD;
        int listRight = LIST_PAD + listW - 2 * LIST_PAD;
        int listTop = LIST_TOP;
        int listBottom = LIST_TOP + visibleRows * ROW_HEIGHT;
        boolean overList = mouseX >= listLeft && mouseX < listRight
                && mouseY >= listTop && mouseY < listBottom;
        if (overList) {
            // verticalAmount > 0 = rueda hacia arriba (convencion MC) = scroll arriba.
            int step = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
            if (step != 0) {
                int max = Math.max(0, entries.size() - visibleRows);
                int next = Math.max(0, Math.min(max, scroll + step));
                if (next != scroll) {
                    scroll = next;
                    rebuild();
                }
            }
            return true;
        }
        return super.method_25401(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void runAction(final String ns, final boolean isDisable) {
        busy = true; banner = null;
        rebuild();
        final class_310 client = this.field_22787;
        final String verb = isDisable ? "Desactivar" : "Restaurar";
        new Thread(() -> {
            BridgeProxy.ActionResult r = isDisable ? BridgeProxy.disable(ns) : BridgeProxy.enable(ns);
            client.execute(() -> {
                if (r.ok) {
                    banner = verb + " '" + ns + "' OK"
                            + (r.blocks > 0 ? " · " + r.blocks + " bloques" : "")
                            + (r.chunks > 0 ? " · " + r.chunks + " chunks" : "");
                    bannerError = false;
                    new Thread(() -> {
                        BridgeProxy.ListResult lr = BridgeProxy.listMods();
                        client.execute(() -> {
                            busy = false;
                            if (lr.ok) {
                                allEntries = filterToMods(lr.mods);
                                refilter();
                            }
                            rebuild();
                        });
                    }, "MksaThin-refresh").start();
                } else {
                    busy = false;
                    bannerError = true;
                    banner = verb + " falló: "
                            + (r.error != null ? r.error : (r.code != null ? r.code : "desconocido"));
                    rebuild();
                }
            });
        }, "MksaThin-" + verb).start();
    }

    /**
     * Desactiva {@code ns} junto con las dependencias huerfanas confirmadas en
     * {@link CascadeDisableScreen} (§cascade disable), como una sola accion del
     * jugador. Reactivar sigue siendo por-mod: {@link #runAction} restaura la
     * raiz y el agente restaura los companeros automaticamente (simetria).
     */
    private void runGroupAction(final String ns, final List<BridgeProxy.CascadeTarget> targets) {
        busy = true; banner = null;
        rebuild();
        final class_310 client = this.field_22787;
        final List<String> nsList = new ArrayList<>();
        nsList.add(ns);
        for (BridgeProxy.CascadeTarget t : targets) nsList.add(t.ns);
        new Thread(() -> {
            BridgeProxy.ActionResult r = BridgeProxy.disableGroup(nsList);
            client.execute(() -> {
                if (r.ok) {
                    banner = "Desactivar '" + ns + "' + " + targets.size() + " dependencia(s) OK"
                            + (r.blocks > 0 ? " · " + r.blocks + " bloques" : "")
                            + (r.chunks > 0 ? " · " + r.chunks + " chunks" : "");
                    bannerError = false;
                    new Thread(() -> {
                        BridgeProxy.ListResult lr = BridgeProxy.listMods();
                        client.execute(() -> {
                            busy = false;
                            if (lr.ok) {
                                allEntries = filterToMods(lr.mods);
                                refilter();
                            }
                            rebuild();
                        });
                    }, "MksaThin-refresh").start();
                } else {
                    busy = false;
                    bannerError = true;
                    banner = "Desactivar '" + ns + "' + " + targets.size() + " dependencia(s) fallo: "
                            + (r.error != null ? r.error : (r.code != null ? r.code : "desconocido"));
                    rebuild();
                }
            });
        }, "MksaThin-cascade-disable").start();
    }
}
