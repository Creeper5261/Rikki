package com.zzf.rikki.idea.agent.tools

import com.fasterxml.jackson.databind.JsonNode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Shell tool: runs commands with bash-first auto-fallback, captures combined output. */
class LiteBashTool {

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val MAX_OUTPUT = 8_000
        private const val POLL_INTERVAL_MS = 200L
        private val SUPPORTED_SHELLS = setOf("auto", "bash", "powershell", "cmd")
    }

    private data class ShellSpec(val name: String, val argvPrefix: List<String>)

    private data class ExecutionResult(
        val output: String,
        val exitCode: Int,
        val timedOut: Boolean,
        val skipped: Boolean
    )

    fun execute(args: JsonNode, workspaceRoot: String, skipFlag: AtomicBoolean? = null): String {
        val command    = args.path("command").asText("").ifBlank { throw IllegalArgumentException("command is required") }
        val workdirStr = args.path("workdir").asText(workspaceRoot).ifBlank { workspaceRoot }
        val timeoutMs  = args.path("timeout").asLong(DEFAULT_TIMEOUT_MS).let { if (it <= 0) DEFAULT_TIMEOUT_MS else it }
        val shellPref  = args.path("shell").asText("auto").trim().lowercase().ifBlank { "auto" }
        if (shellPref !in SUPPORTED_SHELLS) {
            throw IllegalArgumentException("shell must be one of: auto, bash, powershell, cmd")
        }

        val workdir = resolveWorkdir(workspaceRoot, workdirStr)
        val candidates = resolveShellCandidates(shellPref)
        val startupErrors = mutableListOf<String>()

        for (shell in candidates) {
            val pb = ProcessBuilder(shell.argvPrefix + command)
                .directory(workdir)
                .redirectErrorStream(true)

            // Propagate current env, then force UTF-8 for subprocess output
            pb.environment().putAll(System.getenv())
            pb.environment()["PYTHONIOENCODING"] = "utf-8"
            if (shell.name == "bash") {
                pb.environment()["LANG"] = "en_US.UTF-8"
                pb.environment()["LC_ALL"] = "en_US.UTF-8"
            }

            val proc = try {
                pb.start()
            } catch (e: Exception) {
                startupErrors += "${shell.name}: ${e.message ?: "failed to start"}"
                continue
            }

            val result = runProcess(proc, timeoutMs, skipFlag)
            return formatResult(shell.name, command, timeoutMs, result)
        }

        val shellNames = candidates.joinToString(", ") { it.name }
        val err = if (startupErrors.isEmpty()) "no startup error details" else startupErrors.joinToString("; ")
        return "No usable shell found (requested=$shellPref, tried=$shellNames): $err"
    }

    private fun resolveWorkdir(workspaceRoot: String, requested: String): File {
        val dir = File(requested).let { if (it.isAbsolute) it else File(workspaceRoot, requested) }
        return if (dir.isDirectory) dir else File(workspaceRoot)
    }

    private fun resolveShellCandidates(shellPref: String): List<ShellSpec> = when (shellPref) {
        "bash"       -> listOf(ShellSpec("bash", listOf("bash", "-c")))
        "powershell" -> listOf(ShellSpec("powershell", listOf("powershell", "-NoProfile", "-NonInteractive", "-Command")))
        "cmd"        -> listOf(ShellSpec("cmd", listOf("cmd", "/c")))
        else         -> listOf(
            ShellSpec("bash", listOf("bash", "-c")),
            ShellSpec("powershell", listOf("powershell", "-NoProfile", "-NonInteractive", "-Command")),
            ShellSpec("cmd", listOf("cmd", "/c"))
        )
    }

    private fun runProcess(proc: Process, timeoutMs: Long, skipFlag: AtomicBoolean?): ExecutionResult {
        val outputBuf = ByteArrayOutputStream()
        val readerThread = Thread {
            try { proc.inputStream.copyTo(outputBuf) } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }

        val deadline = System.currentTimeMillis() + timeoutMs
        var timedOut = false
        var skipped = false

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
        val exitCode = try { proc.exitValue() } catch (_: Exception) { -1 }
        return ExecutionResult(output, exitCode, timedOut, skipped)
    }

    private fun formatResult(shell: String, command: String, timeoutMs: Long, result: ExecutionResult): String {
        val prefix = "[shell=$shell]"
        val truncated = if (result.output.length > MAX_OUTPUT) {
            result.output.take(MAX_OUTPUT) + "\n\n...(truncated)"
        } else {
            result.output
        }

        return when {
            result.skipped ->
                "$prefix (Skipped by user - command was interrupted)\n$truncated".trimEnd()
            result.timedOut ->
                "$prefix (Command timed out after ${timeoutMs}ms)\n$truncated".trimEnd()
            result.exitCode != 0 ->
                "$prefix Command failed with exit code ${result.exitCode}: $command\n$truncated".trimEnd()
            truncated.isBlank() ->
                "$prefix (no output)"
            else ->
                "$prefix\n$truncated"
        }
    }
}
