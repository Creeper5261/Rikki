package com.zzf.codeagent.api;

import com.zzf.codeagent.service.AgentChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public final class AgentChatController {
    private final AgentChatService agentChatService;

    public AgentChatController(AgentChatService agentChatService) {
        this.agentChatService = agentChatService;
    }

    @GetMapping("/api/agent/health")
    public ResponseEntity<Map<String, Object>> health() {
        return agentChatService.health();
    }

    @PostMapping("/api/agent/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest req) {
        return agentChatService.chat(req);
    }

    @PostMapping("/api/agent/chat/stream")
    public SseEmitter chatStream(@RequestBody ChatRequest req) {
        return agentChatService.chatStream(req);
    }

    @PostMapping("/api/agent/pending")
    public ResponseEntity<Map<String, Object>> resolvePending(@RequestBody PendingChangeRequest req) {
        return agentChatService.resolvePending(req);
    }

    @PostMapping("/api/agent/history/compress")
    public ResponseEntity<Map<String, Object>> compressHistory(@RequestBody CompressHistoryRequest req) {
        return agentChatService.compressHistory(req);
    }

    @PostMapping("/api/agent/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody Map<String, Object> req) {
        return agentChatService.search(req);
    }

    public static final class ChatRequest {
        public String goal;
        public String workspaceRoot;
        public String workspaceName;
        public List<String> history;
        public String ideContextPath;
        public String ideContextContent;
        public List<String> ideOpenedFiles;
        public String agentRole;
    }

    public static final class CompressHistoryRequest {
        public List<String> history;
        public String goalHint;
    }

    public static final class PendingChangeRequest {
        public String traceId;
        public String workspaceRoot;
        public String path;
        public List<String> paths;
        public Boolean reject;
    }

    public static final class ChatResponse {
        public final String traceId;
        public final String answer;
        public final Boolean isNewTopic;
        public final String topicTitle;
        public final Map<String, Object> meta;

        public ChatResponse(String traceId, String answer) {
            this(traceId, answer, null, null, null);
        }

        public ChatResponse(String traceId, String answer, Boolean isNewTopic, String topicTitle) {
            this(traceId, answer, isNewTopic, topicTitle, null);
        }

        public ChatResponse(String traceId, String answer, Boolean isNewTopic, String topicTitle, Map<String, Object> meta) {
            this.traceId = traceId;
            this.answer = answer;
            this.isNewTopic = isNewTopic;
            this.topicTitle = topicTitle;
            this.meta = meta;
        }
    }
}
