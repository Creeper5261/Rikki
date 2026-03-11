package com.zzf.rikki.idea.agent.compat

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = MessageV2.TextPart::class, name = "text"),
    JsonSubTypes.Type(value = MessageV2.ReasoningPart::class, name = "reasoning"),
    JsonSubTypes.Type(value = MessageV2.FilePart::class, name = "file"),
    JsonSubTypes.Type(value = MessageV2.ToolPart::class, name = "tool"),
    JsonSubTypes.Type(value = MessageV2.CompactionPart::class, name = "compaction"),
    JsonSubTypes.Type(value = MessageV2.SubtaskPart::class, name = "subtask"),
    JsonSubTypes.Type(value = MessageV2.AgentPart::class, name = "agent"),
    JsonSubTypes.Type(value = MessageV2.StepStartPart::class, name = "step-start"),
    JsonSubTypes.Type(value = MessageV2.StepFinishPart::class, name = "step-finish")
)
abstract class PromptPart(
    open var id: String = "",
    open var type: String = "",
    @get:JsonProperty("sessionID")
    @set:JsonProperty("sessionID")
    open var sessionID: String = "",
    @get:JsonProperty("messageID")
    @set:JsonProperty("messageID")
    open var messageID: String = "",
    open var metadata: MutableMap<String, Any?> = linkedMapOf()
) {
    @get:JsonIgnore
    val sessionId: String
        get() = sessionID

    @get:JsonIgnore
    val messageId: String
        get() = messageID
}

class MessageV2 {
    data class TextPart(
        override var id: String = "",
        @get:JsonProperty("sessionID")
        @set:JsonProperty("sessionID")
        override var sessionID: String = "",
        @get:JsonProperty("messageID")
        @set:JsonProperty("messageID")
        override var messageID: String = "",
        var text: String = "",
        var delta: String = "",
        var synthetic: Boolean? = false,
        var ignored: Boolean? = false,
        var time: PartTime? = PartTime(),
        override var metadata: MutableMap<String, Any?> = linkedMapOf()
    ) : PromptPart(id = id, type = "text", sessionID = sessionID, messageID = messageID, metadata = metadata)

    data class ReasoningPart(
        override var id: String = "",
        @get:JsonProperty("sessionID")
        @set:JsonProperty("sessionID")
        override var sessionID: String = "",
        @get:JsonProperty("messageID")
        @set:JsonProperty("messageID")
        override var messageID: String = "",
        var text: String = "",
        var delta: String = "",
        var time: PartTime? = PartTime(),
        var collapsed: Boolean? = false,
        override var metadata: MutableMap<String, Any?> = linkedMapOf()
    ) : PromptPart(id = id, type = "reasoning", sessionID = sessionID, messageID = messageID, metadata = metadata)

    data class PartTime(
        var start: Long? = null,
        var end: Long? = null,
        var compacted: Boolean? = false
    )

    data class FilePart(
        override var id: String = "",
        @get:JsonProperty("sessionID")
        @set:JsonProperty("sessionID")
        override var sessionID: String = "",
        @get:JsonProperty("messageID")
        @set:JsonProperty("messageID")
        override var messageID: String = "",
        var mime: String? = null,
        var filename: String? = null,
        var url: String? = null,
        var content: String? = null,
        override var metadata: MutableMap<String, Any?> = linkedMapOf()
    ) : PromptPart(id = id, type = "file", sessionID = sessionID, messageID = messageID, metadata = metadata)

    data class CompactionPart(
        override var id: String = "",
        @get:JsonProperty("sessionID")
        @set:JsonProperty("sessionID")
        override var sessionID: String = "",
        @get:JsonProperty("messageID")
        @set:JsonProperty("messageID")
        override var messageID: String = "",
        var auto: Boolean = false,
        var summary: String? = null,
        override var metadata: MutableMap<String, Any?> = linkedMapOf()
    ) : PromptPart(id = id, type = "compaction", sessionID = sessionID, messageID = messageID, metadata = metadata)

    data class SubtaskPart(
        override var id: String = "",
        @get:JsonProperty("sessionID")
        @set:JsonProperty("sessionID")
        override var sessionID: String = "",
        @get:JsonProperty("messageID")
        @set:JsonProperty("messageID")
        override var messageID: String = "",
        var prompt: String? = null,
        var description: String? = null,
        var agent: String? = null,
        override var metadata: MutableMap<String, Any?> = linkedMapOf()
    ) : PromptPart(id = id, type = "subtask", sessionID = sessionID, messageID = messageID, metadata = metadata)

