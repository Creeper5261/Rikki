package com.zzf.rikki.core.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashRiskAssessorTest {

    private final BashRiskAssessor assessor = new BashRiskAssessor();

    @Test
    void shouldMarkWorkspaceTraversalAsStrict(@TempDir Path workspace) {
        BashRiskAssessor.Assessment assessment = assessor.assess("cd .. && ls -la", workspace.toString());
        assertTrue(assessment.requiresApproval);
        assertTrue(assessment.strictApproval);
        assertEquals("workspace_boundary", assessment.riskCategory);
        assertTrue(assessment.reasons.stream().anyMatch(reason -> reason.contains("parent-directory traversal")));
    }

    @Test
    void shouldDetectDownloadAsRestricted(@TempDir Path workspace) {
        BashRiskAssessor.Assessment assessment = assessor.assess("curl -L https://example.com/a.sh -o a.sh", workspace.toString());
        assertTrue(assessment.requiresApproval);
        assertFalse(assessment.strictApproval);
        assertEquals("restricted", assessment.riskCategory);
        assertTrue(assessment.reasons.stream().anyMatch(reason -> reason.contains("download")));
    }

    @Test
    void shouldExtractCommandFamilySkippingShellWrapper() {
        assertEquals("git", assessor.extractCommandFamily("powershell.exe -Command git status"));
        assertEquals("rm", assessor.extractCommandFamily("bash -lc rm -rf src"));
    }
}
