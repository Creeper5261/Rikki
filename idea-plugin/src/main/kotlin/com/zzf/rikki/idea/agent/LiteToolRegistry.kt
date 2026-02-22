package com.zzf.rikki.idea.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.zzf.rikki.idea.agent.tools.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Dispatches tool calls and provides tool definitions for the LLM. */
class LiteToolRegistry(private val project: Project, private val mapper: ObjectMapper) {

    data class ToolResult(
        val output: String,
        val error: String? = null,
        /** Non-null for write/edit/delete_file — carries before+after content for diff. */
        val pendingChangePath: String? = null,
        val pendingChangeOld: String? = null,
        val pendingChangeNew: String? = null,
        val pendingChangeType: String? = null
    )

    private val bash   = LiteBashTool()
    private val files  = LiteFileTools(mapper)
    val ide            = LiteIdeTools(project, mapper)  // public so engine can set ideContextNode
    private val todos  = LiteTodoTools(mapper)

    private var skipFlag: AtomicBoolean? = null

    fun setSkipFlag(flag: AtomicBoolean) { skipFlag = flag }

    /** Returns true if this tool+args requires inline user confirmation. */
    fun isHighRisk(name: String, args: JsonNode): Boolean {
        if (name == "bash") return isHighRiskBashCommand(args.path("command").asText(""))
        if (name == "delete_file") return true
        return false
    }

    fun execute(name: String, args: JsonNode, workspaceRoot: String, sessionId: String, callId: String): ToolResult {
        // Capture old file content for diff before touching it
        val filePath = if (name in FILE_CHANGE_TOOLS) args.path("filePath").asText("") else ""
        val absFile  = if (filePath.isNotBlank()) resolveAbsFile(filePath, workspaceRoot) else null
        val oldContent = if (absFile?.exists() == true) {
            try { absFile.readText() } catch (_: Exception) { "" }
        } else ""
        val changeType = when (name) {
            "delete_file" -> "DELETE"
            else -> if (absFile?.exists() == true) "EDIT" else "CREATE"
        }

        return try {
            val out = when (name) {
                "bash"              -> bash.execute(args, workspaceRoot, skipFlag)
                "read"              -> files.read(args, workspaceRoot)
                "write"             -> files.write(args, workspaceRoot)
                "edit"              -> files.edit(args, workspaceRoot)
                "delete_file"       -> files.delete(args, workspaceRoot)
                "glob"              -> files.glob(args, workspaceRoot)
                "grep"              -> files.grep(args, workspaceRoot)
                "ls"                -> files.list(args, workspaceRoot)
                "todo_read"         -> todos.read(workspaceRoot)
                "todo_write"        -> todos.write(args, workspaceRoot)
                "ide_context"       -> ide.context(args)
                "ide_action"        -> ide.action(args)
                "ide_capabilities"  -> ide.capabilities()
                else -> "Unknown tool: $name"
            }
            if (absFile != null && filePath.isNotBlank()) {
                val newContent = if ("delete_file" == name) "" else {
                    try { absFile.readText() } catch (_: Exception) { "" }
                }
                ToolResult(out, null, filePath, oldContent, newContent, changeType)
            } else {
                ToolResult(out)
            }
        } catch (e: Exception) {
            ToolResult("", e.message ?: "Tool error")
        }
    }

    private fun resolveAbsFile(filePath: String, workspaceRoot: String): File {
        val f = File(filePath)
        return if (f.isAbsolute) f else File(workspaceRoot, filePath)
    }

    // ── Risk assessment ───────────────────────────────────────────────────────

    private fun isHighRiskBashCommand(cmd: String): Boolean {
        val c = cmd.trim()
        return RISK_PATTERNS.any { c.contains(it) }
    }

    private val RISK_PATTERNS = listOf(
        "sudo ", "su -", "su root",
        "rm -rf", "rm -fr", "rm -r ", "rm -f /",
        "mkfs", "dd if=",
        "| bash", "| sh ", "| zsh ", "| fish ",
        "chmod 777", "chmod -R ",
        "> /dev/", "/dev/sd", "/dev/hd", "/dev/nvme",
        ":(){ :|:& };:"   // fork bomb
    )

