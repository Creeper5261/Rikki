package com.zzf.codeagent.core.event;

import com.fasterxml.jackson.databind.JsonNode;

public final class AgentEvent {
    private long id = -1;
    private String timestamp;
    private EventSource source;
    private Long cause;
    private EventType type;
    private String message;
    private JsonNode payload;

    public AgentEvent() {
    }

    public AgentEvent(EventType type, String message, JsonNode payload) {
        this.type = type;
        this.message = message;
        this.payload = payload;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public EventSource getSource() {
        return source;
    }

    public void setSource(EventSource source) {
        this.source = source;
    }

    public Long getCause() {
        return cause;
    }

    public void setCause(Long cause) {
        this.cause = cause;
    }

    public EventType getType() {
        return type;
    }

    public void setType(EventType type) {
        this.type = type;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }
}
