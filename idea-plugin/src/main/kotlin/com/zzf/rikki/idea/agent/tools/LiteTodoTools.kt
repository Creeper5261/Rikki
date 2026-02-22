package com.zzf.rikki.idea.agent.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Simple file-backed todo storage per workspace root. */
class LiteTodoTools(private val mapper: ObjectMapper) {

    data class TodoItem(
        val id: String,
        val content: String,
        val status: String,
        val priority: String
    )

    private fun todoFile(workspaceRoot: String): File =
        File(workspaceRoot, ".rikki/todos.json")

    fun read(workspaceRoot: String): String {
        val file = todoFile(workspaceRoot)
        if (!file.exists()) return "No todos found."
        return try {
            val todos = mapper.readValue(file, List::class.java)
            if ((todos as List<*>).isEmpty()) "No todos found."
            else mapper.writerWithDefaultPrettyPrinter().writeValueAsString(todos)
        } catch (_: Exception) {
            "No todos found."
        }
    }

    fun write(args: JsonNode, workspaceRoot: String): String {
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

        val file = todoFile(workspaceRoot)
        file.parentFile?.mkdirs()
        file.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(todos), StandardCharsets.UTF_8)
        return "Todos updated: ${todos.size} item(s)"
    }
}
