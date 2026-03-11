package com.zzf.rikki.idea.agent.compat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class InMemoryPendingApprovalService : PendingApprovalService {
    private data class PendingState(
        val record: PendingCommandRecord,
        val resultFuture: CompletableFuture<ToolExecutionResult>
    )

    private val pendingStates = ConcurrentHashMap<String, PendingState>()
    private val latestPendingCommandId = AtomicReference<String?>()
    private val skipFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val whitelistedFamilies = ConcurrentHashMap<String, MutableSet<String>>()
    private val alwaysAllowFamilies = ConcurrentHashMap<String, MutableSet<String>>()

    override fun registerPendingCommand(pendingCommand: PendingCommandRecord): PendingCommandRegistration {
        val autoDecision = autoDecisionFor(pendingCommand)
        if (autoDecision != null) {
            val result = executePending(pendingCommand, autoDecision)
            return PendingCommandRegistration(
                pendingCommand = pendingCommand,
                awaitApproval = false,
                immediateResult = result
            )
        }
        pendingStates[pendingCommand.id] = PendingState(
            record = pendingCommand,
            resultFuture = CompletableFuture()
        )
        latestPendingCommandId.set(pendingCommand.id)
        return PendingCommandRegistration(
            pendingCommand = pendingCommand,
            awaitApproval = true
        )
    }

    override suspend fun awaitDecision(commandId: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        val state = pendingStates[commandId]
        if (state == null) {
            ToolExecutionResult(
                status = "rejected",
                output = "Pending command not found: $commandId",
                error = "Pending command not found: $commandId"
            )
        } else {
            try {
                state.resultFuture.get(1L, TimeUnit.HOURS)
            } catch (e: Exception) {
                pendingStates.remove(commandId)
                ToolExecutionResult(
                    status = "error",
                    output = "",
                    error = e.message ?: "Timed out waiting for approval."
                )
            }
        }
    }

    override fun resolve(
        commandId: String,
        reject: Boolean,
        decisionMode: String
    ): PendingCommandResolutionResult {
        val state = pendingStates[commandId]
            ?: return PendingCommandResolutionResult(
                status = "error",
                output = "",
                error = "Pending command not found: $commandId"
            )
        val normalizedDecision = normalizeDecisionMode(decisionMode)
        val result = if (reject) {
            ToolExecutionResult(
                status = "rejected",
                output = "User rejected command, not executed: ${state.record.command}",
                meta = mapOf(
                    "approval_result" to "rejected",
                    "resolved_by_user" to true,
                    "approval_mode" to PendingApprovalService.DECISION_MANUAL
                )
            )
        } else {
            applyDecisionPolicy(state.record, normalizedDecision)
            executePending(state.record, normalizedDecision)
        }
        pendingStates.remove(commandId)
        state.resultFuture.complete(result)
        return result.toResolutionResult(state.record.command, normalizedDecision)
    }

    override fun skipCurrentExecution() {
        skipFlags.values.forEach { it.set(true) }
    }

    override fun clearSession(sessionId: String) {
        if (sessionId.isBlank()) {
            return
        }
        val ids = pendingStates.values
            .filter { it.record.sessionId == sessionId }
            .map { it.record.id }
        for (id in ids) {
            val state = pendingStates.remove(id) ?: continue
            state.resultFuture.complete(
                ToolExecutionResult(
                    status = "rejected",
                    output = "Pending command cleared for session: $sessionId",
                    error = "Pending command cleared for session: $sessionId"
                )
            )
        }
        skipFlags.remove(sessionId)
        whitelistedFamilies.remove(sessionId)
        alwaysAllowFamilies.remove(sessionId)
    }

    fun resolveLatestPending(approve: Boolean): PendingCommandResolutionResult {
        val commandId = latestPendingCommandId.get()
            ?: return PendingCommandResolutionResult(
                status = "error",
                output = "",
                error = "No pending command."
            )
        return resolve(
            commandId = commandId,
            reject = !approve,
            decisionMode = PendingApprovalService.DECISION_MANUAL
        )
    }

    fun skipFlagFor(sessionId: String): AtomicBoolean =
        skipFlags.computeIfAbsent(sessionId.ifBlank { "__default__" }) { AtomicBoolean(false) }

    private fun autoDecisionFor(record: PendingCommandRecord): String? {
        if (record.sessionId.isBlank() || record.strictApproval) {
            return null
        }
        if (alwaysAllowFamilies[record.sessionId]?.contains(record.commandFamily) == true) {
            return PendingApprovalService.DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE
        }
        if (whitelistedFamilies[record.sessionId]?.contains(record.commandFamily) == true) {
            return PendingApprovalService.DECISION_WHITELIST
        }
        return null
    }

    private fun applyDecisionPolicy(record: PendingCommandRecord, decisionMode: String) {
        if (record.sessionId.isBlank()) {
            return
        }
        if (decisionMode == PendingApprovalService.DECISION_WHITELIST) {
            val set = whitelistedFamilies.computeIfAbsent(record.sessionId) {
                ConcurrentHashMap.newKeySet<String>()
            }
            set += record.commandFamily
        }
        if (
            decisionMode == PendingApprovalService.DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE
            && !record.strictApproval
        ) {
            val set = alwaysAllowFamilies.computeIfAbsent(record.sessionId) {
                ConcurrentHashMap.newKeySet<String>()
            }
            set += record.commandFamily
        }
    }

    private fun executePending(
        record: PendingCommandRecord,
        decisionMode: String
    ): ToolExecutionResult {
        val skipFlag = skipFlagFor(record.sessionId)
        skipFlag.set(false)
        val raw = try {
            record.executor()
        } catch (e: Exception) {
            ToolExecutionResult(
                status = "error",
                output = "",
                error = e.message ?: "Failed to execute pending command."
            )
        }
        val mergedMeta = LinkedHashMap<String, Any?>()
        mergedMeta.putAll(raw.meta)
        mergedMeta["approval_result"] = raw.status
        mergedMeta["resolved_by_user"] = true
        mergedMeta["approval_mode"] = decisionMode
        return raw.copy(meta = mergedMeta)
    }

    companion object {
        fun normalizeDecisionMode(decisionMode: String?): String = when (decisionMode?.trim()?.lowercase()) {
            PendingApprovalService.DECISION_WHITELIST -> PendingApprovalService.DECISION_WHITELIST
            PendingApprovalService.DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE ->
                PendingApprovalService.DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE

            else -> PendingApprovalService.DECISION_MANUAL
        }
    }
}