package com.zzf.rikki.idea.agent.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.idea.settings.RikkiSettings;
import com.zzf.rikki.runtime.port.LlmPort;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LiteChatLlmStreamClient implements LlmPort {
    private final ObjectMapper mapper;

    public LiteChatLlmStreamClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
        RikkiSettings.State settings = RikkiSettings.getInstance().getState();
        String apiKey = settings.currentApiKey();
        if ((apiKey == null || apiKey.isBlank()) && !"OLLAMA".equals(settings.getProvider())) {
            return new LlmStreamResult("Error: API key not configured.", List.of());
        }
        String model = settings.getModelName() == null || settings.getModelName().isBlank() ? "deepseek-chat" : settings.getModelName();
        String baseUrl = settings.currentBaseUrl().replaceAll("/+$", "");
        HttpURLConnection connection = openConnection(baseUrl + "/chat/completions", apiKey);
        if (connection == null) {
            return new LlmStreamResult("Error: cannot connect to LLM endpoint.", List.of());
        }
        try {
            String body = buildRequestBody(model, request.getMessages(), request.getCapabilities(), request.getToolDefinitions());
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            if (connection.getResponseCode() < 200 || connection.getResponseCode() > 299) {
                String errorBody = "";
                try {
                    if (connection.getErrorStream() != null) {
                        errorBody = new String(connection.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                    }
                } catch (Exception ignored) {
                }
                String message = errorBody.isBlank()
                        ? "Error: HTTP " + connection.getResponseCode()
                        : "Error: HTTP " + connection.getResponseCode() + " - " + errorBody.substring(0, Math.min(400, errorBody.length()));
                return new LlmStreamResult(message, List.of());
            }

            StringBuilder textBuffer = new StringBuilder();
            StringBuilder reasoningBuffer = new StringBuilder();
            Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
            String finishReason = "";

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) {
                        continue;
                    }
                    JsonNode chunk;
                    try {
                        chunk = mapper.readTree(data);
                    } catch (Exception ignored) {
                        continue;
                    }
                    JsonNode choice = chunk.path("choices").path(0);
                    JsonNode delta = choice.path("delta");
                    String textDelta = extractChunkTextDelta(choice, delta);
                    if (!textDelta.isEmpty()) {
                        listener.onMessageDelta(request.getMessageId(), textDelta);
                        textBuffer.append(textDelta);
                    }
                    String reasoningDelta = extractChunkReasoningDelta(choice, delta);
                    if (!reasoningDelta.isEmpty()) {
                        listener.onThoughtDelta(request.getMessageId(), reasoningDelta);
                        reasoningBuffer.append(reasoningDelta);
                    }
                    JsonNode toolCallDeltas = delta.path("tool_calls");
                    if (toolCallDeltas.isArray()) {
                        for (JsonNode toolCall : toolCallDeltas) {
                            int index = toolCall.path("index").asInt(0);
                            ToolCallAccumulator current = toolCalls.computeIfAbsent(index, ignored -> new ToolCallAccumulator());
                            String id = toolCall.path("id").asText("");
                            String name = toolCall.path("function").path("name").asText("");
                            String argsChunk = toolCall.path("function").path("arguments").asText("");
                            if (!id.isBlank()) {
                                current.id = id;
                            }
                            if (!name.isBlank()) {
                                current.name = name;
                            }
                            current.arguments.append(argsChunk);
                        }
                    }
                    JsonNode reason = choice.path("finish_reason");
                    if (!reason.isMissingNode() && !reason.isNull()) {
                        finishReason = reason.asText("");
                    }
                }
            }

            if (reasoningBuffer.length() > 0) {
                listener.onThoughtEnd(request.getMessageId());
            }

            List<ToolCallInfo> collected = new ArrayList<>();
            for (ToolCallAccumulator value : toolCalls.values()) {
                if (value.name == null || value.name.isBlank()) {
                    continue;
                }
                String raw = value.arguments.toString();
                JsonNode args;
                try {
                    args = mapper.readTree(raw);
                } catch (Exception ignored) {
                    args = mapper.createObjectNode();
                }
                collected.add(new ToolCallInfo(value.id, value.name, raw, args));
            }
            return new LlmStreamResult(
                    textBuffer.toString(),
                    "tool_calls".equals(finishReason) || !collected.isEmpty() ? collected : List.of(),
                    reasoningBuffer.toString()
            );
        } catch (Exception e) {
            return new LlmStreamResult("Error: " + (e.getMessage() == null ? "LLM request failed." : e.getMessage()), List.of());
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openConnection(String url, String apiKey) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            connection.setDoOutput(true);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(180_000);
            return connection;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildRequestBody(String model, List<Map<String, Object>> messages, ModelCapabilities capabilities, List<Map<String, Object>> toolDefinitions) throws Exception {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", Boolean.TRUE);
        body.put(capabilities.getMaxTokensKey(), 8192);
        body.put("temperature", capabilities.getTemperatureFixed() == null ? 0.1 : capabilities.getTemperatureFixed());
        body.put("messages", messages);
        if (capabilities.getSupportsTools() && toolDefinitions != null && !toolDefinitions.isEmpty()) {
            body.put("tools", toolDefinitions);
        }
        return mapper.writeValueAsString(body);
    }

    private String extractChunkTextDelta(JsonNode choice, JsonNode delta) {
        String value = textFromNode(delta.path("content"));
        if (!value.isEmpty()) return value;
        value = textFromNode(delta.path("text"));
        if (!value.isEmpty()) return value;
        value = textFromNode(choice.path("message").path("content"));
        return value;
    }

    private String extractChunkReasoningDelta(JsonNode choice, JsonNode delta) {
        for (String key : List.of("reasoning_content", "reasoning", "reasoning_delta", "thinking", "reasoning_text")) {
            String value = textFromNode(delta.path(key));
            if (!value.isEmpty()) {
                return value;
            }
        }
        for (String key : List.of("reasoning_content", "reasoning", "thinking")) {
            String value = textFromNode(choice.path("message").path(key));
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String textFromNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : node) {
                String direct = textFromNode(item);
                if (!direct.isEmpty()) {
                    builder.append(direct);
                } else if (item.isObject()) {
                    String nested = textFromNode(item.path("text"));
                    if (!nested.isEmpty()) {
                        builder.append(nested);
                    }
                }
            }
            return builder.toString();
        }
        if (node.isObject()) {
            String nested = textFromNode(node.path("text"));
            if (!nested.isEmpty()) return nested;
            nested = textFromNode(node.path("content"));
            if (!nested.isEmpty()) return nested;
            return "";
        }
        return node.asText("");
    }

    private static final class ToolCallAccumulator {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
    }
}
