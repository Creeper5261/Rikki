package com.zzf.rikki.idea.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.zzf.rikki.idea.agent.compat.InMemoryPendingApprovalService
import com.zzf.rikki.idea.agent.compat.LiteToolExecutor
import com.zzf.rikki.idea.agent.compat.ToolExecutionResult
import java.util.concurrent.atomic.AtomicBoolean

/** Thin facade over the compat tool executor. */
class LiteToolRegistry(
    project: Project,
    mapper: ObjectMapper,
    private val pendingApprovalService: InMemoryPendingApprovalService = InMemoryPendingApprovalService()
) {
    data class ToolResult(
        val output: String,
        val error: String? = null,
        val pendingChangePath: String? = null,
        val pendingChangeOld: String? = null,
        val pendingChangeNew: String? = null,
        val pendingChangeType: String? = null
    )

    private val executor = LiteToolExecutor(project, mapper, pendingApprovalService)

    fun setSkipFlag(flag: AtomicBoolean) {
        if (flag.get()) {
            pendingApprovalService.skipCurrentExecution()
        }
    }

    fun isHighRisk(name: String, args: JsonNode): Boolean = executor.isHighRisk(name, args)

    fun execute(name: String, args: JsonNode, workspaceRoot: String, sessionId: String, callId: String): ToolResult {
        val result = executor.execute(name, args, workspaceRoot, sessionId, callId, "")
        return result.toLegacyResult()
    }

    fun toolDefinitions(workspaceRoot: String, snapshot: com.zzf.rikki.idea.agent.tools.LiteIdeTools.CapabilitySnapshot = executor.ideCapabilities()): List<Map<String, Any>> =
        executor.toolDefinitions(workspaceRoot, snapshot)

    fun readTodosJson(workspaceRoot: String, sessionId: String): String? =
        executor.readTodosJson(workspaceRoot, sessionId)

    private fun ToolExecutionResult.toLegacyResult(): ToolResult = ToolResult(
        output = output,
        error = error,
        pendingChangePath = pendingChange?.path,
        pendingChangeOld = pendingChange?.oldContent,
        pendingChangeNew = pendingChange?.newContent,
        pendingChangeType = pendingChange?.type
    )
}