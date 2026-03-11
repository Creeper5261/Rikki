package com.zzf.rikki.idea.agent.compat

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.zzf.rikki.idea.settings.RikkiSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class SessionLoop(
    project: Project,
    mapper: ObjectMapper,
    private val pendingApprovalService: InMemoryPendingApprovalService,
    private val llmStreamClient: LlmStreamClient,
    private val toolExecutor: ToolExecutor,
    private val sessionService: SessionService = SessionService(mapper),
    private val sessionStatus: SessionStatus = SessionStatus(),
    private val systemPrompt: SystemPrompt = SystemPrompt(mapper),
    private val instructionPrompt: InstructionPrompt = InstructionPrompt(),
    private val reminderService: PromptReminderService = PromptReminderService()
) {
    companion object {
        private const val MAX_STEPS = 120
    }

    private val processor = SessionProcessor(
        mapper = mapper,
        sessionService = sessionService,
        sessionStatus = sessionStatus,
        pendingApprovalService = pendingApprovalService,
        llmStreamClient = llmStreamClient,
        toolExecutor = toolExecutor
    )

    suspend fun run(request: ChatRuntimeRequest, sink: AgentEventSink) {
        val settings = RikkiSettings.getInstance().state
        val session = sessionService.getOrCreate(request.sessionId, request.workspaceRoot)
        val reused = sessionService.getMessages(session.id).isNotEmpty()

        sink.emit(RuntimeEvent.SessionBound(session.id, reused))
        sessionStatus.set(session.id, SessionStatusInfo(type = "busy", message = "Agent is thinking..."))
        sink.emit(RuntimeEvent.StatusChanged("busy", "Agent is thinking..."))

        toolExecutor.setIdeContext(request.ideContext)
        sessionService.importHistory(session.id, request.history)
        if (request.goal.isNotBlank()) {
            sessionService.addUserMessage(session.id, request.goal)
        }

        val capabilities = LiteModelSupport.detectCapabilities(settings.provider, settings.modelName)
        val ideCapabilitySnapshot = toolExecutor.refreshIdeCapabilities()
        val toolDefinitions = toolExecutor.toolDefinitions(request.workspaceRoot, ideCapabilitySnapshot)

        var answer = ""
        var lastMessageId = ""
        for (step in 0 until MAX_STEPS) {
            currentCoroutineContext().ensureActive()
            val promptSections = mutableListOf<String>()
            promptSections += systemPrompt.build(
                workspaceRoot = request.workspaceRoot,
                ideContext = request.ideContext,
                caps = capabilities,
                ideCapabilities = ideCapabilitySnapshot,
                modelId = settings.modelName
            )
            promptSections += instructionPrompt.system(request.workspaceRoot)
            promptSections += reminderService.reminders(session, sessionService.getMessages(session.id))
            val composedSystemPrompt = promptSections.filter { it.isNotBlank() }.joinToString("\n\n")

            val assistantMessage = sessionService.startAssistantMessage(
                sessionId = session.id,
                providerId = settings.provider,
                modelId = settings.modelName
            )
            val result = processor.process(
                request = request,
                session = session,
                assistantMessage = assistantMessage,
                systemPrompt = composedSystemPrompt,
                capabilities = capabilities,
                toolDefinitions = toolDefinitions,
                sink = sink
            )
            answer = result.answer
            lastMessageId = result.messageId
            if (!result.continueLoop) {
                break
            }
        }

        sink.emit(RuntimeEvent.Finished(session.id, lastMessageId, answer))
        sessionStatus.set(session.id, SessionStatusInfo(type = "idle", message = "Ready"))
        sink.emit(RuntimeEvent.StatusChanged("idle", "Ready"))
    }
}
