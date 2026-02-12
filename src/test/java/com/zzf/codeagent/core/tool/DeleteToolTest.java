package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.project.ProjectContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteToolTest {

    private final PendingChangesManager manager = PendingChangesManager.getInstance();

    @AfterEach
    void tearDown() {
        manager.clear();
    }

    @Test
    void shouldStageDeleteChangeAndKeepFileUntilApproved(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("src").resolve("Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Main {}", StandardCharsets.UTF_8);

        ProjectContext projectContext = new ProjectContext();
        projectContext.setDirectory(tempDir.toString());
        projectContext.setWorktree(tempDir.toString());

        ObjectMapper mapper = new ObjectMapper();
        DeleteTool tool = new DeleteTool(projectContext, mapper);

        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", file.toString());

        Tool.Context ctx = Tool.Context.builder()
                .sessionID("session-delete-test")
                .extra(Map.of("workspaceRoot", tempDir.toString()))
                .build();

        Tool.Result result = tool.execute(args, ctx).join();

        assertNotNull(result);
        assertNotNull(result.getMetadata());
        assertTrue(result.getMetadata().containsKey("pending_change"));
        assertTrue(result.getOutput().contains("waiting for user approval"));
        assertTrue(Files.exists(file));
        assertEquals(1, manager.getChanges().size());
    }

    @Test
    void shouldFailWhenTargetFileDoesNotExist(@TempDir Path tempDir) {
        ProjectContext projectContext = new ProjectContext();
        projectContext.setDirectory(tempDir.toString());
        projectContext.setWorktree(tempDir.toString());

        ObjectMapper mapper = new ObjectMapper();
        DeleteTool tool = new DeleteTool(projectContext, mapper);

        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", tempDir.resolve("missing.txt").toString());

        Tool.Context ctx = Tool.Context.builder()
                .sessionID("session-delete-test")
                .extra(Map.of("workspaceRoot", tempDir.toString()))
                .build();

        assertThrows(CompletionException.class, () -> tool.execute(args, ctx).join());
    }
}
