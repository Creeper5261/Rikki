package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolProtocol {
    public static final String DEFAULT_VERSION = "v1";

    private ToolProtocol() {}

    public static final class ToolEnvelope {
        private final String tool;
        private final String version;
        private final JsonNode args;
        private final String traceId;
        private final String requestId;

        public ToolEnvelope(String tool, String version, JsonNode args, String traceId, String requestId) {
            this.tool = tool == null ? "" : tool.trim();
            this.version = version == null || version.isBlank() ? DEFAULT_VERSION : version.trim();
            this.args = args;
            this.traceId = traceId == null ? "" : traceId.trim();
            this.requestId = requestId == null ? "" : requestId.trim();
        }

        public String getTool() {
            return tool;
        }

        public String getVersion() {
            return version;
        }

        public JsonNode getArgs() {
            return args;
        }

        public String getTraceId() {
            return traceId;
        }

        public String getRequestId() {
            return requestId;
        }
    }

    public static final class ToolSpec {
        private final String name;
        private final String version;
        private final String description;
        private final JsonNode inputSchema;
        private final JsonNode outputSchema;

        public ToolSpec(String name, String version, String description, JsonNode inputSchema, JsonNode outputSchema) {
            this.name = name == null ? "" : name.trim();
            this.version = version == null || version.isBlank() ? DEFAULT_VERSION : version.trim();
            this.description = description == null ? "" : description.trim();
            this.inputSchema = inputSchema;
            this.outputSchema = outputSchema;
        }

        public String getName() {
            return name;
        }

        public String getVersion() {
            return version;
        }

        public String getDescription() {
            return description;
        }

        public JsonNode getInputSchema() {
            return inputSchema;
        }

        public JsonNode getOutputSchema() {
            return outputSchema;
        }
    }

    public static final class ToolResult {
        private final String tool;
        private final String version;
        private final String status;
        private final String error;
        private final String hint;
        private final long tookMs;
        private final JsonNode result;
        private final Map<String, JsonNode> extras;

        private ToolResult(String tool, String version, String status, String error, String hint, long tookMs, JsonNode result, Map<String, JsonNode> extras) {
            this.tool = tool == null ? "" : tool.trim();
            this.version = version == null || version.isBlank() ? DEFAULT_VERSION : version.trim();
            this.status = status == null ? "" : status.trim();
            this.error = error == null ? "" : error.trim();
            this.hint = hint == null ? "" : hint.trim();
            this.tookMs = tookMs;
            this.result = result;
            this.extras = extras == null ? Collections.emptyMap() : extras;
        }

        public static ToolResult ok(String tool, String version, JsonNode result) {
            return new ToolResult(tool, version, "ok", "", "", -1L, result, Collections.emptyMap());
        }

        public static ToolResult error(String tool, String version, String error) {
            return new ToolResult(tool, version, "error", error, "", -1L, null, Collections.emptyMap());
        }

        public ToolResult withHint(String hint) {
            return new ToolResult(tool, version, status, error, hint, tookMs, result, extras);
        }

        public ToolResult withTookMs(long tookMs) {
            return new ToolResult(tool, version, status, error, hint, tookMs, result, extras);
        }

        public ToolResult withExtra(String key, JsonNode value) {
            if (key == null || key.isBlank() || value == null) {
                return this;
            }
            Map<String, JsonNode> next = new LinkedHashMap<>(extras);
            next.put(key, value);
            return new ToolResult(tool, version, status, error, hint, tookMs, result, next);
        }

        public boolean isSuccess() {
            return "ok".equalsIgnoreCase(status);
        }

        public JsonNode getData() {
            return result;
        }

        public String getError() {
            return error;
        }

        public String getHint() {
            return hint;
        }

        public ObjectNode toJson(ObjectMapper mapper, ToolEnvelope env) {
            ObjectNode out = mapper.createObjectNode();
            out.put("tool", tool);
            out.put("version", version);
            out.put("status", status);
            if (!error.isEmpty()) {
                out.put("error", error);
            }
            if (!hint.isEmpty()) {
                out.put("hint", hint);
            }
            if (tookMs >= 0) {
                out.put("tookMs", tookMs);
            }
            if (result != null) {
                out.set("result", result);
            }
            if (env != null) {
                if (!env.getTraceId().isEmpty()) {
                    out.put("traceId", env.getTraceId());
                }
                if (!env.getRequestId().isEmpty()) {
                    out.put("requestId", env.getRequestId());
                }
                if (env.getArgs() != null) {
                    out.set("args", env.getArgs());
                }
            }
            for (Map.Entry<String, JsonNode> entry : extras.entrySet()) {
                if (!out.has(entry.getKey())) {
                    out.set(entry.getKey(), entry.getValue());
                }
            }
            return out;
        }
    }
}
