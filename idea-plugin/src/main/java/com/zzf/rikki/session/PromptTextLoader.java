package com.zzf.rikki.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PromptTextLoader {
    private static final int DEFAULT_BASH_MAX_LINES = 200;
    private static final int DEFAULT_BASH_MAX_BYTES = 8000;

    private PromptTextLoader() {
    }

    public static String load(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        try (InputStream stream = PromptTextLoader.class.getClassLoader().getResourceAsStream(normalized)) {
            if (stream == null) {
                return "";
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return "";
        }
    }

    public static boolean has(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return PromptTextLoader.class.getClassLoader().getResource(normalized) != null;
    }

    public static String loadSessionPrompt(String modelId) {
        String lower = modelId == null ? "" : modelId.trim().toLowerCase();
        String normalized = lower.replace(':', '-').replace('/', '-');
        String exact = normalized.isBlank() ? "" : "prompts/session/" + normalized + ".txt";
        if (!exact.isBlank() && has(exact)) {
            return load(exact);
        }
        String file;
        if (lower.contains("gpt-5")) {
            file = "codex_header.txt";
        } else if (lower.contains("gpt-") || lower.contains("o1") || lower.contains("o3") || lower.contains("o4")) {
            file = "beast.txt";
        } else if (lower.contains("gemini-")) {
            file = "gemini.txt";
        } else if (lower.contains("claude") || lower.contains("anthropic")) {
            file = "anthropic.txt";
        } else {
            file = "qwen.txt";
        }
        return load("prompts/session/" + file);
    }

    public static String loadAgentPrompt(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return "";
        }
        return load("prompts/agent/" + agentName.trim().toLowerCase() + ".txt");
    }

    public static String loadRuntimePrompt(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return load("prompts/runtime/" + name.trim().toLowerCase() + ".txt");
    }

    public static String loadToolPrompt(String toolId) {
        String path = toolPromptPath(toolId);
        return path.isBlank() ? "" : load(path).trim();
    }

    public static String loadToolDescription(String toolId, String workspaceRoot) {
        String raw = loadToolPrompt(toolId);
        if (raw.isBlank()) {
            return "";
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("directory", workspaceRoot == null ? "" : workspaceRoot);
        variables.put("maxLines", DEFAULT_BASH_MAX_LINES);
        variables.put("maxBytes", DEFAULT_BASH_MAX_BYTES);
        variables.put("date", java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        return renderTemplate(raw, variables).trim();
    }

    public static String renderTemplate(String raw, Map<String, ?> variables) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String rendered = raw;
        if (variables != null) {
            for (Map.Entry<String, ?> entry : variables.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                rendered = rendered.replace("{{" + key + "}}", value);
                rendered = rendered.replace("${" + key + "}", value);
            }
        }
        return rendered;
    }

    private static String toolPromptPath(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return "";
        }
        String normalized = toolId.trim().toLowerCase();
        return switch (normalized) {
            case "todo_read" -> "prompts/tool/todoread.txt";
            case "todo_write" -> "prompts/tool/todowrite.txt";
            case "web_search" -> "prompts/tool/websearch.txt";
            case "search_codebase" -> "prompts/tool/codesearch.txt";
            default -> "prompts/tool/" + normalized + ".txt";
        };
    }
}
