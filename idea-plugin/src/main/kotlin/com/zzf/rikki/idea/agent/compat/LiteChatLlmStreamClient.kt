package com.zzf.rikki.idea.agent.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.zzf.rikki.idea.settings.RikkiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

class LiteChatLlmStreamClient(
    private val mapper: ObjectMapper
) : LlmStreamClient {
    override suspend fun streamChat(
        request: LlmChatRequest,
        listener: LlmStreamListener
    ): LlmStreamResult = withContext(Dispatchers.IO) {
        val settings = RikkiSettings.getInstance().state
        val apiKey = settings.currentApiKey()
        if (apiKey.isBlank() && settings.provider != "OLLAMA") {
            return@withContext LlmStreamResult(
                text = "Error: API key not configured.",
                toolCalls = emptyList()
            )
        }

        val model = settings.modelName.ifBlank { "deepseek-chat" }
        val baseUrl = settings.currentBaseUrl().trimEnd('/')
        val body = buildRequestBody(model, request.messages, request.capabilities, request.toolDefinitions)
        val connection = openConnection("$baseUrl/chat/completions", apiKey)
            ?: return@withContext LlmStreamResult(
                text = "Error: cannot connect to LLM endpoint.",
                toolCalls = emptyList()
            )

        try {
            connection.outputStream.use {
                it.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            if (connection.responseCode !in 200..299) {
                val errorBody = try {
                    connection.errorStream
                        ?.bufferedReader(StandardCharsets.UTF_8)
                        ?.readText()
                        ?.trim()
                        ?.take(400)
                        ?: ""
                } catch (_: Exception) {
                    ""
                }
                val message = if (errorBody.isNotBlank()) {
                    "Error: HTTP ${connection.responseCode} - $errorBody"
                } else {
                    "Error: HTTP ${connection.responseCode}"
                }
                return@withContext LlmStreamResult(text = message, toolCalls = emptyList())
            }

            val textBuffer = StringBuilder()
            val reasoningBuffer = StringBuilder()
            val toolCallAccum = mutableMapOf<Int, Triple<String, String, StringBuilder>>()
            var finishReason = ""

            BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    currentCoroutineContext().ensureActive()
                    val rawLine = line ?: continue
                    if (!rawLine.startsWith("data:")) {
                        continue
                    }
                    val data = rawLine.removePrefix("data:").trim()
                    if (data == "[DONE]" || data.isEmpty()) {
                        continue
                    }
                    val chunk = try {
                        mapper.readTree(data)
                    } catch (_: Exception) {
                        continue
                    }
                    val choice = chunk.path("choices").path(0)
                    val delta = choice.path("delta")
                    val textDelta = extractChunkTextDelta(choice, delta)
                    if (textDelta.isNotEmpty()) {
                        listener.onMessageDelta(request.messageId, textDelta)
                        textBuffer.append(textDelta)
                    }
                    val reasoningDelta = extractChunkReasoningDelta(choice, delta)
                    if (reasoningDelta.isNotEmpty()) {
                        listener.onThoughtDelta(request.messageId, reasoningDelta)
                        reasoningBuffer.append(reasoningDelta)
                    }
                    val toolCallDeltas = delta.path("tool_calls")
                    if (toolCallDeltas.isArray) {
                        for (toolCall in toolCallDeltas) {
                            val index = toolCall.path("index").asInt(0)
                            val id = toolCall.path("id").asText("")
                            val name = toolCall.path("function").path("name").asText("")
                            val argumentsChunk = toolCall.path("function").path("arguments").asText("")
                            val current = toolCallAccum[index]
                            if (current == null) {
                                toolCallAccum[index] = Triple(id, name, StringBuilder(argumentsChunk))
                            } else {
                                toolCallAccum[index] = Triple(
                                    id.ifBlank { current.first },
                                    name.ifBlank { current.second },
                                    current.third.append(argumentsChunk)
                                )
                            }
                        }
                    }
                    val reason = choice.path("finish_reason")
                    if (!reason.isNull && !reason.isMissingNode) {
                        finishReason = reason.asText("")
                    }
                }
            }

            if (reasoningBuffer.isNotEmpty()) {
                listener.onThoughtEnd(request.messageId)
            }

            val toolCalls = toolCallAccum.entries
                .sortedBy { it.key }
                .mapNotNull { (_, triple) ->
                    val (id, name, argsBuffer) = triple
                    if (name.isBlank()) {
                        return@mapNotNull null
                    }
                    val raw = argsBuffer.toString()
                    val node = try {
                        mapper.readTree(raw)
                    } catch (_: Exception) {
                        mapper.createObjectNode()
                    }
                    ToolCallInfo(id = id, name = name, argsRaw = raw, args = node)
                }

            LlmStreamResult(
                text = textBuffer.toString(),
                toolCalls = if (finishReason == "tool_calls" || toolCalls.isNotEmpty()) {
                    toolCalls
                } else {
                    emptyList()
                },
                reasoningContent = reasoningBuffer.toString()
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, apiKey: String): HttpURLConnection? = try {
        (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 180_000
        }
    } catch (_: Exception) {
        null
    }

    private fun buildRequestBody(
        model: String,
        messages: List<Map<String, Any?>>,
        caps: ModelCapabilities,
        toolDefinitions: List<Map<String, Any>>
    ): String {
        val body = linkedMapOf<String, Any?>(
            "model" to model,
            "stream" to true,
            caps.maxTokensKey to 8192,
            "temperature" to (caps.temperatureFixed ?: 0.1),
            "messages" to messages
        )
        if (caps.supportsTools && toolDefinitions.isNotEmpty()) {
            body["tools"] = toolDefinitions
        }
        return mapper.writeValueAsString(body)
    }

    private fun extractChunkTextDelta(choice: JsonNode, delta: JsonNode): String {
        textFromNode(delta.path("content")).let { if (it.isNotEmpty()) return it }
        textFromNode(delta.path("text")).let { if (it.isNotEmpty()) return it }
        textFromNode(choice.path("message").path("content")).let { if (it.isNotEmpty()) return it }
        return ""
    }

    private fun extractChunkReasoningDelta(choice: JsonNode, delta: JsonNode): String {
        textFromNode(delta.path("reasoning_content")).let { if (it.isNotEmpty()) return it }
        textFromNode(delta.path("reasoning")).let { if (it.isNotEmpty()) return it }
        textFromNode(delta.path("reasoning_delta")).let { if (it.isNotEmpty()) return it }
        textFromNode(delta.path("thinking")).let { if (it.isNotEmpty()) return it }
        textFromNode(delta.path("reasoning_text")).let { if (it.isNotEmpty()) return it }
        textFromNode(choice.path("message").path("reasoning_content")).let { if (it.isNotEmpty()) return it }
        textFromNode(choice.path("message").path("reasoning")).let { if (it.isNotEmpty()) return it }
        textFromNode(choice.path("message").path("thinking")).let { if (it.isNotEmpty()) return it }
        return ""
    }

    private fun textFromNode(node: JsonNode?): String {
        if (node == null || node.isMissingNode || node.isNull) {
            return ""
        }
        if (node.isTextual) {
            return node.asText("")
        }
        if (node.isArray) {
            val sb = StringBuilder()
            for (item in node) {
                val text = textFromNode(item)
                if (text.isNotEmpty()) {
                    sb.append(text)
                } else if (item.isObject) {
                    val candidate = textFromNode(item.path("text"))
                    if (candidate.isNotEmpty()) {
                        sb.append(candidate)
                    }
                }
            }
            return sb.toString()
        }
        if (node.isObject) {
            textFromNode(node.path("text")).let { if (it.isNotBlank()) return it }
            textFromNode(node.path("content")).let { if (it.isNotBlank()) return it }
            return ""
        }
        return node.asText("")
    }
}