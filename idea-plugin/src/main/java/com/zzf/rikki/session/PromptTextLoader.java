package com.zzf.rikki.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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

    public static String loadToolDescription(String toolId, String workspaceRoot) {
        String resourcePath = switch (toolId) {
            case "bash" -> "prompts/tool/bash.txt";
            case "read" -> "prompts/tool/read.txt";
            case "write" -> "prompts/tool/write.txt";
            case "edit" -> "prompts/tool/edit.txt";
            case "glob" -> "prompts/tool/glob.txt";
            case "grep" -> "prompts/tool/grep.txt";
            case "ls" -> "prompts/tool/ls.txt";
            case "todo_read" -> "prompts/tool/todoread.txt";
            case "todo_write" -> "prompts/tool/todowrite.txt";
            case "task" -> "prompts/tool/task.txt";
            case "web_search" -> "prompts/tool/websearch.txt";
            case "search_codebase" -> "prompts/tool/codesearch.txt";
            default -> null;
        };
        String raw = resourcePath == null ? "" : load(resourcePath);
        if (raw.isBlank()) {
            return fallbackDescription(toolId);
        }
        return raw
                .replace("${directory}", workspaceRoot)
                .replace("${maxLines}", String.valueOf(DEFAULT_BASH_MAX_LINES))
                .replace("${maxBytes}", String.valueOf(DEFAULT_BASH_MAX_BYTES))
                .trim();
    }

    private static String fallbackDescription(String toolId) {
        return switch (toolId) {
            case "delete_file" -> "Delete a file from the workspace.";
            case "ide_context" -> "Read IDE project/build environment context on demand. Use this when you need SDK, module, or build-system facts.";
            case "ide_action" -> "Unified IDE-native action tool with async jobs. Supports build/run/test start, status query, cancel, and capability query.";
            case "ide_capabilities" -> "Fetch available IDE-native bridge capabilities (supported operations, run configurations, async job support).";
            default -> toolId;
        };
    }
}
