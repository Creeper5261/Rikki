package com.zzf.rikki.agent;

import com.zzf.rikki.config.ConfigInfo;
import com.zzf.rikki.config.ConfigManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 管理服务 (对齐 OpenCode Agent namespace)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final ConfigManager configManager;
    private final Map<String, AgentInfo> agents = new HashMap<>();

    @PostConstruct
    public void init() {
        loadAgents();
    }

    private void loadAgents() {
        
        
        
        agents.put("build", AgentInfo.builder()
                .name("build")
                .description("The default agent. Executes tools based on configured permissions.")
                .mode("primary")
                .native_(true)
                .options(new HashMap<>())
                .build());

        
        agents.put("plan", AgentInfo.builder()
                .name("plan")
                .description("Plan mode. Disallows all edit tools.")
                .mode("primary")
                .native_(true)
                .options(new HashMap<>())
                .build());

        
        agents.put("general", AgentInfo.builder()
                .name("general")
                .description("General-purpose agent for researching complex questions and executing multi-step tasks.")
                .mode("subagent")
                .native_(true)
                .options(new HashMap<>())
                .build());

        
        agents.put("explore", AgentInfo.builder()
                .name("explore")
                .description("Fast agent specialized for exploring codebases.")
                .mode("subagent")
                .native_(true)
                .prompt(loadPrompt("explore.txt"))
                .options(new HashMap<>())
                .build());

        
        agents.put("compaction", AgentInfo.builder()
                .name("compaction")
                .mode("primary")
                .native_(true)
                .hidden(true)
                .prompt(loadPrompt("compaction.txt"))
                .options(new HashMap<>())
                .build());

        
        agents.put("title", AgentInfo.builder()
                .name("title")
                .mode("primary")
                .native_(true)
                .hidden(true)
                .temperature(0.5)
                .prompt(loadPrompt("title.txt"))
                .options(new HashMap<>())
                .build());

        
        agents.put("summary", AgentInfo.builder()
                .name("summary")
                .mode("primary")
                .native_(true)
                .hidden(true)
                .prompt(loadPrompt("summary.txt"))
                .options(new HashMap<>())
                .build());

        
        ConfigInfo config = configManager.getConfig();
        if (config != null && config.getAgent() != null) {
            config.getAgent().forEach((name, cfg) -> {
                AgentInfo existing = agents.get(name);
                if (existing != null) {
                    
                    existing.setMode(cfg.getMode() != null ? cfg.getMode() : existing.getMode());
                    
                } else {
                    agents.put(name, AgentInfo.builder()
                            .name(name)
                            .mode(cfg.getMode())
                            .model(AgentInfo.AgentModel.builder()
                                    
                                    .build())
                            .build());
                }
            });
        }
    }

    private String loadPrompt(String filename) {
        try {
            
            
            Path path = Paths.get("src/main/resources/prompts/opencode", filename);
            if (Files.exists(path)) {
                return Files.readString(path);
            }
            
             return ""; 
        } catch (IOException e) {
            log.warn("Failed to load prompt: {}", filename);
            return "";
        }
    }

    public Optional<AgentInfo> get(String name) {
        return Optional.ofNullable(agents.get(name));
    }

    public java.util.Collection<AgentInfo> list() {
        return agents.values();
    }

    public Optional<AgentInfo> getAgent(String name) {
        return get(name);
    }

    public Optional<AgentInfo> defaultAgent() {
        return get("build");
    }
}
