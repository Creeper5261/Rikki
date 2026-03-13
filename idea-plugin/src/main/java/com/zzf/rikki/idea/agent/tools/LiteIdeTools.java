package com.zzf.rikki.idea.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.openapi.project.Project;
import com.zzf.rikki.idea.IdeBridgeServer;

import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class LiteIdeTools {
    private final Project project;
    private final ObjectMapper mapper;
    private volatile JsonNode ideContextNode;
    private volatile CapabilitySnapshot cachedCapabilitySnapshot = new CapabilitySnapshot(false, List.of());

    public LiteIdeTools(Project project, ObjectMapper mapper) {
        this.project = project;
        this.mapper = mapper;
        this.ideContextNode = mapper.createObjectNode();
    }

    public CapabilitySnapshot capabilitySnapshot() {
        return cachedCapabilitySnapshot;
    }

    public CapabilitySnapshot refreshCapabilities() {
        JsonNode node = callBridgeJson(mapper.createObjectNode().put("action", "capabilities"));
        CapabilitySnapshot snapshot = parseCapabilitySnapshot(node);
        cachedCapabilitySnapshot = snapshot;
        return snapshot;
    }

    public String context(JsonNode args) throws Exception {
        JsonNode ctx = ideContextNode;
        if (ctx == null || ctx.isNull() || ctx.isMissingNode() || ctx.size() == 0) {
            return "No IDE context is available. Ensure the plugin is loaded and project is indexed.";
        }
        String query = args.path("query").asText("all");
        List<String> keys = new ArrayList<>();
        if (args.has("keys") && args.path("keys").isArray()) {
            args.path("keys").forEach(node -> {
                String key = node.asText("");
                if (!key.isBlank()) {
                    keys.add(key);
                }
            });
        }
        ObjectNode filtered = mapper.createObjectNode();
        ctx.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if ((!keys.isEmpty() && keys.contains(key)) || (keys.isEmpty() && matchesQuery(key, query))) {
                filtered.set(key, entry.getValue());
            }
        });
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(filtered);
    }

    public String action(JsonNode args) throws Exception {
        String operationRaw = firstNonBlank(args.path("operation").asText(""), args.path("action").asText("")).toLowerCase(Locale.ROOT);
        if (operationRaw.isBlank()) {
            return prettyJson(errorNode("missing_operation", "Field 'operation' is required for ide_action."));
        }
        if ("capabilities".equals(operationRaw)) {
            return capabilities();
        }
        if ("build".equals(operationRaw)) {
            return prettyJson(unsupportedBuildNode());
        }
        CapabilitySnapshot snapshot = refreshCapabilities();
        if (!snapshot.getBridgeAvailable()) {
            return prettyJson(errorNode("bridge_unavailable", "IDE bridge unavailable. Use bash for build/test operations."));
        }
        if (!snapshot.getActionOperations().contains(operationRaw)) {
            return prettyJson(errorNode("unsupported_operation", "Unsupported ide_action operation: " + operationRaw));
        }
        ObjectNode payload = mapper.createObjectNode();
        switch (operationRaw) {
            case "run":
            case "test":
                payload.put("action", "start");
                payload.put("operation", operationRaw);
                break;
            case "status":
                payload.put("action", "status");
                break;
            case "cancel":
                payload.put("action", "cancel");
                break;
            default:
                return prettyJson(errorNode("unsupported_operation", "Unsupported ide_action operation: " + operationRaw));
        }
        copyIfPresent(args, payload, "mode", "configuration", "configurationName", "name", "executor", "jobId", "sinceRevision", "waitMs");
        JsonNode firstResponse = callBridgeJson(payload);
        boolean wait = args.path("wait").asBoolean(false);
        if (!wait || (!"run".equals(operationRaw) && !"test".equals(operationRaw))) {
            return prettyJson(firstResponse);
        }
        String jobId = firstNonBlank(firstResponse.path("jobId").asText(""));
        if (!firstResponse.path("ok").asBoolean(false) || jobId.isBlank()) {
            return prettyJson(firstResponse);
        }
        if (!snapshot.getActionOperations().contains("status")) {
            return prettyJson(firstResponse);
        }
        long timeoutMs = Math.max(1_000L, Math.min(900_000L, args.path("timeoutMs").asLong(120_000L)));
        long pollIntervalMs = Math.max(200L, Math.min(10_000L, args.path("pollIntervalMs").asLong(1_000L)));
        long deadline = System.currentTimeMillis() + timeoutMs;
        long sinceRevision = 0L;
        JsonNode latest = firstResponse;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(pollIntervalMs);
            ObjectNode statusPayload = mapper.createObjectNode()
                    .put("action", "status")
                    .put("jobId", jobId)
                    .put("sinceRevision", sinceRevision)
                    .put("waitMs", pollIntervalMs);
            latest = callBridgeJson(statusPayload);
            sinceRevision = Math.max(sinceRevision, latest.path("logRevision").asLong(0L));
            if (isTerminalStatus(latest.path("status").asText(""))) {
                return prettyJson(latest);
            }
        }
        ObjectNode timeoutNode = latest instanceof ObjectNode objectNode ? objectNode : mapper.createObjectNode();
        timeoutNode.put("ok", false);
        timeoutNode.put("status", "timeout");
        timeoutNode.put("summary", "Timed out waiting for IDE job completion.");
        timeoutNode.put("output", firstNonBlank(timeoutNode.path("output").asText(""), "Timed out waiting for IDE job completion."));
        return prettyJson(timeoutNode);
    }

    public String capabilities() throws Exception {
        JsonNode node = callBridgeJson(mapper.createObjectNode().put("action", "capabilities"));
        cachedCapabilitySnapshot = parseCapabilitySnapshot(node);
        return prettyJson(node);
    }

    public void setIdeContextNode(JsonNode ideContextNode) {
        this.ideContextNode = ideContextNode == null ? mapper.createObjectNode() : ideContextNode;
    }

    private CapabilitySnapshot parseCapabilitySnapshot(JsonNode node) {
        if (!node.path("ok").asBoolean(false)) {
            return new CapabilitySnapshot(false, List.of());
        }
        LinkedHashSet<String> operations = new LinkedHashSet<>();
        collectOps(node.path("asyncOperations"), operations);
        collectOps(node.path("directOperations"), operations);
        return new CapabilitySnapshot(true, new ArrayList<>(operations));
    }

    private void collectOps(JsonNode source, LinkedHashSet<String> dest) {
        if (!source.isArray()) {
            return;
        }
        for (JsonNode node : source) {
            String operation = node.asText("").trim().toLowerCase(Locale.ROOT);
            if (List.of("run", "test", "status", "cancel", "capabilities").contains(operation)) {
                dest.add(operation);
            }
        }
    }

    private boolean matchesQuery(String key, String query) {
        if ("all".equalsIgnoreCase(query)) {
            return true;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return switch (query.toLowerCase(Locale.ROOT)) {
            case "project" -> lower.contains("project") || lower.contains("workspace");
            case "build" -> lower.contains("build") || lower.contains("gradle") || lower.contains("mvn");
            case "modules" -> lower.contains("module");
            case "run" -> lower.contains("run") || lower.contains("configuration");
            default -> true;
        };
    }

    private ObjectNode unsupportedBuildNode() {
        ObjectNode node = mapper.createObjectNode();
        node.put("ok", false);
        node.put("action", "build");
        node.put("status", "unsupported");
        node.put("code", "unsupported_operation");
        node.put("summary", "IDE build is disabled in cross-IDE mode. Use bash for build/test.");
        node.put("output", "IDE build is disabled in cross-IDE mode. Use bash for build/test.");
        return node;
    }

    private ObjectNode errorNode(String code, String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("ok", false);
        node.put("status", "error");
        node.put("code", code);
        node.put("summary", message);
        node.put("output", message);
        return node;
    }

    private JsonNode callBridgeJson(JsonNode payload) {
        IdeBridgeServer bridge;
        try {
            bridge = project.getService(IdeBridgeServer.class);
        } catch (Exception ignored) {
            bridge = null;
        }
        if (bridge == null) {
            return errorNode("bridge_unavailable", "IDE bridge unavailable. Ensure the plugin is running.");
        }
        String bridgeUrl = bridge.getBaseUrl();
        if (bridgeUrl == null || bridgeUrl.isBlank()) {
            return errorNode("bridge_not_started", "IDE bridge not started.");
        }
        try {
            String body = mapper.writeValueAsString(payload);
            HttpURLConnection connection = (HttpURLConnection) URI.create(bridgeUrl + "/execute").toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(120_000);
            try (OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(body);
            }
            int status = connection.getResponseCode();
            byte[] responseBody = status >= 200 && status < 300
                    ? connection.getInputStream().readAllBytes()
                    : (connection.getErrorStream() == null ? new byte[0] : connection.getErrorStream().readAllBytes());
            connection.disconnect();
            if (responseBody.length == 0) {
                return errorNode("empty_response", "IDE bridge returned an empty response.");
            }
            String responseText = new String(responseBody, StandardCharsets.UTF_8);
            try {
                return mapper.readTree(responseText);
            } catch (Exception ignored) {
                return errorNode("invalid_response", "IDE bridge returned non-JSON response: " + responseText);
            }
        } catch (Exception e) {
            return errorNode("bridge_call_failed", "IDE bridge call failed: " + (e.getMessage() == null ? "unknown error" : e.getMessage()));
        }
    }

    private String prettyJson(JsonNode node) throws Exception {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String... keys) {
        for (String key : keys) {
            if (source.has(key)) {
                JsonNode value = source.path(key);
                if (!value.isMissingNode() && !value.isNull()) {
                    target.set(key, value.deepCopy());
                }
            }
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean isTerminalStatus(String status) {
        return List.of("succeeded", "success", "failed", "aborted", "canceled", "cancelled", "timeout").contains(status.trim().toLowerCase(Locale.ROOT));
    }

    public static class CapabilitySnapshot {
        private final boolean bridgeAvailable;
        private final List<String> actionOperations;

        public CapabilitySnapshot(boolean bridgeAvailable, List<String> actionOperations) {
            this.bridgeAvailable = bridgeAvailable;
            this.actionOperations = actionOperations == null ? List.of() : List.copyOf(actionOperations);
        }

        public boolean getBridgeAvailable() {
            return bridgeAvailable;
        }

        public List<String> getActionOperations() {
            return actionOperations;
        }
    }
}
