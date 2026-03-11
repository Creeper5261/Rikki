package com.zzf.rikki.idea.agent.compat

import com.zzf.rikki.idea.agent.tools.LiteIdeTools
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptTextLoaderTest {
    @Test
    fun loadSessionPrompt_should_use_backend_resource_files() {
        val prompt = PromptTextLoader.loadSessionPrompt("gpt-5")

        assertTrue(prompt.contains("You are Rikki"))
        assertTrue(prompt.contains("interactive CLI tool"))
    }

    @Test
    fun loadToolDescription_should_use_backend_prompt_resource() {
        val description = PromptTextLoader.loadToolDescription("bash", "D:/Projects/Rikki")

        assertTrue(description.contains("persistent shell session"))
        assertTrue(description.contains("D:/Projects/Rikki"))
    }

    @Test
    fun backendToolDefinitions_should_expose_backend_descriptions_for_supported_tools() {
        val defs = BackendToolDefinitions.build(
            workspaceRoot = "D:/Projects/Rikki",
            snapshot = LiteIdeTools.CapabilitySnapshot(true, listOf("run", "test", "status", "cancel", "capabilities"))
        )

        val bash = defs.first { (it["function"] as Map<*, *>) ["name"] == "bash" }
        val read = defs.first { (it["function"] as Map<*, *>) ["name"] == "read" }
        val bashFunction = bash["function"] as Map<*, *>
        val readFunction = read["function"] as Map<*, *>

        assertTrue((bashFunction["description"] as String).contains("persistent shell session"))
        assertTrue((readFunction["description"] as String).contains("Reads a file from the local filesystem"))
        assertEquals("read", readFunction["name"])
    }
}