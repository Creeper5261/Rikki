package com.zzf.rikki.idea.agent.compat

import com.fasterxml.jackson.databind.JsonNode
import com.zzf.rikki.runtime.port.LlmPort
import com.zzf.rikki.runtime.port.PendingApprovalPort
import com.zzf.rikki.runtime.port.ToolExecutorPort
import kotlinx.coroutines.runBlocking

class ToolExecutorPortAdapter(
    private val delegate: ToolExecutor
) : ToolExecutorPort {
    override fun isHighRisk(name: String, args: JsonNode): Boolean = delegate.isHighRisk(name, args)

    override fun execute(
        name: String,
        args: JsonNode,
        workspaceRoot: String,
        sessionId: String,
        callId: String,
        messageId: String
    ): ToolExecutionResult = delegate.execute(name, args, workspaceRoot, sessionId, callId, messageId)

    override fun toolDefinitions(
        workspaceRoot: String,
        snapshot: com.zzf.rikki.idea.agent.tools.LiteIdeTools.CapabilitySnapshot
    ): List<Map<String, Any>> = delegate.toolDefinitions(workspaceRoot, snapshot)

    override fun refreshIdeCapabilities(): com.zzf.rikki.idea.agent.tools.LiteIdeTools.CapabilitySnapshot = delegate.refreshIdeCapabilities()

    override fun setIdeContext(ideContext: JsonNode) = delegate.setIdeContext(ideContext)

    override fun readTodosJson(workspaceRoot: String, sessionId: String): String? = delegate.readTodosJson(workspaceRoot, sessionId)

    override fun todosAsListJson(workspaceRoot: String): String = delegate.todosAsListJson(workspaceRoot)
}

class LlmPortAdapter(
    private val delegate: LlmStreamClient
) : LlmPort {
    override fun streamChat(request: LlmChatRequest, listener: LlmStreamListener): LlmStreamResult = runBlocking {
        delegate.streamChat(request, listener)
    }
}

class PendingApprovalPortAdapter(
    private val delegate: InMemoryPendingApprovalService
) : PendingApprovalPort {
    override fun registerPendingCommand(pendingCommand: PendingCommandRecord): PendingCommandRegistration =
        delegate.registerPendingCommand(pendingCommand)

    override fun awaitDecision(commandId: String): ToolExecutionResult = runBlocking {
        delegate.awaitDecision(commandId)
    }

    override fun resolve(commandId: String, reject: Boolean, decisionMode: String): PendingCommandResolutionResult =
        delegate.resolve(commandId, reject, decisionMode)

    override fun skipCurrentExecution() = delegate.skipCurrentExecution()

    override fun clearSession(sessionId: String) = delegate.clearSession(sessionId)
}
