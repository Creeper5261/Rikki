package com.zzf.rikki.idea.agent.compat

import com.zzf.rikki.idea.agent.tools.LiteIdeTools

object BackendToolDefinitions {
    fun build(workspaceRoot: String, snapshot: LiteIdeTools.CapabilitySnapshot): List<Map<String, Any>> {
        val defs = mutableListOf<Map<String, Any>>()
        defs += tool(
            "bash",
            PromptTextLoader.loadToolDescription("bash", workspaceRoot),
            props(
                "command" to str("The command to execute"),
                "timeout" to int("Optional timeout in milliseconds"),
                "workdir" to str("The working directory to run the command in. Defaults to $workspaceRoot"),
                "description" to str("Clear, concise description of what this command does in 5-10 words."),
                "shell" to mapOf(
                    "type" to "string",
                    "enum" to listOf("auto", "bash", "powershell", "cmd"),
                    "description" to "Optional plugin extension for shell selection. auto tries bash, then powershell, then cmd."
                )
            ),
            required = listOf("command", "description")
        )
        defs += tool(
            "read",
            PromptTextLoader.loadToolDescription("read", workspaceRoot),
            props(
                "filePath" to str("The absolute path to the file to read"),
                "offset" to int("The line number to start reading from (0-based)"),
                "limit" to int("The number of lines to read (defaults to 2000)")
            ),
            required = listOf("filePath")
        )
        defs += tool(
            "write",
            PromptTextLoader.loadToolDescription("write", workspaceRoot),
            props(
                "filePath" to str("The absolute or relative path to the file"),
                "content" to str("The full content to write to the file")
            ),
            required = listOf("filePath", "content")
        )
        defs += tool(
            "edit",
            PromptTextLoader.loadToolDescription("edit", workspaceRoot),
            props(
                "filePath" to str("The absolute path to the file to modify"),
                "oldString" to str("The text to replace. Leave empty if creating a new file."),
                "newString" to str("The text to replace it with (must be different from oldString)"),
                "replaceAll" to bool("Replace all occurrences of oldString (default false)")
            ),
            required = listOf("filePath", "newString")
        )
        defs += tool(
            "delete_file",
            PromptTextLoader.loadToolDescription("delete_file", workspaceRoot),
            props("filePath" to str("The absolute path to the file to delete")),
            required = listOf("filePath")
        )
        defs += tool(
            "glob",
            PromptTextLoader.loadToolDescription("glob", workspaceRoot),
            props(
                "pattern" to str("The glob pattern to match files against"),
                "path" to str("The directory to search in. If not specified, current working directory is used.")
            ),
            required = listOf("pattern")
        )
        defs += tool(
            "grep",
            PromptTextLoader.loadToolDescription("grep", workspaceRoot),
            props(
                "pattern" to str("The regex pattern to search for in file contents"),
                "path" to str("The directory to search in. Defaults to the current working directory."),
                "include" to str("File pattern to include in the search.")
            ),
            required = listOf("pattern")
        )
        defs += tool(
            "ls",
            PromptTextLoader.loadToolDescription("ls", workspaceRoot),
            props(
                "path" to str("The absolute path to the directory to list (must be absolute, not relative)"),
                "ignore" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string")
                )
            ),
            required = emptyList()
        )
        defs += tool(
            "todo_read",
            PromptTextLoader.loadToolDescription("todo_read", workspaceRoot),
            props(),
            required = emptyList()
        )
        defs += tool(
            "todo_write",
            PromptTextLoader.loadToolDescription("todo_write", workspaceRoot),
            props(
                "todos" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "id" to mapOf("type" to "string"),
                            "content" to mapOf("type" to "string"),
                            "status" to mapOf("type" to "string"),
                            "priority" to mapOf("type" to "string")
                        ),
                        "required" to listOf("content", "status")
                    )
                )
            ),
            required = listOf("todos")
        )
        defs += tool(
            "ide_context",
            PromptTextLoader.loadToolDescription("ide_context", workspaceRoot),
            props(
                "query" to mapOf(
                    "type" to "string",
                    "enum" to listOf("project", "sdk", "build", "modules", "all"),
                    "description" to "Which IDE context section to read. Defaults to all."
                ),
                "keys" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                    "description" to "Optional exact keys to return from IDE context."
                ),
                "maxItems" to int("Max list items to return (default 20, max 100).")
            ),
            required = emptyList()
        )
        if (snapshot.bridgeAvailable && snapshot.actionOperations.isNotEmpty()) {
            defs += tool(
                "ide_action",
                PromptTextLoader.loadToolDescription("ide_action", workspaceRoot),
                props(
                    "operation" to mapOf(
                        "type" to "string",
                        "enum" to snapshot.actionOperations,
                        "description" to "IDE action to execute."
                    ),
                    "mode" to mapOf(
                        "type" to "string",
                        "enum" to listOf("make", "rebuild"),
                        "description" to "Build mode (used when operation=build)."
                    ),
                    "configuration" to str("Run configuration name (used for run/test)."),
                    "executor" to mapOf(
                        "type" to "string",
                        "enum" to listOf("run", "debug"),
                        "description" to "Executor for run/test."
                    ),
                    "jobId" to str("Job id for status/cancel."),
                    "sinceRevision" to int("Optional log cursor for operation=status."),
                    "wait" to bool("Whether to block until async job reaches terminal status."),
                    "timeoutMs" to int("Wait timeout for operation or polling."),
                    "pollIntervalMs" to int("Polling interval while waiting for job status."),
                    "waitMs" to int("Long-poll wait duration for operation=status.")
                ),
                required = listOf("operation")
            )
        }
        defs += tool(
            "ide_capabilities",
            PromptTextLoader.loadToolDescription("ide_capabilities", workspaceRoot),
            props(),
            required = emptyList()
        )
        return defs
    }

    private fun tool(name: String, description: String, properties: Map<String, Any>, required: List<String>): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to description,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to properties,
                "required" to required
            )
        )
    )

    private fun props(vararg pairs: Pair<String, Any>): Map<String, Any> = linkedMapOf(*pairs)
    private fun str(desc: String) = mapOf("type" to "string", "description" to desc)
    private fun int(desc: String) = mapOf("type" to "integer", "description" to desc)
    private fun bool(desc: String) = mapOf("type" to "boolean", "description" to desc)
}