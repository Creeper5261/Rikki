package com.zzf.codeagent.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzf.codeagent.core.tool.PendingChangesManager;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

final class ChatToolMetaExtractor {
    JsonNode extractToolMeta(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode metaNode = node.get("meta");
        if (metaNode == null || metaNode.isMissingNode() || metaNode.isNull()) {
            metaNode = node.get("metadata");
        }
        if (metaNode == null || metaNode.isMissingNode() || metaNode.isNull()) {
            return null;
        }
        return metaNode;
    }

    PendingChangesManager.PendingChange extractPendingChange(JsonNode metaNode, String workspaceRoot, String currentSessionId) {
        if (metaNode == null || metaNode.isMissingNode() || metaNode.isNull()) {
            return null;
        }
        JsonNode changeNode = metaNode.get("pending_change");
        if (changeNode == null || changeNode.isMissingNode() || changeNode.isNull()) {
            return null;
        }
        String id = firstNonBlank(changeNode.path("id").asText(""), UUID.randomUUID().toString());
        String path = firstNonBlank(changeNode.path("path").asText(""), changeNode.path("filePath").asText(""));
        if (path.isBlank()) {
            return null;
        }
        String type = firstNonBlank(changeNode.path("type").asText(""), "EDIT");
        String oldContent = firstNonBlank(changeNode.path("oldContent").asText(""), changeNode.path("old_content").asText(""));
        String newContent = firstNonBlank(changeNode.path("newContent").asText(""), changeNode.path("new_content").asText(""));
        String preview = firstNonBlank(changeNode.path("preview").asText(""));
        long ts = changeNode.path("timestamp").asLong(System.currentTimeMillis());
        String wsRoot = firstNonBlank(changeNode.path("workspaceRoot").asText(""), changeNode.path("workspace_root").asText(""), workspaceRoot);
        String sid = firstNonBlank(changeNode.path("sessionId").asText(""), changeNode.path("session_id").asText(""), currentSessionId);
        return new PendingChangesManager.PendingChange(id, path, type, oldContent, newContent, preview, ts, wsRoot, sid);
    }

    PendingChangesManager.PendingChange synthesizePendingChangeFromArgs(
            String toolName,
            JsonNode argsNode,
            String workspaceRoot,
            String currentSessionId,
            Predicate<String> isModificationTool,
            Predicate<String> isDeleteTool
    ) {
        if (!isModificationTool.test(toolName) || isDeleteTool.test(toolName) || argsNode == null || !argsNode.isObject()) {
            return null;
        }
        String path = firstNonBlank(argsNode.path("filePath").asText(""), argsNode.path("path").asText(""));
        if (path.isBlank()) {
            return null;
        }
        String newContent = firstNonBlank(
                argsNode.path("content").asText(""),
                argsNode.path("newContent").asText(""),
                argsNode.path("new_content").asText("")
        );
        String oldContent = firstNonBlank(argsNode.path("oldContent").asText(""), argsNode.path("old_content").asText(""));
        String type = "EDIT";
        if (!newContent.isBlank() && oldContent.isBlank()) {
            type = "CREATE";
        } else if (newContent.isBlank() && !oldContent.isBlank()) {
            type = "DELETE";
        }
        return new PendingChangesManager.PendingChange(
                UUID.randomUUID().toString(),
                path,
                type,
                oldContent,
                newContent,
                "",
                System.currentTimeMillis(),
                workspaceRoot,
                currentSessionId
        );
    }

