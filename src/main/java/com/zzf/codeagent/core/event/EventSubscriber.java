package com.zzf.codeagent.core.event;

public interface EventSubscriber {
    void onEvent(AgentEvent event);
}
