package com.zzf.codeagent.controller;

import com.zzf.codeagent.bus.AgentBus;
import com.zzf.codeagent.id.Identifier;
import com.zzf.codeagent.session.SessionInfo;
import com.zzf.codeagent.session.SessionLoop;
import com.zzf.codeagent.session.SessionService;
import com.zzf.codeagent.session.model.MessageV2;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Chat Controller (閫傞厤 Plugin SSE)
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentChatController {

    private final SessionLoop sessionLoop;
    private final SessionService sessionService;
    private final AgentBus agentBus;
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, List<Runnable>> unsubs = new ConcurrentHashMap<>();

    @Data
    public static class ChatRequest {
        private String sessionID;
        private String traceId; // Backward compatible alias for sessionID
        private String message;
        private String goal; // Alias for message, sent by plugin
        private String parentID;
        private String workspaceRoot;
        private String workspaceName;
        private String agent;
        private String ideContextContent;
        private String ideContextPath;
        private Map<String, Object> ideContext;
        private Map<String, Object> settings;
        private List<String> history;

        public String getEffectiveMessage() {
            return (message != null && !message.isEmpty()) ? message : goal;
        }

        public String getEffectiveSessionID() {
            return (sessionID != null && !sessionID.isEmpty()) ? sessionID : traceId;
        }
    }

    @PostMapping("/chat/stream")
    public SseEmitter chat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(60 * 60 * 1000L); // 60 min timeout

        String requestedSessionID = request.getEffectiveSessionID();
        SessionInfo session;
        if (requestedSessionID == null || requestedSessionID.isEmpty()) {
            session = sessionService.create(request.getParentID(), null, request.getWorkspaceRoot());
            log.info("Created new session: {}", session.getId());
        } else {
            session = sessionService.get(requestedSessionID).orElseGet(() -> {
                SessionInfo created = sessionService.create(request.getParentID(), null, request.getWorkspaceRoot());
                log.warn("Incoming session not found: {}. Fallback to new session: {}", requestedSessionID, created.getId());
                return created;
            });
        }

        String sessionID = session.getId();
        if (request.getWorkspaceRoot() != null && !request.getWorkspaceRoot().isEmpty()
                && !request.getWorkspaceRoot().equals(session.getDirectory())) {
            sessionService.update(sessionID, s -> s.setDirectory(request.getWorkspaceRoot()));
        }
        if (request.getAgent() != null && !request.getAgent().isEmpty()) {
            final String requestedAgent = request.getAgent();
            sessionService.update(sessionID, s -> s.setAgent(requestedAgent));
        }
        if (request.getWorkspaceName() != null && !request.getWorkspaceName().isEmpty()) {
            final String requestedWorkspaceName = request.getWorkspaceName();
            sessionService.update(sessionID, s -> s.setWorkspaceName(requestedWorkspaceName));
        }
        Map<String, Object> sanitizedIdeContext = sanitizeIdeContext(request.getIdeContext());
        if (!sanitizedIdeContext.isEmpty()) {
            sessionService.update(sessionID, s -> s.setIdeContext(sanitizedIdeContext));
        }

        final String finalSessionID = sessionID;
        final Set<String> emittedFinishMessageIDs = ConcurrentHashMap.newKeySet();
        emitters.put(finalSessionID, emitter);
        List<Runnable> sessionUnsubs = new ArrayList<>();
        unsubs.put(finalSessionID, sessionUnsubs);

        emitter.onCompletion(() -> cleanup(finalSessionID));
        emitter.onTimeout(() -> cleanup(finalSessionID));
        emitter.onError((e) -> cleanup(finalSessionID));

        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("sessionID", finalSessionID);
        sessionData.put("requestedSessionID", requestedSessionID);
        sessionData.put("reused", requestedSessionID != null && requestedSessionID.equals(finalSessionID));
        sendSse(emitter, "session", sessionData);

        // Heartbeat to keep connection alive
        java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        java.util.concurrent.ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
            try {
                sendSse(emitter, "heartbeat", System.currentTimeMillis());
            } catch (Exception e) {
                // Ignore, cleanup will handle it if connection is closed
            }
        }, 15, 15, java.util.concurrent.TimeUnit.SECONDS);
        sessionUnsubs.add(() -> {
            heartbeat.cancel(true);
            scheduler.shutdown();
        });

        // Subscribe to Bus events and forward to SSE
        sessionUnsubs.add(agentBus.subscribe("part.updated", event -> {
            Object part = event.getProperties();

            if (part instanceof com.zzf.codeagent.session.model.MessageV2.TextPart) {
                com.zzf.codeagent.session.model.MessageV2.TextPart tp = (com.zzf.codeagent.session.model.MessageV2.TextPart) part;
                if (tp.getSessionID().equals(finalSessionID)) {
                    if (tp.getDelta() != null && !tp.getDelta().isEmpty()) {
                        Map<String, Object> deltaEvent = new HashMap<>();
                        deltaEvent.put("id", tp.getMessageID());
                        deltaEvent.put("delta", tp.getDelta());
                        sendSse(emitter, "message", deltaEvent);
                    } else if (tp.getText() != null && !tp.getText().isBlank()) {
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("id", tp.getMessageID());
                        payload.put("messageID", tp.getMessageID());
                        payload.put("text", tp.getText());
                        sendSse(emitter, "message_part", payload);
                    }
                }
            } else if (part instanceof com.zzf.codeagent.session.model.MessageV2.ReasoningPart) {
                com.zzf.codeagent.session.model.MessageV2.ReasoningPart rp = (com.zzf.codeagent.session.model.MessageV2.ReasoningPart) part;
                if (rp.getSessionID().equals(finalSessionID)) {
                    if (rp.getDelta() != null && !rp.getDelta().isEmpty()) {
                        Map<String, Object> deltaEvent = new HashMap<>();
                        deltaEvent.put("id", rp.getMessageID());
                        deltaEvent.put("reasoning_delta", rp.getDelta());
                        if (rp.getId() != null) {
                            deltaEvent.put("partId", rp.getId());
                        }
                        if (rp.getCollapsed() != null) {
                            deltaEvent.put("collapsed", rp.getCollapsed());
                        }
                        sendSse(emitter, "thought", deltaEvent);
                    } else if (Boolean.TRUE.equals(rp.getCollapsed())) {
                        Map<String, Object> doneEvent = new HashMap<>();
                        doneEvent.put("id", rp.getMessageID());
                        if (rp.getId() != null) {
                            doneEvent.put("partId", rp.getId());
                        }
                        sendSse(emitter, "thought_end", doneEvent);
                    }
                }
            } else if (part instanceof com.zzf.codeagent.session.model.MessageV2.ToolPart) {
                com.zzf.codeagent.session.model.MessageV2.ToolPart tp = (com.zzf.codeagent.session.model.MessageV2.ToolPart) part;
                if (tp.getSessionID().equals(finalSessionID)) {
                    // Tool handling
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("id", tp.getId());
                    payload.put("partID", tp.getId());
                    payload.put("partId", tp.getId());
                    payload.put("tool", tp.getTool());
                    payload.put("callID", tp.getCallID());
                    payload.put("messageID", tp.getMessageID());
                    payload.put("messageId", tp.getMessageID());

                    if (tp.getState() != null) {
                        payload.put("state", tp.getState().getStatus());
                        payload.put("title", tp.getState().getTitle());
                        if (tp.getState().getMetadata() != null && !tp.getState().getMetadata().isEmpty()) {
                            payload.put("meta", sanitizeMeta(tp.getState().getMetadata()));
                        }
                        
                        boolean isCompleted = "completed".equalsIgnoreCase(tp.getState().getStatus()) || 
                                              "error".equalsIgnoreCase(tp.getState().getStatus());
                        
                        if (isCompleted) {
                            payload.put("output", truncateForUi(tp.getState().getOutput(), 8000));
                            payload.put("error", tp.getState().getError());
                            sendSse(emitter, "tool_result", payload);

                            if (tp.getState().getMetadata() != null) {
                                if (tp.getState().getMetadata().containsKey("pending_change")) {
                                    Map<String, Object> artifact = new HashMap<>();
                                    artifact.put("type", "diff");
                                    artifact.put("change", tp.getState().getMetadata().get("pending_change"));
                                    artifact.put("messageID", tp.getMessageID());
                                    artifact.put("messageId", tp.getMessageID());
                                    sendSse(emitter, "artifact_update", artifact);
                                }
                                if (tp.getState().getMetadata().containsKey("file_view")) {
                                    Map<String, Object> artifact = new HashMap<>();
                                    artifact.put("type", "file");
                                    artifact.put("file", tp.getState().getMetadata().get("file_view"));
                                    artifact.put("messageID", tp.getMessageID());
                                    artifact.put("messageId", tp.getMessageID());
                                    sendSse(emitter, "artifact_view", artifact);
                                }
                            }
                        } else {
                            // Truncate args to prevent UI lag (Issue 4)
                            Map<String, Object> safeArgs = new HashMap<>();
                            if (tp.getArgs() != null) {
                                tp.getArgs().forEach((key, val) -> {
                                    if (val instanceof String && ((String) val).length() > 500) {
                                        safeArgs.put(key, ((String) val).substring(0, 500) + "... (truncated)");
                                    } else {
                                        safeArgs.put(key, val);
                                    }
                                });
                            }
                            payload.put("args", safeArgs);
                            sendSse(emitter, "tool_call", payload);
                        }
                    }
                }
            }
        }));

        sessionUnsubs.add(agentBus.subscribe("session.status", event -> {
            Object props = event.getProperties();
            if (props instanceof com.zzf.codeagent.session.SessionStatus.StatusEvent) {
                com.zzf.codeagent.session.SessionStatus.StatusEvent statusEvent = (com.zzf.codeagent.session.SessionStatus.StatusEvent) props;
                if (finalSessionID.equals(statusEvent.getSessionID())) {
                    sendSse(emitter, "status", statusEvent.getStatus());
                    if (statusEvent.getStatus() != null && "idle".equalsIgnoreCase(statusEvent.getStatus().getType())) {
                        emitter.complete();
                    }
                }
            }
        }));

        sessionUnsubs.add(agentBus.subscribe("message.updated", event -> {
            Object props = event.getProperties();
            if (props instanceof com.zzf.codeagent.session.model.MessageV2.WithParts) {
                com.zzf.codeagent.session.model.MessageV2.WithParts msg = (com.zzf.codeagent.session.model.MessageV2.WithParts) props;
                if (finalSessionID.equals(msg.getInfo().getSessionID()) && "assistant".equals(msg.getInfo().getRole())) {
                    if (shouldEmitFinish(msg, emittedFinishMessageIDs)) {
                        // Extract answer and thought from parts for plugin compatibility
                        String answer = msg.getParts().stream()
                                .filter(p -> p instanceof com.zzf.codeagent.session.model.MessageV2.TextPart)
                                .map(p -> ((com.zzf.codeagent.session.model.MessageV2.TextPart) p).getText())
                                .filter(Objects::nonNull)
                                .collect(java.util.stream.Collectors.joining("\n"));
                        
                        String thought = msg.getParts().stream()
                                .filter(p -> p instanceof com.zzf.codeagent.session.model.MessageV2.ReasoningPart)
                                .map(p -> ((com.zzf.codeagent.session.model.MessageV2.ReasoningPart) p).getText())
                                .filter(Objects::nonNull)
                                .collect(java.util.stream.Collectors.joining("\n"));

                        Map<String, Object> finishData = new java.util.HashMap<>();
                        finishData.put("answer", answer);
                        finishData.put("thought", thought);
                        finishData.put("traceId", msg.getInfo().getSessionID());
                        finishData.put("sessionID", msg.getInfo().getSessionID());
                        finishData.put("messageID", msg.getInfo().getId());
                        
                        // Add metadata for changes if present
                        if (msg.getInfo().getSummaryInfo() != null) {
                            Map<String, Object> meta = new java.util.HashMap<>();
                            meta.put("pendingChanges", msg.getInfo().getSummaryInfo().getDiffs());
                            finishData.put("meta", meta);
                        }

                        sendSse(emitter, "finish", finishData);
                        emitter.complete();
                    }
                }
            }
        }));

        sessionUnsubs.add(agentBus.subscribe("session.error", event -> {
            Object props = event.getProperties();
            if (props instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) props;
                if (finalSessionID.equals(map.get("sessionID"))) {
                    sendSse(emitter, "error", map);
                    emitter.complete();
                }
            }
        }));

        hydrateSessionHistoryIfNeeded(finalSessionID, request);

        // Start the loop with IDE context (if present)
        sessionLoop.start(
                finalSessionID,
                request.getEffectiveMessage(),
                buildAdditionalSystemInstructions(request, sanitizedIdeContext)
        );

        return emitter;
    }

    private List<String> buildAdditionalSystemInstructions(
            ChatRequest request,
            Map<String, Object> sanitizedIdeContext
    ) {
        if (request == null || sanitizedIdeContext == null || sanitizedIdeContext.isEmpty()) {
            return List.of();
        }

        List<String> instructions = new ArrayList<>();
        instructions.add("IDE environment context is available via tool `ide_context`. Fetch it only when needed.");
        Object bridgeUrlObj = sanitizedIdeContext.get("ideBridgeUrl");
        if (bridgeUrlObj instanceof String && !((String) bridgeUrlObj).isBlank()) {
            instructions.add("IDE bridge is available. For compile/build/test/run, prefer `ide_build`, `ide_test`, and `ide_run` before shell `bash`.");
            instructions.add("Use shell `bash` for filesystem/inspection commands, or as fallback if IDE tools are unavailable.");
        }
        return instructions;
    }

    private void hydrateSessionHistoryIfNeeded(String sessionID, ChatRequest request) {
        if (sessionID == null || sessionID.isBlank() || request == null) {
            return;
        }
        List<String> history = request.getHistory();
        if (history == null || history.isEmpty()) {
            return;
        }
        if (!sessionService.getMessages(sessionID).isEmpty()) {
            return;
        }
        List<HistoryMessageSeed> seeds = parseHistorySeeds(history, request.getEffectiveMessage());
        if (seeds.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        int seeded = 0;
        for (HistoryMessageSeed seed : seeds) {
            if (seed == null || seed.text == null || seed.text.isBlank()) {
                continue;
            }
            MessageV2.MessageInfo info = new MessageV2.MessageInfo();
            info.setId(Identifier.ascending("message"));
            info.setSessionID(sessionID);
            info.setRole(seed.role);
            info.setCreated(now + seeded);
            info.setFinish("assistant".equals(seed.role));
            if ("assistant".equals(seed.role)) {
                info.setFinishReason("history");
            }
            info.setTokens(emptyTokenUsage());

            MessageV2.TextPart textPart = new MessageV2.TextPart();
            textPart.setId(Identifier.ascending("part"));
            textPart.setType("text");
            textPart.setText(seed.text);
            textPart.setMessageID(info.getId());
            textPart.setSessionID(sessionID);

            sessionService.addMessage(sessionID, new MessageV2.WithParts(info, new ArrayList<>(List.of(textPart))));
            seeded++;
        }
        if (seeded > 0) {
            log.info("Hydrated {} history messages for session {}", seeded, sessionID);
        }
    }

    private List<HistoryMessageSeed> parseHistorySeeds(List<String> historyLines, String currentUserMessage) {
        if (historyLines == null || historyLines.isEmpty()) {
            return List.of();
        }
        List<HistoryMessageSeed> seeds = new ArrayList<>();
        for (String raw : historyLines) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String line = raw.trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("you:") || lower.startsWith("user:") || lower.startsWith("?:")) {
                String text = stripPrefix(line);
                if (!text.isBlank()) {
                    seeds.add(new HistoryMessageSeed("user", text));
                }
                continue;
            }
            if (lower.startsWith("agent:") || lower.startsWith("assistant:")) {
                String text = stripPrefix(line);
                if (!text.isBlank()) {
                    seeds.add(new HistoryMessageSeed("assistant", text));
                }
            }
        }
        if (seeds.isEmpty()) {
            return seeds;
        }
        if (currentUserMessage != null && !currentUserMessage.isBlank()) {
            HistoryMessageSeed last = seeds.get(seeds.size() - 1);
            if ("user".equals(last.role) && normalizeHistoryText(last.text).equals(normalizeHistoryText(currentUserMessage))) {
                seeds.remove(seeds.size() - 1);
            }
        }
        return seeds;
    }

    private MessageV2.TokenUsage emptyTokenUsage() {
        return MessageV2.TokenUsage.builder()
                .input(0)
                .output(0)
                .reasoning(0)
                .cache(MessageV2.CacheUsage.builder().read(0).write(0).build())
                .build();
    }

    private String stripPrefix(String line) {
        if (line == null) {
            return "";
        }
        int idx = line.indexOf(':');
        if (idx < 0 || idx + 1 >= line.length()) {
            return line.trim();
        }
        return line.substring(idx + 1).trim();
    }

    private String normalizeHistoryText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replace("\r\n", "\n").replace('\r', '\n');
    }

    private static final class HistoryMessageSeed {
        final String role;
        final String text;

        private HistoryMessageSeed(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    private void sendSse(SseEmitter emitter, String eventName, Object data) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            }
        } catch (IOException e) {
            log.error("Error sending SSE", e);
            emitter.completeWithError(e);
        }
    }

    private boolean shouldEmitFinish(
            com.zzf.codeagent.session.model.MessageV2.WithParts msg,
            Set<String> emittedFinishMessageIDs
    ) {
        if (!Boolean.TRUE.equals(msg.getInfo().getFinish())) {
            return false;
        }
        if (!isTerminalFinishReason(msg.getInfo().getFinishReason())) {
            return false;
        }
        boolean hasRunningTools = msg.getParts().stream()
                .filter(p -> p instanceof com.zzf.codeagent.session.model.MessageV2.ToolPart)
                .map(p -> (com.zzf.codeagent.session.model.MessageV2.ToolPart) p)
                .filter(p -> p.getState() != null)
                .anyMatch(p -> {
                    String status = p.getState().getStatus();
                    return "running".equalsIgnoreCase(status) || "pending".equalsIgnoreCase(status);
                });
        if (hasRunningTools) {
            return false;
        }
        return emittedFinishMessageIDs.add(msg.getInfo().getId());
    }

    private boolean isTerminalFinishReason(String finishReason) {
        if (finishReason == null || finishReason.isEmpty()) {
            return false;
        }
        String normalized = finishReason.toLowerCase(Locale.ROOT);
        return !"tool-calls".equals(normalized)
                && !"tool_calls".equals(normalized)
                && !"unknown".equals(normalized);
    }

    private void cleanup(String sessionID) {
        emitters.remove(sessionID);
        List<Runnable> sessionUnsubs = unsubs.remove(sessionID);
        if (sessionUnsubs != null) {
            sessionUnsubs.forEach(Runnable::run);
        }
    }

    private String truncateForUi(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    private Map<String, Object> sanitizeMeta(Map<String, Object> meta) {
        Map<String, Object> sanitized = new HashMap<>();
        meta.forEach((k, v) -> {
            if (v instanceof String) {
                sanitized.put(k, truncateForUi((String) v, 4000));
            } else {
                sanitized.put(k, v);
            }
        });
        return sanitized;
    }

    private String firstNonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private Map<String, Object> sanitizeIdeContext(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = firstNonBlank(entry.getKey());
            if (key.isBlank()) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (!text.isBlank()) {
                    if (text.length() > 4000) {
                        text = text.substring(0, 4000) + "...";
                    }
                    sanitized.put(key, text);
                }
                continue;
            }
            if (value instanceof Number || value instanceof Boolean) {
                sanitized.put(key, value);
                continue;
            }
            if (value instanceof List<?> list) {
                List<Object> limited = new ArrayList<>();
                int maxItems = Math.min(list.size(), 32);
                for (int i = 0; i < maxItems; i++) {
                    Object item = list.get(i);
                    if (item == null) {
                        continue;
                    }
                    if (item instanceof String) {
                        String text = ((String) item).trim();
                        if (!text.isBlank()) {
                            limited.add(text.length() > 1000 ? text.substring(0, 1000) + "..." : text);
                        }
                    } else if (item instanceof Number || item instanceof Boolean) {
                        limited.add(item);
                    }
                }
                if (!limited.isEmpty()) {
                    sanitized.put(key, limited);
                }
            }
        }
        return sanitized;
    }

}