    PendingCommandInfo extractPendingCommand(JsonNode metaNode, String workspaceRoot, String currentSessionId) {
        if (metaNode == null || metaNode.isMissingNode() || metaNode.isNull()) {
            return null;
        }
        JsonNode cmdNode = metaNode.get("pending_command");
        if (cmdNode == null || cmdNode.isMissingNode() || cmdNode.isNull()) {
            return null;
        }
        String id = firstNonBlank(cmdNode.path("id").asText(""));
        String command = firstNonBlank(cmdNode.path("command").asText(""));
        if (id.isBlank() || command.isBlank()) {
            return null;
        }
        PendingCommandInfo info = new PendingCommandInfo();
        info.id = id;
        info.command = command;
        info.description = firstNonBlank(cmdNode.path("description").asText(""));
        info.workdir = firstNonBlank(cmdNode.path("workdir").asText(""));
        info.workspaceRoot = firstNonBlank(cmdNode.path("workspaceRoot").asText(""), workspaceRoot);
        info.sessionId = firstNonBlank(cmdNode.path("sessionId").asText(""), currentSessionId);
        info.timeoutMs = cmdNode.path("timeoutMs").asLong(60000L);
        info.riskLevel = firstNonBlank(cmdNode.path("riskLevel").asText(""), "high");
        info.commandFamily = firstNonBlank(cmdNode.path("commandFamily").asText(""), extractCommandFamily(info.command));
        info.strictApproval = cmdNode.path("strictApproval").asBoolean(false);
        info.riskCategory = firstNonBlank(cmdNode.path("riskCategory").asText(""), info.strictApproval ? "destructive" : "restricted");
        JsonNode reasonsNode = cmdNode.path("reasons");
        if (reasonsNode.isArray()) {
            reasonsNode.forEach(n -> {
                String reason = n.asText("");
                if (!reason.isBlank()) {
                    info.reasons.add(reason);
                }
            });
        }
        if (!info.strictApproval) {
            String category = info.riskCategory == null ? "" : info.riskCategory.toLowerCase();
            info.strictApproval = "destructive".equals(category) || "strict".equals(category);
            if (!info.strictApproval && hasStrictReason(info.reasons)) {
                info.strictApproval = true;
            }
        }
        return info;
    }

    String extractMetaOutput(JsonNode metaNode, Function<JsonNode, String> prettyJson) {
        if (metaNode == null || metaNode.isMissingNode() || metaNode.isNull()) {
            return "";
        }
        String output = textOrJson(metaNode.get("output"), prettyJson);
        if (!output.isBlank()) {
            return output;
        }
        String stdout = textOrJson(metaNode.get("stdout"), prettyJson);
        if (!stdout.isBlank()) {
            return stdout;
        }
        String stderr = textOrJson(metaNode.get("stderr"), prettyJson);
        if (!stderr.isBlank()) {
            return stderr;
        }
        String result = textOrJson(metaNode.get("result"), prettyJson);
        if (!result.isBlank()) {
            return result;
        }
        return textOrJson(metaNode.get("content"), prettyJson);
    }

    private String textOrJson(JsonNode node, Function<JsonNode, String> prettyJson) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return firstNonBlank(node.asText(""));
        }
        String json = prettyJson == null ? "" : firstNonBlank(prettyJson.apply(node));
        if ("{}".equals(json) || "[]".equals(json)) {
            return "";
        }
        return json;
    }

    private boolean hasStrictReason(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return false;
        }
        for (String reason : reasons) {
            String normalizedReason = reason == null ? "" : reason.toLowerCase();
            if (normalizedReason.contains("delete")
                    || normalizedReason.contains("move/rename")
                    || normalizedReason.contains("destructive")) {
                return true;
            }
        }
        return false;
    }

    private String extractCommandFamily(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String[] tokens = command.trim().split("\\s+");
        for (String token : tokens) {
            String normalized = normalizeCommandFamilyToken(token);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String normalizeCommandFamilyToken(String token) {
        if (token == null) {
            return "";
        }
        String value = token.trim().toLowerCase();
        if (value.isBlank()) {
            return "";
        }
        if (value.contains("/") || value.contains("\\")) {
            int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
            if (slash >= 0 && slash + 1 < value.length()) {
                value = value.substring(slash + 1);
            }
        }
        if (value.endsWith(".exe") || value.endsWith(".cmd") || value.endsWith(".bat")) {
            int dot = value.lastIndexOf('.');
            if (dot > 0) {
                value = value.substring(0, dot);
            }
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.equals("sudo") || value.equals("env") || value.equals("command")) {
            return "";
        }
        return value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
