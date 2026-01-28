package com.zzf.codeagent.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;
import com.zzf.codeagent.core.event.EventStream;
import com.zzf.codeagent.core.tool.PendingChangesManager;
import com.zzf.codeagent.core.tool.ToolRegistry;
import com.zzf.codeagent.core.tools.FileSystemToolService;
import com.zzf.codeagent.idea.utils.MarkdownUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

final class ChatPanel {
    private static final Logger logger = Logger.getInstance(ChatPanel.class);
    private static final Duration CHAT_TIMEOUT = Duration.ofSeconds(longOrDefault(System.getProperty("codeagent.chatTimeoutSeconds", ""), 600L));
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(longOrDefault(System.getProperty("codeagent.connectTimeoutSeconds", ""), 10L));
    private static final int HISTORY_SEND_MAX_LINES = (int) longOrDefault(System.getProperty("codeagent.historyMaxLines", ""), 60L);
    private static final ExecutorService IDE_CONTEXT_EXECUTOR = Executors.newCachedThreadPool(r -> new Thread(r, "rikki-ide-context"));
    
    private final JSplitPane splitPane;
    private final JPanel leftPanel; // Session List
    private final JPanel rightPanel; // Chat Area
    private final JPanel conversationList;
    private final JBScrollPane scrollPane;
    private final JBTextArea input;
    private final JButton send;
    private final JLabel status;
    private final HttpClient http;
    private final Project project;
    private final String workspaceRoot;
    private final String workspaceName;
    private String currentSessionId;
    private final ChatHistoryService history;
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final ObjectMapper mapper = new ObjectMapper();
    private FileSystemToolService fsService;
    private EventStream eventStream;
    private final DiffService diffService;

    ChatPanel(Project project) {
        this.project = project;
        this.diffService = new DiffService(project);
        
        // --- Left Panel (Sessions) ---
        this.leftPanel = new JPanel(new BorderLayout());
        this.leftPanel.setBorder(JBUI.Borders.empty(5));
        this.leftPanel.setMinimumSize(new Dimension(150, 0));
        
        // --- Right Panel (Chat) ---
        this.rightPanel = new JPanel(new BorderLayout(8, 8));
        
        // Conversation Area
        this.conversationList = new JPanel();
        this.conversationList.setLayout(new BoxLayout(this.conversationList, BoxLayout.Y_AXIS));
        this.conversationList.setBorder(JBUI.Borders.empty(10));
        
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(conversationList, BorderLayout.NORTH);
        
        this.scrollPane = new JBScrollPane(wrapper);
        this.scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        
        this.input = new JBTextArea(4, 10);
        this.send = new JButton("发送");
        this.status = new JLabel("就绪");
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        
        this.workspaceRoot = resolveWorkspaceRoot(project);
        if (this.workspaceRoot != null) {
            this.fsService = new FileSystemToolService(Path.of(this.workspaceRoot));
            this.fsService.setFileChangeListener(path -> {
                if (path == null) return;
                LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toAbsolutePath().toString());
            });
            this.eventStream = new EventStream(mapper, "ui-session", this.workspaceRoot);
        }
        this.workspaceName = resolveWorkspaceName(workspaceRoot);
        this.history = project.getService(ChatHistoryService.class);
        
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        
        // Header Panel: Title + Session Toggle + Pending Changes
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        
        JPanel titleBar = new JPanel(new BorderLayout());
        JButton toggleSessions = new JButton("☰ History");
        toggleSessions.setBorderPainted(false);
        toggleSessions.setContentAreaFilled(false);
        toggleSessions.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        
        titleBar.add(toggleSessions, BorderLayout.WEST);
        titleBar.add(new JLabel(" Chat with Agent", SwingConstants.CENTER), BorderLayout.CENTER);
        
        headerPanel.add(titleBar);
        headerPanel.add(Box.createVerticalStrut(5));
        // No pending changes panel in direct-apply mode.
        
        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.add(new JBScrollPane(input), BorderLayout.CENTER);
        bottom.add(send, BorderLayout.EAST);
        bottom.add(status, BorderLayout.SOUTH);

