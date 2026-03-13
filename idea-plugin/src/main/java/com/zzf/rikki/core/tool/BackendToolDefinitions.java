package com.zzf.rikki.core.tool;

import com.zzf.rikki.idea.agent.tools.LiteIdeTools;
import com.zzf.rikki.session.PromptTextLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BackendToolDefinitions {
    private BackendToolDefinitions() {
    }

    public static List<Map<String, Object>> build(String workspaceRoot, LiteIdeTools.CapabilitySnapshot snapshot) {
        List<Map<String, Object>> defs = new ArrayList<>();
        defs.add(tool(
                "bash",
                PromptTextLoader.loadToolDescription("bash", workspaceRoot),
                props(
                        "command", str("The command to execute"),
                        "timeout", integer("Optional timeout in milliseconds"),
                        "workdir", str("The working directory to run the command in. Defaults to " + workspaceRoot),
                        "description", str("Clear, concise description of what this command does in 5-10 words."),
                        "shell", mapOf(
                                "type", "string",
                                "enum", List.of("auto", "bash", "powershell", "cmd"),
                                "description", "Optional plugin extension for shell selection. auto tries bash, then powershell, then cmd."
                        )
                ),
                List.of("command", "description")
        ));
        defs.add(tool("read", PromptTextLoader.loadToolDescription("read", workspaceRoot), props(
                "filePath", str("The absolute path to the file to read"),
                "offset", integer("The line number to start reading from (0-based)"),
                "limit", integer("The number of lines to read (defaults to 2000)")
        ), List.of("filePath")));
        defs.add(tool("write", PromptTextLoader.loadToolDescription("write", workspaceRoot), props(
                "filePath", str("The absolute or relative path to the file"),
                "content", str("The full content to write to the file")
        ), List.of("filePath", "content")));
        defs.add(tool("edit", PromptTextLoader.loadToolDescription("edit", workspaceRoot), props(
                "filePath", str("The absolute path to the file to modify"),
                "oldString", str("The text to replace. Leave empty if creating a new file."),
                "newString", str("The text to replace it with (must be different from oldString)"),
                "replaceAll", bool("Replace all occurrences of oldString (default false)")
        ), List.of("filePath", "newString")));
        defs.add(tool("delete_file", PromptTextLoader.loadToolDescription("delete_file", workspaceRoot), props(
                "filePath", str("The absolute path to the file to delete")
        ), List.of("filePath")));
        defs.add(tool("glob", PromptTextLoader.loadToolDescription("glob", workspaceRoot), props(
                "pattern", str("The glob pattern to match files against"),
                "path", str("The directory to search in. If not specified, current working directory is used.")
        ), List.of("pattern")));
        defs.add(tool("grep", PromptTextLoader.loadToolDescription("grep", workspaceRoot), props(
                "pattern", str("The regex pattern to search for in file contents"),
                "path", str("The directory to search in. Defaults to the current working directory."),
                "include", str("File pattern to include in the search.")
        ), List.of("pattern")));
        defs.add(tool("ls", PromptTextLoader.loadToolDescription("ls", workspaceRoot), props(
                "path", str("The absolute path to the directory to list (must be absolute, not relative)"),
                "ignore", mapOf("type", "array", "items", mapOf("type", "string"))
        ), List.of()));
        defs.add(tool("task", PromptTextLoader.loadToolDescription("task", workspaceRoot), props(
                "description", str("A short description of the task."),
                "prompt", str("The task the subagent should perform."),
                "subagent_type", str("The subagent type to use."),
                "session_id", str("Optional existing sub-session id to continue.")
        ), List.of("description", "prompt", "subagent_type")));
        defs.add(tool("web_search", PromptTextLoader.loadToolDescription("web_search", workspaceRoot), props(
                "query", str("Websearch query"),
                "numResults", integer("Number of search results to return (default: 8)"),
                "livecrawl", str("Live crawl mode. Use fallback by default or preferred to force live crawling."),
                "type", str("Search type. Use auto by default, or fast/deep when needed."),
                "contextMaxCharacters", integer("Maximum context characters to return.")
        ), List.of("query")));
        defs.add(tool("search_codebase", PromptTextLoader.loadToolDescription("search_codebase", workspaceRoot), props(
                "query", str("Search query to find relevant APIs, libraries, or SDK usage."),
                "tokensNum", integer("Number of tokens to return. Defaults to 5000.")
        ), List.of("query")));
        defs.add(tool("todo_read", PromptTextLoader.loadToolDescription("todo_read", workspaceRoot), props(), List.of()));
        defs.add(tool("todo_write", PromptTextLoader.loadToolDescription("todo_write", workspaceRoot), props(
                "todos", mapOf(
                        "type", "array",
                        "items", mapOf(
                                "type", "object",
                                "properties", mapOf(
                                        "id", mapOf("type", "string"),
                                        "content", mapOf("type", "string"),
                                        "status", mapOf("type", "string"),
                                        "priority", mapOf("type", "string")
                                ),
                                "required", List.of("content", "status")
                        )
                )
        ), List.of("todos")));
        defs.add(tool("ide_context", PromptTextLoader.loadToolDescription("ide_context", workspaceRoot), props(
                "query", mapOf(
                        "type", "string",
                        "enum", List.of("project", "sdk", "build", "modules", "all"),
                        "description", "Which IDE context section to read. Defaults to all."
                ),
                "keys", mapOf(
                        "type", "array",
                        "items", mapOf("type", "string"),
                        "description", "Optional exact keys to return from IDE context."
                ),
                "maxItems", integer("Max list items to return (default 20, max 100).")
        ), List.of()));
        if (snapshot.getBridgeAvailable() && !snapshot.getActionOperations().isEmpty()) {
            defs.add(tool("ide_action", PromptTextLoader.loadToolDescription("ide_action", workspaceRoot), props(
                    "operation", mapOf(
                            "type", "string",
                            "enum", snapshot.getActionOperations(),
                            "description", "IDE action to execute."
                    ),
                    "mode", mapOf(
                            "type", "string",
                            "enum", List.of("make", "rebuild"),
                            "description", "Build mode (used when operation=build)."
                    ),
                    "configuration", str("Run configuration name (used for run/test)."),
                    "executor", mapOf(
                            "type", "string",
                            "enum", List.of("run", "debug"),
                            "description", "Executor for run/test."
                    ),
                    "jobId", str("Job id for status/cancel."),
                    "sinceRevision", integer("Optional log cursor for operation=status."),
                    "wait", bool("Whether to block until async job reaches terminal status."),
                    "timeoutMs", integer("Wait timeout for operation or polling."),
                    "pollIntervalMs", integer("Polling interval while waiting for job status."),
                    "waitMs", integer("Long-poll wait duration for operation=status.")
            ), List.of("operation")));
        }
        defs.add(tool("ide_capabilities", PromptTextLoader.loadToolDescription("ide_capabilities", workspaceRoot), props(), List.of()));
        return defs;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
        return mapOf(
                "type", "function",
                "function", mapOf(
                        "name", name,
                        "description", description,
                        "parameters", mapOf(
                                "type", "object",
                                "properties", properties,
                                "required", required
                        )
                )
        );
    }

    private static Map<String, Object> props(Object... values) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put((String) values[i], values[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> str(String description) {
        return mapOf("type", "string", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return mapOf("type", "integer", "description", description);
    }

    private static Map<String, Object> bool(String description) {
        return mapOf("type", "boolean", "description", description);
    }

    private static Map<String, Object> mapOf(Object... values) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put((String) values[i], values[i + 1]);
        }
        return map;
    }
}