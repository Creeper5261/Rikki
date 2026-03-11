package com.zzf.rikki.idea.agent.compat

import java.nio.file.Files
import java.nio.file.Path

class InstructionPrompt {
    private val fileNames = listOf("AGENTS.md", "CLAUDE.md", "CONTEXT.md")

    fun system(workspaceRoot: String): List<String> {
        val path = findNearestInstruction(workspaceRoot) ?: return emptyList()
        val content = runCatching { Files.readString(path) }.getOrNull()?.trim().orEmpty()
        if (content.isBlank()) {
            return emptyList()
        }
        return listOf("Instructions from: ${path.toAbsolutePath()}\n$content")
    }

    private fun findNearestInstruction(workspaceRoot: String): Path? {
        var current = runCatching {
            val base = Path.of(workspaceRoot).toAbsolutePath().normalize()
            if (Files.isDirectory(base)) base else base.parent
        }.getOrNull()
        while (current != null) {
            for (fileName in fileNames) {
                val candidate = current.resolve(fileName)
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    return candidate
                }
            }
            current = current.parent
        }
        return null
    }
}