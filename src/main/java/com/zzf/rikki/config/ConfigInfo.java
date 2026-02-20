package com.zzf.rikki.config;

import lombok.Data;
import java.util.Map;
import java.util.List;

/**
 * 配置模型 (对齐 OpenCode Config.Info)
 */
@Data
public class ConfigInfo {
    private String model;
    private String small_model;
    private Map<String, ProviderConfig> provider;
    private List<String> enabled_providers;
    private List<String> disabled_providers;
    private List<String> instructions;
    private CompactionConfig compaction;
    private Map<String, AgentConfig> agent;

    @Data
    public static class ProviderConfig {
        private String name;
        private ApiConfig api;
        private List<String> env;
        private Map<String, Object> options;
        private Map<String, ModelConfig> models;
    }

    @Data
    public static class ApiConfig {
        private String baseUrl;
        private String apiKey;
    }

    @Data
    public static class ModelConfig {
        private String id;
        private String name;
        private Map<String, Object> options;
        private ModelLimit limit;
    }

    @Data
    public static class ModelLimit {
        private Integer context;
        private Integer output;
    }

    @Data
    public static class CompactionConfig {
        private Boolean auto;
        private Boolean prune;
    }

    @Data
    public static class AgentConfig {
        private String name;
        private String mode; 
        private String model;
    }
}
