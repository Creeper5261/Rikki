package com.zzf.codeagent.completion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.config.ConfigInfo;
import com.zzf.codeagent.config.ConfigManager;
import com.zzf.codeagent.provider.ModelInfo;
import com.zzf.codeagent.provider.ProviderManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

@Slf4j
@Service
public class CompletionService {

    private static final String SYSTEM_PROMPT =
            "You are a code completion engine. Your task: complete the code at <|CURSOR|>.\n" +
            "Rules:\n" +
            "- Output ONLY the raw completion text. Nothing else.\n" +
            "- No explanation. No markdown. No backticks. No prose.\n" +
            "- Stop at a natural completion point (end of statement, block, or function).\n" +
            "- Match the indentation and style of the surrounding code.";

    // --- Chat model (fallback) ---
    @Value("${deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com/v1}")
    private String deepseekBaseUrl;

    // --- Dedicated fast model for completion (independent chain) ---
    @Value("${deepseek.fast-base-url:}")
    private String deepseekFastBaseUrl;

    @Value("${deepseek.fast-model-name:}")
    private String deepseekFastModelName;

    private final ConfigManager configManager;
    private final ProviderManager providerManager;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public CompletionService(ConfigManager configManager,
                             ProviderManager providerManager,
                             ObjectMapper objectMapper) {
        this.configManager = configManager;
        this.providerManager = providerManager;
        this.objectMapper = objectMapper;
    }

    public SseEmitter complete(CompletionRequest request) {
        SseEmitter emitter = new SseEmitter(30_000L);

        // Resolve the fast/dedicated completion model first; fall back to default chat model
        String modelId;
        String baseUrl;
        String apiKey;

        if (deepseekFastModelName != null && !deepseekFastModelName.isBlank()) {
            // Fast model configured — fully independent from chat chain
            modelId = deepseekFastModelName;
            baseUrl = (deepseekFastBaseUrl != null && !deepseekFastBaseUrl.isBlank())
                    ? deepseekFastBaseUrl : deepseekBaseUrl;
            apiKey = deepseekApiKey;
        } else {
            // Fall back: use the same model resolution as chat
            ModelInfo model = resolveModelFromConfig();
            modelId = model.getId();
            String[] cfg = resolveApiConfig(model.getProviderID());
            baseUrl = cfg[0];
            apiKey = cfg[1];
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Completion: no API key configured");
            emitter.complete();
            return emitter;
        }

        String body;
        try {
            body = buildRequestBody(request, modelId);
        } catch (Exception e) {
            log.error("Completion: failed to build request body", e);
            emitter.complete();
            return emitter;
        }

        final String finalBaseUrl = baseUrl;
        final String finalApiKey = apiKey;
        final String finalBody = body;
        final String finalModelId = modelId;

        emitter.onTimeout(emitter::complete);
        emitter.onError(ignored -> emitter.complete());

        CompletableFuture.runAsync(() -> {
            try {
                streamTokens(finalBaseUrl, finalApiKey, finalBody, emitter);
            } catch (Exception e) {
                log.error("Completion stream error model={}", finalModelId, e);
                try { sendData(emitter, "[DONE]"); } catch (Exception ignored) {}
                emitter.complete();
            }
        });

        return emitter;
    }

    private void streamTokens(String baseUrl, String apiKey, String body, SseEmitter emitter) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<Stream<String>> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                log.warn("Completion HTTP {}", response.statusCode());
                sendData(emitter, "[DONE]");
                emitter.complete();
                return;
            }

            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> {
                    if (!line.startsWith("data:")) return;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) return;
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> choices =
                                (List<Map<String, Object>>) chunk.get("choices");
                        if (choices == null || choices.isEmpty()) return;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> delta =
                                (Map<String, Object>) choices.get(0).get("delta");
                        if (delta == null) return;
                        Object content = delta.get("content");
                        if (content instanceof String token && !token.isEmpty()) {
                            sendData(emitter, token);
                        }
                    } catch (Exception e) {
                        log.debug("Skipping unparseable SSE line: {}", line);
                    }
                });
            }

            sendData(emitter, "[DONE]");
            emitter.complete();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try { sendData(emitter, "[DONE]"); } catch (Exception ignored) {}
            emitter.complete();
        } catch (Exception e) {
            log.error("Completion stream failed", e);
            try { sendData(emitter, "[DONE]"); } catch (Exception ignored) {}
            emitter.complete();
        }
    }

    private void sendData(SseEmitter emitter, String data) throws IOException {
        synchronized (emitter) {
            if ("[DONE]".equals(data)) {
                emitter.send(SseEmitter.event().data("[DONE]"));
            } else {
                // JSON-encode so embedded \n/\t stay on one SSE data: line instead of
                // being split into multiple data: lines (which would lose the newlines).
                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(data)));
            }
        }
    }

    private String buildRequestBody(CompletionRequest req, String modelId) throws Exception {
        String lang = req.getLanguage() != null ? req.getLanguage() : "unknown";
        String prefix = req.getPrefix() != null ? req.getPrefix() : "";
        String suffix = req.getSuffix() != null ? req.getSuffix() : "";
        String userContent = "Language: " + lang + "\n\n" + prefix + "<|CURSOR|>" + suffix;

        Map<String, Object> body = new HashMap<>();
        body.put("model", modelId);
        body.put("stream", true);
        body.put("max_tokens", 256);
        body.put("temperature", 0.1);

        List<Map<String, Object>> messages = new ArrayList<>();

        Map<String, Object> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", SYSTEM_PROMPT);
        messages.add(sysMsg);

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);
        messages.add(userMsg);

        body.put("messages", messages);
        return objectMapper.writeValueAsString(body);
    }

    private ModelInfo resolveModelFromConfig() {
        ConfigInfo config = configManager.getConfig();
        if (config != null && config.getSmall_model() != null && !config.getSmall_model().isBlank()) {
            String sm = config.getSmall_model();
            if (sm.contains("/")) {
                String[] parts = sm.split("/", 2);
                return providerManager.getModel(parts[0], parts[1])
                        .orElseGet(providerManager::getDefaultModel);
            }
        }
        return providerManager.getDefaultModel();
    }

    private String[] resolveApiConfig(String providerID) {
        String baseUrl = "https://api.openai.com/v1";
        String apiKey = System.getenv("OPENAI_API_KEY");

        ConfigInfo config = configManager.getConfig();
        if (config != null && config.getProvider() != null) {
            ConfigInfo.ProviderConfig pc = config.getProvider().get(providerID);
            if (pc != null && pc.getApi() != null) {
                if (pc.getApi().getBaseUrl() != null) baseUrl = pc.getApi().getBaseUrl();
                if (pc.getApi().getApiKey() != null) apiKey = pc.getApi().getApiKey();
            }
        }

        if ("deepseek".equalsIgnoreCase(providerID)) {
            if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
                apiKey = deepseekApiKey;
            }
            if (baseUrl == null || baseUrl.isBlank() || baseUrl.contains("openai.com")) {
                baseUrl = deepseekBaseUrl;
            }
        }

        return new String[]{baseUrl, apiKey != null ? apiKey : ""};
    }
}
