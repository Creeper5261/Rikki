package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.project.ProjectContext;
import com.zzf.codeagent.shell.ShellService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashToolRiskTest {

    private final PendingCommandsManager manager = PendingCommandsManager.getInstance();

    @AfterEach
    void tearDown() {
        manager.clear();
    }

    @Test
    void shouldStageHighRiskCommandForApproval(@TempDir Path tempDir) {
        assertPendingApprovalFor(tempDir, "Remove all files in src", "rm -rf src/*");
    }

    @Test
    void shouldStagePlainRmForApproval(@TempDir Path tempDir) {
        assertPendingApprovalFor(tempDir, "Delete one file", "rm src/Main.java");
    }

    @Test
    void shouldStagePowershellRecursiveDeleteForApproval(@TempDir Path tempDir) {
        assertPendingApprovalFor(tempDir, "Delete tree by powershell", "powershell -Command \"Remove-Item -Recurse -Force src\"");
    }

    @Test
    void shouldStageDestructiveGitCleanupForApproval(@TempDir Path tempDir) {
        assertPendingApprovalFor(tempDir, "Force cleanup git workspace", "git clean -fdx");
    }

    @Test
    void shouldStageMoveCommandForApproval(@TempDir Path tempDir) {
        assertPendingApprovalFor(tempDir, "Rename file", "mv src/Main.java src/App.java");
    }

    private void assertPendingApprovalFor(Path tempDir, String description, String command) {
        ProjectContext context = new ProjectContext();
        context.setDirectory(tempDir.toString());
        context.setWorktree(tempDir.toString());

        BashTool tool = new BashTool(new ShellService(), context, new DefaultResourceLoader());
        ObjectMapper mapper = new ObjectMapper();

        ObjectNode args = mapper.createObjectNode();
        args.put("description", description);
        args.put("command", command);

        Tool.Context toolContext = Tool.Context.builder()
                .sessionID("session-risk")
                .extra(Map.of("workspaceRoot", tempDir.toString()))
                .build();

        Tool.Result result = tool.execute(args, toolContext).join();
        assertNotNull(result);
        assertNotNull(result.getMetadata());
        assertTrue(result.getMetadata().containsKey("pending_command"));
        assertTrue(result.getOutput().toLowerCase().contains("requires user approval"));

        Object pending = result.getMetadata().get("pending_command");
        assertTrue(pending instanceof PendingCommandsManager.PendingCommand);
        String pendingId = ((PendingCommandsManager.PendingCommand) pending).id;
        assertTrue(manager.get(pendingId).isPresent());
    }
}
