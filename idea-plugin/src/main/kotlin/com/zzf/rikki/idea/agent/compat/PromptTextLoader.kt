package com.zzf.rikki.idea.agent.compat

import java.nio.charset.StandardCharsets

object PromptTextLoader {
    private const val DEFAULT_BASH_MAX_LINES = 200
    private const val DEFAULT_BASH_MAX_BYTES = 8000

    fun load(path: String): String {
        val normalized = path.removePrefix("/")
        val stream = javaClass.classLoader.getResourceAsStream(normalized)
            ?: return ""
        return stream.use { input ->
            input.readBytes().toString(StandardCharsets.UTF_8).trim()
        }
    }

    fun has(path: String): Boolean = javaClass.classLoader.getResource(path.removePrefix("/")) != null

    fun loadSessionPrompt(modelId: String): String {
        val lower = modelId.trim().lowercase()
        val normalized = lower.replace(':', '-').replace('/', '-')
        val exact = if (normalized.isNotBlank()) "prompts/session/$normalized.txt" else ""
        if (exact.isNotBlank() && has(exact)) {
            return load(exact)
        }
        val file = when {
            lower.contains("gpt-5") -> "codex_header.txt"
            lower.contains("gpt-") || lower.contains("o1") || lower.contains("o3") || lower.contains("o4") -> "beast.txt"
            lower.contains("gemini-") -> "gemini.txt"
            lower.contains("claude") || lower.contains("anthropic") -> "anthropic.txt"
            else -> "qwen.txt"
        }
        return load("prompts/session/$file")
    }

    fun loadToolDescription(toolId: String, workspaceRoot: String): String {
        val resourcePath = when (toolId) {
            "bash" -> "prompts/tool/bash.txt"
            "read" -> "prompts/tool/read.txt"
            "write" -> "prompts/tool/write.txt"
            "edit" -> "prompts/tool/edit.txt"
            "glob" -> "prompts/tool/glob.txt"
            "grep" -> "prompts/tool/grep.txt"
            "ls" -> "prompts/tool/ls.txt"
            "todo_read" -> "prompts/tool/todoread.txt"
            "todo_write" -> "prompts/tool/todowrite.txt"
            "task" -> "prompts/tool/task.txt"
            "web_search" -> "prompts/tool/websearch.txt"
            "search_codebase" -> "prompts/tool/codesearch.txt"
            else -> null
        }
        val raw = resourcePath?.let(::load).orEmpty()
        if (raw.isBlank()) {
            return fallbackDescription(toolId)
        }
        return raw
            .replace("\${directory}", workspaceRoot)
            .replace("\${maxLines}", DEFAULT_BASH_MAX_LINES.toString())
            .replace("\${maxBytes}", DEFAULT_BASH_MAX_BYTES.toString())
            .trim()
    }

    private fun fallbackDescription(toolId: String): String = when (toolId) {
        "delete_file" -> "Delete a file from the workspace."
        "ide_context" -> "Read IDE project/build environment context on demand. Use this when you need SDK, module, or build-system facts."
        "ide_action" -> "Unified IDE-native action tool with async jobs. Supports build/run/test start, status query, cancel, and capability query."
        "ide_capabilities" -> "Fetch available IDE-native bridge capabilities (supported operations, run configurations, async job support)."
        else -> toolId
    }
}
