package com.zzf.rikki.idea.agent.compat

import com.fasterxml.jackson.databind.ObjectMapper
import com.zzf.rikki.idea.agent.tools.LiteBashTool
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val MAX_TOOL_OUTPUT = 8_000

data class ProcessorResult(
    val answer: String,
    val messageId: String,
    val continueLoop: Boolean
)

class SessionProcessor(
    private val mapper: ObjectMapper,
    private val sessionService: SessionService,
    private val sessionStatus: SessionStatus,
    private val pendingApprovalService: InMemoryPendingApprovalService,
    private val llmStreamClient: LlmStreamClient,
    private val toolExecutor: ToolExecutor
) {
    suspend fun process(
        request: ChatRuntimeRequest,
        session: SessionInfo,
        assistantMessage: MessageWithParts,
        systemPrompt: String,
        capabilities: ModelCapabilities,
        toolDefinitions: List<Map<String, Any>>,
        sink: AgentEventSink
    ): ProcessorResult {
        val stepStart = StepStartPart(
            id = nextRuntimeId("part"),
            sessionID = session.id,
            messageID = assistantMessage.info.id
        )
        sessionService.updatePart(stepStart)

        var currentText: TextPart? = null
        var currentReasoning: ReasoningPart? = null
        val llmResult = llmStreamClient.streamChat(
            request = LlmChatRequest(
                messageId = assistantMessage.info.id,
                messages = sessionService.toLlmMessages(session.id, systemPrompt, capabilities.systemRole),
                capabilities = capabilities,
                toolDefinitions = toolDefinitions
            ),
            listener = object : LlmStreamListener {
                override fun onMessageDelta(messageId: String, delta: String) {
                    if (delta.isEmpty()) {
                        return
                    }
                    currentText = currentText ?: TextPart(
                        id = nextRuntimeId("part"),
                        sessionID = session.id,
                        messageID = messageId,
                        time = PartTime(start = System.currentTimeMillis())
                    ).also {
                        sessionService.updatePart(it)
                    }
                    currentText?.let { part ->
                        part.delta = delta
                        part.text += delta
                        part.time?.end = System.currentTimeMillis()
                        sessionService.updatePart(part)
                    }
                    sink.emit(RuntimeEvent.MessageDelta(messageId, delta))
                }

                override fun onThoughtDelta(messageId: String, delta: String) {
                    if (delta.isEmpty()) {
                        return
                    }
                    currentReasoning = currentReasoning ?: ReasoningPart(
                        id = nextRuntimeId("part"),
                        sessionID = session.id,
                        messageID = messageId,
                        time = PartTime(start = System.currentTimeMillis())
                    ).also {
                        sessionService.updatePart(it)
                    }
                    currentReasoning?.let { part ->
                        part.delta = delta
                        part.text += delta
                        part.time?.end = System.currentTimeMillis()
                        sessionService.updatePart(part)
                    }
                    sink.emit(RuntimeEvent.ThoughtDelta(messageId, delta))
                }

                override fun onThoughtEnd(messageId: String) {
                    currentReasoning?.let {
                        it.time?.end = System.currentTimeMillis()
                        sessionService.updatePart(it)
                    }
                    sink.emit(RuntimeEvent.ThoughtEnd(messageId))
                }
            }
        )

        if (llmResult.reasoningContent.isNotBlank() && currentReasoning == null) {
            val reasoningPart = ReasoningPart(
                id = nextRuntimeId("part"),
                sessionID = session.id,
                messageID = assistantMessage.info.id,
                text = llmResult.reasoningContent,
                delta = llmResult.reasoningContent,
                time = PartTime(start = System.currentTimeMillis(), end = System.currentTimeMillis())
            )
            sessionService.updatePart(reasoningPart)
            sink.emit(RuntimeEvent.ThoughtDelta(assistantMessage.info.id, llmResult.reasoningContent))
            sink.emit(RuntimeEvent.ThoughtEnd(assistantMessage.info.id))
        }

        val finishReason = if (llmResult.toolCalls.isEmpty()) "stop" else "tool-calls"
        val stepFinish = StepFinishPart(
            id = nextRuntimeId("part"),
            sessionID = session.id,
            messageID = assistantMessage.info.id,
            reason = finishReason,
            tokens = assistantMessage.info.tokens,
            cost = assistantMessage.info.cost
        )
        sessionService.updatePart(stepFinish)

        for (toolCall in llmResult.toolCalls) {
            currentCoroutineContext().ensureActive()
            val argsMap = LinkedHashMap(toArgsMap(toolCall.args))
            val toolPart = ToolPart(
                id = nextRuntimeId("part"),
                sessionID = session.id,
                messageID = assistantMessage.info.id,
                callID = toolCall.id,
                tool = toolCall.name,
                args = argsMap,
                state = ToolState(
                    status = if (toolExecutor.isHighRisk(toolCall.name, toolCall.args)) "pending" else "running",
                    input = LinkedHashMap(argsMap),
                    title = toolCall.name,
                    time = ToolStateTimeInfo(start = System.currentTimeMillis())
                )
            )
            sessionService.updatePart(toolPart)
            if (toolExecutor.isHighRisk(toolCall.name, toolCall.args)) {
                handlePendingToolCall(request, session, assistantMessage, toolPart, toolCall, argsMap, sink)
            } else {
                sink.emit(
                    RuntimeEvent.ToolCall(
                        partId = toolPart.id,
                        tool = toolPart.tool,
                        callId = toolPart.callID,
                        messageId = assistantMessage.info.id,
                        state = "running",
                        title = toolPart.tool,
                        args = argsMap
                    )
                )
                val result = toolExecutor.execute(
                    name = toolCall.name,
                    args = toolCall.args,
                    workspaceRoot = request.workspaceRoot,
                    sessionId = session.id,
                    callId = toolCall.id,
                    messageId = assistantMessage.info.id
                )
                applyToolResult(session, toolPart, result, sink)
            }
        }

        if (llmResult.text.startsWith("Error:") && llmResult.toolCalls.isEmpty()) {
            assistantMessage.info.error = ErrorInfo(message = llmResult.text, type = "llm_error")
            assistantMessage.info.finishReason = "error"
        } else {
            assistantMessage.info.finishReason = finishReason
        }
        assistantMessage.info.finish = true
        assistantMessage.info.time.end = System.currentTimeMillis()
        sessionService.updateMessage(assistantMessage)

        return ProcessorResult(
            answer = llmResult.text,
            messageId = assistantMessage.info.id,
            continueLoop = llmResult.toolCalls.isNotEmpty()
        )
    }

    private suspend fun handlePendingToolCall(
        request: ChatRuntimeRequest,
        session: SessionInfo,
        assistantMessage: MessageWithParts,
        toolPart: ToolPart,
        toolCall: ToolCallInfo,
        argsMap: Map<String, Any?>,
        sink: AgentEventSink
    ) {
        val pendingRecord = buildPendingCommandRecord(
            toolCall = toolCall,
            argsMap = argsMap,
            workspaceRoot = request.workspaceRoot,
            sessionId = session.id,
            messageId = assistantMessage.info.id
        )
        val registration = pendingApprovalService.registerPendingCommand(pendingRecord)
        val meta = pendingMeta(registration.pendingCommand)
        sink.emit(
            RuntimeEvent.ToolCall(
                partId = toolPart.id,
                tool = toolPart.tool,
                callId = toolPart.callID,
                messageId = assistantMessage.info.id,
                state = if (registration.awaitApproval) "pending" else "running",
                title = toolPart.tool,
                args = argsMap,
                meta = meta
            )
        )
        toolPart.state.metadata.putAll(meta)
        sessionService.updatePart(toolPart)
        if (registration.awaitApproval) {
            sessionStatus.set(session.id, SessionStatusInfo(type = "waiting_approval", message = "Awaiting your approval..."))
            sink.emit(RuntimeEvent.StatusChanged("waiting_approval", "Awaiting your approval..."))
            if (registration.pendingCommand.strictApproval) {
                sink.emit(
                    RuntimeEvent.ToolPendingApproval(
                        partId = toolPart.id,
                        callId = toolPart.callID,
                        command = registration.pendingCommand.command,
                        tool = toolPart.tool
                    )
                )
            }
            val result = pendingApprovalService.awaitDecision(registration.pendingCommand.id)
            sessionStatus.set(session.id, SessionStatusInfo(type = "busy", message = "Agent is thinking..."))
            sink.emit(RuntimeEvent.StatusChanged("busy", "Agent is thinking..."))
            applyToolResult(session, toolPart, result, sink)
        } else {
            val result = registration.immediateResult ?: ToolExecutionResult(
                status = "error",
                output = "",
                error = "Auto-approved command result missing."
            )
            applyToolResult(session, toolPart, result, sink)
        }
    }

    private fun applyToolResult(
        session: SessionInfo,
        toolPart: ToolPart,
        result: ToolExecutionResult,
        sink: AgentEventSink
    ) {
        val startedAt = toolPart.state.time.start
        val meta = LinkedHashMap<String, Any?>()
        meta.putAll(result.meta)
        result.pendingChange?.let { meta["pending_change"] = it }
        result.pendingCommand?.let { meta["pending_command"] = it.toMetaMap() }
        toolPart.state = ToolState(
            status = if (result.status.isBlank()) "completed" else result.status,
            input = LinkedHashMap(toolPart.args),
            output = result.output.take(MAX_TOOL_OUTPUT),
            title = toolPart.tool,
            error = result.error,
            metadata = meta,
            time = ToolStateTimeInfo(start = startedAt, end = System.currentTimeMillis())
        )
        sessionService.updatePart(toolPart)
        sink.emit(
            RuntimeEvent.ToolResult(
                partId = toolPart.id,
                tool = toolPart.tool,
                callId = toolPart.callID,
                messageId = toolPart.messageID,
                state = toolPart.state.status,
                title = toolPart.tool,
                output = toolPart.state.output,
                error = toolPart.state.error,
                meta = if (meta.isEmpty()) null else meta
            )
        )
        if (result.todoJson != null) {
            sink.emit(RuntimeEvent.TodoUpdated(result.todoJson, session.id))
        }
    }

    private fun toArgsMap(args: com.fasterxml.jackson.databind.JsonNode): Map<String, Any?> = try {
        @Suppress("UNCHECKED_CAST")
        mapper.convertValue(args, Map::class.java) as Map<String, Any?>
    } catch (_: Exception) {
        emptyMap()
    }

    private fun pendingMeta(pendingCommand: PendingCommandRecord): Map<String, Any?> = linkedMapOf(
        "pending_command" to pendingCommand.toMetaMap(),
        "approval_options" to if (pendingCommand.strictApproval) {
            listOf(PendingApprovalService.DECISION_MANUAL)
        } else {
            listOf(
                PendingApprovalService.DECISION_MANUAL,
                PendingApprovalService.DECISION_WHITELIST,
                PendingApprovalService.DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE
            )
        },
        "requires_explicit_user_consent" to true,
        "risk_level" to pendingCommand.riskLevel,
        "risk_category" to pendingCommand.riskCategory,
        "risk_reasons" to pendingCommand.reasons
    )

    private fun buildPendingCommandRecord(
        toolCall: ToolCallInfo,
        argsMap: Map<String, Any?>,
        workspaceRoot: String,
        sessionId: String,
        messageId: String
    ): PendingCommandRecord {
        val shell = toolCall.args.path("shell").asText("auto").ifBlank { "auto" }
        val timeoutMs = toolCall.args.path("timeout").asLong(CommandRunner.DEFAULT_TIMEOUT_MS).let {
            if (it <= 0L) CommandRunner.DEFAULT_TIMEOUT_MS else it
        }
        val workdir = toolCall.args.path("workdir").asText(workspaceRoot).ifBlank { workspaceRoot }
        val command = if (toolCall.name == "bash") {
            toolCall.args.path("command").asText("(unknown)")
        } else {
            "Delete: ${toolCall.args.path("filePath").asText("(unknown)")}"
        }
        val strictApproval = toolCall.name == "delete_file" || LiteBashTool.isStrictApprovalCommand(command)
        val riskCategory = if (strictApproval) "destructive" else "restricted"
        val reasons = LiteBashTool.detectRiskReasons(command).ifEmpty {
            if (toolCall.name == "delete_file") listOf("file deletion requires approval") else emptyList()
        }
        return PendingCommandRecord(
            id = nextRuntimeId("pending"),
            command = command,
            description = argsMap["description"] as? String ?: toolCall.name,
            workdir = workdir,
            workspaceRoot = workspaceRoot,
            sessionId = sessionId,
            timeoutMs = timeoutMs,
            tool = toolCall.name,
            callId = toolCall.id,
            messageId = messageId,
            riskLevel = "high",
            riskCategory = riskCategory,
            commandFamily = LiteBashTool.commandFamily(command),
            strictApproval = strictApproval,
            reasons = reasons,
            shell = shell,
            executor = {
                toolExecutor.execute(
                    name = toolCall.name,
                    args = toolCall.args,
                    workspaceRoot = workspaceRoot,
                    sessionId = sessionId,
                    callId = toolCall.id,
                    messageId = messageId
                )
            }
        )
    }
}
