package com.zzf.codeagent.session.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 结构化消息分量 (对齐 OpenCode MessageV2.Part)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MessageV2.TextPart.class, name = "text"),
    @JsonSubTypes.Type(value = MessageV2.ReasoningPart.class, name = "reasoning"),
    @JsonSubTypes.Type(value = MessageV2.FilePart.class, name = "file"),
    @JsonSubTypes.Type(value = MessageV2.ToolPart.class, name = "tool"),
    @JsonSubTypes.Type(value = MessageV2.CompactionPart.class, name = "compaction"),
    @JsonSubTypes.Type(value = MessageV2.SubtaskPart.class, name = "subtask"),
    @JsonSubTypes.Type(value = MessageV2.AgentPart.class, name = "agent"),
    @JsonSubTypes.Type(value = MessageV2.StepStartPart.class, name = "step-start"),
    @JsonSubTypes.Type(value = MessageV2.StepFinishPart.class, name = "step-finish")
})
public abstract class PromptPart {
    private String id;
    private String type;
    private String sessionID;
    private String messageID;
    private java.util.Map<String, Object> metadata;
}
