package com.zzf.rikki.runtime.scenario;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.intellij.openapi.project.Project;
import com.zzf.rikki.idea.agent.compat.AgentEventSink;
import com.zzf.rikki.idea.agent.compat.InMemoryPendingApprovalService;
import com.zzf.rikki.idea.agent.compat.LiteChatLlmStreamClient;
import com.zzf.rikki.idea.agent.compat.LiteToolExecutor;
import com.zzf.rikki.idea.agent.compat.RuntimeEvent;
import com.zzf.rikki.runtime.AgentRuntimeBootstrap;
import com.zzf.rikki.runtime.AgentRuntimeFactory;
import com.zzf.rikki.runtime.RuntimeAgentConfig;
import com.zzf.rikki.runtime.port.AgentRuntime;
import com.zzf.rikki.runtime.port.LlmPort;
import com.zzf.rikki.runtime.port.RuntimeRequest;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RuntimeScenarioRunner {
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final YamlScenarioConfigResolver yamlConfigResolver = new YamlScenarioConfigResolver(System::getenv);

    public RuntimeScenarioSpec load(Path path) throws IOException {
        RuntimeScenarioSpec spec = yamlMapper.readValue(path.toFile(), RuntimeScenarioSpec.class);
        if (spec.name == null || spec.name.isBlank()) {
            spec.name = path.getFileName().toString();
        }
        return spec;
    }

    public ScenarioRunResult run(RuntimeScenarioSpec spec) throws Exception {
        Path workspaceRoot = prepareWorkspace(spec.workspaceFixture);
        RuntimeAgentConfig config = yamlConfigResolver.resolve(configMap(spec.config));
        InMemoryPendingApprovalService pendingApprovalService = new InMemoryPendingApprovalService();
        LiteToolExecutor delegateToolExecutor = new LiteToolExecutor((Project) null, jsonMapper, pendingApprovalService);
        ScenarioToolExecutor toolExecutor = new ScenarioToolExecutor(delegateToolExecutor, spec.stubs);
        LlmPort llmPort = spec.live
                ? new LiteChatLlmStreamClient(jsonMapper)
                : new ScriptedLlmPort(jsonMapper, spec.script);
        AgentRuntimeBootstrap bootstrap = AgentRuntimeFactory.create(jsonMapper, llmPort, pendingApprovalService, toolExecutor);
        AgentRuntime runtime = bootstrap.getRuntime();

        RecordingSink sink = new RecordingSink(pendingApprovalService, spec.controls);
        runtime.run(
                new RuntimeRequest(
                        spec.goal,
                        workspaceRoot.toString(),
                        jsonMapper.createObjectNode(),
                        historyNode(spec.history),
                        "scenario-" + UUID.randomUUID(),
                        config
                ),
                sink
        );

        assertExpectations(spec, sink.events);
        return new ScenarioRunResult(spec, workspaceRoot, sink.events);
    }

    public List<Path> scenarioFiles(String group) throws Exception {
        URI uri = RuntimeScenarioRunner.class.getClassLoader().getResource("runtime-scenarios/" + group).toURI();
        try (var files = Files.list(Path.of(uri))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        }
    }

    private Path prepareWorkspace(String fixtureName) throws Exception {
        Path workspaceRoot = Files.createTempDirectory("rikki-runtime-scenario");
        if (fixtureName == null || fixtureName.isBlank()) {
            return workspaceRoot;
        }
        URI uri = RuntimeScenarioRunner.class.getClassLoader().getResource("runtime-fixtures/" + fixtureName).toURI();
        Path fixtureRoot = Path.of(uri);
        try (var walk = Files.walk(fixtureRoot)) {
            for (Path source : walk.toList()) {
                Path relative = fixtureRoot.relativize(source);
                Path target = workspaceRoot.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return workspaceRoot;
    }

    private com.fasterxml.jackson.databind.JsonNode historyNode(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) {
            return jsonMapper.createArrayNode();
        }
        return jsonMapper.valueToTree(history);
    }

    private Map<String, Object> configMap(RuntimeScenarioSpec.Config config) {
        return jsonMapper.convertValue(config, new TypeReference<>() {
        });
    }

    private void assertExpectations(RuntimeScenarioSpec spec, List<RuntimeEvent> events) {
        RuntimeScenarioSpec.Expected expected = spec.expected == null ? new RuntimeScenarioSpec.Expected() : spec.expected;
        String answer = events.stream()
                .filter(RuntimeEvent.Finished.class::isInstance)
                .map(RuntimeEvent.Finished.class::cast)
                .map(RuntimeEvent.Finished::getAnswer)
                .reduce((first, second) -> second)
                .orElse("");

        if (expected.requireReasoning) {
            Assertions.assertTrue(events.stream().anyMatch(RuntimeEvent.ThoughtDelta.class::isInstance), "Expected reasoning events");
        }
        if (expected.requireNonEmptyAnswer) {
            Assertions.assertFalse(answer == null || answer.isBlank(), "Expected non-empty answer");
        }
        if (expected.answerContains != null && !expected.answerContains.isBlank()) {
            Assertions.assertTrue(answer.contains(expected.answerContains), "Expected answer to contain: " + expected.answerContains);
        }
        if (expected.answerRegex != null && !expected.answerRegex.isBlank()) {
            Assertions.assertTrue(answer.matches("(?s).*" + expected.answerRegex + ".*"), "Expected answer to match regex: " + expected.answerRegex);
        }
        for (String tool : expected.requiredTools) {
            Assertions.assertTrue(
                    events.stream().filter(RuntimeEvent.ToolCall.class::isInstance)
                            .map(RuntimeEvent.ToolCall.class::cast)
                            .anyMatch(event -> tool.equals(event.getTool())),
                    "Expected tool call for " + tool
            );
        }
        for (String forbidden : expected.forbiddenEvents) {
            Assertions.assertFalse(
                    events.stream().anyMatch(event -> eventType(event).equals(forbidden)),
                    "Did not expect event type " + forbidden
            );
        }
        for (Map.Entry<String, String> entry : expected.toolOutputContains.entrySet()) {
            Assertions.assertTrue(
                    events.stream()
                            .filter(RuntimeEvent.ToolResult.class::isInstance)
                            .map(RuntimeEvent.ToolResult.class::cast)
                            .filter(event -> entry.getKey().equals(event.getTool()))
                            .map(RuntimeEvent.ToolResult::getOutput)
                            .anyMatch(output -> output != null && output.contains(entry.getValue())),
                    "Expected tool output for " + entry.getKey() + " to contain " + entry.getValue()
            );
        }
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

    public record ScenarioRunResult(RuntimeScenarioSpec spec, Path workspaceRoot, List<RuntimeEvent> events) {
    }

    private static final class RecordingSink implements AgentEventSink {
        private final InMemoryPendingApprovalService pendingApprovalService;
        private final RuntimeScenarioSpec.Controls controls;
        private final List<RuntimeEvent> events = new ArrayList<>();
        private volatile boolean skipTriggered = false;

        private RecordingSink(InMemoryPendingApprovalService pendingApprovalService, RuntimeScenarioSpec.Controls controls) {
            this.pendingApprovalService = pendingApprovalService;
            this.controls = controls == null ? new RuntimeScenarioSpec.Controls() : controls;
        }

        @Override
        public void emit(RuntimeEvent event) {
            events.add(event);
            if (event instanceof RuntimeEvent.ToolPendingApproval) {
                if (controls.rejectPending) {
                    pendingApprovalService.resolveLatestPending(false);
                } else if (controls.approvePending) {
                    pendingApprovalService.resolveLatestPending(true);
                }
            }
            if (!skipTriggered
                    && event instanceof RuntimeEvent.ToolCall toolCall
                    && controls.skipOnTool != null
                    && !controls.skipOnTool.isBlank()
                    && controls.skipOnTool.equals(toolCall.getTool())
                    && "running".equals(toolCall.getState())) {
                skipTriggered = true;
                Thread thread = new Thread(() -> {
                    try {
                        Thread.sleep(Math.max(0, controls.skipDelayMs));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    pendingApprovalService.skipCurrentExecution();
                }, "runtime-scenario-skip");
                thread.setDaemon(true);
                thread.start();
            }
        }
    }
}
