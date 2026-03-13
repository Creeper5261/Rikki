package com.zzf.rikki.agent;

import com.zzf.rikki.session.PromptTextLoader;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class AgentService {
    private final Map<String, AgentInfo> agents = new LinkedHashMap<>();

    public AgentService() {
        loadAgents();
    }

    private void loadAgents() {
        register(primaryAgent("build", "The default agent. Executes tools based on configured permissions.", null));
        register(primaryAgent("plan", "Plan mode. Disallows all edit tools.", null));
        register(subAgent("general", "General-purpose agent for researching complex questions and executing multi-step tasks.", null));
        register(subAgent("explore", "Fast agent specialized for exploring codebases.", PromptTextLoader.loadAgentPrompt("explore")));
        register(hiddenAgent("compaction", PromptTextLoader.loadAgentPrompt("compaction")));
        register(hiddenAgent("title", PromptTextLoader.loadAgentPrompt("title")));
        register(hiddenAgent("summary", PromptTextLoader.loadAgentPrompt("summary")));
    }

    private AgentInfo primaryAgent(String name, String description, String prompt) {
        AgentInfo info = new AgentInfo();
        info.setName(name);
        info.setDescription(description);
        info.setMode("primary");
        info.setNativeAgent(Boolean.TRUE);
        info.setPrompt(blankToNull(prompt));
        return info;
    }

    private AgentInfo subAgent(String name, String description, String prompt) {
        AgentInfo info = primaryAgent(name, description, prompt);
        info.setMode("subagent");
        return info;
    }

    private AgentInfo hiddenAgent(String name, String prompt) {
        AgentInfo info = primaryAgent(name, name, prompt);
        info.setHidden(Boolean.TRUE);
        return info;
    }

    private void register(AgentInfo info) {
        agents.put(info.getName(), info);
    }

    public Optional<AgentInfo> get(String name) {
        return Optional.ofNullable(agents.get(name));
    }

    public Collection<AgentInfo> list() {
        return agents.values();
    }

    public Optional<AgentInfo> defaultAgent() {
        return get("build");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}