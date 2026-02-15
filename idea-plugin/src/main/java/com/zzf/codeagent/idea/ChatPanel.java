package com.zzf.codeagent.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
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
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;
import com.zzf.codeagent.core.event.EventStream;
import com.zzf.codeagent.core.tool.PendingChangesManager;
import com.zzf.codeagent.core.tools.FileSystemToolService;
import com.zzf.codeagent.idea.utils.MarkdownUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ChatPanel {
    private static final Logger logger = Logger.getInstance(ChatPanel.class);
    private static final Duration CHAT_TIMEOUT = Duration.ofSeconds(longOrDefault(System.getProperty("codeagent.chatTimeoutSeconds", ""), 600L));
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(longOrDefault(System.getProperty("codeagent.connectTimeoutSeconds", ""), 10L));
    private static final int HISTORY_SEND_MAX_LINES = (int) longOrDefault(System.getProperty("codeagent.historyMaxLines", ""), 60L);
    private static final int AUTO_SCROLL_BOTTOM_THRESHOLD_PX = 50;
    private static final long MANUAL_SCROLL_WINDOW_MS = 1200L;
    private static final String PENDING_ASSISTANT_KEY = "__pending_assistant__";
    private static final Pattern EXIT_CODE_PATTERN = Pattern.compile("(?i)exit\\s*code\\s*(\\d+)");
    private static final ExecutorService IDE_CONTEXT_EXECUTOR = Executors.newCachedThreadPool(r -> new Thread(r, "rikki-ide-context"));
    
    private final JSplitPane splitPane;
    private final JPanel leftPanel; // Session List
    private final JPanel rightPanel; // Chat Area
    private final JPanel conversationList;
    private final JBScrollPane scrollPane;
    private final JBTextArea input;
    private final JButton send;
    private final JLabel status;
    private final JButton jumpToBottomButton;
    private final HttpClient http;
    private final Project project;
    private final String workspaceRoot;
    private final String workspaceName;
    private String currentSessionId;
    private final ChatHistoryService history;
    private final ObjectMapper mapper = new ObjectMapper();
    private FileSystemToolService fsService;
    private EventStream eventStream;
    private final DiffService diffService;
    private final boolean pendingWorkflowEnabled;
    // private JPanel pendingChangesPanel; // Removed in favor of popup
    private JPanel pendingChangesList;
    private JButton commitAllButton;
    private JButton pendingChangesToggle;
    private JBPopup activePendingPopup;
    private final List<PendingChangesManager.PendingChange> pendingChanges = new ArrayList<>();
    private final Set<String> pendingApprovalKeys = ConcurrentHashMap.newKeySet();
    private volatile boolean runtimeBusy;
    private volatile boolean awaitingUserApproval;
    private volatile String runtimeBusyMessage = "Ready";
    private volatile String approvalBusyMessage = "Awaiting your approval...";
    private volatile boolean followStreamingOutput = true;
    private volatile boolean suppressScrollTracking;
    private volatile long lastManualScrollAtMs;

    ChatPanel(Project project) {
        this.project = project;
        this.pendingWorkflowEnabled = false;
        
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
        this.send = new JButton("Send");
        this.status = new JLabel("Ready");
        this.jumpToBottomButton = createJumpToBottomButton();
        this.jumpToBottomButton.addActionListener(e -> {
            followStreamingOutput = true;
            scrollToBottom();
        });
        JLayeredPane chatCenterPanel = new JLayeredPane() {
            @Override
            public void doLayout() {
                int width = getWidth();
                int height = getHeight();
                scrollPane.setBounds(0, 0, width, height);
                Dimension buttonSize = jumpToBottomButton.getPreferredSize();
                int x = Math.max(8, (width - buttonSize.width) / 2);
                int y = Math.max(8, height - buttonSize.height - 14);
                jumpToBottomButton.setBounds(x, y, buttonSize.width, buttonSize.height);
            }
        };
        chatCenterPanel.setOpaque(false);
        chatCenterPanel.add(this.scrollPane, JLayeredPane.DEFAULT_LAYER);
        chatCenterPanel.add(this.jumpToBottomButton, JLayeredPane.PALETTE_LAYER);
        this.jumpToBottomButton.setVisible(false);
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.diffService = new DiffService(project, http, mapper);
        
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
        JButton toggleSessions = new JButton("History");
        toggleSessions.setBorderPainted(false);
        toggleSessions.setContentAreaFilled(false);
        toggleSessions.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        
        titleBar.add(toggleSessions, BorderLayout.WEST);
        titleBar.add(new JLabel(" Chat with Agent", SwingConstants.CENTER), BorderLayout.CENTER);
        
        if (pendingWorkflowEnabled) {
            pendingChangesToggle = new JButton("Pending (0)");
            pendingChangesToggle.setBorderPainted(false);
            pendingChangesToggle.setContentAreaFilled(false);
            pendingChangesToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            pendingChangesToggle.setVisible(false); // Hidden if 0
            pendingChangesToggle.addActionListener(e -> showPendingChangesPopup());
            titleBar.add(pendingChangesToggle, BorderLayout.EAST);
            
            // Initialize the list panel for the popup
            initPendingChangesList();
        }
        
        headerPanel.add(titleBar);
        headerPanel.add(Box.createVerticalStrut(5));
        // Removed inline pendingChangesPanel
        
        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.add(new JBScrollPane(input), BorderLayout.CENTER);
        bottom.add(send, BorderLayout.EAST);
        bottom.add(status, BorderLayout.SOUTH);

        rightPanel.add(headerPanel, BorderLayout.NORTH);
        rightPanel.add(chatCenterPanel, BorderLayout.CENTER);
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
        installConversationScrollBehavior();
        
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
            currentSessionId = null;
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
                item.setBackground(new JBColor(new Color(220, 230, 240), new Color(70, 76, 84)));
            }

            JLabel title = new JLabel(session.title);
            title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            title.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    history.setCurrentSession(session.id);
                    currentSessionId = session.backendSessionId;
                    refreshSessionList();
                    rebuildConversationFromHistory();
                }
            });

            JButton delBtn = new JButton("Delete");
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
        followStreamingOutput = true;
        ChatHistoryService.ChatSession session = history.getCurrentSession();
        if (session != null && session.backendSessionId != null && !session.backendSessionId.isEmpty()) {
            currentSessionId = session.backendSessionId;
        }
        
        // 1. Add User Message
        addMessage(true, text, null, false);
        history.appendLine("You: " + text.trim());
        persistUserUiMessage(text.trim());
        refreshSessionList(); // Update title if new
        
        // 2. Add Agent Placeholder
        AgentMessageUI placeholderUi = addMessage(false, null, null, true);
        placeholderUi.historyUiMessageId = createAssistantUiPlaceholder();
        
        // 3. Call Agent Stream
        callAgentStream(text.trim(), placeholderUi);
    }
    
    private void callAgentStream(String userMessage, AgentMessageUI initialUi) {
        pendingApprovalKeys.clear();
        setAwaitingUserApproval(false, null);
        setBusy(true, "Agent is thinking...");
        
        new Thread(() -> {
            Map<String, AgentMessageUI> assistantUiByMessageID = new LinkedHashMap<>();
            assistantUiByMessageID.put(PENDING_ASSISTANT_KEY, initialUi);
            try {
                String ideContext = buildIdeContextWithTimeout(5000);
                
                ObjectNode json = mapper.createObjectNode();
                json.put("goal", userMessage);
                json.put("workspaceRoot", workspaceRoot);
                json.put("workspaceName", workspaceName);
                json.put("ideContextContent", ideContext);
                if (currentSessionId != null && !currentSessionId.isEmpty()) {
                    json.put("sessionID", currentSessionId);
                }
                
                ChatHistoryService.ChatSession session = history.getCurrentSession();
                if (session != null && session.settings != null) {
                    ObjectNode settingsNode = json.putObject("settings");
                    settingsNode.put("model", session.settings.model);
                    settingsNode.put("language", session.settings.language);
                    settingsNode.put("temperature", session.settings.temperature);
                    settingsNode.put("agent", session.settings.agent);
                    if (session.settings.agent != null && !session.settings.agent.isEmpty()) {
                        json.put("agent", session.settings.agent);
                    }
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
                final StringBuilder currentData = new StringBuilder();
                
                http.send(req, HttpResponse.BodyHandlers.ofLines()).body().forEach(line -> {
                    if (line.startsWith("event:")) {
                        currentEvent[0] = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (currentData.length() > 0) {
                            currentData.append('\n');
                        }
                        currentData.append(line.substring(5).trim());
                    } else if (line.isBlank()) {
                        dispatchSseEvent(currentEvent[0], currentData.toString(), assistantUiByMessageID);
                        currentEvent[0] = null;
                        currentData.setLength(0);
                    }
                });
                if (currentData.length() > 0) {
                    dispatchSseEvent(currentEvent[0], currentData.toString(), assistantUiByMessageID);
                }
                
            } catch (Exception e) {
                logger.warn("stream_error", e);
                SwingUtilities.invokeLater(() -> {
                    if (initialUi.answerPane != null) initialUi.answerPane.setText("Error: " + e.getMessage());
                });
            } finally {
                finalizePendingAssistantResponses(assistantUiByMessageID);
                setBusy(false, "Ready");
            }
        }).start();
    }

    private void dispatchSseEvent(String event, String data, Map<String, AgentMessageUI> assistantUiByMessageID) {
        if (data == null || data.isEmpty()) {
            return;
        }
        handleSseEvent(event, data, assistantUiByMessageID);
    }
    
    private void handleSseEvent(String event, String data, Map<String, AgentMessageUI> assistantUiByMessageID) {
        SwingUtilities.invokeLater(() -> {
            try {
                if ("session".equals(event)) {
                    JsonNode node = mapper.readTree(data);
                    String sid = node.path("sessionID").asText(node.path("traceId").asText(""));
                    if (sid != null && !sid.isBlank()) {
                        bindBackendSession(sid);
                    }
                } else if ("thought".equals(event)) {
                    JsonNode node = mapper.readTree(data);
                    String delta = node.path("reasoning_delta").asText("");
                    if (delta.isEmpty()) {
                        return;
                    }
                    AgentMessageUI ui = resolveAssistantUi(assistantUiByMessageID, extractMessageID(node));
                    ui.thinkingOpen = true;

                    if (ui.thoughtPanel == null && ui.messagePanel != null) {
                        CollapsiblePanel thoughtPanel = new CollapsiblePanel("Thinking Process", "", true);
                        ui.thoughtPanel = thoughtPanel;
                        ui.messagePanel.add(thoughtPanel, 0);
                        ui.messagePanel.add(Box.createVerticalStrut(5), 1);
                        ui.messagePanel.revalidate();
                        ui.messagePanel.repaint();
                    }
                    if (ui.thoughtPanel != null && !delta.isEmpty()) {
                        ui.thoughtPanel.appendContent(delta, true);
                    }
                } else if ("thought_end".equals(event)) {
                    JsonNode node = mapper.readTree(data);
                    AgentMessageUI ui = findAssistantUi(assistantUiByMessageID, extractMessageID(node));
                    if (ui == null) {
                        return;
                    }
                    ui.thinkingOpen = false;
                    flushDeferredAnswer(ui);
                    persistAssistantUiSnapshot(ui);
                } else if ("message".equals(event)) {
                    JsonNode node = mapper.readTree(data);
                    String delta = node.path("delta").asText(node.path("text_delta").asText(""));
                    if (delta.isEmpty()) {
                        return;
                    }
                    AgentMessageUI ui = resolveAssistantUi(assistantUiByMessageID, extractMessageID(node));
                    appendAssistantText(ui, delta);
                } else if ("message_part".equals(event)) {
                    JsonNode node = mapper.readTree(data);
                    String delta = node.path("delta").asText(node.path("text_delta").asText(""));
                    String snapshotText = node.path("text").asText("");
                    if (delta.isBlank() && snapshotText.isBlank()) {
                        return;
                    }
                    AgentMessageUI ui = resolveAssistantUi(assistantUiByMessageID, extractMessageID(node));
                    if (!delta.isBlank()) {
                        appendAssistantText(ui, delta);
                    } else {
                        syncAssistantTextFromSnapshot(ui, snapshotText);
                    }
                } else if ("artifact_update".equals(event)) {
                    JsonNode node = mapper.readTree(data);
                    if (node.has("change")) {
                        try {
                            JsonNode changeNode = node.get("change");
                            PendingChangesManager.PendingChange change = mapper.treeToValue(changeNode, PendingChangesManager.PendingChange.class);
                            if (change != null) {
                                recordSessionChange(lastAssistantUi(assistantUiByMessageID), change);
                            }
                        } catch (Exception ignored) {
                            // ignore malformed artifact payload
                        }
                    }
                } else if ("finish".equals(event)) {
                    JsonNode node = mapper.readTree(data);
                    String messageID = extractMessageID(node);
                    AgentMessageUI ui = resolveAssistantUi(assistantUiByMessageID, messageID);
                    ui.messageID = firstNonBlank(messageID, ui.messageID);
                    ui.thinkingOpen = false;
                    flushDeferredAnswer(ui);
                    if (node.has("sessionID")) {
                        String sid = node.path("sessionID").asText("");
                        bindBackendSession(sid);
                    }
                    AgentResponse resp = parseAgentResponse(data);
                    if (resp.sessionID != null && !resp.sessionID.isEmpty()) {
                        bindBackendSession(resp.sessionID);
                    }

                    String ans = (resp.answer != null && !resp.answer.isBlank()) ? resp.answer : ui.answerBuffer.toString();
                    if (ans == null || ans.isBlank()) {
                        ans = "No answer received";
                    }
                    if (ui.answerPane != null) {
                        ui.answerPane.setText(MarkdownUtils.renderToHtml(ans));
                    }
                    ui.answerBuffer.setLength(0);
                    ui.answerBuffer.append(ans);
                    ui.streamFinished = true;

                    if (history != null && !ui.historyCommitted) {
                        history.appendLine("Agent: " + ans);
                        ui.historyCommitted = true;
                    }
                    if (resp.changes != null && !resp.changes.isEmpty()) {
                        for (PendingChangesManager.PendingChange change : resp.changes) {
                            applyAndRecordSessionChange(ui, null, change);
                        }
                    }
                    appendSessionChangeSummaryIfNeeded(ui);
                    persistAssistantUiSnapshot(ui);
                    scrollToBottomSmart();
                    setBusy(false, "Ready");
                } else if ("error".equals(event)) {
                    AgentMessageUI ui = lastAssistantUi(assistantUiByMessageID);
                    if (ui.answerPane != null) {
                        ui.answerPane.setText(ui.answerPane.getText() + "<br><span style='color:red'>Error: " + data + "</span>");
                    }
                    ui.streamFinished = true;
                    appendSessionChangeSummaryIfNeeded(ui);
                    persistAssistantUiSnapshot(ui);
                } else if ("tool_call".equals(event) || "tool_result".equals(event)) {
                    JsonNode node = mapper.readTree(data);
                    handleToolEvent(event, node, assistantUiByMessageID);
                } else if ("status".equals(event)) {
                    handleStatusEvent(data);
                } else if ("heartbeat".equals(event)) {
                    // Keep-alive event, no UI update required.
                }
            } catch (Exception e) {
                logger.warn("sse_event_parse_error event=" + event, e);
            }
        });
    }

    private AgentMessageUI resolveAssistantUi(Map<String, AgentMessageUI> assistantUiByMessageID, String messageID) {
        String normalizedID = messageID != null ? messageID.trim() : "";
        if (!normalizedID.isEmpty()) {
            AgentMessageUI existing = assistantUiByMessageID.get(normalizedID);
            if (existing != null) {
                existing.messageID = normalizedID;
                return existing;
            }
            AgentMessageUI pending = assistantUiByMessageID.remove(PENDING_ASSISTANT_KEY);
            if (pending != null) {
                pending.messageID = normalizedID;
                assistantUiByMessageID.put(normalizedID, pending);
                return pending;
            }
            AgentMessageUI created = addMessage(false, null, null, true);
            created.messageID = normalizedID;
            created.historyUiMessageId = createAssistantUiPlaceholder();
            assistantUiByMessageID.put(normalizedID, created);
            return created;
        }

        AgentMessageUI pending = assistantUiByMessageID.get(PENDING_ASSISTANT_KEY);
        if (pending != null) {
            return pending;
        }
        AgentMessageUI created = addMessage(false, null, null, true);
        created.historyUiMessageId = createAssistantUiPlaceholder();
        assistantUiByMessageID.put(PENDING_ASSISTANT_KEY, created);
        return created;
    }

    private AgentMessageUI findAssistantUi(Map<String, AgentMessageUI> assistantUiByMessageID, String messageID) {
        String normalizedID = messageID != null ? messageID.trim() : "";
        if (!normalizedID.isEmpty()) {
            return assistantUiByMessageID.get(normalizedID);
        }
        return assistantUiByMessageID.get(PENDING_ASSISTANT_KEY);
    }

    private AgentMessageUI lastAssistantUi(Map<String, AgentMessageUI> assistantUiByMessageID) {
        AgentMessageUI last = null;
        for (Map.Entry<String, AgentMessageUI> entry : assistantUiByMessageID.entrySet()) {
            if (!PENDING_ASSISTANT_KEY.equals(entry.getKey())) {
                last = entry.getValue();
            }
        }
        return last != null ? last : resolveAssistantUi(assistantUiByMessageID, null);
    }

    private String extractMessageID(JsonNode node) {
        if (node == null || node.isMissingNode()) return null;
        if (node.has("messageID")) return node.path("messageID").asText(null);
        if (node.has("messageId")) return node.path("messageId").asText(null);
        if (node.has("id")) return node.path("id").asText(null);
        return null;
    }

    private void handleToolEvent(String event, JsonNode node, Map<String, AgentMessageUI> assistantUiByMessageID) {
        String messageID = null;
        if (node != null) {
            if (node.has("messageID")) {
                messageID = node.path("messageID").asText(null);
            } else if (node.has("messageId")) {
                messageID = node.path("messageId").asText(null);
            }
        }
        AgentMessageUI ui = (messageID == null || messageID.isBlank())
                ? lastAssistantUi(assistantUiByMessageID)
                : resolveAssistantUi(assistantUiByMessageID, messageID);
        if (ui == null) {
            return;
        }
        String callID = extractToolCallID(node);
        if (callID == null || callID.isBlank()) {
            callID = "__tool_event_" + (++ui.toolEventSeq);
        }
        String toolName = node.path("tool").asText("tool");
        ToolActivityState state = resolveToolActivity(ui, callID, toolName);
        JsonNode argsNode = node.path("args");
        if (argsNode != null && !argsNode.isMissingNode() && !argsNode.isNull()) {
            state.lastArgs = argsNode;
            updateReadTargetFromArgs(state, argsNode);
        }

        if ("tool_call".equals(event)) {
            if (state.startedAtMs <= 0L) {
                state.startedAtMs = System.currentTimeMillis();
            }
            String intent = argsNode.path("description").asText("");
            if (!intent.isBlank()) {
                state.intentSummary = trimForUi(intent, 160);
            }
            state.inputSummary = summarizeArgs(argsNode);
            state.commandSummary = summarizeCommand(argsNode, state.inputSummary);
            state.inputDetails = summarizeInputDetails(argsNode);
            String status = node.path("state").asText("");
            if (status == null || status.isBlank()) {
                status = "running";
            }
            state.status = status;
            JsonNode metaNode = extractToolMeta(node);
            PendingCommandInfo pendingCommand = extractPendingCommand(metaNode);
            if (pendingCommand != null) {
                state.pendingCommand = pendingCommand;
                state.commandDecisionRequired = !state.commandDecisionMade;
                state.status = "awaiting_approval";
            }
            PendingChangesManager.PendingChange pendingChange = extractPendingChange(metaNode);
            if (pendingChange == null) {
                pendingChange = synthesizePendingChangeFromArgs(state.tool, argsNode);
            }
            if (pendingChange != null) {
                state.pendingChange = pendingChange;
                state.deleteDecisionRequired = isDeleteTool(state.tool)
                        && "DELETE".equalsIgnoreCase(pendingChange.type)
                        && !state.deleteDecisionMade;
            }
            state.exitCode = null;
        } else if ("tool_result".equals(event)) {
            String status = node.path("state").asText("completed");
            state.status = (status == null || status.isBlank()) ? "completed" : status;
            if (state.startedAtMs <= 0L) {
                state.startedAtMs = System.currentTimeMillis();
            }
            if (state.finishedAtMs <= 0L) {
                state.finishedAtMs = System.currentTimeMillis();
            }
            state.durationMs = Math.max(0L, state.finishedAtMs - state.startedAtMs);
            state.error = node.path("error").asText("");
            state.title = node.path("title").asText("");
            if ((state.intentSummary == null || state.intentSummary.isBlank()) && state.title != null && !state.title.isBlank()) {
                state.intentSummary = trimForUi(state.title, 160);
            }
            JsonNode metaNode = extractToolMeta(node);
            state.output = extractToolOutput(node, metaNode);
            state.exitCode = extractExitCode(node, metaNode);
            PendingChangesManager.PendingChange pendingChange = extractPendingChange(metaNode);
            if (pendingChange == null) {
                pendingChange = synthesizePendingChangeFromArgs(state.tool, state.lastArgs);
            }
            if (pendingChange != null) {
                state.pendingChange = pendingChange;
                state.deleteDecisionRequired = isDeleteTool(state.tool)
                        && "DELETE".equalsIgnoreCase(pendingChange.type)
                        && !state.deleteDecisionMade;
            }
            PendingCommandInfo pendingCommand = extractPendingCommand(metaNode);
            if (pendingCommand != null) {
                state.pendingCommand = pendingCommand;
                state.commandDecisionRequired = !state.commandDecisionMade;
                state.status = "awaiting_approval";
                if (state.commandSummary == null || state.commandSummary.isBlank()) {
                    state.commandSummary = trimForUi(pendingCommand.command, 180);
                }
                if ((state.intentSummary == null || state.intentSummary.isBlank()) && pendingCommand.description != null && !pendingCommand.description.isBlank()) {
                    state.intentSummary = trimForUi(pendingCommand.description, 160);
                }
            }
            if ("completed".equalsIgnoreCase(state.status)
                    && state.pendingChange != null
                    && !state.deleteDecisionRequired) {
                applyAndRecordSessionChange(ui, state, state.pendingChange);
            }
        }

        ensureToolRenderType(ui, state, classifyToolRenderType(state.tool, state.lastArgs, state.pendingChange));
        refreshToolActivityUi(state);
        updateToolDecisionActions(ui, state);
        syncApprovalState(state);
        persistAssistantUiSnapshot(ui);
        scrollToBottomSmart();
    }

    private void handleStatusEvent(String data) {
        String statusType = extractStatusType(data);
        if ("idle".equals(statusType)) {
            setBusy(false, "Ready");
        } else if ("busy".equals(statusType) || "running".equals(statusType) || "pending".equals(statusType) || "retry".equals(statusType)) {
            setBusy(true, "Agent is thinking...");
        }
    }

    private ToolActivityState resolveToolActivity(AgentMessageUI ui, String callID, String toolName) {
        ToolActivityState existing = ui.toolActivities.get(callID);
        if (existing != null) {
            if (toolName != null && !toolName.isBlank()) {
                existing.tool = toolName;
            }
            ensureToolRenderType(ui, existing, classifyToolRenderType(existing.tool, existing.lastArgs, existing.pendingChange));
            return existing;
        }

        JPanel activityPanel = ensureActivityContainer(ui);
        ToolActivityState created = new ToolActivityState();
        created.callID = callID;
        created.tool = toolName;
        created.hostPanel = new JPanel();
        created.hostPanel.setLayout(new BoxLayout(created.hostPanel, BoxLayout.Y_AXIS));
        created.hostPanel.setOpaque(false);

        ui.toolActivities.put(callID, created);
        activityPanel.add(created.hostPanel);
        activityPanel.add(Box.createVerticalStrut(4));
        ensureToolRenderType(ui, created, classifyToolRenderType(toolName, null, null));
        ui.messagePanel.revalidate();
        ui.messagePanel.repaint();
        return created;
    }

    private void ensureToolRenderType(AgentMessageUI ui, ToolActivityState state, ToolRenderType desiredType) {
        if (state == null) {
            return;
        }
        ToolRenderType resolved = desiredType == null ? ToolRenderType.GENERIC : desiredType;
        boolean rebuild = state.renderType != resolved || state.hostPanel == null;
        state.renderType = resolved;
        if (state.hostPanel == null && ui != null) {
            JPanel activityPanel = ensureActivityContainer(ui);
            state.hostPanel = new JPanel();
            state.hostPanel.setLayout(new BoxLayout(state.hostPanel, BoxLayout.Y_AXIS));
            state.hostPanel.setOpaque(false);
            activityPanel.add(state.hostPanel);
            activityPanel.add(Box.createVerticalStrut(4));
            rebuild = true;
        }
        if (rebuild) {
            rebuildToolHostContent(state);
            return;
        }
        if ((state.renderType == ToolRenderType.GENERIC || state.renderType == ToolRenderType.TERMINAL) && state.panel == null) {
            rebuildToolHostContent(state);
            return;
        }
        if ((state.renderType == ToolRenderType.MODIFICATION || state.renderType == ToolRenderType.READ) && state.inlineRow == null) {
            rebuildToolHostContent(state);
            return;
        }
        refreshInlineDiffCard(state);
    }

    private void rebuildToolHostContent(ToolActivityState state) {
        if (state == null || state.hostPanel == null) {
            return;
        }
        state.hostPanel.removeAll();
        state.panel = null;
        state.inlineRow = null;
        state.inlineDiffCard = null;

        if (state.renderType == ToolRenderType.GENERIC || state.renderType == ToolRenderType.TERMINAL) {
            ActivityCommandPanel commandPanel = new ActivityCommandPanel("Ran " + (state.tool == null ? "tool" : state.tool));
            commandPanel.setDetails("");
            state.panel = commandPanel;
            state.hostPanel.add(commandPanel);
        } else {
            String marker = state.renderType == ToolRenderType.MODIFICATION ? "[edit]" : "[read]";
            InlineToolRowPanel row = new InlineToolRowPanel(marker);
            state.inlineRow = row;
            state.hostPanel.add(row);
        }
        refreshInlineDiffCard(state);
        state.hostPanel.revalidate();
        state.hostPanel.repaint();
    }

    private void refreshInlineDiffCard(ToolActivityState state) {
        if (state == null || state.hostPanel == null) {
            return;
        }
        if (state.inlineDiffCard != null) {
            state.hostPanel.remove(state.inlineDiffCard);
            state.inlineDiffCard = null;
        }
        if (state.renderType != ToolRenderType.MODIFICATION) {
            state.hostPanel.revalidate();
            state.hostPanel.repaint();
            return;
        }
        PendingChangesManager.PendingChange change = state.pendingChange;
        if (change == null || "DELETE".equalsIgnoreCase(change.type)) {
            state.hostPanel.revalidate();
            state.hostPanel.repaint();
            return;
        }
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(JBUI.Borders.emptyTop(4));
        wrapper.add(createChangeRowCard(change, "Show Diff"), BorderLayout.CENTER);
        state.inlineDiffCard = wrapper;
        state.hostPanel.add(wrapper);
        state.hostPanel.revalidate();
        state.hostPanel.repaint();
    }

    private ToolRenderType classifyToolRenderType(
            String toolName,
            JsonNode argsNode,
            PendingChangesManager.PendingChange pendingChange
    ) {
        if (isBashTool(toolName)) {
            return ToolRenderType.TERMINAL;
        }
        if (isDeleteTool(toolName)) {
            return ToolRenderType.GENERIC;
        }
        if (pendingChange != null && !"DELETE".equalsIgnoreCase(pendingChange.type)) {
            return ToolRenderType.MODIFICATION;
        }
        if (isModificationTool(toolName)) {
            return ToolRenderType.MODIFICATION;
        }
        if (isReadTool(toolName, argsNode)) {
            return ToolRenderType.READ;
        }
        return ToolRenderType.GENERIC;
    }

    private JPanel ensureActivityContainer(AgentMessageUI ui) {
        if (ui.activityPanel != null) {
            return ui.activityPanel;
        }
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Activity"));
        panel.setOpaque(false);

        int insertIndex = ui.answerPane != null ? ui.messagePanel.getComponentZOrder(ui.answerPane) : ui.messagePanel.getComponentCount();
        if (insertIndex < 0) {
            insertIndex = ui.messagePanel.getComponentCount();
        }
        ui.messagePanel.add(panel, insertIndex);
        ui.messagePanel.add(Box.createVerticalStrut(5), insertIndex + 1);
        ui.activityPanel = panel;
        ui.messagePanel.revalidate();
        ui.messagePanel.repaint();
        return panel;
    }

    private String extractToolCallID(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        if (node.has("callID")) return node.path("callID").asText(null);
        if (node.has("callId")) return node.path("callId").asText(null);
        if (node.has("partID")) return node.path("partID").asText(null);
        if (node.has("partId")) return node.path("partId").asText(null);
        if (node.has("id")) return node.path("id").asText(null);
        return null;
    }

    private String extractStatusType(String data) {
        if (data == null || data.isBlank()) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(data);
            if (node.isTextual()) {
                return node.asText("").trim().toLowerCase();
            }
            if (node.isObject()) {
                return node.path("type").asText("").trim().toLowerCase();
            }
        } catch (Exception ignored) {
            return data.trim().toLowerCase();
        }
        return "";
    }

    private JsonNode extractToolMeta(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode metaNode = node.get("meta");
        if (metaNode == null || metaNode.isMissingNode() || metaNode.isNull()) {
            metaNode = node.get("metadata");
        }
        if (metaNode == null || metaNode.isMissingNode() || metaNode.isNull()) {
            return null;
        }
        return metaNode;
    }

    private PendingChangesManager.PendingChange extractPendingChange(JsonNode metaNode) {
        if (metaNode == null || metaNode.isMissingNode() || metaNode.isNull()) {
            return null;
        }
        JsonNode changeNode = metaNode.get("pending_change");
        if (changeNode == null || changeNode.isMissingNode() || changeNode.isNull()) {
            return null;
        }
        String id = firstNonBlank(
                changeNode.path("id").asText(""),
                java.util.UUID.randomUUID().toString()
        );
        String path = firstNonBlank(
                changeNode.path("path").asText(""),
                changeNode.path("filePath").asText("")
        );
        if (path.isBlank()) {
            return null;
        }
        String type = firstNonBlank(changeNode.path("type").asText(""), "EDIT");
        String oldContent = firstNonBlank(
                changeNode.path("oldContent").asText(""),
                changeNode.path("old_content").asText("")
        );
        String newContent = firstNonBlank(
                changeNode.path("newContent").asText(""),
                changeNode.path("new_content").asText("")
        );
        String preview = firstNonBlank(changeNode.path("preview").asText(""), null);
        long ts = changeNode.path("timestamp").asLong(System.currentTimeMillis());
        String wsRoot = firstNonBlank(
                changeNode.path("workspaceRoot").asText(""),
                changeNode.path("workspace_root").asText(""),
                workspaceRoot
        );
        String sid = firstNonBlank(
                changeNode.path("sessionId").asText(""),
                changeNode.path("session_id").asText(""),
                currentSessionId
        );
        return new PendingChangesManager.PendingChange(id, path, type, oldContent, newContent, preview, ts, wsRoot, sid);
    }

    private PendingChangesManager.PendingChange synthesizePendingChangeFromArgs(String toolName, JsonNode argsNode) {
        if (!isModificationTool(toolName) || isDeleteTool(toolName) || argsNode == null || !argsNode.isObject()) {
            return null;
        }
        String path = firstNonBlank(
                argsNode.path("filePath").asText(""),
                argsNode.path("path").asText("")
        );
        if (path.isBlank()) {
            return null;
        }
        String newContent = firstNonBlank(
                argsNode.path("content").asText(""),
                argsNode.path("newContent").asText(""),
                argsNode.path("new_content").asText("")
        );
        String oldContent = firstNonBlank(
                argsNode.path("oldContent").asText(""),
                argsNode.path("old_content").asText("")
        );
        String type = "EDIT";
        if (!newContent.isBlank() && oldContent.isBlank()) {
            type = "CREATE";
        } else if (newContent.isBlank() && !oldContent.isBlank()) {
            type = "DELETE";
        }
        return new PendingChangesManager.PendingChange(
                java.util.UUID.randomUUID().toString(),
                path,
                type,
                oldContent,
                newContent,
                "",
                System.currentTimeMillis(),
                workspaceRoot,
                currentSessionId
        );
    }

    private PendingCommandInfo extractPendingCommand(JsonNode metaNode) {
        if (metaNode == null || metaNode.isMissingNode() || metaNode.isNull()) {
            return null;
        }
        JsonNode cmdNode = metaNode.get("pending_command");
        if (cmdNode == null || cmdNode.isMissingNode() || cmdNode.isNull()) {
            return null;
        }
        String id = firstNonBlank(cmdNode.path("id").asText(""));
        String command = firstNonBlank(cmdNode.path("command").asText(""));
        if (id.isBlank() || command.isBlank()) {
            return null;
        }
        PendingCommandInfo info = new PendingCommandInfo();
        info.id = id;
        info.command = command;
        info.description = firstNonBlank(cmdNode.path("description").asText(""));
        info.workdir = firstNonBlank(cmdNode.path("workdir").asText(""));
        info.workspaceRoot = firstNonBlank(cmdNode.path("workspaceRoot").asText(""), workspaceRoot);
        info.sessionId = firstNonBlank(cmdNode.path("sessionId").asText(""), currentSessionId);
        info.timeoutMs = cmdNode.path("timeoutMs").asLong(60000L);
        info.riskLevel = firstNonBlank(cmdNode.path("riskLevel").asText(""), "high");
        JsonNode reasonsNode = cmdNode.path("reasons");
        if (reasonsNode.isArray()) {
            reasonsNode.forEach(n -> {
                String reason = n.asText("");
                if (!reason.isBlank()) {
                    info.reasons.add(reason);
                }
            });
        }
        return info;
    }

    private String extractMetaOutput(JsonNode metaNode) {
        if (metaNode == null || metaNode.isMissingNode() || metaNode.isNull()) {
            return "";
        }
        JsonNode outputNode = metaNode.get("output");
        if (outputNode != null && !outputNode.isMissingNode() && !outputNode.isNull()) {
            if (outputNode.isTextual()) {
                String text = outputNode.asText("");
                if (!text.isBlank()) {
                    return text;
                }
            } else {
                String json = prettyJson(outputNode);
                if (!json.isBlank() && !"{}".equals(json) && !"[]".equals(json)) {
                    return json;
                }
            }
        }
        JsonNode stdoutNode = metaNode.get("stdout");
        if (stdoutNode != null && stdoutNode.isTextual()) {
            String text = stdoutNode.asText("");
            if (!text.isBlank()) {
                return text;
            }
        }
        JsonNode stderrNode = metaNode.get("stderr");
        if (stderrNode != null && stderrNode.isTextual()) {
            String text = stderrNode.asText("");
            if (!text.isBlank()) {
                return text;
            }
        }
        JsonNode resultNode = metaNode.get("result");
        if (resultNode != null && !resultNode.isMissingNode() && !resultNode.isNull()) {
            if (resultNode.isTextual()) {
                String text = resultNode.asText("");
                if (!text.isBlank()) {
                    return text;
                }
            } else {
                String json = prettyJson(resultNode);
                if (!json.isBlank() && !"{}".equals(json) && !"[]".equals(json)) {
                    return json;
                }
            }
        }
        JsonNode contentNode = metaNode.get("content");
        if (contentNode != null && contentNode.isTextual()) {
            String text = contentNode.asText("");
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean isDeleteTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        String normalized = toolName.trim().toLowerCase();
        return "delete_file".equals(normalized) || "delete".equals(normalized);
    }

    private boolean isBashTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        String normalized = toolName.trim().toLowerCase();
        return "bash".equals(normalized) || "shell".equals(normalized);
    }

    private boolean isModificationTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        String normalized = toolName.trim().toLowerCase();
        if (isDeleteTool(normalized) || isBashTool(normalized)) {
            return false;
        }
        if ("write".equals(normalized)
                || "edit".equals(normalized)
                || "apply_patch".equals(normalized)
                || "create_file".equals(normalized)
                || "update_file".equals(normalized)
                || "replace_in_file".equals(normalized)
                || "insert".equals(normalized)
                || "append".equals(normalized)
                || "rename_file".equals(normalized)
                || "move_file".equals(normalized)
                || "copy_file".equals(normalized)) {
            return true;
        }
        return normalized.contains("write")
                || normalized.contains("edit")
                || normalized.contains("patch")
                || normalized.contains("replace")
                || normalized.contains("update")
                || normalized.contains("append");
    }

    private boolean isReadTool(String toolName, JsonNode argsNode) {
        if (toolName == null) {
            return false;
        }
        String normalized = toolName.trim().toLowerCase();
        if (isBashTool(normalized) || isDeleteTool(normalized) || isModificationTool(normalized)) {
            return false;
        }
        if ("read".equals(normalized)
                || "ls".equals(normalized)
                || "glob".equals(normalized)
                || "grep".equals(normalized)
                || "search_codebase".equals(normalized)
                || "search".equals(normalized)
                || "list".equals(normalized)
                || "list_dir".equals(normalized)
                || "stat".equals(normalized)) {
            return true;
        }
        if (normalized.contains("read")
                || normalized.contains("list")
                || normalized.contains("search")
                || normalized.contains("find")
                || normalized.contains("grep")
                || normalized.contains("glob")) {
            return true;
        }
        return argsNode != null
                && argsNode.isObject()
                && !firstNonBlank(
                argsNode.path("filePath").asText(""),
                argsNode.path("path").asText("")
        ).isBlank()
                && firstNonBlank(argsNode.path("content").asText("")).isBlank();
    }

    private void updateReadTargetFromArgs(ToolActivityState state, JsonNode argsNode) {
        if (state == null || argsNode == null || !argsNode.isObject()) {
            return;
        }
        String path = firstNonBlank(
                argsNode.path("filePath").asText(""),
                argsNode.path("path").asText(""),
                argsNode.path("target").asText(""),
                argsNode.path("directory").asText("")
        );
        if (path.isBlank()) {
            return;
        }
        state.targetPath = path;
        state.targetLineStart = -1;
        state.targetLineEnd = -1;
        if (argsNode.has("offset") && argsNode.path("offset").canConvertToInt()) {
            int offset = argsNode.path("offset").asInt(-1);
            if (offset >= 0) {
                state.targetLineStart = offset + 1;
                if (argsNode.has("limit") && argsNode.path("limit").canConvertToInt()) {
                    int limit = argsNode.path("limit").asInt(-1);
                    if (limit > 0) {
                        state.targetLineEnd = state.targetLineStart + limit - 1;
                    }
                }
            }
        } else if (argsNode.has("line") && argsNode.path("line").canConvertToInt()) {
            int line = argsNode.path("line").asInt(-1);
            if (line > 0) {
                state.targetLineStart = line;
                state.targetLineEnd = line;
            }
        } else if (argsNode.has("startLine") && argsNode.path("startLine").canConvertToInt()) {
            int startLine = argsNode.path("startLine").asInt(-1);
            if (startLine > 0) {
                state.targetLineStart = startLine;
            }
            if (argsNode.has("endLine") && argsNode.path("endLine").canConvertToInt()) {
                int endLine = argsNode.path("endLine").asInt(-1);
                if (endLine >= startLine && endLine > 0) {
                    state.targetLineEnd = endLine;
                }
            }
        }
        state.targetNavigable = isNavigableFileTarget(path);
    }

    private String resolveExpandedSummary(ToolActivityState state) {
        if (state == null) {
            return "";
        }
        if (state.renderType == ToolRenderType.TERMINAL) {
            String terminalCommand = firstNonBlank(
                    state.pendingCommand != null ? state.pendingCommand.command : "",
                    extractBashCommand(state.lastArgs, state.commandSummary),
                    state.commandSummary
            );
            return trimForUi(terminalCommand, 260);
        }
        if (state.pendingCommand != null && state.pendingCommand.command != null && !state.pendingCommand.command.isBlank()) {
            return trimForUi(state.pendingCommand.command, 200);
        }
        if (state.commandSummary != null && !state.commandSummary.isBlank()) {
            return state.commandSummary;
        }
        return "";
    }

    private boolean requiresUserApproval(ToolActivityState state) {
        if (state == null) {
            return false;
        }
        boolean pendingCommandApproval = state.commandDecisionRequired && !state.commandDecisionMade;
        boolean pendingDeleteApproval = state.deleteDecisionRequired && !state.deleteDecisionMade;
        return pendingCommandApproval || pendingDeleteApproval;
    }

    private String approvalKey(ToolActivityState state) {
        if (state == null) {
            return "";
        }
        String callId = state.callID == null ? "" : state.callID.trim();
        String tool = state.tool == null ? "" : state.tool.trim();
        if (!callId.isBlank()) {
            return callId;
        }
        return tool + "@" + Integer.toHexString(System.identityHashCode(state));
    }

    private void syncApprovalState(ToolActivityState state) {
        if (state == null) {
            return;
        }
        String key = approvalKey(state);
        if (!key.isBlank()) {
            if (requiresUserApproval(state)) {
                pendingApprovalKeys.add(key);
            } else {
                pendingApprovalKeys.remove(key);
            }
        }
        setAwaitingUserApproval(!pendingApprovalKeys.isEmpty(), "Awaiting your approval...");
    }

    private void updateToolDecisionActions(AgentMessageUI ui, ToolActivityState state) {
        if (state == null || state.panel == null) {
            return;
        }
        if (state.commandDecisionRequired && state.pendingCommand != null && !state.commandDecisionMade) {
            state.panel.setDecisionActions(
                    "Approve run",
                    "Skip",
                    true,
                    () -> handleCommandDecision(ui, state, true),
                    () -> handleCommandDecision(ui, state, false),
                    true
            );
            return;
        }
        if (state.deleteDecisionRequired && state.pendingChange != null && !state.deleteDecisionMade) {
            state.panel.setDecisionActions(
                    "Approve delete",
                    "Skip",
                    true,
                    () -> handleDeleteDecision(ui, state, true),
                    () -> handleDeleteDecision(ui, state, false),
                    true
            );
            return;
        }
        state.panel.clearDecisionActions();
    }
    private void handleDeleteDecision(AgentMessageUI ui, ToolActivityState state, boolean approve) {
        if (state == null || state.pendingChange == null || state.panel == null) {
            return;
        }
        state.panel.setDecisionEnabled(false);
        PendingChangesManager.PendingChange change = state.pendingChange;
        Runnable onSuccess = () -> {
            state.deleteDecisionMade = true;
            state.deleteDecisionRequired = false;
            if (approve) {
                state.status = "completed";
                if (state.output == null || state.output.isBlank()) {
                    state.output = "Deletion applied: " + change.path;
                }
                PendingChangesManager.PendingChange applied = normalizeChangeForDirectApply(change);
                recordSessionChange(ui, applied);
            } else {
                state.status = "rejected";
                state.output = "User rejected deletion. File was not deleted: " + change.path;
                if (history != null) {
                    history.appendLine("System: User rejected delete_file for " + change.path);
                }
                addSystemMessage("User rejected delete_file: " + change.path);
                persistSystemUiMessage("User rejected delete_file: " + change.path);
            }
            state.error = "";
            state.panel.clearDecisionActions();
            refreshToolActivityUi(state);
            syncApprovalState(state);
            scrollToBottomSmart();
        };
        Runnable onFailure = () -> {
            state.panel.setDecisionEnabled(true);
            state.status = "failed";
            state.error = approve ? "Failed to apply deletion change." : "Failed to reject deletion change.";
            refreshToolActivityUi(state);
            syncApprovalState(state);
            scrollToBottomSmart();
        };
        if (approve) {
            diffService.confirmChange(change, onSuccess, onFailure);
        } else {
            diffService.revertChange(change, onSuccess, onFailure);
        }
    }

    private void handleCommandDecision(AgentMessageUI ui, ToolActivityState state, boolean approve) {
        if (state == null || state.pendingCommand == null || state.panel == null) {
            return;
        }
        state.panel.setDecisionEnabled(false);
        resolvePendingCommand(state.pendingCommand, !approve, result -> {
            state.commandDecisionMade = true;
            state.commandDecisionRequired = false;
            state.panel.clearDecisionActions();
            if (result.success) {
                if (approve) {
                    state.status = "completed";
                    state.output = result.output == null ? "" : result.output;
                    state.error = "";
                } else {
                    state.status = "rejected";
                    state.output = firstNonBlank(result.output, "User rejected command execution.");
                    state.error = "";
                    if (history != null) {
                        history.appendLine("System: User rejected high-risk command: " + state.pendingCommand.command);
                    }
                    addSystemMessage("User rejected command: " + state.pendingCommand.command);
                    persistSystemUiMessage("User rejected command: " + state.pendingCommand.command);
                }
            } else {
                state.status = "failed";
                state.error = firstNonBlank(result.error, approve ? "Failed to execute approved command." : "Failed to reject command.");
            }
            refreshToolActivityUi(state);
            syncApprovalState(state);
            scrollToBottomSmart();
        });
    }

    private void resolvePendingCommand(
            PendingCommandInfo pendingCommand,
            boolean reject,
            java.util.function.Consumer<PendingCommandResolutionResult> callback
    ) {
        if (pendingCommand == null || pendingCommand.id == null || pendingCommand.id.isBlank()) {
            callback.accept(PendingCommandResolutionResult.failure("Pending command is missing id."));
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                ObjectNode payload = mapper.createObjectNode();
                payload.put("commandId", pendingCommand.id);
                payload.put("traceId", firstNonBlank(pendingCommand.sessionId, currentSessionId));
                payload.put("workspaceRoot", firstNonBlank(pendingCommand.workspaceRoot, workspaceRoot));
                payload.put("reject", reject);

                HttpRequest req = HttpRequest.newBuilder(URI.create(resolvePendingCommandEndpoint()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JsonNode root = mapper.readTree(resp.body());
                String status = root.path("status").asText("");
                String output = root.path("output").asText("");
                String error = root.path("error").asText("");
                boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300 && !"error".equalsIgnoreCase(status);
                PendingCommandResolutionResult result = ok
                        ? PendingCommandResolutionResult.success(status, output)
                        : PendingCommandResolutionResult.failure(firstNonBlank(error, "Pending command resolution failed."));
                SwingUtilities.invokeLater(() -> callback.accept(result));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> callback.accept(PendingCommandResolutionResult.failure(e.getMessage())));
            }
        });
    }

    private String resolvePendingCommandEndpoint() {
        String override = System.getProperty("codeagent.pending.command.endpoint");
        if (override != null && !override.isBlank()) {
            return override;
        }
        String base = System.getProperty("codeagent.endpoint", "http://localhost:8080/api/agent/chat");
        if (base.endsWith("/chat/stream")) {
            base = base.substring(0, base.length() - "/stream".length());
        }
        if (base.endsWith("/chat")) {
            return base.substring(0, base.length() - "/chat".length()) + "/pending-command";
        }
        int idx = base.indexOf("/api/agent");
        if (idx >= 0) {
            return base.substring(0, idx) + "/api/agent/pending-command";
        }
        return base + "/pending-command";
    }

    private String buildToolSummary(ToolActivityState state) {
        if (state == null) {
            return "";
        }
        if (state.renderType == ToolRenderType.TERMINAL) {
            return resolveTerminalSummary(state);
        }
        if (state.renderType == ToolRenderType.MODIFICATION) {
            String targetPath = firstNonBlank(
                    state.pendingChange != null ? state.pendingChange.path : "",
                    state.commandSummary,
                    state.inputSummary
            );
            if (!targetPath.isBlank()) {
                return trimForUi((state.tool == null ? "modify" : state.tool) + ": " + targetPath, 210);
            }
            return "Modified files";
        }
        if (state.renderType == ToolRenderType.READ) {
            String readTarget = formatReadTarget(state);
            if (!readTarget.isBlank()) {
                return trimForUi(readTarget, 210);
            }
            return "Read project context";
        }
        String tool = (state.tool == null || state.tool.isBlank()) ? "tool" : state.tool;
        String summaryIntent = firstNonBlank(state.intentSummary, state.title);
        if (!summaryIntent.isBlank()) {
            return trimForUi(summaryIntent, 210);
        }
        StringBuilder sb = new StringBuilder("Ran ").append(tool);
        if (state.inputSummary != null && !state.inputSummary.isBlank()) {
            sb.append(" ").append(trimForUi(state.inputSummary, 140));
        }
        return trimForUi(sb.toString(), 210);
    }

    private String buildToolSubtitle(ToolActivityState state) {
        if (state == null || state.renderType != ToolRenderType.TERMINAL) {
            return "";
        }
        String commandPreview = resolveTerminalCommandPreview(state, 190);
        if (commandPreview.isBlank()) {
            return "";
        }
        String summary = firstNonBlank(state.uiSummary, resolveTerminalSummary(state));
        if (!summary.isBlank() && summary.equalsIgnoreCase(commandPreview)) {
            return "";
        }
        return commandPreview;
    }

    private String buildToolMeta(ToolActivityState state) {
        if (state != null && state.renderType == ToolRenderType.TERMINAL) {
            return formatDuration(state.durationMs);
        }
        String statusText = normalizeToolStatusLabel(state.status);
        String durationText = formatDuration(state.durationMs);
        if (statusText.isBlank()) {
            return durationText;
        }
        if (durationText.isBlank()) {
            return statusText;
        }
        return statusText + " | " + durationText;
    }

    private String buildToolDetails(ToolActivityState state) {
        if (isBashTool(state.tool)) {
            return buildBashToolDetails(state);
        }
        if (state.renderType == ToolRenderType.READ) {
            StringBuilder readDetails = new StringBuilder();
            readDetails.append("Tool: ").append(state.tool == null ? "read" : state.tool).append('\n');
            String readTarget = formatReadTarget(state);
            if (!readTarget.isBlank()) {
                readDetails.append("Target: ").append(readTarget).append('\n');
            }
            if (state.output != null && !state.output.isBlank()) {
                readDetails.append('\n').append("Output:").append('\n');
                appendPlainBlock(readDetails, state.output);
            }
            if (state.status != null && !state.status.isBlank()) {
                readDetails.append('\n').append("Status: ").append(state.status).append('\n');
            }
            return readDetails.toString().trim();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Tool: ").append(state.tool == null ? "tool" : state.tool).append('\n');
        if (state.intentSummary != null && !state.intentSummary.isBlank()) {
            sb.append("Intent: ").append(state.intentSummary).append('\n');
        }
        if (state.commandSummary != null && !state.commandSummary.isBlank()) {
            sb.append("Target: ").append(state.commandSummary).append('\n');
        }
        if (state.inputDetails != null && !state.inputDetails.isBlank()) {
            sb.append('\n').append("Input:").append('\n');
            appendPlainBlock(sb, state.inputDetails);
        }
        if (state.callID != null && !state.callID.isBlank()) {
            sb.append('\n').append("Call ID: ").append(state.callID).append('\n');
        }
        if (state.durationMs > 0L) {
            sb.append("Duration: ").append(formatDuration(state.durationMs)).append('\n');
        }
        if (state.deleteDecisionRequired && !state.deleteDecisionMade) {
            sb.append('\n').append("Waiting for approval before delete.").append('\n');
        }
        if (state.commandDecisionRequired && !state.commandDecisionMade) {
            sb.append('\n').append("Waiting for approval before execution.").append('\n');
        }
        if (state.output != null && !state.output.isBlank()) {
            sb.append('\n').append("Output:").append('\n');
            appendPlainBlock(sb, state.output);
        } else if (state.status != null && ("completed".equalsIgnoreCase(state.status) || "error".equalsIgnoreCase(state.status))) {
            sb.append('\n').append("Output:").append('\n');
            sb.append("(no detailed output captured)").append('\n');
        }
        if (state.error != null && !state.error.isBlank()) {
            sb.append('\n').append("Error:").append('\n');
            appendPlainBlock(sb, state.error);
        }
        if (state.status != null && !state.status.isBlank()) {
            sb.append('\n').append("Status: ").append(state.status).append('\n');
        }
        return sb.toString().trim();
    }

    private String formatReadTarget(ToolActivityState state) {
        if (state == null) {
            return "";
        }
        String target = firstNonBlank(state.targetPath, state.commandSummary, state.inputSummary);
        if (target.isBlank()) {
            return "";
        }
        if (state.targetLineStart > 0 && state.targetLineEnd >= state.targetLineStart) {
            return target + ":" + state.targetLineStart + "-" + state.targetLineEnd;
        }
        if (state.targetLineStart > 0) {
            return target + ":" + state.targetLineStart;
        }
        return target;
    }

    private String buildBashToolDetails(ToolActivityState state) {
        StringBuilder sb = new StringBuilder();
        String command = firstNonBlank(
                state.pendingCommand != null ? state.pendingCommand.command : "",
                extractBashCommand(state.lastArgs, state.commandSummary),
                state.commandSummary
        );
        if (!command.isBlank()) {
            sb.append("$ ").append(command).append('\n');
        } else {
            sb.append("$ (command unavailable)").append('\n');
        }

        if (state.commandDecisionRequired && !state.commandDecisionMade) {
            sb.append('\n').append("# waiting for approval before execution").append('\n');
            if (state.pendingCommand != null && state.pendingCommand.reasons != null) {
                for (String reason : state.pendingCommand.reasons) {
                    if (reason != null && !reason.isBlank()) {
                        sb.append("# risk: ").append(reason).append('\n');
                    }
                }
            }
        }

        if (state.output != null && !state.output.isBlank()) {
            sb.append('\n');
            appendPlainBlock(sb, state.output);
        }

        if (state.error != null && !state.error.isBlank() && (state.output == null || !state.output.contains(state.error))) {
            sb.append('\n');
            appendPlainBlock(sb, state.error);
        }
        return sb.toString().trim();
    }

    private void refreshToolActivityUi(ToolActivityState state) {
        if (state == null) {
            return;
        }
        state.uiSummary = buildToolSummary(state);
        state.uiExpandedSummary = resolveExpandedSummary(state);
        state.uiMeta = buildToolMeta(state);
        state.uiDetails = buildToolDetails(state);
        String uiSubtitle = buildToolSubtitle(state);

        if (state.panel != null) {
            state.panel.setSummary(state.uiSummary);
            state.panel.setExpandedSummary(state.uiExpandedSummary);
            state.panel.setSubtitle(uiSubtitle);
            state.panel.setMeta(state.uiMeta, colorForToolStatus(state.status));
            state.panel.setDetails(state.uiDetails);
            String executionStatus = state.renderType == ToolRenderType.TERMINAL ? buildTerminalExecutionStatus(state) : "";
            state.panel.setExecutionStatus(executionStatus, colorForToolStatus(state.status));
        }
        if (state.inlineRow != null) {
            state.inlineRow.setSummaryText(state.uiSummary);
            state.inlineRow.setMetaText(state.uiMeta, colorForToolStatus(state.status));
            if (state.renderType == ToolRenderType.READ && state.targetNavigable) {
                state.inlineRow.setAction(true, () -> navigateToReadTarget(state));
            } else {
                state.inlineRow.setAction(false, null);
            }
        }
        refreshInlineDiffCard(state);
    }

    private String extractToolOutput(JsonNode node, JsonNode metaNode) {
        if (node != null && node.has("output")) {
            JsonNode outputNode = node.get("output");
            if (outputNode != null && !outputNode.isMissingNode() && !outputNode.isNull()) {
                if (outputNode.isTextual()) {
                    String text = outputNode.asText("");
                    if (!text.isBlank()) {
                        return text;
                    }
                } else {
                    String json = prettyJson(outputNode);
                    if (!json.isBlank() && !"{}".equals(json) && !"[]".equals(json)) {
                        return json;
                    }
                }
            }
        }
        return extractMetaOutput(metaNode);
    }

    private Integer extractExitCode(JsonNode node, JsonNode metaNode) {
        Integer direct = firstInteger(
                integerField(node, "exit"),
                integerField(node, "exitCode"),
                integerField(node, "exit_code"),
                integerField(metaNode, "exit"),
                integerField(metaNode, "exitCode"),
                integerField(metaNode, "exit_code")
        );
        if (direct != null) {
            return direct;
        }
        if (metaNode != null) {
            JsonNode resultNode = metaNode.get("result");
            Integer nested = firstInteger(
                    integerField(resultNode, "exit"),
                    integerField(resultNode, "exitCode"),
                    integerField(resultNode, "exit_code")
            );
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private Integer integerField(JsonNode node, String field) {
        if (node == null || field == null || field.isBlank()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.canConvertToInt()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            String raw = value.asText("").trim();
            if (raw.isBlank()) {
                return null;
            }
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer firstInteger(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String buildTerminalExecutionStatus(ToolActivityState state) {
        if (state == null) {
            return "";
        }
        Integer exitCode = firstInteger(state.exitCode, inferExitCodeFromText(state.error), inferExitCodeFromText(state.output));
        if (exitCode != null) {
            if (exitCode == 0) {
                return "成功";
            }
            return "退出码 " + exitCode;
        }
        String normalized = state.status == null ? "" : state.status.trim().toLowerCase();
        switch (normalized) {
            case "completed":
            case "success":
            case "ok":
                return "成功";
            case "error":
            case "failed":
            case "failure":
                return "失败";
            case "awaiting_approval":
            case "needs_approval":
                return "等待确认";
            case "rejected":
            case "skipped":
                return "已跳过";
            case "pending":
            case "running":
            case "retry":
                return "运行中";
            default:
                return normalized;
        }
    }

    private Integer inferExitCodeFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = EXIT_CODE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveTerminalTypeLabel(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "Terminal";
        }
        String normalized = toolName.trim().toLowerCase();
        if ("bash".equals(normalized) || normalized.contains("shell")) {
            return "Bash";
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private String resolveTerminalSummary(ToolActivityState state) {
        if (state == null) {
            return "Bash";
        }
        String intent = firstNonBlank(
                state.intentSummary,
                state.pendingCommand != null ? state.pendingCommand.description : "",
                state.title
        );
        if (!intent.isBlank()) {
            return trimForUi(intent, 100);
        }
        String commandPreview = resolveTerminalCommandPreview(state, 110);
        if (!commandPreview.isBlank()) {
            return commandPreview;
        }
        return resolveTerminalTypeLabel(state.tool);
    }

    private String resolveTerminalCommandPreview(ToolActivityState state, int maxLen) {
        if (state == null) {
            return "";
        }
        String command = firstNonBlank(
                state.pendingCommand != null ? state.pendingCommand.command : "",
                extractBashCommand(state.lastArgs, state.commandSummary),
                state.commandSummary,
                state.uiExpandedSummary
        );
        if (command.isBlank()) {
            return "";
        }
        String compact = compactCommandForPreview(command);
        return trimForUi(compact, Math.max(40, maxLen));
    }

    private String compactCommandForPreview(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String compact = command.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
        String lower = compact.toLowerCase();

        int commandIdx = lower.indexOf(" -command ");
        if (commandIdx > 0) {
            String tail = compact.substring(commandIdx + " -command ".length()).trim();
            String unwrapped = unwrapSingleLayerQuotes(tail);
            if (!unwrapped.isBlank()) {
                compact = unwrapped;
                lower = compact.toLowerCase();
            }
        }

        if (lower.startsWith("bash -lc ")) {
            String tail = compact.substring("bash -lc ".length()).trim();
            String unwrapped = unwrapSingleLayerQuotes(tail);
            if (!unwrapped.isBlank()) {
                compact = unwrapped;
            }
        } else if (lower.startsWith("cmd.exe /c ")) {
            compact = compact.substring("cmd.exe /c ".length()).trim();
        }
        return compact;
    }

    private String unwrapSingleLayerQuotes(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return text.substring(1, text.length() - 1).trim();
            }
        }
        return text;
    }

    private void navigateToReadTarget(ToolActivityState state) {
        if (state == null || state.targetPath == null || state.targetPath.isBlank()) {
            return;
        }
        Path target = resolveWorkspaceFilePath(state.targetPath);
        if (target == null || !Files.isRegularFile(target)) {
            return;
        }
        String normalized = target.toAbsolutePath().normalize().toString().replace('\\', '/');
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized);
        if (file == null || file.isDirectory()) {
            return;
        }
        int line = state.targetLineStart > 0 ? state.targetLineStart - 1 : 0;
        OpenFileDescriptor descriptor = new OpenFileDescriptor(project, file, Math.max(0, line), 0);
        descriptor.navigate(true);
    }

    private boolean isNavigableFileTarget(String rawPath) {
        Path target = resolveWorkspaceFilePath(rawPath);
        return target != null && Files.isRegularFile(target);
    }

    private Path resolveWorkspaceFilePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        Path parsed = parseAnyPath(rawPath);
        if (parsed == null) {
            return null;
        }
        Path root = parseAnyPath(workspaceRoot);
        Path absolute = parsed;
        if (!absolute.isAbsolute()) {
            if (root == null) {
                return null;
            }
            absolute = root.resolve(parsed);
        }
        absolute = absolute.toAbsolutePath().normalize();
        if (root != null) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (!isUnderWorkspaceRoot(absolute, normalizedRoot)) {
                return null;
            }
        }
        if (Files.isDirectory(absolute)) {
            return null;
        }
        return absolute;
    }

    private Path parseAnyPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String normalized = rawPath.trim();
        if (normalized.matches("^/[a-zA-Z]/.*")) {
            char drive = Character.toUpperCase(normalized.charAt(1));
            normalized = drive + ":" + normalized.substring(2);
        }
        try {
            return Path.of(normalized);
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private boolean isUnderWorkspaceRoot(Path target, Path root) {
        if (target == null || root == null) {
            return false;
        }
        if (target.startsWith(root)) {
            return true;
        }
        String targetText = target.toString().replace('\\', '/').toLowerCase();
        String rootText = root.toString().replace('\\', '/').toLowerCase();
        return targetText.startsWith(rootText);
    }

    private void appendPlainBlock(StringBuilder sb, String raw) {
        if (sb == null || raw == null || raw.isBlank()) {
            return;
        }
        String normalized = raw.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            sb.append(line).append('\n');
        }
    }

    private String prettyJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception ignored) {
            return node.toString();
        }
    }

    private String summarizeInputDetails(JsonNode argsNode) {
        if (argsNode == null || argsNode.isMissingNode() || argsNode.isNull()) {
            return "";
        }
        if (argsNode.isTextual()) {
            return argsNode.asText("");
        }
        if (!argsNode.isObject()) {
            return trimForUi(argsNode.toString(), 600);
        }
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = argsNode.fields();
        int count = 0;
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode valueNode = field.getValue();
            String value;
            if (valueNode == null || valueNode.isNull()) {
                value = "null";
            } else if (valueNode.isTextual()) {
                value = valueNode.asText("");
            } else if (valueNode.isNumber() || valueNode.isBoolean()) {
                value = valueNode.asText();
            } else if (valueNode.isArray()) {
                value = "[array size=" + valueNode.size() + "]";
            } else if (valueNode.isObject()) {
                value = "{object}";
            } else {
                value = valueNode.toString();
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(field.getKey()).append(": ").append(trimForUi(value, 260));
            count++;
            if (count >= 12 && fields.hasNext()) {
                sb.append("\n... (more args omitted)");
                break;
            }
        }
        return sb.toString();
    }

    private String summarizeArgs(JsonNode argsNode) {
        if (argsNode == null || argsNode.isMissingNode() || argsNode.isNull()) {
            return "";
        }
        if (argsNode.isObject()) {
            String command = argsNode.path("command").asText("");
            if (!command.isBlank()) {
                return trimForUi(command, 140);
            }
            String description = argsNode.path("description").asText("");
            if (!description.isBlank()) {
                return trimForUi(description, 140);
            }
            String filePath = argsNode.path("filePath").asText(argsNode.path("path").asText(""));
            if (!filePath.isBlank()) {
                return trimForUi(filePath, 140);
            }
        }
        if (!argsNode.isObject()) {
            return trimForUi(argsNode.asText(""), 160);
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = argsNode.fields();
        while (fields.hasNext() && count < 3) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String value = field.getValue() == null ? "" : field.getValue().asText(field.getValue().toString());
            sb.append(field.getKey()).append("=").append(trimForUi(value, 40));
            count++;
        }
        return sb.toString();
    }

    private String trimForUi(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen - 3)).trim() + "...";
    }

    private String summarizeCommand(JsonNode argsNode, String fallback) {
        if (argsNode == null || argsNode.isMissingNode() || argsNode.isNull()) {
            return fallback == null ? "" : fallback;
        }
        if (argsNode.isObject()) {
            String command = argsNode.path("command").asText(argsNode.path("cmd").asText(""));
            if (!command.isBlank()) {
                return trimForUi(command, 180);
            }

            String filePath = argsNode.path("filePath").asText(argsNode.path("path").asText(""));
            String pattern = argsNode.path("pattern").asText("");
            if (!filePath.isBlank() && !pattern.isBlank()) {
                return trimForUi(filePath + " pattern=" + pattern, 180);
            }
            if (!filePath.isBlank()) {
                return trimForUi(filePath, 180);
            }

            String query = argsNode.path("query").asText("");
            if (!query.isBlank()) {
                return trimForUi(query, 180);
            }
        }
        if (!argsNode.isObject()) {
            String text = argsNode.asText("");
            if (!text.isBlank()) {
                return trimForUi(text, 180);
            }
        }
        return fallback == null ? "" : trimForUi(fallback, 180);
    }

    private String extractBashCommand(JsonNode argsNode, String fallback) {
        if (argsNode != null && argsNode.isObject()) {
            String command = firstNonBlank(
                    argsNode.path("command").asText(""),
                    argsNode.path("cmd").asText("")
            );
            if (!command.isBlank()) {
                return command;
            }
        }
        return firstNonBlank(fallback);
    }

    private Color colorForToolStatus(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase();
        if ("completed".equals(normalized) || "success".equals(normalized) || "ok".equals(normalized)) {
            return new JBColor(new Color(0x1B5E20), new Color(0x81C784));
        }
        if ("awaiting_approval".equals(normalized) || "needs_approval".equals(normalized)) {
            return new JBColor(new Color(0xF57F17), new Color(0xFFE082));
        }
        if ("rejected".equals(normalized) || "skipped".equals(normalized)) {
            return new JBColor(new Color(0xE65100), new Color(0xFFCC80));
        }
        if ("error".equals(normalized) || "failed".equals(normalized) || "failure".equals(normalized)) {
            return new JBColor(new Color(0xB71C1C), new Color(0xEF9A9A));
        }
        if ("running".equals(normalized) || "pending".equals(normalized) || "retry".equals(normalized)) {
            return new JBColor(new Color(0x0D47A1), new Color(0x90CAF9));
        }
        return UIUtil.getContextHelpForeground();
    }

    private String normalizeToolStatusLabel(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        String normalized = status.trim().toLowerCase();
        switch (normalized) {
            case "completed":
            case "success":
            case "ok":
                return "done";
            case "error":
            case "failed":
            case "failure":
                return "failed";
            case "rejected":
            case "skipped":
                return "rejected";
            case "awaiting_approval":
            case "needs_approval":
                return "waiting approval";
            case "pending":
            case "running":
            case "retry":
                return "running";
            default:
                return normalized;
        }
    }

    private String formatDuration(long durationMs) {
        if (durationMs <= 0L) {
            return "";
        }
        if (durationMs < 1000L) {
            return durationMs + "ms";
        }
        long seconds = durationMs / 1000L;
        long millisRemainder = durationMs % 1000L;
        if (seconds < 60L) {
            long tenths = millisRemainder / 100L;
            return seconds + "." + tenths + "s";
        }
        long minutes = seconds / 60L;
        long secondsRemainder = seconds % 60L;
        return minutes + "m " + secondsRemainder + "s";
    }

    private void appendAssistantText(AgentMessageUI ui, String text) {
        if (ui == null || ui.answerPane == null || text == null || text.isEmpty()) {
            return;
        }
        if (text.equals(ui.lastAppendedChunk) && text.length() > 2) {
            return;
        }
        ui.lastAppendedChunk = text;
        if (ui.thinkingOpen) {
            ui.deferredAnswerBuffer.append(text);
            return;
        }
        ui.answerBuffer.append(text);
        ui.answerPane.setText(MarkdownUtils.renderToHtml(ui.answerBuffer.toString()));
        scrollToBottomSmart();
    }

    private void syncAssistantTextFromSnapshot(AgentMessageUI ui, String fullText) {
        if (ui == null || ui.answerPane == null || fullText == null || fullText.isBlank()) {
            return;
        }
        String existing = ui.answerBuffer == null ? "" : ui.answerBuffer.toString();
        if (existing.equals(fullText)) {
            return;
        }
        if (!existing.isBlank()) {
            if (fullText.startsWith(existing)) {
                String tail = fullText.substring(existing.length());
                if (!tail.isBlank()) {
                    appendAssistantText(ui, tail);
                }
                return;
            }
            if (existing.startsWith(fullText) || existing.contains(fullText)) {
                return;
            }
        }
        ui.answerBuffer.setLength(0);
        ui.answerBuffer.append(fullText);
        ui.answerPane.setText(MarkdownUtils.renderToHtml(fullText));
        scrollToBottomSmart();
    }

    private void flushDeferredAnswer(AgentMessageUI ui) {
        if (ui == null || ui.deferredAnswerBuffer.length() == 0) {
            return;
        }
        ui.answerBuffer.append(ui.deferredAnswerBuffer);
        ui.deferredAnswerBuffer.setLength(0);
        if (ui.answerPane != null) {
            ui.answerPane.setText(MarkdownUtils.renderToHtml(ui.answerBuffer.toString()));
            scrollToBottomSmart();
        }
    }

    private void finalizePendingAssistantResponses(Map<String, AgentMessageUI> assistantUiByMessageID) {
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            Set<AgentMessageUI> unique = new LinkedHashSet<>(assistantUiByMessageID.values());
            for (AgentMessageUI ui : unique) {
                if (ui == null || ui.streamFinished) {
                    continue;
                }
                ui.thinkingOpen = false;
                flushDeferredAnswer(ui);
                if (ui.answerPane != null && ui.answerBuffer.length() > 0) {
                    ui.answerPane.setText(MarkdownUtils.renderToHtml(ui.answerBuffer.toString()));
                }
                if (history != null && !ui.historyCommitted && ui.answerBuffer.length() > 0) {
                    history.appendLine("Agent: " + ui.answerBuffer);
                    ui.historyCommitted = true;
                }
                ui.streamFinished = true;
                appendSessionChangeSummaryIfNeeded(ui);
                persistAssistantUiSnapshot(ui);
            }
            scrollToBottomSmart();
        });
    }

    private void persistUserUiMessage(String text) {
        if (history == null || text == null || text.isBlank()) {
            return;
        }
        ChatHistoryService.UiMessage message = new ChatHistoryService.UiMessage();
        message.role = "user";
        message.text = text;
        message.timestamp = System.currentTimeMillis();
        history.appendUiMessage(message);
    }

    private void persistSystemUiMessage(String text) {
        if (history == null || text == null || text.isBlank()) {
            return;
        }
        ChatHistoryService.UiMessage message = new ChatHistoryService.UiMessage();
        message.role = "system";
        message.text = text;
        message.timestamp = System.currentTimeMillis();
        history.appendUiMessage(message);
    }

    private String createAssistantUiPlaceholder() {
        if (history == null) {
            return "";
        }
        ChatHistoryService.UiMessage message = new ChatHistoryService.UiMessage();
        message.role = "assistant";
        message.text = "";
        message.thought = "";
        message.timestamp = System.currentTimeMillis();
        return history.appendUiMessage(message);
    }

    private void persistAssistantUiSnapshot(AgentMessageUI ui) {
        if (history == null || ui == null) {
            return;
        }
        ChatHistoryService.UiMessage message = new ChatHistoryService.UiMessage();
        message.id = ui.historyUiMessageId;
        message.role = "assistant";
        message.messageID = ui.messageID;
        message.text = ui.answerBuffer == null ? "" : ui.answerBuffer.toString();
        message.timestamp = System.currentTimeMillis();
        if (ui.thoughtPanel != null) {
            message.thought = ui.thoughtPanel.getContent();
        } else {
            message.thought = "";
        }
        if (ui.toolActivities != null && !ui.toolActivities.isEmpty()) {
            for (ToolActivityState state : ui.toolActivities.values()) {
                if (state == null) {
                    continue;
                }
                ChatHistoryService.ToolActivity activity = new ChatHistoryService.ToolActivity();
                activity.callID = firstNonBlank(state.callID);
                activity.tool = firstNonBlank(state.tool);
                activity.renderType = state.renderType == null ? ToolRenderType.GENERIC.name() : state.renderType.name();
                activity.targetPath = firstNonBlank(state.targetPath);
                activity.lineStart = state.targetLineStart;
                activity.lineEnd = state.targetLineEnd;
                activity.navigable = state.targetNavigable;
                if (state.panel != null) {
                    activity.summary = firstNonBlank(state.panel.getSummaryText(), state.uiSummary, buildToolSummary(state));
                    activity.expandedSummary = firstNonBlank(state.panel.getExpandedSummaryText(), state.uiExpandedSummary, resolveExpandedSummary(state));
                    activity.meta = firstNonBlank(state.panel.getMetaText(), state.uiMeta, buildToolMeta(state));
                    activity.details = firstNonBlank(state.panel.getDetailsText(), state.uiDetails, buildToolDetails(state));
                } else {
                    activity.summary = firstNonBlank(state.uiSummary, buildToolSummary(state));
                    activity.expandedSummary = firstNonBlank(state.uiExpandedSummary, resolveExpandedSummary(state));
                    activity.meta = firstNonBlank(state.uiMeta, buildToolMeta(state));
                    activity.details = firstNonBlank(state.uiDetails, buildToolDetails(state));
                }
                activity.status = firstNonBlank(state.status);
                activity.durationMs = state.durationMs;
                message.toolActivities.add(activity);
            }
        }

        if (ui.historyUiMessageId == null || ui.historyUiMessageId.isBlank()) {
            ui.historyUiMessageId = history.appendUiMessage(message);
        } else {
            history.upsertUiMessage(message);
        }
    }
    
    private void addSystemMessage(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(5));
        JLabel label = new JLabel("<html><i>" + text + "</i></html>");
        label.setForeground(JBColor.GRAY);
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
        ui.messagePanel = messagePanel;
        
        if (isUser) {
            JPanel bubble = new JPanel(new BorderLayout());
            bubble.setBackground(new JBColor(new Color(230, 240, 255), new Color(66, 73, 86)));
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
            // 1. Thinking Process (Placeholder, will be created on event)
            boolean hasThought = response != null && response.thought != null && !response.thought.isEmpty();
            if (hasThought) {
                String initialThought = response.thought;
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
            
            if (answerText != null) {
                ui.answerBuffer.append(answerText);
            }
            
            messagePanel.add(content);
            ui.answerPane = content;
            
            // 3. Embedded File Changes
            if (response != null && response.changes != null && !response.changes.isEmpty()) {
                for (PendingChangesManager.PendingChange change : response.changes) {
                    applyAndRecordSessionChange(ui, null, change);
                }
                appendSessionChangeSummaryIfNeeded(ui);
            }
        }
        
        conversationList.add(messagePanel);
        conversationList.add(new JSeparator());
        
        conversationList.revalidate();
        conversationList.repaint();
        scrollToBottom();
        return ui;
    }

    private void applyAndRecordSessionChange(
            AgentMessageUI ui,
            ToolActivityState state,
            PendingChangesManager.PendingChange rawChange
    ) {
        if (ui == null || rawChange == null) {
            return;
        }
        PendingChangesManager.PendingChange change = normalizeChangeForDirectApply(rawChange);
        if (change == null || change.path == null || change.path.isBlank()) {
            return;
        }
        if ("DELETE".equalsIgnoreCase(change.type) && state != null && !state.deleteDecisionMade) {
            return;
        }

        String fingerprint = buildSessionChangeFingerprint(change);
        if (!ui.appliedChangeFingerprints.contains(fingerprint) && !"DELETE".equalsIgnoreCase(change.type)) {
            diffService.applyChange(change);
            ui.appliedChangeFingerprints.add(fingerprint);
        }
        if (state != null) {
            state.appliedChangeFingerprint = fingerprint;
            state.pendingChange = change;
            refreshInlineDiffCard(state);
        }
        recordSessionChange(ui, change);
    }

    private void recordSessionChange(AgentMessageUI ui, PendingChangesManager.PendingChange rawChange) {
        if (ui == null || rawChange == null || rawChange.path == null || rawChange.path.isBlank()) {
            return;
        }
        PendingChangesManager.PendingChange change = normalizeChangeForDirectApply(rawChange);
        if (change == null) {
            return;
        }
        String key = sessionChangeKey(change);
        PendingChangesManager.PendingChange existing = ui.sessionChanges.get(key);
        if (existing == null) {
            ui.sessionChanges.put(key, copyPendingChange(change));
            return;
        }
        String mergedOld = firstNonBlank(existing.oldContent, change.oldContent);
        String mergedNew = firstNonBlank(change.newContent, existing.newContent);
        String mergedType = mergeChangeType(existing.type, change.type);
        PendingChangesManager.PendingChange merged = new PendingChangesManager.PendingChange(
                firstNonBlank(change.id, existing.id, java.util.UUID.randomUUID().toString()),
                firstNonBlank(change.path, existing.path),
                mergedType,
                mergedOld,
                mergedNew,
                firstNonBlank(change.preview, existing.preview),
                Math.max(change.timestamp, existing.timestamp),
                firstNonBlank(change.workspaceRoot, existing.workspaceRoot, workspaceRoot),
                firstNonBlank(change.sessionId, existing.sessionId, currentSessionId)
        );
        ui.sessionChanges.put(key, merged);
    }

    private PendingChangesManager.PendingChange normalizeChangeForDirectApply(PendingChangesManager.PendingChange source) {
        if (source == null || source.path == null || source.path.isBlank()) {
            return null;
        }
        String path = firstNonBlank(source.path);
        String workspace = firstNonBlank(source.workspaceRoot, workspaceRoot);
        String sessionId = firstNonBlank(source.sessionId, currentSessionId);
        String preview = firstNonBlank(source.preview);
        long timestamp = source.timestamp > 0L ? source.timestamp : System.currentTimeMillis();
        String id = firstNonBlank(source.id, java.util.UUID.randomUUID().toString());

        String oldContent = firstNonBlank(source.oldContent);
        String newContent = firstNonBlank(source.newContent);
        String normalizedType = firstNonBlank(source.type, "EDIT").trim().toUpperCase();

        Path absolute = resolvePathForChange(path, workspace);
        boolean exists = absolute != null && Files.exists(absolute) && Files.isRegularFile(absolute);

        if ("EDIT".equals(normalizedType) || "CREATE".equals(normalizedType)) {
            if (oldContent.isBlank() && exists) {
                try {
                    oldContent = Files.readString(absolute, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    oldContent = "";
                }
            }
            if ("EDIT".equals(normalizedType) && !exists) {
                normalizedType = "CREATE";
            }
            if ("CREATE".equals(normalizedType) && exists && !oldContent.isBlank()) {
                normalizedType = "EDIT";
            }
        }
        if ("DELETE".equals(normalizedType) && oldContent.isBlank() && exists) {
            try {
                oldContent = Files.readString(absolute, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                oldContent = "";
            }
        }
        return new PendingChangesManager.PendingChange(
                id,
                path,
                normalizedType,
                oldContent,
                newContent,
                preview,
                timestamp,
                workspace,
                sessionId
        );
    }

    private PendingChangesManager.PendingChange copyPendingChange(PendingChangesManager.PendingChange source) {
        if (source == null) {
            return null;
        }
        return new PendingChangesManager.PendingChange(
                firstNonBlank(source.id, java.util.UUID.randomUUID().toString()),
                firstNonBlank(source.path),
                firstNonBlank(source.type, "EDIT"),
                firstNonBlank(source.oldContent),
                firstNonBlank(source.newContent),
                firstNonBlank(source.preview),
                source.timestamp > 0L ? source.timestamp : System.currentTimeMillis(),
                firstNonBlank(source.workspaceRoot, workspaceRoot),
                firstNonBlank(source.sessionId, currentSessionId)
        );
    }

    private String sessionChangeKey(PendingChangesManager.PendingChange change) {
        if (change == null) {
            return "";
        }
        return normalizePendingPath(change.path, change.workspaceRoot).toLowerCase();
    }

    private String buildSessionChangeFingerprint(PendingChangesManager.PendingChange change) {
        if (change == null) {
            return "";
        }
        String normalizedPath = sessionChangeKey(change);
        String type = firstNonBlank(change.type, "EDIT").toUpperCase();
        int oldHash = firstNonBlank(change.oldContent).hashCode();
        int newHash = firstNonBlank(change.newContent).hashCode();
        return normalizedPath + "|" + type + "|" + oldHash + "|" + newHash;
    }

    private String mergeChangeType(String existingType, String latestType) {
        String existing = firstNonBlank(existingType, "EDIT").toUpperCase();
        String latest = firstNonBlank(latestType, "EDIT").toUpperCase();
        if ("DELETE".equals(latest) || "DELETE".equals(existing)) {
            return "DELETE";
        }
        if ("CREATE".equals(existing)) {
            return "CREATE";
        }
        return latest;
    }

    private Path resolvePathForChange(String path, String wsRoot) {
        Path raw = parseAnyPath(path);
        if (raw == null) {
            return null;
        }
        if (raw.isAbsolute()) {
            return raw.normalize();
        }
        Path root = parseAnyPath(firstNonBlank(wsRoot, workspaceRoot));
        if (root == null) {
            return raw.toAbsolutePath().normalize();
        }
        return root.resolve(raw).normalize();
    }

    private void appendSessionChangeSummaryIfNeeded(AgentMessageUI ui) {
        if (ui == null || ui.sessionChangeSummaryShown || ui.sessionChanges == null || ui.sessionChanges.isEmpty()) {
            return;
        }
        JPanel summaryPanel = createSessionChangeSummaryPanel(ui);
        if (summaryPanel == null) {
            return;
        }
        ui.messagePanel.add(Box.createVerticalStrut(10));
        ui.messagePanel.add(summaryPanel);
        ui.sessionChangeSummaryPanel = summaryPanel;
        ui.sessionChangeSummaryShown = true;
        ui.messagePanel.revalidate();
        ui.messagePanel.repaint();
        scrollToBottomSmart();
    }

    private JPanel createSessionChangeSummaryPanel(AgentMessageUI ui) {
        if (ui == null || ui.sessionChanges == null || ui.sessionChanges.isEmpty()) {
            return null;
        }
        List<PendingChangesManager.PendingChange> changes = new ArrayList<>(ui.sessionChanges.values());
        int totalAdded = 0;
        int totalRemoved = 0;
        for (PendingChangesManager.PendingChange change : changes) {
            LineDiffStat stat = computeLineDiffStat(change.oldContent, change.newContent);
            totalAdded += stat.added;
            totalRemoved += stat.removed;
        }

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Session Changes"));
        panel.setOpaque(false);

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        JLabel title = new JLabel(changes.size() + " files changed  +" + totalAdded + "  -" + totalRemoved);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setForeground(UIUtil.getLabelForeground());
        JButton undoAllBtn = new JButton("Undo All");
        undoAllBtn.setFocusable(false);
        header.add(title, BorderLayout.WEST);
        header.add(undoAllBtn, BorderLayout.EAST);
        panel.add(header);
        panel.add(Box.createVerticalStrut(6));

        List<JButton> rowUndoButtons = new ArrayList<>();
        for (PendingChangesManager.PendingChange change : changes) {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            row.setBorder(JBUI.Borders.empty(4));

            JPanel textPanel = new JPanel();
            textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
            textPanel.setOpaque(false);

            JLabel nameLabel = new JLabel(shortFileName(change.path));
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
            JLabel pathLabel = new JLabel(firstNonBlank(change.path));
            pathLabel.setForeground(JBColor.GRAY);
            pathLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));
            textPanel.add(nameLabel);
            textPanel.add(pathLabel);

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            actions.setOpaque(false);

            LineDiffStat stat = computeLineDiffStat(change.oldContent, change.newContent);
            JLabel statLabel = new JLabel("+" + stat.added + "  -" + stat.removed);
            statLabel.setForeground(new JBColor(new Color(36, 124, 68), new Color(95, 190, 124)));

            JButton reviewBtn = new JButton("Review");
            reviewBtn.setFocusable(false);
            reviewBtn.addActionListener(e -> diffService.showDiffExplicit(change.path, change.oldContent, change.newContent));

            JButton undoBtn = new JButton("Undo");
            undoBtn.setFocusable(false);
            String changeKey = sessionChangeKey(change);
            if (ui.undoneSessionChangeKeys.contains(changeKey)) {
                undoBtn.setEnabled(false);
            }
            undoBtn.addActionListener(e -> {
                if (undoSessionChange(change)) {
                    ui.undoneSessionChangeKeys.add(changeKey);
                    undoBtn.setEnabled(false);
                }
            });
            rowUndoButtons.add(undoBtn);

            actions.add(statLabel);
            actions.add(reviewBtn);
            actions.add(undoBtn);

            row.add(textPanel, BorderLayout.CENTER);
            row.add(actions, BorderLayout.EAST);
            panel.add(row);
        }

        undoAllBtn.addActionListener(e -> {
            for (PendingChangesManager.PendingChange change : changes) {
                String changeKey = sessionChangeKey(change);
                if (ui.undoneSessionChangeKeys.contains(changeKey)) {
                    continue;
                }
                if (undoSessionChange(change)) {
                    ui.undoneSessionChangeKeys.add(changeKey);
                }
            }
            for (JButton button : rowUndoButtons) {
                button.setEnabled(false);
            }
            undoAllBtn.setEnabled(false);
        });
        if (changes.isEmpty()) {
            undoAllBtn.setEnabled(false);
        }
        return panel;
    }

    private boolean undoSessionChange(PendingChangesManager.PendingChange change) {
        PendingChangesManager.PendingChange undo = buildUndoChange(change);
        if (undo == null) {
            return false;
        }
        diffService.applyChange(undo);
        return true;
    }

    private PendingChangesManager.PendingChange buildUndoChange(PendingChangesManager.PendingChange change) {
        if (change == null || change.path == null || change.path.isBlank()) {
            return null;
        }
        String type = firstNonBlank(change.type, "EDIT").toUpperCase();
        String undoType;
        String undoNewContent;
        if ("DELETE".equals(type)) {
            undoType = "CREATE";
            undoNewContent = firstNonBlank(change.oldContent);
        } else if ("CREATE".equals(type)) {
            undoType = "DELETE";
            undoNewContent = "";
        } else {
            undoType = "EDIT";
            undoNewContent = firstNonBlank(change.oldContent);
        }
        return new PendingChangesManager.PendingChange(
                java.util.UUID.randomUUID().toString(),
                change.path,
                undoType,
                firstNonBlank(change.newContent),
                undoNewContent,
                "undo",
                System.currentTimeMillis(),
                firstNonBlank(change.workspaceRoot, workspaceRoot),
                firstNonBlank(change.sessionId, currentSessionId)
        );
    }

    private JPanel createChangesPanel(List<PendingChangesManager.PendingChange> changes) {
        JPanel changesPanel = new JPanel();
        changesPanel.setLayout(new BoxLayout(changesPanel, BoxLayout.Y_AXIS));
        changesPanel.setBorder(BorderFactory.createTitledBorder("Modified Files"));

        for (PendingChangesManager.PendingChange change : changes) {
            changesPanel.add(createChangeRowCard(change, "View Changes"));
        }

        return changesPanel;
    }

    private JPanel createChangeRowCard(PendingChangesManager.PendingChange change, String actionLabel) {
        JPanel item = new JPanel(new BorderLayout(8, 0));
        item.setOpaque(false);
        item.setBorder(JBUI.Borders.empty(4));

        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setOpaque(false);

        JLabel name = new JLabel(shortFileName(change.path));
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        JLabel path = new JLabel(change.path != null ? change.path : "");
        path.setForeground(JBColor.GRAY);
        path.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));

        textPanel.add(name);
        textPanel.add(path);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightPanel.setOpaque(false);

        LineDiffStat stat = computeLineDiffStat(change.oldContent, change.newContent);
        JLabel statLabel = new JLabel("+" + stat.added + "  -" + stat.removed);
        statLabel.setForeground(new JBColor(new Color(36, 124, 68), new Color(95, 190, 124)));

        JButton viewBtn = new JButton((actionLabel == null || actionLabel.isBlank()) ? "Show Diff" : actionLabel);
        viewBtn.setFocusable(false);
        viewBtn.addActionListener(e -> diffService.showDiffExplicit(change.path, change.oldContent, change.newContent));

        rightPanel.add(statLabel);
        rightPanel.add(viewBtn);

        item.add(textPanel, BorderLayout.CENTER);
        item.add(rightPanel, BorderLayout.EAST);
        return item;
    }

    private String shortFileName(String path) {
        if (path == null || path.isBlank()) {
            return "(unknown)";
        }
        try {
            return Path.of(path).getFileName().toString();
        } catch (Exception e) {
            return path;
        }
    }

    private LineDiffStat computeLineDiffStat(String oldContent, String newContent) {
        String[] oldLines = splitLines(oldContent);
        String[] newLines = splitLines(newContent);
        int n = oldLines.length;
        int m = newLines.length;

        if (n == 0 && m == 0) {
            return new LineDiffStat(0, 0);
        }
        if (n == 0) {
            return new LineDiffStat(m, 0);
        }
        if (m == 0) {
            return new LineDiffStat(0, n);
        }

        long complexity = (long) n * (long) m;
        if (complexity > 240000L) {
            return new LineDiffStat(Math.max(0, m - n), Math.max(0, n - m));
        }

        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];

        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (oldLines[i - 1].equals(newLines[j - 1])) {
                    curr[j] = prev[j - 1] + 1;
                } else {
                    curr[j] = Math.max(curr[j - 1], prev[j]);
                }
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
            java.util.Arrays.fill(curr, 0);
        }

        int lcs = prev[m];
        return new LineDiffStat(Math.max(0, m - lcs), Math.max(0, n - lcs));
    }

    private String[] splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return new String[0];
        }
        return content.split("\\R", -1);
    }
    private void typewriterEffect(JEditorPane pane, String fullMarkdown) {
        // ... (Keep existing implementation) ...
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
            pane.setText(MarkdownUtils.renderToHtml(partial));
            scrollToBottom();
        });
        timer.start();
    }

    private void initPendingChangesList() {
        pendingChangesList = new JPanel();
        pendingChangesList.setLayout(new BoxLayout(pendingChangesList, BoxLayout.Y_AXIS));

        commitAllButton = new JButton("Commit All");
        commitAllButton.addActionListener(e -> {
            List<PendingChangesManager.PendingChange> snapshot = new ArrayList<>(pendingChanges);
            for (PendingChangesManager.PendingChange change : snapshot) {
                diffService.confirmChange(change, () -> {
                    pendingChanges.removeIf(pc -> samePendingChange(pc, change));
                    refreshPendingChangesPanel();
                }, null);
            }
        });
    }

    private void showPendingChangesPopup() {
        if (pendingChanges.isEmpty()) return;
        
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(JBUI.Borders.empty(10));
        
        JBScrollPane scroll = new JBScrollPane(pendingChangesList);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        
        // Dynamic height estimation
        int height = Math.min(400, Math.max(100, pendingChanges.size() * 50 + 50));
        scroll.setPreferredSize(new Dimension(350, height));
        
        content.add(scroll, BorderLayout.CENTER);
        content.add(commitAllButton, BorderLayout.SOUTH);
        
        activePendingPopup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, null)
                .setTitle("Pending Changes")
                .setMovable(true)
                .setResizable(true)
                .setRequestFocus(true)
                .setCancelOnClickOutside(true)
                .createPopup();
        
        activePendingPopup.showUnderneathOf(pendingChangesToggle);
    }

    private void addPendingChanges(List<PendingChangesManager.PendingChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (PendingChangesManager.PendingChange change : changes) {
            if (pendingChanges.stream().noneMatch(existing -> samePendingChange(existing, change))) {
                pendingChanges.add(change);
                changed = true;
            }
        }
        if (changed) {
            refreshPendingChangesPanel();
        }
    }

    private void removePendingChange(PendingChangesManager.PendingChange change) {
        if (change == null) {
            return;
        }
        pendingChanges.removeIf(existing -> samePendingChange(existing, change));
        refreshPendingChangesPanel();
    }

    private void refreshPendingChangesPanel() {
        int count = pendingChanges.size();
        if (pendingChangesToggle != null) {
            pendingChangesToggle.setText("Pending (" + count + ")");
            pendingChangesToggle.setVisible(count > 0);
        }

        if (pendingChangesList == null) {
            return;
        }
        pendingChangesList.removeAll();

        for (PendingChangesManager.PendingChange change : pendingChanges) {
            JPanel item = new JPanel(new BorderLayout(5, 0));
            
            // Extract filename and path
            String fullPath = change.path;
            String fileName = fullPath;
            try {
                fileName = Path.of(fullPath).getFileName().toString();
            } catch (Exception e) {
                // fallback
            }

            JPanel textPanel = new JPanel(new GridLayout(2, 1));
            textPanel.setOpaque(false);
            
            JLabel nameLabel = new JLabel(fileName);
            // nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD)); 
            
            JLabel pathLabel = new JLabel(fullPath);
            pathLabel.setForeground(JBColor.GRAY);
            pathLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));
            
            textPanel.add(nameLabel);
            textPanel.add(pathLabel);

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            actions.setOpaque(false);
            
            JButton undoBtn = new JButton("Undo");
            JButton confirmBtn = new JButton("Apply");
            
            undoBtn.setToolTipText("Undo Change");
            confirmBtn.setToolTipText("Confirm Change");
            
            Dimension btnSize = new Dimension(28, 28);
            undoBtn.setPreferredSize(btnSize);
            confirmBtn.setPreferredSize(btnSize);
            
            // Minimalist button style
            undoBtn.setMargin(JBUI.insets(0));
            confirmBtn.setMargin(JBUI.insets(0));

            undoBtn.addActionListener(e -> {
                diffService.revertChange(change, () -> removePendingChange(change), null);
            });
            confirmBtn.addActionListener(e -> {
                diffService.confirmChange(change, () -> removePendingChange(change), null);
            });

            actions.add(undoBtn);
            actions.add(confirmBtn);

            item.add(textPanel, BorderLayout.CENTER);
            item.add(actions, BorderLayout.EAST);
            item.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(4),
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
            ));
            
            pendingChangesList.add(item);
        }

        if (commitAllButton != null) {
            commitAllButton.setEnabled(count > 0);
        }
        
        pendingChangesList.revalidate();
        pendingChangesList.repaint();
        
        // Close popup if empty
        if (count == 0 && activePendingPopup != null && !activePendingPopup.isDisposed()) {
            activePendingPopup.cancel();
            activePendingPopup = null;
        }
    }

    private boolean samePendingChange(PendingChangesManager.PendingChange a, PendingChangesManager.PendingChange b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.path == null || b.path == null) {
            return false;
        }
        String aPath = normalizePendingPath(a.path, a.workspaceRoot);
        String bPath = normalizePendingPath(b.path, b.workspaceRoot);
        return aPath.equals(bPath) && a.type.equalsIgnoreCase(b.type);
    }

    private String normalizePendingPath(String path, String wsRoot) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim().replace('\\', '/');
        if (normalized.isEmpty()) {
            return "";
        }
        String base = (wsRoot == null || wsRoot.isBlank()) ? workspaceRoot : wsRoot;
        if (base != null && !base.isBlank()) {
            String root = base.replace('\\', '/').trim();
            if (normalized.startsWith(root)) {
                normalized = normalized.substring(root.length());
                if (normalized.startsWith("/")) {
                    normalized = normalized.substring(1);
                }
            }
        }
        return normalized;
    }

    private boolean isPendingWorkflowEnabled() {
        String sys = System.getProperty("codeagent.pending.enabled");
        String env = System.getenv("CODEAGENT_PENDING_ENABLED");
        String val = (sys != null && !sys.isBlank()) ? sys : env;
        if (val == null || val.isBlank()) {
            return true;
        }
        return "true".equalsIgnoreCase(val);
    }

    private JButton createJumpToBottomButton() {
        JButton button = new JButton("\u2193") {
            private Shape shape;

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int size = Math.min(getWidth(), getHeight()) - 1;
                int x = (getWidth() - size) / 2;
                int y = (getHeight() - size) / 2;
                Color fill = getModel().isRollover()
                        ? new JBColor(new Color(0xF2F4F8), new Color(0x444B57))
                        : new JBColor(new Color(0xE5E9F0), new Color(0x343A46));
                Color border = new JBColor(new Color(0xC9D1DD), new Color(0x596173));
                g2.setColor(fill);
                g2.fillOval(x, y, size, size);
                g2.setColor(border);
                g2.drawOval(x, y, size, size);
                g2.dispose();
                super.paintComponent(g);
            }

            @Override
            public boolean contains(int x, int y) {
                int size = Math.min(getWidth(), getHeight()) - 1;
                int sx = (getWidth() - size) / 2;
                int sy = (getHeight() - size) / 2;
                if (shape == null || !shape.getBounds().equals(new Rectangle(sx, sy, size, size))) {
                    shape = new Ellipse2D.Float(sx, sy, size, size);
                }
                return shape.contains(x, y);
            }
        };
        button.setPreferredSize(new Dimension(40, 40));
        button.setMinimumSize(new Dimension(40, 40));
        button.setMaximumSize(new Dimension(40, 40));
        button.setFocusable(false);
        button.setToolTipText("Jump to latest");
        button.setFont(button.getFont().deriveFont(Font.BOLD, 18f));
        button.setForeground(new JBColor(new Color(0x20252E), new Color(0xE7ECF5)));
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMargin(JBUI.emptyInsets());
        return button;
    }
    
    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) return;
            JScrollBar vertical = scrollPane.getVerticalScrollBar();
            if (!followStreamingOutput) {
                updateJumpToBottomVisibility(vertical);
                return;
            }
            suppressScrollTracking = true;
            try {
                vertical.setValue(vertical.getMaximum());
            } finally {
                suppressScrollTracking = false;
            }
            updateJumpToBottomVisibility(vertical);
        });
    }
    
    private void scrollToBottomSmart() {
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) return;
            JScrollBar vertical = scrollPane.getVerticalScrollBar();
            if (!followStreamingOutput) {
                updateJumpToBottomVisibility(vertical);
                return;
            }
            suppressScrollTracking = true;
            try {
                vertical.setValue(vertical.getMaximum());
            } finally {
                suppressScrollTracking = false;
            }
            updateJumpToBottomVisibility(vertical);
        });
    }

    private void installConversationScrollBehavior() {
        JScrollBar vertical = scrollPane.getVerticalScrollBar();
        if (vertical == null) {
            return;
        }
        scrollPane.addMouseWheelListener(e -> {
            markManualScroll();
            followStreamingOutput = false;
            updateJumpToBottomVisibility(vertical);
        });
        scrollPane.getViewport().addMouseWheelListener(e -> {
            markManualScroll();
            followStreamingOutput = false;
            updateJumpToBottomVisibility(vertical);
        });
        vertical.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                markManualScroll();
                if (!isAtBottom(vertical)) {
                    followStreamingOutput = false;
                }
                updateJumpToBottomVisibility(vertical);
            }
        });
        vertical.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                markManualScroll();
                followStreamingOutput = false;
                updateJumpToBottomVisibility(vertical);
            }
        });
        vertical.addAdjustmentListener(e -> {
            if (project.isDisposed() || suppressScrollTracking) {
                return;
            }
            if (isAtBottom(vertical)) {
                followStreamingOutput = true;
            } else if (isManualScrollRecent() || e.getValueIsAdjusting()) {
                followStreamingOutput = false;
            }
            updateJumpToBottomVisibility(vertical);
        });
        updateJumpToBottomVisibility(vertical);
    }

    private void markManualScroll() {
        lastManualScrollAtMs = System.currentTimeMillis();
    }

    private boolean isManualScrollRecent() {
        return (System.currentTimeMillis() - lastManualScrollAtMs) <= MANUAL_SCROLL_WINDOW_MS;
    }

    private int distanceToBottom(JScrollBar vertical) {
        if (vertical == null) {
            return 0;
        }
        int extent = vertical.getModel().getExtent();
        int max = vertical.getMaximum();
        int value = vertical.getValue();
        return max - (value + extent);
    }

    private boolean isAtBottom(JScrollBar vertical) {
        return distanceToBottom(vertical) <= AUTO_SCROLL_BOTTOM_THRESHOLD_PX;
    }

    private void updateJumpToBottomVisibility(JScrollBar vertical) {
        if (jumpToBottomButton == null) {
            return;
        }
        boolean show = vertical != null && !isAtBottom(vertical);
        jumpToBottomButton.setVisible(show);
        jumpToBottomButton.revalidate();
        jumpToBottomButton.repaint();
    }

    // --- Settings Dialog ---
    private class SettingsDialog extends DialogWrapper {
        private final ChatHistoryService.SessionSettings settings;
        private final JTextField modelField;
        private final JTextField languageField;
        private final JTextField temperatureField;
        private final JTextField agentField;

        SettingsDialog(ChatHistoryService.SessionSettings settings) {
            super(project);
            this.settings = settings;
            this.modelField = new JTextField(settings.model);
            this.languageField = new JTextField(settings.language);
            this.temperatureField = new JTextField(String.valueOf(settings.temperature));
            this.agentField = new JTextField(settings.agent != null ? settings.agent : "build");
            
            setTitle("Session Settings");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridLayout(4, 2, 5, 5));
            panel.add(new JLabel("Model:"));
            panel.add(modelField);
            panel.add(new JLabel("Language:"));
            panel.add(languageField);
            panel.add(new JLabel("Temperature:"));
            panel.add(temperatureField);
            panel.add(new JLabel("Agent:"));
            panel.add(agentField);
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
            settings.agent = agentField.getText();
            super.doOKAction();
        }
    }
    
    private static class LineDiffStat {
        final int added;
        final int removed;

        LineDiffStat(int added, int removed) {
            this.added = Math.max(0, added);
            this.removed = Math.max(0, removed);
        }
    }

    private enum ToolRenderType {
        TERMINAL,
        MODIFICATION,
        READ,
        GENERIC;

        static ToolRenderType fromValue(String value) {
            if (value == null || value.isBlank()) {
                return GENERIC;
            }
            String normalized = value.trim().toUpperCase();
            for (ToolRenderType type : values()) {
                if (type.name().equals(normalized)) {
                    return type;
                }
            }
            return GENERIC;
        }
    }

    private static class ToolActivityState {
        String callID;
        String tool;
        ToolRenderType renderType = ToolRenderType.GENERIC;
        String intentSummary;
        String inputSummary;
        String commandSummary;
        String inputDetails;
        String status;
        Integer exitCode;
        String output;
        String error;
        String title;
        PendingChangesManager.PendingChange pendingChange;
        PendingCommandInfo pendingCommand;
        boolean deleteDecisionRequired;
        boolean deleteDecisionMade;
        boolean commandDecisionRequired;
        boolean commandDecisionMade;
        long startedAtMs;
        long finishedAtMs;
        long durationMs;
        JsonNode lastArgs;
        String appliedChangeFingerprint;
        String targetPath;
        int targetLineStart = -1;
        int targetLineEnd = -1;
        boolean targetNavigable;
        JPanel hostPanel;
        ActivityCommandPanel panel;
        InlineToolRowPanel inlineRow;
        JPanel inlineDiffCard;
        String uiSummary;
        String uiExpandedSummary;
        String uiMeta;
        String uiDetails;
    }

    private static class PendingCommandInfo {
        String id;
        String command;
        String description;
        String workdir;
        String workspaceRoot;
        String sessionId;
        long timeoutMs;
        String riskLevel;
        List<String> reasons = new ArrayList<>();
    }

    private static class PendingCommandResolutionResult {
        final boolean success;
        final String status;
        final String output;
        final String error;

        private PendingCommandResolutionResult(boolean success, String status, String output, String error) {
            this.success = success;
            this.status = status;
            this.output = output;
            this.error = error;
        }

        static PendingCommandResolutionResult success(String status, String output) {
            return new PendingCommandResolutionResult(true, status, output, "");
        }

        static PendingCommandResolutionResult failure(String error) {
            return new PendingCommandResolutionResult(false, "error", "", error == null ? "" : error);
        }
    }

    private static class InlineToolRowPanel extends JPanel {
        private final JLabel markerLabel;
        private final JLabel summaryLabel;
        private final JLabel metaLabel;
        private Runnable action;

        InlineToolRowPanel(String marker) {
            setLayout(new BorderLayout(8, 0));
            setOpaque(false);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UIUtil.getBoundsColor(), 1),
                    JBUI.Borders.empty(4, 6, 4, 6)
            ));
            setCursor(Cursor.getDefaultCursor());

            JPanel left = new JPanel(new BorderLayout(6, 0));
            left.setOpaque(false);

            markerLabel = new JLabel(marker == null ? "[tool]" : marker);
            markerLabel.setForeground(UIUtil.getContextHelpForeground());
            markerLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));

            summaryLabel = new JLabel();
            summaryLabel.setForeground(UIUtil.getLabelForeground());

            left.add(markerLabel, BorderLayout.WEST);
            left.add(summaryLabel, BorderLayout.CENTER);

            metaLabel = new JLabel("");
            metaLabel.setForeground(UIUtil.getContextHelpForeground());
            metaLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));

            add(left, BorderLayout.CENTER);
            add(metaLabel, BorderLayout.EAST);

            MouseAdapter click = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (action != null) {
                        action.run();
                    }
                }
            };
            addMouseListener(click);
            left.addMouseListener(click);
            markerLabel.addMouseListener(click);
            summaryLabel.addMouseListener(click);
            metaLabel.addMouseListener(click);
        }

        void setSummaryText(String summary) {
            summaryLabel.setText(summary == null ? "" : summary);
        }

        void setMetaText(String meta, Color color) {
            metaLabel.setText(meta == null ? "" : meta);
            metaLabel.setForeground(color == null ? UIUtil.getContextHelpForeground() : color);
        }

        void setAction(boolean enabled, Runnable action) {
            this.action = enabled ? action : null;
            setCursor(enabled ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        }
    }

    private static class ActivityCommandPanel extends JPanel {
        private final JPanel headerPanel;
        private final JLabel arrowLabel;
        private final JLabel summaryLabel;
        private final JLabel subtitleLabel;
        private final JLabel metaLabel;
        private final JTextArea detailArea;
        private final JBScrollPane detailScroll;
        private final JPanel detailPanel;
        private final JPanel detailFooter;
        private final JLabel executionStatusLabel;
        private final JPanel decisionPanel;
        private final JButton approveButton;
        private final JButton rejectButton;
        private String summary = "";
        private String expandedSummary = "";
        private String subtitle = "";
        private boolean expanded;

        ActivityCommandPanel(String summary) {
            setLayout(new BorderLayout(0, 4));
            setOpaque(false);

            headerPanel = new JPanel(new BorderLayout(8, 0));
            headerPanel.setOpaque(false);
            headerPanel.setBorder(JBUI.Borders.empty(2, 0));
            headerPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JPanel left = new JPanel(new BorderLayout(6, 0));
            left.setOpaque(false);
            arrowLabel = new JLabel(">");
            arrowLabel.setForeground(UIUtil.getContextHelpForeground());
            summaryLabel = new JLabel();
            summaryLabel.setForeground(UIUtil.getLabelForeground());
            subtitleLabel = new JLabel();
            subtitleLabel.setForeground(UIUtil.getContextHelpForeground());
            subtitleLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));

            JPanel textColumn = new JPanel();
            textColumn.setOpaque(false);
            textColumn.setLayout(new BoxLayout(textColumn, BoxLayout.Y_AXIS));
            textColumn.add(summaryLabel);
            textColumn.add(subtitleLabel);

            left.add(arrowLabel, BorderLayout.WEST);
            left.add(textColumn, BorderLayout.CENTER);

            metaLabel = new JLabel("");
            metaLabel.setForeground(UIUtil.getContextHelpForeground());
            metaLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));

            headerPanel.add(left, BorderLayout.CENTER);
            headerPanel.add(metaLabel, BorderLayout.EAST);

            MouseAdapter toggle = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    setExpanded(!expanded);
                }
            };
            headerPanel.addMouseListener(toggle);
            left.addMouseListener(toggle);
            textColumn.addMouseListener(toggle);
            summaryLabel.addMouseListener(toggle);
            subtitleLabel.addMouseListener(toggle);
            metaLabel.addMouseListener(toggle);
            arrowLabel.addMouseListener(toggle);

            detailArea = new JTextArea();
            detailArea.setEditable(false);
            detailArea.setLineWrap(false);
            detailArea.setWrapStyleWord(false);
            detailArea.setBackground(UIUtil.getTextFieldBackground());
            detailArea.setForeground(UIUtil.getLabelForeground());
            detailArea.setBorder(JBUI.Borders.empty(8));
            Font currentFont = detailArea.getFont();
            detailArea.setFont(new Font(Font.MONOSPACED, currentFont.getStyle(), currentFont.getSize()));

            detailScroll = new JBScrollPane(detailArea);
            detailScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            detailScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
            detailScroll.setPreferredSize(new Dimension(10, 150));
            detailScroll.setMinimumSize(new Dimension(10, 150));
            detailScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));

            detailPanel = new JPanel(new BorderLayout());
            detailPanel.setOpaque(false);
            detailPanel.add(detailScroll, BorderLayout.CENTER);
            detailFooter = new JPanel(new BorderLayout());
            detailFooter.setOpaque(false);
            detailFooter.setBorder(JBUI.Borders.empty(2, 8, 4, 8));
            executionStatusLabel = new JLabel("");
            executionStatusLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));
            executionStatusLabel.setForeground(UIUtil.getContextHelpForeground());
            detailFooter.add(executionStatusLabel, BorderLayout.EAST);
            detailFooter.setVisible(false);
            detailPanel.add(detailFooter, BorderLayout.SOUTH);
            detailPanel.setVisible(false);
            detailPanel.setBorder(BorderFactory.createCompoundBorder(
                    JBUI.Borders.empty(0, 18, 0, 0),
                    BorderFactory.createLineBorder(UIUtil.getBoundsColor(), 1)
            ));

            decisionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            decisionPanel.setOpaque(false);
            decisionPanel.setBorder(JBUI.Borders.empty(2, 18, 0, 0));
            approveButton = new JButton("Approve");
            rejectButton = new JButton("Skip");
            approveButton.setFocusable(false);
            rejectButton.setFocusable(false);
            decisionPanel.add(approveButton);
            decisionPanel.add(rejectButton);
            decisionPanel.setVisible(false);

            add(headerPanel, BorderLayout.NORTH);
            add(detailPanel, BorderLayout.CENTER);
            add(decisionPanel, BorderLayout.SOUTH);
            setSummary(summary);
            setSubtitle("");
            setExpanded(false);
        }

        void setSummary(String summary) {
            this.summary = summary == null ? "" : summary;
            refreshHeaderLabels();
        }

        String getSummaryText() {
            return summary == null ? "" : summary;
        }

        void setExpandedSummary(String expandedSummary) {
            this.expandedSummary = expandedSummary == null ? "" : expandedSummary;
            refreshHeaderLabels();
        }

        String getExpandedSummaryText() {
            return expandedSummary == null ? "" : expandedSummary;
        }

        private String resolveHeaderText() {
            if (expanded && expandedSummary != null && !expandedSummary.isBlank()) {
                return expandedSummary;
            }
            return summary;
        }

        void setSubtitle(String subtitle) {
            this.subtitle = subtitle == null ? "" : subtitle;
            refreshHeaderLabels();
        }

        String getSubtitleText() {
            return subtitle == null ? "" : subtitle;
        }

        private void refreshHeaderLabels() {
            summaryLabel.setText(resolveHeaderText());
            if (expanded || subtitle == null || subtitle.isBlank()) {
                subtitleLabel.setVisible(false);
                subtitleLabel.setText("");
            } else {
                subtitleLabel.setVisible(true);
                subtitleLabel.setText(subtitle);
            }
        }

        void setMeta(String text, Color color) {
            metaLabel.setText(text == null ? "" : text);
            metaLabel.setForeground(color == null ? UIUtil.getContextHelpForeground() : color);
        }

        String getMetaText() {
            return metaLabel.getText() == null ? "" : metaLabel.getText();
        }

        void setDetails(String details) {
            detailArea.setText(details == null ? "" : details);
            detailArea.setCaretPosition(0);
        }

        String getDetailsText() {
            return detailArea.getText() == null ? "" : detailArea.getText();
        }

        void setExecutionStatus(String statusText, Color color) {
            String text = statusText == null ? "" : statusText.trim();
            executionStatusLabel.setText(text);
            executionStatusLabel.setForeground(color == null ? UIUtil.getContextHelpForeground() : color);
            detailFooter.setVisible(!text.isBlank());
            revalidate();
            repaint();
        }

        String getExecutionStatusText() {
            return executionStatusLabel.getText() == null ? "" : executionStatusLabel.getText();
        }

        void setDecisionActions(
                String approveText,
                String rejectText,
                boolean visible,
                Runnable onApprove,
                Runnable onReject,
                boolean enabled
        ) {
            if (!visible) {
                clearDecisionActions();
                return;
            }
            approveButton.setText(approveText == null || approveText.isBlank() ? "Approve" : approveText);
            rejectButton.setText(rejectText == null || rejectText.isBlank() ? "Skip" : rejectText);
            for (java.awt.event.ActionListener listener : approveButton.getActionListeners()) {
                approveButton.removeActionListener(listener);
            }
            for (java.awt.event.ActionListener listener : rejectButton.getActionListeners()) {
                rejectButton.removeActionListener(listener);
            }
            if (onApprove != null) {
                approveButton.addActionListener(e -> onApprove.run());
            }
            if (onReject != null) {
                rejectButton.addActionListener(e -> onReject.run());
            }
            approveButton.setEnabled(enabled);
            rejectButton.setEnabled(enabled);
            decisionPanel.setVisible(true);
            revalidate();
            repaint();
        }

        void setDecisionEnabled(boolean enabled) {
            approveButton.setEnabled(enabled);
            rejectButton.setEnabled(enabled);
        }

        void clearDecisionActions() {
            for (java.awt.event.ActionListener listener : approveButton.getActionListeners()) {
                approveButton.removeActionListener(listener);
            }
            for (java.awt.event.ActionListener listener : rejectButton.getActionListeners()) {
                rejectButton.removeActionListener(listener);
            }
            decisionPanel.setVisible(false);
            approveButton.setEnabled(true);
            rejectButton.setEnabled(true);
            revalidate();
            repaint();
        }

        void setExpanded(boolean expanded) {
            this.expanded = expanded;
            detailPanel.setVisible(expanded);
            arrowLabel.setText(expanded ? "v" : ">");
            refreshHeaderLabels();
            revalidate();
            repaint();
        }
    }

    private static class AgentMessageUI {
        CollapsiblePanel thoughtPanel;
        JPanel activityPanel;
        JPanel messagePanel;
        JEditorPane answerPane;
        JPanel changesPanel;
        JPanel sessionChangeSummaryPanel;
        String messageID;
        String historyUiMessageId;
        Map<String, ToolActivityState> toolActivities = new LinkedHashMap<>();
        Map<String, PendingChangesManager.PendingChange> sessionChanges = new LinkedHashMap<>();
        Set<String> appliedChangeFingerprints = new LinkedHashSet<>();
        Set<String> undoneSessionChangeKeys = new LinkedHashSet<>();
        int toolEventSeq;
        StringBuilder answerBuffer = new StringBuilder();
        StringBuilder deferredAnswerBuffer = new StringBuilder();
        String lastAppendedChunk = "";
        boolean thinkingOpen;
        boolean streamFinished;
        boolean historyCommitted;
        boolean sessionChangeSummaryShown;
    }

    private static class AgentResponse {
        String thought;
        String answer;
        List<PendingChangesManager.PendingChange> changes = new ArrayList<>();
        String traceId;
        String sessionID;
        String messageID;
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
        if (currentSessionId != null && !currentSessionId.isEmpty()) {
            json.put("sessionID", currentSessionId);
        }
        
        // Add Session Settings
        ChatHistoryService.ChatSession session = history.getCurrentSession();
        if (session != null && session.settings != null) {
            ObjectNode settingsNode = json.putObject("settings");
            settingsNode.put("model", session.settings.model);
            settingsNode.put("language", session.settings.language);
            settingsNode.put("temperature", session.settings.temperature);
            settingsNode.put("agent", session.settings.agent);
            if (session.settings.agent != null && !session.settings.agent.isEmpty()) {
                json.put("agent", session.settings.agent);
            }
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
            }
            if (rootNode.has("sessionID")) {
                response.sessionID = rootNode.get("sessionID").asText();
            } else if (rootNode.has("sessionId")) {
                response.sessionID = rootNode.get("sessionId").asText();
            }
            if (rootNode.has("messageID")) {
                response.messageID = rootNode.get("messageID").asText();
            } else if (rootNode.has("messageId")) {
                response.messageID = rootNode.get("messageId").asText();
            }
            if (response.sessionID != null && !response.sessionID.isEmpty()) {
                bindBackendSession(response.sessionID);
            } else if (response.traceId != null && !response.traceId.isEmpty()) {
                bindBackendSession(response.traceId);
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
                        String defaultSessionId = response.sessionID != null ? response.sessionID : response.traceId;
                        String sessionId = changeNode.path("sessionId").asText(changeNode.path("session_id").asText(defaultSessionId));
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
            return "IDEA Structure Capture Timeout/Failed";
        }
    }

    private String buildIdeContext() {
        if (project == null || DumbService.isDumb(project)) return "IDEA Index Not Ready";
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

    private void bindBackendSession(String sessionID) {
        if (sessionID == null || sessionID.isEmpty()) {
            return;
        }
        currentSessionId = sessionID;
        if (history != null) {
            history.setCurrentBackendSessionId(sessionID);
        }
    }

    private void setBusy(boolean busy, String msg) {
        runtimeBusy = busy;
        if (msg != null && !msg.isBlank()) {
            runtimeBusyMessage = msg;
        }
        refreshBusyUi();
    }

    private void setAwaitingUserApproval(boolean awaiting, String msg) {
        awaitingUserApproval = awaiting;
        if (msg != null && !msg.isBlank()) {
            approvalBusyMessage = msg;
        }
        refreshBusyUi();
    }

    private void refreshBusyUi() {
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) return;
            boolean effectiveBusy = runtimeBusy || awaitingUserApproval;
            send.setEnabled(!effectiveBusy);
            status.setText(awaitingUserApproval ? approvalBusyMessage : runtimeBusyMessage);
        });
    }

    private String resolveWorkspaceRoot(Project project) {
        return project.getBasePath() != null ? project.getBasePath() : System.getProperty("user.dir");
    }

    private String resolveWorkspaceName(String root) {
        return root == null ? "unknown" : Path.of(root).getFileName().toString();
    }
    
    private void rebuildConversationFromHistory() {
        followStreamingOutput = true;
        pendingApprovalKeys.clear();
        setAwaitingUserApproval(false, null);
        conversationList.removeAll();
        if (pendingWorkflowEnabled) {
            pendingChanges.clear();
            refreshPendingChangesPanel();
        }
        
        if (history == null) return;
        List<ChatHistoryService.UiMessage> uiMessages = history.getUiMessages();
        if (uiMessages != null && !uiMessages.isEmpty()) {
            rebuildConversationFromUiMessages(uiMessages);
            conversationList.revalidate();
            conversationList.repaint();
            scrollToBottom();
            return;
        }
        List<String> lines = history.getLines();
        if (lines != null) {
            for (String line : lines) {
                if (line.startsWith("You: ")) {
                    addMessage(true, line.substring(5), null, false);
                } else if (line.startsWith("?: ")) {
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

    private void rebuildConversationFromUiMessages(List<ChatHistoryService.UiMessage> uiMessages) {
        for (ChatHistoryService.UiMessage message : uiMessages) {
            if (message == null) {
                continue;
            }
            String role = message.role == null ? "" : message.role.trim().toLowerCase();
            if ("user".equals(role)) {
                addMessage(true, firstNonBlank(message.text), null, false);
                continue;
            }
            if ("system".equals(role)) {
                addSystemMessage(firstNonBlank(message.text));
                continue;
            }

            AgentResponse response = new AgentResponse();
            response.answer = firstNonBlank(message.text);
            response.thought = firstNonBlank(message.thought);
            AgentMessageUI ui = addMessage(false, null, response, false);
            ui.messageID = firstNonBlank(message.messageID);
            ui.historyUiMessageId = firstNonBlank(message.id);
            ui.streamFinished = true;
            ui.historyCommitted = true;
            rebuildToolActivitiesFromHistory(ui, message.toolActivities);
        }
    }

    private void rebuildToolActivitiesFromHistory(AgentMessageUI ui, List<ChatHistoryService.ToolActivity> toolActivities) {
        if (ui == null || toolActivities == null || toolActivities.isEmpty()) {
            return;
        }
        for (ChatHistoryService.ToolActivity activity : toolActivities) {
            if (activity == null) {
                continue;
            }
            String callId = firstNonBlank(activity.callID, "__tool_history_" + (++ui.toolEventSeq));
            String toolName = firstNonBlank(activity.tool, "tool");
            ToolActivityState state = resolveToolActivity(ui, callId, toolName);
            state.callID = callId;
            state.tool = toolName;
            if (activity.renderType == null || activity.renderType.isBlank()) {
                state.renderType = classifyToolRenderType(state.tool, null, null);
            } else {
                state.renderType = ToolRenderType.fromValue(activity.renderType);
            }
            state.targetPath = firstNonBlank(activity.targetPath);
            state.targetLineStart = activity.lineStart;
            state.targetLineEnd = activity.lineEnd;
            state.targetNavigable = activity.navigable && isNavigableFileTarget(state.targetPath);
            state.status = firstNonBlank(activity.status);
            state.durationMs = activity.durationMs;
            ensureToolRenderType(ui, state, state.renderType);
            state.uiSummary = firstNonBlank(activity.summary, buildToolSummary(state));
            state.uiExpandedSummary = firstNonBlank(activity.expandedSummary, resolveExpandedSummary(state));
            state.uiMeta = firstNonBlank(activity.meta, buildToolMeta(state));
            state.uiDetails = firstNonBlank(activity.details, buildToolDetails(state));
            if (state.panel != null) {
                state.panel.setSummary(state.uiSummary);
                state.panel.setExpandedSummary(state.uiExpandedSummary);
                state.panel.setSubtitle(buildToolSubtitle(state));
                state.panel.setMeta(state.uiMeta, colorForToolStatus(state.status));
                state.panel.setDetails(state.uiDetails);
                String executionStatus = state.renderType == ToolRenderType.TERMINAL ? buildTerminalExecutionStatus(state) : "";
                state.panel.setExecutionStatus(executionStatus, colorForToolStatus(state.status));
                state.panel.clearDecisionActions();
            }
            if (state.inlineRow != null) {
                state.inlineRow.setSummaryText(state.uiSummary);
                state.inlineRow.setMetaText(state.uiMeta, colorForToolStatus(state.status));
                if (state.renderType == ToolRenderType.READ && state.targetNavigable) {
                    state.inlineRow.setAction(true, () -> navigateToReadTarget(state));
                } else {
                    state.inlineRow.setAction(false, null);
                }
            }
        }
    }
    
    private static long longOrDefault(String val, long def) {
        try { return Long.parseLong(val); } catch (Exception e) { return def; }
    }
    
    // -- Inner Classes --
    
    private class CollapsiblePanel extends JPanel {
        private final JPanel contentPanel;
        private final JButton toggleBtn;
        private final JTextArea textArea;
        private final String title;
        private boolean collapsible = true;
        private boolean expanded = false;
        private String contentText = "";
        
        CollapsiblePanel(String title, String contentText, boolean animate) {
            this.title = title;
            this.contentText = contentText == null ? "" : contentText;
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createLineBorder(JBColor.border()));
            
            toggleBtn = new JButton("> " + title);
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
            textArea.setBackground(UIUtil.getTextFieldBackground());
            textArea.setForeground(UIUtil.getLabelForeground());
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
                if (!collapsible) {
                    return;
                }
                setExpanded(!expanded);
            });
            
            add(toggleBtn, BorderLayout.NORTH);
            add(contentPanel, BorderLayout.CENTER);
            setExpanded(false);
        }

        void appendContent(String text) {
            appendContent(text, false);
        }

        void appendContent(String text, boolean append) {
            if (text == null) return;
            if (append) {
                this.contentText += text;
            } else {
                this.contentText = text;
            }
            this.textArea.setText(this.contentText);
        }
        
        void setContent(String text) {
            this.contentText = text != null ? text : "";
            this.textArea.setText(this.contentText);
        }

        String getContent() {
            return contentText == null ? "" : contentText;
        }

        void setExpanded(boolean expanded) {
            this.expanded = expanded;
            boolean visible = !collapsible || expanded;
            contentPanel.setVisible(visible);
            toggleBtn.setText((collapsible ? (expanded ? "v " : "> ") : "") + title);
            revalidate();
            repaint();
        }

        void setCollapsible(boolean collapsible) {
            this.collapsible = collapsible;
            toggleBtn.setEnabled(collapsible);
            toggleBtn.setCursor(collapsible ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            setExpanded(expanded);
        }
    }
}














