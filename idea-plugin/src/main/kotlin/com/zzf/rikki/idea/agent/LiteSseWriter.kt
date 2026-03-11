package com.zzf.rikki.idea.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.zzf.rikki.idea.agent.compat.AgentEventSink
import com.zzf.rikki.idea.agent.compat.RuntimeEvent
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

/** Writes SSE events in the format ChatPanel/ChatSseAdapter expects. */
class LiteSseWriter(outputStream: OutputStream) : AgentEventSink {
    private val writer = PrintWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
    private val mapper = ObjectMapper()

    @Synchronized
    fun emit(event: String, data: Any) {
        writer.print("event: $event\n")
        if (data is String) {
            writer.print("data: $data\n")
        } else {
            val json = mapper.writeValueAsString(data)
            writer.print("data: $json\n")
        }
        writer.print("\n")
        writer.flush()
    }

    override fun emit(event: RuntimeEvent) {
        when (event) {
            is RuntimeEvent.SessionBound -> emitSession(event.sessionId, event.reused)
            is RuntimeEvent.StatusChanged -> emitStatus(event.type, event.message)
            is RuntimeEvent.MessageDelta -> emitMessage(event.messageId, event.delta)
            is RuntimeEvent.MessageSnapshot -> emit("message_part", mapOf("messageID" to event.messageId, "text" to event.text))
            is RuntimeEvent.ThoughtDelta -> emitThought(event.messageId, event.delta)
            is RuntimeEvent.ThoughtEnd -> emitThoughtEnd(event.messageId)
            is RuntimeEvent.ToolCall -> emitToolCall(
                partId = event.partId,
                tool = event.tool,
                callId = event.callId,
                msgId = event.messageId,
                state = event.state,
                title = event.title,
                args = event.args,
                meta = event.meta
            )
            is RuntimeEvent.ToolPendingApproval -> emitToolConfirm(
                partId = event.partId,
                callId = event.callId,
                command = event.command,
                tool = event.tool
            )
            is RuntimeEvent.ToolResult -> emitToolResult(
                partId = event.partId,
                tool = event.tool,
                callId = event.callId,
                msgId = event.messageId,
                state = event.state,
                title = event.title,
                output = event.output,
                error = event.error,
                meta = event.meta
            )
            is RuntimeEvent.TodoUpdated -> emitTodoUpdated(event.todosJson, event.sessionId)
            is RuntimeEvent.Finished -> emitFinish(event.sessionId, event.messageId, event.answer)
            is RuntimeEvent.Errored -> emit("error", event.message)
        }
    }

    fun emitSession(sessionId: String, reused: Boolean = false) =
        emit("session", mapOf("sessionID" to sessionId, "reused" to reused))

    fun emitStatus(type: String, message: String) =
        emit("status", mapOf("type" to type, "message" to message))

    fun emitMessage(msgId: String, delta: String) =
        emit("message", mapOf("id" to msgId, "delta" to delta))

    fun emitThought(msgId: String, delta: String) =
        emit("thought", mapOf("messageID" to msgId, "reasoning_delta" to delta))

    fun emitThoughtEnd(msgId: String) =
        emit("thought_end", mapOf("messageID" to msgId))

    fun emitToolCall(
        partId: String,
        tool: String,
        callId: String,
        msgId: String,
        state: String,
        title: String,
        args: Any,
        meta: Map<String, Any?>? = null
    ) {
        val payload = linkedMapOf<String, Any?>(
            "id" to partId,
            "partID" to partId,
            "partId" to partId,
            "tool" to tool,
            "callID" to callId,
            "messageID" to msgId,
            "messageId" to msgId,
            "state" to state,
            "title" to title,
            "args" to args
        )
        if (meta != null) {
            payload["meta"] = meta
        }
        emit("tool_call", payload)
    }

    fun emitToolResult(
        partId: String,
        tool: String,
        callId: String,
        msgId: String,
        state: String,
        title: String,
        output: String,
        error: String? = null,
        meta: Map<String, Any?>? = null
    ) {
        val data = linkedMapOf<String, Any?>(
            "id" to partId,
            "partID" to partId,
            "partId" to partId,
            "tool" to tool,
            "callID" to callId,
            "messageID" to msgId,
            "messageId" to msgId,
            "state" to state,
            "title" to title,
            "output" to output
        )
        if (error != null) data["error"] = error
        if (meta != null) data["meta"] = meta
        emit("tool_result", data)
    }

    fun emitToolConfirm(partId: String, callId: String, command: String, tool: String) =
        emit(
            "tool_confirm", mapOf(
                "id" to partId,
                "partID" to partId,
                "partId" to partId,
                "callID" to callId,
                "callId" to callId,
                "tool" to tool,
                "command" to command
            )
        )

    fun emitTodoUpdated(todosJson: String, sessionId: String? = null) {
        try {
            val todosNode = mapper.readTree(todosJson)
            val payload = mutableMapOf<String, Any>("todos" to todosNode)
            if (!sessionId.isNullOrBlank()) {
                payload["sessionID"] = sessionId
            }
            emit("todo_updated", payload)
        } catch (_: Exception) {
        }
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