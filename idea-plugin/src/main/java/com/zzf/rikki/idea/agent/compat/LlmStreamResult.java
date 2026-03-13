package com.zzf.rikki.idea.agent.compat;

import java.util.ArrayList;
import java.util.List;

public class LlmStreamResult {
    private final String text;
    private final List<ToolCallInfo> toolCalls;
    private final String reasoningContent;

    public LlmStreamResult(String text, List<ToolCallInfo> toolCalls) {
        this(text, toolCalls, "");
    }

    public LlmStreamResult(String text, List<ToolCallInfo> toolCalls, String reasoningContent) {
        this.text = text == null ? "" : text;
        this.toolCalls = toolCalls == null ? List.of() : new ArrayList<>(toolCalls);
        this.reasoningContent = reasoningContent == null ? "" : reasoningContent;
    }

    public String getText() {
        return text;
    }

    public List<ToolCallInfo> getToolCalls() {
        return toolCalls;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }
}
