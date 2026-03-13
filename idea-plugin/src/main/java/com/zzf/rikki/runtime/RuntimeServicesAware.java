package com.zzf.rikki.runtime;

import com.zzf.rikki.agent.AgentService;
import com.zzf.rikki.session.SessionService;

public interface RuntimeServicesAware {
    void bindRuntimeServices(SessionService sessionService, AgentService agentService);
}
