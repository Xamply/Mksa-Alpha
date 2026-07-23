package dev.mksa.modthin;

import net.minecraft.class_2561;
import net.minecraft.class_310;
import net.minecraft.class_4185;
import net.minecraft.class_437;
import net.minecraft.class_332;

import java.util.ArrayList;
import java.util.List;

/**
 * Sección 8: Pantalla de Diagnóstico Expandible/Copiable (DiagnosticScreen).
 * Muestra el resumen del error, causa raíz, target afectado, fase, ID de transacción
 * y botón para copiar al portapapeles.
 */
public final class DiagnosticScreen extends class_437 {

    private final class_437 parent;
    private final String titleStr;
    private final String summary;
    private final String rootCauseClass;
    private final String rootCauseMsg;
    private final String phase;
    private final String target;
    private final String transactionId;
    private final List<String> lines = new ArrayList<String>();

    public DiagnosticScreen(class_437 parent, String titleStr, String summary,
                            String rootCauseClass, String rootCauseMsg,
                            String phase, String target, String transactionId) {
        super(class_2561.method_30163("MKSA · Diagnóstico"));
        this.parent = parent;
        this.titleStr = titleStr != null ? titleStr : "Diagnóstico de Operación";
        this.summary = summary != null ? summary : "Sin resumen";
        this.rootCauseClass = rootCauseClass;
        this.rootCauseMsg = rootCauseMsg;
        this.phase = phase;
        this.target = target;
        this.transactionId = transactionId != null ? transactionId : "n/a";

        lines.add("Detalle del Diagnóstico:");
        lines.add("----------------------------------------");
        lines.add("Resumen: " + this.summary);
        if (this.phase != null) lines.add("Fase: " + this.phase);
        if (this.target != null) lines.add("Target: " + this.target);
        if (this.rootCauseClass != null) lines.add("Causa Raíz: " + this.rootCauseClass);
        if (this.rootCauseMsg != null) lines.add("Mensaje Causa: " + this.rootCauseMsg);
        lines.add("ID Transacción: " + this.transactionId);
    }

    @Override
    protected void method_25426() {
        int btnW = 120;
        int btnY = this.field_22790 - 32;
        int startX = (this.field_22789 - (btnW * 2 + 10)) / 2;

        this.method_37063(class_4185.method_46430(class_2561.method_30163("Copiar Diagnóstico"), b -> copyToClipboard())
                .method_46434(startX, btnY, btnW, 20).method_46431());

        this.method_37063(class_4185.method_46430(class_2561.method_30163("Volver"), b -> this.field_22787.method_1507(parent))
                .method_46434(startX + btnW + 10, btnY, btnW, 20).method_46431());
    }

    private void copyToClipboard() {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        try {
            this.field_22787.field_1774.method_1455(sb.toString());
        } catch (Throwable ignored) {}
    }

    @Override
    public void method_25394(class_332 ctx, int mouseX, int mouseY, float delta) {
        super.method_25394(ctx, mouseX, mouseY, delta);
        ctx.method_25303(this.field_22793, "MKSA · " + titleStr, 20, 15, 0xFFFF5555);

        int y = 40;
        for (String line : lines) {
            ctx.method_25303(this.field_22793, line, 20, y, 0xFFCCCCCC);
            y += 14;
        }
    }
}
