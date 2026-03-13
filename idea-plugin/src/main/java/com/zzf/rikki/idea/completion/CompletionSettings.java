package com.zzf.rikki.idea.completion;

public final class CompletionSettings {
    private static boolean enabled = Boolean.parseBoolean(System.getProperty("rikki.completion.enabled", "true"));
    private static String endpoint = System.getProperty("rikki.completion.endpoint", resolveDefaultEndpoint());

    private CompletionSettings() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static String getEndpoint() {
        return endpoint;
    }

    public static void setEndpoint(String value) {
        endpoint = value == null ? "" : value;
    }

    private static String resolveDefaultEndpoint() {
        String base = System.getProperty("rikki.endpoint", "http://localhost:18080/api/agent/chat");
        if (base.endsWith("/chat/stream")) {
            return base.substring(0, base.length() - "/stream".length()).replaceAll("/chat$", "") + "/complete";
        }
        if (base.endsWith("/chat")) {
            return base.substring(0, base.length() - "/chat".length()) + "/complete";
        }
        return base + "/complete";
    }
}
