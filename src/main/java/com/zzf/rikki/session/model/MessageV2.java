package com.zzf.rikki.session.model;

import lombok.*;
import java.util.List;
import java.util.Map;

/**
 * 结构化消息体系 (对齐 OpenCode MessageV2)
 */
public class MessageV2 {

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TextPart extends PromptPart {
        private String text;
        private String delta; 
        private Boolean synthetic;
        private Boolean ignored;
        private PartTime time;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ReasoningPart extends PromptPart {
        private String text;
        private String delta; 
        private PartTime time;
        private Boolean collapsed; 
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartTime {
        private Long start;
        private Long end;
        private Boolean compacted;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class FilePart extends PromptPart {
        private String mime;
        private String filename;
        private String url;
        private String content; 
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class CompactionPart extends PromptPart {
        private boolean auto;
        private String summary;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class SubtaskPart extends PromptPart {
        private String prompt;
        private String description;
        private String agent;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ToolPart extends PromptPart {
        private String callID;
        private String tool;
        private Map<String, Object> args;
        private ToolState state;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class StepStartPart extends PromptPart {
        private String snapshot;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class StepFinishPart extends PromptPart {
        private String reason;
        private String snapshot;
        private TokenUsage tokens;
        private Double cost;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class AgentPart extends PromptPart {
        private String name;
    }

    @Data
    public static class ToolState {
        private String status; 
        private Map<String, Object> input;
        private String output;
        private String title;
        private String error;
        private Map<String, Object> metadata;
        private TimeInfo time;

        @Data
        public static class TimeInfo {
            private Long start;
            private Long end;
            private Boolean compacted;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Assistant {
        private String id;
        private String sessionID;
        private String role; 
        private Long created;
        private String modelID;
        private String providerID;
        private String agent;
        private String parentID;
        private String mode;
        private Boolean summary;
        private TokenUsage tokens;
        private MessageTime time;
        private Double cost;
        private Boolean finish;
        private String finishReason;
        private MessageSummary summaryInfo;
        private ErrorInfo error;
        @Builder.Default
        private List<PromptPart> parts = new java.util.ArrayList<>();

        public MessageInfo toInfo() {
            MessageInfo info = new MessageInfo();
            info.setId(id);
            info.setSessionID(sessionID);
            info.setRole(role);
            info.setCreated(created);
            info.setModelID(modelID);
            info.setProviderID(providerID);
            info.setAgent(agent);
            info.setParentID(parentID);
            info.setMode(mode);
            info.setSummary(summary);
            info.setTokens(tokens);
            info.setTime(time);
            info.setCost(cost);
            info.setSummaryInfo(summaryInfo);
            info.setError(error);
            info.setFinish(finish);
            info.setFinishReason(finishReason);
            return info;
        }

        public WithParts withParts() {
            return WithParts.builder()
                    .info(toInfo())
                    .parts(parts)
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageInfo {
        private String id;
        private String sessionID;
        private String role; 
        private Long created;
        private String modelID;
        private String providerID;
        private String agent;
        private String parentID;
        private String mode; 
        private Boolean summary;
        private TokenUsage tokens;
        private MessageTime time;
        private Double cost;
        private MessageSummary summaryInfo;
        private ErrorInfo error;
        private Boolean finish;
        private String finishReason;
        private User user; 
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorInfo {
        private String message;
        private String type;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageSummary {
        private String title;
        private List<Object> diffs; 
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageTime {
        private Long created;
        private Long start;
        private Long end;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenUsage {
        private int input;
        private int output;
        private int reasoning;
        private CacheUsage cache;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CacheUsage {
        private int read;
        private int write;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class User {
        private String id;
        private Map<String, Boolean> tools;
        private String system;
        private String variant;
        private MessageSummary summary;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class WithParts {
        private MessageInfo info;
        private List<PromptPart> parts;
    }
}
