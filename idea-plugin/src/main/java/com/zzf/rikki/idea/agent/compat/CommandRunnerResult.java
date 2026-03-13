package com.zzf.rikki.idea.agent.compat;

public class CommandRunnerResult {
    private final String output;
    private final int exitCode;
    private final boolean timedOut;
    private final boolean skipped;
    private final String shell;

    public CommandRunnerResult(String output, int exitCode, boolean timedOut, boolean skipped, String shell) {
        this.output = output == null ? "" : output;
        this.exitCode = exitCode;
        this.timedOut = timedOut;
        this.skipped = skipped;
        this.shell = shell == null ? "auto" : shell;
    }

    public String getOutput() {
        return output;
    }

    public int getExitCode() {
        return exitCode;
    }

    public boolean getTimedOut() {
        return timedOut;
    }

    public boolean getSkipped() {
        return skipped;
    }

    public String getShell() {
        return shell;
    }
}
