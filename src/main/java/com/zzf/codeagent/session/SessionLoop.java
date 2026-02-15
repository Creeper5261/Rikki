package com.zzf.codeagent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.agent.AgentInfo;
import com.zzf.codeagent.agent.AgentService;
import com.zzf.codeagent.bus.AgentBus;
import com.zzf.codeagent.id.Identifier;
import com.zzf.codeagent.llm.LLMService;
import com.zzf.codeagent.provider.ProviderManager;
import com.zzf.codeagent.provider.ModelInfo;
import com.zzf.codeagent.session.SessionInfo;
import com.zzf.codeagent.session.model.MessageV2;
import com.zzf.codeagent.session.model.PromptPart;
import com.zzf.codeagent.shell.ShellService;
import com.zzf.codeagent.core.tool.PendingChangesManager;
import com.zzf.codeagent.core.tool.PendingCommandsManager;
import com.zzf.codeagent.core.tool.Tool;
import com.zzf.codeagent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReAct Loop 閺嶇绺鹃柅鏄忕帆 (鐎靛綊缍?OpenCode prompt.ts loop)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionLoop {

    private static final int MAX_TOOL_DESCRIPTION_FOR_FUNCTION = 1024;
    private static final long TOOL_WAIT_TIMEOUT_MS = Long.getLong("codeagent.toolWaitTimeoutMs", 30L * 60L * 1000L);
    private static final long TOOL_WAIT_POLL_MS = Long.getLong("codeagent.toolWaitPollMs", 150L);

    private final SessionService sessionService;
    private final AgentService agentService;
    private final ToolRegistry toolRegistry;
    private final LLMService llmService;
    private final InstructionPrompt instructionPrompt;
    private final ShellService shellService;
    private final ContextCompactionService compactionService;
    private final SystemPrompt systemPrompt;
    private final PromptReminderService reminderService;
    private final ObjectMapper objectMapper;
    private final SessionStatus sessionStatus;

    private final SessionProcessorFactory processorFactory;
    private final ProviderManager providerManager;

    @Async
    public void start(String sessionID, String userInput) {
        log.info("Starting session loop for session: {}", sessionID);
        loop(sessionID, userInput).exceptionally(e -> {
            log.error("Loop failed", e);
            return null;
        });
    }

    /**
     * ReAct Loop 閺嶇绺鹃柅鏄忕帆 (鐎靛綊缍?OpenCode prompt.ts loop)
     */
    public CompletableFuture<MessageV2.WithParts> loop(String sessionID, String userInput) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Get/Create Session & User Message
                SessionInfo session = sessionService.get(sessionID)
                        .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionID));

                if (userInput != null && !userInput.isEmpty()) {
                    // Add User Message
                    MessageV2.MessageInfo userInfo = new MessageV2.MessageInfo();
                    userInfo.setId(Identifier.ascending("message"));
                    userInfo.setSessionID(sessionID);
                    userInfo.setRole("user");
                    userInfo.setCreated(System.currentTimeMillis());
                    
                    // Aligned with OpenCode: Resolve agent from session if missing
                    String agentName = session.getAgent();
                    if (agentName == null || agentName.isEmpty()) {
                        agentName = agentService.defaultAgent().map(AgentInfo::getName).orElse("build");
                    }
                    userInfo.setAgent(agentName);
                    
                    MessageV2.User user = new MessageV2.User();
                    user.setId(Identifier.ascending("user"));
                    userInfo.setUser(user);

                    MessageV2.TextPart textPart = new MessageV2.TextPart();
                    textPart.setId(Identifier.ascending("part"));
                    textPart.setType("text");
                    textPart.setText(userInput);
                    textPart.setMessageID(userInfo.getId());
                    textPart.setSessionID(sessionID);

                    sessionService.addMessage(sessionID, new MessageV2.WithParts(userInfo, new ArrayList<>(List.of(textPart))));
                    log.info("Added user message {} to session {}", userInfo.getId(), sessionID);
                }

                int step = 0;
                while (true) {
                    sessionStatus.set(sessionID, SessionStatus.Info.builder().type("busy").build());
                    log.info("Loop step: {}, sessionID: {}", step, sessionID);
                    
                    List<MessageV2.WithParts> history = sessionService.getFilteredMessages(sessionID);
                    log.info("History size for session {}: {}", sessionID, history.size());
                    
                    // Identify last messages and pending tasks
                    MessageV2.WithParts lastUserMsg = null;
                    MessageV2.WithParts lastAssistantMsg = null;
                    MessageV2.WithParts lastFinishedMsg = null;
                    List<PromptPart> pendingTasks = new ArrayList<>();

                    for (int i = history.size() - 1; i >= 0; i--) {
                        MessageV2.WithParts m = history.get(i);
                        if (lastUserMsg == null && "user".equals(m.getInfo().getRole())) {
                            lastUserMsg = m;
                        }
                        if (lastAssistantMsg == null && "assistant".equals(m.getInfo().getRole())) {
                            lastAssistantMsg = m;
                        }
                        if (lastFinishedMsg == null && "assistant".equals(m.getInfo().getRole()) && Boolean.TRUE.equals(m.getInfo().getFinish())) {
                            lastFinishedMsg = m;
                        }

                        // Collect pending tasks if not finished
                        if (lastFinishedMsg == null) {
                            for (PromptPart p : m.getParts()) {
                                if (p instanceof MessageV2.CompactionPart || p instanceof MessageV2.SubtaskPart) {
                                    pendingTasks.add(p);
                                }
                            }
                        }

                        if (lastUserMsg != null && lastFinishedMsg != null) break;
                    }

                    if (lastUserMsg == null) {
                        log.error("No user message found in history for session {}. Total messages in session: {}", 
                            sessionID, sessionService.getMessages(sessionID).size());
                        throw new RuntimeException("No user message found");
                    }

                    // Check if we should exit loop
                    if (lastAssistantMsg != null && Boolean.TRUE.equals(lastAssistantMsg.getInfo().getFinish())) {
                        String finish = lastAssistantMsg.getInfo().getFinishReason();
                        boolean hasActiveToolCalls = lastAssistantMsg.getParts().stream()
                                .filter(p -> p instanceof MessageV2.ToolPart)
                                .map(p -> (MessageV2.ToolPart) p)
                                .filter(p -> p.getState() != null)
                                .anyMatch(p -> {
                                    String status = p.getState().getStatus();
                                    return "pending".equalsIgnoreCase(status) || "running".equalsIgnoreCase(status);
                                });
                        
                        // Fix for Issue 3: Don't exit if message only contains reasoning
                        boolean hasContent = lastAssistantMsg.getParts().stream()
                                .anyMatch(p -> p instanceof MessageV2.TextPart || p instanceof MessageV2.ToolPart);

                        if (!"tool-calls".equals(finish) && !"unknown".equals(finish) && !hasActiveToolCalls &&
                            lastUserMsg.getInfo().getId().compareTo(lastAssistantMsg.getInfo().getId()) <= 0) {
                            
                            if (hasContent) {
                                log.info("Exiting loop for session: {}", sessionID);
                                break;
                            } else {
                                log.info("Assistant finished but produced no content (only reasoning?), continuing loop. Finish reason: {}", finish);
                                // Fallthrough to continue
                            }
                        }
                    }

                    step++;
                    
                    // Get Model
                    ModelInfo model = null;
                    if (lastUserMsg.getInfo().getModelID() != null) {
                        model = providerManager.getModel(lastUserMsg.getInfo().getProviderID(), lastUserMsg.getInfo().getModelID()).orElse(null);
                    }
                    if (model == null) {
                        model = providerManager.getDefaultModel();
                    }
                    
                    if (model == null) {
                        log.error("Model is still null after resolving default model! lastUserMsg: {}", lastUserMsg.getInfo());
                        throw new RuntimeException("Failed to resolve model for session: " + sessionID);
                    }

                    // Process pending tasks (Subtask or Compaction)
                    if (!pendingTasks.isEmpty()) {
                        PromptPart task = pendingTasks.get(pendingTasks.size() - 1);
                        if (task instanceof MessageV2.SubtaskPart) {
                            executeSubtask((MessageV2.SubtaskPart) task, sessionID, lastUserMsg, history, model);
                            continue;
                        } else if (task instanceof MessageV2.CompactionPart) {
                            MessageV2.CompactionPart cp = (MessageV2.CompactionPart) task;
                            String result = compactionService.process(sessionID, lastUserMsg.getInfo().getId(), history, cp.isAuto()).join();
                            if ("stop".equals(result)) break;
                            continue;
                        }
                    }

                    // Context overflow check
                    if (lastFinishedMsg != null && !Boolean.TRUE.equals(lastFinishedMsg.getInfo().getSummary())) {
                        if (compactionService.isOverflow(lastFinishedMsg.getInfo().getTokens(), model)) {
                            log.info("Context overflow detected, triggering compaction");
                            compactionService.process(sessionID, lastUserMsg.getInfo().getId(), history, true).join();
                            continue;
                        }
                    }

                    // Normal processing
                    AgentInfo agent = agentService.get(lastUserMsg.getInfo().getAgent()).orElse(agentService.defaultAgent().orElseThrow());
                    
                    log.info("Starting interaction loop step: {}, session: {}, model: {}", step, sessionID, model.getId());
                    log.info("Processing message history: {} messages total", history.size());
                    log.info("Current agent: {}", agent.getName());
                    
                    // Reminders
                    history = reminderService.insertReminders(history, agent, session);
                    if (step > 1 && lastAssistantMsg != null) {
                        reminderService.wrapMidLoopUserMessages(history, lastAssistantMsg.getInfo().getId());
                    }

                    // Create Processor
                    MessageV2.Assistant assistantInfo = MessageV2.Assistant.builder()
                            .id(Identifier.ascending("message"))
                            .parentID(lastUserMsg.getInfo().getId())
                            .role("assistant")
                            .sessionID(sessionID)
                            .agent(agent.getName())
                            .modelID(model.getId())
                            .providerID(model.getProviderID())
                            .time(MessageV2.MessageTime.builder().created(System.currentTimeMillis()).build())
                            .tokens(MessageV2.TokenUsage.builder().input(0).output(0).reasoning(0).cache(new MessageV2.CacheUsage()).build())
                            .cost(0.0)
                            .build();
                    
                    sessionService.addMessage(sessionID, new MessageV2.WithParts(assistantInfo.toInfo(), new ArrayList<>()));
                    
                    SessionProcessor processor = processorFactory.create(assistantInfo, sessionID, model);
                    
                    // Resolve Tools
                    boolean bypassAgentCheck = lastUserMsg.getParts().stream()
                            .anyMatch(p -> p instanceof MessageV2.AgentPart);
                            
                    Map<String, Tool> tools = resolveTools(agent, model, session, history, processor, bypassAgentCheck);
                    
                    // LLM Stream Input
                    List<String> instructions = new ArrayList<>();
                    instructions.addAll(systemPrompt.environment(model, session.getDirectory()));
                    instructions.addAll(systemPrompt.provider(model));
                    instructions.addAll(instructionPrompt.system().join());

                    LLMService.StreamInput streamInput = LLMService.StreamInput.builder()
                            .sessionID(sessionID)
                            .agent(agent)
                            .model(model)
                            .user(lastUserMsg.getInfo().getUser())
                            .messages(history)
                            .systemInstructions(instructions)
                            .tools(convertToToolDefinitions(tools))
                            .build();

                    String nextAction = processor.process(streamInput, tools).join();
                    
                    // Wait for any running tools to complete
                    waitForRunningTools(sessionID, assistantInfo.getId(), session.getDirectory());

                    if ("stop".equals(nextAction)) {
                        // Double check if we really should stop (e.g. if we have tool calls)
                        // If we have tool calls, we should continue to let the agent see the results
                        MessageV2.WithParts updatedMsg = sessionService.getMessage(assistantInfo.getId());
                        boolean hasActiveToolCalls = updatedMsg != null && updatedMsg.getParts().stream()
                                .filter(p -> p instanceof MessageV2.ToolPart)
                                .map(p -> (MessageV2.ToolPart) p)
                                .filter(p -> p.getState() != null)
                                .anyMatch(p -> {
                                    String status = p.getState().getStatus();
                                    return "pending".equalsIgnoreCase(status) || "running".equalsIgnoreCase(status);
                                });
                        
                        if (!hasActiveToolCalls) {
                            break;
                        }
                    }
                    if ("compact".equals(nextAction)) {
                        compactionService.process(sessionID, lastUserMsg.getInfo().getId(), history, true).join();
                    }
                }
            } catch (Exception e) {
                log.error("Loop failed", e);
                throw new RuntimeException(e);
            } finally {
                sessionStatus.set(sessionID, SessionStatus.Info.builder().type("idle").build());
            }
            
            // Return last assistant message
            return sessionService.getFilteredMessages(sessionID).stream()
                    .filter(m -> "assistant".equals(m.getInfo().getRole()))
                    .reduce((first, second) -> second)
                    .orElse(null);
        });
    }

    private void waitForRunningTools(String sessionID, String messageID, String workspaceRoot) {
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + Math.max(TOOL_WAIT_TIMEOUT_MS, 5_000L);
        boolean timedOut = false;

        while (true) {
            MessageV2.WithParts msg = sessionService.getMessage(messageID);
            boolean hasRunningTools = msg != null && msg.getParts().stream()
                    .filter(p -> p instanceof MessageV2.ToolPart)
                    .map(p -> (MessageV2.ToolPart) p)
                    .filter(p -> p.getState() != null)
                    .anyMatch(p -> {
                        String status = p.getState().getStatus();
                        return "running".equalsIgnoreCase(status) || "pending".equalsIgnoreCase(status);
                    });
            boolean hasPendingCommands = PendingCommandsManager.getInstance()
                    .hasPendingForScope(workspaceRoot, sessionID);
            boolean hasPendingDeletes = PendingChangesManager.getInstance()
                    .hasPendingDeleteForScope(workspaceRoot, sessionID);

            if (!hasRunningTools && !hasPendingCommands && !hasPendingDeletes) {
                return;
            }

            if (System.currentTimeMillis() >= deadline) {
                timedOut = true;
                break;
            }

            try {
                Thread.sleep(Math.max(TOOL_WAIT_POLL_MS, 50L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (timedOut) {
            log.warn("Timeout waiting for tools/approvals in session {} message {}. pending commands/deletes may still exist.", sessionID, messageID);
        } else {
            log.warn("Stopped waiting for tools/approvals due to interruption in session {} message {}", sessionID, messageID);
        }
    }

    private Map<String, Object> convertToToolDefinitions(Map<String, Tool> tools) {
        Map<String, Object> definitions = new HashMap<>();
        for (Map.Entry<String, Tool> entry : tools.entrySet()) {
            Tool tool = entry.getValue();

            Map<String, Object> function = new HashMap<>();
            function.put("name", tool.getId());
            function.put("description", sanitizeToolDescription(tool.getDescription(), MAX_TOOL_DESCRIPTION_FOR_FUNCTION));

            JsonNode schema = tool.getParametersSchema();
            if (schema == null || schema.isNull()) {
                function.put("parameters", Map.of("type", "object", "properties", new HashMap<>()));
            } else {
                function.put("parameters", objectMapper.convertValue(schema, Map.class));
            }

            Map<String, Object> openAiTool = new HashMap<>();
            openAiTool.put("type", "function");
            openAiTool.put("function", function);

            definitions.put(entry.getKey(), openAiTool);
        }
        return definitions;
    }

    private String sanitizeToolDescription(String rawDescription, int maxLength) {
        if (rawDescription == null || rawDescription.isEmpty()) {
            return "";
        }
        String compact = rawDescription.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxLength) {
            return compact;
        }
        return compact.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }

    private Map<String, Tool> resolveTools(AgentInfo agent, ModelInfo model, SessionInfo session, List<MessageV2.WithParts> history, SessionProcessor processor, boolean bypassAgentCheck) {
        Map<String, Tool> tools = new HashMap<>();
        
        // 1. Get tools from registry
        List<Tool> availableTools = toolRegistry.getTools(model.getId(), agent);
        
        for (Tool tool : availableTools) {
            // Wrap tool to inject context (Align with prompt.ts resolveTools)
            Tool wrappedTool = new Tool() {
                @Override
                public String getId() { return tool.getId(); }
                @Override
                public String getDescription() { return tool.getDescription(); }
                @Override
                public JsonNode getParametersSchema() { return tool.getParametersSchema(); }

                @Override
                public CompletableFuture<Result> execute(JsonNode args, Context context) {
                    // Create Context for this execution
                    Context ctx = Context.builder()
                            .sessionID(session.getId())
                            .messageID(processor.getMessage().getId())
                            .agent(agent.getName())
                            .callID(context != null ? context.getCallID() : Identifier.random("call"))
                            .messages(history)
                            .extra(new HashMap<>(Map.of(
                                    "model", model,
                                    "bypassAgentCheck", bypassAgentCheck,
                                    "workspaceRoot", session.getDirectory() == null ? "" : session.getDirectory()
                            )))
                            .metadataConsumer((title, metadata) -> {
                                MessageV2.ToolPart part = processor.partFromToolCall(context != null ? context.getCallID() : Identifier.random("call"));
                                if (part != null && "running".equals(part.getState().getStatus())) {
                                    part.getState().setTitle(title);
                                    part.getState().setMetadata(metadata);
                                    sessionService.updatePart(part);
                                }
                            })
                            .permissionAsker(req -> {
                                // Simplified permission check
                                log.info("Requesting permission: {} for session: {}", req, session.getId());
                                return CompletableFuture.completedFuture(null);
                            })
                            .build();
                    
                    return tool.execute(args, ctx);
                }
            };
            tools.put(tool.getId(), wrappedTool);
        }
        
        return tools;
    }

    private void executeSubtask(MessageV2.SubtaskPart task, String sessionID, MessageV2.WithParts lastUserMsg, List<MessageV2.WithParts> history, ModelInfo model) {
        log.info("Executing subtask: {}", task.getDescription());
        
        // Create Assistant Message for subtask
        MessageV2.Assistant assistantMsg = MessageV2.Assistant.builder()
                .id(Identifier.ascending("message"))
                .parentID(lastUserMsg.getInfo().getId())
                .role("assistant")
                .sessionID(sessionID)
                .agent(task.getAgent())
                .modelID(model.getId())
                .providerID(model.getProviderID())
                .time(MessageV2.MessageTime.builder().created(System.currentTimeMillis()).build())
                .tokens(MessageV2.TokenUsage.builder().input(0).output(0).reasoning(0).cache(new MessageV2.CacheUsage()).build())
                .cost(0.0)
                .build();
        
        sessionService.addMessage(sessionID, new MessageV2.WithParts(assistantMsg.toInfo(), new ArrayList<>()));
        
        // Create Tool Part for the subtask execution (e.g. TaskTool)
        String callID = Identifier.random("call");
        MessageV2.ToolPart toolPart = new MessageV2.ToolPart();
        toolPart.setId(Identifier.ascending("part"));
        toolPart.setMessageID(assistantMsg.getId());
        toolPart.setSessionID(sessionID);
        toolPart.setType("tool");
        toolPart.setTool("task");
        toolPart.setCallID(callID);
        
        MessageV2.ToolState state = new MessageV2.ToolState();
        state.setStatus("running");
        Map<String, Object> taskInput = new HashMap<>();
        taskInput.put("prompt", task.getPrompt());
        taskInput.put("description", task.getDescription());
        taskInput.put("agent", task.getAgent());
        state.setInput(taskInput);
        state.setTime(new MessageV2.ToolState.TimeInfo());
        state.getTime().setStart(System.currentTimeMillis());
        toolPart.setState(state);
        
        sessionService.updatePart(toolPart);
        
        // Execute the subtask
        Tool taskTool = toolRegistry.get("task").orElse(null);
        if (taskTool != null) {
            // Create Context for TaskTool
            Tool.Context ctx = Tool.Context.builder()
                    .sessionID(sessionID)
                    .messageID(assistantMsg.getId())
                    .agent(assistantMsg.getAgent())
                    .callID(callID)
                    .messages(history)
                    .extra(Map.of("bypassAgentCheck", true))
                    .metadataConsumer((title, meta) -> {
                        toolPart.getState().setTitle(title);
                        toolPart.getState().setMetadata(meta);
                        sessionService.updatePart(toolPart);
                    })
                    .permissionAsker(req -> CompletableFuture.completedFuture(null))
                    .build();

            taskTool.execute(objectMapper.valueToTree(taskInput), ctx)
                .thenAccept(result -> {
                    assistantMsg.setFinish(true);
                    assistantMsg.setFinishReason("tool-calls");
                    assistantMsg.getTime().setEnd(System.currentTimeMillis());
                    sessionService.updateMessage(assistantMsg.withParts());

                    state.setStatus("completed");
                    state.setTitle(result.getTitle());
                    state.setOutput(result.getOutput());
                    state.setMetadata(result.getMetadata());
                    state.getTime().setEnd(System.currentTimeMillis());
                    sessionService.updatePart(toolPart);
                })
                .exceptionally(e -> {
                    state.setStatus("error");
                    state.setError(e.toString());
                    state.getTime().setEnd(System.currentTimeMillis());
                    sessionService.updatePart(toolPart);
                    return null;
                });
        }
    }


}


