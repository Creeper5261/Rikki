package com.zzf.rikki.idea.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzf.rikki.idea.agent.compat.CommandRunner;
import com.zzf.rikki.idea.agent.compat.CommandRunnerResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class LiteBashTool {
    public static final Companion Companion = new Companion();
    private final CommandRunner runner = new CommandRunner();

    public String execute(JsonNode args, String workspaceRoot, AtomicBoolean skipFlag) {
        CommandRunnerResult detail = executeDetailed(args, workspaceRoot, skipFlag);
        long timeoutMs = args.path("timeout").asLong(CommandRunner.DEFAULT_TIMEOUT_MS);
        if (timeoutMs <= 0L) {
            timeoutMs = CommandRunner.DEFAULT_TIMEOUT_MS;
        }
        return formatResult(args.path("command").asText(""), timeoutMs, detail);
    }

    public CommandRunnerResult executeDetailed(JsonNode args, String workspaceRoot, AtomicBoolean skipFlag) {
        String command = args.path("command").asText("");
        if (command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        String workdir = args.path("workdir").asText(workspaceRoot);
        if (workdir.isBlank()) {
            workdir = workspaceRoot;
        }
        long timeoutMs = args.path("timeout").asLong(CommandRunner.DEFAULT_TIMEOUT_MS);
        if (timeoutMs <= 0L) {
            timeoutMs = CommandRunner.DEFAULT_TIMEOUT_MS;
        }
        String shell = args.path("shell").asText("auto").trim().toLowerCase(Locale.ROOT);
        if (shell.isBlank()) {
            shell = "auto";
        }
        return runner.run(command, workspaceRoot, workdir, timeoutMs, shell, skipFlag);
    }

    public String formatResult(String command, long timeoutMs, CommandRunnerResult result) {
        return runner.formatOutput(command, timeoutMs, result);
    }

    public static String commandFamily(String command) {
        return Companion.commandFamily(command);
    }

    public static final class Companion {
        private static final List<Map.Entry<String, String>> RISK_PATTERNS = List.of(
                Map.entry("sudo ", "requires elevated privileges"),
                Map.entry("su -", "switches user identity"),
                Map.entry("su root", "switches user identity"),
                Map.entry("rm -rf", "destructive file removal"),
                Map.entry("rm -fr", "destructive file removal"),
                Map.entry("rm -r ", "recursive deletion"),
                Map.entry("rm -f /", "targets filesystem root"),
                Map.entry("mkfs", "formats a filesystem"),
                Map.entry("dd if=", "raw disk write"),
                Map.entry("| bash", "pipes remote script into shell"),
                Map.entry("| sh ", "pipes remote script into shell"),
                Map.entry("| zsh ", "pipes remote script into shell"),
                Map.entry("| fish ", "pipes remote script into shell"),
                Map.entry("chmod 777", "grants unsafe world-writable permissions"),
                Map.entry("chmod -R ", "recursively changes permissions"),
                Map.entry("> /dev/", "writes to device path"),
                Map.entry("/dev/sd", "targets block device"),
                Map.entry("/dev/hd", "targets block device"),
                Map.entry("/dev/nvme", "targets block device"),
                Map.entry(":(){ :|:& };:", "fork bomb")
        );

        public boolean isHighRiskCommand(String command) {
            return !detectRiskReasons(command).isEmpty();
        }

        public List<String> detectRiskReasons(String command) {
            String normalized = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
            List<String> reasons = new ArrayList<>();
            for (Map.Entry<String, String> pattern : RISK_PATTERNS) {
                if (normalized.contains(pattern.getKey()) && !reasons.contains(pattern.getValue())) {
                    reasons.add(pattern.getValue());
                }
            }
            return reasons;
        }

        public boolean isStrictApprovalCommand(String command) {
            String normalized = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
            for (String pattern : List.of("rm -rf", "rm -fr", "rm -r ", "rm -f /", "mkfs", "dd if=", "> /dev/", "/dev/sd", "/dev/hd", "/dev/nvme", ":(){ :|:& };:")) {
                if (normalized.contains(pattern)) {
                    return true;
                }
            }
            return false;
        }

        public String commandFamily(String command) {
            String trimmed = command == null ? "" : command.trim();
            if (trimmed.isBlank()) {
                return "command";
            }
            String firstToken = trimmed.split("\\s+")[0];
            int slash = Math.max(firstToken.lastIndexOf('/'), firstToken.lastIndexOf('\\'));
            if (slash >= 0) {
                firstToken = firstToken.substring(slash + 1);
            }
            String normalized = firstToken.toLowerCase(Locale.ROOT).trim();
            return normalized.isBlank() ? "command" : normalized;
        }
    }
}
