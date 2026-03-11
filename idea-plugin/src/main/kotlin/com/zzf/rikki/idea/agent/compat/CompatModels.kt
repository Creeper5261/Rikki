package com.zzf.rikki.idea.agent.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.zzf.rikki.core.tool.PendingChangesManager
import com.zzf.rikki.idea.agent.tools.LiteIdeTools
import com.zzf.rikki.idea.settings.RikkiSettings

interface AgentEventSink {
    fun emit(event: RuntimeEvent)
}

sealed class RuntimeEvent {
    data class SessionBound(
        val sessionId: String,
        val reused: Boolean = false
    ) : RuntimeEvent()

    data class StatusChanged(
        val type: String,
        val message: String
    ) : RuntimeEvent()

    data class ThoughtDelta(
        val messageId: String,
        val delta: String
    ) : RuntimeEvent()

    data class ThoughtEnd(
        val messageId: String
    ) : RuntimeEvent()

    data class MessageDelta(
        val messageId: String,
        val delta: String
    ) : RuntimeEvent()

    data class MessageSnapshot(
        val messageId: String,
        val text: String
    ) : RuntimeEvent()

    data class ToolCall(
        val partId: String,
        val tool: String,
        val callId: String,
        val messageId: String,
        val state: String,
        val title: String,
        val args: Map<String, Any?>,
        val meta: Map<String, Any?>? = null
    ) : RuntimeEvent()

    data class ToolPendingApproval(
        val partId: String,
        val callId: String,
        val command: String,
        val tool: String
    ) : RuntimeEvent()

    data class ToolResult(
        val partId: String,
        val tool: String,
        val callId: String,
        val messageId: String,
        val state: String,
        val title: String,
        val output: String,
        val error: String? = null,
        val meta: Map<String, Any?>? = null
    ) : RuntimeEvent()

    data class TodoUpdated(
        val todosJson: String,
        val sessionId: String? = null
    ) : RuntimeEvent()

    data class Finished(
        val sessionId: String,
        val messageId: String,
        val answer: String
    ) : RuntimeEvent()

    data class Errored(
        val message: String
    ) : RuntimeEvent()
}

data class ChatRuntimeRequest(
    val goal: String,
    val workspaceRoot: String,
    val ideContext: JsonNode,
    val history: JsonNode,
    val settings: JsonNode,
    val sessionId: String
)

interface ChatRuntimeFacade {
    suspend fun run(request: ChatRuntimeRequest, sink: AgentEventSink)
}

data class ModelCapabilities(
    val systemRole: String = "system",
    val temperatureFixed: Double? = null,
    val maxTokensKey: String = "max_tokens",
    val supportsTools: Boolean = true,
    val hasReasoningContent: Boolean = false
)

object LiteModelSupport {
    fun detectCapabilities(provider: String, model: String): ModelCapabilities {
        val normalizedModel = model.trim().lowercase()
        return when {
            normalizedModel == "deepseek-reasoner" ->
                ModelCapabilities(hasReasoningContent = true)

            provider == "OPENAI" && (
                normalizedModel == "o1"
                    || normalizedModel == "o1-mini"
                    || normalizedModel == "o1-preview"
                ) ->
                ModelCapabilities(
                    systemRole = "developer",
                    temperatureFixed = 1.0,
                    maxTokensKey = "max_completion_tokens",
                    supportsTools = false
                )

            provider == "OPENAI" && (
                normalizedModel.startsWith("o3")
                    || normalizedModel.startsWith("o4")
                ) ->
                ModelCapabilities(maxTokensKey = "max_completion_tokens")

            else -> ModelCapabilities()
        }
    }

    fun parseHistoryLine(text: String): Pair<String, String>? = when {
        text.startsWith("You:") -> "user" to text.removePrefix("You:").trim()
        text.startsWith("Agent:") -> "assistant" to text.removePrefix("Agent:").trim()
        text.startsWith("Assistant:") -> "assistant" to text.removePrefix("Assistant:").trim()
        text.startsWith("System:") -> "system" to text.removePrefix("System:").trim()
        else -> null
    }
}

interface PromptStrategy {
    fun buildSystemPrompt(
        workspaceRoot: String,
        ideContext: JsonNode,
        caps: ModelCapabilities,
        ideCapabilities: LiteIdeTools.CapabilitySnapshot,
        modelId: String
    ): String
}

