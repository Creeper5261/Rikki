package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.core.tool.ToolProtocol.ToolEnvelope;
import com.zzf.codeagent.core.tool.ToolProtocol.ToolResult;
import com.zzf.codeagent.core.tool.ToolProtocol.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ToolExecutionService {
    private static final Logger logger = LoggerFactory.getLogger(ToolExecutionService.class);
    private static final Pattern SENSITIVE_KV = Pattern.compile("(?i)(password|passwd|secret|token|apikey|accesskey|secretkey)\\s*[:=]\\s*([\"']?)([^\"'\\\\\\r\\n\\s]{1,160})\\2");
    private static final Pattern SENSITIVE_JSON_KV = Pattern.compile("(?i)(\"(?:password|passwd|secret|token|apiKey|accessKey|secretKey)\"\\s*:\\s*\")([^\"]{1,160})(\")");
    private static final Map<String, String> TOOL_ALIASES;
    private final ToolRegistry registry;
    private final ToolRouter router;
    private final boolean runCommandEnabled;
    private final boolean pendingWorkflowEnabled;
    private final int defaultMaxArgsChars;
    private final Set<String> allowTools;
    private final Set<String> denyTools;

    static {
        Map<String, String> aliases = new HashMap<>();
        aliases.put("read", "READ_FILE");
        aliases.put("edit", "EDIT_FILE");
        aliases.put("write", "WRITE_FILE");
        aliases.put("apply_patch", "APPLY_PATCH");
        aliases.put("applypatch", "APPLY_PATCH");
        aliases.put("ls", "LIST_FILES");
        aliases.put("list", "LIST_FILES");
        aliases.put("glob", "LIST_FILES");
        aliases.put("grep", "GREP");
        aliases.put("codesearch", "SEARCH_KNOWLEDGE");
        aliases.put("search", "SEARCH_KNOWLEDGE");
        aliases.put("bash", "RUN_COMMAND");
        aliases.put("batch", "BATCH_REPLACE");
        aliases.put("multiedit", "REPLACE_LINES");
        aliases.put("create_file", "CREATE_FILE");
        aliases.put("delete_file", "DELETE_FILE");
        aliases.put("insert_line", "INSERT_LINE");
        aliases.put("undo_edit", "UNDO_EDIT");
        aliases.put("plan_enter", "PLAN_ENTER");
        aliases.put("plan_exit", "PLAN_EXIT");
        aliases.put("open_file_view", "OPEN_FILE_VIEW");
        aliases.put("scroll_file_view", "SCROLL_FILE_VIEW");
        aliases.put("goto_file_view", "GOTO_FILE_VIEW");
        aliases.put("search_file", "SEARCH_FILE");
        TOOL_ALIASES = Collections.unmodifiableMap(aliases);
    }

    public ToolExecutionService() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInToolHandlers.registerAll(reg);
        this.registry = reg;
        this.router = new ToolRouter(reg);
        this.runCommandEnabled = resolveRunCommandEnabled();
        this.pendingWorkflowEnabled = resolvePendingWorkflowEnabled();
        this.defaultMaxArgsChars = resolveIntEnv("CODEAGENT_TOOL_ARGS_MAX", "codeagent.tool.args.max", 12000);
        this.allowTools = resolveToolSet("CODEAGENT_TOOLS_ALLOW", "codeagent.tools.allow");
        this.denyTools = resolveToolSet("CODEAGENT_TOOLS_DENY", "codeagent.tools.deny");
    }

    public boolean isRunCommandEnabled() {
        return runCommandEnabled;
    }

    public boolean isPendingWorkflowEnabled() {
        return pendingWorkflowEnabled;
    }

    public boolean isToolAllowed(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String normalized = normalizeToolName(toolName).toUpperCase();
        if (!allowTools.isEmpty() && !allowTools.contains(normalized)) {
            return false;
        }
        if (!denyTools.isEmpty() && denyTools.contains(normalized)) {
            return false;
        }
        return true;
    }

    public String execute(String tool, String version, JsonNode args, ToolExecutionContext ctx) {
        long t0 = System.nanoTime();
        String requestId = "tool-" + UUID.randomUUID();
        try {
            String normalizedTool = normalizeToolName(tool);
            if (!isToolAllowed(normalizedTool)) {
                ToolEnvelope env = new ToolEnvelope(normalizedTool, registry.resolveVersion(normalizedTool, version), args, ctx.traceId, requestId);
                ToolResult result = ToolResult.error(normalizedTool, env.getVersion(), "tool_not_allowed")
                        .withHint("Tool is not permitted by current allow/deny rules.")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
                ObjectNode out = result.toJson(ctx.mapper, env);
                return sanitizeObservation(out.toString());
            }
            if ("RUN_COMMAND".equalsIgnoreCase(normalizedTool) && !runCommandEnabled) {
                ToolEnvelope env = new ToolEnvelope(normalizedTool, registry.resolveVersion(normalizedTool, version), args, ctx.traceId, requestId);
                ToolResult result = ToolResult.error(normalizedTool, env.getVersion(), "tool_disabled")
                        .withHint("RUN_COMMAND is disabled for safety.").withTookMs((System.nanoTime() - t0) / 1_000_000L);
                ObjectNode out = result.toJson(ctx.mapper, env);
                return sanitizeObservation(out.toString());
            }
            if ("APPLY_PENDING_DIFF".equalsIgnoreCase(normalizedTool) && !pendingWorkflowEnabled) {
                ToolEnvelope env = new ToolEnvelope(normalizedTool, registry.resolveVersion(normalizedTool, version), args, ctx.traceId, requestId);
                ToolResult result = ToolResult.error(normalizedTool, env.getVersion(), "tool_disabled")
                        .withHint("Pending-diff workflow is disabled. Changes are applied directly.")
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
                ObjectNode out = result.toJson(ctx.mapper, env);
                return sanitizeObservation(out.toString());
            }
            int argChars = args == null ? 0 : args.toString().length();
            int maxArgs = resolveToolArgsLimit(normalizedTool);
            if (argChars > maxArgs) {
                ToolEnvelope env = new ToolEnvelope(normalizedTool, registry.resolveVersion(normalizedTool, version), args, ctx.traceId, requestId);
                ToolResult result = ToolResult.error(normalizedTool, env.getVersion(), "tool_args_too_large")
                        .withHint("Tool args too large (" + argChars + " > " + maxArgs + "). Use APPLY_PATCH or smaller edits.")
                        .withExtra("maxArgsChars", ctx.mapper.valueToTree(maxArgs))
                        .withExtra("argsChars", ctx.mapper.valueToTree(argChars))
                        .withTookMs((System.nanoTime() - t0) / 1_000_000L);
                ObjectNode out = result.toJson(ctx.mapper, env);
                return sanitizeObservation(out.toString());
            }
            String v = registry.resolveVersion(normalizedTool, version);
            ToolEnvelope env = new ToolEnvelope(normalizedTool, v, args, ctx.traceId, requestId);
            logger.info("tool.call traceId={} tool={} version={} requestId={}", ctx.traceId, env.getTool(), env.getVersion(), env.getRequestId());
            ToolResult result = router.execute(env, ctx);
            result = result.withTookMs((System.nanoTime() - t0) / 1_000_000L);
            ObjectNode out = result.toJson(ctx.mapper, env);
            logger.info("tool.result traceId={} tool={} version={} status={} error={}", ctx.traceId, env.getTool(), env.getVersion(), out.path("status").asText(""), out.path("error").asText(""));
            return sanitizeObservation(out.toString());
        } catch (Exception e) {
            String normalizedTool = normalizeToolName(tool);
            logger.warn("tool.fail traceId={} tool={} err={}", ctx.traceId, normalizedTool, e.toString());
            ToolEnvelope env = new ToolEnvelope(normalizedTool, registry.resolveVersion(normalizedTool, version), args, ctx.traceId, requestId);
            ToolResult result = ToolResult.error(normalizedTool, env.getVersion(), e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()))
                    .withTookMs((System.nanoTime() - t0) / 1_000_000L);
            ObjectNode out = result.toJson(ctx.mapper, env);
            return sanitizeObservation(out.toString());
        }
    }

    public List<ToolSpec> listToolSpecs() {
        return registry.listSpecs();
    }

    private String sanitizeObservation(String obs) {
        if (obs == null || obs.isEmpty()) {
            return obs;
        }
        String masked = obs;
        masked = SENSITIVE_JSON_KV.matcher(masked).replaceAll("$1******$3");
        masked = SENSITIVE_KV.matcher(masked).replaceAll("$1:******");
        return masked;
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }

    private static String normalizeToolName(String tool) {
        if (tool == null || tool.isBlank()) {
            return "";
        }
        String trimmed = tool.trim();
        String alias = TOOL_ALIASES.get(trimmed.toLowerCase());
        return alias != null ? alias : trimmed;
    }

    private static boolean resolveRunCommandEnabled() {
        String sys = System.getProperty("codeagent.run_command.enabled");
        String env = System.getenv("CODEAGENT_RUN_COMMAND_ENABLED");
        String val = (sys != null && !sys.isBlank()) ? sys : env;
        return "true".equalsIgnoreCase(val);
    }

    private static boolean resolvePendingWorkflowEnabled() {
        String sys = System.getProperty("codeagent.pending.enabled");
        String env = System.getenv("CODEAGENT_PENDING_ENABLED");
        String val = (sys != null && !sys.isBlank()) ? sys : env;
        if (val == null || val.isBlank()) {
            return true;
        }
        return "true".equalsIgnoreCase(val);
    }

    private int resolveToolArgsLimit(String tool) {
        if (tool == null || tool.isEmpty()) {
            return defaultMaxArgsChars;
        }
        String upper = tool.toUpperCase();
        int override = resolveIntEnv("CODEAGENT_TOOL_ARGS_MAX_" + upper, "codeagent.tool.args.max." + tool.toLowerCase(), -1);
        if (override > 0) {
            return override;
        }
        if ("STR_REPLACE_EDITOR".equals(upper)) {
            return Math.min(defaultMaxArgsChars, 8000);
        }
        if ("EDIT_FILE".equals(upper) || "REPLACE_LINES".equals(upper)) {
            return Math.min(defaultMaxArgsChars, 16000);
        }
        if ("APPLY_PATCH".equals(upper)) {
            return Math.max(defaultMaxArgsChars, 20000);
        }
        return defaultMaxArgsChars;
    }

    private Set<String> resolveToolSet(String envKey, String propKey) {
        String prop = System.getProperty(propKey);
        String env = System.getenv(envKey);
        String raw = (prop != null && !prop.trim().isEmpty()) ? prop.trim() : (env == null ? "" : env.trim());
        if (raw.isEmpty()) {
            return Collections.emptySet();
        }
        String[] parts = raw.split("[,;\\s]+");
        Set<String> out = new HashSet<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String val = part.trim();
            if (val.isEmpty()) {
                continue;
            }
            out.add(normalizeToolName(val).toUpperCase());
        }
        return out;
    }

    private int resolveIntEnv(String envKey, String propKey, int fallback) {
        String prop = System.getProperty(propKey);
        if (prop != null && !prop.trim().isEmpty()) {
            try {
                return Integer.parseInt(prop.trim());
            } catch (Exception ignored) {}
        }
        String env = System.getenv(envKey);
        if (env != null && !env.trim().isEmpty()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (Exception ignored) {}
        }
        return fallback;
    }
}
