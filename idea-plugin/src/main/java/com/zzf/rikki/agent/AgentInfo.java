package com.zzf.rikki.agent;

import java.util.LinkedHashMap;
import java.util.Map;

public class AgentInfo {
    private String name;
    private String description;
    private String mode;
    private Boolean nativeAgent;
    private Boolean hidden;
    private Double topP;
    private Double temperature;
    private String color;
    private AgentModel model;
    private String prompt;
    private Map<String, Object> options = new LinkedHashMap<>();
    private Integer steps;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Boolean getNativeAgent() {
        return nativeAgent;
    }

    public void setNativeAgent(Boolean nativeAgent) {
        this.nativeAgent = nativeAgent;
    }

    public Boolean getHidden() {
        return hidden;
    }

    public void setHidden(Boolean hidden) {
        this.hidden = hidden;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public AgentModel getModel() {
        return model;
    }

    public void setModel(AgentModel model) {
        this.model = model;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options == null ? new LinkedHashMap<>() : new LinkedHashMap<>(options);
    }

    public Integer getSteps() {
        return steps;
    }

    public void setSteps(Integer steps) {
        this.steps = steps;
    }

    public static class AgentModel {
        private String modelID;
        private String providerID;

        public String getModelID() {
            return modelID;
        }

        public void setModelID(String modelID) {
            this.modelID = modelID;
        }

        public String getProviderID() {
            return providerID;
        }

        public void setProviderID(String providerID) {
            this.providerID = providerID;
        }
    }
}