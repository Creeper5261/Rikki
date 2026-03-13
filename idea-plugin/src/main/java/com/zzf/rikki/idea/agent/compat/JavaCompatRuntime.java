package com.zzf.rikki.idea.agent.compat;

import com.zzf.rikki.bus.AgentBus;
import com.zzf.rikki.runtime.port.AgentRuntime;
import com.zzf.rikki.runtime.port.RuntimeRequest;
import com.zzf.rikki.session.SessionLoop;

public class JavaCompatRuntime implements AgentRuntime {
    private final SessionLoop sessionLoop;
    private final AgentBus agentBus;

    public JavaCompatRuntime(SessionLoop sessionLoop, AgentBus agentBus) {
        this.sessionLoop = sessionLoop;
        this.agentBus = agentBus;
    }

    @Override
    public void run(RuntimeRequest request, AgentEventSink sink) {
        Runnable unsubscribe = agentBus.subscribeAll(event -> {
            if (event.getProperties() instanceof RuntimeEvent runtimeEvent) {
                sink.emit(runtimeEvent);
            }
        });
        try {
            sessionLoop.run(request, new BusEventSink(agentBus));
        } finally {
            unsubscribe.run();
        }
    }

    private static final class BusEventSink implements AgentEventSink {
        private final AgentBus agentBus;

        private BusEventSink(AgentBus agentBus) {
            this.agentBus = agentBus;
        }

        @Override
        public void emit(RuntimeEvent event) {
            agentBus.publish(eventType(event), event).join();
        }

        private String eventType(RuntimeEvent event) {
            if (event instanceof RuntimeEvent.SessionBound) return "session";
            if (event instanceof RuntimeEvent.StatusChanged) return "status";
            if (event instanceof RuntimeEvent.MessageDelta) return "message";
            if (event instanceof RuntimeEvent.MessageSnapshot) return "message_part";
            if (event instanceof RuntimeEvent.ThoughtDelta) return "thought";
            if (event instanceof RuntimeEvent.ThoughtEnd) return "thought_end";
            if (event instanceof RuntimeEvent.ToolCall) return "tool_call";
            if (event instanceof RuntimeEvent.ToolPendingApproval) return "tool_confirm";
            if (event instanceof RuntimeEvent.ToolResult) return "tool_result";
            if (event instanceof RuntimeEvent.TodoUpdated) return "todo_updated";
            if (event instanceof RuntimeEvent.Finished) return "finish";
            if (event instanceof RuntimeEvent.Errored) return "error";
            return "runtime";
        }
    }
}