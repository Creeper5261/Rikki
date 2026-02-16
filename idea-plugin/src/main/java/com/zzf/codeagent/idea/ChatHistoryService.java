package com.zzf.codeagent.idea;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.annotations.XCollection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service(Service.Level.PROJECT)
@State(name = "CodeAgentChatHistory", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ChatHistoryService implements PersistentStateComponent<ChatHistoryService.State> {
    private static final Logger logger = Logger.getInstance(ChatHistoryService.class);
    private State state = new State();
    private static final boolean PERSIST_HISTORY = resolvePersistHistory();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path historyFile;

    public ChatHistoryService(Project project) {
        this.historyFile = resolveHistoryFile(project);
        if (PERSIST_HISTORY) {
            loadFromDiskIfNeeded();
        }
    }

    // Legacy support: redirect to current session
    public synchronized List<String> getLines() {
        ChatSession current = getCurrentSession();
        return current != null ? new ArrayList<>(current.messages) : new ArrayList<>();
    }

    public synchronized void appendLine(String line) {
        if (line == null || line.trim().isEmpty()) return;

        ChatSession current = getCurrentSession();
        if (current == null) {
            current = createSession("New Chat");
        }
        current.messages.add(line);

        // Auto-update title if it's the first user message
        if (current.messages.size() == 1 && isUserLine(line)) {
            String title = stripUserPrefix(line);
            if (title.length() > 20) title = title.substring(0, 20) + "...";
            current.title = title;
        }
        persistToDisk();
    }

    public synchronized List<UiMessage> getUiMessages() {
        ChatSession current = getCurrentSession();
        if (current == null || current.uiMessages == null) {
            return new ArrayList<>();
        }
        List<UiMessage> copy = new ArrayList<>();
        for (UiMessage message : current.uiMessages) {
            copy.add(cloneUiMessage(message));
        }
        return copy;
    }

    public synchronized String appendUiMessage(UiMessage message) {
        if (message == null) {
            return "";
        }
        ChatSession current = getCurrentSession();
        if (current == null) {
            current = createSession("New Chat");
        }
        UiMessage normalized = cloneUiMessage(message);
        normalizeUiMessage(normalized);
        current.uiMessages.add(normalized);
        persistToDisk();
        return normalized.id;
    }

    public synchronized void upsertUiMessage(UiMessage message) {
        if (message == null) {
            return;
        }
        ChatSession current = getCurrentSession();
        if (current == null) {
            current = createSession("New Chat");
        }
        UiMessage normalized = cloneUiMessage(message);
        normalizeUiMessage(normalized);
        if (current.uiMessages == null) {
            current.uiMessages = new ArrayList<>();
        }
        if (normalized.id == null || normalized.id.isBlank()) {
            normalized.id = UUID.randomUUID().toString();
            current.uiMessages.add(normalized);
        } else {
            int idx = -1;
            for (int i = 0; i < current.uiMessages.size(); i++) {
                UiMessage existing = current.uiMessages.get(i);
                if (existing != null && normalized.id.equals(existing.id)) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                current.uiMessages.set(idx, normalized);
            } else {
                current.uiMessages.add(normalized);
            }
        }
        persistToDisk();
    }

    public synchronized ChatSession createSession(String title) {
        ChatSession session = new ChatSession();
        session.id = UUID.randomUUID().toString();
        session.title = title;
        session.createdAt = System.currentTimeMillis();
        state.sessions.add(0, session); // Add to top
        state.currentSessionId = session.id;
        persistToDisk();
        return session;
    }

    public synchronized void deleteSession(String id) {
        state.sessions.removeIf(s -> s.id.equals(id));
        if (state.currentSessionId != null && state.currentSessionId.equals(id)) {
            state.currentSessionId = state.sessions.isEmpty() ? null : state.sessions.get(0).id;
        }
        persistToDisk();
    }

    public synchronized void setCurrentSession(String id) {
        state.currentSessionId = id;
        persistToDisk();
    }

    public synchronized ChatSession getCurrentSession() {
        if (state.currentSessionId == null) {
            if (!state.sessions.isEmpty()) {
                state.currentSessionId = state.sessions.get(0).id;
            } else {
                return createSession("Default Chat");
            }
        }

        String finalId = state.currentSessionId;
        return state.sessions.stream().filter(s -> s.id.equals(finalId)).findFirst().orElseGet(() -> {
            // Fallback if ID not found
            if (!state.sessions.isEmpty()) {
                state.currentSessionId = state.sessions.get(0).id;
                return state.sessions.get(0);
            }
            return createSession("Default Chat");
        });
    }

    public synchronized @Nullable ChatSession peekCurrentSession() {
        if (state.currentSessionId == null || state.currentSessionId.isBlank()) {
            return null;
        }
        String id = state.currentSessionId;
        for (ChatSession session : state.sessions) {
            if (session != null && id.equals(session.id)) {
                return session;
            }
        }
        return null;
    }

    public synchronized String getCurrentSessionIdRaw() {
        return state.currentSessionId == null ? "" : state.currentSessionId;
    }

    public synchronized List<ChatSession> getSessions() {
        return new ArrayList<>(state.sessions);
    }

    public synchronized void setCurrentBackendSessionId(String backendSessionId) {
        ChatSession current = getCurrentSession();
        if (current == null) {
            return;
        }
        if (backendSessionId == null || backendSessionId.isBlank()) {
            return;
        }
        if (backendSessionId.equals(current.backendSessionId)) {
            return;
        }
        current.backendSessionId = backendSessionId;
        persistToDisk();
    }

    @Override
    public synchronized @Nullable State getState() {
        return PERSIST_HISTORY ? state : new State();
    }

    @Override
    public synchronized void loadState(@NotNull State state) {
        if (!PERSIST_HISTORY) {
            this.state = new State();
            return;
        }
        this.state = state;
        migrateLegacyLines(this.state);
        loadFromDiskIfNeeded();
    }

    private static boolean resolvePersistHistory() {
        String prop = System.getProperty("codeagent.history.persist");
        if (prop != null && !prop.trim().isEmpty()) {
            return Boolean.parseBoolean(prop.trim());
        }
        String env = System.getenv("CODEAGENT_HISTORY_PERSIST");
        if (env != null && !env.trim().isEmpty()) {
            return Boolean.parseBoolean(env.trim());
        }
        return true;
    }

    private void loadFromDiskIfNeeded() {
        if (!PERSIST_HISTORY || historyFile == null) {
            return;
        }
        if (!Files.exists(historyFile)) {
            return;
        }
        try {
            State diskState = MAPPER.readValue(historyFile.toFile(), State.class);
            if (diskState != null) {
                mergeState(diskState);
            }
        } catch (Exception e) {
            logger.warn("chat_history_load_disk_failed path=" + historyFile, e);
        }
    }

    private void persistToDisk() {
        if (!PERSIST_HISTORY || historyFile == null) {
            return;
        }
        try {
            Files.createDirectories(historyFile.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(historyFile.toFile(), state);
        } catch (Exception e) {
            logger.warn("chat_history_persist_disk_failed path=" + historyFile, e);
        }
    }

    private void migrateLegacyLines(State target) {
        if (target == null) {
            return;
        }
        if (target.sessions == null) {
            target.sessions = new ArrayList<>();
        }
        if (target.lines == null) {
            target.lines = new ArrayList<>();
        }
        if (target.sessions.isEmpty() && !target.lines.isEmpty()) {
            ChatSession legacy = new ChatSession();
            legacy.id = UUID.randomUUID().toString();
            legacy.title = "Legacy Chat";
            legacy.createdAt = System.currentTimeMillis();
            legacy.messages = new ArrayList<>(target.lines);
            target.sessions.add(legacy);
            target.currentSessionId = legacy.id;
            target.lines.clear();
        }
        for (ChatSession session : target.sessions) {
            normalizeSession(session);
        }
    }

    private void normalizeSession(ChatSession session) {
        if (session == null) {
            return;
        }
        if (session.id == null || session.id.isBlank()) {
            session.id = UUID.randomUUID().toString();
        }
        if (session.messages == null) {
            session.messages = new ArrayList<>();
        }
        if (session.uiMessages == null) {
            session.uiMessages = new ArrayList<>();
        }
        if (session.title == null || session.title.isBlank()) {
            session.title = "New Chat";
        }
        if (session.createdAt <= 0) {
            session.createdAt = System.currentTimeMillis();
        }
        if (session.settings == null) {
            session.settings = new SessionSettings();
        }
        for (UiMessage message : session.uiMessages) {
            normalizeUiMessage(message);
        }
    }

    private void normalizeUiMessage(UiMessage message) {
        if (message == null) {
            return;
        }
        if (message.id == null || message.id.isBlank()) {
            message.id = UUID.randomUUID().toString();
        }
        if (message.role == null || message.role.isBlank()) {
            message.role = "assistant";
        }
        if (message.text == null) {
            message.text = "";
        }
        if (message.thought == null) {
            message.thought = "";
        }
        if (message.timestamp <= 0L) {
            message.timestamp = System.currentTimeMillis();
        }
        if (message.toolActivities == null) {
            message.toolActivities = new ArrayList<>();
        }
        for (ToolActivity activity : message.toolActivities) {
            normalizeToolActivity(activity);
        }
    }

    private void normalizeToolActivity(ToolActivity activity) {
        if (activity == null) {
            return;
        }
        if (activity.id == null || activity.id.isBlank()) {
            activity.id = UUID.randomUUID().toString();
        }
        if (activity.callID == null) {
            activity.callID = "";
        }
        if (activity.tool == null) {
            activity.tool = "";
        }
        if (activity.summary == null) {
            activity.summary = "";
        }
        if (activity.expandedSummary == null) {
            activity.expandedSummary = "";
        }
        if (activity.meta == null) {
            activity.meta = "";
        }
        if (activity.details == null) {
            activity.details = "";
        }
        if (activity.status == null) {
            activity.status = "";
        }
        if (activity.renderType == null) {
            activity.renderType = "";
        }
        if (activity.targetPath == null) {
            activity.targetPath = "";
        }
        if (activity.lineStart < 0) {
            activity.lineStart = -1;
        }
        if (activity.lineEnd < 0) {
            activity.lineEnd = -1;
        }
    }

    private void mergeState(State diskState) {
        migrateLegacyLines(this.state);
        migrateLegacyLines(diskState);

        Map<String, ChatSession> merged = new LinkedHashMap<>();
        for (ChatSession session : this.state.sessions) {
            if (session == null) {
                continue;
            }
            ChatSession copy = cloneSession(session);
            merged.put(copy.id, copy);
        }

        for (ChatSession session : diskState.sessions) {
            if (session == null) {
                continue;
            }
            ChatSession copy = cloneSession(session);
            ChatSession existing = merged.get(copy.id);
            if (existing == null) {
                merged.put(copy.id, copy);
            } else {
                mergeSession(existing, copy);
            }
        }

        this.state.sessions = new ArrayList<>(merged.values());
        this.state.sessions.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));

        if (!containsSessionId(this.state.sessions, this.state.currentSessionId)) {
            if (containsSessionId(this.state.sessions, diskState.currentSessionId)) {
                this.state.currentSessionId = diskState.currentSessionId;
            } else if (!this.state.sessions.isEmpty()) {
                this.state.currentSessionId = this.state.sessions.get(0).id;
            } else {
                this.state.currentSessionId = null;
            }
        }
    }

    private void mergeSession(ChatSession target, ChatSession incoming) {
        if (target == null || incoming == null) {
            return;
        }
        if (incoming.messages != null) {
            int incomingSize = incoming.messages.size();
            int targetSize = target.messages == null ? 0 : target.messages.size();
            if (incomingSize > targetSize
                    || (incomingSize == targetSize && totalChars(incoming.messages) > totalChars(target.messages))) {
                target.messages = new ArrayList<>(incoming.messages);
            }
        }
        if (incoming.uiMessages != null) {
            int incomingSize = incoming.uiMessages.size();
            int targetSize = target.uiMessages == null ? 0 : target.uiMessages.size();
            if (incomingSize > targetSize
                    || (incomingSize == targetSize && totalUiChars(incoming.uiMessages) > totalUiChars(target.uiMessages))) {
                target.uiMessages = new ArrayList<>();
                for (UiMessage message : incoming.uiMessages) {
                    target.uiMessages.add(cloneUiMessage(message));
                }
            }
        }
        if ((target.backendSessionId == null || target.backendSessionId.isBlank())
                && incoming.backendSessionId != null && !incoming.backendSessionId.isBlank()) {
            target.backendSessionId = incoming.backendSessionId;
        }
        if ((target.title == null || target.title.isBlank()) && incoming.title != null && !incoming.title.isBlank()) {
            target.title = incoming.title;
        }
        if (target.createdAt <= 0 && incoming.createdAt > 0) {
            target.createdAt = incoming.createdAt;
        }
        if (incoming.settings != null) {
            if (target.settings == null) {
                target.settings = cloneSettings(incoming.settings);
            } else {
                mergeSettings(target.settings, incoming.settings);
            }
        }
    }

    private void mergeSettings(SessionSettings target, SessionSettings incoming) {
        if (target == null || incoming == null) {
            return;
        }
        if ((target.model == null || target.model.isBlank()) && incoming.model != null && !incoming.model.isBlank()) {
            target.model = incoming.model;
        }
        if ((target.language == null || target.language.isBlank()) && incoming.language != null && !incoming.language.isBlank()) {
            target.language = incoming.language;
        }
        if ((target.agent == null || target.agent.isBlank()) && incoming.agent != null && !incoming.agent.isBlank()) {
            target.agent = incoming.agent;
        }
        if (target.temperature <= 0 && incoming.temperature > 0) {
            target.temperature = incoming.temperature;
        }
    }

    private ChatSession cloneSession(ChatSession source) {
        ChatSession session = new ChatSession();
        session.id = source.id;
        session.title = source.title;
        session.createdAt = source.createdAt;
        session.backendSessionId = source.backendSessionId;
        session.messages = source.messages == null ? new ArrayList<>() : new ArrayList<>(source.messages);
        session.uiMessages = new ArrayList<>();
        if (source.uiMessages != null) {
            for (UiMessage message : source.uiMessages) {
                session.uiMessages.add(cloneUiMessage(message));
            }
        }
        session.settings = cloneSettings(source.settings);
        normalizeSession(session);
        return session;
    }

    private UiMessage cloneUiMessage(UiMessage source) {
        UiMessage message = new UiMessage();
        if (source == null) {
            normalizeUiMessage(message);
            return message;
        }
        message.id = source.id;
        message.role = source.role;
        message.messageID = source.messageID;
        message.text = source.text;
        message.thought = source.thought;
        message.timestamp = source.timestamp;
        message.toolActivities = new ArrayList<>();
        if (source.toolActivities != null) {
            for (ToolActivity activity : source.toolActivities) {
                ToolActivity copied = new ToolActivity();
                if (activity != null) {
                    copied.id = activity.id;
                    copied.callID = activity.callID;
                    copied.tool = activity.tool;
                    copied.summary = activity.summary;
                    copied.expandedSummary = activity.expandedSummary;
                    copied.meta = activity.meta;
                    copied.details = activity.details;
                    copied.status = activity.status;
                    copied.durationMs = activity.durationMs;
                    copied.renderType = activity.renderType;
                    copied.targetPath = activity.targetPath;
                    copied.lineStart = activity.lineStart;
                    copied.lineEnd = activity.lineEnd;
                    copied.navigable = activity.navigable;
                }
                normalizeToolActivity(copied);
                message.toolActivities.add(copied);
            }
        }
        normalizeUiMessage(message);
        return message;
    }

    private SessionSettings cloneSettings(SessionSettings source) {
        SessionSettings settings = new SessionSettings();
        if (source == null) {
            return settings;
        }
        if (source.model != null && !source.model.isBlank()) {
            settings.model = source.model;
        }
        if (source.language != null && !source.language.isBlank()) {
            settings.language = source.language;
        }
        if (source.temperature > 0) {
            settings.temperature = source.temperature;
        }
        if (source.agent != null && !source.agent.isBlank()) {
            settings.agent = source.agent;
        }
        return settings;
    }

    private boolean containsSessionId(List<ChatSession> sessions, String sessionId) {
        if (sessions == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        for (ChatSession session : sessions) {
            if (session != null && sessionId.equals(session.id)) {
                return true;
            }
        }
        return false;
    }

    private int totalChars(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String line : lines) {
            if (line != null) {
                total += line.length();
            }
        }
        return total;
    }

    private int totalUiChars(List<UiMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (UiMessage message : messages) {
            if (message == null) {
                continue;
            }
            if (message.text != null) {
                total += message.text.length();
            }
            if (message.thought != null) {
                total += message.thought.length();
            }
            if (message.toolActivities != null) {
                for (ToolActivity activity : message.toolActivities) {
                    if (activity == null) {
                        continue;
                    }
                    if (activity.summary != null) {
                        total += activity.summary.length();
                    }
                    if (activity.meta != null) {
                        total += activity.meta.length();
                    }
                    if (activity.details != null) {
                        total += activity.details.length();
                    }
                    if (activity.targetPath != null) {
                        total += activity.targetPath.length();
                    }
                }
            }
        }
        return total;
    }

    private static Path resolveHistoryFile(Project project) {
        if (project == null || project.getBasePath() == null || project.getBasePath().isBlank()) {
            return null;
        }
        return Path.of(project.getBasePath(), ".codeagent", "chat-history.json");
    }

    private boolean isUserLine(String line) {
        if (line == null) {
            return false;
        }
        return line.startsWith("You: ") || line.startsWith("?: ");
    }

    private String stripUserPrefix(String line) {
        if (line == null) {
            return "";
        }
        if (line.startsWith("You: ")) {
            return line.substring(5).trim();
        }
        if (line.startsWith("?: ")) {
            return line.substring(3).trim();
        }
        return line.trim();
    }

    public static final class State {
        @XCollection(style = XCollection.Style.v2)
        public List<ChatSession> sessions = new ArrayList<>();

        public String currentSessionId;

        // Legacy field
        @XCollection(style = XCollection.Style.v2)
        public List<String> lines = new ArrayList<>();
    }

    public static final class ChatSession {
        public String id;
        public String title;
        public long createdAt;
        public String backendSessionId;
        @XCollection(style = XCollection.Style.v2)
        public List<String> messages = new ArrayList<>();
        @XCollection(style = XCollection.Style.v2)
        public List<UiMessage> uiMessages = new ArrayList<>();
        public SessionSettings settings = new SessionSettings();
    }

    public static final class UiMessage {
        public String id;
        public String role;
        public String messageID;
        public String text;
        public String thought;
        public long timestamp;
        @XCollection(style = XCollection.Style.v2)
        public List<ToolActivity> toolActivities = new ArrayList<>();
    }

    public static final class ToolActivity {
        public String id;
        public String callID;
        public String tool;
        public String summary;
        public String expandedSummary;
        public String meta;
        public String details;
        public String status;
        public long durationMs;
        public String renderType;
        public String targetPath;
        public int lineStart = -1;
        public int lineEnd = -1;
        public boolean navigable;
    }

    public static final class SessionSettings {
        public String model = "gpt-4o";
        public String language = "Chinese";
        public double temperature = 0.7;
        public String agent = "build";
    }
}
