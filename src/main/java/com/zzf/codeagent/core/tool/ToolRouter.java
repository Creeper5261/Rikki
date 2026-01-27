package com.zzf.codeagent.core.tool;

import com.zzf.codeagent.core.tool.ToolProtocol.ToolEnvelope;
import com.zzf.codeagent.core.tool.ToolProtocol.ToolResult;

public final class ToolRouter {
    private final ToolRegistry registry;

    public ToolRouter(ToolRegistry registry) {
        this.registry = registry;
    }

    public ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx) {
        if (env == null || env.getTool().isEmpty()) {
            return ToolResult.error("", ToolProtocol.DEFAULT_VERSION, "tool_is_blank");
        }
        String version = registry.resolveVersion(env.getTool(), env.getVersion());
        ToolHandler handler = registry.get(env.getTool(), version);
        if (handler == null) {
            return ToolResult.error(env.getTool(), version, "unsupported_tool");
        }
        ToolEnvelope resolved = new ToolEnvelope(env.getTool(), version, env.getArgs(), env.getTraceId(), env.getRequestId());
        return handler.execute(resolved, ctx);
    }
}
