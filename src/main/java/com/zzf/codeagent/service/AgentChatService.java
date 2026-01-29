package com.zzf.codeagent.service;

import com.zzf.codeagent.api.AgentChatController.ChatRequest;
import com.zzf.codeagent.api.AgentChatController.ChatResponse;
import com.zzf.codeagent.api.AgentChatController.CompressHistoryRequest;
import com.zzf.codeagent.api.AgentChatController.PendingChangeRequest;
import com.zzf.codeagent.core.runtime.RuntimeService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public final class AgentChatService {
    private static final String JSON_UTF8 = "application/json;charset=UTF-8";

    private final AgentService agentService;
    private final RetrievalService retrievalService;
    private final ContextService contextService;
    private final RuntimeService runtimeService;

    public AgentChatService(AgentService agentService, RetrievalService retrievalService, ContextService contextService, RuntimeService runtimeService) {
        this.agentService = agentService;
        this.retrievalService = retrievalService;
        this.contextService = contextService;
        this.runtimeService = runtimeService;
    }

    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> out = new HashMap<String, Object>();
        out.put("docker", contextService.dockerHealth());
        out.put("elasticsearch", retrievalService.esHealth());
        out.put("deepseek", contextService.deepSeekHealth());
        out.put("runtime", runtimeService.health());
        return jsonOk(out);
    }

    public ResponseEntity<ChatResponse> chat(ChatRequest req) {
        return agentService.chat(req);
    }

    public SseEmitter chatStream(ChatRequest req) {
        return agentService.chatStream(req);
    }

    public ResponseEntity<Map<String, Object>> compressHistory(CompressHistoryRequest req) {
        return agentService.compressHistory(req);
    }

    public ResponseEntity<Map<String, Object>> search(Map<String, Object> req) {
        return retrievalService.search(req);
    }

    public ResponseEntity<Map<String, Object>> resolvePending(PendingChangeRequest req) {
        return agentService.resolvePending(req);
    }

    private ResponseEntity<Map<String, Object>> jsonOk(Map<String, Object> body) {
        return ResponseEntity.ok().header("Content-Type", JSON_UTF8).body(body);
    }
}
