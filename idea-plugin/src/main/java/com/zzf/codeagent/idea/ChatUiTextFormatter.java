package com.zzf.codeagent.idea;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

final class ChatUiTextFormatter {

    private ChatUiTextFormatter() {
    }

    static String summarizeInputDetails(JsonNode argsNode) {
        if (argsNode == null || argsNode.isMissingNode() || argsNode.isNull()) {
            return "";
        }
        if (argsNode.isTextual()) {
            return argsNode.asText("");
        }
        if (!argsNode.isObject()) {
            return trimForUi(argsNode.toString(), 600);
        }
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = argsNode.fields();
        int count = 0;
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode valueNode = field.getValue();
            String value;
            if (valueNode == null || valueNode.isNull()) {
                value = "null";
            } else if (valueNode.isTextual()) {
                value = valueNode.asText("");
            } else if (valueNode.isNumber() || valueNode.isBoolean()) {
                value = valueNode.asText();
            } else if (valueNode.isArray()) {
                value = "[array size=" + valueNode.size() + "]";
            } else if (valueNode.isObject()) {
                value = "{object}";
            } else {
                value = valueNode.toString();
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(field.getKey()).append(": ").append(trimForUi(value, 260));
            count++;
            if (count >= 12 && fields.hasNext()) {
                sb.append("\n... (more args omitted)");
                break;
            }
        }
        return sb.toString();
    }

    static String summarizeArgs(JsonNode argsNode) {
        if (argsNode == null || argsNode.isMissingNode() || argsNode.isNull()) {
            return "";
        }
        if (argsNode.isObject()) {
            String command = argsNode.path("command").asText("");
            if (!command.isBlank()) {
                return trimForUi(command, 140);
            }
            String description = argsNode.path("description").asText("");
            if (!description.isBlank()) {
                return trimForUi(description, 140);
            }
            String filePath = argsNode.path("filePath").asText(argsNode.path("path").asText(""));
            if (!filePath.isBlank()) {
                return trimForUi(filePath, 140);
            }
        }
        if (!argsNode.isObject()) {
            return trimForUi(argsNode.asText(""), 160);
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = argsNode.fields();
        while (fields.hasNext() && count < 3) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String value = field.getValue() == null ? "" : field.getValue().asText(field.getValue().toString());
            sb.append(field.getKey()).append("=").append(trimForUi(value, 40));
            count++;
        }
        return sb.toString();
    }

    static String summarizeCommand(JsonNode argsNode, String fallback) {
        if (argsNode == null || argsNode.isMissingNode() || argsNode.isNull()) {
            return fallback == null ? "" : fallback;
        }
        if (argsNode.isObject()) {
            String command = argsNode.path("command").asText(argsNode.path("cmd").asText(""));
            if (!command.isBlank()) {
                return trimForUi(command, 180);
            }

            String filePath = argsNode.path("filePath").asText(argsNode.path("path").asText(""));
            String pattern = argsNode.path("pattern").asText("");
            if (!filePath.isBlank() && !pattern.isBlank()) {
                return trimForUi(filePath + " pattern=" + pattern, 180);
            }
            if (!filePath.isBlank()) {
                return trimForUi(filePath, 180);
            }

            String query = argsNode.path("query").asText("");
            if (!query.isBlank()) {
                return trimForUi(query, 180);
            }
        }
        if (!argsNode.isObject()) {
            String text = argsNode.asText("");
            if (!text.isBlank()) {
                return trimForUi(text, 180);
            }
        }
        return fallback == null ? "" : trimForUi(fallback, 180);
    }

    static String extractBashCommand(JsonNode argsNode, String fallback) {
        if (argsNode != null && argsNode.isObject()) {
            String command = firstNonBlank(
                    argsNode.path("command").asText(""),
                    argsNode.path("cmd").asText("")
            );
            if (!command.isBlank()) {
                return command;
            }
        }
        return firstNonBlank(fallback);
    }

    static String trimForUi(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen - 3)).trim() + "...";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
