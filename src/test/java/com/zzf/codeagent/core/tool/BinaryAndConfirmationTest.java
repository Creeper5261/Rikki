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

public class BinaryAndConfirmationTest {

    @Test
    public void testBinaryFileProtection(@TempDir Path tempDir) throws Exception {
        // 1. Setup binary file
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);
        Path binaryFile = workspaceRoot.resolve("binary.txt");
        // Write content with null byte
        byte[] bytes = new byte[]{'H', 'e', 'l', 'l', 'o', 0, 'W', 'o', 'r', 'l', 'd'};
        Files.write(binaryFile, bytes);

        FileSystemToolService fs = new FileSystemToolService(workspaceRoot);

        // 2. Test Read
        FileSystemToolService.ReadFileResult read = fs.readFile("binary.txt", 1, 10, 1000);
        assertFalse(read.truncated); // boolean in ReadFileResult constructor is 'truncated'
        // ReadFileResult(String path, Integer startLine, Integer endLine, boolean truncated, String content, String error)
        // Actually, looking at FileSystemToolService.java:
        // return new ReadFileResult(path, start, end, false, "", "file_is_binary");
        assertEquals("file_is_binary", read.error);

        // 3. Test Edit
        FileSystemToolService.EditFileResult edit = fs.editFile("binary.txt", "Hello", "Hi");
        assertEquals("file_is_binary", edit.error);

        // 4. Test Overwrite
        FileSystemToolService.EditFileResult overwrite = fs.overwriteFile("binary.txt", "New Content", false);
        assertEquals("file_is_binary", overwrite.error);

        // 5. Test Delete
        FileSystemToolService.EditFileResult delete = fs.deletePath("binary.txt", false);
        assertEquals("file_is_binary", delete.error);
    }

    @Test
    public void testConfirmationWorkflow(@TempDir Path tempDir) throws Exception {
        // 1. Setup
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);
        Path file = workspaceRoot.resolve("test.txt");
        Files.writeString(file, "original", StandardCharsets.UTF_8);

        ObjectMapper mapper = new ObjectMapper();
        String sessionId = UUID.randomUUID().toString();
        EventStream eventStream = new EventStream(mapper, sessionId, workspaceRoot.toString());
        FileSystemToolService fs = new FileSystemToolService(workspaceRoot);

        ToolExecutionContext ctx = new ToolExecutionContext(
                "trace-1", workspaceRoot.toString(), workspaceRoot.toString(), mapper, fs, null, null, null, null, eventStream, null, null
        );

        ToolRegistry registry = new ToolRegistry();
        BuiltInToolHandlers.registerAll(registry);
        ToolHandler editTool = registry.get("EDIT_FILE", null);
        ToolHandler applyTool = registry.get("APPLY_PENDING_DIFF", null);

        // 2. Dry Run Edit
        ObjectNode args = mapper.createObjectNode();
        args.put("path", "test.txt");
        args.put("old_str", "original");
        args.put("new_str", "modified");
        args.put("dry_run", true);

        ToolProtocol.ToolEnvelope env = new ToolProtocol.ToolEnvelope("EDIT_FILE", "1.0", args, "trace-1", "call-1");
        ToolProtocol.ToolResult result = editTool.execute(env, ctx);
        assertTrue(result.isSuccess());

        // Verify Pending Diff
        ObjectNode state = eventStream.getStore().getWorkspaceState();
        assertTrue(state.has("pending_diff"));
        assertFalse(state.get("pending_diff").isNull());
        assertEquals("test.txt", state.get("pending_diff").get("path").asText());

        // 3. Confirm (Apply)
        ObjectNode applyArgs = mapper.createObjectNode();
        applyArgs.put("path", "test.txt");
        applyArgs.put("reject", false);
        
        ToolProtocol.ToolEnvelope applyEnv = new ToolProtocol.ToolEnvelope("APPLY_PENDING_DIFF", "1.0", applyArgs, "trace-1", "call-2");
        ToolProtocol.ToolResult applyResult = applyTool.execute(applyEnv, ctx);
        
        assertTrue(applyResult.isSuccess(), "Apply should succeed: " + applyResult.getError());
        assertEquals("modified", Files.readString(file));
        
        // Verify Pending Diff Cleared
        state = eventStream.getStore().getWorkspaceState();
        assertTrue(state.has("pending_diff"));
        assertTrue(state.get("pending_diff").isNull());

        // 4. Dry Run Edit Again
        args.put("old_str", "modified");
        args.put("new_str", "rejected_change");
        result = editTool.execute(env, ctx);
        assertTrue(result.isSuccess());
        
        state = eventStream.getStore().getWorkspaceState();
        assertFalse(state.get("pending_diff").isNull());
        assertEquals("rejected_change", state.get("pending_diff").get("new_content").asText());

        // 5. Reject
        applyArgs.put("reject", true);
        applyResult = applyTool.execute(applyEnv, ctx);
        
        assertTrue(applyResult.isSuccess());
        assertEquals("modified", Files.readString(file)); // Content should NOT change
        
        state = eventStream.getStore().getWorkspaceState();
        assertTrue(state.get("pending_diff").isNull());
    }
}
