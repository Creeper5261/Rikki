package com.zzf.rikki.idea.agent.compat;

import com.zzf.rikki.runtime.port.PendingApprovalPort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class InMemoryPendingApprovalService implements PendingApprovalPort {
    private record PendingState(PendingCommandRecord record, CompletableFuture<ToolExecutionResult> resultFuture) {
    }

    private final Map<String, PendingState> pendingStates = new ConcurrentHashMap<>();
    private final AtomicReference<String> latestPendingCommandId = new AtomicReference<>();
    private final Map<String, AtomicBoolean> skipFlags = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> whitelistedFamilies = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> alwaysAllowFamilies = new ConcurrentHashMap<>();

    @Override
    public PendingCommandRegistration registerPendingCommand(PendingCommandRecord pendingCommand) {
        String autoDecision = autoDecisionFor(pendingCommand);
        if (autoDecision != null) {
            ToolExecutionResult result = executePending(pendingCommand, autoDecision);
            return new PendingCommandRegistration(pendingCommand, false, result);
        }
        pendingStates.put(
                pendingCommand.getId(),
                new PendingState(pendingCommand, new CompletableFuture<>())
        );
        latestPendingCommandId.set(pendingCommand.getId());
        return new PendingCommandRegistration(pendingCommand, true, null);
    }

    @Override
    public ToolExecutionResult awaitDecision(String commandId) {
        PendingState state = pendingStates.get(commandId);
        if (state == null) {
            return new ToolExecutionResult("rejected", "Pending command not found: " + commandId, "Pending command not found: " + commandId, Map.of(), null, null, null, null, false);
        }
        try {
            return state.resultFuture().get(1L, TimeUnit.HOURS);
        } catch (Exception e) {
            pendingStates.remove(commandId);
            return new ToolExecutionResult("error", "", e.getMessage() == null ? "Timed out waiting for approval." : e.getMessage(), Map.of(), null, null, null, null, false);
        }
    }

    @Override
    public PendingCommandResolutionResult resolve(String commandId, boolean reject, String decisionMode) {
        PendingState state = pendingStates.get(commandId);
        if (state == null) {
            return new PendingCommandResolutionResult("error", "", "Pending command not found: " + commandId, null, "", false, PendingApprovalService.DECISION_MANUAL);
        }
        String normalizedDecision = normalizeDecisionMode(decisionMode);
        ToolExecutionResult result;
        if (reject) {
            LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
            meta.put("approval_result", "rejected");
            meta.put("resolved_by_user", Boolean.TRUE);
            meta.put("approval_mode", PendingApprovalService.DECISION_MANUAL);
            result = new ToolExecutionResult(
                    "rejected",
                    "User rejected command, not executed: " + state.record().getCommand(),
                    null,
                    meta,
                    null,
                    null,
                    null,
                    null,
                    false
            );
        } else {
            applyDecisionPolicy(state.record(), normalizedDecision);
            result = executePending(state.record(), normalizedDecision);
        }
        pendingStates.remove(commandId);
        state.resultFuture().complete(result);
        return result.toResolutionResult(state.record().getCommand(), normalizedDecision);
    }

    @Override
    public void skipCurrentExecution() {
        skipFlags.values().forEach(flag -> flag.set(true));
    }

    @Override
    public void clearSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        pendingStates.values().stream()
                .filter(state -> sessionId.equals(state.record().getSessionId()))
                .map(state -> state.record().getId())
                .toList()
                .forEach(id -> {
                    PendingState state = pendingStates.remove(id);
                    if (state != null) {
                        state.resultFuture().complete(
                                new ToolExecutionResult(
                                        "rejected",
                                        "Pending command cleared for session: " + sessionId,
                                        "Pending command cleared for session: " + sessionId,
                                        Map.of(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        false
                                )
                        );
                    }
                });
        skipFlags.remove(sessionId);
        whitelistedFamilies.remove(sessionId);
        alwaysAllowFamilies.remove(sessionId);
    }

    public PendingCommandResolutionResult resolveLatestPending(boolean approve) {
        String commandId = latestPendingCommandId.get();
        if (commandId == null || commandId.isBlank()) {
            return new PendingCommandResolutionResult("error", "", "No pending command.", null, "", false, PendingApprovalService.DECISION_MANUAL);
        }
        return resolve(commandId, !approve, PendingApprovalService.DECISION_MANUAL);
    }

    public AtomicBoolean skipFlagFor(String sessionId) {
        String key = sessionId == null || sessionId.isBlank() ? "__default__" : sessionId;
        return skipFlags.computeIfAbsent(key, ignored -> new AtomicBoolean(false));
    }

    public static String normalizeDecisionMode(String decisionMode) {
        if (PendingApprovalService.DECISION_WHITELIST.equalsIgnoreCase(decisionMode)) {
            return PendingApprovalService.DECISION_WHITELIST;
        }
        if (PendingApprovalService.DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE.equalsIgnoreCase(decisionMode)) {
            return PendingApprovalService.DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE;
        }
        return PendingApprovalService.DECISION_MANUAL;
    }

    private String autoDecisionFor(PendingCommandRecord record) {
        if (record.getSessionId() == null || record.getSessionId().isBlank() || record.getStrictApproval()) {
            return null;
        }
        if (alwaysAllowFamilies.getOrDefault(record.getSessionId(), Set.of()).contains(record.getCommandFamily())) {
            return PendingApprovalService.DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE;
        }
        if (whitelistedFamilies.getOrDefault(record.getSessionId(), Set.of()).contains(record.getCommandFamily())) {
            return PendingApprovalService.DECISION_WHITELIST;
        }
        return null;
    }

    private void applyDecisionPolicy(PendingCommandRecord record, String decisionMode) {
        if (record.getSessionId() == null || record.getSessionId().isBlank()) {
            return;
        }
        if (PendingApprovalService.DECISION_WHITELIST.equals(decisionMode)) {
            whitelistedFamilies.computeIfAbsent(record.getSessionId(), ignored -> ConcurrentHashMap.newKeySet()).add(record.getCommandFamily());
        }
        if (PendingApprovalService.DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE.equals(decisionMode) && !record.getStrictApproval()) {
            alwaysAllowFamilies.computeIfAbsent(record.getSessionId(), ignored -> ConcurrentHashMap.newKeySet()).add(record.getCommandFamily());
        }
    }

    private ToolExecutionResult executePending(PendingCommandRecord record, String decisionMode) {
        AtomicBoolean skipFlag = skipFlagFor(record.getSessionId());
        skipFlag.set(false);
        ToolExecutionResult raw;
        try {
            raw = record.getExecutor().get();
        } catch (Exception e) {
            raw = new ToolExecutionResult("error", "", e.getMessage() == null ? "Failed to execute pending command." : e.getMessage(), Map.of(), null, null, null, null, false);
        }
        LinkedHashMap<String, Object> meta = new LinkedHashMap<>(raw.getMeta());
        meta.put("approval_result", raw.getStatus());
        meta.put("resolved_by_user", Boolean.TRUE);
        meta.put("approval_mode", decisionMode);
        return new ToolExecutionResult(
                raw.getStatus(),
                raw.getOutput(),
                raw.getError(),
                meta,
                raw.getPendingChange(),
                raw.getPendingCommand(),
                raw.getTodoJson(),
                raw.getExitCode(),
                raw.getTimeout()
        );
    }
}