class LitePromptStrategy(
    private val mapper: ObjectMapper
) : PromptStrategy {
    override fun buildSystemPrompt(
        workspaceRoot: String,
        ideContext: JsonNode,
        caps: ModelCapabilities,
        ideCapabilities: LiteIdeTools.CapabilitySnapshot,
        modelId: String
    ): String {
        val prompt = PromptTextLoader.loadSessionPrompt(modelId)
        val sb = StringBuilder(prompt)
        sb.append("\n\n<plugin-runtime>\n")
        sb.append("Working directory: ").append(workspaceRoot).append("\n")
        sb.append("IDE bridge available: ").append(ideCapabilities.bridgeAvailable).append("\n")
        if (ideCapabilities.actionOperations.isNotEmpty()) {
            sb.append("IDE actions available: ").append(ideCapabilities.actionOperations.joinToString(", ")).append("\n")
        }
        if (!caps.supportsTools) {
            sb.append("This model does not support tool calls; answer without executing tools.\n")
        }
        sb.append("</plugin-runtime>")
        if (!ideContext.isNull && !ideContext.isMissingNode && ideContext.size() > 0) {
            sb.append("\n\n<ide_context>\n")
            sb.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ideContext))
            sb.append("\n</ide_context>")
        }
        return sb.toString()
    }
}

data class ToolCallInfo(
    val id: String,
    val name: String,
    val argsRaw: String,
    val args: JsonNode
)

data class LlmStreamResult(
    val text: String,
    val toolCalls: List<ToolCallInfo>,
    val reasoningContent: String = ""
)

data class LlmChatRequest(
    val messageId: String,
    val messages: List<Map<String, Any?>>,
    val capabilities: ModelCapabilities,
    val toolDefinitions: List<Map<String, Any>>
)

interface LlmStreamListener {
    fun onMessageDelta(messageId: String, delta: String)
    fun onThoughtDelta(messageId: String, delta: String)
    fun onThoughtEnd(messageId: String)
}

interface LlmStreamClient {
    suspend fun streamChat(
        request: LlmChatRequest,
        listener: LlmStreamListener
    ): LlmStreamResult
}

data class PendingCommandRecord(
    val id: String,
    val command: String,
    val description: String,
    val workdir: String,
    val workspaceRoot: String,
    val sessionId: String,
    val timeoutMs: Long,
    val tool: String,
    val callId: String,
    val messageId: String,
    val riskLevel: String,
    val riskCategory: String,
    val commandFamily: String,
    val strictApproval: Boolean,
    val reasons: List<String>,
    val shell: String? = null,
    val executor: () -> ToolExecutionResult
) {
    fun toMetaMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "command" to command,
        "description" to description,
        "workdir" to workdir,
        "workspaceRoot" to workspaceRoot,
        "sessionId" to sessionId,
        "timeoutMs" to timeoutMs,
        "riskLevel" to riskLevel,
        "riskCategory" to riskCategory,
        "commandFamily" to commandFamily,
        "strictApproval" to strictApproval,
        "reasons" to reasons
    )
}

data class ToolExecutionResult(
    val status: String,
    val output: String,
    val error: String? = null,
    val meta: Map<String, Any?> = emptyMap(),
    val pendingChange: PendingChangesManager.PendingChange? = null,
    val pendingCommand: PendingCommandRecord? = null,
    val todoJson: String? = null,
    val exitCode: Int? = null,
    val timeout: Boolean = false
)

data class PendingCommandRegistration(
    val pendingCommand: PendingCommandRecord,
    val awaitApproval: Boolean,
    val immediateResult: ToolExecutionResult? = null
)

data class PendingCommandResolutionResult(
    val status: String,
    val output: String,
    val error: String = "",
    val exitCode: Int? = null,
    val command: String = "",
    val timeout: Boolean = false,
    val decisionMode: String = PendingApprovalService.DECISION_MANUAL
)

fun ToolExecutionResult.toResolutionResult(
    command: String,
    decisionMode: String
): PendingCommandResolutionResult = PendingCommandResolutionResult(
    status = status,
    output = output,
    error = error ?: "",
    exitCode = exitCode,
    command = command,
    timeout = timeout,
    decisionMode = decisionMode
)

interface PendingApprovalService {
    fun registerPendingCommand(pendingCommand: PendingCommandRecord): PendingCommandRegistration

    suspend fun awaitDecision(commandId: String): ToolExecutionResult

    fun resolve(
        commandId: String,
        reject: Boolean,
        decisionMode: String
    ): PendingCommandResolutionResult

    fun skipCurrentExecution()

    fun clearSession(sessionId: String)

    companion object {
        const val DECISION_MANUAL = "manual"
        const val DECISION_WHITELIST = "whitelist"
        const val DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE = "always_allow_non_destructive"
    }
}