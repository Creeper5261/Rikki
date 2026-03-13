package com.zzf.rikki.idea.agent.compat

import com.zzf.rikki.idea.agent.tools.LiteBashTool

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class InMemoryPendingApprovalServiceTest {
    @Test
    fun resolve_should_complete_pending_command_on_manual_approve() = runBlocking {
        val service = InMemoryPendingApprovalService()
        val executions = AtomicInteger()
        val registration = service.registerPendingCommand(record("pc-approve", strictApproval = true) {
            executions.incrementAndGet()
            ToolExecutionResult("completed", "approved", null, emptyMap<String, Any>(), null, null, null, null, false)
        })

        assertTrue(registration.awaitApproval)
        val awaited = async(start = CoroutineStart.DEFAULT) { service.awaitDecision("pc-approve") }
        delay(25)

        val resolution = service.resolve("pc-approve", false, PendingApprovalService.DECISION_MANUAL)
        val result = awaited.await()

        assertEquals("completed", resolution.status)
        assertEquals(PendingApprovalService.DECISION_MANUAL, resolution.decisionMode)
        assertEquals("completed", result.status)
        assertEquals("approved", result.output)
        assertEquals(1, executions.get())
        assertEquals("manual", result.meta["approval_mode"])
    }

    @Test
    fun resolve_should_return_rejected_without_executing_command() = runBlocking {
        val service = InMemoryPendingApprovalService()
        val executions = AtomicInteger()
        service.registerPendingCommand(record("pc-reject", strictApproval = true) {
            executions.incrementAndGet()
            ToolExecutionResult("completed", "should-not-run", null, emptyMap<String, Any>(), null, null, null, null, false)
        })

        val awaited = async(start = CoroutineStart.DEFAULT) { service.awaitDecision("pc-reject") }
        delay(25)

        val resolution = service.resolve("pc-reject", true, PendingApprovalService.DECISION_MANUAL)
        val result = awaited.await()

        assertEquals("rejected", resolution.status)
        assertEquals("rejected", result.status)
        assertTrue(result.output.contains("User rejected command"))
        assertEquals(0, executions.get())
    }

    @Test
    fun whitelist_should_auto_approve_following_command_in_same_family() = runBlocking {
        val service = InMemoryPendingApprovalService()
        val executions = AtomicInteger()

        service.registerPendingCommand(record("pc-1", sessionId = "session-1", command = "npm test") {
            executions.incrementAndGet()
            ToolExecutionResult("completed", "first", null, emptyMap<String, Any>(), null, null, null, null, false)
        })
        val awaited = async(start = CoroutineStart.DEFAULT) { service.awaitDecision("pc-1") }
        delay(25)
        service.resolve("pc-1", false, PendingApprovalService.DECISION_WHITELIST)
        assertEquals("first", awaited.await().output)

        val second = service.registerPendingCommand(record("pc-2", sessionId = "session-1", command = "npm run lint") {
            executions.incrementAndGet()
            ToolExecutionResult("completed", "second", null, emptyMap<String, Any>(), null, null, null, null, false)
        })

        assertFalse(second.awaitApproval)
        assertNotNull(second.immediateResult)
        assertEquals("completed", second.immediateResult?.status)
        assertEquals("second", second.immediateResult?.output)
        assertEquals(2, executions.get())
    }

    @Test
    fun skipCurrentExecution_should_set_existing_skip_flags() {
        val service = InMemoryPendingApprovalService()
        val flag = service.skipFlagFor("session-1")

        assertFalse(flag.get())
        service.skipCurrentExecution()
        assertTrue(flag.get())
    }

    private fun record(
        id: String,
        sessionId: String = "session-1",
        command: String = "npm test",
        strictApproval: Boolean = false,
        executor: () -> ToolExecutionResult
    ): PendingCommandRecord {
        return PendingCommandRecord(
            id,
            command,
            "run command",
            "D:/Projects/Rikki",
            "D:/Projects/Rikki",
            sessionId,
            60_000,
            "bash",
            "call-$id",
            "msg-$id",
            "high",
            if (strictApproval) "destructive" else "restricted",
            LiteBashTool.commandFamily(command),
            strictApproval,
            if (strictApproval) listOf("destructive") else listOf("requires approval"),
            "auto",
            executor
        )
    }
}
