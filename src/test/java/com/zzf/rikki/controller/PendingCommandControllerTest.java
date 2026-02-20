package com.zzf.rikki.controller;

import com.zzf.rikki.core.tool.PendingCommandsManager;
import com.zzf.rikki.session.SessionService;
import com.zzf.rikki.shell.ShellService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PendingCommandControllerTest {

    private final PendingCommandsManager manager = PendingCommandsManager.getInstance();
    private static final String SESSION_ID = "session-scope";

    @AfterEach
    void tearDown() {
        manager.clearPendingForSession(SESSION_ID);
    }

    @Test
    void shouldExecuteUsingPendingScopeNotRequestedWorkspace(@TempDir Path workspaceA, @TempDir Path workspaceB) throws Exception {
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getMessages(anyString())).thenReturn(new ArrayList<>());
        when(sessionService.getMessage(anyString())).thenReturn(null);

        PendingCommandController controller = new PendingCommandController(new ShellService(), sessionService);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String shell = windows ? "cmd.exe" : "/bin/sh";
        String command = windows ? "echo scoped>scope_check.txt" : "echo scoped > scope_check.txt";

        PendingCommandsManager.PendingCommand pending = new PendingCommandsManager.PendingCommand(
                UUID.randomUUID().toString(),
                command,
                "create marker file",
                workspaceA.toString(),
                shell,
                workspaceA.toString(),
                SESSION_ID,
                "message-1",
                "call-1",
                60_000L,
                "high",
                List.of("test"),
                windows ? "cmd" : "sh",
                "restricted",
                false,
                System.currentTimeMillis()
        );
        manager.add(pending);

        PendingCommandController.PendingCommandRequest request = new PendingCommandController.PendingCommandRequest();
        request.setTraceId(SESSION_ID);
        request.setWorkspaceRoot(workspaceB.toString());
        request.setCommandId(pending.id);
        request.setReject(false);
        request.setDecisionMode(PendingCommandsManager.DECISION_MANUAL);

        Map<String, Object> response = controller.resolvePendingCommand(request);

        assertEquals("applied", response.get("status"));
        assertTrue(Files.exists(workspaceA.resolve("scope_check.txt")));
        assertTrue(Files.notExists(workspaceB.resolve("scope_check.txt")));
    }
}
