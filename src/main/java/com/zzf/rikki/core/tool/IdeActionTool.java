package com.zzf.rikki.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdeActionTool implements Tool {

    private static final long DEFAULT_BUILD_WAIT_TIMEOUT_MS = 300_000L;
    private static final long DEFAULT_NON_BUILD_WAIT_TIMEOUT_MS = 60_000L;
    private static final long DEFAULT_POLL_INTERVAL_MS = 1_000L;
    private static final long MIN_POLL_INTERVAL_MS = 200L;
    private static final long MAX_POLL_INTERVAL_MS = 5_000L;

    private final ObjectMapper objectMapper;

    @Override
    public String getId() {
        return "ide_action";
    }

    @Override
    public String getDescription() {
        return "Unified IDE-native action tool with async jobs. Supports build/run/test start, status query, cancel, and capability query.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode operation = properties.putObject("operation");
        operation.put("type", "string");
        ArrayNode opEnums = operation.putArray("enum");
        opEnums.add("build");
        opEnums.add("run");
        opEnums.add("test");
        opEnums.add("status");
        opEnums.add("cancel");
        opEnums.add("capabilities");
        operation.put("description", "IDE action to execute.");

        ObjectNode mode = properties.putObject("mode");
        mode.put("type", "string");
        ArrayNode modeEnums = mode.putArray("enum");
        modeEnums.add("make");
        modeEnums.add("rebuild");
        mode.put("description", "Build mode (used when operation=build).");

        ObjectNode configuration = properties.putObject("configuration");
        configuration.put("type", "string");
        configuration.put("description", "Run configuration name (used for run/test).");

        ObjectNode executor = properties.putObject("executor");
        executor.put("type", "string");
        ArrayNode executorEnums = executor.putArray("enum");
        executorEnums.add("run");
        executorEnums.add("debug");
        executor.put("description", "Executor for run/test.");

        ObjectNode jobId = properties.putObject("jobId");
        jobId.put("type", "string");
        jobId.put("description", "Job id for status/cancel.");

        ObjectNode sinceRevision = properties.putObject("sinceRevision");
        sinceRevision.put("type", "integer");
        sinceRevision.put("description", "Optional log cursor for operation=status.");

        ObjectNode wait = properties.putObject("wait");
        wait.put("type", "boolean");
        wait.put("description", "Whether to block until async job reaches terminal status.");

        ObjectNode timeout = properties.putObject("timeoutMs");
        timeout.put("type", "integer");
        timeout.put("description", "Wait timeout for operation or polling.");

        ObjectNode poll = properties.putObject("pollIntervalMs");
        poll.put("type", "integer");
        poll.put("description", "Polling interval while waiting for job status.");

        ObjectNode waitMs = properties.putObject("waitMs");
        waitMs.put("type", "integer");
        waitMs.put("description", "Long-poll wait duration for operation=status.");

        schema.putArray("required").add("operation");
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

                String operation = normalizeOperation(args == null ? "" : args.path("operation").asText(""));
                if (operation.isBlank()) {
                    throw new IllegalArgumentException("ide_action requires non-empty operation.");
                }

                return switch (operation) {
                    case "capabilities" -> runCapabilities(bridgeUrl);
                    case "status" -> runStatus(bridgeUrl, args);
                    case "cancel" -> runCancel(bridgeUrl, args);
                    case "build", "run", "test" -> runAsyncOperation(bridgeUrl, operation, args, ctx);
                    default -> throw new IllegalArgumentException("Unsupported ide_action operation: " + operation);
                };
            } catch (Exception e) {
                log.warn("ide_action failed", e);
                throw new RuntimeException("ide_action failed: " + e.getMessage(), e);
            }
        });
    }

    private Result runCapabilities(String bridgeUrl) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", "capabilities");
        JsonNode response = IdeBridgeClient.execute(objectMapper, bridgeUrl, payload, 10_000L);
        return buildResult("ide_action: capabilities", "capabilities", bridgeUrl, response);
    }

    private Result runStatus(String bridgeUrl, JsonNode args) throws Exception {
        String jobId = firstNonBlank(args == null ? "" : args.path("jobId").asText(""));
        if (jobId.isBlank()) {
            throw new IllegalArgumentException("ide_action status requires jobId.");
        }
        long sinceRevision = args == null ? 0L : args.path("sinceRevision").asLong(0L);
        long waitMs = args == null ? 0L : Math.max(0L, args.path("waitMs").asLong(0L));
        JsonNode response = queryJobStatus(bridgeUrl, jobId, 20_000L, sinceRevision, waitMs);
        return buildResult("ide_action: status", "status", bridgeUrl, response);
    }

    private Result runCancel(String bridgeUrl, JsonNode args) throws Exception {
        String jobId = firstNonBlank(args == null ? "" : args.path("jobId").asText(""));
        if (jobId.isBlank()) {
            throw new IllegalArgumentException("ide_action cancel requires jobId.");
        }
        JsonNode response = cancelJob(bridgeUrl, jobId, 15_000L);
        return buildResult("ide_action: cancel", "cancel", bridgeUrl, response);
    }

    private Result runAsyncOperation(String bridgeUrl, String operation, JsonNode args, Context ctx) throws Exception {
        String mode = normalizeBuildMode(args == null ? "" : args.path("mode").asText(""));
        String configuration = firstNonBlank(args == null ? "" : args.path("configuration").asText(""));
        String executor = normalizeExecutor(args == null ? "" : args.path("executor").asText(""));
        boolean wait = args != null && args.has("wait") ? args.path("wait").asBoolean(defaultWait(operation)) : defaultWait(operation);
        long timeoutMs = normalizeTimeout(operation, args == null ? 0L : args.path("timeoutMs").asLong(0L));
        long pollIntervalMs = normalizePollInterval(args == null ? 0L : args.path("pollIntervalMs").asLong(0L));

        ObjectNode startPayload = objectMapper.createObjectNode();
        startPayload.put("action", "start");
        startPayload.put("operation", operation);
        if ("build".equals(operation)) {
            startPayload.put("mode", mode);
            startPayload.put("timeoutMs", timeoutMs);
        } else {
            if (!configuration.isBlank()) {
                startPayload.put("configuration", configuration);
            }
            startPayload.put("executor", executor);
        }

        JsonNode startResponse = IdeBridgeClient.execute(objectMapper, bridgeUrl, startPayload, 20_000L);
        String jobId = firstNonBlank(startResponse.path("jobId").asText(""));
        String startStatus = normalizeStatus(startResponse.path("status").asText(""));
        long logRevision = startResponse.path("logRevision").asLong(0L);

        if (ctx != null) {
            ctx.metadata(
                    "IDE " + operation + " started",
                    metadataForStatus(operation, bridgeUrl, jobId, startResponse)
            );
        }

        if (!wait || jobId.isBlank() || isTerminal(startStatus)) {
            return buildResult("ide_action: " + operation, operation, bridgeUrl, startResponse);
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonNode last = startResponse;
        while (System.currentTimeMillis() <= deadline) {
            JsonNode statusResponse = queryJobStatus(bridgeUrl, jobId, 20_000L, logRevision, pollIntervalMs);
            last = statusResponse;
            if (statusResponse.has("logRevision")) {
                logRevision = Math.max(logRevision, statusResponse.path("logRevision").asLong(logRevision));
            }
            String status = normalizeStatus(statusResponse.path("status").asText(""));
            if (ctx != null) {
                ctx.metadata(
                        "IDE " + operation + " " + status,
                        metadataForStatus(operation, bridgeUrl, jobId, statusResponse)
                );
            }
            if (isTerminal(status)) {
                break;
            }
            sleep(pollIntervalMs);
        }

        String finalStatus = normalizeStatus(last.path("status").asText(""));
        if (!isTerminal(finalStatus)) {
            ObjectNode timeoutNode = objectMapper.createObjectNode();
            timeoutNode.put("ok", false);
            timeoutNode.put("action", "status");
            timeoutNode.put("operation", operation);
            timeoutNode.put("jobId", jobId);
            timeoutNode.put("status", "timeout");
            timeoutNode.put("summary", "Timed out waiting for IDE job completion after " + timeoutMs + "ms.");
            timeoutNode.put("output", "");
            last = timeoutNode;
        }
        return buildResult("ide_action: " + operation, operation, bridgeUrl, last);
    }

    private JsonNode queryJobStatus(String bridgeUrl, String jobId, long timeoutMs) throws Exception {
        return queryJobStatus(bridgeUrl, jobId, timeoutMs, 0L, 0L);
    }

    private JsonNode queryJobStatus(String bridgeUrl, String jobId, long timeoutMs, long sinceRevision) throws Exception {
        return queryJobStatus(bridgeUrl, jobId, timeoutMs, sinceRevision, 0L);
    }

    private JsonNode queryJobStatus(
            String bridgeUrl,
            String jobId,
            long timeoutMs,
            long sinceRevision,
            long waitMs
    ) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", "status");
        payload.put("jobId", jobId);
        if (sinceRevision > 0L) {
            payload.put("sinceRevision", sinceRevision);
        }
        if (waitMs > 0L) {
            payload.put("waitMs", Math.max(0L, Math.min(waitMs, 30_000L)));
        }
        return IdeBridgeClient.execute(objectMapper, bridgeUrl, payload, timeoutMs);
    }

    private JsonNode cancelJob(String bridgeUrl, String jobId, long timeoutMs) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", "cancel");
        payload.put("jobId", jobId);
        return IdeBridgeClient.execute(objectMapper, bridgeUrl, payload, timeoutMs);
    }

    private Result buildResult(String title, String operation, String bridgeUrl, JsonNode response) throws Exception {
        Map<String, Object> metadata = metadataForStatus(operation, bridgeUrl, firstNonBlank(response.path("jobId").asText("")), response);

        String status = normalizeStatus(response.path("status").asText(""));
        String summary = firstNonBlank(
                response.path("summary").asText(""),
                "IDE action finished with status: " + status
        );
        String output = composeOutput(response);

        StringBuilder rendered = new StringBuilder();
        rendered.append("Status: ").append(status);
        if (!summary.isBlank()) {
            rendered.append("\n").append(summary);
        }
        if (!output.isBlank()) {
            rendered.append("\n\n").append(output);
        }

        return Result.builder()
                .title(title)
                .output(rendered.toString())
                .metadata(metadata)
                .build();
    }

    private Map<String, Object> metadataForStatus(
            String operation,
            String bridgeUrl,
            String jobId,
            JsonNode response
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transport", "ide_bridge");
        metadata.put("bridgeUrl", bridgeUrl);
        metadata.put("action", "ide_action");
        metadata.put("operation", operation);
        metadata.put("jobId", jobId);
        metadata.put("status", normalizeStatus(response.path("status").asText("")));
        metadata.put("ok", response.path("ok").asBoolean(false));
        putIfPresent(metadata, "summary", firstNonBlank(response.path("summary").asText("")));
        putIfPresent(metadata, "output", IdeBridgeClient.trim(composeOutput(response), 2000));
        putIfPresent(metadata, "configuration", firstNonBlank(response.path("configuration").asText("")));
        putIfPresent(metadata, "executor", firstNonBlank(response.path("executor").asText("")));
        if (response.has("logRevision")) {
            metadata.put("logRevision", response.path("logRevision").asLong(0L));
        }
        if (response.has("fromRevision")) {
            metadata.put("fromRevision", response.path("fromRevision").asLong(0L));
        }
        if (response.has("errors")) {
            metadata.put("errors", response.path("errors").asInt(0));
        }
        if (response.has("warnings")) {
            metadata.put("warnings", response.path("warnings").asInt(0));
        }
        if (response.has("durationMs")) {
            metadata.put("durationMs", response.path("durationMs").asLong(0L));
        }
        if (response.has("cancelRequested")) {
            metadata.put("cancelRequested", response.path("cancelRequested").asBoolean(false));
        }
        if (response.has("cancellable")) {
            metadata.put("cancellable", response.path("cancellable").asBoolean(false));
        }
        if (response.has("processAttached")) {
            metadata.put("processAttached", response.path("processAttached").asBoolean(false));
        }
        if (response.has("exitCode")) {
            metadata.put("exitCode", response.path("exitCode").asInt(0));
        }
        return metadata;
    }

    private void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (metadata == null || key == null || key.isBlank()) {
            return;
        }
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private boolean defaultWait(String operation) {
        return "build".equals(operation) || "test".equals(operation);
    }

    private long normalizeTimeout(String operation, long rawTimeout) {
        long defaultValue = "build".equals(operation) ? DEFAULT_BUILD_WAIT_TIMEOUT_MS : DEFAULT_NON_BUILD_WAIT_TIMEOUT_MS;
        long safe = rawTimeout > 0 ? rawTimeout : defaultValue;
        safe = Math.max(2_000L, safe);
        safe = Math.min(1_800_000L, safe);
        return safe;
    }

    private long normalizePollInterval(long rawPoll) {
        long safe = rawPoll > 0 ? rawPoll : DEFAULT_POLL_INTERVAL_MS;
        safe = Math.max(MIN_POLL_INTERVAL_MS, safe);
        safe = Math.min(MAX_POLL_INTERVAL_MS, safe);
        return safe;
    }

    private String normalizeOperation(String raw) {
        String normalized = firstNonBlank(raw).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "build", "run", "test", "status", "cancel", "capabilities" -> normalized;
            default -> "";
        };
    }

    private String normalizeStatus(String raw) {
        String normalized = firstNonBlank(raw).trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "unknown";
        }
        return normalized;
    }

    private boolean isTerminal(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return "succeeded".equals(normalized)
                || "failed".equals(normalized)
                || "aborted".equals(normalized)
                || "canceled".equals(normalized)
                || "timeout".equals(normalized)
                || "success".equals(normalized)
                || "error".equals(normalized)
                || "completed".equals(normalized);
    }

    private String normalizeBuildMode(String raw) {
        String normalized = firstNonBlank(raw).trim().toLowerCase(Locale.ROOT);
        if ("rebuild".equals(normalized)) {
            return "rebuild";
        }
        return "make";
    }

    private String normalizeExecutor(String raw) {
        String normalized = firstNonBlank(raw).trim().toLowerCase(Locale.ROOT);
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

    private void sleep(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String composeOutput(JsonNode response) {
        if (response == null || response.isMissingNode() || response.isNull()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        JsonNode logsNode = response.get("logs");
        if (logsNode != null && logsNode.isArray()) {
            int count = 0;
            for (JsonNode lineNode : logsNode) {
                if (lineNode == null || lineNode.isNull()) {
                    continue;
                }
                String line = lineNode.asText("");
                if (line.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
                count++;
                if (count >= 200) {
                    sb.append("\n... log tail truncated ...");
                    break;
                }
            }
        }
        String plainOutput = firstNonBlank(response.path("output").asText(""));
        if (!plainOutput.isBlank()) {
            if (sb.length() == 0) {
                sb.append(plainOutput);
            } else if (!sb.toString().contains(plainOutput)) {
                sb.append('\n').append(plainOutput);
            }
        }
        return sb.toString();
    }
}
