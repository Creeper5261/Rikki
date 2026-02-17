package com.zzf.codeagent.core.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class BashCommandNormalizer {

    String prepareCommandForShell(String command, String shell) {
        if (command == null) {
            return "";
        }
        String normalized = command
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
        if (isWindowsBashShell(shell)) {
            return BashTool.quoteLeadingWindowsExecutable(normalized);
        }
        return normalized;
    }

    boolean isWindowsBashShell(String shell) {
        if (shell == null) {
            return false;
        }
        String lower = shell.toLowerCase(Locale.ROOT);
        return lower.endsWith("bash") || lower.endsWith("bash.exe");
    }

    String resolveExecutionShell(String preferredShell, String command) {
        String shell = resolveShell(preferredShell);
        if (isWindows() && isWindowsBashShell(shell) && looksWindowsBatchCommand(command)) {
            return "cmd.exe";
        }
        if (isWindows() && !isWindowsBashShell(shell) && looksBashSpecific(command)) {
            String bash = findWindowsBash();
            if (bash != null && !bash.isBlank()) {
                return bash;
            }
        }
        return shell;
    }

    String resolveShell(String shell) {
        if (shell != null && !shell.isBlank()) {
            return shell;
        }
        return isWindows() ? "cmd.exe" : "/bin/sh";
    }

    private boolean looksBashSpecific(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.toLowerCase(Locale.ROOT);
        return normalized.contains("mkdir -p")
                || normalized.contains("rm -")
                || normalized.contains("chmod +")
                || normalized.contains("export ")
                || normalized.contains("cat <<")
                || normalized.contains("<<'eof'")
                || normalized.contains("<<\"eof\"")
                || normalized.contains("<<eof")
                || normalized.contains("$(");
    }

    private boolean looksWindowsBatchCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith(".bat")
                || normalized.endsWith(".cmd")
                || normalized.startsWith("gradlew.bat")
                || normalized.startsWith(".\\gradlew.bat")
                || normalized.startsWith("mvnw.cmd")
                || normalized.startsWith(".\\mvnw.cmd")
                || normalized.startsWith("where ")
                || normalized.startsWith("dir ");
    }

    private String findWindowsBash() {
        String[] candidates = new String[]{
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
                System.getProperty("user.home", "") + "\\AppData\\Local\\Programs\\Git\\bin\\bash.exe"
        };
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                Path path = Path.of(candidate);
                if (Files.exists(path)) {
                    return path.toString();
                }
            }
        }
        String fromPath = findExecutableOnPath("bash");
        return fromPath == null ? "" : fromPath;
    }

    private String findExecutableOnPath(String executable) {
        if (executable == null || executable.isBlank()) {
            return null;
        }
        String[] command = isWindows()
                ? new String[]{"where", executable}
                : new String[]{"which", executable};
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.isBlank()) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {

        }
        return null;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
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
}
