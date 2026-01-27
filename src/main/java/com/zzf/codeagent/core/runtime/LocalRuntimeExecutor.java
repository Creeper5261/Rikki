package com.zzf.codeagent.core.runtime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class LocalRuntimeExecutor implements RuntimeExecutor {
    @Override
    public RuntimeType type() {
        return RuntimeType.LOCAL;
    }

    @Override
    public CommandResult execute(CommandRequest request) {
        long t0 = System.nanoTime();
        if (request == null || request.command == null || request.command.trim().isEmpty()) {
            return new CommandResult(-1, "", "command_empty", false, elapsedMs(t0));
        }
        String cmd = request.command;
        if (request.mode == ExecutionMode.STEP && cmd.contains("\n")) {
            return new CommandResult(-1, "", "step_mode_disallows_multiline", false, elapsedMs(t0));
        }
        List<String> command = buildCommand(cmd);
        ProcessBuilder pb = new ProcessBuilder(command);
        if (request.cwd != null && !request.cwd.trim().isEmpty()) {
            pb.directory(new File(request.cwd.trim()));
        }
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            Thread reader = new Thread(() -> drain(in, out), "runtime-local-reader");
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

    private static List<String> buildCommand(String command) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<String>();
        if (isWindows) {
            cmd.add("powershell");
            cmd.add("-NoProfile");
            cmd.add("-ExecutionPolicy");
            cmd.add("Bypass");
            cmd.add("-Command");
            cmd.add(command);
        } else {
            cmd.add("bash");
            cmd.add("-lc");
            cmd.add(command);
        }
        return cmd;
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
