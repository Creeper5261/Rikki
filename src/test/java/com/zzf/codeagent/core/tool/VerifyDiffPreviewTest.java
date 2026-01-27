package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.core.event.EventStream;
import com.zzf.codeagent.core.tools.FileSystemToolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class VerifyDiffPreviewTest {

    @Test
    public void testEditFileDryRunUpdatesPendingDiff(@TempDir Path tempDir) throws Exception {
        // 1. Setup
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);
        Path codeAgentDir = workspaceRoot.resolve(".codeagent");
        // Fixed: EventStore now uses ".codeagent" to match ChatPanel.

        Path file = workspaceRoot.resolve("test.txt");
        Files.writeString(file, "hello world", StandardCharsets.UTF_8);

        ObjectMapper mapper = new ObjectMapper();
        String sessionId = UUID.randomUUID().toString();
        EventStream eventStream = new EventStream(mapper, sessionId, workspaceRoot.toString());
        FileSystemToolService fs = new FileSystemToolService(workspaceRoot);

        ToolExecutionContext ctx = new ToolExecutionContext(
                "trace-1", workspaceRoot.toString(), mapper, fs, null, null, null, null, eventStream, null
        );

        ToolRegistry registry = new ToolRegistry();
        BuiltInToolHandlers.registerAll(registry);
        ToolHandler editTool = registry.get("EDIT_FILE", null);

        // 2. Execute Dry Run
        ObjectNode args = mapper.createObjectNode();
        args.put("path", "test.txt");
        args.put("old_str", "hello world");
        args.put("new_str", "hello universe");
        args.put("dry_run", true);

        ToolProtocol.ToolEnvelope env = new ToolProtocol.ToolEnvelope("EDIT_FILE", "1.0", args, "trace-1", "call-1");
        ToolProtocol.ToolResult result = editTool.execute(env, ctx);

        // 3. Verify Result
        assertTrue(result.isSuccess());
        JsonNode resData = result.getData();
        assertTrue(resData.path("preview").asBoolean());
        assertEquals("hello world", resData.path("oldContent").asText());
        assertEquals("hello universe", resData.path("newContent").asText());

        // 4. Verify Workspace State
        ObjectNode state = eventStream.getStore().getWorkspaceState();
        System.out.println("Workspace State: " + state.toPrettyString());
        
        assertTrue(state.has("pending_diff"), "State should have pending_diff");
        JsonNode pending = state.get("pending_diff");
        assertEquals("test.txt", pending.path("path").asText());
        assertEquals("hello world", pending.path("old_content").asText());
        assertEquals("hello universe", pending.path("new_content").asText());
    }

    @Test
    public void testMultiplePendingChanges(@TempDir Path tempDir) throws Exception {
        // 1. Setup
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);
        Path codeAgentDir = workspaceRoot.resolve(".codeagent");
        Files.createDirectories(codeAgentDir); // Ensure dir exists

        Path file1 = workspaceRoot.resolve("file1.txt");
        Files.writeString(file1, "content1", StandardCharsets.UTF_8);

        ObjectMapper mapper = new ObjectMapper();
        String sessionId = UUID.randomUUID().toString();
        EventStream eventStream = new EventStream(mapper, sessionId, workspaceRoot.toString());
        FileSystemToolService fs = new FileSystemToolService(workspaceRoot);

        ToolExecutionContext ctx = new ToolExecutionContext(
                "trace-3", workspaceRoot.toString(), mapper, fs, null, null, null, null, eventStream, null
        );

        ToolRegistry registry = new ToolRegistry();
        BuiltInToolHandlers.registerAll(registry);
        ToolHandler editTool = registry.get("EDIT_FILE", null);
        ToolHandler createTool = registry.get("CREATE_FILE", null);

        // Clear previous pending changes
        com.zzf.codeagent.core.tool.PendingChangesManager.getInstance().clear();

        // 2. Change 1: Edit file1
        ObjectNode args1 = mapper.createObjectNode();
        args1.put("path", "file1.txt");
        args1.put("old_str", "content1");
        args1.put("new_str", "content1-edited");
        args1.put("dry_run", true);
        editTool.execute(new ToolProtocol.ToolEnvelope("EDIT_FILE", "1.0", args1, "trace-3", "call-1"), ctx);

        // 3. Change 2: Create file2
        ObjectNode args2 = mapper.createObjectNode();
        args2.put("path", "file2.txt");
        args2.put("content", "content2");
        args2.put("dry_run", true);
        createTool.execute(new ToolProtocol.ToolEnvelope("CREATE_FILE", "1.0", args2, "trace-3", "call-2"), ctx);

        // 4. Verify Workspace State has pending_changes list
        ObjectNode state = eventStream.getStore().getWorkspaceState();
        assertTrue(state.has("pending_changes"), "State should have pending_changes");
        JsonNode pendingChanges = state.get("pending_changes");
        assertTrue(pendingChanges.isArray());
        assertEquals(2, pendingChanges.size());
        
        // Verify contents
        boolean foundFile1 = false;
        boolean foundFile2 = false;
        for (JsonNode change : pendingChanges) {
            String path = change.path("path").asText();
            if ("file1.txt".equals(path)) {
                foundFile1 = true;
                // EDIT_FILE usually maps to "edit_file" or "str_replace" depending on impl. 
                // Let's just check existence for now.
            } else if ("file2.txt".equals(path)) {
                foundFile2 = true;
            }
        }
        assertTrue(foundFile1, "file1.txt change should be present");
        assertTrue(foundFile2, "file2.txt change should be present");
    }

    @Test
    public void testCreateFileDryRunUpdatesPendingDiff(@TempDir Path tempDir) throws Exception {
        // 1. Setup
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);
        
        ObjectMapper mapper = new ObjectMapper();
        String sessionId = UUID.randomUUID().toString();
        EventStream eventStream = new EventStream(mapper, sessionId, workspaceRoot.toString());
        FileSystemToolService fs = new FileSystemToolService(workspaceRoot);
        
        ToolExecutionContext ctx = new ToolExecutionContext(
                "trace-2", workspaceRoot.toString(), mapper, fs, null, null, null, null, eventStream, null
        );
        
        ToolRegistry registry = new ToolRegistry();
        BuiltInToolHandlers.registerAll(registry);
        ToolHandler createTool = registry.get("CREATE_FILE", null);
        
        // 2. Execute Dry Run
        ObjectNode args = mapper.createObjectNode();
        args.put("path", "new.txt");
        args.put("content", "new file content");
        args.put("dry_run", true);
        
        ToolProtocol.ToolEnvelope env = new ToolProtocol.ToolEnvelope("CREATE_FILE", "1.0", args, "trace-2", "call-2");
        ToolProtocol.ToolResult result = createTool.execute(env, ctx);
        
        // 3. Verify Result
        assertTrue(result.isSuccess());
        JsonNode resData = result.getData();
        assertTrue(resData.path("preview").asBoolean());
        assertTrue(resData.path("oldContent").isNull());
        assertEquals("new file content", resData.path("newContent").asText());
        
        // 4. Verify Workspace State
        ObjectNode state = eventStream.getStore().getWorkspaceState();
        assertTrue(state.has("pending_diff"));
        JsonNode pending = state.get("pending_diff");
        assertEquals("new.txt", pending.path("path").asText());
        assertTrue(pending.path("old_content").isNull()); // Create should have null old content in state
        assertEquals("new file content", pending.path("new_content").asText());
        assertFalse(pending.path("prev_exist").asBoolean(), "prev_exist should be false for create");
    }

    @Test
    public void testDeleteFileDryRunUpdatesPendingDiff(@TempDir Path tempDir) throws Exception {
        // 1. Setup
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);
        Path file = workspaceRoot.resolve("delete_me.txt");
        Files.writeString(file, "goodbye world", StandardCharsets.UTF_8);
        
        ObjectMapper mapper = new ObjectMapper();
        String sessionId = UUID.randomUUID().toString();
        EventStream eventStream = new EventStream(mapper, sessionId, workspaceRoot.toString());
        FileSystemToolService fs = new FileSystemToolService(workspaceRoot);
        
        ToolExecutionContext ctx = new ToolExecutionContext(
                "trace-3", workspaceRoot.toString(), mapper, fs, null, null, null, null, eventStream, null
        );
        
        ToolRegistry registry = new ToolRegistry();
        BuiltInToolHandlers.registerAll(registry);
        ToolHandler deleteTool = registry.get("DELETE_FILE", null);
        
        // 2. Execute Dry Run
        ObjectNode args = mapper.createObjectNode();
        args.put("path", "delete_me.txt");
        args.put("dry_run", true);
        
        ToolProtocol.ToolEnvelope env = new ToolProtocol.ToolEnvelope("DELETE_FILE", "1.0", args, "trace-3", "call-3");
        ToolProtocol.ToolResult result = deleteTool.execute(env, ctx);
        
        // 3. Verify Result
        assertTrue(result.isSuccess());
        JsonNode resData = result.getData();
        assertTrue(resData.path("preview").asBoolean());
        assertEquals("goodbye world", resData.path("oldContent").asText());
        assertTrue(resData.path("newContent").isNull());
        
        // 4. Verify Workspace State
        ObjectNode state = eventStream.getStore().getWorkspaceState();
        assertTrue(state.has("pending_diff"));
        JsonNode pending = state.get("pending_diff");
        assertEquals("delete_me.txt", pending.path("path").asText());
        assertEquals("goodbye world", pending.path("old_content").asText());
        assertTrue(pending.path("new_content").isNull()); // Delete should have null new content in state
    }

    @Test
    public void testApplyPendingDiffToolMultipleFiles(@TempDir Path tempDir) throws Exception {
        // 1. Setup
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);
        Path codeAgentDir = workspaceRoot.resolve(".codeagent");
        Files.createDirectories(codeAgentDir);

        Path file1 = workspaceRoot.resolve("file1.txt");
        Files.writeString(file1, "content1", StandardCharsets.UTF_8);

        ObjectMapper mapper = new ObjectMapper();
        String sessionId = UUID.randomUUID().toString();
        EventStream eventStream = new EventStream(mapper, sessionId, workspaceRoot.toString());
        FileSystemToolService fs = new FileSystemToolService(workspaceRoot);

        ToolExecutionContext ctx = new ToolExecutionContext(
                "trace-4", workspaceRoot.toString(), mapper, fs, null, null, null, null, eventStream, null
        );

        ToolRegistry registry = new ToolRegistry();
        BuiltInToolHandlers.registerAll(registry);
        ToolHandler editTool = registry.get("EDIT_FILE", null);
        ToolHandler createTool = registry.get("CREATE_FILE", null);
        ToolHandler applyTool = registry.get("APPLY_PENDING_DIFF", null);

        // Clear previous pending changes
        com.zzf.codeagent.core.tool.PendingChangesManager.getInstance().clear();

        // 2. Create Pending Changes
        // Change 1: Edit file1
        ObjectNode args1 = mapper.createObjectNode();
        args1.put("path", "file1.txt");
        args1.put("old_str", "content1");
        args1.put("new_str", "content1-edited");
        args1.put("dry_run", true);
        editTool.execute(new ToolProtocol.ToolEnvelope("EDIT_FILE", "1.0", args1, "trace-4", "call-1"), ctx);

        // Change 2: Create file2
        ObjectNode args2 = mapper.createObjectNode();
        args2.put("path", "file2.txt");
        args2.put("content", "content2");
        args2.put("dry_run", true);
        createTool.execute(new ToolProtocol.ToolEnvelope("CREATE_FILE", "1.0", args2, "trace-4", "call-2"), ctx);

        assertEquals(2, com.zzf.codeagent.core.tool.PendingChangesManager.getInstance().getChanges().size());

        // 3. Apply Change 1 only
        ObjectNode applyArgs1 = mapper.createObjectNode();
        applyArgs1.put("path", "file1.txt");
        ToolProtocol.ToolResult res1 = applyTool.execute(new ToolProtocol.ToolEnvelope("APPLY_PENDING_DIFF", "1.0", applyArgs1, "trace-4", "call-3"), ctx);

        assertTrue(res1.isSuccess());
        assertEquals(1, com.zzf.codeagent.core.tool.PendingChangesManager.getInstance().getChanges().size());
        assertEquals("file2.txt", com.zzf.codeagent.core.tool.PendingChangesManager.getInstance().getChanges().get(0).path);
        
        // Verify file1 is actually changed on disk
        assertEquals("content1-edited", Files.readString(file1));
        // Verify file2 is NOT yet created
        assertFalse(Files.exists(workspaceRoot.resolve("file2.txt")));

        // 4. Apply Remaining (All)
        ObjectNode applyArgs2 = mapper.createObjectNode(); // Empty args = Apply All
        ToolProtocol.ToolResult res2 = applyTool.execute(new ToolProtocol.ToolEnvelope("APPLY_PENDING_DIFF", "1.0", applyArgs2, "trace-4", "call-4"), ctx);
        
        assertTrue(res2.isSuccess());
        assertTrue(com.zzf.codeagent.core.tool.PendingChangesManager.getInstance().getChanges().isEmpty());
        
        // Verify file2 is created
        assertTrue(Files.exists(workspaceRoot.resolve("file2.txt")));
        assertEquals("content2", Files.readString(workspaceRoot.resolve("file2.txt")));
    }
}
