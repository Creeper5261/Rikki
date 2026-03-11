package com.zzf.rikki.idea.agent.tools

import com.fasterxml.jackson.databind.JsonNode
import com.zzf.rikki.idea.agent.compat.CommandRunner
import com.zzf.rikki.idea.agent.compat.CommandRunnerResult
import java.util.concurrent.atomic.AtomicBoolean

/** Shell tool facade: validates args, delegates execution, preserves legacy output formatting. */
class LiteBashTool {
    private val runner = CommandRunner()

    fun execute(args: JsonNode, workspaceRoot: String, skipFlag: AtomicBoolean? = null): String {
        val detail = executeDetailed(args, workspaceRoot, skipFlag)
        val timeoutMs = args.path("timeout").asLong(CommandRunner.DEFAULT_TIMEOUT_MS).let {
            if (it <= 0L) CommandRunner.DEFAULT_TIMEOUT_MS else it
        }
        return formatResult(args.path("command").asText(""), timeoutMs, detail)
    }

    fun executeDetailed(
        args: JsonNode,
        workspaceRoot: String,
        skipFlag: AtomicBoolean? = null
    ): CommandRunnerResult {
        val command = args.path("command").asText("").ifBlank {
            throw IllegalArgumentException("command is required")
        }
        val workdir = args.path("workdir").asText(workspaceRoot).ifBlank { workspaceRoot }
        val timeoutMs = args.path("timeout").asLong(CommandRunner.DEFAULT_TIMEOUT_MS).let {
            if (it <= 0L) CommandRunner.DEFAULT_TIMEOUT_MS else it
        }
        val shell = args.path("shell").asText("auto").trim().lowercase().ifBlank { "auto" }
        return runner.run(
            command = command,
            workspaceRoot = workspaceRoot,
            workdir = workdir,
            timeoutMs = timeoutMs,
            shellPref = shell,
            skipFlag = skipFlag
        )
    }

    fun formatResult(command: String, timeoutMs: Long, result: CommandRunnerResult): String =
        runner.formatOutput(command, timeoutMs, result)

    companion object {
        private val RISK_PATTERNS = listOf(
            "sudo " to "requires elevated privileges",
            "su -" to "switches user identity",
            "su root" to "switches user identity",
            "rm -rf" to "destructive file removal",
            "rm -fr" to "destructive file removal",
            "rm -r " to "recursive deletion",
            "rm -f /" to "targets filesystem root",
            "mkfs" to "formats a filesystem",
            "dd if=" to "raw disk write",
            "| bash" to "pipes remote script into shell",
            "| sh " to "pipes remote script into shell",
            "| zsh " to "pipes remote script into shell",
            "| fish " to "pipes remote script into shell",
            "chmod 777" to "grants unsafe world-writable permissions",
            "chmod -R " to "recursively changes permissions",
            "> /dev/" to "writes to device path",
            "/dev/sd" to "targets block device",
            "/dev/hd" to "targets block device",
            "/dev/nvme" to "targets block device",
            ":(){ :|:& };:" to "fork bomb"
        )

        fun isHighRiskCommand(command: String): Boolean = detectRiskReasons(command).isNotEmpty()

        fun detectRiskReasons(command: String): List<String> {
            val normalized = command.trim().lowercase()
            return RISK_PATTERNS
                .filter { normalized.contains(it.first) }
                .map { it.second }
                .distinct()
        }

        fun isStrictApprovalCommand(command: String): Boolean {
            val normalized = command.trim().lowercase()
            return listOf(
                "rm -rf",
                "rm -fr",
                "rm -r ",
                "rm -f /",
                "mkfs",
                "dd if=",
                "> /dev/",
                "/dev/sd",
                "/dev/hd",
                "/dev/nvme",
                ":(){ :|:& };:"
            ).any { normalized.contains(it) }
        }

        fun commandFamily(command: String): String {
            val trimmed = command.trim()
            if (trimmed.isBlank()) {
                return "command"
            }
            val firstToken = trimmed.substringBefore(' ').substringAfterLast('/')
            return firstToken.lowercase().ifBlank { "command" }
        }
    }
}