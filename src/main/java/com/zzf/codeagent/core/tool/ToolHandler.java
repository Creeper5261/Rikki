package com.zzf.codeagent.core.tool;

import com.zzf.codeagent.core.tool.ToolProtocol.ToolEnvelope;
import com.zzf.codeagent.core.tool.ToolProtocol.ToolResult;
import com.zzf.codeagent.core.tool.ToolProtocol.ToolSpec;

public interface ToolHandler {
    ToolSpec spec();
    ToolResult execute(ToolEnvelope env, ToolExecutionContext ctx);
}
