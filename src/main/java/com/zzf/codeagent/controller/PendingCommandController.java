package com.zzf.codeagent.controller;

import com.zzf.codeagent.core.tool.PendingCommandsManager;
import com.zzf.codeagent.shell.ShellService;
import com.zzf.codeagent.session.SessionService;
import com.zzf.codeagent.session.model.MessageV2;
import com.zzf.codeagent.session.model.PromptPart;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

@Slf4j
@RestController
@RequestMapping("/api/agent/pending-command")
@RequiredArgsConstructor
public class PendingCommandController {

    private static final long DEFAULT_TIMEOUT_MS = 60_000L;
    private static final int MAX_OUTPUT_LENGTH = 12_000;

    private final ShellService shellService;
    private final SessionService sessionService;

    @Data
    public static class PendingCommandRequest {
        private String traceId;
        private String workspaceRoot;
        private String commandId;
        private boolean reject;
        private String decisionMode;
    }

    @PostMapping
    public Map<String, Object> resolvePendingCommand(@RequestBody PendingCommandRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String commandId = request.getCommandId();
            if (commandId == null || commandId.isBlank()) {
                response.put("status", "error");
                response.put("error", "commandId is required");
                return response;
            }

            Optional<PendingCommandsManager.PendingCommand> pendingOpt =
                    PendingCommandsManager.getInstance().get(commandId);
            if (pendingOpt.isEmpty()) {
                response.put("status", "error");
                response.put("error", "Pending command not found: " + commandId);
                return response;
            }

            PendingCommandsManager.PendingCommand pending = pendingOpt.get();
            if (request.getTraceId() == null || request.getTraceId().isBlank()) {
                response.put("status", "error");
                response.put("error", "traceId is required");
                return response;
            }
            if (!PendingCommandsManager.getInstance().scopeMatches(pending, pending.workspaceRoot, request.getTraceId())) {
                response.put("status", "error");
                response.put("error", "Pending command scope mismatch.");
                return response;
            }

            if (request.isReject()) {
                PendingCommandsManager.getInstance().remove(commandId);
                String output = "User rejected command, not executed: " + pending.command;
                updatePendingToolPart(
                        pending,
                        "rejected",
                        output,
                        "",
                        null,
                        false,
                        PendingCommandsManager.DECISION_MANUAL
                );
                response.put("status", "rejected");
                response.put("output", output);
                response.put("command", pending.command);
                return response;
            }

            String decisionMode = PendingCommandsManager.normalizeDecisionMode(request.getDecisionMode());
            PendingCommandsManager.getInstance().applyApprovalDecision(pending, decisionMode);
            CommandResult result = executePendingCommand(pending);
            PendingCommandsManager.getInstance().remove(commandId);
            String status = result.exitCode == 0 ? "applied" : "error";
            updatePendingToolPart(
                    pending,
                    result.exitCode == 0 ? "completed" : "error",
                    result.output,
                    result.exitCode == 0 ? "" : "Command failed with exit code " + result.exitCode,
                    result.exitCode,
                    result.timedOut,
                    decisionMode
            );

            response.put("status", status);
            response.put("output", result.output);
            response.put("exitCode", result.exitCode);
            response.put("command", pending.command);
            response.put("timeout", result.timedOut);
            response.put("decisionMode", decisionMode);
            return response;
        } catch (Exception e) {
            log.error("Failed to resolve pending command", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
            return response;
        }
    }

