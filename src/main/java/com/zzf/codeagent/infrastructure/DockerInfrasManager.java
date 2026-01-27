package com.zzf.codeagent.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class DockerInfrasManager {
    private static final Logger logger = LoggerFactory.getLogger(DockerInfrasManager.class);

    private final Path composeFile;
    private final boolean keepRunningOnShutdown;

    public DockerInfrasManager(Path composeFile, boolean keepRunningOnShutdown) {
        this.composeFile = composeFile;
        this.keepRunningOnShutdown = keepRunningOnShutdown;
    }

    public static DockerInfrasManager defaultForWorkDir() {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path compose = cwd.resolve("docker-compose.yml").normalize();
        if (!Files.exists(compose)) {
            compose = cwd.resolve("../docker-compose.yml").normalize();
        }
        return new DockerInfrasManager(compose, false);
    }

    public boolean isDockerAvailable() {
        return canRun(new String[]{"docker", "--version"});
    }

    public void start() {
        requireDockerCli();
        requireComposeFile();

        Path devYaml = Paths.get(System.getProperty("user.dir"))
                .resolve("src/main/resources/application-dev.yml")
                .normalize();
        new ApplicationDevYamlUpdater(devYaml).ensureDevConfig();

        List<String> cmd = composeCommand("up", "-d");
        run(cmd, composeFile.getParent().toFile(), 10, TimeUnit.MINUTES);
        logger.info("docker.infra.up ok compose={}", composeFile);

        if (!keepRunningOnShutdown) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    stop();
                } catch (Exception ignored) {
                }
            }, "docker-infra-shutdown"));
        }
    }

    public void stop() {
        if (keepRunningOnShutdown) {
            return;
        }
        if (!Files.exists(composeFile)) {
            return;
        }
        requireDockerCli();
        List<String> cmd = composeCommand("down", "--remove-orphans");
        run(cmd, composeFile.getParent().toFile(), 10, TimeUnit.MINUTES);
        logger.info("docker.infra.down ok compose={}", composeFile);
    }

    private List<String> composeCommand(String... args) {
        List<String> cmd = new ArrayList<String>();
        if (isDockerComposeV2()) {
            cmd.add("docker");
            cmd.add("compose");
        } else {
            cmd.add("docker-compose");
        }
        cmd.add("-f");
        cmd.add(composeFile.toString());
        for (String a : args) {
            cmd.add(a);
        }
        return cmd;
    }

    private void requireDockerCli() {
        if (!canRun(new String[]{"docker", "--version"})) {
            throw new IllegalStateException("docker cli not available");
        }
        if (!canRun(new String[]{"docker", "compose", "version"}) && !canRun(new String[]{"docker-compose", "--version"})) {
            throw new IllegalStateException("docker compose not available");
        }
    }

    private void requireComposeFile() {
        if (composeFile == null) {
            throw new IllegalArgumentException("composeFile is null");
        }
        if (!Files.exists(composeFile)) {
            throw new IllegalStateException("docker-compose.yml not found: " + composeFile);
        }
    }

    private boolean isDockerComposeV2() {
        try {
            runSimple(new String[]{"docker", "compose", "version"}, 10);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean canRun(String[] cmd) {
        try {
            runSimple(cmd, 5);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void runSimple(String[] cmd, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        boolean ok = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!ok) {
            p.destroyForcibly();
            throw new IllegalStateException("Command timeout: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", cmd) + " exit=" + p.exitValue());
        }
    }

    private void run(List<String> cmd, File cwd, long timeout, TimeUnit unit) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        try {
            Process p = pb.start();
            boolean ok = p.waitFor(timeout, unit);
            if (!ok) {
                p.destroyForcibly();
                throw new IllegalStateException("Command timeout: " + String.join(" ", cmd));
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("Command failed: " + String.join(" ", cmd) + " exit=" + p.exitValue());
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
