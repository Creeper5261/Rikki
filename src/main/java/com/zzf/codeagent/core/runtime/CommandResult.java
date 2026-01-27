package com.zzf.codeagent.core.runtime;

public final class CommandResult {
    public final int exitCode;
    public final String output;
    public final String error;
    public final boolean timeout;
    public final long tookMs;

    public CommandResult(int exitCode, String output, String error, boolean timeout, long tookMs) {
        this.exitCode = exitCode;
        this.output = output == null ? "" : output;
        this.error = error == null ? "" : error;
        this.timeout = timeout;
        this.tookMs = tookMs;
    }
}
