package com.zzf.rikki.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdeCapabilitiesTool implements Tool {

    private final ObjectMapper objectMapper;

    @Override
    public String getId() {
        return "ide_capabilities";
    }

    @Override
    public String getDescription() {
        return "Fetch available IDE-native bridge capabilities (supported operations, run configurations, async job support).";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String bridgeUrl = IdeBridgeClient.resolveBridgeUrl(ctx);
                if (bridgeUrl.isBlank()) {
                    throw new IllegalStateException("IDE bridge unavailable. Please run from IDEA plugin with IDE context enabled.");
                }

                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("action", "capabilities");
                JsonNode response = IdeBridgeClient.execute(objectMapper, bridgeUrl, payload, 10_000L);

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("transport", "ide_bridge");
                metadata.put("bridgeUrl", bridgeUrl);
                metadata.put("action", "capabilities");
                metadata.put("status", response.path("status").asText("success"));
                metadata.put("ok", response.path("ok").asBoolean(true));
                if (response.has("jobMode")) {
                    metadata.put("jobMode", response.path("jobMode").asBoolean(false));
                }

                String output = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response);
                return Result.builder()
                        .title("IDE capabilities")
                        .output(output)
                        .metadata(metadata)
                        .build();
            } catch (Exception e) {
                log.warn("ide_capabilities failed", e);
                throw new RuntimeException("ide_capabilities failed: " + e.getMessage(), e);
            }
        });
    }
}

