package com.zzf.codeagent.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonUtils {

    public static String textOrFallback(JsonNode args, String... keys) {
        if (args == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            JsonNode node = args.get(key);
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                String val = node.asText("").trim();
                if (!val.isEmpty()) {
                    return val;
                }
            }
        }
        for (String key : keys) {
            JsonNode node = args.get(key);
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                return node.asText("");
            }
        }
        return "";
    }

    public static Integer intOrNull(JsonNode args, String... keys) {
        String val = textOrFallback(args, keys);
        if (val == null || val.isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(val.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String extractFirstJsonObject(String text) {
        if (text == null) {
            return "{}";
        }
        text = text.trim();
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }
        
        // Try to find first { and matching }
        int start = text.indexOf('{');
        if (start == -1) {
            return "{}";
        }
        
        int balance = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                balance++;
            } else if (c == '}') {
                balance--;
                if (balance == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return "{}";
    }
}
