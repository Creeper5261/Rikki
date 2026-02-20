package com.zzf.rikki.controller;

import com.zzf.rikki.core.tool.PendingChangesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingChangesControllerTest {

    private final PendingChangesManager manager = PendingChangesManager.getInstance();
    private final PendingChangesController controller = new PendingChangesController();

    @AfterEach
    void tearDown() {
        manager.clear();
    }

    @Test
    void shouldNotFallbackToPathOnlyLookupWhenScopeMismatch(@TempDir Path workspace) {
        PendingChangesManager.PendingChange change = new PendingChangesManager.PendingChange(
                "chg-1",
                "src/Main.java",
                "CREATE",
                "",
                "class Main {}",
                "",
                System.currentTimeMillis(),
                workspace.toString(),
                "session-a"
        );
        manager.addChange(change);

        PendingChangesController.PendingChangeRequest request = new PendingChangesController.PendingChangeRequest();
        request.setPath("src/Main.java");
        request.setWorkspaceRoot(workspace.toString());
        request.setTraceId("session-b");
        request.setReject(false);

        Map<String, Object> response = controller.resolvePendingChange(request);
        assertEquals("error", response.get("status"));
        assertTrue(Files.notExists(workspace.resolve("src").resolve("Main.java")));
        assertTrue(manager.getById("chg-1").isPresent());
    }

    @Test
    void shouldRejectPathEscapingWorkspace(@TempDir Path workspace) {
        PendingChangesManager.PendingChange change = new PendingChangesManager.PendingChange(
                "chg-2",
                "../escape.txt",
                "CREATE",
                "",
                "escape",
                "",
                System.currentTimeMillis(),
                workspace.toString(),
                "session-c"
        );
        manager.addChange(change);

        PendingChangesController.PendingChangeRequest request = new PendingChangesController.PendingChangeRequest();
        request.setChangeId("chg-2");
        request.setWorkspaceRoot(workspace.toString());
        request.setTraceId("session-c");
        request.setReject(false);

        Map<String, Object> response = controller.resolvePendingChange(request);
        assertEquals("error", response.get("status"));
        Path escaped = workspace.getParent() == null
                ? workspace.resolve("..").resolve("escape.txt").normalize()
                : workspace.getParent().resolve("escape.txt").normalize();
        assertFalse(Files.exists(escaped));
        assertTrue(manager.getById("chg-2").isPresent());
    }
}
