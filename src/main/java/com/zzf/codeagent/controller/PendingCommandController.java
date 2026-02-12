package com.zzf.codeagent.controller;

import com.zzf.codeagent.core.tool.PendingCommandsManager;
import com.zzf.codeagent.shell.ShellService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/agent/pending-command")
@RequiredArgsConstructor
public class PendingCommandController {

    private static final long DEFAULT_TIMEOUT_MS = 60_000L;
    private static final int MAX_OUTPUT_LENGTH = 12_000;

    private final ShellService shellService;

    @Data
    public static class PendingCommandRequest {
        private String traceId;
        private String workspaceRoot;
        private String commandId;
        private boolean reject;
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
            if (!PendingCommandsManager.getInstance().scopeMatches(pending, request.getWorkspaceRoot(), request.getTraceId())) {
                response.put("status", "error");
                response.put("error", "Pending command scope mismatch.");
                return response;
            }

            if (request.isReject()) {
                PendingCommandsManager.getInstance().remove(commandId);
                response.put("status", "rejected");
                response.put("output", "User rejected command, not executed: " + pending.command);
                response.put("command", pending.command);
                return response;
            }

            CommandResult result = executePendingCommand(pending, request.getWorkspaceRoot());
            PendingCommandsManager.getInstance().remove(commandId);

            response.put("status", result.exitCode == 0 ? "applied" : "error");
            response.put("output", result.output);
            response.put("exitCode", result.exitCode);
            response.put("command", pending.command);
            response.put("timeout", result.timedOut);
            return response;
        } catch (Exception e) {
            log.error("Failed to resolve pending command", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
            return response;
        }
    }

    private CommandResult executePendingCommand(
            PendingCommandsManager.PendingCommand pending,
            String requestedWorkspaceRoot
    ) throws Exception {
        String workspaceRoot = firstNonBlank(requestedWorkspaceRoot, pending.workspaceRoot, System.getProperty("user.dir"));
        String workdirRaw = firstNonBlank(pending.workdir, workspaceRoot);
        Path workdir = normalizePath(workdirRaw).toAbsolutePath().normalize();
        if (!workdir.toFile().exists()) {
            workdir = normalizePath(workspaceRoot).toAbsolutePath().normalize();
        }

        String command = pending.command == null ? "" : pending.command;
        long timeout = pending.timeoutMs > 0L ? pending.timeoutMs : DEFAULT_TIMEOUT_MS;
        String shell = shellService.acceptable();

        ProcessBuilder pb;
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") && shell.toLowerCase(Locale.ROOT).endsWith("cmd.exe")) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            pb = new ProcessBuilder(shell, "-c", command);
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
