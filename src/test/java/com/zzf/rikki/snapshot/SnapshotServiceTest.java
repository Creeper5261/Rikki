package com.zzf.rikki.snapshot;

import com.zzf.rikki.project.ProjectContext;
import com.zzf.rikki.shell.ShellService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnapshotServiceTest {

    private ProjectContext projectContext;
    private ShellService shellService;
    private SnapshotService snapshotService;

    @BeforeEach
    void setUp() {
        projectContext = Mockito.mock(ProjectContext.class);
        shellService = Mockito.mock(ShellService.class);
        snapshotService = new SnapshotService(projectContext, shellService);
    }

    @Test
    void testTrackNotGit() {
        when(projectContext.isGit()).thenReturn(false);
        String hash = snapshotService.track();
        assertNull(hash);
    }

    @Test
    void testTrackGit() {
        when(projectContext.isGit()).thenReturn(true);
        when(projectContext.getDirectory()).thenReturn("/tmp/project");
        when(projectContext.getWorktree()).thenReturn("/tmp/project");
        
        ShellService.ExecuteResult result = Mockito.mock(ShellService.ExecuteResult.class);
        when(result.text()).thenReturn("tree_hash_123");
        when(shellService.execute(anyString(), anyString(), anyString())).thenReturn(result);
        
        String hash = snapshotService.track();
        assertEquals("tree_hash_123", hash);
        verify(shellService, atLeastOnce()).execute(anyString(), anyString(), anyString());
    }
}
