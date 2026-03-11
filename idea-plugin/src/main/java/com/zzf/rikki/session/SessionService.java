package com.zzf.rikki.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.session.model.MessageV2;
import com.zzf.rikki.session.model.PromptPart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionService {
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<MessageV2.WithParts>> messages = new ConcurrentHashMap<>();

    public SessionService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SessionInfo getOrCreate(String sessionId, String workspaceRoot) {
        String normalizedId = sessionId == null || sessionId.isBlank() ? nextId("session") : sessionId;
        return sessions.compute(normalizedId, (key, existing) -> {
            if (existing != null) {
                existing.updatedAt = System.currentTimeMillis();
                return existing;
            }
            return new SessionInfo(normalizedId, workspaceRoot);
        });
    }

    public SessionInfo get(String sessionId) {
        return sessions.get(sessionId);
    }

    public void importHistory(String sessionId, JsonNode history) {
        SessionInfo session = sessions.get(sessionId);
        if (session == null || session.historyImported || history == null || !history.isArray()) {
            return;
        }
        for (JsonNode entry : history) {
            if (entry.isTextual()) {
                importLegacyLine(sessionId, entry.asText(""));
            } else if (entry.isObject()) {
                importStructuredHistoryEntry(sessionId, entry);
            }
        }
        session.historyImported = true;
        session.updatedAt = System.currentTimeMillis();
    }

    public MessageV2.WithParts addUserMessage(String sessionId, String text) {
        return addTextMessage(sessionId, "user", text);
    }

    public MessageV2.WithParts startAssistantMessage(String sessionId, String providerId, String modelId) {
        long created = System.currentTimeMillis();
        String parentId = null;
        List<MessageV2.WithParts> history = getMessages(sessionId);
        for (int i = history.size() - 1; i >= 0; i--) {
            MessageV2.WithParts candidate = history.get(i);
            if ("user".equals(candidate.info.role)) {
                parentId = candidate.info.id;
                break;
            }
        }
        MessageV2.MessageInfo info = new MessageV2.MessageInfo();
        info.id = nextId("message");
        info.sessionID = sessionId;
        info.role = "assistant";
        info.created = created;
        info.providerID = providerId;
        info.modelID = modelId;
        info.parentID = parentId;
        info.finish = Boolean.FALSE;
        info.time = new MessageV2.MessageTime();
        info.time.created = created;
        info.time.start = created;
        info.tokens = new MessageV2.TokenUsage();
        MessageV2.WithParts message = new MessageV2.WithParts();
        message.info = info;
        messages.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(message);
        touch(sessionId);
        return message;
    }

    public void updateMessage(MessageV2.WithParts message) {
        List<MessageV2.WithParts> bucket = messages.computeIfAbsent(message.info.sessionID, ignored -> new ArrayList<>());
        int index = -1;
        for (int i = 0; i < bucket.size(); i++) {
            if (bucket.get(i).info.id.equals(message.info.id)) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            bucket.set(index, message);
        } else {
            bucket.add(message);
        }
        touch(message.info.sessionID);
    }

    public void updatePart(PromptPart part) {
        List<MessageV2.WithParts> bucket = messages.get(part.sessionID);
        if (bucket == null) {
            return;
        }
        for (MessageV2.WithParts message : bucket) {
            if (!message.info.id.equals(part.messageID)) {
                continue;
            }
            int index = -1;
            for (int i = 0; i < message.parts.size(); i++) {
                if (message.parts.get(i).id.equals(part.id)) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                message.parts.set(index, part);
            } else {
                message.parts.add(part);
            }
            touch(part.sessionID);
            return;
        }
    }

    public List<MessageV2.WithParts> getMessages(String sessionId) {
        List<MessageV2.WithParts> bucket = messages.get(sessionId);
        return bucket == null ? List.of() : new ArrayList<>(bucket);
    }

    public List<MessageV2.WithParts> getFilteredMessages(String sessionId) {
        List<MessageV2.WithParts> all = getMessages(sessionId);
        if (all.isEmpty()) {
            return List.of();
        }
        int summaryIndex = -1;
        for (int i = all.size() - 1; i >= 0; i--) {
            MessageV2.WithParts candidate = all.get(i);
            if ("assistant".equals(candidate.info.role) && Boolean.TRUE.equals(candidate.info.summary)) {
                summaryIndex = i;
                break;
            }
        }
        return summaryIndex >= 0 ? new ArrayList<>(all.subList(summaryIndex, all.size())) : all;
    }

    public MessageV2.WithParts getMessage(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return null;
        }
        for (List<MessageV2.WithParts> bucket : messages.values()) {
            for (MessageV2.WithParts message : bucket) {
                if (messageId.equals(message.info.id)) {
                    return message;
                }
            }
        }
        return null;
    }

    public List<MessageV2.WithParts> copyMessages(List<MessageV2.WithParts> source) {
        List<MessageV2.WithParts> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (MessageV2.WithParts message : source) {
            copy.add(cloneMessage(message));
        }
        return copy;
    }

    public List<Map<String, Object>> toLlmMessages(String sessionId, String systemPrompt, String systemRole) {
        return toLlmMessages(getFilteredMessages(sessionId), systemPrompt, systemRole);
    }

    public List<Map<String, Object>> toLlmMessages(List<MessageV2.WithParts> sourceMessages, String systemPrompt, String systemRole) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(mapOf("role", systemRole, "content", systemPrompt));
        if (sourceMessages == null || sourceMessages.isEmpty()) {
            return result;
        }
        for (MessageV2.WithParts message : sourceMessages) {
            if (message == null || message.info == null) {
                continue;
            }
            if ("user".equals(message.info.role) || "system".equals(message.info.role)) {
                String text = message.textContent();
                if (!text.isBlank()) {
                    result.add(mapOf("role", message.info.role, "content", text));
                }
                continue;
            }
            if (!"assistant".equals(message.info.role)) {
                continue;
            }
            String text = message.textContent();
            List<MessageV2.ToolPart> toolParts = message.toolParts();
            if (!text.isBlank() || !toolParts.isEmpty()) {
                LinkedHashMap<String, Object> assistant = new LinkedHashMap<>();
                assistant.put("role", "assistant");
                assistant.put("content", text.isBlank() ? null : text);
                if (!toolParts.isEmpty()) {
                    List<Map<String, Object>> toolCalls = new ArrayList<>();
                    for (MessageV2.ToolPart part : toolParts) {
                        toolCalls.add(mapOf(
                                "id", part.callID,
                                "type", "function",
                                "function", mapOf(
                                        "name", part.tool,
                                        "arguments", writeJson(part.args)
                                )
                        ));
                    }
                    assistant.put("tool_calls", toolCalls);
                }
                result.add(assistant);
            }
            for (MessageV2.ToolPart part : toolParts) {
                if (part.state != null && part.state.time != null && Boolean.TRUE.equals(part.state.time.compacted)) {
                    continue;
                }
                String output = part.state.output == null || part.state.output.isBlank()
                        ? (part.state.error == null ? "" : part.state.error)
                        : part.state.output;
                if (!output.isBlank()) {
                    result.add(mapOf("role", "tool", "tool_call_id", part.callID, "content", output));
                }
            }
        }
        return result;
    }

    private void importLegacyLine(String sessionId, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isBlank()) {
            return;
        }
        String[] parsed = parseHistoryLine(text);
        if (parsed == null) {
            return;
        }
        addTextMessage(sessionId, parsed[0], parsed[1]);
    }

    private void importStructuredHistoryEntry(String sessionId, JsonNode entry) {
        String role = entry.path("role").asText("");
        if (role.isBlank()) {
            return;
        }
        long created = entry.path("timestamp").asLong(entry.path("created").asLong(System.currentTimeMillis()));
        MessageV2.MessageInfo info = new MessageV2.MessageInfo();
        info.id = textOr(entry, "messageID", nextId("message"));
        info.sessionID = sessionId;
        info.role = role;
        info.created = created;
        info.providerID = blankToNull(entry.path("providerID").asText(""));
        info.modelID = blankToNull(entry.path("modelID").asText(""));
        info.agent = blankToNull(entry.path("agent").asText(""));
        info.parentID = blankToNull(entry.path("parentID").asText(""));
        info.mode = blankToNull(entry.path("mode").asText(""));
        info.summary = entry.path("summary").asBoolean(false);
        info.finish = entry.has("finish") ? entry.path("finish").asBoolean(true) : Boolean.TRUE;
        info.finishReason = textOr(entry, "finishReason", info.finish ? "history" : null);
        info.cost = entry.has("cost") && !entry.path("cost").isNull() ? entry.path("cost").asDouble() : null;
        info.time = new MessageV2.MessageTime();
        info.time.created = created;
        info.time.start = entry.path("time").path("start").asLong(created);
        info.time.end = entry.path("time").path("end").asLong(created);
        if ("user".equals(role)) {
            info.user = parseUser(entry.path("user"));
            if (info.user == null) {
                info.user = new MessageV2.User();
                info.user.id = nextId("user");
            }
        }

        MessageV2.WithParts message = new MessageV2.WithParts();
        message.info = info;

        JsonNode parts = entry.path("parts");
        if (parts.isArray() && parts.size() > 0) {
            for (JsonNode partNode : parts) {
                PromptPart parsed = parsePart(sessionId, info.id, partNode, created);
                if (parsed != null) {
                    message.parts.add(parsed);
                }
            }
        } else {
            importLegacyStructuredParts(sessionId, created, info.id, entry, message.parts);
        }

        messages.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(message);
        touch(sessionId);
    }

    private void importLegacyStructuredParts(String sessionId, long created, String messageId, JsonNode entry, List<PromptPart> parts) {
        String text = entry.path("text").asText("");
        if (!text.isBlank()) {
            MessageV2.TextPart part = new MessageV2.TextPart();
            part.id = nextId("part");
            part.sessionID = sessionId;
            part.messageID = messageId;
            part.text = text;
            part.delta = text;
            part.time.start = created;
            part.time.end = created;
            parts.add(part);
        }
        String thought = entry.path("thought").asText("");
        if (!thought.isBlank()) {
            MessageV2.ReasoningPart part = new MessageV2.ReasoningPart();
            part.id = nextId("part");
            part.sessionID = sessionId;
            part.messageID = messageId;
            part.text = thought;
            part.delta = thought;
            part.time.start = created;
            part.time.end = created;
            parts.add(part);
        }
        JsonNode activities = entry.path("toolActivities");
        if (activities.isArray()) {
            for (JsonNode activity : activities) {
                MessageV2.ToolPart part = new MessageV2.ToolPart();
                part.id = textOr(activity, "id", nextId("part"));
                part.sessionID = sessionId;
                part.messageID = messageId;
                part.callID = textOr(activity, "callID", nextId("call"));
                part.tool = textOr(activity, "tool", "tool");
                part.state.status = textOr(activity, "status", "completed");
                part.state.output = activity.path("details").asText("");
                part.state.title = textOr(activity, "summary", part.tool);
                part.state.time.start = created;
                part.state.time.end = created;
                String rawMeta = activity.path("meta").asText("");
                if (!rawMeta.isBlank()) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> meta = mapper.readValue(rawMeta, LinkedHashMap.class);
                        part.state.metadata.putAll(meta);
                    } catch (Exception ignored) {
                    }
                }
                parts.add(part);
            }
        }
    }

    private PromptPart parsePart(String sessionId, String messageId, JsonNode node, long created) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String type = node.path("type").asText("");
        PromptPart part = switch (type) {
            case "text" -> parseTextPart(sessionId, messageId, node, created);
            case "reasoning" -> parseReasoningPart(sessionId, messageId, node, created);
            case "file" -> parseFilePart(sessionId, messageId, node);
            case "tool" -> parseToolPart(sessionId, messageId, node, created);
            case "compaction" -> parseCompactionPart(sessionId, messageId, node);
            case "subtask" -> parseSubtaskPart(sessionId, messageId, node);
            case "agent" -> parseAgentPart(sessionId, messageId, node);
            case "step-start" -> parseStepStartPart(sessionId, messageId, node);
            case "step-finish" -> parseStepFinishPart(sessionId, messageId, node);
            default -> null;
        };
        if (part != null && node.path("metadata").isObject()) {
            part.metadata.putAll(asMap(node.path("metadata")));
        }
        return part;
    }

    private MessageV2.TextPart parseTextPart(String sessionId, String messageId, JsonNode node, long created) {
        MessageV2.TextPart part = new MessageV2.TextPart();
        part.id = textOr(node, "id", nextId("part"));
        part.sessionID = sessionId;
        part.messageID = messageId;
        part.text = node.path("text").asText("");
        part.delta = node.path("delta").asText(part.text);
        part.synthetic = node.path("synthetic").asBoolean(false);
        part.ignored = node.path("ignored").asBoolean(false);
        part.time.start = node.path("time").path("start").asLong(created);
        part.time.end = node.path("time").path("end").asLong(part.time.start);
        part.time.compacted = node.path("time").path("compacted").asBoolean(false);
        return part;
    }

    private MessageV2.ReasoningPart parseReasoningPart(String sessionId, String messageId, JsonNode node, long created) {
        MessageV2.ReasoningPart part = new MessageV2.ReasoningPart();
        part.id = textOr(node, "id", nextId("part"));
        part.sessionID = sessionId;
        part.messageID = messageId;
        part.text = node.path("text").asText("");
        part.delta = node.path("delta").asText(part.text);
        part.collapsed = node.path("collapsed").asBoolean(false);
        part.time.start = node.path("time").path("start").asLong(created);
        part.time.end = node.path("time").path("end").asLong(part.time.start);
        part.time.compacted = node.path("time").path("compacted").asBoolean(false);
        return part;
    }

    private MessageV2.FilePart parseFilePart(String sessionId, String messageId, JsonNode node) {
        MessageV2.FilePart part = new MessageV2.FilePart();
        part.id = textOr(node, "id", nextId("part"));
        part.sessionID = sessionId;
        part.messageID = messageId;
        part.mime = blankToNull(node.path("mime").asText(""));
        part.filename = blankToNull(node.path("filename").asText(""));
        part.url = blankToNull(node.path("url").asText(""));
        part.content = blankToNull(node.path("content").asText(""));
        return part;
    }

    private MessageV2.ToolPart parseToolPart(String sessionId, String messageId, JsonNode node, long created) {
        MessageV2.ToolPart part = new MessageV2.ToolPart();
        part.id = textOr(node, "id", nextId("part"));
        part.sessionID = sessionId;
        part.messageID = messageId;
        part.callID = textOr(node, "callID", nextId("call"));
        part.tool = textOr(node, "tool", "tool");
        if (node.path("args").isObject()) {
            part.args.putAll(asMap(node.path("args")));
        }
        part.state = parseToolState(node.path("state"), created);
        return part;
    }

    private MessageV2.CompactionPart parseCompactionPart(String sessionId, String messageId, JsonNode node) {
        MessageV2.CompactionPart part = new MessageV2.CompactionPart();
        part.id = textOr(node, "id", nextId("part"));
        part.sessionID = sessionId;
        part.messageID = messageId;
        part.auto = node.path("auto").asBoolean(false);
        part.summary = blankToNull(node.path("summary").asText(""));
        return part;
    }

    private MessageV2.SubtaskPart parseSubtaskPart(String sessionId, String messageId, JsonNode node) {
        MessageV2.SubtaskPart part = new MessageV2.SubtaskPart();
        part.id = textOr(node, "id", nextId("part"));
        part.sessionID = sessionId;
        part.messageID = messageId;
        part.prompt = blankToNull(node.path("prompt").asText(""));
        part.description = blankToNull(node.path("description").asText(""));
        part.agent = blankToNull(node.path("agent").asText(""));
        return part;
    }

    private MessageV2.AgentPart parseAgentPart(String sessionId, String messageId, JsonNode node) {
        MessageV2.AgentPart part = new MessageV2.AgentPart();
        part.id = textOr(node, "id", nextId("part"));
        part.sessionID = sessionId;
        part.messageID = messageId;
        part.name = blankToNull(node.path("name").asText(""));
        return part;
    }

    private MessageV2.StepStartPart parseStepStartPart(String sessionId, String messageId, JsonNode node) {
        MessageV2.StepStartPart part = new MessageV2.StepStartPart();
        part.id = textOr(node, "id", nextId("part"));
        part.sessionID = sessionId;
        part.messageID = messageId;
        part.snapshot = blankToNull(node.path("snapshot").asText(""));
        return part;
    }

    private MessageV2.StepFinishPart parseStepFinishPart(String sessionId, String messageId, JsonNode node) {
        MessageV2.StepFinishPart part = new MessageV2.StepFinishPart();
        part.id = textOr(node, "id", nextId("part"));
        part.sessionID = sessionId;
        part.messageID = messageId;
        part.reason = blankToNull(node.path("reason").asText(""));
        part.snapshot = blankToNull(node.path("snapshot").asText(""));
        if (node.path("tokens").isObject()) {
            part.tokens = parseTokenUsage(node.path("tokens"));
        }
        part.cost = node.has("cost") && !node.path("cost").isNull() ? node.path("cost").asDouble() : null;
        return part;
    }

    private MessageV2.ToolState parseToolState(JsonNode node, long created) {
        MessageV2.ToolState state = new MessageV2.ToolState();
        if (node == null || !node.isObject()) {
            state.time.start = created;
            state.time.end = created;
            return state;
        }
        state.status = textOr(node, "status", state.status);
        if (node.path("input").isObject()) {
            state.input.putAll(asMap(node.path("input")));
        }
        state.output = node.path("output").asText("");
        state.title = node.path("title").asText("");
        state.error = blankToNull(node.path("error").asText(""));
        if (node.path("metadata").isObject()) {
            state.metadata.putAll(asMap(node.path("metadata")));
        }
        state.time.start = node.path("time").path("start").asLong(created);
        state.time.end = node.path("time").path("end").asLong(state.time.start);
        state.time.compacted = node.path("time").path("compacted").asBoolean(false);
        return state;
    }

    private MessageV2.User parseUser(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        MessageV2.User user = new MessageV2.User();
        user.id = textOr(node, "id", nextId("user"));
        user.system = blankToNull(node.path("system").asText(""));
        user.variant = blankToNull(node.path("variant").asText(""));
        if (node.path("tools").isObject()) {
            Map<String, Object> tools = asMap(node.path("tools"));
            for (Map.Entry<String, Object> entry : tools.entrySet()) {
                if (entry.getValue() instanceof Boolean value) {
                    user.tools.put(entry.getKey(), value);
                }
            }
        }
        return user;
    }

    private MessageV2.TokenUsage parseTokenUsage(JsonNode node) {
        MessageV2.TokenUsage usage = new MessageV2.TokenUsage();
        usage.input = node.path("input").asInt(0);
        usage.output = node.path("output").asInt(0);
        usage.reasoning = node.path("reasoning").asInt(0);
        usage.cache.read = node.path("cache").path("read").asInt(0);
        usage.cache.write = node.path("cache").path("write").asInt(0);
        return usage;
    }

    private MessageV2.WithParts addTextMessage(String sessionId, String role, String text) {
        long created = System.currentTimeMillis();
        MessageV2.MessageInfo info = new MessageV2.MessageInfo();
        info.id = nextId("message");
        info.sessionID = sessionId;
        info.role = role;
        info.created = created;
        info.finish = Boolean.TRUE;
        info.finishReason = "history";
        info.time = new MessageV2.MessageTime();
        info.time.created = created;
        info.time.start = created;
        info.time.end = created;
        if ("user".equals(role)) {
            info.user = new MessageV2.User();
            info.user.id = nextId("user");
        }
        MessageV2.TextPart part = new MessageV2.TextPart();
        part.id = nextId("part");
        part.sessionID = sessionId;
        part.messageID = info.id;
        part.text = text;
        part.delta = text;
        part.time.start = created;
        part.time.end = created;
        MessageV2.WithParts message = new MessageV2.WithParts();
        message.info = info;
        message.parts.add(part);
        messages.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(message);
        touch(sessionId);
        return message;
    }

    private MessageV2.WithParts cloneMessage(MessageV2.WithParts source) {
        MessageV2.WithParts message = new MessageV2.WithParts();
        message.info = cloneMessageInfo(source.info);
        for (PromptPart part : source.parts) {
            PromptPart cloned = clonePart(part);
            if (cloned != null) {
                message.parts.add(cloned);
            }
        }
        return message;
    }

    private MessageV2.MessageInfo cloneMessageInfo(MessageV2.MessageInfo source) {
        MessageV2.MessageInfo info = new MessageV2.MessageInfo();
        info.id = source.id;
        info.sessionID = source.sessionID;
        info.role = source.role;
        info.created = source.created;
        info.modelID = source.modelID;
        info.providerID = source.providerID;
        info.agent = source.agent;
        info.parentID = source.parentID;
        info.mode = source.mode;
        info.summary = source.summary;
        info.tokens = source.tokens == null ? null : parseTokenUsage(mapper.valueToTree(source.tokens));
        info.time = new MessageV2.MessageTime();
        if (source.time != null) {
            info.time.created = source.time.created;
            info.time.start = source.time.start;
            info.time.end = source.time.end;
        }
        info.cost = source.cost;
        info.finish = source.finish;
        info.finishReason = source.finishReason;
        if (source.error != null) {
            info.error = new MessageV2.ErrorInfo();
            info.error.message = source.error.message;
            info.error.type = source.error.type;
        }
        if (source.user != null) {
            info.user = parseUser(mapper.valueToTree(source.user));
        }
        return info;
    }

    private PromptPart clonePart(PromptPart source) {
        if (source == null) {
            return null;
        }
        return parsePart(source.sessionID, source.messageID, mapper.valueToTree(source), System.currentTimeMillis());
    }

    private void touch(String sessionId) {
        SessionInfo session = sessions.get(sessionId);
        if (session != null) {
            session.updatedAt = System.currentTimeMillis();
        }
    }

    private static String[] parseHistoryLine(String text) {
        if (text.startsWith("You:")) {
            return new String[]{"user", text.substring(4).trim()};
        }
        if (text.startsWith("Agent:")) {
            return new String[]{"assistant", text.substring(6).trim()};
        }
        if (text.startsWith("Assistant:")) {
            return new String[]{"assistant", text.substring(10).trim()};
        }
        if (text.startsWith("System:")) {
            return new String[]{"system", text.substring(7).trim()};
        }
        return null;
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(JsonNode node) {
        try {
            return mapper.convertValue(node, LinkedHashMap.class);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static LinkedHashMap<String, Object> mapOf(Object... pairs) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}