package com.zzf.rikki.idea.agent.compat;

public class ModelCapabilities {
    private final String systemRole;
    private final Double temperatureFixed;
    private final String maxTokensKey;
    private final boolean supportsTools;
    private final boolean hasReasoningContent;

    public ModelCapabilities() {
        this("system", null, "max_tokens", true, false);
    }

    public ModelCapabilities(
            String systemRole,
            Double temperatureFixed,
            String maxTokensKey,
            boolean supportsTools,
            boolean hasReasoningContent
    ) {
        this.systemRole = systemRole == null ? "system" : systemRole;
        this.temperatureFixed = temperatureFixed;
        this.maxTokensKey = maxTokensKey == null ? "max_tokens" : maxTokensKey;
        this.supportsTools = supportsTools;
        this.hasReasoningContent = hasReasoningContent;
    }

    public String getSystemRole() {
        return systemRole;
    }

    public Double getTemperatureFixed() {
        return temperatureFixed;
    }

    public String getMaxTokensKey() {
        return maxTokensKey;
    }

    public boolean getSupportsTools() {
        return supportsTools;
    }

    public boolean getHasReasoningContent() {
        return hasReasoningContent;
    }
}
