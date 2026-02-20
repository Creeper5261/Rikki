package com.zzf.rikki.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class IdeContextTool implements Tool {

    private static final int DEFAULT_MAX_ITEMS = 20;
    private static final int MAX_ALLOWED_ITEMS = 100;
    private static final int MAX_STRING_LENGTH = 1000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String getId() {
        return "ide_context";
    }

    @Override
    public String getDescription() {
        return "Read IDE project/build environment context on demand. Use this when you need SDK, module, or build-system facts.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode query = properties.putObject("query");
        query.put("type", "string");
        ArrayNode enums = query.putArray("enum");
        enums.add("project");
        enums.add("sdk");
        enums.add("build");
        enums.add("modules");
        enums.add("all");
        query.put("description", "Which IDE context section to read. Defaults to all.");

        ObjectNode keys = properties.putObject("keys");
        keys.put("type", "array");
        keys.putObject("items").put("type", "string");
        keys.put("description", "Optional exact keys to return from IDE context.");

        ObjectNode maxItems = properties.putObject("maxItems");
        maxItems.put("type", "integer");
        maxItems.put("description", "Max list items to return (default 20, max 100).");

        return schema;
    }

    @Override
    public java.util.concurrent.CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Map<String, Object> extra = ctx != null ? ctx.getExtra() : null;
            Map<String, Object> ideContext = extractIdeContext(extra);
            if (ideContext.isEmpty()) {
                return Result.builder()
                        .title("IDE context unavailable")
                        .output("No IDE context is available for this session. Ask user to run from IDE plugin with indexing ready.")
                        .metadata(Map.of("available", false))
                        .build();
            }

            String query = args != null && args.has("query") ? args.path("query").asText("all") : "all";
            int maxItems = args != null && args.has("maxItems")
                    ? Math.max(1, Math.min(MAX_ALLOWED_ITEMS, args.path("maxItems").asInt(DEFAULT_MAX_ITEMS)))
                    : DEFAULT_MAX_ITEMS;

            List<String> requestedKeys = new ArrayList<>();
            if (args != null && args.has("keys") && args.path("keys").isArray()) {
                for (JsonNode node : args.path("keys")) {
                    String key = node.asText("");
                    if (!key.isBlank()) {
                        requestedKeys.add(key.trim());
                    }
                }
            }

            Map<String, Object> filtered = filterContext(ideContext, query, requestedKeys, maxItems);
            String output;
            try {
                output = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(filtered);
            } catch (Exception e) {
                output = String.valueOf(filtered);
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("available", true);
            metadata.put("query", query);
            metadata.put("keys", requestedKeys);
            metadata.put("key_count", filtered.size());

            return Result.builder()
                    .title("IDE context: " + query)
                    .output(output)
                    .metadata(metadata)
                    .build();
        });
    }

    private Map<String, Object> extractIdeContext(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return Map.of();
        }
        Object raw = extra.get("ideContext");
        if (!(raw instanceof Map<?, ?> source) || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                continue;
            }
            String key = ((String) entry.getKey()).trim();
            if (key.isBlank()) {
                continue;
            }
            Object value = sanitizeValue(entry.getValue(), DEFAULT_MAX_ITEMS);
            if (value != null) {
                normalized.put(key, value);
            }
        }
        return normalized;
    }

    private Map<String, Object> filterContext(
            Map<String, Object> ideContext,
            String query,
            List<String> requestedKeys,
            int maxItems
    ) {
        if (ideContext == null || ideContext.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        String normalizedQuery = query == null ? "all" : query.trim().toLowerCase();

        if (requestedKeys != null && !requestedKeys.isEmpty()) {
            for (String key : requestedKeys) {
                if (ideContext.containsKey(key)) {
                    Object sanitized = sanitizeValue(ideContext.get(key), maxItems);
                    if (sanitized != null) {
                        result.put(key, sanitized);
                    }
                }
            }
            return result;
        }

        for (Map.Entry<String, Object> entry : ideContext.entrySet()) {
            String key = entry.getKey();
            if (!matchesQuery(key, normalizedQuery)) {
                continue;
            }
            Object sanitized = sanitizeValue(entry.getValue(), maxItems);
            if (sanitized != null) {
                result.put(key, sanitized);
            }
        }
        return result;
    }

    private boolean matchesQuery(String key, String query) {
        if (query == null || query.isBlank() || "all".equals(query)) {
            return true;
        }
        String k = key == null ? "" : key.toLowerCase();
        return switch (query) {
            case "project" -> k.contains("project") || k.contains("workspace") || k.contains("index");
            case "sdk" -> k.contains("sdk") || k.contains("java") || k.contains("language");
            case "build" -> k.contains("gradle") || k.contains("mvn") || k.contains("pom") || k.contains("build") || k.contains("settings");
            case "modules" -> k.contains("module");
            default -> true;
        };
    }

    private Object sanitizeValue(Object value, int maxItems) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.length() <= MAX_STRING_LENGTH) {
                return trimmed;
            }
            return trimmed.substring(0, MAX_STRING_LENGTH) + "...";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> limited = new ArrayList<>();
            int limit = Math.min(Math.max(1, maxItems), list.size());
            for (int i = 0; i < limit; i++) {
                Object item = sanitizeValue(list.get(i), maxItems);
                if (item != null) {
                    limited.add(item);
                }
            }
            return limited;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count >= maxItems) {
                    break;
                }
                if (!(entry.getKey() instanceof String key)) {
                    continue;
                }
                Object sanitized = sanitizeValue(entry.getValue(), maxItems);
                if (sanitized != null) {
                    nested.put(key, sanitized);
                    count++;
                }
            }
            return nested;
        }
        return String.valueOf(value);
    }
}

