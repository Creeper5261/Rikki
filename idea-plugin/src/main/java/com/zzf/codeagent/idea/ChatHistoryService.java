package com.zzf.codeagent.idea;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.util.xmlb.annotations.Tag;
import com.intellij.util.xmlb.annotations.XCollection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service(Service.Level.PROJECT)
@State(name = "CodeAgentChatHistory", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ChatHistoryService implements PersistentStateComponent<ChatHistoryService.State> {
    private State state = new State();
    private static final boolean PERSIST_HISTORY = resolvePersistHistory();

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
        if (current.messages.size() == 1 && line.startsWith("你: ")) {
            String title = line.substring(3).trim();
            if (title.length() > 20) title = title.substring(0, 20) + "...";
            current.title = title;
        }
    }

    public synchronized ChatSession createSession(String title) {
        ChatSession session = new ChatSession();
        session.id = UUID.randomUUID().toString();
        session.title = title;
        session.createdAt = System.currentTimeMillis();
        state.sessions.add(0, session); // Add to top
        state.currentSessionId = session.id;
        return session;
    }

    public synchronized void deleteSession(String id) {
        state.sessions.removeIf(s -> s.id.equals(id));
        if (state.currentSessionId != null && state.currentSessionId.equals(id)) {
            state.currentSessionId = state.sessions.isEmpty() ? null : state.sessions.get(0).id;
        }
    }

    public synchronized void setCurrentSession(String id) {
        state.currentSessionId = id;
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

    public synchronized List<ChatSession> getSessions() {
        return new ArrayList<>(state.sessions);
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
        
        // Migration: If sessions empty but lines exist, create a default session
        if (this.state.sessions.isEmpty() && !this.state.lines.isEmpty()) {
            ChatSession legacy = new ChatSession();
            legacy.id = UUID.randomUUID().toString();
            legacy.title = "Legacy Chat";
            legacy.createdAt = System.currentTimeMillis();
            legacy.messages = new ArrayList<>(this.state.lines);
            this.state.sessions.add(legacy);
            this.state.currentSessionId = legacy.id;
            this.state.lines.clear(); // Clear legacy lines
        }
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
        return false;
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
        @XCollection(style = XCollection.Style.v2)
        public List<String> messages = new ArrayList<>();
        public SessionSettings settings = new SessionSettings();
    }

    public static final class SessionSettings {
        public String model = "gpt-4o";
        public String language = "Chinese";
        public double temperature = 0.7;
    }
}
