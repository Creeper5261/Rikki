package com.zzf.rikki.idea.agent.tools

import com.fasterxml.jackson.databind.JsonNode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bash tool: runs commands via ProcessBuilder, captures combined output. */
class LiteBashTool {

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val MAX_OUTPUT = 8_000
        private const val POLL_INTERVAL_MS = 200L
    }

    fun execute(args: JsonNode, workspaceRoot: String, skipFlag: AtomicBoolean? = null): String {
        val command    = args.path("command").asText("").ifBlank { throw IllegalArgumentException("command is required") }
        val workdirStr = args.path("workdir").asText(workspaceRoot).ifBlank { workspaceRoot }
        val timeoutMs  = args.path("timeout").asLong(DEFAULT_TIMEOUT_MS).let { if (it <= 0) DEFAULT_TIMEOUT_MS else it }

        val workdir = resolveWorkdir(workspaceRoot, workdirStr)

        val pb = ProcessBuilder("bash", "-c", command)
            .directory(workdir)
            .redirectErrorStream(true)

        // Propagate current env, then force UTF-8 for subprocess output
        pb.environment().putAll(System.getenv())
        pb.environment()["LANG"]             = "en_US.UTF-8"
        pb.environment()["LC_ALL"]           = "en_US.UTF-8"
        pb.environment()["PYTHONIOENCODING"] = "utf-8"

        val proc = pb.start()

        // Read output asynchronously so we can poll for skip/timeout
        val outputBuf = ByteArrayOutputStream()
        val readerThread = Thread {
            try { proc.inputStream.copyTo(outputBuf) } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }

        val deadline = System.currentTimeMillis() + timeoutMs
        var timedOut = false
        var skipped  = false

        while (proc.isAlive) {
            if (skipFlag?.get() == true) {
                skipped = true
                proc.destroyForcibly()
                break
            }
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true
                proc.destroyForcibly()
                break
            }
            proc.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }

        readerThread.join(2_000)
        val output = outputBuf.toString(Charsets.UTF_8.name())
        val truncated = if (output.length > MAX_OUTPUT) output.take(MAX_OUTPUT) + "\n\n...(truncated)" else output

        return when {
            skipped  -> "(Skipped by user - command was interrupted)\n$truncated".trimEnd()
            timedOut -> "(Command timed out after ${timeoutMs}ms)\n$truncated".trimEnd()
            proc.exitValue() != 0 -> "Command failed with exit code ${proc.exitValue()}: $command\n$truncated"
            truncated.isBlank() -> "(no output)"
            else -> truncated
        }
    }

    private fun resolveWorkdir(workspaceRoot: String, requested: String): File {
        val dir = File(requested).let { if (it.isAbsolute) it else File(workspaceRoot, requested) }
        return if (dir.isDirectory) dir else File(workspaceRoot)
    }
}
