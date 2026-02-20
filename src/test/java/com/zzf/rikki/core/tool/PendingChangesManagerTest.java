package com.zzf.rikki.core.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingChangesManagerTest {

    private final PendingChangesManager manager = PendingChangesManager.getInstance();

    @AfterEach
    void tearDown() {
        manager.clear();
    }

    @Test
    void shouldMatchWorkspaceRootsWithWindowsAndMsysStyle() {
        PendingChangesManager.PendingChange change = new PendingChangesManager.PendingChange(
                "id_1",
                "src/Main.java",
                "EDIT",
                "old",
                "new",
                null,
                System.currentTimeMillis(),
                "D:\\plugin_dev\\code-agent",
                "session_1"
        );

        manager.addChange(change);

        boolean found = manager.getPendingChange("src/Main.java", "/d/plugin_dev/code-agent", "session_1").isPresent();
        assertTrue(found);
    }

    @Test
    void shouldMergeChangesWithSameNormalizedScope() {
        PendingChangesManager.PendingChange first = new PendingChangesManager.PendingChange(
                "id_1",
                "src/Main.java",
                "EDIT",
                "old",
                "mid",
                null,
                System.currentTimeMillis(),
                "D:\\plugin_dev\\code-agent",
                "session_1"
        );
        PendingChangesManager.PendingChange second = new PendingChangesManager.PendingChange(
                "id_2",
                "src/Main.java",
                "EDIT",
                "mid",
                "new",
                null,
                System.currentTimeMillis(),
                "/d/plugin_dev/code-agent",
                "session_1"
        );

        manager.addChange(first);
        manager.addChange(second);

        assertEquals(1, manager.getChanges().size());
    }

    @Test
    void shouldMatchBySessionWhenWorkspaceFormatDiffers() {
        PendingChangesManager.PendingChange change = new PendingChangesManager.PendingChange(
                "id_3",
                "src/App.java",
                "DELETE",
                "old",
                null,
                null,
                System.currentTimeMillis(),
                "D:\\plugin_dev\\code-agent",
                "session_scope_only"
        );
        manager.addChange(change);

        PendingChangesManager.PendingChange resolved = manager
                .getPendingChange("src/App.java", "Z:\\wrong\\workspace", "session_scope_only")
                .orElse(null);
        assertNotNull(resolved);
    }
}
