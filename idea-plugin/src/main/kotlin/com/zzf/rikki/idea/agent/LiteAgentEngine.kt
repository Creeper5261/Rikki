package com.zzf.rikki.idea.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.zzf.rikki.idea.agent.compat.ChatRuntimeRequest
import com.zzf.rikki.idea.agent.compat.InMemoryPendingApprovalService
import com.zzf.rikki.idea.agent.compat.LiteCompatRuntime
import com.zzf.rikki.idea.agent.compat.LiteModelSupport
import com.zzf.rikki.idea.agent.compat.LiteToolExecutor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Thin facade over the compat runtime adapter. */
class LiteAgentEngine(
    project: Project,
    mapper: ObjectMapper,
    private val pendingApprovalService: InMemoryPendingApprovalService = InMemoryPendingApprovalService(),
    toolExecutor: LiteToolExecutor = LiteToolExecutor(project, mapper, pendingApprovalService)
) {
    private val runtimeFacade = LiteCompatRuntime(
        project = project,
        mapper = mapper,
        pendingApprovalService = pendingApprovalService,
        toolExecutor = toolExecutor
    )

    fun approvalService(): InMemoryPendingApprovalService = pendingApprovalService

    fun setSkipFlag(flag: AtomicBoolean) {
        if (flag.get()) {
            pendingApprovalService.skipCurrentExecution()
        }
    }

    fun setConfirmFutureRef(ref: AtomicReference<CompletableFuture<Boolean>?>) {
        ref.set(null)
    }

    suspend fun run(
        goal: String,
        workspaceRoot: String,
        ideContext: JsonNode,
        history: JsonNode,
        settings: JsonNode,
        sessionId: String,
        sseWriter: LiteSseWriter
    ) {
        runtimeFacade.run(
            request = ChatRuntimeRequest(
                goal = goal,
                workspaceRoot = workspaceRoot,
                ideContext = ideContext,
                history = history,
                settings = settings,
                sessionId = sessionId
            ),
            sink = sseWriter
        )
    }

    companion object {
        fun detectCapabilities(provider: String, model: String) =
            LiteModelSupport.detectCapabilities(provider, model)

        fun parseHistoryLine(text: String): Pair<String, String>? =
            LiteModelSupport.parseHistoryLine(text)
    }
}