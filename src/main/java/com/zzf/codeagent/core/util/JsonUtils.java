package com.zzf.codeagent.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JsonUtils {
    private static final Pattern FENCED_JSON = Pattern.compile("(?s)```(?:json)?\\s*(\\{.*?\\})\\s*```");
    private static final Pattern FIRST_BRACE = Pattern.compile("\\{");

    private JsonUtils() {}

    public static String extractFirstJsonObject(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("raw is null");
        }
        Matcher fenced = FENCED_JSON.matcher(raw);
        if (fenced.find()) {
            String inside = fenced.group(1);
            if (inside != null && inside.trim().startsWith("{")) {
                return inside.trim();
            }
        }
        String cleaned = raw.replace("```json", "").replace("```JSON", "").replace("```", "").trim();
        Matcher m = FIRST_BRACE.matcher(cleaned);
        if (!m.find()) {
            throw new IllegalArgumentException("no json object found");
        }
        int start = m.start();
        return balancedJsonObject(cleaned, start);
    }

    private static String balancedJsonObject(String text, int startIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = startIndex; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(startIndex, i + 1);
                }
            }
        }
        throw new IllegalArgumentException("unterminated json");
    }

    public static Integer intOrNull(JsonNode args, String k1, String k2) {
        JsonNode n1 = args.path(k1);
        if (n1 != null && n1.isNumber()) {
            return n1.asInt();
        }
        JsonNode n2 = args.path(k2);
        if (n2 != null && n2.isNumber()) {
            return n2.asInt();
        }
        return null;
    }

    public static String textOrFallback(JsonNode args, String k1, String k2) {
        String a = args.path(k1).asText("");
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        String b = args.path(k2).asText("");
        return b == null ? "" : b.trim();
    }
}
