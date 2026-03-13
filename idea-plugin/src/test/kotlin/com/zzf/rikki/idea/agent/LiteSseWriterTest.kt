package com.zzf.rikki.idea

import com.fasterxml.jackson.databind.ObjectMapper
import com.zzf.rikki.idea.ChatSseAdapter
import com.zzf.rikki.idea.agent.LiteSseWriter
import com.zzf.rikki.idea.agent.compat.RuntimeEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class LiteSseWriterTest {
    private val mapper = ObjectMapper()

    @Test
    fun emit_should_preserve_lite_sse_contract_and_pending_command_meta() {
        val output = ByteArrayOutputStream()
        val writer = LiteSseWriter(output)

        writer.emit(RuntimeEvent.SessionBound("session-1"))
        writer.emit(RuntimeEvent.StatusChanged("busy", "Agent is thinking..."))
        writer.emit(RuntimeEvent.MessageDelta("msg-1", "hello"))
        writer.emit(RuntimeEvent.MessageSnapshot("msg-1", "hello world"))
        writer.emit(RuntimeEvent.ThoughtDelta("msg-1", "reasoning"))
        writer.emit(RuntimeEvent.ThoughtEnd("msg-1"))
        writer.emit(
            RuntimeEvent.ToolCall(
                "part-1",
                "bash",
                "call-1",
                "msg-1",
                "pending",
                "bash",
                mapOf("command" to "npm test"),
                mapOf(
                    "pending_command" to mapOf("id" to "pc-1", "command" to "npm test"),
                    "approval_options" to listOf("manual", "whitelist"),
                    "requires_explicit_user_consent" to true
                )
            )
        )
        writer.emit(RuntimeEvent.ToolPendingApproval("part-1", "call-1", "npm test", "bash"))
        writer.emit(
            RuntimeEvent.ToolResult(
                "part-1",
                "bash",
                "call-1",
                "msg-1",
                "completed",
                "bash",
                "ok",
                null,
                null
            )
        )
        writer.emit(RuntimeEvent.TodoUpdated("[{\"content\":\"task\",\"status\":\"pending\"}]", "session-1"))
        writer.emit(RuntimeEvent.Finished("session-1", "msg-1", "done"))
        writer.emit(RuntimeEvent.Errored("boom"))

        val captured = mutableListOf<Pair<String, String>>()
        ChatSseAdapter().consume(output.toString(StandardCharsets.UTF_8).lines()) { event, data ->
            captured += event to data
        }

        assertEquals(
            listOf(
                "session",
                "status",
                "message",
                "message_part",
                "thought",
                "thought_end",
                "tool_call",
                "tool_confirm",
                "tool_result",
                "todo_updated",
                "finish",
                "error"
            ),
            captured.map { it.first }
        )

        val toolCall = mapper.readTree(captured[6].second)
        assertEquals("pc-1", toolCall.path("meta").path("pending_command").path("id").asText())
        assertEquals(true, toolCall.path("meta").path("requires_explicit_user_consent").asBoolean())

        val finish = mapper.readTree(captured[10].second)
        assertEquals("session-1", finish.path("sessionID").asText())
        assertEquals("done", finish.path("answer").asText())
    }
}
