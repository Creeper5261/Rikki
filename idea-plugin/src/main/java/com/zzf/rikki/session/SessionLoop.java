package com.zzf.rikki.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.project.Project;
import com.zzf.rikki.core.tool.BackendToolDefinitions;
import com.zzf.rikki.idea.agent.compat.AgentEventSink;
import com.zzf.rikki.idea.agent.compat.LiteModelSupport;
import com.zzf.rikki.idea.agent.compat.ModelCapabilities;
import com.zzf.rikki.idea.agent.compat.RuntimeEvent;
import com.zzf.rikki.idea.agent.tools.LiteIdeTools;
import com.zzf.rikki.idea.settings.RikkiSettings;
import com.zzf.rikki.runtime.port.LlmPort;
import com.zzf.rikki.runtime.port.PendingApprovalPort;
import com.zzf.rikki.runtime.port.RuntimeRequest;
import com.zzf.rikki.runtime.port.ToolExecutorPort;
import com.zzf.rikki.session.model.MessageV2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SessionLoop {
    private static final int MAX_STEPS = 120;

    private final SessionService sessionService;
    private final SessionStatus sessionStatus;
    private final SystemPrompt systemPrompt;
    private final InstructionPrompt instructionPrompt;
    private final PromptReminderService reminderService;
    private final SessionProcessor processor;
    private final ToolExecutorPort toolExecutor;

    public SessionLoop(
            Project project,
            ObjectMapper mapper,
            PendingApprovalPort pendingApprovalPort,
            LlmPort llmPort,
            ToolExecutorPort toolExecutor
    ) {
        this.sessionService = new SessionService(mapper);
        this.sessionStatus = new SessionStatus();
        this.systemPrompt = new SystemPrompt(mapper);
        this.instructionPrompt = new InstructionPrompt();
        this.reminderService = new PromptReminderService();
        this.processor = new SessionProcessor(mapper, sessionService, sessionStatus, pendingApprovalPort, llmPort, toolExecutor);
        this.toolExecutor = toolExecutor;
    }

    public void run(RuntimeRequest request, AgentEventSink sink) {
        RikkiSettings.State settings = RikkiSettings.Companion.getInstance().getState();
        SessionInfo session = sessionService.getOrCreate(request.getSessionId(), request.getWorkspaceRoot());
        boolean reused = !sessionService.getMessages(session.id).isEmpty();

        sink.emit(new RuntimeEvent.SessionBound(session.id, reused));
        sessionStatus.set(session.id, new SessionStatus.Info("busy", null, "Agent is thinking...", null));
        sink.emit(new RuntimeEvent.StatusChanged("busy", "Agent is thinking..."));

        toolExecutor.setIdeContext(request.getIdeContext());
        sessionService.importHistory(session.id, request.getHistory());
        if (request.getGoal() != null && !request.getGoal().isBlank()) {
            sessionService.addUserMessage(session.id, request.getGoal());
        }

        ModelCapabilities capabilities = LiteModelSupport.INSTANCE.detectCapabilities(settings.getProvider(), settings.getModelName());
        LiteIdeTools.CapabilitySnapshot ideCapabilities = toolExecutor.refreshIdeCapabilities();
        List<Map<String, Object>> toolDefinitions = BackendToolDefinitions.build(request.getWorkspaceRoot(), ideCapabilities);

        String answer = "";
        String lastMessageId = "";
        for (int step = 0; step < MAX_STEPS; step++) {
            List<String> promptSections = new ArrayList<>();
            promptSections.add(systemPrompt.build(
                    request.getWorkspaceRoot(),
                    request.getIdeContext(),
                    capabilities,
                    ideCapabilities,
                    settings.getModelName()
            ));
            promptSections.addAll(instructionPrompt.system(request.getWorkspaceRoot()));
            promptSections.addAll(reminderService.reminders(session, sessionService.getMessages(session.id)));
            String composedSystemPrompt = promptSections.stream()
                    .filter(part -> part != null && !part.isBlank())
                    .reduce((a, b) -> a + "\n\n" + b)
                    .orElse("");

            List<MessageV2.WithParts> history = sessionService.copyMessages(sessionService.getFilteredMessages(session.id));
            history = reminderService.insertReminders(history, session);
            reminderService.wrapMidLoopUserMessages(history, lastFinishedAssistantId(history));

            MessageV2.WithParts assistantMessage = sessionService.startAssistantMessage(session.id, settings.getProvider(), settings.getModelName());
            SessionProcessor.ProcessorResult result = processor.process(
                    request,
                    session,
                    assistantMessage,
                    history,
                    composedSystemPrompt,
                    capabilities,
                    toolDefinitions,
                    sink
            );
            answer = result.answer;
            lastMessageId = result.messageId;
            if (!result.continueLoop) {
                break;
            }
        }

        sink.emit(new RuntimeEvent.Finished(session.id, lastMessageId, answer));
        sessionStatus.set(session.id, new SessionStatus.Info("idle", null, "Ready", null));
        sink.emit(new RuntimeEvent.StatusChanged("idle", "Ready"));
    }

    private String lastFinishedAssistantId(List<MessageV2.WithParts> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            MessageV2.WithParts message = history.get(i);
            if (message != null
                    && message.info != null
                    && "assistant".equals(message.info.role)
                    && Boolean.TRUE.equals(message.info.finish)) {
                return message.info.id;
            }
        }
        return null;
    }
}