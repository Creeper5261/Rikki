package com.zzf.codeagent.core.runtime;

public interface RuntimeExecutor {
    RuntimeType type();
    CommandResult execute(CommandRequest request);
}
