package com.zzf.rikki.idea.agent.compat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class RuntimeEvent {

    public static final class SessionBound extends RuntimeEvent {
        private final String sessionId;
        private final boolean reused;

        public SessionBound(String sessionId) {
            this(sessionId, false);
        }

        public SessionBound(String sessionId, boolean reused) {
            this.sessionId = sessionId;
            this.reused = reused;
        }

        public String getSessionId() {
            return sessionId;
        }

        public boolean getReused() {
            return reused;
        }
    }

    public static final class StatusChanged extends RuntimeEvent {
        private final String type;
        private final String message;

        public StatusChanged(String type, String message) {
            this.type = type;
            this.message = message;
        }

        public String getType() {
            return type;
        }

        public String getMessage() {
            return message;
        }
    }

    public static final class ThoughtDelta extends RuntimeEvent {
        private final String messageId;
        private final String delta;

        public ThoughtDelta(String messageId, String delta) {
            this.messageId = messageId;
            this.delta = delta;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getDelta() {
            return delta;
        }
    }

    public static final class ThoughtEnd extends RuntimeEvent {
        private final String messageId;

        public ThoughtEnd(String messageId) {
            this.messageId = messageId;
        }

        public String getMessageId() {
            return messageId;
        }
    }

    public static final class MessageDelta extends RuntimeEvent {
        private final String messageId;
        private final String delta;

        public MessageDelta(String messageId, String delta) {
            this.messageId = messageId;
            this.delta = delta;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getDelta() {
            return delta;
        }
    }

    public static final class MessageSnapshot extends RuntimeEvent {
        private final String messageId;
        private final String text;

        public MessageSnapshot(String messageId, String text) {
            this.messageId = messageId;
            this.text = text;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getText() {
            return text;
        }
    }

    public static final class ToolCall extends RuntimeEvent {
        private final String partId;
        private final String tool;
        private final String callId;
        private final String messageId;
        private final String state;
        private final String title;
        private final Map<String, Object> args;
        private final Map<String, Object> meta;

        public ToolCall(
                String partId,
                String tool,
                String callId,
                String messageId,
                String state,
                String title,
                Map<String, ?> args,
                Map<String, ?> meta
        ) {
            this.partId = partId;
            this.tool = tool;
            this.callId = callId;
            this.messageId = messageId;
            this.state = state;
            this.title = title;
            this.args = copyMap(args);
            this.meta = meta == null ? null : copyMap(meta);
        }

        public String getPartId() {
            return partId;
        }

        public String getTool() {
            return tool;
        }

        public String getCallId() {
            return callId;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getState() {
            return state;
        }

        public String getTitle() {
            return title;
        }

        public Map<String, Object> getArgs() {
            return args;
        }

        public Map<String, Object> getMeta() {
            return meta;
        }
    }

    public static final class ToolPendingApproval extends RuntimeEvent {
        private final String partId;
        private final String callId;
        private final String command;
        private final String tool;

        public ToolPendingApproval(String partId, String callId, String command, String tool) {
            this.partId = partId;
            this.callId = callId;
            this.command = command;
            this.tool = tool;
        }

        public String getPartId() {
            return partId;
        }

        public String getCallId() {
            return callId;
        }

        public String getCommand() {
            return command;
        }

        public String getTool() {
            return tool;
        }
    }

    public static final class ToolResult extends RuntimeEvent {
        private final String partId;
        private final String tool;
        private final String callId;
        private final String messageId;
        private final String state;
        private final String title;
        private final String output;
        private final String error;
        private final Map<String, Object> meta;

        public ToolResult(
                String partId,
                String tool,
                String callId,
                String messageId,
                String state,
                String title,
                String output,
                String error,
                Map<String, ?> meta
        ) {
            this.partId = partId;
            this.tool = tool;
            this.callId = callId;
            this.messageId = messageId;
            this.state = state;
            this.title = title;
            this.output = output;
            this.error = error;
            this.meta = meta == null ? null : copyMap(meta);
        }

        public String getPartId() {
            return partId;
        }

        public String getTool() {
            return tool;
        }

        public String getCallId() {
            return callId;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getState() {
            return state;
        }

        public String getTitle() {
            return title;
        }

        public String getOutput() {
            return output;
        }

        public String getError() {
            return error;
        }

        public Map<String, Object> getMeta() {
            return meta;
        }
    }

    public static final class TodoUpdated extends RuntimeEvent {
        private final String todosJson;
        private final String sessionId;

        public TodoUpdated(String todosJson) {
            this(todosJson, null);
        }

        public TodoUpdated(String todosJson, String sessionId) {
            this.todosJson = todosJson;
            this.sessionId = sessionId;
        }

        public String getTodosJson() {
            return todosJson;
        }

        public String getSessionId() {
            return sessionId;
        }
    }

    public static final class Finished extends RuntimeEvent {
        private final String sessionId;
        private final String messageId;
        private final String answer;

        public Finished(String sessionId, String messageId, String answer) {
            this.sessionId = sessionId;
            this.messageId = messageId;
            this.answer = answer;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getAnswer() {
            return answer;
        }
    }

    public static final class Errored extends RuntimeEvent {
        private final String message;

        public Errored(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    private static LinkedHashMap<String, Object> copyMap(Map<String, ?> raw) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        if (raw == null) {
            return map;
        }
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            map.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                map.put(String.valueOf(entry.getKey()), copyValue(entry.getValue()));
            }
            return map;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(RuntimeEvent::copyValue).toList();
        }
        return value;
    }
}
