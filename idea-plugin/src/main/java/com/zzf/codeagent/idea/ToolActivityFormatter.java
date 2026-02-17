package com.zzf.codeagent.idea;

final class ToolActivityFormatter {

    private ToolActivityFormatter() {
    }

    static String resolveTerminalTypeLabel(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "Terminal";
        }
        String normalized = toolName.trim().toLowerCase();
        if ("bash".equals(normalized) || normalized.contains("shell")) {
            return "Bash";
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    static String toCommandPreview(String command, int maxLen) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String compact = compactCommandForPreview(command);
        return trimForUi(compact, Math.max(40, maxLen));
    }

    private static String compactCommandForPreview(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String compact = command.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
        String lower = compact.toLowerCase();

        int commandIdx = lower.indexOf(" -command ");
        if (commandIdx > 0) {
            String tail = compact.substring(commandIdx + " -command ".length()).trim();
            String unwrapped = unwrapSingleLayerQuotes(tail);
            if (!unwrapped.isBlank()) {
                compact = unwrapped;
                lower = compact.toLowerCase();
            }
        }

        if (lower.startsWith("bash -lc ")) {
            String tail = compact.substring("bash -lc ".length()).trim();
            String unwrapped = unwrapSingleLayerQuotes(tail);
            if (!unwrapped.isBlank()) {
                compact = unwrapped;
            }
        } else if (lower.startsWith("cmd.exe /c ")) {
            compact = compact.substring("cmd.exe /c ".length()).trim();
        }
        return compact;
    }

    private static String unwrapSingleLayerQuotes(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return text.substring(1, text.length() - 1).trim();
            }
        }
        return text;
    }

    private static String trimForUi(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen - 1)).trim() + "...";
    }
}
