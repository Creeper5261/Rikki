package com.zzf.rikki.idea.agent.compat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CommandRunner {
    public static final long DEFAULT_TIMEOUT_MS = 60_000L;
    private static final int MAX_OUTPUT = 8_000;
    private static final long POLL_INTERVAL_MS = 200L;
    private static final Set<String> SUPPORTED_SHELLS = Set.of("auto", "bash", "powershell", "cmd");

    public CommandRunnerResult run(
            String command,
            String workspaceRoot,
            String workdir,
            long timeoutMs,
            String shellPref,
            AtomicBoolean skipFlag
    ) {
        String normalizedShell = shellPref == null || shellPref.isBlank() ? "auto" : shellPref.trim().toLowerCase();
        if (!SUPPORTED_SHELLS.contains(normalizedShell)) {
            throw new IllegalArgumentException("shell must be one of: auto, bash, powershell, cmd");
        }
        File resolvedWorkdir = resolveWorkdir(workspaceRoot, workdir);
        List<ShellSpec> candidates = resolveShellCandidates(normalizedShell);
        List<String> startupErrors = new ArrayList<>();
        for (ShellSpec shell : candidates) {
            ProcessBuilder processBuilder = new ProcessBuilder();
            List<String> commandLine = new ArrayList<>(shell.argvPrefix);
            commandLine.add(command);
            processBuilder.command(commandLine);
            processBuilder.directory(resolvedWorkdir);
            processBuilder.redirectErrorStream(true);
            Map<String, String> environment = processBuilder.environment();
            environment.putAll(System.getenv());
            environment.put("PYTHONIOENCODING", "utf-8");
            if ("bash".equals(shell.name)) {
                environment.put("LANG", "en_US.UTF-8");
                environment.put("LC_ALL", "en_US.UTF-8");
            }
            Process process;
            try {
                process = processBuilder.start();
            } catch (Exception e) {
                startupErrors.add(shell.name + ": " + (e.getMessage() == null ? "failed to start" : e.getMessage()));
                continue;
            }
            return executeProcess(process, timeoutMs, skipFlag, shell.name);
        }
        String shellNames = String.join(", ", candidates.stream().map(spec -> spec.name).toList());
        String detail = startupErrors.isEmpty() ? "no startup error details" : String.join("; ", startupErrors);
        return new CommandRunnerResult(
                "No usable shell found (requested=" + normalizedShell + ", tried=" + shellNames + "): " + detail,
                -1,
                false,
                false,
                normalizedShell
        );
    }

    public String formatOutput(String command, long timeoutMs, CommandRunnerResult result) {
        String prefix = "[shell=" + result.getShell() + "]";
        String output = result.getOutput();
        String truncated = output.length() > MAX_OUTPUT ? output.substring(0, MAX_OUTPUT) + "\n\n...(truncated)" : output;
        if (result.getSkipped()) {
            return (prefix + " (Skipped by user - command was interrupted)\n" + truncated).trim();
        }
        if (result.getTimedOut()) {
            return (prefix + " (Command timed out after " + timeoutMs + "ms)\n" + truncated).trim();
        }
        if (result.getExitCode() != 0) {
            return (prefix + " Command failed with exit code " + result.getExitCode() + ": " + command + "\n" + truncated).trim();
        }
        if (truncated.isBlank()) {
            return prefix + " (no output)";
        }
        return (prefix + "\n" + truncated).trim();
    }

    private File resolveWorkdir(String workspaceRoot, String requested) {
        File dir = new File(requested == null || requested.isBlank() ? workspaceRoot : requested);
        if (!dir.isAbsolute()) {
            dir = new File(workspaceRoot, requested == null ? "" : requested);
        }
        return dir.isDirectory() ? dir : new File(workspaceRoot);
    }

    private List<ShellSpec> resolveShellCandidates(String shellPref) {
        return switch (shellPref) {
            case "bash" -> List.of(new ShellSpec("bash", List.of("bash", "-c")));
            case "powershell" -> List.of(new ShellSpec("powershell", List.of("powershell", "-NoProfile", "-NonInteractive", "-Command")));
            case "cmd" -> List.of(new ShellSpec("cmd", List.of("cmd", "/c")));
            default -> List.of(
                    new ShellSpec("bash", List.of("bash", "-c")),
                    new ShellSpec("powershell", List.of("powershell", "-NoProfile", "-NonInteractive", "-Command")),
                    new ShellSpec("cmd", List.of("cmd", "/c"))
            );
        };
    }

    private CommandRunnerResult executeProcess(Process process, long timeoutMs, AtomicBoolean skipFlag, String shell) {
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try {
                process.getInputStream().transferTo(outputBuffer);
            } catch (Exception ignored) {
            }
        }, "rikki-command-reader");
        reader.setDaemon(true);
        reader.start();

        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean timedOut = false;
        boolean skipped = false;
        while (process.isAlive()) {
            if (skipFlag != null && skipFlag.get()) {
                skipped = true;
                process.destroyForcibly();
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true;
                process.destroyForcibly();
                break;
            }
            try {
                process.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                skipped = true;
                process.destroyForcibly();
                break;
            }
        }
        try {
            reader.join(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int exitCode;
        try {
            exitCode = process.exitValue();
        } catch (Exception ignored) {
            exitCode = -1;
        }
        return new CommandRunnerResult(outputBuffer.toString(java.nio.charset.StandardCharsets.UTF_8), exitCode, timedOut, skipped, shell);
    }

    private record ShellSpec(String name, List<String> argvPrefix) {
    }
}
