package com.zzf.rikki.runtime.port;

import com.zzf.rikki.idea.agent.compat.AgentEventSink;

public interface AgentRuntime {
    void run(RuntimeRequest request, AgentEventSink sink);
}