    data class ToolPart(
        override var id: String = "",
        @get:JsonProperty("sessionID")
        @set:JsonProperty("sessionID")
        override var sessionID: String = "",
        @get:JsonProperty("messageID")
        @set:JsonProperty("messageID")
        override var messageID: String = "",
        @get:JsonProperty("callID")
        @set:JsonProperty("callID")
        var callID: String = "",
        var tool: String = "",
        var args: MutableMap<String, Any?> = linkedMapOf(),
        var state: ToolState = ToolState(),
        override var metadata: MutableMap<String, Any?> = linkedMapOf()
    ) : PromptPart(id = id, type = "tool", sessionID = sessionID, messageID = messageID, metadata = metadata) {
        @get:JsonIgnore
        val callId: String
            get() = callID
    }

    data class StepStartPart(
        override var id: String = "",
        @get:JsonProperty("sessionID")
        @set:JsonProperty("sessionID")
        override var sessionID: String = "",
        @get:JsonProperty("messageID")
        @set:JsonProperty("messageID")
        override var messageID: String = "",
        var snapshot: String? = null,
        override var metadata: MutableMap<String, Any?> = linkedMapOf()
    ) : PromptPart(id = id, type = "step-start", sessionID = sessionID, messageID = messageID, metadata = metadata)

    data class StepFinishPart(
        override var id: String = "",
        @get:JsonProperty("sessionID")
        @set:JsonProperty("sessionID")
        override var sessionID: String = "",
        @get:JsonProperty("messageID")
        @set:JsonProperty("messageID")
        override var messageID: String = "",
        var reason: String? = "stop",
        var snapshot: String? = null,
        var tokens: TokenUsage? = null,
        var cost: Double? = null,
        override var metadata: MutableMap<String, Any?> = linkedMapOf()
    ) : PromptPart(id = id, type = "step-finish", sessionID = sessionID, messageID = messageID, metadata = metadata)

    data class AgentPart(
        override var id: String = "",
        @get:JsonProperty("sessionID")
        @set:JsonProperty("sessionID")
        override var sessionID: String = "",
        @get:JsonProperty("messageID")
        @set:JsonProperty("messageID")
        override var messageID: String = "",
        var name: String? = null,
        override var metadata: MutableMap<String, Any?> = linkedMapOf()
    ) : PromptPart(id = id, type = "agent", sessionID = sessionID, messageID = messageID, metadata = metadata)

    data class ToolState(
        var status: String = "running",
        var input: MutableMap<String, Any?> = linkedMapOf(),
        var output: String = "",
        var title: String = "",
        var error: String? = null,
        var metadata: MutableMap<String, Any?> = linkedMapOf(),
        var time: TimeInfo = TimeInfo()
    ) {
        data class TimeInfo(
            var start: Long? = null,
            var end: Long? = null,
            var compacted: Boolean? = false
        )
    }

    data class Assistant(
        var id: String = "",
        @get:JsonProperty("sessionID")
        @set:JsonProperty("sessionID")
        var sessionID: String = "",
        var role: String = "assistant",
        var created: Long = System.currentTimeMillis(),
        @get:JsonProperty("modelID")
        @set:JsonProperty("modelID")
        var modelID: String? = null,
        @get:JsonProperty("providerID")
        @set:JsonProperty("providerID")
        var providerID: String? = null,
        var agent: String? = null,
        @get:JsonProperty("parentID")
        @set:JsonProperty("parentID")
        var parentID: String? = null,
        var mode: String? = null,
        var summary: Boolean? = false,
        var tokens: TokenUsage? = null,
        var time: MessageTime = MessageTime(created = created),
        var cost: Double? = null,
        var finish: Boolean? = false,
        var finishReason: String? = null,
        var summaryInfo: MessageSummary? = null,
        var error: ErrorInfo? = null,
        var parts: MutableList<PromptPart> = mutableListOf()
    ) {
        fun toInfo(): MessageInfo = MessageInfo(
            id = id,
            sessionID = sessionID,
            role = role,
            created = created,
            modelID = modelID,
            providerID = providerID,
            agent = agent,
            parentID = parentID,
            mode = mode,
            summary = summary,
            tokens = tokens,
            time = time,
            cost = cost,
            summaryInfo = summaryInfo,
            error = error,
            finish = finish,
            finishReason = finishReason
        )

        fun withParts(): WithParts = WithParts(info = toInfo(), parts = parts)
    }

