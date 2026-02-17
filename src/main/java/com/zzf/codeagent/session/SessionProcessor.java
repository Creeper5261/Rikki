package com.zzf.codeagent.session;

import com.zzf.codeagent.id.Identifier;
import com.zzf.codeagent.llm.LLMService;
import com.zzf.codeagent.provider.ModelInfo;
import com.zzf.codeagent.session.model.MessageV2;
import com.zzf.codeagent.session.model.PromptPart;
import com.zzf.codeagent.core.tool.Tool;
import com.zzf.codeagent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 浼氳瘽澶勭悊鍣?(瀵归綈 OpenCode SessionProcessor)
 * 澶勭悊 LLM 涓叉祦杈撳嚭銆佸伐鍏疯皟鐢ㄨВ鏋愪笌鐘舵€佸悓姝? */
@Slf4j
@RequiredArgsConstructor
public class SessionProcessor {

    private static final int DOOM_LOOP_THRESHOLD = 3;

    private final MessageV2.Assistant assistantMessage;
    private final String sessionID;
    private final ModelInfo model;
    private final SessionService sessionService;
    private final SessionStatus sessionStatus;
    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final ContextCompactionService compactionService;
    private final ObjectMapper objectMapper;

    private final Map<String, MessageV2.ToolPart> toolcalls = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Tool.Result>> runningToolExecutions = new ConcurrentHashMap<>();
    private final Map<String, Tool> runningExecutionTools = new ConcurrentHashMap<>();
    private final AtomicBoolean needsCompaction = new AtomicBoolean(false);
    private final AtomicBoolean blocked = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private int attempt = 0;
    private volatile CompletableFuture<Void> llmStreamFuture;
    private volatile CompletableFuture<String> activeResultFuture;

    public MessageV2.Assistant getMessage() {
        return assistantMessage;
    }

    public MessageV2.ToolPart partFromToolCall(String toolCallID) {
        return toolcalls.get(toolCallID);
    }

    private LLMService.StreamInput streamInput;
    private Map<String, Tool> tools;

    public CompletableFuture<String> process(LLMService.StreamInput streamInput, Map<String, Tool> tools) {
        log.info("Processing session stream: {}", sessionID);
        this.streamInput = streamInput;
        this.tools = tools;
        needsCompaction.set(false);
        blocked.set(false);
        cancelRequested.set(false);
        runningToolExecutions.clear();
        runningExecutionTools.clear();
        attempt = 0;
        
        CompletableFuture<String> result = new CompletableFuture<>();
        activeResultFuture = result;
        processLoop(streamInput, tools, result);
        return result;
    }

    public void cancel(String reason) {
        if (!cancelRequested.compareAndSet(false, true)) {
            return;
        }
        log.info("Cancelling session processor for session {}: {}", sessionID, reason);
        CompletableFuture<Void> streamFuture = llmStreamFuture;
        if (streamFuture != null && !streamFuture.isDone()) {
            streamFuture.cancel(true);
        }
        cancelRunningTools(reason);
        markRunningToolsCancelled(reason);
        CompletableFuture<String> result = activeResultFuture;
        if (result != null && !result.isDone()) {
            result.complete("stop");
        }
    }

