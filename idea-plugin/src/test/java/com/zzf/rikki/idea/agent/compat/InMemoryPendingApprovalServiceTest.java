package com.zzf.rikki.idea.agent.compat;

import com.zzf.rikki.idea.agent.tools.LiteBashTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPendingApprovalServiceTest {

    @Test
    void resolve_shouldCompletePendingCommandOnManualApprove() throws Exception {
        InMemoryPendingApprovalService service = new InMemoryPendingApprovalService();
        AtomicInteger executions = new AtomicInteger();
        PendingCommandRegistration registration = service.registerPendingCommand(record(
                "pc-approve",
                "session-1",
                "npm test",
                true,
                () -> {
                    executions.incrementAndGet();
                    return new ToolExecutionResult("completed", "approved", null, Map.of(), null, null, null, null, false);
                }
        ));

        assertTrue(registration.getAwaitApproval());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ToolExecutionResult> awaited = executor.submit(() -> service.awaitDecision("pc-approve"));
            Thread.sleep(25L);

            PendingCommandResolutionResult resolution = service.resolve("pc-approve", false, PendingApprovalService.DECISION_MANUAL);
            ToolExecutionResult result = awaited.get(5L, TimeUnit.SECONDS);

            assertEquals("completed", resolution.getStatus());
            assertEquals(PendingApprovalService.DECISION_MANUAL, resolution.getDecisionMode());
            assertEquals("completed", result.getStatus());
            assertEquals("approved", result.getOutput());
            assertEquals(1, executions.get());
            assertEquals("manual", result.getMeta().get("approval_mode"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void resolve_shouldReturnRejectedWithoutExecutingCommand() throws Exception {
        InMemoryPendingApprovalService service = new InMemoryPendingApprovalService();
        AtomicInteger executions = new AtomicInteger();
        service.registerPendingCommand(record(
                "pc-reject",
                "session-1",
                "npm test",
                true,
                () -> {
                    executions.incrementAndGet();
                    return new ToolExecutionResult("completed", "should-not-run", null, Map.of(), null, null, null, null, false);
                }
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ToolExecutionResult> awaited = executor.submit(() -> service.awaitDecision("pc-reject"));
            Thread.sleep(25L);

            PendingCommandResolutionResult resolution = service.resolve("pc-reject", true, PendingApprovalService.DECISION_MANUAL);
            ToolExecutionResult result = awaited.get(5L, TimeUnit.SECONDS);

            assertEquals("rejected", resolution.getStatus());
            assertEquals("rejected", result.getStatus());
            assertTrue(result.getOutput().contains("User rejected command"));
            assertEquals(0, executions.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void whitelist_shouldAutoApproveFollowingCommandInSameFamily() throws Exception {
        InMemoryPendingApprovalService service = new InMemoryPendingApprovalService();
        AtomicInteger executions = new AtomicInteger();

        service.registerPendingCommand(record(
                "pc-1",
                "session-1",
                "npm test",
                false,
                () -> {
                    executions.incrementAndGet();
                    return new ToolExecutionResult("completed", "first", null, Map.of(), null, null, null, null, false);
                }
        ));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ToolExecutionResult> awaited = executor.submit(() -> service.awaitDecision("pc-1"));
            Thread.sleep(25L);
            service.resolve("pc-1", false, PendingApprovalService.DECISION_WHITELIST);
            assertEquals("first", awaited.get(5L, TimeUnit.SECONDS).getOutput());
        } finally {
            executor.shutdownNow();
        }

        PendingCommandRegistration second = service.registerPendingCommand(record(
                "pc-2",
                "session-1",
                "npm run lint",
                false,
                () -> {
                    executions.incrementAndGet();
                    return new ToolExecutionResult("completed", "second", null, Map.of(), null, null, null, null, false);
                }
        ));

        assertFalse(second.getAwaitApproval());
        assertNotNull(second.getImmediateResult());
        assertEquals("completed", second.getImmediateResult().getStatus());
        assertEquals("second", second.getImmediateResult().getOutput());
        assertEquals(2, executions.get());
    }

    @Test
    void skipCurrentExecution_shouldSetExistingSkipFlags() {
        InMemoryPendingApprovalService service = new InMemoryPendingApprovalService();
        var flag = service.skipFlagFor("session-1");

        assertFalse(flag.get());
        service.skipCurrentExecution();
        assertTrue(flag.get());
    }

    private static PendingCommandRecord record(
            String id,
            String sessionId,
            String command,
            boolean strictApproval,
            java.util.function.Supplier<ToolExecutionResult> executor
    ) {
        return new PendingCommandRecord(
                id,
                command,
                "run command",
                "D:/Projects/Rikki",
                "D:/Projects/Rikki",
                sessionId,
                60_000L,
                "bash",
                "call-" + id,
                "msg-" + id,
                "high",
                strictApproval ? "destructive" : "restricted",
                LiteBashTool.commandFamily(command),
                strictApproval,
                strictApproval ? List.of("destructive") : List.of("requires approval"),
                "auto",
                executor
        );
    }
}
