package com.zzf.codeagent.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

/**
 * Agent 信息模型 (对齐 OpenCode Agent.Info)
 */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentInfo {
    private String name;
    private String description;
    private String mode; // subagent, primary, all
    private Boolean native_; // mapped from 'native'
    private Boolean hidden;
    private Double topP;
    private Double temperature;
    private String color;
    private Object permission; // Complex permission object, use Object for now or specific class
    private AgentModel model;
    private String prompt;
    private Map<String, Object> options;
    private Integer steps;

    @Data
    @Builder
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentModel {
        private String modelID;
        private String providerID;
    }
}