    data class MessageInfo(
        var id: String = "",
        @get:JsonProperty("sessionID")
        @set:JsonProperty("sessionID")
        var sessionID: String = "",
        var role: String = "assistant",
        var created: Long = System.currentTimeMillis(),
        @get:JsonProperty("modelID")
        @set:JsonProperty("modelID")
        var modelID: String? = null,
        @get:JsonProperty("providerID")
        @set:JsonProperty("providerID")
        var providerID: String? = null,
        var agent: String? = null,
        @get:JsonProperty("parentID")
        @set:JsonProperty("parentID")
        var parentID: String? = null,
        var mode: String? = null,
        var summary: Boolean? = false,
        var tokens: TokenUsage? = null,
        var time: MessageTime = MessageTime(created = created),
        var cost: Double? = null,
        var summaryInfo: MessageSummary? = null,
        var error: ErrorInfo? = null,
        var finish: Boolean? = false,
        var finishReason: String? = null,
        var user: User? = null
    ) {
        @get:JsonIgnore
        val sessionId: String
            get() = sessionID

        @get:JsonIgnore
        val modelId: String?
            get() = modelID

        @get:JsonIgnore
        val providerId: String?
            get() = providerID
    }

    data class ErrorInfo(
        var message: String? = null,
        var type: String? = null
    )

    data class MessageSummary(
        var title: String? = null,
        var diffs: MutableList<Any> = mutableListOf()
    )

    data class MessageTime(
        var created: Long = System.currentTimeMillis(),
        var start: Long? = null,
        var end: Long? = null
    )

    data class TokenUsage(
        var input: Int = 0,
        var output: Int = 0,
        var reasoning: Int = 0,
        var cache: CacheUsage = CacheUsage()
    )

    data class CacheUsage(
        var read: Int = 0,
        var write: Int = 0
    )

    data class User(
        var id: String? = null,
        var tools: MutableMap<String, Boolean> = linkedMapOf(),
        var system: String? = null,
        var variant: String? = null,
        var summary: MessageSummary? = null
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class WithParts(
        var info: MessageInfo = MessageInfo(),
        var parts: MutableList<PromptPart> = mutableListOf()
    ) {
        fun textContent(): String = parts
            .filterIsInstance<TextPart>()
            .filterNot { it.ignored == true }
            .joinToString("") { it.text }

        fun reasoningContent(): String = parts
            .filterIsInstance<ReasoningPart>()
            .joinToString("") { it.text }

        fun toolParts(): List<ToolPart> = parts.filterIsInstance<ToolPart>()
    }
}

typealias PartTime = MessageV2.PartTime
typealias TextPart = MessageV2.TextPart
typealias ReasoningPart = MessageV2.ReasoningPart
typealias FilePart = MessageV2.FilePart
typealias CompactionPart = MessageV2.CompactionPart
typealias SubtaskPart = MessageV2.SubtaskPart
typealias ToolPart = MessageV2.ToolPart
typealias StepStartPart = MessageV2.StepStartPart
typealias StepFinishPart = MessageV2.StepFinishPart
typealias AgentPart = MessageV2.AgentPart
typealias ToolState = MessageV2.ToolState
typealias ToolStateTimeInfo = MessageV2.ToolState.TimeInfo
typealias MessageInfo = MessageV2.MessageInfo
typealias MessageTime = MessageV2.MessageTime
typealias TokenUsage = MessageV2.TokenUsage
typealias CacheUsage = MessageV2.CacheUsage
typealias ErrorInfo = MessageV2.ErrorInfo
typealias MessageSummary = MessageV2.MessageSummary
typealias UserInfo = MessageV2.User
typealias MessageWithParts = MessageV2.WithParts

data class SessionInfo(
    val id: String,
    val workspaceRoot: String,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt,
    var historyImported: Boolean = false
)

internal fun nextRuntimeId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
