package com.zzf.rikki.idea.agent.compat

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.zzf.rikki.agent.AgentService
import com.zzf.rikki.bus.AgentBus
import com.zzf.rikki.llm.LLMService
import com.zzf.rikki.runtime.port.RuntimeRequest
import com.zzf.rikki.session.ContextCompactionService
import com.zzf.rikki.session.InstructionPrompt
import com.zzf.rikki.session.PromptReminderService
import com.zzf.rikki.session.SessionLoop
import com.zzf.rikki.session.SessionProcessor
import com.zzf.rikki.session.SessionService
import com.zzf.rikki.session.SessionStatus
import com.zzf.rikki.session.SystemPrompt

class LiteCompatRuntime(
    project: Project,
    mapper: ObjectMapper,
    pendingApprovalService: InMemoryPendingApprovalService,
    llmStreamClient: LlmStreamClient = LiteChatLlmStreamClient(mapper),
    toolExecutor: ToolExecutor = LiteToolExecutor(project, mapper, pendingApprovalService)
) : ChatRuntimeFacade {
    private val sessionService = SessionService(mapper)
    private val sessionStatus = SessionStatus()
    private val agentService = AgentService()
    private val llmService = LLMService(LlmPortAdapter(llmStreamClient))
    private val toolExecutorPort = ToolExecutorPortAdapter(toolExecutor)
    private val processor = SessionProcessor(
        mapper,
        sessionService,
        sessionStatus,
        PendingApprovalPortAdapter(pendingApprovalService),
        llmService,
        toolExecutorPort
    )
    private val sessionLoop = SessionLoop(
        sessionService,
        sessionStatus,
        SystemPrompt(mapper),
        InstructionPrompt(),
        PromptReminderService(),
        processor,
        toolExecutorPort,
        agentService,
        ContextCompactionService(sessionService, llmService, agentService)
    )
    private val runtime = JavaCompatRuntime(sessionLoop, AgentBus())

    init {
        toolExecutor.bindServices(sessionService, agentService)
    }

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