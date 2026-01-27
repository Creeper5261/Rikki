package com.zzf.codeagent.core.runtime;

public final class CommandRequest {
    public final String command;
    public final String cwd;
    public final long timeoutMs;
    public final ExecutionMode mode;

    public CommandRequest(String command, String cwd, long timeoutMs, ExecutionMode mode) {
        this.command = command;
        this.cwd = cwd;
        this.timeoutMs = timeoutMs;
        this.mode = mode == null ? ExecutionMode.STEP : mode;
    }
}
