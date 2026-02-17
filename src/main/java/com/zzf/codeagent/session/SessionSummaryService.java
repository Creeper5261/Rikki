package com.zzf.codeagent.session;

import com.zzf.codeagent.agent.AgentInfo;
import com.zzf.codeagent.agent.AgentService;
import com.zzf.codeagent.bus.AgentBus;
import com.zzf.codeagent.llm.LLMService;
import com.zzf.codeagent.session.model.MessageV2;
import com.zzf.codeagent.session.model.PromptPart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 会话摘要服务 (对齐 OpenCode SessionSummary)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionSummaryService {

    private final SessionService sessionService;
    private final AgentService agentService;
    private final LLMService llmService;
    private final AgentBus agentBus;

    public CompletableFuture<Void> summarize(String sessionID, String messageID) {
        List<MessageV2.WithParts> all = sessionService.getMessages(sessionID);
        
        CompletableFuture<Void> f1 = summarizeSession(sessionID, all);
        CompletableFuture<Void> f2 = summarizeMessage(messageID, all);
        
        return CompletableFuture.allOf(f1, f2);
    }

    private CompletableFuture<Void> summarizeSession(String sessionID, List<MessageV2.WithParts> messages) {
        return computeDiff(messages).thenAccept(diffs -> {
            int additionsSum = 0;
            int deletionsSum = 0;
            
            
            for (Object d : diffs) {
                if (d instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) d;
                    additionsSum += ((Number) map.getOrDefault("additions", 0)).intValue();
                    deletionsSum += ((Number) map.getOrDefault("deletions", 0)).intValue();
                }
            }

            final int finalAdditions = additionsSum;
            final int finalDeletions = deletionsSum;

            sessionService.update(sessionID, draft -> {
                draft.setSummary(SessionInfo.SessionSummary.builder()
                        .additions(finalAdditions)
                        .deletions(finalDeletions)
                        .files(diffs.size())
                        .build());
            });

            
            agentBus.publish("session.diff", Map.of(
                "sessionID", sessionID,
                "diff", diffs
            ));
        });
    }

    private CompletableFuture<Void> summarizeMessage(String messageID, List<MessageV2.WithParts> allMessages) {
        List<MessageV2.WithParts> relevantMessages = allMessages.stream()
                .filter(m -> m.getInfo().getId().equals(messageID) || 
                           ("assistant".equals(m.getInfo().getRole()) && messageID.equals(m.getInfo().getParentID())))
                .collect(Collectors.toList());

        Optional<MessageV2.WithParts> msgOpt = allMessages.stream()
                .filter(m -> m.getInfo().getId().equals(messageID))
                .findFirst();

        if (msgOpt.isEmpty()) return CompletableFuture.completedFuture(null);

        MessageV2.WithParts msgWithParts = msgOpt.get();
        
        return computeDiff(relevantMessages).thenCompose(diffs -> {
            if (msgWithParts.getInfo().getSummaryInfo() == null) {
                msgWithParts.getInfo().setSummaryInfo(new MessageV2.MessageSummary());
            }
            msgWithParts.getInfo().getSummaryInfo().setDiffs(new ArrayList<>(diffs));
            sessionService.updateMessage(msgWithParts.getInfo());

            
            if (msgWithParts.getInfo().getSummaryInfo().getTitle() == null) {
                return generateTitle(msgWithParts);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    public CompletableFuture<List<Object>> computeDiff(List<MessageV2.WithParts> messages) {
        String from = null;
        String to = null;

        for (MessageV2.WithParts item : messages) {
            if (from == null) {
                for (PromptPart part : item.getParts()) {
                    if (part instanceof MessageV2.StepStartPart) {
                        from = ((MessageV2.StepStartPart) part).getSnapshot();
                        if (from != null) break;
                    }
                }
            }

            for (PromptPart part : item.getParts()) {
                if (part instanceof MessageV2.StepFinishPart) {
                    String snap = ((MessageV2.StepFinishPart) part).getSnapshot();
                    if (snap != null) to = snap;
                }
            }
        }

        if (from != null && to != null) {
            
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    private CompletableFuture<Void> generateTitle(MessageV2.WithParts msgWithParts) {
        Optional<AgentInfo> titleAgentOpt = agentService.get("title");
        if (titleAgentOpt.isEmpty()) return CompletableFuture.completedFuture(null);

        AgentInfo agent = titleAgentOpt.get();
        String text = msgWithParts.getParts().stream()
                .filter(p -> p instanceof MessageV2.TextPart && !Boolean.TRUE.equals(((MessageV2.TextPart) p).getSynthetic()))
                .map(p -> ((MessageV2.TextPart) p).getText())
                .collect(Collectors.joining("\n"));

        if (text.isEmpty()) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> future = new CompletableFuture<>();
        StringBuilder titleBuilder = new StringBuilder();

        LLMService.StreamInput input = LLMService.StreamInput.builder()
                .sessionID(msgWithParts.getInfo().getSessionID())
                .agent(agent)
                .messages(List.of(MessageV2.WithParts.builder()
                        .info(MessageV2.MessageInfo.builder().role("user").build())
                        .parts(List.of(MessageV2.TextPart.builder()
                                .text("The following is the text to summarize:\n<text>\n" + text + "\n</text>")
                                .build()))
                        .build()))
                .systemInstructions(agent.getPrompt() != null ? List.of(agent.getPrompt()) : new ArrayList<>())
                .small(true)
                .tools(Map.of())
                .build();

        llmService.stream(input, new LLMService.StreamCallback() {
            @Override
            public void onTextDelta(String text, Map<String, Object> metadata) {
                titleBuilder.append(text);
            }

            @Override
            public void onComplete(String finishReason) {
                String title = titleBuilder.toString().trim();
                log.info("Generated title for message {}: {}", msgWithParts.getInfo().getId(), title);
                
                if (msgWithParts.getInfo().getSummaryInfo() == null) {
                    msgWithParts.getInfo().setSummaryInfo(new MessageV2.MessageSummary());
                }
                msgWithParts.getInfo().getSummaryInfo().setTitle(title);
                sessionService.updateMessage(msgWithParts.getInfo());
                future.complete(null);
            }

            @Override
            public void onError(Throwable t) {
                log.error("Failed to generate title", t);
                future.completeExceptionally(t);
            }
        });

        return future;
    }
}
