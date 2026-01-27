package com.zzf.codeagent.core.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class EventStore {
    private final ObjectMapper mapper;
    private final Path sessionDir;
    private final Path eventsDir;
    private final Path cacheDir;
    private final Path sessionStateFile;
    private final Path workspaceStateFile;
    private final Object lock = new Object();
    private final int cacheSize;
    private long curId;
    private final List<AgentEvent> writePage = new ArrayList<AgentEvent>();
    private long writePageStart = -1;

    public EventStore(ObjectMapper mapper, String sessionId, String workspaceRoot) {
        this.mapper = mapper;
        this.cacheSize = 25;
        Path base = resolveBaseDir(workspaceRoot);
        String safeSession = safeId(sessionId);
        this.sessionDir = base.resolve(".codeagent").resolve("events").resolve(safeSession);
        this.eventsDir = sessionDir.resolve("events");
        this.cacheDir = sessionDir.resolve("event_cache");
        this.sessionStateFile = sessionDir.resolve("session_state.json");
        this.workspaceStateFile = base.resolve(".codeagent").resolve("workspace_state.json");
        try {
            Files.createDirectories(eventsDir);
            Files.createDirectories(cacheDir);
        } catch (Exception e) {
            throw new IllegalStateException("event store init failed: " + e.getMessage(), e);
        }
        this.curId = detectCurId();
    }

    public Path getSessionDir() {
        return sessionDir;
    }

    public long getLatestEventId() {
        synchronized (lock) {
            return curId - 1;
        }
    }

    public AgentEvent appendEvent(AgentEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event is null");
        }
        synchronized (lock) {
            if (event.getId() >= 0) {
                throw new IllegalArgumentException("event already has id");
            }
            event.setId(curId++);
            if (event.getTimestamp() == null || event.getTimestamp().isEmpty()) {
                event.setTimestamp(Instant.now().toString());
            }
            writeEventFile(event);
            writePageEvent(event);
            return event;
        }
    }

    public AgentEvent getEvent(long id) {
        Path file = eventsDir.resolve(id + ".json");
        try {
            String json = Files.readString(file);
            return mapper.readValue(json, AgentEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("event read failed: " + e.getMessage(), e);
        }
    }

    public List<AgentEvent> searchEvents(long startId, Long endId, boolean reverse) {
        long endExclusive = endId == null ? getLatestEventId() + 1 : endId + 1;
        List<AgentEvent> out = new ArrayList<AgentEvent>();
        if (reverse) {
            for (long i = endExclusive - 1; i >= startId; i--) {
                if (i < 0) {
                    break;
                }
                out.add(readEventWithCache(i));
            }
        } else {
            for (long i = startId; i < endExclusive; i++) {
                if (i < 0) {
                    continue;
                }
                out.add(readEventWithCache(i));
            }
        }
        return out;
    }

    public List<AgentEvent> batchSearchEvents(long startId, int batchSize, boolean reverse) {
        if (batchSize <= 0) {
            return new ArrayList<AgentEvent>();
        }
        synchronized (lock) {
            if (reverse) {
                long endId = Math.max(0, startId);
                long startInclusive = Math.max(0, endId - batchSize + 1);
                return searchEvents(startInclusive, endId, true);
            }
            long endId = startId + batchSize - 1;
            return searchEvents(startId, endId, false);
        }
    }

    public ObjectNode getSessionState() {
        synchronized (lock) {
            return readStateObject(sessionStateFile);
        }
    }

    public ObjectNode getWorkspaceState() {
        synchronized (lock) {
            return readStateObject(workspaceStateFile);
        }
    }

    public ObjectNode updateSessionState(ObjectNode update) {
        synchronized (lock) {
            return updateState(sessionStateFile, update);
        }
    }

    public ObjectNode updateWorkspaceState(ObjectNode update) {
        synchronized (lock) {
            return updateState(workspaceStateFile, update);
        }
    }

    private AgentEvent readEventWithCache(long id) {
        try {
            Path cache = cacheFileForId(id);
            if (Files.exists(cache)) {
                String json = Files.readString(cache);
                AgentEvent[] page = mapper.readValue(json, AgentEvent[].class);
                int offset = (int) (id % cacheSize);
                if (offset >= 0 && offset < page.length && page[offset] != null) {
                    return page[offset];
                }
            }
        } catch (Exception ignored) {
        }
        return getEvent(id);
    }

    private ObjectNode updateState(Path file, ObjectNode update) {
        ObjectNode state = readStateObject(file);
        if (update != null) {
            state.setAll(update);
        }
        writeState(file, state);
        return state;
    }

    private ObjectNode readStateObject(Path file) {
        if (file == null || !Files.exists(file)) {
            return mapper.createObjectNode();
        }
        try {
            String json = Files.readString(file);
            JsonNode node = mapper.readTree(json);
            if (node instanceof ObjectNode) {
                return (ObjectNode) node;
            }
        } catch (Exception ignored) {
        }
        return mapper.createObjectNode();
    }

    private void writeState(Path file, ObjectNode state) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            String json = mapper.writeValueAsString(state == null ? mapper.createObjectNode() : state);
            Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("state write failed: " + e.getMessage(), e);
        }
    }

    private void writeEventFile(AgentEvent event) {
        Path file = eventsDir.resolve(event.getId() + ".json");
        try {
            String json = mapper.writeValueAsString(event);
            Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("event write failed: " + e.getMessage(), e);
        }
    }

    private void writePageEvent(AgentEvent event) {
        if (writePage.isEmpty()) {
            writePageStart = event.getId();
        }
        writePage.add(event);
        if (writePage.size() >= cacheSize) {
            long start = writePageStart;
            long end = start + cacheSize;
            Path cache = cacheDir.resolve(start + "-" + end + ".json");
            try {
                String json = mapper.writeValueAsString(writePage);
                Files.writeString(cache, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception e) {
                throw new IllegalStateException("event cache write failed: " + e.getMessage(), e);
            } finally {
                writePage.clear();
                writePageStart = -1;
            }
        }
    }

    private long detectCurId() {
        if (!Files.exists(eventsDir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(eventsDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> parseId(p.getFileName().toString()))
                    .filter(id -> id >= 0)
                    .max(Comparator.naturalOrder())
                    .map(id -> id + 1)
                    .orElse(0L);
        } catch (Exception e) {
            return 0;
        }
    }

    private long parseId(String name) {
        try {
            String n = name.replace(".json", "");
            return Long.parseLong(n);
        } catch (Exception e) {
            return -1;
        }
    }

    private Path cacheFileForId(long id) {
        long start = id - (id % cacheSize);
        long end = start + cacheSize;
        return cacheDir.resolve(start + "-" + end + ".json");
    }

    private Path resolveBaseDir(String workspaceRoot) {
        if (workspaceRoot != null && !workspaceRoot.trim().isEmpty()) {
            return Path.of(workspaceRoot.trim()).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private String safeId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return "session";
        }
        String s = sessionId.trim();
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
