package com.zzf.rikki.session.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class MessageV2 {
    private MessageV2() {
    }

    public static class TextPart extends PromptPart {
        public String text = "";
        public String delta = "";
        public Boolean synthetic = Boolean.FALSE;
        public Boolean ignored = Boolean.FALSE;
        public PartTime time = new PartTime();

        public TextPart() {
            this.type = "text";
        }
    }

    public static class ReasoningPart extends PromptPart {
        public String text = "";
        public String delta = "";
        public PartTime time = new PartTime();
        public Boolean collapsed = Boolean.FALSE;

        public ReasoningPart() {
            this.type = "reasoning";
        }
    }

    public static class PartTime {
        public Long start;
        public Long end;
        public Boolean compacted = Boolean.FALSE;
    }

    public static class FilePart extends PromptPart {
        public String mime;
        public String filename;
        public String url;
        public String content;

        public FilePart() {
            this.type = "file";
        }
    }

    public static class CompactionPart extends PromptPart {
        public boolean auto;
        public String summary;

        public CompactionPart() {
            this.type = "compaction";
        }
    }

    public static class SubtaskPart extends PromptPart {
        public String prompt;
        public String description;
        public String agent;

        public SubtaskPart() {
            this.type = "subtask";
        }
    }

    public static class ToolPart extends PromptPart {
        public String callID;
        public String tool;
        public Map<String, Object> args = new LinkedHashMap<>();
        public ToolState state = new ToolState();

        public ToolPart() {
            this.type = "tool";
        }
    }

    public static class StepStartPart extends PromptPart {
        public String snapshot;

        public StepStartPart() {
            this.type = "step-start";
        }
    }

    public static class StepFinishPart extends PromptPart {
        public String reason;
        public String snapshot;
        public TokenUsage tokens;
        public Double cost;

        public StepFinishPart() {
            this.type = "step-finish";
        }
    }

    public static class AgentPart extends PromptPart {
        public String name;

        public AgentPart() {
            this.type = "agent";
        }
    }

    public static class ToolState {
        public String status = "running";
        public Map<String, Object> input = new LinkedHashMap<>();
        public String output = "";
        public String title = "";
        public String error;
        public Map<String, Object> metadata = new LinkedHashMap<>();
        public TimeInfo time = new TimeInfo();

        public static class TimeInfo {
            public Long start;
            public Long end;
            public Boolean compacted = Boolean.FALSE;
        }
    }

    public static class Assistant {
        public String id;
        public String sessionID;
        public String role = "assistant";
        public Long created = System.currentTimeMillis();
        public String modelID;
        public String providerID;
        public String agent;
        public String parentID;
        public String mode;
        public Boolean summary = Boolean.FALSE;
        public TokenUsage tokens;
        public MessageTime time = new MessageTime();
        public Double cost;
        public Boolean finish = Boolean.FALSE;
        public String finishReason;
        public MessageSummary summaryInfo;
        public ErrorInfo error;
        public List<PromptPart> parts = new ArrayList<>();

        public MessageInfo toInfo() {
            MessageInfo info = new MessageInfo();
            info.id = id;
            info.sessionID = sessionID;
            info.role = role;
            info.created = created;
            info.modelID = modelID;
            info.providerID = providerID;
            info.agent = agent;
            info.parentID = parentID;
            info.mode = mode;
            info.summary = summary;
            info.tokens = tokens;
            info.time = time;
            info.cost = cost;
            info.summaryInfo = summaryInfo;
            info.error = error;
            info.finish = finish;
            info.finishReason = finishReason;
            return info;
        }

        public WithParts withParts() {
            WithParts result = new WithParts();
            result.info = toInfo();
            result.parts = parts;
            return result;
        }
    }

    public static class MessageInfo {
        public String id;
        public String sessionID;
        public String role;
        public Long created;
        public String modelID;
        public String providerID;
        public String agent;
        public String parentID;
        public String mode;
        public Boolean summary = Boolean.FALSE;
        public TokenUsage tokens;
        public MessageTime time = new MessageTime();
        public Double cost;
        public MessageSummary summaryInfo;
        public ErrorInfo error;
        public Boolean finish = Boolean.FALSE;
        public String finishReason;
        public User user;
    }

    public static class ErrorInfo {
        public String message;
        public String type;
    }

    public static class MessageSummary {
        public String title;
        public List<Object> diffs = new ArrayList<>();
    }

    public static class MessageTime {
        public Long created = System.currentTimeMillis();
        public Long start;
        public Long end;
    }

    public static class TokenUsage {
        public int input;
        public int output;
        public int reasoning;
        public CacheUsage cache = new CacheUsage();
    }

    public static class CacheUsage {
        public int read;
        public int write;
    }

    public static class User {
        public String id;
        public Map<String, Boolean> tools = new LinkedHashMap<>();
        public String system;
        public String variant;
        public MessageSummary summary;
    }

    public static class WithParts {
        public MessageInfo info = new MessageInfo();
        public List<PromptPart> parts = new ArrayList<>();

        public String textContent() {
            StringBuilder sb = new StringBuilder();
            for (PromptPart part : parts) {
                if (part instanceof TextPart textPart && !Boolean.TRUE.equals(textPart.ignored)) {
                    sb.append(textPart.text == null ? "" : textPart.text);
                }
            }
            return sb.toString();
        }

        public String reasoningContent() {
            return parts.stream()
                    .filter(ReasoningPart.class::isInstance)
                    .map(ReasoningPart.class::cast)
                    .map(part -> part.text == null ? "" : part.text)
                    .collect(Collectors.joining());
        }

        public List<ToolPart> toolParts() {
            return parts.stream()
                    .filter(ToolPart.class::isInstance)
                    .map(ToolPart.class::cast)
                    .collect(Collectors.toList());
        }
    }
}
