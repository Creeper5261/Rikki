package com.zzf.rikki.idea.agent.compat

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.zzf.rikki.runtime.port.RuntimeRequest

class LiteCompatRuntime(
    project: Project,
    mapper: ObjectMapper,
    pendingApprovalService: InMemoryPendingApprovalService,
    llmStreamClient: LlmStreamClient = LiteChatLlmStreamClient(mapper),
    toolExecutor: ToolExecutor = LiteToolExecutor(project, mapper, pendingApprovalService)
) : ChatRuntimeFacade {
    private val runtime = JavaCompatRuntime(
        project,
        mapper,
        PendingApprovalPortAdapter(pendingApprovalService),
        LlmPortAdapter(llmStreamClient),
        ToolExecutorPortAdapter(toolExecutor)
    )

    override suspend fun run(request: ChatRuntimeRequest, sink: AgentEventSink) {
        runtime.run(
            RuntimeRequest(
                request.goal,
                request.workspaceRoot,
                request.ideContext,
                request.history,
                request.settings,
                request.sessionId
            ),
            sink
        )
    }
}
