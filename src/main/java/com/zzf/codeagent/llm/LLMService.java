package com.zzf.codeagent.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.agent.AgentInfo;
import com.zzf.codeagent.config.ConfigInfo;
import com.zzf.codeagent.config.ConfigManager;
import com.zzf.codeagent.provider.ModelInfo;
import com.zzf.codeagent.provider.ProviderManager;
import com.zzf.codeagent.provider.ProviderTransform;
import com.zzf.codeagent.session.model.MessageV2;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * LLM 閺堝秴濮?(鐎靛綊缍?OpenCode LLM namespace)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMService {

    private static final int MAX_TOOL_DESCRIPTION_LENGTH = 1024;
    private static final int MAX_ERROR_BODY_LENGTH = 1200;

    @Value("${deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com/v1}")
    private String deepseekBaseUrl;

    private final ProviderManager providerManager;
    private final ConfigManager configManager;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();

    @Data
    @Builder
    public static class StreamInput {
        private String sessionID;
        private MessageV2.User user;
        private AgentInfo agent;
        private com.zzf.codeagent.provider.ModelInfo model;
        private List<MessageV2.WithParts> messages;
        private Map<String, Object> tools; // Tool definitions
        private List<String> systemInstructions;
        private Boolean small;
    }

    public interface StreamCallback {
        default void onStart() {}
        default void onTextStart(String id, Map<String, Object> metadata) {}
        default void onTextDelta(String text, Map<String, Object> metadata) {}
        default void onTextEnd(Map<String, Object> metadata) {}
        default void onReasoningStart(String id, Map<String, Object> metadata) {}
        default void onReasoningDelta(String text, Map<String, Object> metadata) {}
        default void onReasoningEnd(Map<String, Object> metadata) {}
        default void onToolInputStart(String name, String id) {}
        default void onToolCall(String name, String id, Map<String, Object> input, Map<String, Object> metadata) {}
        default void onToolResult(String id, Map<String, Object> input, Object output, Map<String, Object> metadata) {}
        default void onToolError(String id, Map<String, Object> input, Throwable error) {}
        default void onStepStart() {}
        default void onStepFinish(String finishReason, Map<String, Object> usage, Map<String, Object> metadata) {}
        default void onComplete(String finishReason) {}
        default void onError(Throwable t) {}
    }

    public CompletableFuture<Void> stream(StreamInput input, StreamCallback callback) {
        return stream(input, callback, () -> false);
    }

    public CompletableFuture<Void> stream(StreamInput input, StreamCallback callback, BooleanSupplier cancelRequested) {
        BooleanSupplier cancellation = cancelRequested != null ? cancelRequested : () -> false;
        // 1. Resolve Model
        ModelInfo model = input.model;
        if (model == null && input.agent != null && input.agent.getModel() != null) {
            AgentInfo.AgentModel agentModel = input.agent.getModel();
            model = providerManager.getModel(agentModel.getProviderID(), agentModel.getModelID()).orElse(null);
        }
        
        String providerID = model != null ? model.getProviderID() : (input.agent != null && input.agent.getModel() != null ? input.agent.getModel().getProviderID() : "openai");
        String modelID = model != null ? model.getId() : (input.agent != null && input.agent.getModel() != null ? input.agent.getModel().getModelID() : "gpt-4o");

        // 2. Resolve Tools (Align with OpenCode resolveTools)
        Map<String, Object> activeTools = resolveTools(input);

        // 3. Prepare Request
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelID);
        requestBody.put("stream", true);

        // Model Parameters (Align with LLM.ts)
        Double temperature = null;
        Double topP = null;
        Integer topK = null;
        Map<String, Object> options = new HashMap<>();

        if (model != null) {
            if (model.getCapabilities() != null && model.getCapabilities().isTemperature()) {
                temperature = input.agent.getTemperature() != null ? input.agent.getTemperature() : ProviderTransform.temperature(model);
            }
            topP = input.agent.getTopP() != null ? input.agent.getTopP() : ProviderTransform.topP(model);
            topK = ProviderTransform.topK(model);

            Map<String, Object> baseOptions = input.small != null && input.small
                    ? ProviderTransform.smallOptions(model)
                    : ProviderTransform.options(model, input.sessionID);
            
            options.putAll(baseOptions);
            if (model.getOptions() != null) options.putAll(model.getOptions());
            if (input.agent.getOptions() != null) options.putAll(input.agent.getOptions());
        }

        if (temperature != null) requestBody.put("temperature", temperature);
        if (topP != null) requestBody.put("top_p", topP);
        if (topK != null) requestBody.put("top_k", topK);
        
        if (model != null) {
            Map<String, Object> providerOpts = ProviderTransform.providerOptions(model, options);
            if (!providerOpts.isEmpty()) {
                requestBody.put("provider_options", providerOpts);
            }
            // Some options might still need to go to the root for specific providers
            requestBody.putAll(options);
        }

        List<Map<String, Object>> messages = convertMessages(input.messages, input.systemInstructions);
        if (model != null) {
            messages = ProviderTransform.message(messages, model, options);
        }
        requestBody.put("messages", messages);
        
        // LiteLLM compatibility
        boolean isLiteLLMProxy = providerID.toLowerCase().contains("litellm");
        if (isLiteLLMProxy && activeTools.isEmpty() && hasToolCalls(input.messages)) {
            activeTools.put("_noop", createNoopTool());
        }

        if (!activeTools.isEmpty()) {
            requestBody.put("tools", convertToOpenAITools(activeTools));
        }

        // 4. Get API Config
        String baseUrl = "https://api.openai.com/v1";
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            if (providerID.equalsIgnoreCase("deepseek")) {
                apiKey = System.getenv("DEEPSEEK_API_KEY");
            }
        }

        ConfigInfo config = configManager.getConfig();
        if (config != null && config.getProvider() != null) {
            ConfigInfo.ProviderConfig providerConfig = config.getProvider().get(providerID);
            if (providerConfig != null && providerConfig.getApi() != null) {
                if (providerConfig.getApi().getBaseUrl() != null) baseUrl = providerConfig.getApi().getBaseUrl();
                if (providerConfig.getApi().getApiKey() != null) apiKey = providerConfig.getApi().getApiKey();
            }
        }
        
        // Fallback for DeepSeek if still default or missing
        if (providerID.equalsIgnoreCase("deepseek")) {
            if (apiKey == null || apiKey.isEmpty() || apiKey.startsWith("${")) {
                apiKey = deepseekApiKey;
            }
            if (baseUrl == null || baseUrl.isEmpty() || baseUrl.contains("openai.com")) {
                baseUrl = deepseekBaseUrl;
            }
        }

        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.info("LLM Request to {}: {}", baseUrl, jsonBody);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofMinutes(10))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            callback.onStart();
            callback.onStepStart();
            final String[] finalFinishReason = {"stop"};
            final boolean[] textOpen = {false};
            final boolean[] reasoningOpen = {false};

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                    .thenAccept(response -> {
                        if (cancellation.getAsBoolean()) {
                            callback.onError(new CancellationException("LLM stream cancelled"));
                            return;
                        }
                        if (response.statusCode() != 200) {
                            String errorBody = "";
                            try (Stream<String> errorLines = response.body()) {
                                errorBody = errorLines.collect(java.util.stream.Collectors.joining("\n"));
                            } catch (Exception e) {
                                log.warn("Failed to read LLM error response body", e);
                            }
                            log.error("LLM Request failed with status: {}. Response body: {}. Request body: {}",
                                    response.statusCode(),
                                    truncateForLog(errorBody, MAX_ERROR_BODY_LENGTH),
                                    truncateForLog(jsonBody, MAX_ERROR_BODY_LENGTH));
                            callback.onError(new RuntimeException("LLM Error: " + response.statusCode() + " - " + truncateForLog(errorBody, 300)));
                            return;
                        }
                        
                        try (Stream<String> lines = response.body()) {
                            Map<Integer, String> toolCallNames = new HashMap<>();
                            Map<Integer, String> toolCallIds = new HashMap<>();
                            Map<Integer, StringBuilder> toolCallArgs = new HashMap<>();
                            Set<Integer> toolInputStarted = new HashSet<>();
                            java.util.Iterator<String> iterator = lines.iterator();
                            boolean cancelledMidStream = false;
                            while (iterator.hasNext()) {
                                if (cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                                    cancelledMidStream = true;
                                    break;
                                }
                                String line = iterator.next();
                                if (line.startsWith("data:")) {
                                    String data = line.substring(5).trim();
                                    if ("[DONE]".equals(data)) {
                                        continue;
                                    }
                                    try {
                                        Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                                        List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                                        if (choices != null && !choices.isEmpty()) {
                                            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");

                                            if (delta.containsKey("reasoning_content") && delta.get("reasoning_content") != null) {
                                                if (!reasoningOpen[0]) {
                                                    callback.onReasoningStart("reasoning", null);
                                                    reasoningOpen[0] = true;
                                                }
                                                callback.onReasoningDelta((String) delta.get("reasoning_content"), null);
                                            }

                                            if (delta.containsKey("content") && delta.get("content") != null) {
                                                if (!textOpen[0]) {
                                                    callback.onTextStart("text", null);
                                                    textOpen[0] = true;
                                                }
                                                callback.onTextDelta((String) delta.get("content"), null);
                                            }

                                            if (delta.containsKey("tool_calls")) {
                                                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
                                                for (Map<String, Object> tc : toolCalls) {
                                                    Object indexObj = tc.get("index");
                                                    Integer index = indexObj instanceof Number ? ((Number) indexObj).intValue() : null;
                                                    if (index == null) continue;

                                                    Map<String, Object> function = (Map<String, Object>) tc.get("function");
                                                    String id = tc.get("id") instanceof String ? (String) tc.get("id") : null;

                                                    if (id != null) {
                                                        toolCallIds.putIfAbsent(index, id);
                                                    }

                                                    String callId = toolCallIds.computeIfAbsent(index, k -> "call_" + UUID.randomUUID());

                                                    if (function != null) {
                                                        String name = (String) function.get("name");
                                                        if (name != null) {
                                                            toolCallNames.put(index, name);
                                                            if (toolInputStarted.add(index)) {
                                                                callback.onToolInputStart(name, callId);
                                                            }
                                                        }

                                                        if (function.containsKey("arguments")) {
                                                            String argChunk = (String) function.get("arguments");
                                                            if (argChunk != null) {
                                                                toolCallArgs.computeIfAbsent(index, k -> new StringBuilder()).append(argChunk);
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            String finishReason = (String) choices.get(0).get("finish_reason");
                                            if (finishReason != null) {
                                                finalFinishReason[0] = "tool_calls".equals(finishReason) ? "tool-calls" : finishReason;
                                                if ("tool_calls".equals(finishReason)) {
                                                    toolCallArgs.forEach((index, argsBuilder) -> {
                                                        String name = toolCallNames.get(index);
                                                        if (name == null || name.isBlank()) {
                                                            return;
                                                        }
                                                        String id = toolCallIds.computeIfAbsent(index, k -> "call_" + UUID.randomUUID());
                                                        String fullArgs = argsBuilder.toString();
                                                        try {
                                                            Map<String, Object> parsedArgs = objectMapper.readValue(fullArgs, Map.class);
                                                            callback.onToolCall(name, id, parsedArgs, null);
                                                        } catch (Exception e) {
                                                            log.error("Failed to parse tool arguments: {}", fullArgs, e);
                                                            callback.onToolCall(name, id, new HashMap<>(), null);
                                                        }
                                                    });
                                                    toolCallArgs.clear();
                                                    toolCallNames.clear();
                                                    toolCallIds.clear();
                                                    toolInputStarted.clear();
                                                }
                                                if (textOpen[0]) {
                                                    callback.onTextEnd(null);
                                                    textOpen[0] = false;
                                                }
                                                if (reasoningOpen[0]) {
                                                    callback.onReasoningEnd(null);
                                                    reasoningOpen[0] = false;
                                                }
                                                callback.onStepFinish(finalFinishReason[0], (Map<String, Object>) chunk.get("usage"), null);
                                            }
                                        }
                                    } catch (Exception e) {
                                        log.error("Parse error: {}", data, e);
                                    }
                                }
                            }
                            if (cancelledMidStream) {
                                if (textOpen[0]) {
                                    callback.onTextEnd(null);
                                    textOpen[0] = false;
                                }
                                if (reasoningOpen[0]) {
                                    callback.onReasoningEnd(null);
                                    reasoningOpen[0] = false;
                                }
                                callback.onError(new CancellationException("LLM stream cancelled"));
                                return;
                            }
                        }
                        if (textOpen[0]) {
                            callback.onTextEnd(null);
                        }
                        if (reasoningOpen[0]) {
                            callback.onReasoningEnd(null);
                        }
                        callback.onComplete(finalFinishReason[0]);
                    });

        } catch (Exception e) {
            callback.onError(e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private Map<String, Object> resolveTools(StreamInput input) {
        Map<String, Object> result = new HashMap<>(input.tools != null ? input.tools : Map.of());
        // Permission check could go here, for now just filter based on user preference
        if (input.user != null && input.user.getTools() != null) {
            result.keySet().removeIf(toolName -> input.user.getTools().get(toolName) == Boolean.FALSE);
        }
        return result;
    }

    private boolean hasToolCalls(List<MessageV2.WithParts> messages) {
        for (MessageV2.WithParts msg : messages) {
            for (com.zzf.codeagent.session.model.PromptPart part : msg.getParts()) {
                if (part instanceof MessageV2.ToolPart) return true;
            }
        }
        return false;
    }

    private Map<String, Object> createNoopTool() {
        Map<String, Object> noop = new HashMap<>();
        noop.put("type", "function");
        Map<String, Object> function = new HashMap<>();
        function.put("name", "_noop");
        function.put("description", "Placeholder for LiteLLM compatibility");
        function.put("parameters", Map.of("type", "object", "properties", new HashMap<>()));
        noop.put("function", function);
        return noop;
    }

    private List<Map<String, Object>> convertToOpenAITools(Map<String, Object> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        tools.forEach((name, def) -> {
            if (def instanceof Map) {
                Map<String, Object> mapDef = (Map<String, Object>) def;
                if (isOpenAIFunctionTool(mapDef)) {
                    result.add(normalizeOpenAIFunctionTool(name, mapDef));
                } else {
                    result.add(wrapAsOpenAIFunctionTool(name, mapDef, null));
                }
            } else if (def instanceof com.fasterxml.jackson.databind.JsonNode) {
                Map<String, Object> schema = objectMapper.convertValue(def, Map.class);
                result.add(wrapAsOpenAIFunctionTool(name, schema, null));
            } else if (def != null) {
                log.warn("Skip unsupported tool definition type for {}: {}", name, def.getClass().getName());
            }
        });
        return result;
    }

    private boolean isOpenAIFunctionTool(Map<String, Object> toolDef) {
        if (!"function".equals(toolDef.get("type"))) {
            return false;
        }
        Object function = toolDef.get("function");
        return function instanceof Map && ((Map<?, ?>) function).containsKey("name");
    }

    private Map<String, Object> wrapAsOpenAIFunctionTool(
            String name,
            Map<String, Object> parametersSchema,
            String description
    ) {
        Map<String, Object> function = new HashMap<>();
        function.put("name", name);
        function.put("description", sanitizeToolDescription(description));
        function.put("parameters", parametersSchema != null ? parametersSchema : Map.of("type", "object", "properties", new HashMap<>()));

        Map<String, Object> tool = new HashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private Map<String, Object> normalizeOpenAIFunctionTool(String fallbackName, Map<String, Object> toolDef) {
        Map<String, Object> normalized = new HashMap<>(toolDef);
        Object functionObj = normalized.get("function");
        if (!(functionObj instanceof Map)) {
            return wrapAsOpenAIFunctionTool(fallbackName, Map.of("type", "object", "properties", new HashMap<>()), "");
        }

        Map<String, Object> function = new HashMap<>((Map<String, Object>) functionObj);
        String functionName = function.get("name") != null ? String.valueOf(function.get("name")) : fallbackName;
        function.put("name", functionName);
        function.put("description", sanitizeToolDescription(function.get("description") != null ? String.valueOf(function.get("description")) : ""));

        Object parameters = function.get("parameters");
        if (!(parameters instanceof Map)) {
            function.put("parameters", Map.of("type", "object", "properties", new HashMap<>()));
        }

        normalized.put("type", "function");
        normalized.put("function", function);
        return normalized;
    }

    private String sanitizeToolDescription(String description) {
        if (description == null || description.isEmpty()) {
            return "";
        }
        String compact = description.replaceAll("\\s+", " ").trim();
        if (compact.length() <= MAX_TOOL_DESCRIPTION_LENGTH) {
            return compact;
        }
        return compact.substring(0, Math.max(0, MAX_TOOL_DESCRIPTION_LENGTH - 3)).trim() + "...";
    }

    private String truncateForLog(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private List<Map<String, Object>> convertMessages(List<MessageV2.WithParts> messages, List<String> systemInstructions) {
        List<Map<String, Object>> result = new ArrayList<>();

        if (systemInstructions != null && !systemInstructions.isEmpty()) {
            Map<String, Object> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", String.join("\n", systemInstructions));
            result.add(systemMsg);
        }

        for (MessageV2.WithParts msg : messages) {
            Map<String, Object> m = new HashMap<>();
            m.put("role", msg.getInfo().getRole());

            List<Map<String, Object>> contentParts = new ArrayList<>();
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            List<Map<String, Object>> toolResults = new ArrayList<>();

            for (com.zzf.codeagent.session.model.PromptPart part : msg.getParts()) {
                if (part instanceof MessageV2.TextPart) {
                    String text = ((MessageV2.TextPart) part).getText();
                    if (text == null || text.isEmpty()) {
                        continue;
                    }
                    Map<String, Object> cp = new HashMap<>();
                    cp.put("type", "text");
                    cp.put("text", text);
                    contentParts.add(cp);
                } else if (part instanceof MessageV2.ToolPart) {
                    MessageV2.ToolPart tp = (MessageV2.ToolPart) part;
                    if (tp.getState() == null) {
                        continue;
                    }
                    String status = tp.getState().getStatus();
                    String callId = tp.getCallID();
                    if (callId == null || callId.isBlank()) {
                        callId = "call_" + UUID.randomUUID();
                    }
                    String toolName = tp.getTool() != null ? tp.getTool() : "";

                    Map<String, Object> tc = new HashMap<>();
                    tc.put("id", callId);
                    tc.put("type", "function");
                    Map<String, Object> fn = new HashMap<>();
                    fn.put("name", toolName);
                    Map<String, Object> input = tp.getState().getInput() != null ? tp.getState().getInput() : tp.getArgs();
                    try {
                        fn.put("arguments", objectMapper.writeValueAsString(input != null ? input : Map.of()));
                    } catch (Exception e) {
                        fn.put("arguments", "{}");
                    }
                    tc.put("function", fn);
                    toolCalls.add(tc);

                    boolean shouldEmitResult = "completed".equals(status) || "error".equals(status)
                            || "pending".equals(status) || "running".equals(status);
                    if (shouldEmitResult) {
                        String output;
                        if ("completed".equals(status)) {
                            output = tp.getState().getOutput();
                        } else if ("error".equals(status)) {
                            output = tp.getState().getError();
                        } else {
                            output = "[Tool execution was interrupted]";
                        }
                        Map<String, Object> tr = new HashMap<>();
                        tr.put("role", "tool");
                        tr.put("tool_call_id", callId);
                        tr.put("content", output != null ? output : "");
                        toolResults.add(tr);
                    }
                }
            }

            if (!contentParts.isEmpty() && !"tool".equals(msg.getInfo().getRole())) {
                m.put("content", contentParts);
            } else if (toolCalls.isEmpty()) {
                m.put("content", "");
            }

            if (!toolCalls.isEmpty()) {
                m.put("tool_calls", toolCalls);
            }

            if (m.containsKey("content") || m.containsKey("tool_calls")) {
                result.add(m);
            }
            result.addAll(toolResults);
        }
        return result;
    }

}


