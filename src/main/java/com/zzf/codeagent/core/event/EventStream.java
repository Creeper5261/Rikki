package com.zzf.codeagent.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public final class EventStream {
    private final ObjectMapper mapper;
    private final EventStore store;
    private final Map<String, EventSubscriber> subscribers = new ConcurrentHashMap<String, EventSubscriber>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final String workspaceRoot;

    public EventStream(ObjectMapper mapper, String sessionId, String workspaceRoot) {
        this.mapper = mapper;
        this.workspaceRoot = workspaceRoot;
        this.store = new EventStore(mapper, sessionId, workspaceRoot);
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public EventStore getStore() {
        return store;
    }

    public long getLatestEventId() {
        return store.getLatestEventId();
    }

    public AgentEvent addEvent(AgentEvent event, EventSource source) {
        if (event == null) {
            throw new IllegalArgumentException("event is null");
        }
        event.setSource(source);
        AgentEvent stored = store.appendEvent(event);
        for (Map.Entry<String, EventSubscriber> entry : subscribers.entrySet()) {
            EventSubscriber subscriber = entry.getValue();
            if (subscriber == null) {
                continue;
            }
            executor.submit(() -> subscriber.onEvent(stored));
        }
        return stored;
    }

    public void subscribe(String subscriberId, EventSubscriber subscriber) {
        if (subscriberId == null || subscriberId.trim().isEmpty()) {
            throw new IllegalArgumentException("subscriberId is blank");
        }
        if (subscriber == null) {
            throw new IllegalArgumentException("subscriber is null");
        }
        String key = subscriberId.trim();
        if (subscribers.containsKey(key)) {
            throw new IllegalStateException("subscriber already exists");
        }
        subscribers.put(key, subscriber);
    }

    public void unsubscribe(String subscriberId) {
        if (subscriberId == null || subscriberId.trim().isEmpty()) {
            return;
        }
        subscribers.remove(subscriberId.trim());
    }

    public AgentEvent updateSessionState(ObjectNode update, EventSource source, Long cause) {
        ObjectNode state = store.updateSessionState(update);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("scope", "session");
        if (update != null) {
            payload.set("update", update);
        }
        payload.set("state", state);
        AgentEvent event = new AgentEvent(EventType.STATE_UPDATE, "session_state", payload);
        if (cause != null && cause.longValue() >= 0) {
            event.setCause(cause);
        }
        return addEvent(event, source);
    }

    public AgentEvent updateWorkspaceState(ObjectNode update, EventSource source, Long cause) {
        ObjectNode state = store.updateWorkspaceState(update);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("scope", "workspace");
        if (update != null) {
            payload.set("update", update);
        }
        payload.set("state", state);
        AgentEvent event = new AgentEvent(EventType.STATE_UPDATE, "workspace_state", payload);
        if (cause != null && cause.longValue() >= 0) {
            event.setCause(cause);
        }
        return addEvent(event, source);
    }

    public java.util.List<AgentEvent> replay(long startId, Long endId, boolean reverse, EventSubscriber subscriber) {
        java.util.List<AgentEvent> events = store.searchEvents(startId, endId, reverse);
        if (subscriber != null) {
            for (AgentEvent event : events) {
                subscriber.onEvent(event);
            }
        }
        return events;
    }

    public List<AgentEvent> paginateEvents(long startId, int limit, boolean reverse) {
        return store.batchSearchEvents(startId, limit, reverse);
    }

    public List<AgentEvent> filterEvents(EventType type, EventSource source) {
        List<AgentEvent> events = store.searchEvents(0, null, false);
        return events.stream()
                .filter(e -> type == null || e.getType() == type)
                .filter(e -> source == null || e.getSource() == source)
                .collect(Collectors.toList());
    }

    public void close() {
        executor.shutdownNow();
        subscribers.clear();
    }
}