    private CommandResult executePendingCommand(PendingCommandsManager.PendingCommand pending) throws Exception {
        String workspaceRoot = firstNonBlank(pending.workspaceRoot, System.getProperty("user.dir"));
        Path workspacePath = normalizePath(workspaceRoot).toAbsolutePath().normalize();
        String workdirRaw = firstNonBlank(pending.workdir, workspaceRoot);
        Path workdir = normalizePath(workdirRaw).toAbsolutePath().normalize();
        if (!workdir.startsWith(workspacePath)) {
            workdir = workspacePath;
        }
        if (!Files.exists(workdir)) {
            workdir = workspacePath;
        }
        if (!Files.exists(workspacePath)) {
            throw new IllegalArgumentException("Pending command workspace root does not exist: " + workspacePath);
        }

        String command = pending.command == null ? "" : pending.command;
        long timeout = pending.timeoutMs > 0L ? pending.timeoutMs : DEFAULT_TIMEOUT_MS;
        String shell = firstNonBlank(pending.shell, shellService.acceptable());
        shell = shell == null || shell.isBlank() ? (isWindows() ? "cmd.exe" : "/bin/sh") : shell;
        String preparedCommand = normalizeCommandForShell(command, shell);
        String lowerShell = shell.toLowerCase(Locale.ROOT);

        ProcessBuilder pb;
        if (isWindows() && lowerShell.endsWith("cmd.exe")) {
            pb = new ProcessBuilder("cmd.exe", "/c", preparedCommand);
        } else if (isPowerShell(lowerShell)) {
            pb = new ProcessBuilder(shell, "-NoProfile", "-NonInteractive", "-Command", preparedCommand);
        } else if (lowerShell.endsWith("bash") || lowerShell.endsWith("bash.exe")) {
            pb = new ProcessBuilder(shell, "-lc", preparedCommand);
        } else {
            pb = new ProcessBuilder(shell, "-c", preparedCommand);
        }
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        boolean truncated = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!truncated) {
                    if (output.length() + line.length() + 1 <= MAX_OUTPUT_LENGTH) {
                        output.append(line).append('\n');
                    } else {
                        int remain = Math.max(0, MAX_OUTPUT_LENGTH - output.length());
                        if (remain > 0) {
                            output.append(line, 0, Math.min(remain, line.length()));
                        }
                        output.append("\n...\n");
                        truncated = true;
                    }
                }
            }
        }

        boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
        if (!finished) {
            shellService.killTree(process);
            if (output.length() < MAX_OUTPUT_LENGTH) {
                output.append("\nCommand terminated due to timeout (").append(timeout).append(" ms).");
            }
            return new CommandResult(-1, output.toString().trim(), true);
        }
        return new CommandResult(process.exitValue(), output.toString().trim(), false);
    }

    private Path normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return Paths.get(".");
        }
        String normalized = rawPath.trim();
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        if (windows && normalized.matches("^/[a-zA-Z]/.*")) {
            char drive = Character.toUpperCase(normalized.charAt(1));
            normalized = drive + ":" + normalized.substring(2);
        }
        return Paths.get(normalized);
    }

    private void updatePendingToolPart(
            PendingCommandsManager.PendingCommand pending,
            String status,
            String output,
            String error,
            Integer exitCode,
            boolean timedOut,
            String decisionMode
    ) {
        if (pending == null) {
            return;
        }
        MessageV2.ToolPart part = findToolPart(pending);
        if (part == null) {
            return;
        }
        if (part.getState() == null) {
            part.setState(new MessageV2.ToolState());
        }
        MessageV2.ToolState state = part.getState();
        state.setStatus(status);
        state.setOutput(output == null ? "" : output);
        state.setError(error == null ? "" : error);
        Map<String, Object> metadata = state.getMetadata() == null
                ? new HashMap<>()
                : new HashMap<>(state.getMetadata());
        metadata.put("command", firstNonBlank(pending.command));
        metadata.put("exit", exitCode);
        metadata.put("timeout", timedOut);
        metadata.put("approval_result", status);
        metadata.put("resolved_by_user", true);
        metadata.put("approval_mode", PendingCommandsManager.normalizeDecisionMode(decisionMode));
        state.setMetadata(metadata);
        if (state.getTime() == null) {
            state.setTime(new MessageV2.ToolState.TimeInfo());
        }
        state.getTime().setEnd(System.currentTimeMillis());
        sessionService.updatePart(part);
    }

    private MessageV2.ToolPart findToolPart(PendingCommandsManager.PendingCommand pending) {
        if (pending == null) {
            return null;
        }
        if (pending.messageId != null && !pending.messageId.isBlank()) {
            MessageV2.WithParts message = sessionService.getMessage(pending.messageId);
            MessageV2.ToolPart matched = findToolPartInMessage(message, tool -> matchesCall(tool, pending.callId, pending.id));
            if (matched != null) {
                return matched;
            }
        }
        if (pending.sessionId == null || pending.sessionId.isBlank()) {
            return null;
        }
        java.util.List<MessageV2.WithParts> messages = sessionService.getMessages(pending.sessionId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageV2.ToolPart matched = findToolPartInMessage(messages.get(i), tool -> matchesCall(tool, pending.callId, pending.id));
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    private MessageV2.ToolPart findToolPartInMessage(MessageV2.WithParts message, Predicate<MessageV2.ToolPart> matcher) {
        if (message == null || message.getParts() == null || matcher == null) {
            return null;
        }
        for (PromptPart part : message.getParts()) {
            if (part instanceof MessageV2.ToolPart toolPart && matcher.test(toolPart)) {
                return toolPart;
            }
        }
        return null;
    }

    private boolean matchesCall(MessageV2.ToolPart part, String callId, String pendingId) {
        if (part == null) {
            return false;
        }
        if (callId != null && !callId.isBlank()) {
            return callId.equals(part.getCallID());
        }
        if (pendingId != null
                && part.getState() != null
                && part.getState().getMetadata() != null) {
            Object value = part.getState().getMetadata().get("pending_command_id");
            if (value != null && pendingId.equals(String.valueOf(value))) {
                return true;
            }
        }
        return false;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String normalizeCommandForShell(String command, String shell) {
        String normalized = command == null ? "" : command
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        String lowerShell = shell == null ? "" : shell.toLowerCase(Locale.ROOT);
        if (normalized.contains("\n")) {
            if (isPowerShell(lowerShell)) {
                normalized = normalized.replace("\n", "; ");
            } else if (isWindows() && lowerShell.endsWith("cmd.exe")) {
                normalized = normalized.replace("\n", " && ");
            } else {
                normalized = normalized.replace("\n", " && ");
            }
        }
        return normalized;
    }

    private boolean isPowerShell(String lowerShell) {
        if (lowerShell == null || lowerShell.isBlank()) {
            return false;
        }
        return lowerShell.endsWith("powershell.exe")
                || lowerShell.endsWith("pwsh.exe")
                || lowerShell.contains("\\powershell")
                || lowerShell.endsWith("powershell")
                || lowerShell.endsWith("pwsh");
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

    private static class CommandResult {
        final int exitCode;
        final String output;
        final boolean timedOut;

        CommandResult(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output;
            this.timedOut = timedOut;
        }
    }
}
