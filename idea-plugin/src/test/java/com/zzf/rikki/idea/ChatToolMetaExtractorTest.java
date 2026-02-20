package com.zzf.rikki.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.core.tool.PendingChangesManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatToolMetaExtractorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void extractPendingCommand_should_mark_strict_for_destructive_reason() throws Exception {
        ChatToolMetaExtractor extractor = new ChatToolMetaExtractor();
        JsonNode metaNode = MAPPER.readTree("""
                {
                  "pending_command": {
                    "id": "pc_1",
                    "command": "rm -rf src",
                    "description": "remove files",
                    "reasons": ["destructive operation"]
                  }
                }
                """);

        PendingCommandInfo info = extractor.extractPendingCommand(metaNode, "D:/ws", "s1");

        assertNotNull(info);
        assertEquals("pc_1", info.id);
        assertEquals("rm -rf src", info.command);
        assertTrue(info.strictApproval);
        assertEquals("rm", info.commandFamily);
    }

    @Test
    void synthesizePendingChangeFromArgs_should_create_edit_change_for_write_tool() throws Exception {
        ChatToolMetaExtractor extractor = new ChatToolMetaExtractor();
        JsonNode argsNode = MAPPER.readTree("""
                {
                  "filePath": "src/Main.java",
                  "content": "class Main {}"
                }
                """);

        PendingChangesManager.PendingChange change = extractor.synthesizePendingChangeFromArgs(
                "write",
                argsNode,
                "D:/ws",
                "s1",
                name -> "write".equalsIgnoreCase(name),
                name -> false
        );

        assertNotNull(change);
        assertEquals("CREATE", change.type);
        assertEquals("src/Main.java", change.path);
        assertEquals("D:/ws", change.workspaceRoot);
        assertEquals("s1", change.sessionId);
    }

    @Test
    void extractMetaOutput_should_prefer_output_then_stdout_then_result() throws Exception {
        ChatToolMetaExtractor extractor = new ChatToolMetaExtractor();
        JsonNode metaNode = MAPPER.readTree("""
                {
                  "output": "",
                  "stdout": "std out value",
                  "result": {"ok": true}
                }
                """);

        String out = extractor.extractMetaOutput(metaNode, JsonNode::toPrettyString);
        assertEquals("std out value", out);
    }
}
