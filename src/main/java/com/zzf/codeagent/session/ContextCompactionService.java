package com.zzf.codeagent.session;

import com.zzf.codeagent.agent.AgentInfo;
import com.zzf.codeagent.agent.AgentService;
import com.zzf.codeagent.bus.AgentBus;
import com.zzf.codeagent.config.ConfigInfo;
import com.zzf.codeagent.config.ConfigManager;
import com.zzf.codeagent.llm.LLMService;
import com.zzf.codeagent.provider.ModelInfo;
import com.zzf.codeagent.provider.ProviderManager;
import com.zzf.codeagent.session.model.MessageV2;
import com.zzf.codeagent.session.model.PromptPart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 上下文压缩服务 (对齐 OpenCode SessionCompaction)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextCompactionService {

    private final SessionService sessionService;
    private final LLMService llmService;
    private final AgentService agentService;
    private final ConfigManager configManager;
    private final ProviderManager providerManager;
    private final AgentBus agentBus;

    public static final int PRUNE_MINIMUM = 20_000;
    public static final int PRUNE_PROTECT = 40_000;
    private static final List<String> PRUNE_PROTECTED_TOOLS = List.of("skill");
    private static final int OUTPUT_TOKEN_MAX = 4096; 

    /**
     * 检测是否溢出 (Check if context overflow)
     */
    public boolean isOverflow(MessageV2.TokenUsage tokens, ModelInfo model) {
        if (model == null) {
            return false;
        }
        ConfigInfo config = configManager.getConfig();
        if (config != null && config.getCompaction() != null && Boolean.FALSE.equals(config.getCompaction().getAuto())) {
            return false;
        }

        int context = model.getLimit() != null ? model.getLimit().getContext() : 0;
        if (context <= 0) return false;

        int input = 0;
        int cacheRead = 0;
        int output = 0;
        if (tokens != null) {
            input = Math.max(0, tokens.getInput());
            output = Math.max(0, tokens.getOutput());
            if (tokens.getCache() != null) {
                cacheRead = Math.max(0, tokens.getCache().getRead());
            }
        }
        int count = input + cacheRead + output;
        
        int modelOutputLimit = model.getLimit() != null ? model.getLimit().getOutput() : 0;
        int usableOutput = Math.min(modelOutputLimit, OUTPUT_TOKEN_MAX);
        if (usableOutput == 0) usableOutput = OUTPUT_TOKEN_MAX;

        
        
        int usable = Math.max(0, context - usableOutput);
        
        return count > usable;
    }

    /**
     * 剪枝冗余工具输出 (Prune old tool outputs)
     */
    public void prune(String sessionID) {
        ConfigInfo config = configManager.getConfig();
        if (config.getCompaction() != null && Boolean.FALSE.equals(config.getCompaction().getPrune())) {
            return;
        }

        log.info("Pruning session: {}", sessionID);
        List<MessageV2.WithParts> msgs = sessionService.getMessages(sessionID);
        
        int total = 0;
        int pruned = 0;
        List<MessageV2.ToolPart> toPrune = new ArrayList<>();
        int turns = 0;

        
        for (int i = msgs.size() - 1; i >= 0; i--) {
            MessageV2.WithParts msg = msgs.get(i);
            
            if ("user".equals(msg.getInfo().getRole())) {
                turns++;
            }
            if (turns < 2) continue; 

            
            if ("assistant".equals(msg.getInfo().getRole()) && Boolean.TRUE.equals(msg.getInfo().getSummary())) {
                break;
            }

            
            for (int j = msg.getParts().size() - 1; j >= 0; j--) {
                PromptPart part = msg.getParts().get(j);
                
                if (part instanceof MessageV2.ToolPart) {
                    MessageV2.ToolPart toolPart = (MessageV2.ToolPart) part;
                    if (toolPart.getState() != null && "completed".equals(toolPart.getState().getStatus())) {
                        if (PRUNE_PROTECTED_TOOLS.contains(toolPart.getTool())) continue;
                        
                        
                        if (toolPart.getState().getMetadata() != null && toolPart.getState().getMetadata().containsKey("compacted")) {
                            break; 
                        }

                        
                        String output = toolPart.getState().getOutput();
                        int estimate = output != null ? output.length() / 4 : 0;
                        
                        total += estimate;
                        if (total > PRUNE_PROTECT) {
                            pruned += estimate;
                            toPrune.add(toolPart);
                        }
                    }
                }
            }
        }
        
        log.info("Found {} tokens to prune, total tool output tokens: {}", pruned, total);
        if (pruned > PRUNE_MINIMUM) {
            for (MessageV2.ToolPart part : toPrune) {
                if ("completed".equals(part.getState().getStatus())) {
                    Map<String, Object> metadata = part.getState().getMetadata();
                    if (metadata == null) {
                        metadata = new HashMap<>();
                        part.getState().setMetadata(metadata);
                    }
                    metadata.put("compacted", System.currentTimeMillis());
                    part.getState().setOutput("(Output pruned for brevity)");
                    sessionService.updatePart(part);
                }
            }
            log.info("Pruned {} tool parts in session {}", toPrune.size(), sessionID);
        }
    }

    /**
     * 执行上下文压缩/总结 (Auto-summarization)
     * 对齐 OpenCode SessionCompaction.process
     */
    public CompletableFuture<String> process(String sessionID, String parentID, List<MessageV2.WithParts> messages, boolean auto) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                MessageV2.WithParts userMsg = messages.stream()
                        .filter(m -> m.getInfo().getId().equals(parentID))
                        .findFirst()
                        .orElseThrow();

                
                AgentInfo agent = agentService.get("compaction").orElse(null);
                ModelInfo model;
                if (agent != null && agent.getModel() != null) {
                    model = providerManager.getModel(agent.getModel().getProviderID(), agent.getModel().getModelID()).orElse(null);
                } else {
                    model = providerManager.getModel(userMsg.getInfo().getProviderID(), userMsg.getInfo().getModelID()).orElse(null);
                }

                if (model == null) model = providerManager.getDefaultModel();

                
                MessageV2.MessageInfo assistantInfo = new MessageV2.MessageInfo();
                assistantInfo.setId(UUID.randomUUID().toString());
                assistantInfo.setSessionID(sessionID);
                assistantInfo.setRole("assistant");
                assistantInfo.setCreated(System.currentTimeMillis());
                assistantInfo.setParentID(parentID);
                assistantInfo.setMode("compaction");
                assistantInfo.setAgent("compaction");
                assistantInfo.setSummary(true);
                assistantInfo.setModelID(model.getId());
                assistantInfo.setProviderID(model.getProviderID());
                assistantInfo.setTokens(new MessageV2.TokenUsage());

                List<PromptPart> parts = new ArrayList<>();
                MessageV2.WithParts assistantMsg = new MessageV2.WithParts(assistantInfo, parts);
                sessionService.addMessage(sessionID, assistantMsg);

                
                String defaultPrompt = "Provide a detailed prompt for continuing our conversation above. Focus on information that would be helpful for continuing the conversation, including what we did, what we're doing, which files we're working on, and what we're going to do next considering new session will not have access to our conversation.";
                
                
                List<MessageV2.WithParts> inputMessages = new ArrayList<>(messages);
                
                
                MessageV2.MessageInfo promptMsgInfo = new MessageV2.MessageInfo();
                promptMsgInfo.setRole("user");
                MessageV2.TextPart promptPart = new MessageV2.TextPart();
                promptPart.setText(defaultPrompt);
                inputMessages.add(new MessageV2.WithParts(promptMsgInfo, List.of(promptPart)));

                LLMService.StreamInput input = LLMService.StreamInput.builder()
                        .sessionID(sessionID)
                        .agent(agent != null ? agent : agentService.defaultAgent().orElse(null))
                        .messages(inputMessages)
                        .model(model)
                        .build();

                StringBuilder summaryBuffer = new StringBuilder();
                llmService.stream(input, new LLMService.StreamCallback() {
                    @Override
                    public void onTextDelta(String text, Map<String, Object> metadata) {
                        summaryBuffer.append(text);
                    }
                }).join();

                String summary = summaryBuffer.toString();
                MessageV2.TextPart summaryPart = new MessageV2.TextPart();
                summaryPart.setId(UUID.randomUUID().toString());
                summaryPart.setType("text");
                summaryPart.setText(summary);
                summaryPart.setSessionID(sessionID);
                summaryPart.setMessageID(assistantInfo.getId());
                parts.add(summaryPart);
                sessionService.updateMessage(assistantMsg);

                
                if (auto) {
                    MessageV2.MessageInfo continueInfo = new MessageV2.MessageInfo();
                    continueInfo.setId(UUID.randomUUID().toString());
                    continueInfo.setSessionID(sessionID);
                    continueInfo.setRole("user");
                    continueInfo.setCreated(System.currentTimeMillis());
                    continueInfo.setAgent(userMsg.getInfo().getAgent());
                    continueInfo.setModelID(userMsg.getInfo().getModelID());
                    continueInfo.setProviderID(userMsg.getInfo().getProviderID());

                    MessageV2.TextPart continuePart = new MessageV2.TextPart();
                    continuePart.setId(UUID.randomUUID().toString());
                    continuePart.setType("text");
                    continuePart.setText("Continue if you have next steps");
                    continuePart.setSynthetic(true);
                    continuePart.setSessionID(sessionID);
                    continuePart.setMessageID(continueInfo.getId());

                    sessionService.addMessage(sessionID, new MessageV2.WithParts(continueInfo, List.of(continuePart)));
                }

                agentBus.publish("session.compacted", Map.of("sessionID", sessionID));
                return "continue";

            } catch (Exception e) {
                log.error("Compaction process failed", e);
                return "stop";
            }
        });
    }
}
