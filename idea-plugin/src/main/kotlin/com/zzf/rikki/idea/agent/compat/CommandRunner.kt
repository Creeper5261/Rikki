package com.zzf.rikki.idea.agent.compat

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class CommandRunnerResult(
    val output: String,
    val exitCode: Int,
    val timedOut: Boolean,
    val skipped: Boolean,
    val shell: String
)

class CommandRunner {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val MAX_OUTPUT = 8_000
        private const val POLL_INTERVAL_MS = 200L
        private val SUPPORTED_SHELLS = setOf("auto", "bash", "powershell", "cmd")
    }

    private data class ShellSpec(val name: String, val argvPrefix: List<String>)

    fun run(
        command: String,
        workspaceRoot: String,
        workdir: String,
        timeoutMs: Long,
        shellPref: String,
        skipFlag: AtomicBoolean? = null
    ): CommandRunnerResult {
        val normalizedShell = shellPref.trim().lowercase().ifBlank { "auto" }
        require(normalizedShell in SUPPORTED_SHELLS) {
            "shell must be one of: auto, bash, powershell, cmd"
        }
        val resolvedWorkdir = resolveWorkdir(workspaceRoot, workdir)
        val candidates = resolveShellCandidates(normalizedShell)
        val startupErrors = mutableListOf<String>()
        for (shell in candidates) {
            val pb = ProcessBuilder(shell.argvPrefix + command)
                .directory(resolvedWorkdir)
                .redirectErrorStream(true)
            pb.environment().putAll(System.getenv())
            pb.environment()["PYTHONIOENCODING"] = "utf-8"
            if (shell.name == "bash") {
                pb.environment()["LANG"] = "en_US.UTF-8"
                pb.environment()["LC_ALL"] = "en_US.UTF-8"
            }
            val process = try {
                pb.start()
            } catch (e: Exception) {
                startupErrors += "${shell.name}: ${e.message ?: "failed to start"}"
                continue
            }
            return executeProcess(process, timeoutMs, skipFlag, shell.name)
        }
        val shellNames = candidates.joinToString(", ") { it.name }
        val detail = if (startupErrors.isEmpty()) {
            "no startup error details"
        } else {
            startupErrors.joinToString("; ")
        }
        return CommandRunnerResult(
            output = "No usable shell found (requested=$normalizedShell, tried=$shellNames): $detail",
            exitCode = -1,
            timedOut = false,
            skipped = false,
            shell = normalizedShell
        )
    }

    fun formatOutput(
        command: String,
        timeoutMs: Long,
        result: CommandRunnerResult
    ): String {
        val prefix = "[shell=${result.shell}]"
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

    private fun resolveWorkdir(workspaceRoot: String, requested: String): File {
        val dir = File(requested).let { if (it.isAbsolute) it else File(workspaceRoot, requested) }
        return if (dir.isDirectory) dir else File(workspaceRoot)
    }

    private fun resolveShellCandidates(shellPref: String): List<ShellSpec> = when (shellPref) {
        "bash" -> listOf(ShellSpec("bash", listOf("bash", "-c")))
        "powershell" -> listOf(
            ShellSpec("powershell", listOf("powershell", "-NoProfile", "-NonInteractive", "-Command"))
        )

        "cmd" -> listOf(ShellSpec("cmd", listOf("cmd", "/c")))
        else -> listOf(
            ShellSpec("bash", listOf("bash", "-c")),
            ShellSpec("powershell", listOf("powershell", "-NoProfile", "-NonInteractive", "-Command")),
            ShellSpec("cmd", listOf("cmd", "/c"))
        )
    }

    private fun executeProcess(
        process: Process,
        timeoutMs: Long,
        skipFlag: AtomicBoolean?,
        shell: String
    ): CommandRunnerResult {
        val outputBuf = ByteArrayOutputStream()
        val readerThread = Thread {
            try {
                process.inputStream.copyTo(outputBuf)
            } catch (_: Exception) {
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var timedOut = false
        var skipped = false
        while (process.isAlive) {
            if (skipFlag?.get() == true) {
                skipped = true
                process.destroyForcibly()
                break
            }
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true
                process.destroyForcibly()
                break
            }
            process.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
        readerThread.join(2_000)
        val output = outputBuf.toString(Charsets.UTF_8.name())
        val exitCode = try {
            process.exitValue()
        } catch (_: Exception) {
            -1
        }
        return CommandRunnerResult(output, exitCode, timedOut, skipped, shell)
    }
}