        rightPanel.add(headerPanel, BorderLayout.NORTH);
        rightPanel.add(scrollPane, BorderLayout.CENTER);
        rightPanel.add(bottom, BorderLayout.SOUTH);
        
        // Split Pane
        this.splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        this.splitPane.setDividerSize(5);
        this.splitPane.setDividerLocation(200);
        this.leftPanel.setVisible(false); // Hidden by default

        toggleSessions.addActionListener(e -> {
            boolean visible = leftPanel.isVisible();
            leftPanel.setVisible(!visible);
            if (!visible) refreshSessionList();
            splitPane.setDividerLocation(visible ? 0 : 200);
        });

        send.addActionListener(e -> onSend());
        
        initSessionList();
        rebuildConversationFromHistory();
    }

    JComponent getComponent() {
        return splitPane;
    }

    // --- Session Management ---
    
    private void initSessionList() {
        refreshSessionList();
    }
    
    private void refreshSessionList() {
        leftPanel.removeAll();
        
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        
        JButton newChatBtn = new JButton("+ New Chat");
        newChatBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        newChatBtn.addActionListener(e -> {
            history.createSession("New Chat");
            refreshSessionList();
            rebuildConversationFromHistory();
        });
        
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(JBUI.Borders.empty(5));
        top.add(newChatBtn, BorderLayout.CENTER);
        leftPanel.add(top, BorderLayout.NORTH);
        
        List<ChatHistoryService.ChatSession> sessions = history.getSessions();
        ChatHistoryService.ChatSession current = history.getCurrentSession();
        
        for (ChatHistoryService.ChatSession session : sessions) {
            JPanel item = new JPanel(new BorderLayout());
            item.setBorder(JBUI.Borders.empty(5));
            if (current != null && current.id.equals(session.id)) {
                item.setBackground(new Color(220, 230, 240));
            }
            
            JLabel title = new JLabel(session.title);
            title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            title.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    history.setCurrentSession(session.id);
                    refreshSessionList();
                    rebuildConversationFromHistory();
                }
            });
            
            JButton delBtn = new JButton("×");
            delBtn.setBorderPainted(false);
            delBtn.setContentAreaFilled(false);
            delBtn.setPreferredSize(new Dimension(20, 20));
            delBtn.addActionListener(e -> {
                history.deleteSession(session.id);
                refreshSessionList();
                rebuildConversationFromHistory();
            });
            
            item.add(title, BorderLayout.CENTER);
            item.add(delBtn, BorderLayout.EAST);
            item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            
            list.add(item);
        }
        
        leftPanel.add(new JBScrollPane(list), BorderLayout.CENTER);
        leftPanel.revalidate();
        leftPanel.repaint();
    }

    // --- Chat Logic ---

    private void onSend() {
        String text = input.getText();
        if (text == null || text.trim().isEmpty()) return;
        input.setText("");
        sendMessage(text);
    }

    private void sendMessage(String text) {
        if (text == null || text.trim().isEmpty()) return;
        
        // 1. Add User Message
        addMessage(true, text, null, false);
        history.appendLine("你: " + text.trim());
        refreshSessionList(); // Update title if new
        
        // 2. Add Agent Placeholder
        AgentMessageUI ui = addMessage(false, null, null, true);
        
        // 3. Call Agent Stream
        callAgentStream(text.trim(), ui);
    }
    
    private void callAgentStream(String userMessage, AgentMessageUI ui) {
        setBusy(true, "Agent is thinking...");
        
        new Thread(() -> {
            try {
                String ideContext = buildIdeContextWithTimeout(5000);
                
                ObjectNode json = mapper.createObjectNode();
                json.put("goal", userMessage);
                json.put("workspaceRoot", workspaceRoot);
                json.put("workspaceName", workspaceName);
                json.put("ideContextContent", ideContext);
                
                ChatHistoryService.ChatSession session = history.getCurrentSession();
                if (session != null && session.settings != null) {
                    ObjectNode settingsNode = json.putObject("settings");
                    settingsNode.put("model", session.settings.model);
                    settingsNode.put("language", session.settings.language);
                    settingsNode.put("temperature", session.settings.temperature);
                }
                
                ArrayNode historyNode = json.putArray("history");
                if (history != null) {
                    List<String> lines = history.getLines();
                    if (lines != null) {
                        int start = Math.max(0, lines.size() - HISTORY_SEND_MAX_LINES);
                        for (int i = start; i < lines.size(); i++) {
                            historyNode.add(lines.get(i));
                        }
                    }
                }
                
                String payload = mapper.writeValueAsString(json);
                String endpointUrl = System.getProperty("codeagent.endpoint", "http://localhost:8080/api/agent/chat/stream");
                
                int timeoutMinutes = Integer.getInteger("codeagent.stream.timeout.minutes", 15);
                HttpRequest req = HttpRequest.newBuilder(URI.create(endpointUrl))
                        .timeout(Duration.ofMinutes(timeoutMinutes))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();
                
                final String[] currentEvent = {null};
                
                http.send(req, HttpResponse.BodyHandlers.ofLines()).body().forEach(line -> {
                    if (line.startsWith("event:")) {
                        currentEvent[0] = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        handleSseEvent(currentEvent[0], data, ui);
                        currentEvent[0] = null;
                    }
                });
                
            } catch (Exception e) {
                logger.warn("stream_error", e);
                SwingUtilities.invokeLater(() -> {
                    if (ui.answerPane != null) ui.answerPane.setText("Error: " + e.getMessage());
                });
            } finally {
                setBusy(false, "Ready");
            }
        }).start();
    }
    
    private void handleSseEvent(String event, String data, AgentMessageUI ui) {
        SwingUtilities.invokeLater(() -> {
            try {
                if ("agent_step".equals(event)) {
                    JsonNode node = mapper.readTree(data);
                    String stepType = node.path("stepType").asText();
                    if (stepType == null || stepType.isEmpty()) {
                        stepType = node.path("stage").asText();
                    }
                    if ("thought".equals(stepType)) {
                         String thought = node.path("text").asText();
                         if (ui.thoughtPanel != null) {
                             ui.thoughtPanel.appendContent(thought + "\n");
                         }
                    }
                } else if ("finish".equals(event)) {
                    AgentResponse resp = parseAgentResponse(data);
                    
                    String ans = resp.answer != null ? resp.answer : "No answer received";
                    if (ui.answerPane != null) {
                        ui.answerPane.setText(MarkdownUtils.renderToHtml(ans));
                    }
                    
                    if (history != null) {
                        history.appendLine("Agent: " + ans);
                    }
                    
                    if (resp.changes != null && !resp.changes.isEmpty()) {
                         for (PendingChangesManager.PendingChange change : resp.changes) {
                             diffService.applyChange(change);
                         }
                         JPanel changesPanel = new JPanel();
                         changesPanel.setLayout(new BoxLayout(changesPanel, BoxLayout.Y_AXIS));
                         changesPanel.setBorder(BorderFactory.createTitledBorder("Modified Files"));
                         
                         for (PendingChangesManager.PendingChange change : resp.changes) {
                             JPanel item = new JPanel(new BorderLayout(5, 0));
                             JLabel name = new JLabel(change.path);
                             JButton viewBtn = new JButton("Show Diff");
                             viewBtn.addActionListener(e -> diffService.showDiffExplicit(change.path, change.oldContent, change.newContent));
                             item.add(name, BorderLayout.CENTER);
                             item.add(viewBtn, BorderLayout.EAST);
                             item.setBorder(JBUI.Borders.empty(2));
                             changesPanel.add(item);
                         }
                         
                         if (ui.answerPane != null) {
                             Container parent = ui.answerPane.getParent();
                             if (parent != null) {
                                 parent.add(Box.createVerticalStrut(10));
                                 parent.add(changesPanel);
                                 parent.revalidate();
                                 parent.repaint();
                             }
                         }
                    }
                    scrollToBottom();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
    
    private void addSystemMessage(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(5));
        JLabel label = new JLabel("<html><i>" + text + "</i></html>");
        label.setForeground(Color.GRAY);
        panel.add(label, BorderLayout.CENTER);
        conversationList.add(panel);
        conversationList.revalidate();
        conversationList.repaint();
        scrollToBottom();
    }
    
    private AgentMessageUI addMessage(boolean isUser, String text, AgentResponse response, boolean animate) {
        AgentMessageUI ui = new AgentMessageUI();
        JPanel messagePanel = new JPanel();
        messagePanel.setLayout(new BoxLayout(messagePanel, BoxLayout.Y_AXIS));
        messagePanel.setBorder(JBUI.Borders.empty(5, 0));
        
        if (isUser) {
            JPanel bubble = new JPanel(new BorderLayout());
            bubble.setBackground(new Color(230, 240, 255)); // Light blue
            bubble.setBorder(JBUI.Borders.empty(8));
            
            JTextArea textArea = new JTextArea("You: " + text);
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            textArea.setEditable(false);
            textArea.setOpaque(false);
            
            bubble.add(textArea, BorderLayout.CENTER);
            messagePanel.add(bubble);
        } else {
            // Agent Message
            // 1. Thinking Process
            if (animate || (response != null && response.thought != null && !response.thought.isEmpty())) {
                String initialThought = (response != null && response.thought != null) ? response.thought : "";
                CollapsiblePanel thoughtPanel = new CollapsiblePanel("Thinking Process", initialThought, animate);
                messagePanel.add(thoughtPanel);
                messagePanel.add(Box.createVerticalStrut(5));
                ui.thoughtPanel = thoughtPanel;
            }
            
            // 2. Main Answer
            String answerText = (response != null) ? response.answer : text;
            JEditorPane content = new JEditorPane();
            content.setEditable(false);
            content.setContentType("text/html");
            content.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            content.setOpaque(false);
            
            if (animate && answerText != null && !answerText.isEmpty()) {
                typewriterEffect(content, answerText);
            } else {
                content.setText(MarkdownUtils.renderToHtml(answerText != null ? answerText : ""));
            }
            
            messagePanel.add(content);
            ui.answerPane = content;
            
            // 3. Embedded File Changes
            if (response != null && response.changes != null && !response.changes.isEmpty()) {
                for (PendingChangesManager.PendingChange change : response.changes) {
                    diffService.applyChange(change);
                }
                messagePanel.add(Box.createVerticalStrut(10));
                JPanel changesPanel = new JPanel();
                changesPanel.setLayout(new BoxLayout(changesPanel, BoxLayout.Y_AXIS));
                changesPanel.setBorder(BorderFactory.createTitledBorder("Modified Files"));
                
                for (PendingChangesManager.PendingChange change : response.changes) {
                    JPanel item = new JPanel(new BorderLayout(5, 0));
                    JLabel name = new JLabel(change.path);
                    JButton viewBtn = new JButton("Show Diff");
                    viewBtn.addActionListener(e -> diffService.showDiffExplicit(change.path, change.oldContent, change.newContent));
                    
                    item.add(name, BorderLayout.CENTER);
                    item.add(viewBtn, BorderLayout.EAST);
                    item.setBorder(JBUI.Borders.empty(2));
                    changesPanel.add(item);
                }
                messagePanel.add(changesPanel);
                ui.changesPanel = changesPanel;
            }
        }
        
        conversationList.add(messagePanel);
        conversationList.add(new JSeparator());
        
        conversationList.revalidate();
        conversationList.repaint();
        scrollToBottom();
        return ui;
    }
    
    private void typewriterEffect(JEditorPane pane, String fullMarkdown) {
        // Simple streaming: append raw chars? No, markdown needs full render.
        // We simulate by revealing chunks.
        // Or strictly: 
        // 1. Parse markdown to plain text for speed? No, formatting needed.
        // 2. Render partial markdown.
        
        Timer timer = new Timer(10, null); // 10ms per char
        final int[] index = {0};
        final int length = fullMarkdown.length();
        final int chunk = 2; // chars per tick
        
        timer.addActionListener(e -> {
            if (project.isDisposed()) {
                timer.stop();
                return;
            }
            
            index[0] += chunk;
            if (index[0] >= length) {
                index[0] = length;
                timer.stop();
            }
            
            String partial = fullMarkdown.substring(0, index[0]);
            // Ensure we don't break markdown syntax too badly (e.g. unclosed backticks)
            // Ideally we should fix unclosed tags, but MarkdownUtils might handle it gracefully?
            // MarkdownUtils manual parsing might break on unclosed code blocks.
            // Let's rely on MarkdownUtils robustness or just ensure we close them.
            // For now, raw substring.
            
            pane.setText(MarkdownUtils.renderToHtml(partial));
            scrollToBottom();
        });
        timer.start();
    }
    
    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) return;
            JScrollBar vertical = scrollPane.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });
    }

    // --- Settings Dialog ---
    private class SettingsDialog extends DialogWrapper {
        private final ChatHistoryService.SessionSettings settings;
        private final JTextField modelField;
        private final JTextField languageField;
        private final JTextField temperatureField;

        SettingsDialog(ChatHistoryService.SessionSettings settings) {
            super(project);
            this.settings = settings;
            this.modelField = new JTextField(settings.model);
            this.languageField = new JTextField(settings.language);
            this.temperatureField = new JTextField(String.valueOf(settings.temperature));
            
            setTitle("Session Settings");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridLayout(3, 2, 5, 5));
            panel.add(new JLabel("Model:"));
            panel.add(modelField);
            panel.add(new JLabel("Language:"));
            panel.add(languageField);
            panel.add(new JLabel("Temperature:"));
            panel.add(temperatureField);
            return panel;
        }

        @Override
        protected void doOKAction() {
            settings.model = modelField.getText();
            settings.language = languageField.getText();
            try {
                settings.temperature = Double.parseDouble(temperatureField.getText());
            } catch (NumberFormatException e) {
                // Ignore invalid number
            }
            super.doOKAction();
        }
    }
    
    private static class AgentMessageUI {
        CollapsiblePanel thoughtPanel;
        JEditorPane answerPane;
        JPanel changesPanel;
    }

    private static class AgentResponse {
        String thought;
        String answer;
        List<PendingChangesManager.PendingChange> changes = new ArrayList<>();
        String traceId;
    }

    private AgentResponse callAgent(String goal, boolean includeIde) throws Exception {
        String ideContextPath = "";
        if (includeIde) {
            String ideContext = buildIdeContextWithTimeout(2000);
        }
        
        ObjectNode json = mapper.createObjectNode();
        json.put("goal", goal);
        json.put("workspaceRoot", workspaceRoot);
        json.put("workspaceName", workspaceName);
        json.put("ideContextPath", ideContextPath);
        
        // Add Session Settings
        ChatHistoryService.ChatSession session = history.getCurrentSession();
        if (session != null && session.settings != null) {
            ObjectNode settingsNode = json.putObject("settings");
            settingsNode.put("model", session.settings.model);
            settingsNode.put("language", session.settings.language);
            settingsNode.put("temperature", session.settings.temperature);
        }
        
        List<String> lines = history.getLines();
        ArrayNode historyNode = json.putArray("history");
        if (lines != null) {
            int start = Math.max(0, lines.size() - HISTORY_SEND_MAX_LINES);
            for (int i = start; i < lines.size(); i++) {
                historyNode.add(lines.get(i));
            }
        }

        String payload = mapper.writeValueAsString(json);
        String endpointUrl = System.getProperty("codeagent.endpoint", "http://localhost:8080/api/agent/chat");
        
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpointUrl))
                .timeout(CHAT_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = resp.body();
        
        return parseAgentResponse(body);
    }

    private AgentResponse parseAgentResponse(String body) {
        AgentResponse response = new AgentResponse();
        
        try {
            JsonNode rootNode = mapper.readTree(body);

            if (rootNode.has("traceId")) {
                response.traceId = rootNode.get("traceId").asText();
                if (response.traceId != null && !response.traceId.isEmpty()) {
                    currentSessionId = response.traceId;
                }
            }
            
            if (rootNode.has("thought")) {
                response.thought = rootNode.get("thought").asText();
            } else if (rootNode.has("answer")) {
                String rawAnswer = rootNode.get("answer").asText();
                 if (rawAnswer.trim().startsWith("{")) {
                    try {
                        JsonNode inner = mapper.readTree(rawAnswer);
                        if (inner.has("thought")) {
                            response.thought = inner.get("thought").asText();
                        }
                    } catch (Exception e) {}
                 }
            }

            if (rootNode.has("meta")) {
                JsonNode meta = rootNode.get("meta");
                JsonNode changes = meta.has("appliedChanges") ? meta.get("appliedChanges") : meta.get("pendingChanges");
                if (changes != null && changes.isArray()) {
                    for (JsonNode changeNode : changes) {
                        String path = changeNode.path("filePath").asText(changeNode.path("path").asText());
                        String type = changeNode.path("type").asText("EDIT");
                        String oldContent = changeNode.path("oldContent").asText("");
                        String newContent = changeNode.path("newContent").asText("");
                        String preview = changeNode.path("preview").asText("");
                        String wsRoot = changeNode.path("workspaceRoot").asText(changeNode.path("workspace_root").asText(null));
                        String sessionId = changeNode.path("sessionId").asText(changeNode.path("session_id").asText(response.traceId));
                        String id = changeNode.path("id").asText(java.util.UUID.randomUUID().toString());
                        long ts = changeNode.path("timestamp").asLong(System.currentTimeMillis());

                        PendingChangesManager.PendingChange pc = new PendingChangesManager.PendingChange(id, path, type, oldContent, newContent, preview, ts, wsRoot, sessionId);
                        response.changes.add(pc);
                    }
                }
            }
            
            String answer = null;
            if (rootNode.has("answer")) {
                String rawAnswer = rootNode.get("answer").asText();
                if (rawAnswer != null && rawAnswer.trim().startsWith("{")) {
                    try {
                        JsonNode inner = mapper.readTree(rawAnswer);
                        if (inner.has("finalAnswer")) {
                            answer = inner.get("finalAnswer").asText();
                        } else if (inner.has("answer")) {
                            answer = inner.get("answer").asText();
                        } else {
                            answer = rawAnswer;
                        }
                    } catch (Exception e) {
                        answer = rawAnswer;
                    }
                } else {
                    answer = rawAnswer;
                }
            }
            
            if (answer == null) {
                 answer = extractJsonStringField(body, "finalAnswer");
                 if (answer == null) answer = extractJsonStringField(body, "answer");
                 if (answer == null) answer = body;
            }
            response.answer = answer;
            
        } catch (Exception e) {
            logger.warn("chat.parse_error", e);
            response.answer = body;
        }
        
        return response;
    }

    private String buildIdeContextWithTimeout(long timeoutMs) {
        Callable<String> task = this::buildIdeContext;
        Future<String> future = IDE_CONTEXT_EXECUTOR.submit(task);
        try {
            return future.get(Math.max(200L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "IDEA 结构捕获超时/失败";
        }
    }

    private String buildIdeContext() {
        if (project == null || DumbService.isDumb(project)) return "IDEA 索引未就绪";
        return ReadAction.compute(() -> {
            StringBuilder sb = new StringBuilder("ClassStructure:\n");
            ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
            List<VirtualFile> files = new ArrayList<>();
            fileIndex.iterateContent(file -> {
                if (files.size() < 60 && !file.isDirectory() && file.getFileType() instanceof JavaFileType) {
                    files.add(file);
                }
                return true;
            });
            
            PsiManager psiManager = PsiManager.getInstance(project);
            for (VirtualFile file : files) {
                PsiFile psiFile = psiManager.findFile(file);
                if (psiFile instanceof PsiJavaFile) {
                    PsiClass[] classes = ((PsiJavaFile) psiFile).getClasses();
                    for (PsiClass clz : classes) {
                        sb.append("class ").append(clz.getName()).append("\n");
                    }
                }
            }
            return sb.toString();
        });
    }

    private String extractJsonStringField(String json, String field) {
        try {
            JsonNode node = mapper.readTree(json);
            if (node.has(field)) return node.get(field).asText();
        } catch (Exception e) {}
        return null;
    }

    private void setBusy(boolean busy, String msg) {
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) return;
            send.setEnabled(!busy);
            status.setText(msg);
        });
    }

    private String resolveWorkspaceRoot(Project project) {
        return project.getBasePath() != null ? project.getBasePath() : System.getProperty("user.dir");
    }

    private String resolveWorkspaceName(String root) {
        return root == null ? "unknown" : Path.of(root).getFileName().toString();
    }
    
    private void rebuildConversationFromHistory() {
        conversationList.removeAll();
        
        if (history == null) return;
        List<String> lines = history.getLines();
        if (lines != null) {
            for (String line : lines) {
                if (line.startsWith("你: ")) {
                    addMessage(true, line.substring(3), null, false);
                } else if (line.startsWith("Agent: ")) {
                    AgentResponse resp = new AgentResponse();
                    resp.answer = line.substring(7);
                    addMessage(false, null, resp, false);
                } else {
                    addSystemMessage(line);
                }
            }
        }
        
        conversationList.revalidate();
        conversationList.repaint();
        scrollToBottom();
    }
    
    private static long longOrDefault(String val, long def) {
        try { return Long.parseLong(val); } catch (Exception e) { return def; }
    }
    
    // -- Inner Classes --
    
    private class CollapsiblePanel extends JPanel {
        private final JPanel contentPanel;
        private final JButton toggleBtn;
        private final JTextArea textArea;
        private boolean expanded = false;
        private String contentText = "";
        
        CollapsiblePanel(String title, String contentText, boolean animate) {
            this.contentText = contentText == null ? "" : contentText;
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
            
            toggleBtn = new JButton("▶ " + title);
            toggleBtn.setHorizontalAlignment(SwingConstants.LEFT);
            toggleBtn.setBorderPainted(false);
            toggleBtn.setContentAreaFilled(false);
            toggleBtn.setFocusPainted(false);
            toggleBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            
            contentPanel = new JPanel(new BorderLayout());
            textArea = new JTextArea();
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            textArea.setEditable(false);
            textArea.setBackground(new Color(245, 245, 245));
            textArea.setBorder(JBUI.Borders.empty(5));
            
            if (animate) {
                // Streaming effect for thinking
                contentPanel.setVisible(false);
                Timer timer = new Timer(20, null);
                final int[] index = {0};
                final int length = contentText.length();
                timer.addActionListener(e -> {
                    if (project.isDisposed()) { timer.stop(); return; }
                    index[0] += 1;
                    if (index[0] >= length) {
                        index[0] = length;
                        timer.stop();
                    }
                    textArea.setText(contentText.substring(0, index[0]));
                });
                timer.start();
            } else {
                textArea.setText(contentText);
                contentPanel.setVisible(false);
            }
            
            contentPanel.add(textArea, BorderLayout.CENTER);
            
            toggleBtn.addActionListener(e -> {
                expanded = !expanded;
                contentPanel.setVisible(expanded);
                toggleBtn.setText((expanded ? "▼ " : "▶ ") + title);
                revalidate();
                repaint();
            });
            
            add(toggleBtn, BorderLayout.NORTH);
            add(contentPanel, BorderLayout.CENTER);
        }

        void appendContent(String text) {
            if (text == null) return;
            this.contentText += text;
            this.textArea.setText(this.contentText);
        }
    }
    
}
