package com.zzf.codeagent.core.runtime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class DockerRuntimeExecutor implements RuntimeExecutor {
    private final String image;
    private final String workspaceRoot;
    private final String containerWorkdir;

    public DockerRuntimeExecutor(String image, String workspaceRoot, String containerWorkdir) {
        this.image = image == null || image.trim().isEmpty() ? "ubuntu:22.04" : image.trim();
        this.workspaceRoot = workspaceRoot == null ? "" : workspaceRoot.trim();
        this.containerWorkdir = containerWorkdir == null || containerWorkdir.trim().isEmpty() ? "/workspace" : containerWorkdir.trim();
    }

    @Override
    public RuntimeType type() {
        return RuntimeType.DOCKER;
    }

    @Override
    public CommandResult execute(CommandRequest request) {
        long t0 = System.nanoTime();
        if (request == null || request.command == null || request.command.trim().isEmpty()) {
            return new CommandResult(-1, "", "command_empty", false, elapsedMs(t0));
        }
        if (workspaceRoot.isEmpty()) {
            return new CommandResult(-1, "", "workspace_root_empty", false, elapsedMs(t0));
        }
        String cmd = request.command;
        if (request.mode == ExecutionMode.STEP && cmd.contains("\n")) {
            return new CommandResult(-1, "", "step_mode_disallows_multiline", false, elapsedMs(t0));
        }
        List<String> dockerCmd = buildDockerCommand(cmd, request.cwd);
        ProcessBuilder pb = new ProcessBuilder(dockerCmd);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            Thread reader = new Thread(() -> drain(in, out), "runtime-docker-reader");
            reader.start();
            long timeout = request.timeoutMs > 0 ? request.timeoutMs : 120000;
            boolean ok = p.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!ok) {
                p.destroyForcibly();
            }
            try {
                reader.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (!ok) {
                return new CommandResult(-1, out.toString(StandardCharsets.UTF_8), "timeout", true, elapsedMs(t0));
            }
            int exit = p.exitValue();
            String output = out.toString(StandardCharsets.UTF_8);
            String err = exit == 0 ? "" : "exit_code_" + exit;
            return new CommandResult(exit, output, err, false, elapsedMs(t0));
        } catch (Exception e) {
            return new CommandResult(-1, "", "io_error:" + e.getClass().getSimpleName(), false, elapsedMs(t0));
        }
    }

    private List<String> buildDockerCommand(String command, String cwd) {
        List<String> cmd = new ArrayList<String>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("-v");
        cmd.add(hostMount() + ":" + containerWorkdir);
        cmd.add("-w");
        cmd.add(containerCwd(cwd));
        cmd.add(image);
        cmd.add("sh");
        cmd.add("-lc");
        cmd.add(command);
        return cmd;
    }

    private String hostMount() {
        Path p = Path.of(workspaceRoot).toAbsolutePath().normalize();
        String raw = p.toString();
        if (File.separatorChar == '\\') {
            return raw;
        }
        return raw;
    }

    private String containerCwd(String cwd) {
        if (cwd == null || cwd.trim().isEmpty()) {
            return containerWorkdir;
        }
        Path base = Path.of(workspaceRoot).toAbsolutePath().normalize();
        Path input = Path.of(cwd.trim());
        Path abs = input.isAbsolute() ? input.toAbsolutePath().normalize() : base.resolve(input).normalize();
        if (!abs.startsWith(base)) {
            return containerWorkdir;
        }
        Path rel = base.relativize(abs);
        String relUnix = rel.toString().replace('\\', '/');
        if (relUnix.isEmpty()) {
            return containerWorkdir;
        }
        return containerWorkdir + "/" + relUnix;
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static void drain(InputStream in, ByteArrayOutputStream out) {
        if (in == null || out == null) {
            return;
        }
        byte[] buf = new byte[4096];
        try {
            int read;
            while ((read = in.read(buf)) >= 0) {
                out.write(buf, 0, read);
            }
        } catch (Exception ignored) {
        }
    }
}
