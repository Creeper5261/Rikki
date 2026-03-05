package com.zzf.rikki.idea.agent.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Simple file-backed todo storage per workspace + session. */
class LiteTodoTools(private val mapper: ObjectMapper) {

    data class TodoItem(
        val id: String,
        val content: String,
        val status: String,
        val priority: String
    )

    private fun legacyTodoFile(workspaceRoot: String): File =
        File(workspaceRoot, ".rikki/todos.json")

    private fun sessionTodoFile(workspaceRoot: String, sessionId: String): File? {
        val normalized = sessionId.trim()
        if (normalized.isBlank()) return null
        return File(workspaceRoot, ".rikki/todos/${safeSessionId(normalized)}.json")
    }

    private fun todoFile(workspaceRoot: String, sessionId: String): File =
        sessionTodoFile(workspaceRoot, sessionId) ?: legacyTodoFile(workspaceRoot)

    private fun safeSessionId(sessionId: String): String =
        sessionId.map { ch ->
            when {
                ch.isLetterOrDigit() -> ch
                ch == '-' || ch == '_' || ch == '.' -> ch
                else -> '_'
            }
        }.joinToString("")

    fun read(workspaceRoot: String, sessionId: String): String {
        val file = todoFile(workspaceRoot, sessionId)
        if (!file.exists()) return "No todos found."
        return try {
            val todos = mapper.readValue(file, List::class.java)
            if ((todos as List<*>).isEmpty()) "No todos found."
            else mapper.writerWithDefaultPrettyPrinter().writeValueAsString(todos)
        } catch (_: Exception) {
            "No todos found."
        }
    }

    fun write(args: JsonNode, workspaceRoot: String, sessionId: String): String {
        val todosNode = args.path("todos")
        if (!todosNode.isArray) throw IllegalArgumentException("todos must be an array")

        val todos = buildList {
            for (item in todosNode) {
                // Accept 'content' (canonical) or 'title'/'description' as fallbacks
                val content = item.path("content").asText("").ifBlank {
                    item.path("title").asText("").ifBlank { item.path("description").asText("") }
                }
                add(
                    TodoItem(
                        id       = item.path("id").asText("").ifBlank { UUID.randomUUID().toString() },
                        content  = content,
                        status   = item.path("status").asText("pending"),
                        priority = item.path("priority").asText("medium")
                    )
                )
            }
        }

        val file = todoFile(workspaceRoot, sessionId)
        file.parentFile?.mkdirs()
        file.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(todos), StandardCharsets.UTF_8)
        return "Todos updated: ${todos.size} item(s)"
    }

    fun readJson(workspaceRoot: String, sessionId: String): String? {
        val file = todoFile(workspaceRoot, sessionId)
        if (!file.exists()) return null
        val text = try { file.readText(StandardCharsets.UTF_8) } catch (_: Exception) { return null }
        if (text.isBlank()) return null
        return text
    }
}
