package com.zzf.codeagent.core.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public final class RuntimeService {
    @Value("${codeagent.runtime.type:local}")
    private String runtimeType;

    @Value("${codeagent.runtime.docker-image:ubuntu:22.04}")
    private String dockerImage;

    @Value("${codeagent.runtime.docker-workdir:/workspace}")
    private String dockerWorkdir;

    @Value("${codeagent.runtime.timeout-ms:120000}")
    private long defaultTimeoutMs;

    public CommandResult execute(String workspaceRoot, CommandRequest request, RuntimeType override) {
        long t0 = System.nanoTime();
        if (request == null) {
            return new CommandResult(-1, "", "request_null", false, elapsedMs(t0));
        }
        CommandRequest normalized = normalizeRequest(request);
        RuntimeType t = override == null ? resolveType(runtimeType) : override;
        if (t == RuntimeType.DOCKER) {
            DockerCheck check = checkDockerAvailability();
            if (!check.ok) {
                return new CommandResult(-1, check.detail, check.error, false, elapsedMs(t0));
            }
            DockerRuntimeExecutor docker = new DockerRuntimeExecutor(dockerImage, workspaceRoot, dockerWorkdir);
            return docker.execute(normalized);
        }
        LocalRuntimeExecutor local = new LocalRuntimeExecutor();
        return local.execute(normalized);
    }

    private CommandRequest normalizeRequest(CommandRequest request) {
        long timeout = request.timeoutMs > 0 ? request.timeoutMs : defaultTimeoutMs;
        return new CommandRequest(request.command, request.cwd, timeout, request.mode);
    }

    public RuntimeType resolveType(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return RuntimeType.LOCAL;
        }
        String v = raw.trim().toLowerCase();
        if (v.contains("docker")) {
            return RuntimeType.DOCKER;
        }
        return RuntimeType.LOCAL;
    }

    public RuntimeType defaultType() {
        return resolveType(runtimeType);
    }

    public Map<String, Object> health() {
        Map<String, Object> out = new HashMap<String, Object>();
        RuntimeType t = resolveType(runtimeType);
        out.put("type", t.name());
        if (t == RuntimeType.DOCKER) {
            DockerCheck check = checkDockerAvailability();
            out.put("docker_ok", check.ok);
            out.put("docker_error", check.error);
            if (!check.ok && check.detail != null && !check.detail.isEmpty()) {
                out.put("docker_detail", check.detail);
            }
        }
        return out;
    }

    private DockerCheck checkDockerAvailability() {
        if (!canRun(new String[] {"docker", "--version"})) {
            return new DockerCheck(false, "docker_cli_unavailable", "");
        }
        if (!canRun(new String[] {"docker", "info"})) {
            String info = runCapture(new String[] {"docker", "info"});
            return new DockerCheck(false, "docker_engine_unavailable", info);
        }
        return new DockerCheck(true, "", "");
    }

    private static boolean canRun(String[] cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String runCapture(String[] cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
                if (baos.size() > 64 * 1024) {
                    break;
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);
            return baos.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static final class DockerCheck {
        private final boolean ok;
        private final String error;
        private final String detail;

        private DockerCheck(boolean ok, String error, String detail) {
            this.ok = ok;
            this.error = error == null ? "" : error;
            this.detail = detail == null ? "" : detail;
        }
    }
}
