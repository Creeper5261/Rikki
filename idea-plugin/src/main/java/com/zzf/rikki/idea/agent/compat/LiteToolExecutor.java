package com.zzf.rikki.idea.agent.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.project.Project;
import com.zzf.rikki.agent.AgentService;
import com.zzf.rikki.core.tool.BackendToolDefinitions;
import com.zzf.rikki.core.tool.CodeSearchTool;
import com.zzf.rikki.core.tool.PendingChangesManager;
import com.zzf.rikki.core.tool.TaskTool;
import com.zzf.rikki.core.tool.Tool;
import com.zzf.rikki.core.tool.ToolRegistry;
import com.zzf.rikki.core.tool.WebSearchTool;
import com.zzf.rikki.idea.agent.tools.LiteBashTool;
import com.zzf.rikki.idea.agent.tools.LiteFileTools;
import com.zzf.rikki.idea.agent.tools.LiteIdeTools;
import com.zzf.rikki.idea.agent.tools.LiteTodoTools;
import com.zzf.rikki.runtime.RuntimeServicesAware;
import com.zzf.rikki.runtime.port.ToolExecutorPort;
import com.zzf.rikki.session.SessionService;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LiteToolExecutor implements ToolExecutorPort, RuntimeServicesAware {
    private static final List<String> FILE_CHANGE_TOOLS = List.of("write", "edit", "delete_file");

    private final ObjectMapper mapper;
    private final InMemoryPendingApprovalService pendingApprovalService;
    private final LiteBashTool bash = new LiteBashTool();
    private final LiteFileTools files;
    private final LiteIdeTools ide;
    private final LiteTodoTools todos;
    private SessionService sessionService;
    private AgentService agentService;
    private ToolRegistry javaToolRegistry;

    public LiteToolExecutor(Project project, ObjectMapper mapper, InMemoryPendingApprovalService pendingApprovalService) {
        this.mapper = mapper;
        this.pendingApprovalService = pendingApprovalService;
        this.files = new LiteFileTools(mapper);
        this.ide = new LiteIdeTools(project, mapper);
        this.todos = new LiteTodoTools(mapper);
        this.javaToolRegistry = new ToolRegistry(List.of(new WebSearchTool(mapper), new CodeSearchTool(mapper)));
    }

    @Override
    public void bindRuntimeServices(SessionService sessionService, AgentService agentService) {
        this.sessionService = sessionService;
        this.agentService = agentService;
        this.javaToolRegistry = new ToolRegistry(List.of(
                new WebSearchTool(mapper),
                new CodeSearchTool(mapper),
                new TaskTool(agentService, sessionService, mapper)
        ));
    }

    @Override
    public boolean isHighRisk(String name, JsonNode args) {
        if ("bash".equals(name)) {
            return LiteBashTool.Companion.isHighRiskCommand(args.path("command").asText(""));
        }
        return "delete_file".equals(name);
    }

    @Override
    public ToolExecutionResult execute(String name, JsonNode args, String workspaceRoot, String sessionId, String callId, String messageId) {
        String filePath = FILE_CHANGE_TOOLS.contains(name) ? args.path("filePath").asText("") : "";
        File absFile = filePath.isBlank() ? null : resolveAbsFile(filePath, workspaceRoot);
        String oldContent = absFile != null && absFile.exists() ? readCurrent(absFile) : "";
        String changeType = "delete_file".equals(name) ? "DELETE" : ((absFile != null && absFile.exists()) ? "EDIT" : "CREATE");
        try {
            return switch (name) {
                case "bash" -> executeBash(args, workspaceRoot, sessionId);
                case "read" -> success("completed", files.read(args, workspaceRoot), null, null);
                case "write" -> withPendingChange(files.write(args, workspaceRoot), filePath, oldContent, readCurrent(absFile), changeType, workspaceRoot, sessionId);
                case "edit" -> withPendingChange(files.edit(args, workspaceRoot), filePath, oldContent, readCurrent(absFile), changeType, workspaceRoot, sessionId);
                case "delete_file" -> withPendingChange(files.delete(args, workspaceRoot), filePath, oldContent, "", "DELETE", workspaceRoot, sessionId);
                case "glob" -> success("completed", files.glob(args, workspaceRoot), null, null);
                case "grep" -> success("completed", files.grep(args, workspaceRoot), null, null);
                case "ls" -> success("completed", files.list(args, workspaceRoot), null, null);
                case "task", "web_search", "search_codebase" -> executeJavaTool(name, args, workspaceRoot, sessionId, callId, messageId);
                case "todo_read" -> success("completed", todos.read(workspaceRoot, sessionId), null, null);
                case "todo_write" -> {
                    String output = todos.write(args, workspaceRoot, sessionId);
                    yield success("completed", output, null, todos.readJson(workspaceRoot, sessionId));
                }
                case "ide_context" -> success("completed", ide.context(args), null, null);
                case "ide_action" -> success("completed", ide.action(args), null, null);
                case "ide_capabilities" -> success("completed", ide.capabilities(), null, null);
                default -> new ToolExecutionResult("error", "", "Unknown tool: " + name, Map.of(), null, null, null, null, false);
            };
        } catch (Exception e) {
            return new ToolExecutionResult("error", "", e.getMessage() == null ? "Tool error" : e.getMessage(), Map.of(), null, null, null, null, false);
        }
    }

    @Override
    public List<Map<String, Object>> toolDefinitions(String workspaceRoot, LiteIdeTools.CapabilitySnapshot snapshot) {
        return BackendToolDefinitions.build(workspaceRoot, snapshot);
    }

    @Override
    public LiteIdeTools.CapabilitySnapshot refreshIdeCapabilities() {
        return ide.refreshCapabilities();
    }

    @Override
    public void setIdeContext(JsonNode ideContext) {
        ide.setIdeContextNode(ideContext);
    }

    @Override
    public String readTodosJson(String workspaceRoot, String sessionId) {
        return todos.readJson(workspaceRoot, sessionId);
    }

    @Override
    public String todosAsListJson(String workspaceRoot) {
        String json = todos.readJson(workspaceRoot, "");
        return json == null ? "[]" : json;
    }

    private ToolExecutionResult executeBash(JsonNode args, String workspaceRoot, String sessionId) {
        CommandRunnerResult detail = bash.executeDetailed(args, workspaceRoot, pendingApprovalService.skipFlagFor(sessionId));
        long timeoutMs = args.path("timeout").asLong(CommandRunner.DEFAULT_TIMEOUT_MS);
        if (timeoutMs <= 0L) {
            timeoutMs = CommandRunner.DEFAULT_TIMEOUT_MS;
        }
        String output = bash.formatResult(args.path("command").asText(""), timeoutMs, detail);
        String status = detail.getSkipped() ? "rejected" : (detail.getExitCode() == 0 && !detail.getTimedOut() ? "completed" : "error");
        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
        meta.put("shell", detail.getShell());
        meta.put("exit", detail.getExitCode());
        meta.put("timeout", detail.getTimedOut());
        meta.put("skipped", detail.getSkipped());
        meta.put("output", output);
        return new ToolExecutionResult(status, output, "completed".equals(status) ? null : output, meta, null, null, null, detail.getExitCode(), detail.getTimedOut());
    }

    private ToolExecutionResult executeJavaTool(String name, JsonNode args, String workspaceRoot, String sessionId, String callId, String messageId) {
        Tool tool = javaToolRegistry.get(name).orElse(null);
        if (tool == null) {
            return new ToolExecutionResult("error", "", "Unknown tool: " + name, Map.of(), null, null, null, null, false);
        }
        Tool.Context context = Tool.Context.basic(sessionId, messageId, callId);
        context.setExtra(Map.of("workspaceRoot", workspaceRoot));
        if (sessionService != null) {
            context.setMessages(sessionService.getMessages(sessionId));
        }
        context.setPermissionAsker(request -> java.util.concurrent.CompletableFuture.completedFuture(null));
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        context.setMetadataConsumer((title, data) -> {
            if (title != null) {
                metadata.put("title", title);
            }
            if (data != null) {
                metadata.putAll(data);
            }
        });
        Tool.Result result = tool.execute(args, context).join();
        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
        if (result.getTitle() != null) {
            meta.put("title", result.getTitle());
        }
        meta.putAll(metadata);
        meta.putAll(result.getMetadata());
        return new ToolExecutionResult("completed", result.getOutput(), null, meta, null, null, null, null, false);
    }

    private ToolExecutionResult withPendingChange(
            String output,
            String filePath,
            String oldContent,
            String newContent,
            String changeType,
            String workspaceRoot,
            String sessionId
    ) {
        PendingChangesManager.PendingChange pendingChange = new PendingChangesManager.PendingChange(
                UUID.randomUUID().toString(),
                filePath,
                changeType,
                oldContent,
                newContent,
                "",
                System.currentTimeMillis(),
                workspaceRoot,
                sessionId
        );
        return success("completed", output, pendingChange, null);
    }

    private ToolExecutionResult success(String status, String output, PendingChangesManager.PendingChange pendingChange, String todoJson) {
        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
        if (pendingChange != null) {
            meta.put("workspaceApplied", Boolean.TRUE);
            meta.put("pending_change", pendingChange);
        }
        if (todoJson != null) {
            meta.put("todos", todoJson);
        }
        return new ToolExecutionResult(status, output, null, meta, pendingChange, null, todoJson, null, false);
    }

    private String readCurrent(File file) {
        if (file == null) {
            return "";
        }
        try {
            return Files.readString(file.toPath());
        } catch (Exception ignored) {
            return "";
        }
    }

    private File resolveAbsFile(String filePath, String workspaceRoot) {
        File file = new File(filePath);
        return file.isAbsolute() ? file : new File(workspaceRoot, filePath);
    }
}
