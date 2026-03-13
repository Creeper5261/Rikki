package com.zzf.rikki.idea.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.idea.agent.compat.AgentEventSink;
import com.zzf.rikki.idea.agent.compat.RuntimeEvent;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class LiteSseWriter implements AgentEventSink {
    private final PrintWriter writer;
    private final ObjectMapper mapper = new ObjectMapper();

    public LiteSseWriter(OutputStream outputStream) {
        this.writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
    }

    public synchronized void emit(String event, Object data) {
        writer.print("event: " + event + "\n");
        try {
            writer.print("data: " + (data instanceof String ? data : mapper.writeValueAsString(data)) + "\n");
        } catch (Exception e) {
            writer.print("data: {}\n");
        }
        writer.print("\n");
        writer.flush();
    }

    @Override
    public void emit(RuntimeEvent event) {
        if (event instanceof RuntimeEvent.SessionBound sessionBound) {
            emitSession(sessionBound.getSessionId(), sessionBound.getReused());
        } else if (event instanceof RuntimeEvent.StatusChanged statusChanged) {
            emitStatus(statusChanged.getType(), statusChanged.getMessage());
        } else if (event instanceof RuntimeEvent.MessageDelta messageDelta) {
            emitMessage(messageDelta.getMessageId(), messageDelta.getDelta());
        } else if (event instanceof RuntimeEvent.MessageSnapshot messageSnapshot) {
            emit("message_part", Map.of("messageID", messageSnapshot.getMessageId(), "text", messageSnapshot.getText()));
        } else if (event instanceof RuntimeEvent.ThoughtDelta thoughtDelta) {
            emitThought(thoughtDelta.getMessageId(), thoughtDelta.getDelta());
        } else if (event instanceof RuntimeEvent.ThoughtEnd thoughtEnd) {
            emitThoughtEnd(thoughtEnd.getMessageId());
        } else if (event instanceof RuntimeEvent.ToolCall toolCall) {
            emitToolCall(toolCall.getPartId(), toolCall.getTool(), toolCall.getCallId(), toolCall.getMessageId(), toolCall.getState(), toolCall.getTitle(), toolCall.getArgs(), toolCall.getMeta());
        } else if (event instanceof RuntimeEvent.ToolPendingApproval toolPendingApproval) {
            emitToolConfirm(toolPendingApproval.getPartId(), toolPendingApproval.getCallId(), toolPendingApproval.getCommand(), toolPendingApproval.getTool());
        } else if (event instanceof RuntimeEvent.ToolResult toolResult) {
            emitToolResult(toolResult.getPartId(), toolResult.getTool(), toolResult.getCallId(), toolResult.getMessageId(), toolResult.getState(), toolResult.getTitle(), toolResult.getOutput(), toolResult.getError(), toolResult.getMeta());
        } else if (event instanceof RuntimeEvent.TodoUpdated todoUpdated) {
            emitTodoUpdated(todoUpdated.getTodosJson(), todoUpdated.getSessionId());
        } else if (event instanceof RuntimeEvent.Finished finished) {
            emitFinish(finished.getSessionId(), finished.getMessageId(), finished.getAnswer());
        } else if (event instanceof RuntimeEvent.Errored errored) {
            emit("error", errored.getMessage());
        }
    }

    public void emitSession(String sessionId, boolean reused) {
        emit("session", Map.of("sessionID", sessionId, "reused", reused));
    }

    public void emitStatus(String type, String message) {
        emit("status", Map.of("type", type, "message", message));
    }

    public void emitMessage(String messageId, String delta) {
        emit("message", Map.of("id", messageId, "delta", delta));
    }

    public void emitThought(String messageId, String delta) {
        emit("thought", Map.of("messageID", messageId, "reasoning_delta", delta));
    }

    public void emitThoughtEnd(String messageId) {
        emit("thought_end", Map.of("messageID", messageId));
    }

    public void emitToolCall(String partId, String tool, String callId, String messageId, String state, String title, Object args, Map<String, ?> meta) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", partId);
        payload.put("partID", partId);
        payload.put("partId", partId);
        payload.put("tool", tool);
        payload.put("callID", callId);
        payload.put("messageID", messageId);
        payload.put("messageId", messageId);
        payload.put("state", state);
        payload.put("title", title);
        payload.put("args", args);
        if (meta != null) {
            payload.put("meta", meta);
        }
        emit("tool_call", payload);
    }

    public void emitToolResult(String partId, String tool, String callId, String messageId, String state, String title, String output, String error, Map<String, ?> meta) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", partId);
        payload.put("partID", partId);
        payload.put("partId", partId);
        payload.put("tool", tool);
        payload.put("callID", callId);
        payload.put("messageID", messageId);
        payload.put("messageId", messageId);
        payload.put("state", state);
        payload.put("title", title);
        payload.put("output", output);
        if (error != null) {
            payload.put("error", error);
        }
        if (meta != null) {
            payload.put("meta", meta);
        }
        emit("tool_result", payload);
    }

    public void emitToolConfirm(String partId, String callId, String command, String tool) {
        emit("tool_confirm", Map.of(
                "id", partId,
                "partID", partId,
                "partId", partId,
                "callID", callId,
                "callId", callId,
                "tool", tool,
                "command", command
        ));
    }

    public void emitTodoUpdated(String todosJson, String sessionId) {
        try {
            Object todos = mapper.readTree(todosJson);
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("todos", todos);
            if (sessionId != null && !sessionId.isBlank()) {
                payload.put("sessionID", sessionId);
            }
            emit("todo_updated", payload);
        } catch (Exception ignored) {
        }
    }

    public void emitFinish(String sessionId, String messageId, String answer) {
        emit("finish", Map.of(
                "answer", answer,
                "traceId", sessionId,
                "sessionID", sessionId,
                "messageID", messageId,
                "meta", Map.of("pendingChanges", java.util.List.of())
        ));
    }
}
