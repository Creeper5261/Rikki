package com.zzf.rikki.session;

import java.util.concurrent.ConcurrentHashMap;

public class SessionStatus {
    public static final class Info {
        public final String type;
        public final Integer attempt;
        public final String message;
        public final Long next;

        public Info(String type) {
            this(type, null, null, null);
        }

        public Info(String type, Integer attempt, String message, Long next) {
            this.type = type;
            this.attempt = attempt;
            this.message = message;
            this.next = next;
        }
    }

    private final ConcurrentHashMap<String, Info> state = new ConcurrentHashMap<>();

    public Info get(String sessionId) {
        return state.getOrDefault(sessionId, new Info("idle"));
    }

    public void set(String sessionId, Info info) {
        if (info == null || "idle".equals(info.type)) {
            state.remove(sessionId);
        } else {
            state.put(sessionId, info);
        }
    }
}
