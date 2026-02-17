package com.zzf.codeagent.core.tool;

import com.zzf.codeagent.shell.ShellService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class BashCommandExecutor {

    private final ShellService shellService;
    private final int maxMetadataLength;
    private final Map<String, Process> runningProcessesByCall = new ConcurrentHashMap<>();
    private final Map<String, String> runningCallSession = new ConcurrentHashMap<>();

    BashCommandExecutor(ShellService shellService, int maxMetadataLength) {
        this.shellService = shellService;
        this.maxMetadataLength = maxMetadataLength;
    }

    void cancel(String sessionID, String callID) {
        if (callID != null && !callID.isBlank()) {
            killRunningProcess(callID);
            return;
        }
        if (sessionID == null || sessionID.isBlank()) {
            return;
        }
        List<String> callIds = runningCallSession.entrySet().stream()
                .filter(entry -> sessionID.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        for (String id : callIds) {
            killRunningProcess(id);
        }
    }

    ExecutionResult execute(
            String command,
            String shell,
            Path workdir,
            long timeoutMs,
            String description,
            Tool.Context ctx,
            boolean streamMetadata
    ) throws Exception {
        StringBuilder output = new StringBuilder();
        boolean windows = isWindows();
        String lowerShell = shell == null ? "" : shell.toLowerCase();

        ProcessBuilder pb;
        if (windows && lowerShell.endsWith("cmd.exe")) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else if (isPowerShell(lowerShell)) {
            pb = new ProcessBuilder(shell, "-NoProfile", "-NonInteractive", "-Command", command);
        } else if (lowerShell.endsWith("bash") || lowerShell.endsWith("bash.exe")) {
            pb = new ProcessBuilder(shell, "-lc", command);
        } else {
            pb = new ProcessBuilder(shell, "-c", command);
        }

        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);

        long startedAt = System.currentTimeMillis();
        Process process = pb.start();
        String callID = ctx != null ? firstNonBlank(ctx.getCallID()) : "";
        String sessionID = ctx != null ? firstNonBlank(ctx.getSessionID()) : "";
        if (!callID.isBlank()) {
            runningProcessesByCall.put(callID, process);
            if (!sessionID.isBlank()) {
                runningCallSession.put(callID, sessionID);
            }
        }

        if (streamMetadata && ctx != null) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("output", "");
            meta.put("description", description);
            ctx.metadata(description, meta);
        }

        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        shellService.killTree(process);
                        output.append("\nCommand terminated due to interruption.");
                        return new ExecutionResult(command, -1, output.toString(), false, shell, System.currentTimeMillis() - startedAt);
                    }
                    output.append(line).append("\n");
                    if (streamMetadata && ctx != null && output.length() < maxMetadataLength) {
                        Map<String, Object> updateMeta = new HashMap<>();
                        updateMeta.put("output", output.toString());
                        updateMeta.put("description", description);
                        ctx.metadata(description, updateMeta);
                    }
                }
            }

            if (Thread.currentThread().isInterrupted()) {
                shellService.killTree(process);
                output.append("\nCommand terminated due to interruption.");
                return new ExecutionResult(command, -1, output.toString(), false, shell, System.currentTimeMillis() - startedAt);
            }

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                shellService.killTree(process);
                output.append("\nCommand terminated due to timeout (").append(timeoutMs).append(" ms).");
                return new ExecutionResult(command, -1, output.toString(), true, shell, System.currentTimeMillis() - startedAt);
            }

            return new ExecutionResult(
                    command,
                    process.exitValue(),
                    output.toString(),
                    false,
                    shell,
                    System.currentTimeMillis() - startedAt
            );
        } finally {
            if (!callID.isBlank()) {
                runningProcessesByCall.remove(callID, process);
                runningCallSession.remove(callID);
            }
        }
    }

    private void killRunningProcess(String callID) {
        if (callID == null || callID.isBlank()) {
            return;
        }
        Process process = runningProcessesByCall.remove(callID);
        runningCallSession.remove(callID);
        if (process != null && process.isAlive()) {
            try {
                shellService.killTree(process);
            } catch (Exception ignored) {
            }
        }
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

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static final class ExecutionResult {
        final String command;
        final int exitCode;
        final String output;
        final boolean timedOut;
        final String shell;
        final long durationMs;

        ExecutionResult(String command, int exitCode, String output, boolean timedOut, String shell, long durationMs) {
            this.command = command;
            this.exitCode = exitCode;
            this.output = output;
            this.timedOut = timedOut;
            this.shell = shell;
            this.durationMs = durationMs;
        }
    }
}
