package com.zzf.rikki.idea.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.zzf.rikki.idea.settings.RikkiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Runs the agentic LLM loop and emits SSE events through [LiteSseWriter]. */
class LiteAgentEngine(
    private val project: Project,
    private val mapper: ObjectMapper
) {
    companion object {
        private const val MAX_STEPS = 120
        private const val MAX_OUTPUT = 8_000
    }

    private val tools = LiteToolRegistry(project, mapper)

    fun setSkipFlag(flag: AtomicBoolean) { tools.setSkipFlag(flag) }

    private var confirmFutureRef: AtomicReference<CompletableFuture<Boolean>?>? = null
    fun setConfirmFutureRef(ref: AtomicReference<CompletableFuture<Boolean>?>) { confirmFutureRef = ref }

    suspend fun run(
        goal: String,
        workspaceRoot: String,
        ideContext: JsonNode,
        history: JsonNode,
        settings: JsonNode,
        sessionId: String,
        sseWriter: LiteSseWriter
    ) {
        val sid = sessionId.ifBlank { UUID.randomUUID().toString() }
        sseWriter.emitSession(sid)
        sseWriter.emitStatus("busy", "Agent is thinking...")

        // Make IDE context available to ide_context tool
        tools.ide.ideContextNode = ideContext

        // Build message list
        val messages = mutableListOf<Map<String, Any?>>()
        messages += mapOf("role" to "system", "content" to buildSystemPrompt(workspaceRoot, ideContext))

        // Replay history lines
        if (history.isArray) {
            for (line in history) {
                val text = line.asText("").trim()
                if (text.isBlank()) continue
                when {
                    text.startsWith("You:") ->
                        messages += mapOf("role" to "user", "content" to text.removePrefix("You:").trim())
                    text.startsWith("Assistant:") ->
                        messages += mapOf("role" to "assistant", "content" to text.removePrefix("Assistant:").trim())
                }
            }
        }
        messages += mapOf("role" to "user", "content" to goal)

        var textAnswer = ""
        var msgIdx = 0

        for (step in 0 until MAX_STEPS) {
            val msgId = "msg_$msgIdx"

            val result = callLlmStreaming(messages, sseWriter, msgId)
            textAnswer = result.text

            // Add assistant turn to history
            val assistantMsg = mutableMapOf<String, Any?>("role" to "assistant")
            assistantMsg["content"] = result.text.ifBlank { null }
            if (result.toolCalls.isNotEmpty()) {
                assistantMsg["tool_calls"] = result.toolCalls.map { tc ->
                    mapOf(
                        "id" to tc.id, "type" to "function",
                        "function" to mapOf("name" to tc.name, "arguments" to tc.argsRaw)
                    )
                }
            }
            messages += assistantMsg

            if (result.toolCalls.isEmpty()) break

            // Execute each tool
            for ((idx, tc) in result.toolCalls.withIndex()) {
                val partId = "part_${msgIdx}_$idx"
                val argsMap = try {
                    @Suppress("UNCHECKED_CAST")
                    mapper.convertValue(tc.args, Map::class.java) as Map<String, Any?>
                } catch (_: Exception) { emptyMap() }

                // Emit the tool call as "pending" first (creates the UI row)
                sseWriter.emitToolCall(partId, tc.name, tc.id, msgId, "pending", tc.name, argsMap)

                // High-risk inline confirmation
                if (tools.isHighRisk(tc.name, tc.args)) {
                    val command = if (tc.name == "bash")
                        tc.args.path("command").asText("(unknown)")
                    else
                        "Delete: ${tc.args.path("filePath").asText("(unknown)")}"

                    sseWriter.emitToolConfirm(partId, tc.id, command, tc.name)
                    sseWriter.emitStatus("waiting_approval", "Awaiting your approval...")

                    val future = CompletableFuture<Boolean>()
                    confirmFutureRef?.set(future)
                    val approved = try {
                        withContext(Dispatchers.IO) { future.get(120L, TimeUnit.SECONDS) }
                    } catch (_: Exception) { false }
                    confirmFutureRef?.set(null)

                    sseWriter.emitStatus("busy", "Agent is thinking...")

                    if (!approved) {
                        sseWriter.emitToolResult(
                            partId, tc.name, tc.id, msgId, "rejected", tc.name,
                            "(User rejected this command.)"
                        )
                        messages += mapOf(
                            "role" to "tool", "tool_call_id" to tc.id,
                            "content" to "(User rejected this command. Do not retry.)"
                        )
                        continue
                    }
                }

                // Update to "running" state and execute
                sseWriter.emitToolCall(partId, tc.name, tc.id, msgId, "running", tc.name, argsMap)

                val toolResult = tools.execute(tc.name, tc.args, workspaceRoot, sid, tc.id)

                // Build meta for file-change tools: carry old/new content + workspaceApplied flag
                val meta: Map<String, Any?>? = if (toolResult.pendingChangePath != null) {
                    mapOf(
                        "workspaceApplied" to true,
                        "pending_change" to mapOf(
                            "path"         to toolResult.pendingChangePath,
                            "type"         to toolResult.pendingChangeType,
                            "oldContent"   to toolResult.pendingChangeOld,
                            "newContent"   to toolResult.pendingChangeNew,
                            "workspaceRoot" to workspaceRoot,
                            "sessionId"    to sid
                        )
                    )
                } else null

                if (toolResult.error != null) {
                    sseWriter.emitToolResult(partId, tc.name, tc.id, msgId, "error", tc.name, "", toolResult.error)
                    messages += mapOf("role" to "tool", "tool_call_id" to tc.id, "content" to "Error: ${toolResult.error}")
                } else {
                    val out = toolResult.output.take(MAX_OUTPUT)
                    sseWriter.emitToolResult(partId, tc.name, tc.id, msgId, "completed", tc.name, out, meta = meta)
                    messages += mapOf("role" to "tool", "tool_call_id" to tc.id, "content" to out)
                }

                // Emit todo_updated after successful todo_write
                if (tc.name == "todo_write" && toolResult.error == null) {
                    val todosFile = File(workspaceRoot, ".rikki/todos.json")
                    if (todosFile.exists()) sseWriter.emitTodoUpdated(todosFile.readText())
                }
            }
            msgIdx++
        }

        sseWriter.emitFinish(sid, "msg_$msgIdx", textAnswer)
        sseWriter.emitStatus("idle", "Ready")
    }

    // ── LLM streaming ────────────────────────────────────────────────────────

    data class ToolCallInfo(val id: String, val name: String, val argsRaw: String, val args: JsonNode)
    data class LlmResult(val text: String, val toolCalls: List<ToolCallInfo>)

    private suspend fun callLlmStreaming(
        messages: List<Map<String, Any?>>,
        sseWriter: LiteSseWriter,
        msgId: String
    ): LlmResult = withContext(Dispatchers.IO) {
        val s = RikkiSettings.getInstance().state
        if (s.apiKey.isBlank()) return@withContext LlmResult("Error: API key not configured.", emptyList())

        val model   = s.modelName.ifBlank { "deepseek-chat" }
        val baseUrl = s.baseUrl.trimEnd('/')

        val body = buildRequestBody(model, messages)
        val conn = openConnection("$baseUrl/chat/completions", s.apiKey)
            ?: return@withContext LlmResult("Error: cannot connect to LLM endpoint.", emptyList())

        try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                return@withContext LlmResult("Error: HTTP ${conn.responseCode}", emptyList())
            }

            val textBuf = StringBuilder()
            // Accumulate streaming tool calls: index → (id, name, args)
            val tcAccum = mutableMapOf<Int, Triple<String, String, StringBuilder>>()
            var finishReason = ""

            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    currentCoroutineContext().ensureActive()
                    val l = line ?: continue
                    if (!l.startsWith("data:")) continue
                    val data = l.removePrefix("data:").trim()
                    if (data == "[DONE]" || data.isEmpty()) continue
                    val chunk = try { mapper.readTree(data) } catch (_: Exception) { continue }

                    val choice = chunk.path("choices").path(0)
                    val delta  = choice.path("delta")

                    // Text delta
                    val content = delta.path("content")
                    if (!content.isNull && !content.isMissingNode) {
                        val text = content.asText("")
                        if (text.isNotEmpty()) {
                            sseWriter.emitMessage(msgId, text)
                            textBuf.append(text)
                        }
                    }

                    // Tool call deltas
                    val tcDeltas = delta.path("tool_calls")
                    if (tcDeltas.isArray) {
                        for (tc in tcDeltas) {
                            val idx      = tc.path("index").asInt(0)
                            val id       = tc.path("id").asText("")
                            val name     = tc.path("function").path("name").asText("")
                            val argChunk = tc.path("function").path("arguments").asText("")
                            val cur      = tcAccum[idx]
                            if (cur == null) {
                                tcAccum[idx] = Triple(id, name, StringBuilder(argChunk))
                            } else {
                                tcAccum[idx] = Triple(
                                    id.ifBlank { cur.first },
                                    name.ifBlank { cur.second },
                                    cur.third.append(argChunk)
                                )
                            }
                        }
                    }

                    val fr = choice.path("finish_reason")
                    if (!fr.isNull && !fr.isMissingNode) finishReason = fr.asText("")
                }
            }

            val toolCalls = tcAccum.entries.sortedBy { it.key }.mapNotNull { (_, triple) ->
                val (id, name, argsBuf) = triple
                if (name.isBlank()) return@mapNotNull null
                val raw  = argsBuf.toString()
                val node = try { mapper.readTree(raw) } catch (_: Exception) { mapper.createObjectNode() }
                ToolCallInfo(id, name, raw, node)
            }

            LlmResult(
                textBuf.toString(),
                if (finishReason == "tool_calls" || toolCalls.isNotEmpty()) toolCalls else emptyList()
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(url: String, apiKey: String): HttpURLConnection? = try {
        (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
            connectTimeout = 10_000
            readTimeout    = 180_000
        }
    } catch (_: Exception) { null }

    private fun buildRequestBody(model: String, messages: List<Map<String, Any?>>): String =
        mapper.writeValueAsString(
            mapOf(
                "model"       to model,
                "stream"      to true,
                "max_tokens"  to 8192,
                "temperature" to 0.1,
                "tools"       to LiteToolRegistry.toolDefinitions(),
                "messages"    to messages
            )
        )

    // ── System prompt ─────────────────────────────────────────────────────────

    private fun buildSystemPrompt(workspaceRoot: String, ideContext: JsonNode): String {
        val sb = StringBuilder(SYSTEM_PROMPT)
        sb.append("\n\nWorking directory: $workspaceRoot")
        if (!ideContext.isNull && !ideContext.isMissingNode && ideContext.size() > 0) {
            sb.append("\n\n<ide_context>\n")
            sb.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ideContext))
            sb.append("\n</ide_context>")
        }
        return sb.toString()
    }

    private val SYSTEM_PROMPT = """
You are Rikki, a powerful AI coding agent.

You are an interactive assistant that helps users with software engineering tasks. Use the tools available to you to assist the user.

## Editing constraints
- Default to ASCII when editing or creating files. Only introduce non-ASCII characters when there is a clear justification.
- Only add comments if they are necessary to make a non-obvious block easier to understand.

## Tool usage
- Prefer specialized tools over shell for file operations:
  - Use read to view files, edit to modify files, write only when creating new files.
  - Use glob to find files by name and grep to search file contents.
- Use bash for terminal operations (git, builds, tests, scripts).
- Run tool calls in parallel when outputs are independent; otherwise run sequentially.

## Workflow
- Default to doing the work without asking questions.
- Only ask when truly blocked after checking relevant context.
- Be concise and friendly.
    """.trimIndent()
}
