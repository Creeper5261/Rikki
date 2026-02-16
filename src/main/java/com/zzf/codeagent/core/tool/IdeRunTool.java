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
public class IdeRunTool implements Tool {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final ObjectMapper objectMapper;

    @Override
    public String getId() {
        return "ide_run";
    }

    @Override
    public String getDescription() {
        return "Start Run Configuration in IDE environment. Use this instead of shell for app run/start tasks.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode configuration = properties.putObject("configuration");
        configuration.put("type", "string");
        configuration.put("description", "Optional run configuration name. If omitted, IDE selects best match.");

        ObjectNode executor = properties.putObject("executor");
        executor.put("type", "string");
        ArrayNode enums = executor.putArray("enum");
        enums.add("run");
        enums.add("debug");
        executor.put("description", "Execution mode.");

        ObjectNode timeout = properties.putObject("timeoutMs");
        timeout.put("type", "integer");
        timeout.put("description", "Optional bridge timeout in milliseconds.");

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

                String configuration = firstNonBlank(
                        args != null ? args.path("configuration").asText("") : ""
                );
                String executor = normalizeExecutor(args != null ? args.path("executor").asText("run") : "run");
                long timeoutMs = args != null ? Math.max(2_000L, args.path("timeoutMs").asLong(DEFAULT_TIMEOUT_MS)) : DEFAULT_TIMEOUT_MS;

                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("action", "run");
                if (!configuration.isBlank()) {
                    payload.put("configuration", configuration);
                }
                payload.put("executor", executor);

                JsonNode response = IdeBridgeClient.execute(objectMapper, bridgeUrl, payload, timeoutMs);
                boolean ok = response.path("ok").asBoolean(false);
                String status = firstNonBlank(response.path("status").asText(""), ok ? "started" : "error");
                String summary = firstNonBlank(response.path("summary").asText(""), ok ? "IDE run started." : "IDE run failed.");
                String output = firstNonBlank(response.path("output").asText(""));

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("transport", "ide_bridge");
                metadata.put("bridgeUrl", bridgeUrl);
                metadata.put("action", "run");
                metadata.put("status", status);
                metadata.put("ok", ok);
                metadata.put("executor", executor);
                String resolvedCfg = firstNonBlank(response.path("configuration").asText(""), configuration);
                if (!resolvedCfg.isBlank()) {
                    metadata.put("configuration", resolvedCfg);
                }

                if (!ok) {
                    throw new IllegalStateException(summary + (output.isBlank() ? "" : "\n" + output));
                }

                StringBuilder rendered = new StringBuilder(summary);
                if (!output.isBlank()) {
                    rendered.append("\n\n").append(output);
                }

                return Result.builder()
                        .title("IDE run: " + status)
                        .output(rendered.toString())
                        .metadata(metadata)
                        .build();
            } catch (Exception e) {
                log.warn("ide_run failed", e);
                throw new RuntimeException("ide_run failed: " + e.getMessage(), e);
            }
        });
    }

    private String normalizeExecutor(String raw) {
        String normalized = firstNonBlank(raw, "run").trim().toLowerCase();
        if ("debug".equals(normalized)) {
            return "debug";
        }
        return "run";
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

