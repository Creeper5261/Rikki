package com.zzf.rikki.shell;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Shell 服务 (对齐 OpenCode Shell namespace)
 */
@Slf4j
@Service
public class ShellService {

    private static final long SIGKILL_TIMEOUT_MS = 200;
    private static final Set<String> BLACKLIST = new HashSet<>(Set.of("fish", "nu"));

    public void killTree(Process process) {
        if (process == null || !process.isAlive()) return;

        long pid = process.pid();
        log.info("Killing process tree for pid: {}", pid);

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            try {
                new ProcessBuilder("taskkill", "/pid", String.valueOf(pid), "/f", "/t")
                        .inheritIO()
                        .start()
                        .waitFor();
            } catch (Exception e) {
                log.error("Failed to kill process tree on Windows", e);
            }
            return;
        }

        try {
            process.destroy();
            if (!process.waitFor(SIGKILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public ExecuteResult execute(String command, String gitDir, String workTree) {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", command);
            
            if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                 pb = new ProcessBuilder("/bin/sh", "-c", command);
            }
            
            Map<String, String> env = pb.environment();
            if (gitDir != null) env.put("GIT_DIR", gitDir);
            if (workTree != null) env.put("GIT_WORK_TREE", workTree);
            
            Process p = pb.start();
            
            String stdout;
            String stderr;
            
            try (BufferedReader stdReader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                 BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                stdout = stdReader.lines().collect(Collectors.joining("\n"));
                stderr = errReader.lines().collect(Collectors.joining("\n"));
            }
            
            int exitCode = p.waitFor();
            return new ExecuteResult(exitCode, stdout, stderr);
        } catch (Exception e) {
            log.error("Failed to execute command: {}", command, e);
            return new ExecuteResult(-1, "", e.getMessage());
        }
    }

    @lombok.Data
    @lombok.RequiredArgsConstructor
    public static class ExecuteResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        public String text() {
            return stdout;
        }
    }

    public String preferred() {
        String shell = System.getenv("SHELL");
        if (shell != null) {
            String name = Paths.get(shell).getFileName().toString();
            if (!BLACKLIST.contains(name)) return shell;
        }
        return fallback();
    }

    public String acceptable() {
        return preferred();
    }

    private String fallback() {
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWin) {
            
            String gitPath = which("git");
            if (gitPath != null) {
                
                Path gitParent = Paths.get(gitPath).getParent();
                
                Path bashPath = gitParent.getParent().resolve("bin").resolve("bash.exe");
                if (bashPath.toFile().exists()) {
                    return bashPath.toString();
                }
                
                bashPath = gitParent.resolve("bash.exe");
                if (bashPath.toFile().exists()) {
                    return bashPath.toString();
                }
            }
            
            
            String[] commonPaths = {
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
                System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Git\\bin\\bash.exe"
            };
            for (String path : commonPaths) {
                if (new File(path).exists()) {
                    return path;
                }
            }
            
            
            String bashPath = which("bash");
            if (bashPath != null && new File(bashPath).exists()) {
                return bashPath;
            }

            String comspec = System.getenv("COMSPEC");
            return comspec != null ? comspec : "cmd.exe";
        }

        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            return "/bin/zsh";
        }

        String bash = which("bash");
        return bash != null ? bash : "/bin/sh";
    }

    private String which(String cmd) {
        try {
            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
            String[] command = isWin ? new String[]{"where", cmd} : new String[]{"which", cmd};
            
            Process p = new ProcessBuilder(command).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.isEmpty()) {
                    return line;
                }
            }
        } catch (Exception e) {
            
        }
        return null;
    }
}
