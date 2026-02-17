package com.zzf.codeagent.core.tool;

import com.zzf.codeagent.shell.ShellService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashCommandExecutorTest {

    private final BashCommandExecutor executor = new BashCommandExecutor(new ShellService(), 1000);

    @Test
    void shouldExecuteCommandAndCaptureOutput(@TempDir Path workdir) throws Exception {
        String shell = isWindows() ? "cmd.exe" : "/bin/sh";
        String command = "echo hello_executor";
        BashCommandExecutor.ExecutionResult result = executor.execute(
                command,
                shell,
                workdir,
                5000,
                "echo",
                null,
                false
        );

        assertEquals(0, result.exitCode);
        assertFalse(result.timedOut);
        assertTrue(result.output.toLowerCase().contains("hello_executor"));
    }

    @Test
    void shouldEmitMetadataWhenStreamingEnabled(@TempDir Path workdir) throws Exception {
        String shell = isWindows() ? "cmd.exe" : "/bin/sh";
        String command = isWindows() ? "echo one & echo two" : "echo one && echo two";
        List<Map<String, Object>> updates = new ArrayList<>();
        Tool.Context ctx = Tool.Context.builder()
                .callID("call-meta")
                .sessionID("session-meta")
                .metadataConsumer((title, metadata) -> updates.add(Map.copyOf(metadata)))
                .build();

        BashCommandExecutor.ExecutionResult result = executor.execute(
                command,
                shell,
                workdir,
                5000,
                "metadata-test",
                ctx,
                true
        );

        assertEquals(0, result.exitCode);
        assertTrue(updates.size() >= 1);
        assertTrue(updates.stream().anyMatch(meta -> String.valueOf(meta.getOrDefault("output", "")).contains("one")));
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