    private void processLoop(LLMService.StreamInput streamInput, Map<String, Tool> tools, CompletableFuture<String> result) {
        llmStreamFuture = llmService.stream(streamInput, new LLMService.StreamCallback() {
            private MessageV2.TextPart currentText;
            private Map<String, MessageV2.ReasoningPart> reasoningMap = new HashMap<>();
            private String snapshot;

            
            private StringBuilder xmlBuffer = new StringBuilder();
            private boolean inXmlTool = false;
            private boolean inThought = false;
            private String currentXmlToolName = null;
            private String currentXmlToolId = null;
            private MessageV2.ReasoningPart currentReasoning = null;
            private String activeReasoningKey = null;
            private boolean providerReasoningObserved = false;

            @Override
            public void onStart() {
                if (cancelRequested.get()) {
                    return;
                }
                sessionStatus.set(sessionID, SessionStatus.Info.builder().type("busy").build());
            }

            @Override
            public void onStepStart() {
                if (cancelRequested.get()) {
                    return;
                }
                
                MessageV2.StepStartPart part = new MessageV2.StepStartPart();
                part.setId(Identifier.ascending("part"));
                part.setMessageID(assistantMessage.getId());
                part.setSessionID(sessionID);
                part.setType("step-start");
                
                sessionService.updatePart(part);
            }

            @Override
            public void onStepFinish(String finishReason, Map<String, Object> usage, Map<String, Object> metadata) {
                if (cancelRequested.get()) {
                    return;
                }
                assistantMessage.setFinish(true);
                assistantMessage.setFinishReason(normalizeFinishReason(finishReason));
                
                
                if (usage != null) {
                    MessageV2.TokenUsage tokenUsage = assistantMessage.getTokens();
                    if (tokenUsage == null) {
                        tokenUsage = MessageV2.TokenUsage.builder()
                                .input(0)
                                .output(0)
                                .reasoning(0)
                                .cache(new MessageV2.CacheUsage())
                                .build();
                        assistantMessage.setTokens(tokenUsage);
                    }
                    if (usage.containsKey("prompt_tokens")) tokenUsage.setInput(asInt(usage.get("prompt_tokens")));
                    if (usage.containsKey("completion_tokens")) tokenUsage.setOutput(asInt(usage.get("completion_tokens")));
                    if (usage.containsKey("reasoning_tokens")) tokenUsage.setReasoning(asInt(usage.get("reasoning_tokens")));
                }
                
                MessageV2.StepFinishPart part = new MessageV2.StepFinishPart();
                part.setId(Identifier.ascending("part"));
                part.setMessageID(assistantMessage.getId());
                part.setSessionID(sessionID);
                part.setType("step-finish");
                part.setReason(normalizeFinishReason(finishReason));
                
                part.setTokens(assistantMessage.getTokens());
                part.setCost(assistantMessage.getCost());
                
                sessionService.updatePart(part);

                
                if (isOverflow(usage)) {
                    needsCompaction.set(true);
                }
            }

            @Override
            public void onTextStart(String id, Map<String, Object> metadata) {
                if (cancelRequested.get()) {
                    return;
                }
                currentText = new MessageV2.TextPart();
                currentText.setId(Identifier.ascending("part"));
                currentText.setMessageID(assistantMessage.getId());
                currentText.setSessionID(sessionID);
                currentText.setType("text");
                currentText.setText("");
                currentText.setTime(MessageV2.PartTime.builder().start(System.currentTimeMillis()).build());
                currentText.setMetadata(metadata);
                
                assistantMessage.getParts().add(currentText);
                sessionService.updatePart(currentText);
            }

            @Override
            public synchronized void onTextDelta(String text, Map<String, Object> metadata) {
                if (text == null || text.isEmpty()) return;
                if (cancelRequested.get()) {
                    return;
                }
                
                
                xmlBuffer.append(text);
                
                
                processXmlBuffer(metadata);
            }
            
            private void processXmlBuffer(Map<String, Object> metadata) {
                String content = xmlBuffer.toString();
                
                
                if (!inXmlTool && !providerReasoningObserved) {
                    if (!inThought) {
                        
                        java.util.regex.Matcher tm = java.util.regex.Pattern.compile("<(thought|think|thinking)([\\s>])").matcher(content);
                        if (tm.find()) {
                            int thoughtStart = tm.start();
                            if (thoughtStart != -1) {
                                if (thoughtStart > 0) {
                                    handleOriginalTextDelta(content.substring(0, thoughtStart), metadata);
                                }
                                currentText = null; 
                                inThought = true;
                                
                                
                                currentReasoning = new MessageV2.ReasoningPart();
                                currentReasoning.setId(Identifier.ascending("part"));
                                currentReasoning.setMessageID(assistantMessage.getId());
                                currentReasoning.setSessionID(sessionID);
                                currentReasoning.setType("reasoning");
                                currentReasoning.setText("");
                                currentReasoning.setDelta("");
                                currentReasoning.setTime(MessageV2.PartTime.builder().start(System.currentTimeMillis()).build());
                                
                                assistantMessage.getParts().add(currentReasoning);
                                sessionService.updatePart(currentReasoning);
                                
                                int tagEnd = content.indexOf('>', thoughtStart);
                                if (tagEnd != -1) {
                                    xmlBuffer.delete(0, tagEnd + 1);
                                    processXmlBuffer(metadata);
                                    return;
                                } else {
                                    
                                    xmlBuffer.delete(0, thoughtStart);
                                    inThought = false; 
                                    return;
                                }
                            }
                        }
                    } else {
                        
                        int thoughtEnd = -1;
                        int thoughtTagLen = 0;
                        
                        int end1 = content.indexOf("</thought>");
                        int end2 = content.indexOf("</think>");
                        int end3 = content.indexOf("</thinking>");
                        
                        
                        if (end1 != -1) {
                            thoughtEnd = end1;
                            thoughtTagLen = 10;
                        }
                        if (end2 != -1 && (thoughtEnd == -1 || end2 < thoughtEnd)) {
                            thoughtEnd = end2;
                            thoughtTagLen = 8;
                        }
                        if (end3 != -1 && (thoughtEnd == -1 || end3 < thoughtEnd)) {
                            thoughtEnd = end3;
                            thoughtTagLen = 11;
                        }

                        if (thoughtEnd != -1) {
                            String thoughtContent = content.substring(0, thoughtEnd);
                            handleReasoningDelta(thoughtContent, metadata);
                            inThought = false;
                            finalizeCurrentReasoning(metadata);
                            xmlBuffer.delete(0, thoughtEnd + thoughtTagLen);
                            processXmlBuffer(metadata);
                            return;
                        } else {
                            
                            int safeLen = content.length() - 10;
                            if (safeLen > 0) {
                                handleReasoningDelta(content.substring(0, safeLen), metadata);
                                xmlBuffer.delete(0, safeLen);
                            }
                            return;
                        }
                    }
                }

                if (!inXmlTool && !inThought) {
                    
                    
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("<([a-zA-Z0-9_]+)[\\s>]").matcher(content);
                    if (m.find()) {
                        String potentialToolName = m.group(1);
                        boolean isWriteAlias = false; 
                        
                        
                        if (tools != null && (tools.containsKey(potentialToolName) || isWriteAlias)) {
                            int start = m.start();
                            int tagClose = content.indexOf('>', start);
                            if (tagClose != -1) {
                                if (start > 0) handleOriginalTextDelta(content.substring(0, start), metadata);
                                
                                currentText = null; 
                                inXmlTool = true;
                                currentXmlToolName = potentialToolName;
                                currentXmlToolId = "xml_" + System.currentTimeMillis();
                                onToolInputStart(currentXmlToolName, currentXmlToolId);
                                
                                
                                String tagContent = content.substring(start + 1, tagClose);
                                Map<String, String> attributes = parseAttributes(tagContent);
                                if (!attributes.isEmpty()) {
                                    MessageV2.ToolPart part = toolcalls.get(currentXmlToolId);
                                    if (part != null) {
                                        part.getState().getInput().putAll(attributes);
                                        sessionService.updatePart(part);
                                    }
                                }
                                
                                xmlBuffer.delete(0, tagClose + 1);
                                processXmlBuffer(metadata);
                                return;
                            } else {
                                
                                
                                if (start > 0) {
                                    handleOriginalTextDelta(content.substring(0, start), metadata);
                                    xmlBuffer.delete(0, start);
                                }
                                return;
                            }
                        }
                    }
                    
                    int lastOpen = content.lastIndexOf('<');
                    if (lastOpen == -1) {
                        handleOriginalTextDelta(content, metadata);
                        xmlBuffer.setLength(0);
                    } else if (lastOpen > 0) {
                        handleOriginalTextDelta(content.substring(0, lastOpen), metadata);
                        xmlBuffer.delete(0, lastOpen);
                    } else { 
                        
                        if (content.indexOf('>') != -1 || content.indexOf('\n') != -1 || content.length() > 50) {
                            handleOriginalTextDelta(content, metadata);
                            xmlBuffer.setLength(0);
                        }
                    }
                } else if (inXmlTool) { 
                    String closing = "</" + currentXmlToolName + ">";
                    int idx = content.indexOf(closing);
                    if (idx != -1) {
                        updateToolInput(currentXmlToolId, content.substring(0, idx));
                        
                        Map<String, Object> finalInput = new HashMap<>();
                        MessageV2.ToolPart part = toolcalls.get(currentXmlToolId);
                        if (part != null) {
                            finalInput.putAll(part.getState().getInput());
                            
                            
                            String xmlContent = (String) finalInput.get("xml_content");
                            if (xmlContent != null) {
                                if ("bash".equals(currentXmlToolName)) {
                                    finalInput.put("command", xmlContent.trim());
                                } else if ("tool_code".equals(currentXmlToolName)) {
                                    finalInput.put("code", xmlContent.trim());
                                } else if ("write".equals(currentXmlToolName) || "edit".equals(currentXmlToolName)) {
                                    finalInput.put("content", xmlContent);
                                }
                            }
                        }
                        onToolCall(currentXmlToolName, currentXmlToolId, finalInput, metadata);
                        inXmlTool = false;
                        currentXmlToolName = null;
                        currentXmlToolId = null;
                        xmlBuffer.delete(0, idx + closing.length());
                        processXmlBuffer(metadata);
                    } else {
                        int safeLen = content.length() - (currentXmlToolName.length() + 3);
                        if (safeLen > 0) {
                            updateToolInput(currentXmlToolId, content.substring(0, safeLen));
                            xmlBuffer.delete(0, safeLen);
                        }
                    }
                }
            }
            
            private void handleReasoningDelta(String text, Map<String, Object> metadata) {
                if (currentReasoning == null) return;
                
                currentReasoning.setText(currentReasoning.getText() + text);
                
                MessageV2.ReasoningPart update = new MessageV2.ReasoningPart();
                update.setId(currentReasoning.getId());
                update.setMessageID(currentReasoning.getMessageID());
                update.setSessionID(currentReasoning.getSessionID());
                update.setType(currentReasoning.getType());
                update.setText(currentReasoning.getText());
                update.setDelta(text);
                update.setTime(currentReasoning.getTime());
                
                sessionService.updatePart(update);
            }

            private void finalizeCurrentReasoning(Map<String, Object> metadata) {
                if (currentReasoning == null) {
                    return;
                }
                currentReasoning.setText(currentReasoning.getText().trim());
                currentReasoning.setCollapsed(true);
                if (currentReasoning.getTime() != null) {
                    currentReasoning.getTime().setEnd(System.currentTimeMillis());
                }
                if (metadata != null) {
                    currentReasoning.setMetadata(metadata);
                }

                MessageV2.ReasoningPart update = new MessageV2.ReasoningPart();
                update.setId(currentReasoning.getId());
                update.setMessageID(currentReasoning.getMessageID());
                update.setSessionID(currentReasoning.getSessionID());
                update.setType(currentReasoning.getType());
                update.setText(currentReasoning.getText());
                update.setDelta(null);
                update.setTime(currentReasoning.getTime());
                update.setCollapsed(true);
                update.setMetadata(currentReasoning.getMetadata());
                sessionService.updatePart(update);

                currentReasoning = null;
            }

            private Map<String, String> parseAttributes(String tagContent) {
                Map<String, String> attributes = new HashMap<>();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\w+)=[\"']([^\"']*)[\"']").matcher(tagContent);
                while (m.find()) {
                    attributes.put(m.group(1), m.group(2));
                }
                return attributes;
            }

            private void handleOriginalTextDelta(String text, Map<String, Object> metadata) {
                if (currentText == null) {
                    onTextStart(Identifier.ascending("part"), metadata);
                }
                
                
                
                String processedText = text;
                
                
                
                
                
                currentText.setText(currentText.getText() + processedText);
                
                MessageV2.TextPart update = new MessageV2.TextPart();
                update.setId(currentText.getId());
                update.setMessageID(currentText.getMessageID());
                update.setSessionID(currentText.getSessionID());
                update.setType(currentText.getType());
                update.setText(currentText.getText());
                update.setDelta(processedText);
                update.setTime(currentText.getTime());
                update.setMetadata(metadata != null ? metadata : currentText.getMetadata());
                
                sessionService.updatePart(update);
            }
            
            private void updateToolInput(String id, String text) {
                MessageV2.ToolPart part = toolcalls.get(id);
                if (part != null) {
                    Map<String, Object> input = part.getState().getInput();
                    String current = (String) input.getOrDefault("xml_content", "");
                    input.put("xml_content", current + text);
                    sessionService.updatePart(part);
                }
            }

            @Override
            public void onTextEnd(Map<String, Object> metadata) {
                if (cancelRequested.get()) {
                    return;
                }
                
                if (xmlBuffer.length() > 0) {
                    if (inXmlTool) {
                        updateToolInput(currentXmlToolId, xmlBuffer.toString());
                    } else {
                        handleOriginalTextDelta(xmlBuffer.toString(), metadata);
                    }
                }
                
                
                if (inXmlTool) {
                    log.warn("Tool {} was not closed properly. Forcing close.", currentXmlToolName);
                    Map<String, Object> finalInput = new HashMap<>();
                    MessageV2.ToolPart part = toolcalls.get(currentXmlToolId);
                    if (part != null) {
                        finalInput.putAll(part.getState().getInput());
                    }
                    onToolCall(currentXmlToolName, currentXmlToolId, finalInput, metadata);
                    
                    inXmlTool = false;
                    currentXmlToolName = null;
                    currentXmlToolId = null;
                }

                if (inThought) {
                    inThought = false;
                    finalizeCurrentReasoning(metadata);
                }
                
                if (currentText != null) {
                    log.info("Assistant text response: {}", currentText.getText());
                    currentText.setText(currentText.getText().trim());
                    currentText.getTime().setEnd(System.currentTimeMillis());
                    if (metadata != null) currentText.setMetadata(metadata);
                    
                    
                    MessageV2.TextPart update = new MessageV2.TextPart();
                    update.setId(currentText.getId());
                    update.setMessageID(currentText.getMessageID());
                    update.setSessionID(currentText.getSessionID());
                    update.setType(currentText.getType());
                    update.setText(currentText.getText());
                    update.setDelta(null);
                    update.setTime(currentText.getTime());
                    update.setMetadata(currentText.getMetadata());
                    
                    sessionService.updatePart(update);
                    currentText = null;
                }
            }

            @Override
            public void onReasoningStart(String id, Map<String, Object> metadata) {
                if (cancelRequested.get()) {
                    return;
                }
                providerReasoningObserved = true;
                String reasoningKey = (id == null || id.isBlank()) ? "default" : id;
                activeReasoningKey = reasoningKey;
                MessageV2.ReasoningPart part = new MessageV2.ReasoningPart();
                part.setId(Identifier.ascending("part"));
                part.setMessageID(assistantMessage.getId());
                part.setSessionID(sessionID);
                part.setType("reasoning");
                part.setText("");
                part.setCollapsed(false); 
                part.setTime(MessageV2.PartTime.builder().start(System.currentTimeMillis()).build());
                part.setMetadata(metadata);
                
                reasoningMap.put(reasoningKey, part);
                assistantMessage.getParts().add(part);
                sessionService.updatePart(part);
            }

            @Override
            public synchronized void onReasoningDelta(String text, Map<String, Object> metadata) {
                if (cancelRequested.get()) {
                    return;
                }
                String id = activeReasoningKey != null ? activeReasoningKey : "default";
                MessageV2.ReasoningPart part = reasoningMap.get(id);
                if (part == null) {
                    onReasoningStart(id, metadata);
                    part = reasoningMap.get(id);
                }
                part.setText(part.getText() + text);
                
                
                MessageV2.ReasoningPart update = new MessageV2.ReasoningPart();
                update.setId(part.getId());
                update.setMessageID(part.getMessageID());
                update.setSessionID(part.getSessionID());
                update.setType(part.getType());
                update.setText(part.getText());
                update.setDelta(text); 
                update.setTime(part.getTime());
                update.setCollapsed(part.getCollapsed());
                update.setMetadata(metadata != null ? metadata : part.getMetadata());
                
                sessionService.updatePart(update);
            }

            @Override
            public void onReasoningEnd(Map<String, Object> metadata) {
                if (cancelRequested.get()) {
                    return;
                }
                String id = activeReasoningKey != null ? activeReasoningKey : "default";
                MessageV2.ReasoningPart part = reasoningMap.get(id);
                if (part != null) {
                    part.setText(part.getText().trim());
                    part.setCollapsed(true);
                    if (part.getTime() != null) {
                        part.getTime().setEnd(System.currentTimeMillis());
                    }
                    if (metadata != null) part.setMetadata(metadata);
                    
                    
                    MessageV2.ReasoningPart update = new MessageV2.ReasoningPart();
                    update.setId(part.getId());
                    update.setMessageID(part.getMessageID());
                    update.setSessionID(part.getSessionID());
                    update.setType(part.getType());
                    update.setText(part.getText());
                    update.setDelta(null);
                    update.setTime(part.getTime());
                    update.setCollapsed(true);
                    update.setMetadata(part.getMetadata());
                    
                    sessionService.updatePart(update);
                    log.info("Assistant reasoning: {}", part.getText());
                    reasoningMap.remove(id);
                }
                activeReasoningKey = null;
            }

            @Override
            public void onToolInputStart(String name, String id) {
                if (cancelRequested.get()) {
                    return;
                }
                String callId = (id == null || id.isBlank()) ? "call_" + System.currentTimeMillis() : id;
                MessageV2.ToolPart part = new MessageV2.ToolPart();
                part.setId(Identifier.ascending("part"));
                part.setMessageID(assistantMessage.getId());
                part.setSessionID(sessionID);
                part.setType("tool");
                part.setTool(name);
                part.setCallID(callId);
                
                MessageV2.ToolState state = new MessageV2.ToolState();
                state.setStatus("pending");
                state.setInput(new HashMap<>());
                part.setState(state);
                
                toolcalls.put(callId, part);
                assistantMessage.getParts().add(part);
                sessionService.updatePart(part);
            }

            @Override
            public void onToolCall(String name, String id, Map<String, Object> input, Map<String, Object> metadata) {
                if (cancelRequested.get()) {
                    return;
                }
                String callId = (id == null || id.isBlank()) ? "call_" + System.currentTimeMillis() : id;
                Map<String, Object> safeInput = input != null ? new HashMap<>(input) : new HashMap<>();
                log.info("Assistant calling tool: {} with input: {}", name, safeInput);
                MessageV2.ToolPart part = toolcalls.get(callId);
                if (part == null) {
                    onToolInputStart(name, callId);
                    part = toolcalls.get(callId);
                }
                
                part.setTool(name);
                part.setArgs(safeInput); 
                part.getState().setStatus("running");
                part.getState().setInput(new HashMap<>(safeInput));
                part.getState().setTime(new MessageV2.ToolState.TimeInfo());
                part.getState().getTime().setStart(System.currentTimeMillis());
                part.setMetadata(metadata);
                
                sessionService.updatePart(part);
                
                
                checkDoomLoop(name, safeInput);

                
                Tool tool = tools.get(name);
                if (tool != null) {
                    String validationError = validateToolInput(tool, safeInput);
                    if (validationError != null) {
                        onToolError(id, safeInput, new IllegalArgumentException(validationError));
                        return;
                    }

                    log.info("Executing tool: {}", name);
                    Tool.Context ctx = Tool.Context.builder()
                            .sessionID(sessionID)
                            .messageID(assistantMessage.getId())
                            .agent(assistantMessage.getAgent())
                            .callID(callId)
                            .messages(sessionService.getMessages(sessionID))
                            .extra(buildToolContextExtra())
                            .metadataConsumer((title, meta) -> {
                                MessageV2.ToolPart tp = toolcalls.get(callId);
                                if (tp != null) {
                                    tp.getState().setTitle(title);
                                    tp.getState().setMetadata(meta);
                                    sessionService.updatePart(tp);
                                }
                            })
                            .permissionAsker(req -> {
                                
                                return CompletableFuture.completedFuture(null);
                            })
                            .build();

                    JsonNode args = objectMapper.valueToTree(safeInput);
                    CompletableFuture<Tool.Result> execution = tool.execute(args, ctx);
                    runningToolExecutions.put(callId, execution);
                    runningExecutionTools.put(callId, tool);
                    
                    
                    execution.orTimeout(60, TimeUnit.SECONDS)
                        .thenAccept(res -> {
                            runningToolExecutions.remove(callId);
                            runningExecutionTools.remove(callId);
                            if (cancelRequested.get()) {
                                onToolError(callId, safeInput, new CancellationException("Tool execution cancelled"));
                                return;
                            }
                            onToolResult(callId, safeInput, res.getOutput(), res.getMetadata());
                        })
                        .exceptionally(e -> {
                            runningToolExecutions.remove(callId);
                            runningExecutionTools.remove(callId);
                            onToolError(callId, safeInput, e);
                            return null;
                        });
                } else {
                    onToolError(callId, safeInput, new RuntimeException("Tool not found: " + name));
                }
            }

            @Override
            public void onToolResult(String id, Map<String, Object> input, Object output, Map<String, Object> metadata) {
                MessageV2.ToolPart part = toolcalls.get(id);
                if (part != null) {
                    log.info("Tool {} execution result: {}", part.getTool(), output);
                    part.getState().setStatus("completed");
                    part.getState().setInput(input != null ? input : part.getState().getInput());
                    part.getState().setOutput(output.toString());
                    part.getState().setMetadata(metadata);
                    if (part.getState().getTime() == null) part.getState().setTime(new MessageV2.ToolState.TimeInfo());
                    part.getState().getTime().setEnd(System.currentTimeMillis());
                    
                    sessionService.updatePart(part);
                    toolcalls.remove(id);
                }
            }

            @Override
            public void onToolError(String id, Map<String, Object> input, Throwable error) {
                MessageV2.ToolPart part = toolcalls.get(id);
                if (part != null) {
                    boolean cancelled = isCancellationThrowable(error) || cancelRequested.get();
                    part.getState().setStatus(cancelled ? "cancelled" : "error");
                    part.getState().setInput(input != null ? input : part.getState().getInput());
                    if (cancelled) {
                        String msg = error != null && error.getMessage() != null
                                ? error.getMessage()
                                : "Tool execution cancelled";
                        part.getState().setOutput(msg);
                        part.getState().setError("");
                    } else {
                        part.getState().setError(error == null ? "Unknown tool error" : error.toString());
                    }
                    if (part.getState().getTime() == null) part.getState().setTime(new MessageV2.ToolState.TimeInfo());
                    part.getState().getTime().setEnd(System.currentTimeMillis());
                    
                    sessionService.updatePart(part);
                    toolcalls.remove(id);
                }
            }

    @Override
    public void onComplete(String finishReason) {
        if (cancelRequested.get()) {
            markRunningToolsCancelled("Cancelled by user");
            assistantMessage.setFinish(true);
            assistantMessage.setFinishReason("cancelled");
            if (assistantMessage.getTime() == null) {
                assistantMessage.setTime(MessageV2.MessageTime.builder().build());
            }
            assistantMessage.getTime().setEnd(System.currentTimeMillis());
            sessionService.updateMessage(assistantMessage.withParts());
            result.complete("stop");
            return;
        }
        if (assistantMessage.getTime() == null) {
            assistantMessage.setTime(MessageV2.MessageTime.builder().build());
        }
        if (assistantMessage.getFinishReason() == null || assistantMessage.getFinishReason().isEmpty()) {
            assistantMessage.setFinish(true);
            assistantMessage.setFinishReason(normalizeFinishReason(finishReason));
        }
        assistantMessage.getTime().setEnd(System.currentTimeMillis());
        sessionService.updateMessage(assistantMessage.withParts());
        
        if (needsCompaction.get()) {
            result.complete("compact");
        } else if (blocked.get() || assistantMessage.getError() != null) {
            result.complete("stop");
        } else {
            String normalized = normalizeFinishReason(assistantMessage.getFinishReason());
            if ("tool-calls".equals(normalized) || "unknown".equals(normalized)) {
                result.complete("continue");
            } else {
                result.complete("stop");
            }
        }
    }

    @Override
    public void onError(Throwable t) {
        if (cancelRequested.get() || isCancellationThrowable(t)) {
            log.info("Stream cancelled for session {}", sessionID);
            markRunningToolsCancelled("Cancelled by user");
            assistantMessage.setFinish(true);
            assistantMessage.setFinishReason("cancelled");
            if (assistantMessage.getTime() == null) {
                assistantMessage.setTime(MessageV2.MessageTime.builder().build());
            }
            assistantMessage.getTime().setEnd(System.currentTimeMillis());
            sessionService.updateMessage(assistantMessage.withParts());
            result.complete("stop");
            return;
        }
        log.error("Stream error", t);
        String retryableMsg = SessionRetry.getRetryableMessage(t);
        if (retryableMsg != null && attempt < 3) {
            attempt++;
            long delay = SessionRetry.getDelay(attempt, t);
            sessionStatus.set(sessionID, SessionStatus.Info.builder()
                    .type("retry")
                    .attempt(attempt)
                    .message(retryableMsg)
                    .next(System.currentTimeMillis() + delay)
                    .build());
            
            SessionRetry.sleep(delay);
            processLoop(streamInput, tools, result);
        } else {
            assistantMessage.setError(MessageV2.ErrorInfo.builder()
                    .message(t.getMessage())
                    .type("error")
                    .build());
            sessionService.updateMessage(assistantMessage.withParts());
            result.complete("stop");
        }
    }
        }, () -> cancelRequested.get() || Thread.currentThread().isInterrupted());
    }

    private void cancelRunningTools(String reason) {
        for (Map.Entry<String, CompletableFuture<Tool.Result>> entry : runningToolExecutions.entrySet()) {
            String callID = entry.getKey();
            CompletableFuture<Tool.Result> execution = entry.getValue();
            Tool tool = runningExecutionTools.get(callID);
            if (tool != null) {
                try {
                    tool.cancel(sessionID, callID);
                } catch (Exception e) {
                    log.warn("Failed to cancel tool {} call {} in session {}", tool.getId(), callID, sessionID, e);
                }
            }
            if (execution != null && !execution.isDone()) {
                execution.cancel(true);
            }
        }
        runningToolExecutions.clear();
        runningExecutionTools.clear();
    }

    private void markRunningToolsCancelled(String reason) {
        String message = (reason == null || reason.isBlank()) ? "Cancelled by user" : reason;
        for (MessageV2.ToolPart part : new java.util.ArrayList<>(toolcalls.values())) {
            if (part == null || part.getState() == null) {
                continue;
            }
            String status = part.getState().getStatus();
            if (!"running".equalsIgnoreCase(status) && !"pending".equalsIgnoreCase(status)) {
                continue;
            }
            part.getState().setStatus("cancelled");
            part.getState().setOutput(message);
            part.getState().setError("");
            if (part.getState().getTime() == null) {
                part.getState().setTime(new MessageV2.ToolState.TimeInfo());
            }
            part.getState().getTime().setEnd(System.currentTimeMillis());
            sessionService.updatePart(part);
        }
    }

    private boolean isCancellationThrowable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CancellationException || current instanceof InterruptedException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("cancelled")
                        || normalized.contains("canceled")
                        || normalized.contains("interrupted")) {
                    return true;
                }
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    private Map<String, Object> buildToolContextExtra() {
        Map<String, Object> extra = new HashMap<>();
        sessionService.get(sessionID).ifPresent(session -> {
            if (session.getDirectory() != null && !session.getDirectory().isBlank()) {
                extra.put("workspaceRoot", session.getDirectory());
            }
            if (session.getWorkspaceName() != null && !session.getWorkspaceName().isBlank()) {
                extra.put("workspaceName", session.getWorkspaceName());
            }
            if (session.getIdeContext() != null && !session.getIdeContext().isEmpty()) {
                extra.put("ideContext", new HashMap<>(session.getIdeContext()));
            }
        });
        return extra;
    }

    private void checkDoomLoop(String toolName, Map<String, Object> input) {
        List<PromptPart> parts = assistantMessage.getParts();
        if (parts.size() < DOOM_LOOP_THRESHOLD) return;
        
        long count = parts.stream()
                .filter(p -> p instanceof MessageV2.ToolPart)
                .map(p -> (MessageV2.ToolPart) p)
                .filter(tp -> toolName.equals(tp.getTool()) && input.equals(tp.getState().getInput()))
                .count();
                
        if (count >= DOOM_LOOP_THRESHOLD) {
            log.warn("Doom loop detected for tool: {} with input: {}", toolName, input);
            
        }
    }

    private boolean isOverflow(Map<String, Object> usage) {
        if (usage == null) return false;
        
        MessageV2.TokenUsage tokenUsage = new MessageV2.TokenUsage();
        if (usage.containsKey("prompt_tokens")) tokenUsage.setInput(asInt(usage.get("prompt_tokens")));
        if (usage.containsKey("completion_tokens")) tokenUsage.setOutput(asInt(usage.get("completion_tokens")));
        if (usage.containsKey("reasoning_tokens")) tokenUsage.setReasoning(asInt(usage.get("reasoning_tokens")));
        
        return compactionService.isOverflow(tokenUsage, model);
    }

    private int asInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return Math.max(0, ((Number) value).intValue());
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value).trim()));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String validateToolInput(Tool tool, Map<String, Object> input) {
        JsonNode schema = tool.getParametersSchema();
        if (schema == null || schema.isNull()) {
            return null;
        }
        JsonNode required = schema.get("required");
        if (required == null || !required.isArray()) {
            return null;
        }
        for (JsonNode requiredField : required) {
            String field = requiredField.asText();
            if (field == null || field.isEmpty()) {
                continue;
            }
            if (!input.containsKey(field) || input.get(field) == null) {
                return "Missing required parameter '" + field + "' for tool '" + tool.getId() + "'";
            }
        }
        return null;
    }

    private String normalizeFinishReason(String finishReason) {
        if ("tool_calls".equals(finishReason)) {
            return "tool-calls";
        }
        return finishReason;
    }

    public static SessionProcessor create(
            MessageV2.Assistant assistantMessage,
            String sessionID,
            ModelInfo model,
            SessionService sessionService,
            SessionStatus sessionStatus,
            LLMService llmService,
            ToolRegistry toolRegistry,
            ContextCompactionService compactionService,
            ObjectMapper objectMapper) {
        return new SessionProcessor(assistantMessage, sessionID, model, sessionService, sessionStatus, llmService, toolRegistry, compactionService, objectMapper);
    }
}
