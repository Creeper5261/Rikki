package com.zzf.rikki.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.core.tool.EditTool;
import com.zzf.rikki.core.tool.PendingChangesManager;
import com.zzf.rikki.core.tool.Tool;
import com.zzf.rikki.core.tool.ToolRegistry;
import com.zzf.rikki.llm.LLMService;
import com.zzf.rikki.project.ProjectContext;
import com.zzf.rikki.provider.ModelInfo;
import com.zzf.rikki.session.model.MessageV2;
import com.zzf.rikki.snapshot.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.core.io.ResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SessionProcessorTest {

    @Mock
    private SessionService sessionService;
    @Mock
    private SessionStatus sessionStatus;
    @Mock
    private LLMService llmService;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private ContextCompactionService compactionService;
    @Mock
    private ProjectContext projectContext;
    @Mock
    private SnapshotService snapshotService;
    @Mock
    private ResourceLoader resourceLoader;

    private SessionProcessor processor;
    private MessageV2.Assistant assistantMessage;
    private ObjectMapper objectMapper = new ObjectMapper();
    private Map<String, Tool> tools = new HashMap<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        assistantMessage = MessageV2.Assistant.builder()
                .id("msg_1")
                .sessionID("session_1")
                .parts(new ArrayList<>())
                .tokens(new MessageV2.TokenUsage())
                .time(new MessageV2.MessageTime())
                .build();

        
        when(projectContext.getDirectory()).thenReturn(tempDir.toString());
        EditTool editTool = new EditTool(projectContext, objectMapper, snapshotService, resourceLoader);
        tools.put("edit", editTool);

        processor = new SessionProcessor(
                assistantMessage,
                "session_1",
                new ModelInfo(),
                sessionService,
                sessionStatus,
                llmService,
                toolRegistry,
                compactionService,
                objectMapper
        );
    }

    @Test
    void testCorrectToolCallParsingAndExecution() throws Exception {
        
        
        
        doAnswer(invocation -> {
            LLMService.StreamInput input = invocation.getArgument(0);
            LLMService.StreamCallback callback = invocation.getArgument(1);
            
            callback.onStart();
            callback.onTextStart("part_1", new HashMap<>());
            
            
            String chunk1 = "Sure, I will create the file.\n<ed";
            String chunk2 = "it filePath=\"test.txt\" \n";
            String chunk3 = "newString=\"Hello World\" oldString=\"\">\n";
            String chunk4 = "</edit>";
            
            callback.onTextDelta(chunk1, new HashMap<>());
            callback.onTextDelta(chunk2, new HashMap<>());
            callback.onTextDelta(chunk3, new HashMap<>());
            callback.onTextDelta(chunk4, new HashMap<>());
            
            callback.onTextEnd(new HashMap<>());
            callback.onComplete("stop");
            return CompletableFuture.completedFuture(null);
        }).when(llmService).stream(any(), any(), any());

        
        CompletableFuture<String> future = processor.process(LLMService.StreamInput.builder().build(), tools);
        String result = future.get(5, TimeUnit.SECONDS);

        assertEquals("stop", result);

        
        List<MessageV2.ToolPart> toolParts = new ArrayList<>();
        for (Object part : assistantMessage.getParts()) {
            if (part instanceof MessageV2.ToolPart) {
                toolParts.add((MessageV2.ToolPart) part);
            }
        }
        assertEquals(1, toolParts.size(), "Should have exactly one tool call");
        
        MessageV2.ToolPart toolPart = toolParts.get(0);
        assertEquals("edit", toolPart.getTool());

        int statusRetries = 20;
        while (statusRetries-- > 0 && !"completed".equals(toolPart.getState().getStatus())) {
            Thread.sleep(100);
        }
        assertEquals("completed", toolPart.getState().getStatus());
        
        
        Path filePath = tempDir.resolve("test.txt");
        
        
        
        
        
        int retries = 10;
        while (!Files.exists(filePath) && retries-- > 0) {
            Thread.sleep(100);
        }
        
        
        PendingChangesManager.PendingChange change = PendingChangesManager.getInstance().getPendingChange("test.txt", tempDir.toString(), "session_1").orElse(null);
        assertNotNull(change, "Pending change should exist");
        assertEquals("CREATE", change.type);
        assertEquals("Hello World", change.newContent);
        
        
        Map<String, Object> metadata = toolPart.getState().getMetadata();
        assertNotNull(metadata);
        assertTrue(metadata.containsKey("pending_change"));
    }

    @Test
    void testTextCodeBlockNotParsedAsTool() throws Exception {
        doAnswer(invocation -> {
            LLMService.StreamCallback callback = invocation.getArgument(1);
            callback.onStart();
            callback.onTextStart("part_1", new HashMap<>());
            
            String text = "Here is the code:\n```java\nSystem.out.println(\"Hello\");\n```";
            callback.onTextDelta(text, new HashMap<>());
            
            callback.onTextEnd(new HashMap<>());
            callback.onComplete("stop");
            return CompletableFuture.completedFuture(null);
        }).when(llmService).stream(any(), any(), any());

        processor.process(LLMService.StreamInput.builder().build(), tools).get(5, TimeUnit.SECONDS);

        
        boolean hasTool = assistantMessage.getParts().stream().anyMatch(p -> p instanceof MessageV2.ToolPart);
        assertFalse(hasTool, "Should not interpret code block as tool");
        
        
        MessageV2.TextPart textPart = (MessageV2.TextPart) assistantMessage.getParts().get(0);
        assertTrue(textPart.getText().contains("```java"));
    }
}
