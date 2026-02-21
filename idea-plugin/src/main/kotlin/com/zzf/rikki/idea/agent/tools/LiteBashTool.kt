package com.zzf.rikki.idea.agent.tools

import com.fasterxml.jackson.databind.JsonNode
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Bash tool: runs commands via ProcessBuilder, captures combined output. */
class LiteBashTool {

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val MAX_OUTPUT = 8_000
    }

    fun execute(args: JsonNode, workspaceRoot: String): String {
        val command     = args.path("command").asText("").ifBlank { throw IllegalArgumentException("command is required") }
        val workdirStr  = args.path("workdir").asText(workspaceRoot).ifBlank { workspaceRoot }
        val timeoutMs   = args.path("timeout").asLong(DEFAULT_TIMEOUT_MS).let { if (it <= 0) DEFAULT_TIMEOUT_MS else it }

        val workdir = resolveWorkdir(workspaceRoot, workdirStr)

        val pb = ProcessBuilder("bash", "-c", command)
            .directory(workdir)
            .redirectErrorStream(true)

        // Propagate current env
        pb.environment().putAll(System.getenv())

        val proc = pb.start()
        val output = proc.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

        if (!finished) {
            proc.destroyForcibly()
            return "(Command timed out after ${timeoutMs}ms)\n" + output.takeLast(MAX_OUTPUT)
        }

        val exitCode = proc.exitValue()
        val truncated = if (output.length > MAX_OUTPUT)
            output.take(MAX_OUTPUT) + "\n\n...(truncated)"
        else output

        return if (exitCode != 0) {
            "Command failed with exit code $exitCode: $command\n$truncated"
        } else {
            truncated
        }
    }

    private fun resolveWorkdir(workspaceRoot: String, requested: String): File {
        val dir = File(requested).let { if (it.isAbsolute) it else File(workspaceRoot, requested) }
        return if (dir.isDirectory) dir else File(workspaceRoot)
    }
}
