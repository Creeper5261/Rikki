package com.zzf.rikki.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.core.tool.PendingChangesManager;
import com.zzf.rikki.idea.agent.compat.AgentEventSink;
import com.zzf.rikki.idea.agent.compat.LlmChatRequest;
import com.zzf.rikki.idea.agent.compat.LlmStreamListener;
import com.zzf.rikki.idea.agent.compat.LlmStreamResult;
import com.zzf.rikki.idea.agent.compat.ModelCapabilities;
import com.zzf.rikki.idea.agent.compat.PendingCommandRecord;
import com.zzf.rikki.idea.agent.compat.PendingCommandRegistration;
import com.zzf.rikki.idea.agent.compat.RuntimeEvent;
import com.zzf.rikki.idea.agent.compat.ToolCallInfo;
import com.zzf.rikki.idea.agent.compat.ToolExecutionResult;
import com.zzf.rikki.idea.agent.tools.LiteBashTool;
import com.zzf.rikki.llm.LLMService;
import com.zzf.rikki.runtime.port.PendingApprovalPort;
import com.zzf.rikki.runtime.port.RuntimeRequest;
import com.zzf.rikki.runtime.port.ToolExecutorPort;
import com.zzf.rikki.session.model.MessageV2;
import com.zzf.rikki.session.model.PromptPart;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SessionProcessor {
    private static final int MAX_TOOL_OUTPUT = 8000;

    public static final class ProcessorResult {
        public final String answer;
        public final String messageId;
        public final boolean continueLoop;

        public ProcessorResult(String answer, String messageId, boolean continueLoop) {
            this.answer = answer;
            this.messageId = messageId;
            this.continueLoop = continueLoop;
        }
    }

    private final ObjectMapper mapper;
    private final SessionService sessionService;
    private final SessionStatus sessionStatus;
    private final PendingApprovalPort pendingApprovalService;
    private final LLMService llmService;
    private final ToolExecutorPort toolExecutor;

    public SessionProcessor(
            ObjectMapper mapper,
            SessionService sessionService,
            SessionStatus sessionStatus,
            PendingApprovalPort pendingApprovalService,
            LLMService llmService,
            ToolExecutorPort toolExecutor
    ) {
        this.mapper = mapper;
        this.sessionService = sessionService;
        this.sessionStatus = sessionStatus;
        this.pendingApprovalService = pendingApprovalService;
        this.llmService = llmService;
        this.toolExecutor = toolExecutor;
    }

    public ProcessorResult process(
            RuntimeRequest request,
            SessionInfo session,
            MessageV2.WithParts assistantMessage,
            List<MessageV2.WithParts> promptMessages,
            String systemPrompt,
            ModelCapabilities capabilities,
            List<Map<String, Object>> toolDefinitions,
            AgentEventSink sink
    ) {
        MessageV2.StepStartPart stepStart = new MessageV2.StepStartPart();
        stepStart.id = nextId("part");
        stepStart.sessionID = session.id;
        stepStart.messageID = assistantMessage.info.id;
        sessionService.updatePart(stepStart);

        final MessageV2.TextPart[] currentText = {null};
        final MessageV2.ReasoningPart[] currentReasoning = {null};

        LlmChatRequest llmRequest = new LlmChatRequest(
                assistantMessage.info.id,
                castMaps(sessionService.toLlmMessages(promptMessages, systemPrompt, capabilities.getSystemRole())),
                capabilities,
                castMaps(toolDefinitions)
        );
        LlmStreamResult llmResult = llmService.streamChat(llmRequest, new LlmStreamListener() {
            @Override
            public void onMessageDelta(String messageId, String delta) {
                if (delta == null || delta.isEmpty()) {
                    return;
                }
                if (currentText[0] == null) {
                    MessageV2.TextPart part = new MessageV2.TextPart();
                    part.id = nextId("part");
                    part.sessionID = session.id;
                    part.messageID = messageId;
                    part.time.start = System.currentTimeMillis();
                    currentText[0] = part;
                    sessionService.updatePart(part);
                }
                currentText[0].delta = delta;
                currentText[0].text += delta;
                currentText[0].time.end = System.currentTimeMillis();
                sessionService.updatePart(currentText[0]);
                sink.emit(new RuntimeEvent.MessageDelta(messageId, delta));
            }

            @Override
            public void onThoughtDelta(String messageId, String delta) {
                if (delta == null || delta.isEmpty()) {
                    return;
                }
                if (currentReasoning[0] == null) {
                    MessageV2.ReasoningPart part = new MessageV2.ReasoningPart();
                    part.id = nextId("part");
                    part.sessionID = session.id;
                    part.messageID = messageId;
                    part.time.start = System.currentTimeMillis();
                    currentReasoning[0] = part;
                    sessionService.updatePart(part);
                }
                currentReasoning[0].delta = delta;
                currentReasoning[0].text += delta;
                currentReasoning[0].time.end = System.currentTimeMillis();
                sessionService.updatePart(currentReasoning[0]);
                sink.emit(new RuntimeEvent.ThoughtDelta(messageId, delta));
            }

            @Override
            public void onThoughtEnd(String messageId) {
                if (currentReasoning[0] != null) {
                    currentReasoning[0].time.end = System.currentTimeMillis();
                    sessionService.updatePart(currentReasoning[0]);
                }
                sink.emit(new RuntimeEvent.ThoughtEnd(messageId));
            }
        });

        if (llmResult.getReasoningContent() != null && !llmResult.getReasoningContent().isBlank() && currentReasoning[0] == null) {
            MessageV2.ReasoningPart part = new MessageV2.ReasoningPart();
            part.id = nextId("part");
            part.sessionID = session.id;
            part.messageID = assistantMessage.info.id;
            part.text = llmResult.getReasoningContent();
            part.delta = llmResult.getReasoningContent();
            part.time.start = System.currentTimeMillis();
            part.time.end = part.time.start;
            sessionService.updatePart(part);
            sink.emit(new RuntimeEvent.ThoughtDelta(assistantMessage.info.id, llmResult.getReasoningContent()));
            sink.emit(new RuntimeEvent.ThoughtEnd(assistantMessage.info.id));
        }

        boolean hasToolCalls = llmResult.getToolCalls() != null && !llmResult.getToolCalls().isEmpty();
        String finishReason = hasToolCalls ? "tool-calls" : "stop";
        MessageV2.StepFinishPart stepFinish = new MessageV2.StepFinishPart();
        stepFinish.id = nextId("part");
        stepFinish.sessionID = session.id;
        stepFinish.messageID = assistantMessage.info.id;
        stepFinish.reason = finishReason;
        stepFinish.tokens = assistantMessage.info.tokens;
        stepFinish.cost = assistantMessage.info.cost;
        sessionService.updatePart(stepFinish);

        if (llmResult.getToolCalls() != null) {
            for (ToolCallInfo toolCall : llmResult.getToolCalls()) {
                Map<String, Object> argsMap = toArgsMap(toolCall.getArgs());
                MessageV2.ToolPart toolPart = new MessageV2.ToolPart();
                toolPart.id = nextId("part");
                toolPart.sessionID = session.id;
                toolPart.messageID = assistantMessage.info.id;
                toolPart.callID = toolCall.getId();
                toolPart.tool = toolCall.getName();
                toolPart.args.putAll(argsMap);
                toolPart.state.status = toolExecutor.isHighRisk(toolCall.getName(), toolCall.getArgs()) ? "pending" : "running";
                toolPart.state.input.putAll(argsMap);
                toolPart.state.title = toolCall.getName();
                toolPart.state.time.start = System.currentTimeMillis();
                sessionService.updatePart(toolPart);
                if (toolExecutor.isHighRisk(toolCall.getName(), toolCall.getArgs())) {
                    handlePendingToolCall(request, session, assistantMessage, toolPart, toolCall, argsMap, sink);
                } else {
                    sink.emit(new RuntimeEvent.ToolCall(
                            toolPart.id,
                            toolPart.tool,
                            toolPart.callID,
                            assistantMessage.info.id,
                            "running",
                            toolPart.tool,
                            argsMap,
                            null
                    ));
                    ToolExecutionResult result = toolExecutor.execute(
                            toolCall.getName(),
                            toolCall.getArgs(),
                            request.getWorkspaceRoot(),
                            session.id,
                            toolCall.getId(),
                            assistantMessage.info.id
                    );
                    applyToolResult(session, toolPart, result, sink);
                }
            }
        }

        if (!hasToolCalls && llmResult.getText() != null && llmResult.getText().startsWith("Error:")) {
            assistantMessage.info.error = new MessageV2.ErrorInfo();
            assistantMessage.info.error.message = llmResult.getText();
            assistantMessage.info.error.type = "llm_error";
            assistantMessage.info.finishReason = "error";
        } else {
            assistantMessage.info.finishReason = finishReason;
        }
        assistantMessage.info.finish = Boolean.TRUE;
        assistantMessage.info.time.end = System.currentTimeMillis();
        sessionService.updateMessage(assistantMessage);

        return new ProcessorResult(llmResult.getText(), assistantMessage.info.id, hasToolCalls);
    }

    private void handlePendingToolCall(
            RuntimeRequest request,
            SessionInfo session,
            MessageV2.WithParts assistantMessage,
            MessageV2.ToolPart toolPart,
            ToolCallInfo toolCall,
            Map<String, Object> argsMap,
            AgentEventSink sink
    ) {
        PendingCommandRecord pendingRecord = buildPendingCommandRecord(toolCall, argsMap, request.getWorkspaceRoot(), session.id, assistantMessage.info.id);
        PendingCommandRegistration registration = pendingApprovalService.registerPendingCommand(pendingRecord);
        Map<String, Object> meta = pendingMeta(registration.getPendingCommand());
        sink.emit(new RuntimeEvent.ToolCall(
                toolPart.id,
                toolPart.tool,
                toolPart.callID,
                assistantMessage.info.id,
                registration.getAwaitApproval() ? "pending" : "running",
                toolPart.tool,
                argsMap,
                meta
        ));
        toolPart.state.metadata.putAll(meta);
        sessionService.updatePart(toolPart);
        ToolExecutionResult result;
        if (registration.getAwaitApproval()) {
            sessionStatus.set(session.id, new SessionStatus.Info("waiting_approval", null, "Awaiting your approval...", null));
            sink.emit(new RuntimeEvent.StatusChanged("waiting_approval", "Awaiting your approval..."));
            if (registration.getPendingCommand().getStrictApproval()) {
                sink.emit(new RuntimeEvent.ToolPendingApproval(toolPart.id, toolPart.callID, registration.getPendingCommand().getCommand(), toolPart.tool));
            }
            result = pendingApprovalService.awaitDecision(registration.getPendingCommand().getId());
            sessionStatus.set(session.id, new SessionStatus.Info("busy", null, "Agent is thinking...", null));
            sink.emit(new RuntimeEvent.StatusChanged("busy", "Agent is thinking..."));
        } else {
            result = registration.getImmediateResult();
            if (result == null) {
                result = new ToolExecutionResult("error", "", "Auto-approved command result missing.", Map.of(), null, null, null, null, false);
            }
        }
        applyToolResult(session, toolPart, result, sink);
    }

    private void applyToolResult(SessionInfo session, MessageV2.ToolPart toolPart, ToolExecutionResult result, AgentEventSink sink) {
        Long startedAt = toolPart.state.time.start;
        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
        meta.putAll(result.getMeta());
        List<PromptPart> emittedParts = materializeEmittedParts(toolPart.sessionID, toolPart.messageID, meta.get("emitted_parts"));
        PendingChangesManager.PendingChange pendingChange = result.getPendingChange();
        if (pendingChange != null) {
            meta.put("pending_change", pendingChange);
        }
        PendingCommandRecord pendingCommand = result.getPendingCommand();
        if (pendingCommand != null) {
            meta.put("pending_command", pendingCommand.toMetaMap());
        }
        MessageV2.ToolState nextState = new MessageV2.ToolState();
        nextState.status = result.getStatus() == null || result.getStatus().isBlank() ? "completed" : result.getStatus();
        nextState.input.putAll(toolPart.args);
        nextState.output = result.getOutput() == null ? "" : truncate(result.getOutput(), MAX_TOOL_OUTPUT);
        nextState.title = toolPart.tool;
        nextState.error = result.getError();
        nextState.metadata.putAll(meta);
        nextState.time.start = startedAt;
        nextState.time.end = System.currentTimeMillis();
        toolPart.state = nextState;
        sessionService.updatePart(toolPart);
        for (PromptPart part : emittedParts) {
            sessionService.updatePart(part);
        }
        sink.emit(new RuntimeEvent.ToolResult(
                toolPart.id,
                toolPart.tool,
                toolPart.callID,
                toolPart.messageID,
                toolPart.state.status,
                toolPart.tool,
                toolPart.state.output,
                toolPart.state.error,
                meta.isEmpty() ? null : meta
        ));
        if (result.getTodoJson() != null) {
            sink.emit(new RuntimeEvent.TodoUpdated(result.getTodoJson(), session.id));
        }
    }

    private List<PromptPart> materializeEmittedParts(String sessionId, String messageId, Object rawParts) {
        List<PromptPart> parts = new ArrayList<>();
        if (!(rawParts instanceof List<?> rawList)) {
            return parts;
        }
        for (Object candidate : rawList) {
            try {
                JsonNode node = mapper.valueToTree(candidate);
                PromptPart part = sessionService.deserializePart(sessionId, messageId, node);
                if (part != null) {
                    if (part.sessionID == null || part.sessionID.isBlank()) {
                        part.sessionID = sessionId;
                    }
                    if (part.messageID == null || part.messageID.isBlank()) {
                        part.messageID = messageId;
                    }
                    if (part.id == null || part.id.isBlank()) {
                        part.id = nextId("part");
                    }
                    parts.add(part);
                }
            } catch (Exception ignored) {
            }
        }
        return parts;
    }

    private PendingCommandRecord buildPendingCommandRecord(
            ToolCallInfo toolCall,
            Map<String, Object> argsMap,
            String workspaceRoot,
            String sessionId,
            String messageId
    ) {
        String shell = toolCall.getArgs().path("shell").asText("auto");
        if (shell.isBlank()) {
            shell = "auto";
        }
        long timeoutMs = toolCall.getArgs().path("timeout").asLong(com.zzf.rikki.idea.agent.compat.CommandRunner.DEFAULT_TIMEOUT_MS);
        if (timeoutMs <= 0L) {
            timeoutMs = com.zzf.rikki.idea.agent.compat.CommandRunner.DEFAULT_TIMEOUT_MS;
        }
        String workdir = toolCall.getArgs().path("workdir").asText(workspaceRoot);
        if (workdir.isBlank()) {
            workdir = workspaceRoot;
        }
        String command = "bash".equals(toolCall.getName())
                ? toolCall.getArgs().path("command").asText("(unknown)")
                : "Delete: " + toolCall.getArgs().path("filePath").asText("(unknown)");
        boolean strictApproval = "delete_file".equals(toolCall.getName()) || LiteBashTool.Companion.isStrictApprovalCommand(command);
        String riskCategory = strictApproval ? "destructive" : "restricted";
        List<String> reasons = new ArrayList<>(LiteBashTool.Companion.detectRiskReasons(command));
        if (reasons.isEmpty() && "delete_file".equals(toolCall.getName())) {
            reasons.add("file deletion requires approval");
        }
        String description = argsMap.get("description") instanceof String value ? value : toolCall.getName();
        return new PendingCommandRecord(
                nextId("pending"),
                command,
                description,
                workdir,
                workspaceRoot,
                sessionId,
                timeoutMs,
                toolCall.getName(),
                toolCall.getId(),
                messageId,
                "high",
                riskCategory,
                LiteBashTool.Companion.commandFamily(command),
                strictApproval,
                reasons,
                shell,
                () -> toolExecutor.execute(
                        toolCall.getName(),
                        toolCall.getArgs(),
                        workspaceRoot,
                        sessionId,
                        toolCall.getId(),
                        messageId
                )
        );
    }

    private Map<String, Object> pendingMeta(PendingCommandRecord pendingCommand) {
        List<String> approvalOptions = pendingCommand.getStrictApproval()
                ? List.of("manual")
                : List.of("manual", "whitelist", "always_allow_non_destructive");
        return mapOf(
                "pending_command", pendingCommand.toMetaMap(),
                "approval_options", approvalOptions,
                "requires_explicit_user_consent", Boolean.TRUE,
                "risk_level", pendingCommand.getRiskLevel(),
                "risk_category", pendingCommand.getRiskCategory(),
                "risk_reasons", pendingCommand.getReasons()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toArgsMap(JsonNode args) {
        try {
            return mapper.convertValue(args, LinkedHashMap.class);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Map<String, Object>> castMaps(List<? extends Map<String, ?>> raw) {
        return (List) raw;
    }

    private static LinkedHashMap<String, Object> mapOf(Object... pairs) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max);
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
