package com.zzf.rikki.idea.agent

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

/** Writes SSE events in the format ChatPanel/ChatSseAdapter expects. */
class LiteSseWriter(outputStream: OutputStream) {

    private val writer = PrintWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
    private val mapper = ObjectMapper()

    @Synchronized
    fun emit(event: String, data: Any) {
        val json = mapper.writeValueAsString(data)
        writer.print("event: $event\n")
        writer.print("data: $json\n")
        writer.print("\n")
        writer.flush()
    }

    fun emitSession(sessionId: String) =
        emit("session", mapOf("sessionID" to sessionId, "reused" to false))

    fun emitStatus(type: String, message: String) =
        emit("status", mapOf("type" to type, "message" to message))

    fun emitMessage(msgId: String, delta: String) =
        emit("message", mapOf("id" to msgId, "delta" to delta))

    fun emitToolCall(
        partId: String, tool: String, callId: String, msgId: String,
        state: String, title: String, args: Any
    ) = emit(
        "tool_call", mapOf(
            "id" to partId, "partID" to partId, "partId" to partId,
            "tool" to tool, "callID" to callId,
            "messageID" to msgId, "messageId" to msgId,
            "state" to state, "title" to title, "args" to args
        )
    )

    fun emitToolResult(
        partId: String, tool: String, callId: String, msgId: String,
        state: String, title: String, output: String, error: String? = null,
        meta: Map<String, Any?>? = null
    ) {
        val data = mutableMapOf<String, Any?>(
            "id" to partId, "partID" to partId, "partId" to partId,
            "tool" to tool, "callID" to callId,
            "messageID" to msgId, "messageId" to msgId,
            "state" to state, "title" to title,
            "output" to output
        )
        if (error != null) data["error"] = error
        if (meta != null) data["meta"] = meta
        emit("tool_result", data)
    }

    /**
     * Asks the frontend to show inline approve/skip buttons for a high-risk tool.
     * The engine suspends until the user responds via /api/agent/confirm.
     */
    fun emitToolConfirm(partId: String, callId: String, command: String, tool: String) =
        emit(
            "tool_confirm", mapOf(
                "id" to partId, "partID" to partId, "partId" to partId,
                "callID" to callId, "callId" to callId,
                "tool" to tool, "command" to command
            )
        )

    /** Pushes a todo list update so ChatPanel's TodoPanel refreshes in real time. */
    fun emitTodoUpdated(todosJson: String) {
        try {
            val todosNode = mapper.readTree(todosJson)
            emit("todo_updated", mapOf("todos" to todosNode))
        } catch (_: Exception) {}
    }

    fun emitFinish(sessionId: String, msgId: String, answer: String) =
        emit(
            "finish", mapOf(
                "answer" to answer,
                "traceId" to sessionId,
                "sessionID" to sessionId,
                "messageID" to msgId,
                "meta" to mapOf("pendingChanges" to emptyList<Any>())
            )
        )
}
