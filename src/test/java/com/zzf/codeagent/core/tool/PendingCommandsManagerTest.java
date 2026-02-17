package com.zzf.codeagent.core.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingCommandsManagerTest {

    private final PendingCommandsManager manager = PendingCommandsManager.getInstance();

    @AfterEach
    void tearDown() {
        manager.clear();
    }

    @Test
    void shouldRequireWorkspaceAndSessionScopeToMatch() {
        PendingCommandsManager.PendingCommand command = new PendingCommandsManager.PendingCommand(
                "cmd-1",
                "rm src/Main.java",
                "delete file",
                "D:\\plugin_dev\\code-agent",
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "D:\\plugin_dev\\code-agent",
                "session-1",
                "message-1",
                "call-1",
                60000L,
                "high",
                List.of("delete"),
                "rm",
                "destructive",
                true,
                System.currentTimeMillis()
        );
        manager.add(command);

        assertTrue(manager.hasPendingForScope("Z:\\wrong\\workspace", "session-1"));
        assertFalse(manager.scopeMatches(command, "Z:\\wrong\\workspace", "session-1"));
        assertTrue(manager.scopeMatches(command, "D:\\plugin_dev\\code-agent", "session-1"));
    }

    @Test
    void shouldAutoApproveWhitelistedCommandFamily() {
        PendingCommandsManager.PendingCommand command = new PendingCommandsManager.PendingCommand(
                "cmd-2",
                "curl https://example.com",
                "download",
                "D:\\plugin_dev\\code-agent",
                "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "D:\\plugin_dev\\code-agent",
                "session-2",
                "message-2",
                "call-2",
                60000L,
                "high",
                List.of("network download"),
                "curl",
                "restricted",
                false,
                System.currentTimeMillis()
        );
        manager.applyApprovalDecision(command, PendingCommandsManager.DECISION_WHITELIST);
        assertTrue(manager.shouldAutoApprove("session-2", "curl", false));
        assertFalse(manager.shouldAutoApprove("session-2", "wget", false));
    }

    @Test
    void shouldNeverAutoApproveStrictCommand() {
        PendingCommandsManager.PendingCommand command = new PendingCommandsManager.PendingCommand(
                "cmd-3",
                "rm -rf src",
                "delete",
                "D:\\plugin_dev\\code-agent",
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "D:\\plugin_dev\\code-agent",
                "session-3",
                "message-3",
                "call-3",
                60000L,
                "high",
                List.of("recursive delete"),
                "rm",
                "destructive",
                true,
                System.currentTimeMillis()
        );
        manager.applyApprovalDecision(command, PendingCommandsManager.DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE);
        assertFalse(manager.shouldAutoApprove("session-3", "rm", true));
        assertFalse(manager.shouldAutoApprove("session-3", "rm", false));
    }
}