    companion object {
        private val FILE_CHANGE_TOOLS = setOf("write", "edit", "delete_file")

        fun toolDefinitions(): List<Map<String, Any>> = listOf(
            tool("bash", "Execute a bash command in the workspace.",
                props(
                    "command"     to str("The command to execute"),
                    "description" to str("What this command does in 5-10 words"),
                    "timeout"     to int("Timeout in milliseconds (default 60000)"),
                    "workdir"     to str("Working directory (defaults to workspace root)")
                ),
                required = listOf("command", "description")
            ),
            tool("read", "Reads a file from the local filesystem. Returns line-numbered content.",
                props(
                    "filePath" to str("Absolute path to the file"),
                    "offset"   to int("Line number to start reading from (0-based)"),
                    "limit"    to int("Number of lines to read (default 2000)")
                ),
                required = listOf("filePath")
            ),
            tool("write", "Writes content to a file. Creates it if it doesn't exist, overwrites if it does.",
                props(
                    "filePath" to str("Absolute path to the file"),
                    "content"  to str("Full content to write")
                ),
                required = listOf("filePath", "content")
            ),
            tool("edit", "Performs exact string replacement in a file.",
                props(
                    "filePath"   to str("Absolute path to the file"),
                    "oldString"  to str("Text to replace (leave empty to create new file)"),
                    "newString"  to str("Replacement text"),
                    "replaceAll" to bool("Replace all occurrences (default false)")
                ),
                required = listOf("filePath", "newString")
            ),
            tool("delete_file", "Deletes a file from the workspace.",
                props("filePath" to str("Absolute path to the file")),
                required = listOf("filePath")
            ),
            tool("glob", "Find files matching a glob pattern, sorted by modification time.",
                props(
                    "pattern" to str("Glob pattern, e.g. **/*.kt"),
                    "path"    to str("Directory to search (defaults to workspace root)")
                ),
                required = listOf("pattern")
            ),
            tool("grep", "Search file contents using a regex pattern.",
                props(
                    "pattern" to str("Regex pattern to search for"),
                    "path"    to str("Directory to search (defaults to workspace root)"),
                    "include" to str("File glob filter, e.g. *.java")
                ),
                required = listOf("pattern")
            ),
            tool("ls", "Lists files and directories in a path.",
                props(
                    "path"   to str("Absolute path to directory"),
                    "ignore" to mapOf("type" to "array", "items" to mapOf("type" to "string"),
                        "description" to "Patterns to ignore")
                ),
                required = emptyList()
            ),
            tool("todo_read", "Read the current to-do list for this workspace.",
                props(), required = emptyList()
            ),
            tool("todo_write", "Update the to-do list for this workspace.",
                props(
                    "todos" to mapOf(
                        "type" to "array",
                        "description" to "Full replacement todo list",
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "id"       to mapOf("type" to "string", "description" to "Unique identifier for this item"),
                                "content"  to mapOf("type" to "string", "description" to "Task description text (required)"),
                                "status"   to mapOf("type" to "string", "enum" to listOf("pending", "in_progress", "completed"), "description" to "Task status"),
                                "priority" to mapOf("type" to "string", "enum" to listOf("high", "medium", "low"), "description" to "Task priority")
                            ),
                            "required" to listOf("content", "status")
                        )
                    )
                ),
                required = listOf("todos")
            ),
            tool("ide_context", "Read IDE project/SDK/build context on demand.",
                props(
                    "query" to mapOf(
                        "type" to "string",
                        "enum" to listOf("project", "sdk", "build", "modules", "all"),
                        "description" to "Which IDE context section to read."
                    ),
                    "keys"     to mapOf("type" to "array", "items" to mapOf("type" to "string"),
                        "description" to "Specific keys to return."),
                    "maxItems" to int("Max list items (default 20, max 100)")
                ),
                required = emptyList()
            ),
            tool("ide_action", "Unified IDE-native action: build, run, test, status, cancel, capabilities.",
                props(
                    "operation" to mapOf(
                        "type" to "string",
                        "enum" to listOf("build", "run", "test", "status", "cancel", "capabilities"),
                        "description" to "IDE action to execute."
                    ),
                    "mode"          to str("Build mode: make|rebuild"),
                    "configuration" to str("Run configuration name"),
                    "executor"      to str("Executor: run|debug"),
                    "jobId"         to str("Job id for status/cancel"),
                    "wait"          to bool("Block until job finishes"),
                    "timeoutMs"     to int("Wait timeout in ms"),
                    "pollIntervalMs" to int("Polling interval in ms")
                ),
                required = listOf("operation")
            ),
            tool("ide_capabilities", "Fetch available IDE-native bridge capabilities.",
                props(), required = emptyList()
            )
        )

        // ── helpers ──────────────────────────────────────────────────────────

        private fun tool(
            name: String, description: String,
            properties: Map<String, Any>, required: List<String>
        ): Map<String, Any> = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to description,
                "parameters" to mapOf(
                    "type"       to "object",
                    "properties" to properties,
                    "required"   to required
                )
            )
        )

        private fun props(vararg pairs: Pair<String, Any>) = mapOf(*pairs)
        private fun str(desc: String)  = mapOf("type" to "string",  "description" to desc)
        private fun int(desc: String)  = mapOf("type" to "integer", "description" to desc)
        private fun bool(desc: String) = mapOf("type" to "boolean", "description" to desc)
    }
}
