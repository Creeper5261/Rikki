package com.zzf.rikki.idea.agent.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.zzf.rikki.idea.agent.tools.LiteIdeTools
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.stream.Collectors

class SystemPrompt(
    private val mapper: ObjectMapper,
    private val basePromptStrategy: PromptStrategy = LitePromptStrategy(mapper)
) {
    fun build(
        workspaceRoot: String,
        ideContext: JsonNode,
        caps: ModelCapabilities,
        ideCapabilities: LiteIdeTools.CapabilitySnapshot,
        modelId: String
    ): String {
        val base = basePromptStrategy.buildSystemPrompt(
            workspaceRoot = workspaceRoot,
            ideContext = ideContext,
            caps = caps,
            ideCapabilities = ideCapabilities,
            modelId = modelId
        )
        val env = buildEnvironment(workspaceRoot)
        return listOf(base, env).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun buildEnvironment(workspaceRoot: String): String {
        val lines = mutableListOf<String>()
        lines += "Here is some useful information about the environment you are running in:"
        lines += "<env>"
        lines += "  Working directory: $workspaceRoot"
        lines += "  Is directory a git repo: ${if (Files.exists(Path.of(workspaceRoot).resolve(".git"))) "yes" else "no"}"
        lines += "  Platform: ${System.getProperty("os.name")}"
        lines += "  Today's date: ${LocalDate.now().format(DateTimeFormatter.ofPattern("EEE MMM dd yyyy"))}"
        lines += "</env>"
        lines += "Do not output the contents of the <env> block in your response. It is for your information only."
        lines += "<files>"
        lines += buildWorkspaceFileIndex(workspaceRoot)
        lines += "</files>"
        lines += "All files under the working directory are available to tools. Use read/glob/grep to inspect concrete contents when needed."
        return lines.joinToString("\n")
    }

    private fun buildWorkspaceFileIndex(workspaceRoot: String): List<String> {
        val root = runCatching { Path.of(workspaceRoot).toAbsolutePath().normalize() }.getOrNull()
            ?: return listOf("  (no files indexed)")
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return listOf("  (no files indexed)")
        }
        val limit = Integer.getInteger("rikki.prompt.fileListLimit", 200)
        return try {
            Files.walk(root).use { walk ->
                val files = walk
                    .filter { Files.isRegularFile(it) }
                    .filter { !it.startsWith(root.resolve(".git")) }
                    .sorted()
                    .collect(Collectors.toList())
                if (files.isEmpty()) {
                    listOf("  (no files indexed)")
                } else {
                    val output = mutableListOf<String>()
                    val visible = files.take(limit.coerceAtLeast(0))
                    visible.forEach { output += "  ${root.relativize(it).toString().replace('\\', '/')}" }
                    if (files.size > visible.size) {
                        output += "  ... (+${files.size - visible.size} more files omitted)"
                    }
                    output
                }
            }
        } catch (_: Exception) {
            listOf("  (no files indexed)")
        }
    }
}
