package com.zzf.rikki.idea;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;

import java.awt.Color;

final class ToolStatusFormatter {

    private ToolStatusFormatter() {
    }

    static Color colorForToolStatus(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase();
        if ("completed".equals(normalized) || "success".equals(normalized) || "ok".equals(normalized)) {
            return new JBColor(new Color(0x1B5E20), new Color(0x81C784));
        }
        if ("awaiting_approval".equals(normalized) || "needs_approval".equals(normalized)) {
            return new JBColor(new Color(0xF57F17), new Color(0xFFE082));
        }
        if ("rejected".equals(normalized) || "skipped".equals(normalized)) {
            return new JBColor(new Color(0xE65100), new Color(0xFFCC80));
        }
        if ("error".equals(normalized) || "failed".equals(normalized) || "failure".equals(normalized)) {
            return new JBColor(new Color(0xB71C1C), new Color(0xEF9A9A));
        }
        if ("running".equals(normalized) || "pending".equals(normalized) || "retry".equals(normalized)) {
            return new JBColor(new Color(0x0D47A1), new Color(0x90CAF9));
        }
        return UIUtil.getContextHelpForeground();
    }

    static String normalizeToolStatusLabel(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        String normalized = status.trim().toLowerCase();
        switch (normalized) {
            case "completed":
            case "success":
            case "ok":
                return "done";
            case "error":
            case "failed":
            case "failure":
                return "failed";
            case "rejected":
            case "skipped":
                return "rejected";
            case "awaiting_approval":
            case "needs_approval":
                return "waiting approval";
            case "pending":
            case "running":
            case "retry":
                return "running";
            default:
                return normalized;
        }
    }

    static String formatDuration(long durationMs) {
        if (durationMs <= 0L) {
            return "";
        }
        if (durationMs < 1000L) {
            return durationMs + "ms";
        }
        long seconds = durationMs / 1000L;
        long millisRemainder = durationMs % 1000L;
        if (seconds < 60L) {
            long tenths = millisRemainder / 100L;
            return seconds + "." + tenths + "s";
        }
        long minutes = seconds / 60L;
        long secondsRemainder = seconds % 60L;
        return minutes + "m " + secondsRemainder + "s";
    }
}
