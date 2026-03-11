package com.zzf.rikki.idea.agent.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.zzf.rikki.core.tool.PendingChangesManager
import com.zzf.rikki.idea.agent.tools.LiteBashTool
import com.zzf.rikki.idea.agent.tools.LiteFileTools
import com.zzf.rikki.idea.agent.tools.LiteIdeTools
import com.zzf.rikki.idea.agent.tools.LiteTodoTools
import java.io.File

interface ToolExecutor {
    fun isHighRisk(name: String, args: JsonNode): Boolean
    fun execute(
        name: String,
        args: JsonNode,
        workspaceRoot: String,
        sessionId: String,
        callId: String,
        messageId: String
    ): ToolExecutionResult

    fun toolDefinitions(workspaceRoot: String, snapshot: LiteIdeTools.CapabilitySnapshot = ideCapabilities()): List<Map<String, Any>>
    fun ideCapabilities(): LiteIdeTools.CapabilitySnapshot
    fun refreshIdeCapabilities(): LiteIdeTools.CapabilitySnapshot
    fun setIdeContext(ideContext: JsonNode)
    fun readTodosJson(workspaceRoot: String, sessionId: String = ""): String?
    fun todosAsListJson(workspaceRoot: String): String
}

class LiteToolExecutor(
    private val project: Project,
    private val mapper: ObjectMapper,
    private val pendingApprovalService: InMemoryPendingApprovalService
) : ToolExecutor {
    private val bash = LiteBashTool()
    private val files = LiteFileTools(mapper)
    private val ide = LiteIdeTools(project, mapper)
    private val todos = LiteTodoTools(mapper)

    override fun isHighRisk(name: String, args: JsonNode): Boolean {
        if (name == "bash") {
            return LiteBashTool.isHighRiskCommand(args.path("command").asText(""))
        }
        return name == "delete_file"
    }

    override fun execute(
        name: String,
        args: JsonNode,
        workspaceRoot: String,
        sessionId: String,
        callId: String,
        messageId: String
    ): ToolExecutionResult {
        val filePath = if (name in FILE_CHANGE_TOOLS) args.path("filePath").asText("") else ""
        val absFile = if (filePath.isNotBlank()) resolveAbsFile(filePath, workspaceRoot) else null
        val oldContent = if (absFile?.exists() == true) {
            try {
                absFile.readText()
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
        val changeType = when (name) {
            "delete_file" -> "DELETE"
            else -> if (absFile?.exists() == true) "EDIT" else "CREATE"
        }

        return try {
            when (name) {
                "bash" -> executeBash(args, workspaceRoot, sessionId)
                "read" -> success("completed", files.read(args, workspaceRoot))
                "write" -> withPendingChange(
                    files.write(args, workspaceRoot),
                    filePath,
                    oldContent,
                    readCurrent(absFile),
                    changeType,
                    workspaceRoot,
                    sessionId
                )
                "edit" -> withPendingChange(
                    files.edit(args, workspaceRoot),
                    filePath,
                    oldContent,
                    readCurrent(absFile),
                    changeType,
                    workspaceRoot,
                    sessionId
                )
                "delete_file" -> {
                    val out = files.delete(args, workspaceRoot)
                    withPendingChange(
                        out,
                        filePath,
                        oldContent,
                        "",
                        "DELETE",
                        workspaceRoot,
                        sessionId
                    )
                }
                "glob" -> success("completed", files.glob(args, workspaceRoot))
                "grep" -> success("completed", files.grep(args, workspaceRoot))
                "ls" -> success("completed", files.list(args, workspaceRoot))
                "todo_read" -> success("completed", todos.read(workspaceRoot, sessionId))
                "todo_write" -> {
                    val out = todos.write(args, workspaceRoot, sessionId)
                    success(
                        status = "completed",
                        output = out,
                        todoJson = todos.readJson(workspaceRoot, sessionId)
                    )
                }
                "ide_context" -> success("completed", ide.context(args))
                "ide_action" -> success("completed", ide.action(args))
                "ide_capabilities" -> success("completed", ide.capabilities())
                else -> ToolExecutionResult(
                    status = "error",
                    output = "",
                    error = "Unknown tool: $name"
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                status = "error",
                output = "",
                error = e.message ?: "Tool error"
            )
        }
    }

    override fun toolDefinitions(workspaceRoot: String, snapshot: LiteIdeTools.CapabilitySnapshot): List<Map<String, Any>> {
        return BackendToolDefinitions.build(workspaceRoot, snapshot)
    }

    override fun ideCapabilities(): LiteIdeTools.CapabilitySnapshot = ide.capabilitySnapshot()

    override fun refreshIdeCapabilities(): LiteIdeTools.CapabilitySnapshot = ide.refreshCapabilities()

    override fun setIdeContext(ideContext: JsonNode) {
        ide.ideContextNode = ideContext
    }

    override fun readTodosJson(workspaceRoot: String, sessionId: String): String? =
        todos.readJson(workspaceRoot, sessionId)

    override fun todosAsListJson(workspaceRoot: String): String =
        todos.readJson(workspaceRoot, "") ?: "[]"

    private fun executeBash(
        args: JsonNode,
        workspaceRoot: String,
        sessionId: String
    ): ToolExecutionResult {
        val detail = bash.executeDetailed(args, workspaceRoot, pendingApprovalService.skipFlagFor(sessionId))
        val timeoutMs = args.path("timeout").asLong(CommandRunner.DEFAULT_TIMEOUT_MS).let {
            if (it <= 0L) CommandRunner.DEFAULT_TIMEOUT_MS else it
        }
        val output = bash.formatResult(
            args.path("command").asText(""),
            timeoutMs,
            detail
        )
        val status = when {
            detail.skipped -> "rejected"
            detail.exitCode == 0 && !detail.timedOut -> "completed"
            else -> "error"
        }
        return ToolExecutionResult(
            status = status,
            output = output,
            error = if (status == "completed") null else output,
            meta = mapOf(
                "shell" to detail.shell,
                "exit" to detail.exitCode,
                "timeout" to detail.timedOut,
                "skipped" to detail.skipped,
                "output" to output
            ),
            exitCode = detail.exitCode,
            timeout = detail.timedOut
        )
    }

    private fun withPendingChange(
        output: String,
        filePath: String,
        oldContent: String,
        newContent: String,
        changeType: String,
        workspaceRoot: String,
        sessionId: String
    ): ToolExecutionResult {
        val pendingChange = PendingChangesManager.PendingChange(
            filePath,
            changeType,
            oldContent,
            newContent,
            "",
            workspaceRoot
        )
        val scopedChange = PendingChangesManager.PendingChange(
            pendingChange.id,
            pendingChange.path,
            pendingChange.type,
            pendingChange.oldContent,
            pendingChange.newContent,
            pendingChange.preview,
            pendingChange.timestamp,
            workspaceRoot,
            sessionId
        )
        return success(
            status = "completed",
            output = output,
            pendingChange = scopedChange
        )
    }

    private fun success(
        status: String,
        output: String,
        pendingChange: PendingChangesManager.PendingChange? = null,
        todoJson: String? = null
    ): ToolExecutionResult {
        val meta = LinkedHashMap<String, Any?>()
        if (pendingChange != null) {
            meta["workspaceApplied"] = true
            meta["pending_change"] = pendingChange
        }
        if (todoJson != null) {
            meta["todos"] = todoJson
        }
        return ToolExecutionResult(
            status = status,
            output = output,
            meta = meta,
            pendingChange = pendingChange,
            todoJson = todoJson
        )
    }

    private fun readCurrent(file: File?): String = if (file == null) {
        ""
    } else {
        try {
            file.readText()
        } catch (_: Exception) {
            ""
        }
    }

    private fun resolveAbsFile(filePath: String, workspaceRoot: String): File {
        val file = File(filePath)
        return if (file.isAbsolute) file else File(workspaceRoot, filePath)
    }

    companion object {
        private val FILE_CHANGE_TOOLS = setOf("write", "edit", "delete_file")

        private fun tool(
            name: String,
            description: String,
            properties: Map<String, Any>,
            required: List<String>
        ): Map<String, Any> = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to properties,
                    "required" to required
                )
            )
        )

        private fun props(vararg pairs: Pair<String, Any>) = mapOf(*pairs)
        private fun str(desc: String) = mapOf("type" to "string", "description" to desc)
        private fun int(desc: String) = mapOf("type" to "integer", "description" to desc)
        private fun bool(desc: String) = mapOf("type" to "boolean", "description" to desc)
    }
}