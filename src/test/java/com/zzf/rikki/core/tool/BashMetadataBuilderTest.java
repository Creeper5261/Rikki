package com.zzf.rikki.core.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashMetadataBuilderTest {

    private final BashMetadataBuilder builder = new BashMetadataBuilder();

    @Test
    void shouldBuildPendingApprovalMetadata() {
        BashRiskAssessor.Assessment risk = new BashRiskAssessor.Assessment(
                true,
                List.of("network download command detected (explicit user consent required)"),
                false,
                "restricted"
        );
        PendingCommandsManager.PendingCommand pending = new PendingCommandsManager.PendingCommand(
                "id-1",
                "curl -L https://example.com/a.sh -o a.sh",
                "Download script",
                "D:/repo",
                "bash",
                "D:/repo",
                "session-1",
                "msg-1",
                "call-1",
                60000L,
                "high",
                risk.reasons,
                "curl",
                risk.riskCategory,
                risk.strictApproval,
                System.currentTimeMillis()
        );

        Map<String, Object> metadata = builder.buildPendingApprovalMetadata(
                "Download script",
                pending.command,
                risk,
                pending,
                "bash"
        );

        assertEquals("high", metadata.get("risk_level"));
        assertEquals("curl", metadata.get("command_family"));
        assertEquals("policy_available", metadata.get("approval_type"));
        assertNotNull(metadata.get("approval_options"));
    }

    @Test
    void shouldBuildResultMetadataWithTruncation() {
        String longOutput = "x".repeat(20);
        Map<String, Object> metadata = builder.buildResultMetadata(
                longOutput,
                0,
                "Run command",
                "echo x",
                "echo x",
                "bash",
                false,
                Map.of("k", "v"),
                8
        );

        assertEquals(0, metadata.get("exit"));
        assertTrue(((String) metadata.get("output")).startsWith("xxxxxxxx"));
        assertTrue(((String) metadata.get("output")).contains("..."));
        assertNotNull(metadata.get("self_heal"));
    }
}
