package com.zzf.codeagent.session;

import com.zzf.codeagent.bus.AgentBus;
import com.zzf.codeagent.bus.AgentBus.BusEvent;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话状态管理 (对齐 OpenCode SessionStatus)
 */
@Component
@RequiredArgsConstructor
public class SessionStatus {

    private final AgentBus agentBus;
    private final Map<String, Info> state = new ConcurrentHashMap<>();

    @Data
    @Builder
    public static class Info {
        private String type; // idle, retry, busy
        private Integer attempt;
        private String message;
        private Long next;
    }

    @Data
    @Builder
    public static class StatusEvent {
        private String sessionID;
        private Info status;
    }

    public static final BusEvent<StatusEvent> STATUS_EVENT = new BusEvent<>("session.status", StatusEvent.class);

    @SuppressWarnings("unchecked")
    public static final BusEvent<Map<String, String>> IDLE_EVENT = new BusEvent("session.idle", Map.class);

    public Info get(String sessionID) {
        return state.getOrDefault(sessionID, Info.builder().type("idle").build());
    }

    public void set(String sessionID, Info status) {
        agentBus.publish(STATUS_EVENT, StatusEvent.builder()
                .sessionID(sessionID)
                .status(status)
                .build());

        if ("idle".equals(status.getType())) {
            // deprecated
            agentBus.publish(IDLE_EVENT, Map.of("sessionID", sessionID));
            state.remove(sessionID);
            return;
        }
        state.put(sessionID, status);
    }

    public Map<String, Info> list() {
        return state;
    }
}
