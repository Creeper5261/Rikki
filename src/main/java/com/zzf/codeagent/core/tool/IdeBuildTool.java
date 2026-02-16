package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
public class IdeBuildTool implements Tool {

    private static final long DEFAULT_TIMEOUT_MS = 300_000L;
    private static final long MIN_TIMEOUT_MS = 10_000L;
    private static final long MAX_TIMEOUT_MS = 1_800_000L;

    private final ObjectMapper objectMapper;

    @Override
    public String getId() {
        return "ide_build";
    }

    @Override
    public String getDescription() {
        return "Build project via IDE-native compiler environment. Prefer this over shell build commands when IDE context is available.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode modeNode = properties.putObject("mode");
        modeNode.put("type", "string");
        ArrayNode enums = modeNode.putArray("enum");
        enums.add("make");
        enums.add("rebuild");
        modeNode.put("description", "Build mode. make = incremental; rebuild = full rebuild.");

        ObjectNode timeoutNode = properties.putObject("timeoutMs");
        timeoutNode.put("type", "integer");
        timeoutNode.put("description", "Optional timeout in milliseconds (10000-1800000).");

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

                String mode = normalizeMode(args != null ? args.path("mode").asText("make") : "make");
                long timeoutMs = normalizeTimeout(args != null ? args.path("timeoutMs").asLong(DEFAULT_TIMEOUT_MS) : DEFAULT_TIMEOUT_MS);

                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("action", "build");
                payload.put("mode", mode);
                payload.put("timeoutMs", timeoutMs);

                JsonNode response = IdeBridgeClient.execute(objectMapper, bridgeUrl, payload, timeoutMs + 5000L);

                String status = response.path("status").asText("");
                boolean ok = response.path("ok").asBoolean("success".equalsIgnoreCase(status));
                int errors = response.path("errors").asInt(0);
                int warnings = response.path("warnings").asInt(0);
                boolean aborted = response.path("aborted").asBoolean(false);
                long durationMs = response.path("durationMs").asLong(0L);
                String summary = firstNonBlank(
                        response.path("summary").asText(""),
                        ok ? "IDE build succeeded." : "IDE build failed."
                );
                String output = firstNonBlank(response.path("output").asText(""));

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("transport", "ide_bridge");
                metadata.put("bridgeUrl", bridgeUrl);
                metadata.put("action", "build");
                metadata.put("mode", mode);
                metadata.put("status", firstNonBlank(status, ok ? "success" : "failed"));
                metadata.put("ok", ok);
                metadata.put("errors", errors);
                metadata.put("warnings", warnings);
                metadata.put("aborted", aborted);
                metadata.put("durationMs", durationMs);

                StringBuilder rendered = new StringBuilder();
                rendered.append(summary);
                if (!output.isBlank()) {
                    rendered.append("\n\n").append(output);
                }

                return Result.builder()
                        .title("IDE build: " + firstNonBlank(status, ok ? "success" : "failed"))
                        .output(rendered.toString())
                        .metadata(metadata)
                        .build();
            } catch (Exception e) {
                log.warn("ide_build failed", e);
                throw new RuntimeException("ide_build failed: " + e.getMessage(), e);
            }
        });
    }

    private String normalizeMode(String raw) {
        String normalized = firstNonBlank(raw, "make").trim().toLowerCase();
        if ("rebuild".equals(normalized)) {
            return "rebuild";
        }
        return "make";
    }

    private long normalizeTimeout(long value) {
        long safe = value <= 0 ? DEFAULT_TIMEOUT_MS : value;
        safe = Math.max(MIN_TIMEOUT_MS, safe);
        safe = Math.min(MAX_TIMEOUT_MS, safe);
        return safe;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isBlank()) {
                    return trimmed;
                }
            }
        }
        return "";
    }
}

