package com.zzf.rikki.idea.agent.compat

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SessionServiceTest {
    private val mapper = ObjectMapper()

    @Test
    fun toLlmMessages_should_include_assistant_tool_calls_and_tool_results() {
        val service = SessionService(mapper)
        val session = service.getOrCreate("session-1", "D:/Projects/Rikki")
        service.addUserMessage(session.id, "run tests")
        val assistant = service.startAssistantMessage(session.id, "OPENAI", "gpt-4o")
        service.updatePart(
            ToolPart(
                id = "part-tool",
                sessionID = session.id,
                messageID = assistant.info.id,
                callID = "call-1",
                tool = "bash",
                args = linkedMapOf("command" to "npm test"),
                state = ToolState(
                    status = "completed",
                    input = linkedMapOf("command" to "npm test"),
                    output = "tests passed",
                    title = "bash",
                    time = ToolStateTimeInfo(start = 1L, end = 2L)
                )
            )
        )
        assistant.info.finish = true
        assistant.info.finishReason = "tool-calls"
        service.updateMessage(assistant)

        val messages = service.toLlmMessages(session.id, "system prompt", "system")

        assertEquals("system", messages[0]["role"])
        assertEquals("user", messages[1]["role"])
        assertEquals("assistant", messages[2]["role"])
        assertEquals("tool", messages[3]["role"])
        @Suppress("UNCHECKED_CAST")
        val toolCalls = messages[2]["tool_calls"] as List<Map<String, Any?>>
        assertEquals("bash", (toolCalls.first()["function"] as Map<*, *>)["name"])
        assertEquals("tests passed", messages[3]["content"])
    }

    @Test
    fun instructionPrompt_should_load_workspace_agents_file() {
        val workspace = Files.createTempDirectory("rikki-instructions")
        Files.writeString(workspace.resolve("AGENTS.md"), "# Project rules\nUse tests.")

        val instructions = InstructionPrompt().system(workspace.toString())

        assertEquals(1, instructions.size)
        assertTrue(instructions.first().contains("AGENTS.md"))
        assertTrue(instructions.first().contains("Use tests."))
    }
}
