package com.zzf.rikki.idea.agent.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap

class SessionService(
    private val mapper: ObjectMapper
) {
    private val sessions = ConcurrentHashMap<String, SessionInfo>()
    private val messages = ConcurrentHashMap<String, MutableList<MessageWithParts>>()

    fun getOrCreate(sessionId: String, workspaceRoot: String): SessionInfo {
        val normalizedId = sessionId.ifBlank { nextRuntimeId("session") }
        return sessions.compute(normalizedId) { _, existing ->
            existing?.apply {
                updatedAt = System.currentTimeMillis()
            } ?: SessionInfo(
                id = normalizedId,
                workspaceRoot = workspaceRoot
            )
        }!!
    }

    fun get(sessionId: String): SessionInfo? = sessions[sessionId]

    fun importHistory(sessionId: String, history: JsonNode) {
        val session = sessions[sessionId] ?: return
        if (session.historyImported || !history.isArray) {
            return
        }
        for (entry in history) {
            when {
                entry.isTextual -> importLegacyLine(sessionId, entry.asText(""))
                entry.isObject -> importStructuredHistoryEntry(sessionId, entry)
            }
        }
        session.historyImported = true
        session.updatedAt = System.currentTimeMillis()
    }

    fun addUserMessage(sessionId: String, text: String): MessageWithParts = addTextMessage(sessionId, "user", text)

    fun startAssistantMessage(sessionId: String, providerId: String, modelId: String): MessageWithParts {
        val created = System.currentTimeMillis()
        val parentId = getMessages(sessionId)
            .lastOrNull { it.info.role == "user" }
            ?.info
            ?.id
        val info = MessageInfo(
            id = nextRuntimeId("message"),
            sessionID = sessionId,
            role = "assistant",
            created = created,
            providerID = providerId,
            modelID = modelId,
            parentID = parentId,
            finish = false,
            time = MessageTime(created = created, start = created),
            tokens = TokenUsage(cache = CacheUsage())
        )
        val message = MessageWithParts(info = info)
        messages.computeIfAbsent(sessionId) { mutableListOf() }.add(message)
        touch(sessionId)
        return message
    }

    fun updateMessage(message: MessageWithParts) {
        val bucket = messages.computeIfAbsent(message.info.sessionID) { mutableListOf() }
        val index = bucket.indexOfFirst { it.info.id == message.info.id }
        if (index >= 0) {
            bucket[index] = message
        } else {
            bucket += message
        }
        touch(message.info.sessionID)
    }

    fun updatePart(part: PromptPart) {
        val bucket = messages[part.sessionID] ?: return
        val message = bucket.firstOrNull { it.info.id == part.messageID } ?: return
        val index = message.parts.indexOfFirst { it.id == part.id }
        if (index >= 0) {
            message.parts[index] = part
        } else {
            message.parts += part
        }
        touch(part.sessionID)
    }

    fun getMessages(sessionId: String): List<MessageWithParts> = messages[sessionId]?.toList() ?: emptyList()

    fun getFilteredMessages(sessionId: String): List<MessageWithParts> {
        val all = getMessages(sessionId)
        if (all.isEmpty()) {
            return emptyList()
        }
        val summaryIndex = all.indexOfLast { it.info.role == "assistant" && it.info.summary == true }
        return if (summaryIndex >= 0) all.subList(summaryIndex, all.size).toList() else all
    }

    fun getMessage(messageId: String): MessageWithParts? {
        if (messageId.isBlank()) {
            return null
        }
        for (bucket in messages.values) {
            val match = bucket.firstOrNull { it.info.id == messageId }
            if (match != null) {
                return match
            }
        }
        return null
    }

    fun toLlmMessages(
        sessionId: String,
        systemPrompt: String,
        systemRole: String
    ): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        result += mapOf("role" to systemRole, "content" to systemPrompt)
        for (message in getFilteredMessages(sessionId)) {
            when (message.info.role) {
                "user", "system" -> {
                    val text = message.textContent()
                    if (text.isNotBlank()) {
                        result += mapOf("role" to message.info.role, "content" to text)
                    }
                }

                "assistant" -> {
                    val text = message.textContent().ifBlank { null }
                    val toolParts = message.toolParts()
                    if (text != null || toolParts.isNotEmpty()) {
                        val assistantMessage = linkedMapOf<String, Any?>(
                            "role" to "assistant",
                            "content" to text
                        )
                        if (toolParts.isNotEmpty()) {
                            assistantMessage["tool_calls"] = toolParts.map { part ->
                                mapOf(
                                    "id" to part.callID,
                                    "type" to "function",
                                    "function" to mapOf(
                                        "name" to part.tool,
                                        "arguments" to mapper.writeValueAsString(part.args)
                                    )
                                )
                            }
                        }
                        result += assistantMessage
                    }
                    for (toolPart in toolParts) {
                        if (toolPart.state.time.compacted == true) {
                            continue
                        }
                        val output = toolPart.state.output.ifBlank { toolPart.state.error ?: "" }
                        if (output.isNotBlank()) {
                            result += mapOf(
                                "role" to "tool",
                                "tool_call_id" to toolPart.callID,
                                "content" to output
                            )
                        }
                    }
                }
            }
        }
        return result
    }

    private fun importLegacyLine(sessionId: String, raw: String) {
        val text = raw.trim()
        if (text.isBlank()) {
            return
        }
        val parsed = LiteModelSupport.parseHistoryLine(text) ?: return
        addTextMessage(sessionId, parsed.first, parsed.second)
    }

    private fun importStructuredHistoryEntry(sessionId: String, entry: JsonNode) {
        val role = entry.path("role").asText("").ifBlank { return }
        val created = entry.path("timestamp").asLong(System.currentTimeMillis())
        val info = MessageInfo(
            id = entry.path("messageID").asText("").ifBlank { nextRuntimeId("message") },
            sessionID = sessionId,
            role = role,
            created = created,
            finish = true,
            finishReason = "history",
            time = MessageTime(created = created, start = created, end = created)
        )
        val message = MessageWithParts(info = info)
        val text = entry.path("text").asText("")
        if (text.isNotBlank()) {
            message.parts += TextPart(
                id = nextRuntimeId("part"),
                sessionID = sessionId,
                messageID = info.id,
                text = text,
                delta = text,
                time = PartTime(start = created, end = created)
            )
        }
        val thought = entry.path("thought").asText("")
        if (thought.isNotBlank()) {
            message.parts += ReasoningPart(
                id = nextRuntimeId("part"),
                sessionID = sessionId,
                messageID = info.id,
                text = thought,
                delta = thought,
                time = PartTime(start = created, end = created)
            )
        }
        val toolActivities = entry.path("toolActivities")
        if (toolActivities.isArray) {
            for (activity in toolActivities) {
                val metaMap = try {
                    val rawMeta = activity.path("meta").asText("")
                    if (rawMeta.isBlank()) linkedMapOf<String, Any?>() else mapper.readValue(rawMeta, LinkedHashMap::class.java) as LinkedHashMap<String, Any?>
                } catch (_: Exception) {
                    linkedMapOf()
                }
                val details = activity.path("details").asText("")
                val status = activity.path("status").asText("completed")
                val toolPart = ToolPart(
                    id = activity.path("id").asText("").ifBlank { nextRuntimeId("part") },
                    sessionID = sessionId,
                    messageID = info.id,
                    callID = activity.path("callID").asText("").ifBlank { nextRuntimeId("call") },
                    tool = activity.path("tool").asText("tool"),
                    args = linkedMapOf(),
                    state = ToolState(
                        status = status,
                        input = linkedMapOf(),
                        output = details,
                        title = activity.path("summary").asText(activity.path("tool").asText("tool")),
                        metadata = metaMap,
                        time = ToolStateTimeInfo(start = created, end = created)
                    )
                )
                message.parts += toolPart
            }
        }
        messages.computeIfAbsent(sessionId) { mutableListOf() }.add(message)
        touch(sessionId)
    }

    private fun addTextMessage(sessionId: String, role: String, text: String): MessageWithParts {
        val created = System.currentTimeMillis()
        val message = MessageWithParts(
            info = MessageInfo(
                id = nextRuntimeId("message"),
                sessionID = sessionId,
                role = role,
                created = created,
                finish = true,
                finishReason = "history",
                time = MessageTime(created = created, start = created, end = created),
                user = if (role == "user") UserInfo(id = nextRuntimeId("user")) else null
            )
        )
        message.parts += TextPart(
            id = nextRuntimeId("part"),
            sessionID = sessionId,
            messageID = message.info.id,
            text = text,
            delta = text,
            time = PartTime(start = created, end = created)
        )
        messages.computeIfAbsent(sessionId) { mutableListOf() }.add(message)
        touch(sessionId)
        return message
    }

    private fun touch(sessionId: String) {
        sessions[sessionId]?.updatedAt = System.currentTimeMillis()
    }
}
