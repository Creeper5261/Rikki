package com.zzf.rikki.runtime.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.idea.agent.compat.LlmChatRequest;
import com.zzf.rikki.idea.agent.compat.LlmStreamListener;
import com.zzf.rikki.idea.agent.compat.LlmStreamResult;
import com.zzf.rikki.idea.agent.compat.ToolCallInfo;
import com.zzf.rikki.runtime.port.LlmPort;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScriptedLlmPort implements LlmPort {
    private final ObjectMapper mapper;
    private final List<RuntimeScenarioSpec.ScriptTurn> turns;
    private final AtomicInteger index = new AtomicInteger();

    public ScriptedLlmPort(ObjectMapper mapper, List<RuntimeScenarioSpec.ScriptTurn> turns) {
        this.mapper = mapper;
        this.turns = turns == null ? List.of() : List.copyOf(turns);
    }

    @Override
    public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
        int current = index.getAndIncrement();
        if (current >= turns.size()) {
            return new LlmStreamResult("Error: scripted LLM exhausted before runtime completed.", List.of());
        }
        RuntimeScenarioSpec.ScriptTurn turn = turns.get(current);
        if (turn.reasoning != null && !turn.reasoning.isBlank()) {
            listener.onThoughtDelta(request.getMessageId(), turn.reasoning);
            listener.onThoughtEnd(request.getMessageId());
        }
        if (turn.text != null && !turn.text.isBlank()) {
            listener.onMessageDelta(request.getMessageId(), turn.text);
        }
        return new LlmStreamResult(turn.text, toToolCalls(turn.toolCalls), turn.reasoning);
    }

    private List<ToolCallInfo> toToolCalls(List<RuntimeScenarioSpec.ToolCallSpec> specs) {
        List<ToolCallInfo> toolCalls = new ArrayList<>();
        if (specs == null) {
            return toolCalls;
        }
        for (RuntimeScenarioSpec.ToolCallSpec spec : specs) {
            JsonNode args = mapper.valueToTree(spec.args == null ? java.util.Map.of() : spec.args);
            String id = spec.id == null || spec.id.isBlank() ? "call-" + toolCalls.size() : spec.id;
            toolCalls.add(new ToolCallInfo(id, spec.name, args.toString(), args));
        }
        return toolCalls;
    }
}
