package com.zzf.rikki.idea.agent.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.project.Project;
import com.zzf.rikki.runtime.port.AgentRuntime;
import com.zzf.rikki.runtime.port.LlmPort;
import com.zzf.rikki.runtime.port.PendingApprovalPort;
import com.zzf.rikki.runtime.port.RuntimeRequest;
import com.zzf.rikki.runtime.port.ToolExecutorPort;
import com.zzf.rikki.session.SessionLoop;

public class JavaCompatRuntime implements AgentRuntime {
    private final SessionLoop sessionLoop;

    public JavaCompatRuntime(
            Project project,
            ObjectMapper mapper,
            PendingApprovalPort pendingApprovalPort,
            LlmPort llmPort,
            ToolExecutorPort toolExecutorPort
    ) {
        this.sessionLoop = new SessionLoop(project, mapper, pendingApprovalPort, llmPort, toolExecutorPort);
    }

    @Override
    public void run(RuntimeRequest request, AgentEventSink sink) {
        sessionLoop.run(request, sink);
    }
}
