package com.zzf.rikki.runtime;

public final class RuntimeAgentConfig {
    private final String provider;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final String agent;
    private final String language;
    private final Double temperature;
    private final boolean requiresApiKey;

    public RuntimeAgentConfig(
            String provider,
            String model,
            String baseUrl,
            String apiKey,
            String agent,
            String language,
            Double temperature,
            boolean requiresApiKey
    ) {
        this.provider = blankToDefault(provider, "DEEPSEEK").trim().toUpperCase();
        this.model = blankToDefault(model, "deepseek-chat").trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.agent = agent == null ? "" : agent.trim();
        this.language = language == null ? "" : language.trim();
        this.temperature = temperature;
        this.requiresApiKey = requiresApiKey;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getAgent() {
        return agent;
    }

    public String getLanguage() {
        return language;
    }

    public Double getTemperature() {
        return temperature;
    }

    public boolean getRequiresApiKey() {
        return requiresApiKey;
    }

    public double effectiveTemperature(double fallback) {
        return temperature == null ? fallback : temperature;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
