package com.zzf.rikki.session;

import com.zzf.rikki.agent.AgentInfo;
import com.zzf.rikki.agent.AgentService;
import com.zzf.rikki.idea.agent.compat.ModelCapabilities;
import com.zzf.rikki.llm.LLMService;
import com.zzf.rikki.session.model.MessageV2;
import com.zzf.rikki.session.model.PromptPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ContextCompactionService {
    private static final int PRUNE_THRESHOLD_CHARS = Integer.getInteger("rikki.compaction.pruneChars", 40_000);
    private static final int COMPACT_THRESHOLD_CHARS = Integer.getInteger("rikki.compaction.maxChars", 90_000);
    private static final String DEFAULT_COMPACTION_PROMPT = "Provide a detailed prompt for continuing the conversation. Focus on what was done, what files changed, what remains, and the next concrete steps.";

    private final SessionService sessionService;
    private final LLMService llmService;
    private final AgentService agentService;

    public ContextCompactionService(SessionService sessionService, LLMService llmService, AgentService agentService) {
        this.sessionService = sessionService;
        this.llmService = llmService;
        this.agentService = agentService;
    }

    public void prune(String sessionId) {
        List<MessageV2.WithParts> messages = sessionService.getMessages(sessionId);
        int toolOutputChars = 0;
        List<MessageV2.ToolPart> pruneCandidates = new ArrayList<>();
        int seenUserMessages = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageV2.WithParts message = messages.get(i);
            if ("user".equals(message.info.role)) {
                seenUserMessages++;
            }
            if (seenUserMessages < 2) {
                continue;
            }
            if ("assistant".equals(message.info.role) && Boolean.TRUE.equals(message.info.summary)) {
                break;
            }
            for (PromptPart part : message.parts) {
                if (part instanceof MessageV2.ToolPart toolPart) {
                    String output = toolPart.state == null ? "" : toolPart.state.output;
                    int length = output == null ? 0 : output.length();
                    toolOutputChars += length;
                    if (toolOutputChars > PRUNE_THRESHOLD_CHARS && toolPart.state != null && !Boolean.TRUE.equals(toolPart.state.time.compacted)) {
                        pruneCandidates.add(toolPart);
                    }
                }
            }
        }
        for (MessageV2.ToolPart toolPart : pruneCandidates) {
            toolPart.state.output = "(Output pruned for brevity)";
            toolPart.state.time.compacted = Boolean.TRUE;
            sessionService.updatePart(toolPart);
        }
    }

    public boolean needsCompaction(String sessionId) {
        return totalChars(sessionService.getFilteredMessages(sessionId)) > COMPACT_THRESHOLD_CHARS;
    }

    public boolean compact(String sessionId, ModelCapabilities capabilities) {
        List<MessageV2.WithParts> messages = sessionService.copyMessages(sessionService.getFilteredMessages(sessionId));
        if (messages.isEmpty()) {
            return false;
        }
        String prompt = agentService.get("compaction")
                .map(AgentInfo::getPrompt)
                .filter(value -> value != null && !value.isBlank())
                .orElse(DEFAULT_COMPACTION_PROMPT);
        String summary = llmService.completeText(
                "compaction-" + UUID.randomUUID(),
                sessionService.toLlmMessages(
                        messages,
                        prompt,
                        capabilities.getSystemRole(),
                        capabilities.getHasReasoningContent()
                ),
                capabilities
        );
        if (summary == null || summary.isBlank()) {
            summary = fallbackSummary(messages);
        }
        if (summary == null || summary.isBlank()) {
            return false;
        }

        long now = System.currentTimeMillis();
        MessageV2.MessageInfo assistantInfo = new MessageV2.MessageInfo();
        assistantInfo.id = nextId("message");
        assistantInfo.sessionID = sessionId;
        assistantInfo.role = "assistant";
        assistantInfo.agent = "compaction";
        assistantInfo.summary = Boolean.TRUE;
        assistantInfo.finish = Boolean.TRUE;
        assistantInfo.finishReason = "compaction";
        assistantInfo.created = now;
        assistantInfo.time = new MessageV2.MessageTime();
        assistantInfo.time.created = now;
        assistantInfo.time.start = now;
        assistantInfo.time.end = now;

        MessageV2.TextPart summaryPart = new MessageV2.TextPart();
        summaryPart.id = nextId("part");
        summaryPart.sessionID = sessionId;
        summaryPart.messageID = assistantInfo.id;
        summaryPart.text = summary;
        summaryPart.delta = summary;
        summaryPart.synthetic = Boolean.TRUE;
        summaryPart.time.start = now;
        summaryPart.time.end = now;

        MessageV2.WithParts assistantMessage = new MessageV2.WithParts();
        assistantMessage.info = assistantInfo;
        assistantMessage.parts.add(summaryPart);
        sessionService.addMessage(sessionId, assistantMessage);
        sessionService.addSyntheticUserMessage(sessionId, "Continue if you have next steps");
        return true;
    }

    private String fallbackSummary(List<MessageV2.WithParts> messages) {
        List<String> lines = new ArrayList<>();
        for (MessageV2.WithParts message : messages) {
            String text = message.textContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            lines.add(message.info.role + ": " + text.trim());
        }
        if (lines.isEmpty()) {
            return "Conversation compacted. Continue from the last tool results and pending work.";
        }
        int start = Math.max(0, lines.size() - 8);
        return String.join("\n", lines.subList(start, lines.size()));
    }

    private int totalChars(List<MessageV2.WithParts> messages) {
        int total = 0;
        for (MessageV2.WithParts message : messages) {
            total += message.textContent().length();
            total += message.reasoningContent().length();
            for (MessageV2.ToolPart part : message.toolParts()) {
                if (part.state != null && part.state.output != null) {
                    total += part.state.output.length();
                }
            }
        }
        return total;
    }

    private String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
