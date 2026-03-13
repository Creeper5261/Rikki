package com.zzf.rikki.idea.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LiteTodoTools {
    private final ObjectMapper mapper;

    public LiteTodoTools(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String read(String workspaceRoot, String sessionId) {
        File file = todoFile(workspaceRoot, sessionId);
        if (!file.exists()) {
            return "No todos found.";
        }
        try {
            List<?> todos = mapper.readValue(file, List.class);
            return todos.isEmpty() ? "No todos found." : mapper.writerWithDefaultPrettyPrinter().writeValueAsString(todos);
        } catch (Exception ignored) {
            return "No todos found.";
        }
    }

    public String write(JsonNode args, String workspaceRoot, String sessionId) throws Exception {
        JsonNode todosNode = args.path("todos");
        if (!todosNode.isArray()) {
            throw new IllegalArgumentException("todos must be an array");
        }
        List<Map<String, Object>> todos = new ArrayList<>();
        for (JsonNode item : todosNode) {
            String content = item.path("content").asText("");
            if (content.isBlank()) {
                content = item.path("title").asText("");
            }
            if (content.isBlank()) {
                content = item.path("description").asText("");
            }
            LinkedHashMap<String, Object> todo = new LinkedHashMap<>();
            todo.put("id", item.path("id").asText("").isBlank() ? UUID.randomUUID().toString() : item.path("id").asText(""));
            todo.put("content", content);
            todo.put("status", item.path("status").asText("pending"));
            todo.put("priority", item.path("priority").asText("medium"));
            todos.add(todo);
        }
        File file = todoFile(workspaceRoot, sessionId);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        Files.writeString(file.toPath(), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(todos), StandardCharsets.UTF_8);
        return "Todos updated: " + todos.size() + " item(s)";
    }

    public String readJson(String workspaceRoot, String sessionId) {
        File file = todoFile(workspaceRoot, sessionId);
        if (!file.exists()) {
            return null;
        }
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            return text.isBlank() ? null : text;
        } catch (Exception ignored) {
            return null;
        }
    }

    private File todoFile(String workspaceRoot, String sessionId) {
        File sessionFile = sessionTodoFile(workspaceRoot, sessionId);
        return sessionFile != null ? sessionFile : new File(workspaceRoot, ".rikki/todos.json");
    }

    private File sessionTodoFile(String workspaceRoot, String sessionId) {
        if (sessionId == null || sessionId.trim().isBlank()) {
            return null;
        }
        return new File(workspaceRoot, ".rikki/todos/" + safeSessionId(sessionId.trim()) + ".json");
    }

    private String safeSessionId(String sessionId) {
        StringBuilder builder = new StringBuilder();
        for (char ch : sessionId.toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == '.') {
                builder.append(ch);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }
}
