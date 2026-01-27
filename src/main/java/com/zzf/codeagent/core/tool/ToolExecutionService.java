package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.core.tool.ToolProtocol.ToolEnvelope;
import com.zzf.codeagent.core.tool.ToolProtocol.ToolResult;
import com.zzf.codeagent.core.tool.ToolProtocol.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ToolExecutionService {
    private static final Logger logger = LoggerFactory.getLogger(ToolExecutionService.class);
    private static final Pattern SENSITIVE_KV = Pattern.compile("(?i)(password|passwd|secret|token|apikey|accesskey|secretkey)\\s*[:=]\\s*([\"']?)([^\"'\\\\\\r\\n\\s]{1,160})\\2");
    private static final Pattern SENSITIVE_JSON_KV = Pattern.compile("(?i)(\"(?:password|passwd|secret|token|apiKey|accessKey|secretKey)\"\\s*:\\s*\")([^\"]{1,160})(\")");
    private final ToolRegistry registry;
    private final ToolRouter router;

    public ToolExecutionService() {
        ToolRegistry reg = new ToolRegistry();
        BuiltInToolHandlers.registerAll(reg);
        this.registry = reg;
        this.router = new ToolRouter(reg);
    }

    public String execute(String tool, String version, JsonNode args, ToolExecutionContext ctx) {
        long t0 = System.nanoTime();
        String requestId = "tool-" + UUID.randomUUID();
        try {
            String v = registry.resolveVersion(tool, version);
            ToolEnvelope env = new ToolEnvelope(tool, v, args, ctx.traceId, requestId);
            logger.info("tool.call traceId={} tool={} version={} requestId={}", ctx.traceId, env.getTool(), env.getVersion(), env.getRequestId());
            ToolResult result = router.execute(env, ctx);
            result = result.withTookMs((System.nanoTime() - t0) / 1_000_000L);
            ObjectNode out = result.toJson(ctx.mapper, env);
            logger.info("tool.result traceId={} tool={} version={} status={} error={}", ctx.traceId, env.getTool(), env.getVersion(), out.path("status").asText(""), out.path("error").asText(""));
            return sanitizeObservation(out.toString());
        } catch (Exception e) {
            logger.warn("tool.fail traceId={} tool={} err={}", ctx.traceId, tool, e.toString());
            ToolEnvelope env = new ToolEnvelope(tool, registry.resolveVersion(tool, version), args, ctx.traceId, requestId);
            ToolResult result = ToolResult.error(tool, env.getVersion(), e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()))
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
}
