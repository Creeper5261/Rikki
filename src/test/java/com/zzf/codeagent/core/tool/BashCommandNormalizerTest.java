package com.zzf.codeagent.core.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BashCommandNormalizerTest {

    private final BashCommandNormalizer normalizer = new BashCommandNormalizer();

    @Test
    void shouldNormalizeNewlinesForPowerShell() {
        String prepared = normalizer.prepareCommandForShell("echo 1\necho 2", "powershell.exe");
        assertEquals("echo 1; echo 2", prepared);
    }

    @Test
    void shouldNormalizeNewlinesForDefaultShell() {
        String prepared = normalizer.prepareCommandForShell("echo 1\necho 2", "/bin/sh");
        assertEquals("echo 1 && echo 2", prepared);
    }

    @Test
    void shouldResolveDefaultShellByOs() {
        String resolved = normalizer.resolveShell("");
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            assertEquals("cmd.exe", resolved);
        } else {
            assertEquals("/bin/sh", resolved);
        }
    }

    @Test
    void shouldKeepPreferredShellWhenNoSwitchNeeded() {
        String resolved = normalizer.resolveExecutionShell("pwsh.exe", "echo hello");
        assertEquals("pwsh.exe", resolved);
    }
}
