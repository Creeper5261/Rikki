package com.zzf.codeagent.core.tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BashMetadataBuilder {

    Map<String, Object> buildPendingApprovalMetadata(
            String description,
            String command,
            BashRiskAssessor.Assessment risk,
            PendingCommandsManager.PendingCommand pending,
            String shell
    ) {
        Map<String, Object> pendingMeta = new HashMap<>();
        pendingMeta.put("description", description);
        pendingMeta.put("command", command);
        pendingMeta.put("risk_level", "high");
        pendingMeta.put("risk_reasons", risk.reasons);
        pendingMeta.put("risk_category", risk.riskCategory);
        pendingMeta.put("strict_approval", risk.strictApproval);
        pendingMeta.put("command_family", pending.commandFamily);
        pendingMeta.put("pending_command_id", pending.id);
        pendingMeta.put("pending_command", pending);
        pendingMeta.put("shell", shell);
        pendingMeta.put("requires_explicit_user_consent", true);
        pendingMeta.put("approval_type", risk.strictApproval ? "strict" : "policy_available");
        if (!risk.strictApproval) {
            pendingMeta.put("approval_options", List.of(
                    PendingCommandsManager.DECISION_MANUAL,
                    PendingCommandsManager.DECISION_WHITELIST,
                    PendingCommandsManager.DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE
            ));
        }
        return pendingMeta;
    }

    Map<String, Object> buildPermissionRequest(String command, String workspaceRoot) {
        Map<String, Object> permissionRequest = new HashMap<>();
        permissionRequest.put("permission", "bash");
        permissionRequest.put("patterns", new String[]{command});
        permissionRequest.put("always", new String[]{command.split(" ")[0] + "*"});
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("command", command);
        metadata.put("workspaceRoot", workspaceRoot);
        permissionRequest.put("metadata", metadata);
        return permissionRequest;
    }

    Map<String, Object> buildResultMetadata(
            String output,
            int exitCode,
            String description,
            String command,
            String originalCommand,
            String shell,
            boolean ideJavaOverrideApplied,
            Map<String, Object> selfHealMetadata,
            int maxMetadataLength
    ) {
        Map<String, Object> resultMetadata = new HashMap<>();
        resultMetadata.put("output", truncate(output, maxMetadataLength));
        resultMetadata.put("exit", exitCode);
        resultMetadata.put("description", description);
        resultMetadata.put("command", command);
        resultMetadata.put("original_command", originalCommand);
        resultMetadata.put("shell", shell);
        resultMetadata.put("ide_java_override_applied", ideJavaOverrideApplied);
        if (selfHealMetadata != null && !selfHealMetadata.isEmpty()) {
            resultMetadata.put("self_heal", selfHealMetadata);
        }
        return resultMetadata;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "\n\n...";
    }
}
