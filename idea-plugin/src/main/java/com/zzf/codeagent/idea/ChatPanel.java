package com.zzf.codeagent.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.LanguageLevelProjectExtension;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.util.ui.UIUtil;
import com.intellij.ui.ColorUtil;
import org.jetbrains.annotations.Nullable;
import com.zzf.codeagent.core.event.EventStream;
import com.zzf.codeagent.core.tool.PendingChangesManager;
import com.zzf.codeagent.core.tools.FileSystemToolService;
import com.zzf.codeagent.idea.utils.MarkdownUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.geom.Ellipse2D;
import java.beans.PropertyChangeListener;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ChatPanel {
    private static final Logger logger = Logger.getInstance(ChatPanel.class);
    private static final Duration CHAT_TIMEOUT = Duration.ofSeconds(longOrDefault(System.getProperty("codeagent.chatTimeoutSeconds", ""), 600L));
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(longOrDefault(System.getProperty("codeagent.connectTimeoutSeconds", ""), 10L));
    private static final int HISTORY_SEND_MAX_LINES = (int) longOrDefault(System.getProperty("codeagent.historyMaxLines", ""), 60L);
    private static final int AUTO_SCROLL_BOTTOM_THRESHOLD_PX = 48;
    private static final int TOOLWINDOW_AUTO_HIDE_THRESHOLD_PX = 140;
    private static final long MANUAL_SCROLL_WINDOW_MS = 1200L;
    private static final boolean AUTO_ALIGN_PROJECT_SDK =
            Boolean.parseBoolean(System.getProperty("codeagent.ide.autoAlignProjectSdk", "true"));
    private static final boolean AUTO_ALIGN_GRADLE_JVM =
            Boolean.parseBoolean(System.getProperty("codeagent.ide.autoAlignGradleJvm", "true"));
    private static final boolean AUTO_ALIGN_MAVEN_RUNNER_JRE =
            Boolean.parseBoolean(System.getProperty("codeagent.ide.autoAlignMavenRunnerJre", "true"));
    private static final boolean AUTO_ALIGN_RUN_CONFIGURATION_JRE =
            Boolean.parseBoolean(System.getProperty("codeagent.ide.autoAlignRunConfigJre", "true"));
    private static final boolean SEND_VERBOSE_IDE_CONTEXT =
            Boolean.parseBoolean(System.getProperty("codeagent.ide.sendVerboseContext", "false"));
    private static final String PENDING_ASSISTANT_KEY = "__pending_assistant__";
    private static final String DEFAULT_AGENT_ENDPOINT = "http://localhost:18080/api/agent/chat";
    private static final String DECISION_MANUAL = "manual";
    private static final String DECISION_WHITELIST = "whitelist";
    private static final String DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE = "always_allow_non_destructive";
    private static final String MARKDOWN_SOURCE_CLIENT_KEY = "rikki.markdown.source";
    private static final Pattern EXIT_CODE_PATTERN = Pattern.compile("(?i)exit\\s*code\\s*(\\d+)");
    private static final ExecutorService IDE_CONTEXT_EXECUTOR = Executors.newCachedThreadPool(r -> new Thread(r, "rikki-ide-context"));
    
    private final JSplitPane splitPane;
    private final JPanel leftPanel; 
    private final JPanel rightPanel; 
    private final JPanel conversationList;
    private final JBScrollPane scrollPane;
    private final JBTextArea input;
    private final JButton send;
    private final ChatInputController inputController;
    private final JLabel status;
    private final JLabel chatTitleLabel;
    private final JButton jumpToBottomButton;
    private JButton toggleSessionsButton;
    private JButton historyNavButton;
    private JButton settingsNavButton;
    private JButton newChatNavButton;
    private RoundedPanel inputCardPanel;
    private final ConversationScrollController scrollController;
    private final ThinkingStatusPanel thinkingStatusPanel;
    private final HttpClient http;
    private final ChatStopClient stopClient;
    private final Project project;
    private final String workspaceRoot;
    private final String workspaceName;
    private final WorkspacePathResolver workspacePathResolver;
    private final MessageStateStore<AgentMessageUI> assistantUiStateStore;
    private final ToolEventStateMachine toolEventStateMachine;
    private final ChatToolMetaExtractor toolMetaExtractor;
    private final ToolActivityRenderer toolActivityRenderer;
    private String currentSessionId;
    private final ChatHistoryService history;
    private final IdeBridgeServer ideBridgeServer;
    private final ObjectMapper mapper = new ObjectMapper();
    private FileSystemToolService fsService;
    private EventStream eventStream;
    private final DiffService diffService;
    private final boolean pendingWorkflowEnabled;
    
    private JPanel pendingChangesList;
    private JButton commitAllButton;
    private JButton pendingChangesToggle;
    private JBPopup activePendingPopup;
    private RoundedPanel historyBrowserOverlay;
    private JTextField historySearchField;
    private JPanel historyBrowserList;
    private boolean historyBrowserVisible;
    private final List<PendingChangesManager.PendingChange> pendingChanges = new ArrayList<>();
    private final Set<String> pendingApprovalKeys = ConcurrentHashMap.newKeySet();
    private volatile boolean runtimeBusy;
    private volatile boolean awaitingUserApproval;
    private volatile String runtimeBusyMessage = "Ready";
    private volatile String approvalBusyMessage = "Awaiting your approval...";
    private volatile Thread activeStreamThread;
    private volatile boolean stopRequestedByUser;
    private volatile boolean toolWindowAutoHideTriggered;
    private PropertyChangeListener lookAndFeelListener;
    private MessageBusConnection lafMessageBusConnection;
    private final TodoPanel todoPanel = new TodoPanel();

    ChatPanel(Project project) {
        this.project = project;
        this.pendingWorkflowEnabled = false;
        
        
        this.leftPanel = new JPanel(new BorderLayout());
        this.leftPanel.setBorder(JBUI.Borders.empty(5));
        this.leftPanel.setMinimumSize(new Dimension(150, 0));
        
        
        this.rightPanel = new JPanel(new BorderLayout());
        this.rightPanel.setOpaque(true);
        this.rightPanel.setBackground(UIUtil.getPanelBackground());
        
        
        this.conversationList = new ConversationListPanel();
        this.conversationList.setLayout(new BoxLayout(this.conversationList, BoxLayout.Y_AXIS));
        this.conversationList.setBorder(JBUI.Borders.empty(10));
        this.conversationList.setOpaque(false);
        
        this.scrollPane = new JBScrollPane(this.conversationList);
        this.scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        this.scrollPane.setWheelScrollingEnabled(true);
        this.scrollPane.getVerticalScrollBar().setUnitIncrement(JBUI.scale(18));
        this.scrollPane.getHorizontalScrollBar().setUnitIncrement(JBUI.scale(18));
        this.scrollPane.setBorder(BorderFactory.createEmptyBorder());
        this.scrollPane.setOpaque(false);
        this.scrollPane.getViewport().setOpaque(false);
        
        this.input = new JBTextArea(4, 10);
        this.send = ChatInputController.createRoundSendButton();
        this.inputController = new ChatInputController(
                this.input,
                this.send,
                () -> runtimeBusy,
                () -> awaitingUserApproval,
                this::requestStopCurrentRun,
                this::sendMessage
        );
        this.status = new JLabel("Ready");
        this.chatTitleLabel = new JLabel("New Chat");
        this.jumpToBottomButton = createJumpToBottomButton();
        this.scrollController = new ConversationScrollController(
                project,
                this.scrollPane,
                this.jumpToBottomButton,
                AUTO_SCROLL_BOTTOM_THRESHOLD_PX,
                MANUAL_SCROLL_WINDOW_MS
        );
        this.jumpToBottomButton.addActionListener(e -> {
            scrollController.enableFollow();
            scrollToBottom();
        });
        this.historyBrowserOverlay = createHistoryBrowserOverlay();
        this.historyBrowserOverlay.setVisible(false);
        JLayeredPane chatCenterPanel = new JLayeredPane() {
            @Override
            public void doLayout() {
                int width = getWidth();
                int height = getHeight();
                scrollPane.setBounds(0, 0, width, height);
                if (historyBrowserOverlay != null && historyBrowserOverlay.isVisible()) {
                    int overlayWidth = Math.max(280, width - 24);
                    int overlayHeight = Math.max(240, Math.min(520, height - 24));
                    int overlayX = Math.max(8, (width - overlayWidth) / 2);
                    int overlayY = 8;
                    historyBrowserOverlay.setBounds(overlayX, overlayY, overlayWidth, overlayHeight);
                }
                Dimension buttonSize = jumpToBottomButton.getPreferredSize();
                int x = Math.max(8, (width - buttonSize.width) / 2);
                int y = Math.max(8, height - buttonSize.height - 14);
                jumpToBottomButton.setBounds(x, y, buttonSize.width, buttonSize.height);
            }
        };
        chatCenterPanel.setOpaque(false);
        chatCenterPanel.add(this.scrollPane, JLayeredPane.DEFAULT_LAYER);
        chatCenterPanel.add(this.historyBrowserOverlay, JLayeredPane.MODAL_LAYER);
        chatCenterPanel.add(this.jumpToBottomButton, JLayeredPane.PALETTE_LAYER);
        this.jumpToBottomButton.setVisible(false);
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        String streamEndpoint = System.getProperty("codeagent.endpoint", DEFAULT_AGENT_ENDPOINT + "/stream");
        this.stopClient = new ChatStopClient(this.http, this.mapper, streamEndpoint, DEFAULT_AGENT_ENDPOINT + "/stop");
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
        this.workspacePathResolver = new WorkspacePathResolver(this.workspaceRoot);
        this.assistantUiStateStore = new MessageStateStore<>(
                PENDING_ASSISTANT_KEY,
                this::createPendingAssistantUi,
                this::createAssistantUiForMessageId
        );
        this.toolEventStateMachine = new ToolEventStateMachine();
        this.toolMetaExtractor = new ChatToolMetaExtractor();
        this.toolActivityRenderer = new ToolActivityRenderer();
        this.history = project.getService(ChatHistoryService.class);
        this.ideBridgeServer = project.getService(IdeBridgeServer.class);
        if (this.ideBridgeServer != null) {
            try {
                this.ideBridgeServer.ensureStarted();
            } catch (Exception e) {
                logger.warn("ide_bridge_start_failed", e);
            }
        }
        
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setBorder(JBUI.Borders.empty(0));
        input.setOpaque(false);
        input.setForeground(UIUtil.getLabelForeground());
        input.setCaretColor(UIUtil.getLabelForeground());
        this.chatTitleLabel.setFont(this.chatTitleLabel.getFont().deriveFont(Font.BOLD, 14f));
        this.chatTitleLabel.setForeground(UIUtil.getLabelForeground());

        JPanel headerPanel = new HeaderBarPanel();
        headerPanel.setLayout(new BorderLayout(8, 0));
        headerPanel.setBorder(JBUI.Borders.empty(8, 10, 8, 10));
        headerPanel.setPreferredSize(new Dimension(10, 48));
        headerPanel.setMinimumSize(new Dimension(10, 44));

        toggleSessionsButton = createTopNavIconButton("\u2190", "Show history");
        JPanel navLeft = new JPanel(new BorderLayout(8, 0));
        navLeft.setOpaque(false);
        navLeft.add(toggleSessionsButton, BorderLayout.WEST);
        navLeft.add(chatTitleLabel, BorderLayout.CENTER);

        JPanel navRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        navRight.setOpaque(false);
        historyNavButton = createTopNavIconButton("\u21BA", "History");
        historyNavButton.addActionListener(e -> openHistoryBrowserPopup(historyNavButton));
        settingsNavButton = createTopNavIconButton("\u2699", "Agent settings");
        settingsNavButton.addActionListener(e -> openSettingsDialogForCurrentSession());
        newChatNavButton = createTopNavIconButton("\u270E", "New chat");
        newChatNavButton.addActionListener(e -> {
            pruneEmptyCurrentSessionIfNeeded();
            history.createSession("New Chat");
            currentSessionId = null;
            refreshSessionList();
            rebuildConversationFromHistory();
            refreshChatHeaderTitle();
        });
        navRight.add(historyNavButton);
        navRight.add(settingsNavButton);
        navRight.add(newChatNavButton);

        if (pendingWorkflowEnabled) {
            pendingChangesToggle = createHeaderIconButton("\u25CF", "Pending changes");
            pendingChangesToggle.setVisible(false);
            pendingChangesToggle.addActionListener(e -> showPendingChangesPopup());
            navRight.add(pendingChangesToggle);
            initPendingChangesList();
        }

        headerPanel.add(navLeft, BorderLayout.CENTER);
        headerPanel.add(navRight, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        this.thinkingStatusPanel = new ThinkingStatusPanel();
        this.thinkingStatusPanel.setVisible(false);
        bottom.add(thinkingStatusPanel, BorderLayout.NORTH);

        inputCardPanel = new RoundedPanel(
                18,
                UIUtil::getTextFieldBackground,
                UIUtil::getBoundsColor
        );
        inputCardPanel.setLayout(new BorderLayout(8, 8));
        inputCardPanel.setBorder(JBUI.Borders.empty(8, 10, 8, 8));

        JBScrollPane inputScroll = new JBScrollPane(input);
        inputScroll.setBorder(BorderFactory.createEmptyBorder());
        inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inputScroll.setOpaque(false);
        inputScroll.getViewport().setOpaque(false);
        inputCardPanel.add(inputScroll, BorderLayout.CENTER);

        JPanel inputFooter = new JPanel(new BorderLayout(0, 0));
        inputFooter.setOpaque(false);
        inputFooter.add(send, BorderLayout.EAST);
        inputCardPanel.add(inputFooter, BorderLayout.SOUTH);

        bottom.add(inputCardPanel, BorderLayout.CENTER);

        rightPanel.add(headerPanel, BorderLayout.NORTH);
        rightPanel.add(chatCenterPanel, BorderLayout.CENTER);
        JPanel bottomWrap = new JPanel(new BorderLayout());
        bottomWrap.setOpaque(false);
        bottomWrap.setBorder(JBUI.Borders.empty(8, 8, 8, 8));
        bottomWrap.add(todoPanel, BorderLayout.NORTH);
        bottomWrap.add(bottom, BorderLayout.CENTER);
        rightPanel.add(bottomWrap, BorderLayout.SOUTH);
        
        
        this.splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        this.splitPane.setDividerSize(5);
        this.splitPane.setContinuousLayout(true);
        this.splitPane.setResizeWeight(0.0);
        this.splitPane.setBorder(BorderFactory.createEmptyBorder());
        this.splitPane.setDividerLocation(200);
        setSessionListVisible(false);

        toggleSessionsButton.addActionListener(e -> {
            boolean nextVisible = !leftPanel.isVisible();
            setSessionListVisible(nextVisible);
            if (nextVisible) {
                pruneEmptyCurrentSessionIfNeeded();
                refreshSessionList();
            }
        });

        inputController.bindActions();
        inputController.updateSendButtonMode(false);
        installConversationScrollBehavior();
        
        initSessionList();
        rebuildConversationFromHistory();
        refreshChatHeaderTitle();
        installResponsiveSidebarBehavior();
        installLookAndFeelListener();
        loadTodosAsync();
    }

    JComponent getComponent() {
        return splitPane;
    }

    
    
    private void initSessionList() {
        refreshSessionList();
    }

    private void installResponsiveSidebarBehavior() {
        ComponentAdapter adapter = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                applyResponsiveSidebarState();
            }
        };
        splitPane.addComponentListener(adapter);
        rightPanel.addComponentListener(adapter);
        SwingUtilities.invokeLater(this::applyResponsiveSidebarState);
    }

    private void applyResponsiveSidebarState() {
        if (splitPane == null) {
            return;
        }
        int width = splitPane.getWidth();
        if (width <= 0) {
            return;
        }
        if (width < TOOLWINDOW_AUTO_HIDE_THRESHOLD_PX) {
            requestToolWindowAutoHide();
            return;
        }
        toolWindowAutoHideTriggered = false;
    }

    private void setSessionListVisible(boolean visible) {
        if (leftPanel == null || splitPane == null) {
            return;
        }
        leftPanel.setVisible(visible);
        splitPane.setDividerSize(visible ? 5 : 0);
        splitPane.setDividerLocation(visible ? 220 : 0);
        splitPane.revalidate();
        splitPane.repaint();
    }

    private void requestToolWindowAutoHide() {
        if (toolWindowAutoHideTriggered || project == null || project.isDisposed()) {
            return;
        }
        toolWindowAutoHideTriggered = true;
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("RIKKI");
            if (toolWindow != null && toolWindow.isVisible()) {
                toolWindow.hide(null);
            }
        });
    }

    private void installLookAndFeelListener() {
        if (project != null && !project.isDisposed()) {
            lafMessageBusConnection = project.getMessageBus().connect();
            lafMessageBusConnection.subscribe(LafManagerListener.TOPIC, source ->
                    SwingUtilities.invokeLater(this::refreshThemeAfterLookAndFeelChange));
        }
        lookAndFeelListener = evt -> {
            if (evt == null) {
                return;
            }
            String name = evt.getPropertyName();
            if (!"lookAndFeel".equals(name) && !"UIDefaults".equals(name)) {
                return;
            }
            SwingUtilities.invokeLater(this::refreshThemeAfterLookAndFeelChange);
        };
        UIManager.addPropertyChangeListener(lookAndFeelListener);
        splitPane.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) == 0) {
                return;
            }
            if (splitPane.isDisplayable()) {
                return;
            }
            if (lookAndFeelListener != null) {
                UIManager.removePropertyChangeListener(lookAndFeelListener);
                lookAndFeelListener = null;
            }
            if (lafMessageBusConnection != null) {
                lafMessageBusConnection.disconnect();
                lafMessageBusConnection = null;
            }
        });
    }

    private void refreshThemeAfterLookAndFeelChange() {
        if (project.isDisposed()) {
            return;
        }
        rightPanel.setBackground(UIUtil.getPanelBackground());
        chatTitleLabel.setForeground(UIUtil.getLabelForeground());
        input.setForeground(UIUtil.getLabelForeground());
        input.setCaretColor(UIUtil.getLabelForeground());
        applyTopNavButtonTheme(toggleSessionsButton, false);
        applyTopNavButtonTheme(historyNavButton, false);
        applyTopNavButtonTheme(settingsNavButton, false);
        applyTopNavButtonTheme(newChatNavButton, false);
        if (historyBrowserOverlay != null) {
            historyBrowserOverlay.setColorSuppliers(UIUtil::getPanelBackground, UIUtil::getBoundsColor);
        }
        if (inputCardPanel != null) {
            inputCardPanel.setColorSuppliers(UIUtil::getTextFieldBackground, UIUtil::getBoundsColor);
        }
        inputController.updateSendButtonMode(runtimeBusy || awaitingUserApproval);
        refreshRenderedMarkdownInConversation();
        if (historyBrowserVisible) {
            refreshHistoryBrowserList();
        }
        splitPane.revalidate();
        splitPane.repaint();
    }

    private void openSettingsDialogForCurrentSession() {
        ChatHistoryService.ChatSession session = history.getCurrentSession();
        if (session == null) {
            return;
        }
        SettingsDialog dialog = new SettingsDialog(session.settings);
        if (dialog.showAndGet()) {
            refreshSessionList();
            refreshChatHeaderTitle();
        }
    }
    
    private void refreshSessionList() {
        leftPanel.removeAll();

        JPanel container = new JPanel(new BorderLayout(0, 8));
        container.setOpaque(false);
        container.setBorder(JBUI.Borders.empty(4, 4, 4, 4));

        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setOpaque(false);
        JButton newChatBtn = createHeaderIconButton("\u270E", "New chat");
        newChatBtn.addActionListener(e -> {
            pruneEmptyCurrentSessionIfNeeded();
            history.createSession("New Chat");
            currentSessionId = null;
            refreshSessionList();
            rebuildConversationFromHistory();
            refreshChatHeaderTitle();
        });
        JLabel title = new JLabel("Recent");
        title.setForeground(UIUtil.getContextHelpForeground());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        topBar.add(title, BorderLayout.WEST);
        topBar.add(newChatBtn, BorderLayout.EAST);
        container.add(topBar, BorderLayout.NORTH);

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);

        List<ChatHistoryService.ChatSession> sessions = history.getSessions();
        String currentId = history.getCurrentSessionIdRaw();
        int visibleCount = 0;
        for (ChatHistoryService.ChatSession session : sessions) {
            String titleText = firstNonBlank(session.title, "New Chat");
            SessionListRowPanel item = new SessionListRowPanel();
            item.setLayout(new BorderLayout(8, 0));
            item.setBorder(JBUI.Borders.empty(7, 10, 7, 10));
            item.setAlignmentX(Component.LEFT_ALIGNMENT);
            item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            item.setPreferredSize(new Dimension(10, 38));
            item.setMinimumSize(new Dimension(10, 34));
            if (currentId != null && !currentId.isBlank() && currentId.equals(session.id)) {
                item.setSelectedRow(true);
            }

            JLabel itemTitle = new JLabel(trimForUi(titleText, 54));
            itemTitle.setFont(itemTitle.getFont().deriveFont(Font.BOLD, 13f));
            itemTitle.setForeground(UIUtil.getLabelForeground());
            JLabel age = new JLabel(formatSessionAge(session.createdAt));
            age.setForeground(UIUtil.getContextHelpForeground());
            age.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));
            item.add(itemTitle, BorderLayout.CENTER);
            item.add(age, BorderLayout.EAST);
            item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            installSessionRowInteraction(item, () -> {
                history.setCurrentSession(session.id);
                currentSessionId = firstNonBlank(session.backendSessionId);
                refreshSessionList();
                rebuildConversationFromHistory();
                refreshChatHeaderTitle();
            });
            list.add(item);
            list.add(Box.createVerticalStrut(2));
            visibleCount++;
        }
        if (visibleCount == 0) {
            JLabel empty = new JLabel("No conversations yet");
            empty.setForeground(UIUtil.getContextHelpForeground());
            empty.setBorder(JBUI.Borders.empty(8, 4, 0, 4));
            list.add(empty);
        }

        JBScrollPane listScroll = new JBScrollPane(list);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.getViewport().setOpaque(false);
        listScroll.setOpaque(false);
        container.add(listScroll, BorderLayout.CENTER);

        leftPanel.add(container, BorderLayout.CENTER);
        leftPanel.revalidate();
        leftPanel.repaint();
    }

    private void openHistoryBrowserPopup(Component anchor) {
        if (historyBrowserOverlay == null) {
            return;
        }
        historyBrowserVisible = !historyBrowserVisible;
        historyBrowserOverlay.setVisible(historyBrowserVisible);
        if (historyBrowserVisible) {
            if (historySearchField != null) {
                historySearchField.setText("");
            }
            refreshHistoryBrowserList();
            SwingUtilities.invokeLater(() -> {
                if (historySearchField != null) {
                    historySearchField.requestFocusInWindow();
                }
                historyBrowserOverlay.revalidate();
                historyBrowserOverlay.repaint();
            });
        }
        if (anchor != null) {
            anchor.repaint();
        }
    }

    private RoundedPanel createHistoryBrowserOverlay() {
        RoundedPanel overlay = new RoundedPanel(
                18,
                UIUtil.getPanelBackground(),
                UIUtil.getBoundsColor()
        );
        overlay.setLayout(new BorderLayout(0, 8));
        overlay.setBorder(JBUI.Borders.empty(10, 10, 10, 10));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JPanel searchWrap = new JPanel(new BorderLayout());
        searchWrap.setOpaque(false);
        historySearchField = new JTextField();
        historySearchField.setToolTipText("Search recent tasks");
        historySearchField.putClientProperty("JTextField.placeholderText", "搜索最近任务");
        historySearchField.setBorder(BorderFactory.createCompoundBorder(
                new RoundedLineBorder(UIUtil.getBoundsColor(), 12),
                JBUI.Borders.empty(7, 11, 7, 11)
        ));
        searchWrap.add(historySearchField, BorderLayout.CENTER);

        JPanel filterRow = new JPanel(new BorderLayout());
        filterRow.setOpaque(false);
        filterRow.setBorder(JBUI.Borders.empty(6, 2, 2, 2));
        JLabel filterLeft = new JLabel("所有任务 \u2304");
        filterLeft.setForeground(UIUtil.getLabelForeground());
        filterLeft.setFont(filterLeft.getFont().deriveFont(Font.BOLD, 12f));
        JLabel filterRight = new JLabel("\u25AD");
        filterRight.setForeground(UIUtil.getContextHelpForeground());
        filterRight.setFont(filterRight.getFont().deriveFont(Font.PLAIN, 12f));
        filterRow.add(filterLeft, BorderLayout.WEST);
        filterRow.add(filterRight, BorderLayout.EAST);

        top.add(searchWrap);
        top.add(Box.createVerticalStrut(4));
        top.add(filterRow);
        overlay.add(top, BorderLayout.NORTH);

        historyBrowserList = new JPanel();
        historyBrowserList.setLayout(new BoxLayout(historyBrowserList, BoxLayout.Y_AXIS));
        historyBrowserList.setOpaque(false);

        JBScrollPane listScroll = new JBScrollPane(historyBrowserList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.setOpaque(false);
        listScroll.getViewport().setOpaque(false);
        overlay.add(listScroll, BorderLayout.CENTER);

        historySearchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshHistoryBrowserList();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshHistoryBrowserList();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshHistoryBrowserList();
            }
        });
        return overlay;
    }

    private void refreshHistoryBrowserList() {
        if (historyBrowserList == null) {
            return;
        }
        historyBrowserList.removeAll();
        List<ChatHistoryService.ChatSession> sessions = history.getSessions();
        String currentId = history.getCurrentSessionIdRaw();
        String query = historySearchField == null ? "" : firstNonBlank(historySearchField.getText()).trim().toLowerCase();
        int visibleCount = 0;
        for (ChatHistoryService.ChatSession session : sessions) {
            String titleText = firstNonBlank(session.title, "New Chat");
            if (!query.isBlank() && !titleText.toLowerCase().contains(query)) {
                continue;
            }
            SessionListRowPanel item = new SessionListRowPanel();
            item.setLayout(new BorderLayout(8, 0));
            item.setBorder(JBUI.Borders.empty(8, 10, 8, 10));
            item.setAlignmentX(Component.LEFT_ALIGNMENT);
            item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            if (currentId != null && !currentId.isBlank() && currentId.equals(session.id)) {
                item.setSelectedRow(true);
            }

            JLabel itemTitle = new JLabel(trimForUi(titleText, 72));
            itemTitle.setForeground(UIUtil.getLabelForeground());
            itemTitle.setFont(itemTitle.getFont().deriveFont(Font.BOLD, 13f));

            String ageText = formatSessionAge(session.createdAt);
            JLabel age = new JLabel(ageText);
            age.setForeground(UIUtil.getContextHelpForeground());
            age.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));

            item.add(itemTitle, BorderLayout.CENTER);
            item.add(age, BorderLayout.EAST);
            item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            installSessionRowInteraction(item, () -> {
                history.setCurrentSession(session.id);
                currentSessionId = firstNonBlank(session.backendSessionId);
                rebuildConversationFromHistory();
                refreshChatHeaderTitle();
                refreshSessionList();
                historyBrowserVisible = false;
                if (historyBrowserOverlay != null) {
                    historyBrowserOverlay.setVisible(false);
                }
            });
            historyBrowserList.add(item);
            historyBrowserList.add(Box.createVerticalStrut(2));
            visibleCount++;
        }
        if (visibleCount == 0) {
            JLabel empty = new JLabel("No conversations");
            empty.setForeground(UIUtil.getContextHelpForeground());
            empty.setBorder(JBUI.Borders.empty(12, 6, 0, 6));
            historyBrowserList.add(empty);
        }
        historyBrowserList.revalidate();
        historyBrowserList.repaint();
    }

    private void installSessionRowInteraction(SessionListRowPanel row, Runnable onOpen) {
        if (row == null || onOpen == null) {
            return;
        }
        MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    onOpen.run();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                row.setHoverRow(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                Point pointInRow = SwingUtilities.convertPoint((Component) e.getSource(), e.getPoint(), row);
                if (!row.contains(pointInRow)) {
                    row.setHoverRow(false);
                }
            }
        };
        attachMouseListenerRecursively(row, listener);
    }

    private void attachMouseListenerRecursively(Component component, MouseAdapter listener) {
        if (component == null || listener == null) {
            return;
        }
        component.addMouseListener(listener);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                attachMouseListenerRecursively(child, listener);
            }
        }
    }

    private void refreshChatHeaderTitle() {
        if (chatTitleLabel == null || history == null) {
            return;
        }
        ChatHistoryService.ChatSession session = history.peekCurrentSession();
        chatTitleLabel.setText(trimForUi(session == null ? "New Chat" : firstNonBlank(session.title, "New Chat"), 48));
    }

    private void pruneEmptyCurrentSessionIfNeeded() {
        if (history == null) {
            return;
        }
        ChatHistoryService.ChatSession current = history.peekCurrentSession();
        if (current == null || !isSessionEffectivelyEmpty(current)) {
            return;
        }
        String removingId = current.id;
        history.deleteSession(removingId);
        ChatHistoryService.ChatSession next = history.peekCurrentSession();
        if (next == null) {
            currentSessionId = null;
        } else if (next.backendSessionId != null && !next.backendSessionId.isBlank()) {
            currentSessionId = next.backendSessionId;
        } else {
            currentSessionId = null;
        }
    }

    private boolean isSessionEffectivelyEmpty(ChatHistoryService.ChatSession session) {
        if (session == null) {
            return true;
        }
        if (session.messages != null) {
            for (String line : session.messages) {
                if (line != null && !line.isBlank()) {
                    return false;
                }
            }
        }
        if (session.uiMessages != null) {
            for (ChatHistoryService.UiMessage msg : session.uiMessages) {
                if (msg == null) {
                    continue;
                }
                if (!firstNonBlank(msg.text).isBlank() || !firstNonBlank(msg.thought).isBlank()) {
                    return false;
                }
                if (msg.toolActivities != null) {
                    for (ChatHistoryService.ToolActivity activity : msg.toolActivities) {
                        if (activity == null) {
                            continue;
                        }
                        if (!firstNonBlank(activity.summary, activity.expandedSummary, activity.meta, activity.details, activity.status).isBlank()) {
                            return false;
                        }
                        if (activity.durationMs > 0L) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private JButton createHeaderIconButton(String text, String tooltip) {
        JButton button = new JButton(text == null ? "" : text);
        button.setToolTipText(tooltip);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setFocusable(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFont(button.getFont().deriveFont(Font.BOLD, 13f));
        button.setForeground(UIUtil.getContextHelpForeground());
        button.setMargin(JBUI.insets(2, 8, 2, 8));
        return button;
    }

    private JButton createTopNavIconButton(String text, String tooltip) {
        JButton button = new JButton(text == null ? "" : text);
        button.setToolTipText(tooltip);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setFocusable(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 17f));
        button.setMargin(JBUI.emptyInsets());
        button.setPreferredSize(new Dimension(28, 28));
        button.setMinimumSize(new Dimension(28, 28));
        button.setMaximumSize(new Dimension(28, 28));
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setVerticalAlignment(SwingConstants.CENTER);
        applyTopNavButtonTheme(button, false);
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                applyTopNavButtonTheme(button, true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                applyTopNavButtonTheme(button, false);
            }
        });
        return button;
    }

    private void applyTopNavButtonTheme(JButton button, boolean hovered) {
        if (button == null) {
            return;
        }
        Color base = UIUtil.getLabelForeground();
        button.setForeground(hovered ? base : withAlpha(base, UIUtil.isUnderDarcula() ? 205 : 180));
    }

    private void requestStopCurrentRun() {
        if (!(runtimeBusy || awaitingUserApproval)) {
            return;
        }
        stopRequestedByUser = true;
        setAwaitingUserApproval(false, null);
        setBusy(false, "Stopped");
        Thread streamThread = activeStreamThread;
        if (streamThread != null && streamThread.isAlive()) {
            streamThread.interrupt();
        }
        String sessionId = firstNonBlank(
                currentSessionId,
                history != null && history.peekCurrentSession() != null ? history.peekCurrentSession().backendSessionId : ""
        );
        if (sessionId.isBlank()) {
            return;
        }
        new Thread(() -> {
            boolean ok = stopClient.requestStop(sessionId, "Stopped by user");
            if (!ok) {
                logger.warn("chat.stop_request_failed session=" + sessionId);
            }
        }, "rikki-stop-request").start();
    }

    private String formatSessionAge(long createdAt) {
        if (createdAt <= 0L) {
            return "";
        }
        long diff = Math.max(0L, System.currentTimeMillis() - createdAt);
        long dayMs = 24L * 60L * 60L * 1000L;
        long hourMs = 60L * 60L * 1000L;
        if (diff >= 7L * dayMs) {
            return (diff / dayMs / 7L) + "w";
        }
        if (diff >= dayMs) {
            return (diff / dayMs) + "d";
        }
        if (diff >= hourMs) {
            return (diff / hourMs) + "h";
        }
        return "now";
    }

    

    private void sendMessage(String text) {
        if (text == null || text.trim().isEmpty()) return;
        stopRequestedByUser = false;
        scrollController.enableFollow();
        ChatHistoryService.ChatSession session = history.getCurrentSession();
        if (session != null && session.backendSessionId != null && !session.backendSessionId.isEmpty()) {
            currentSessionId = session.backendSessionId;
        }
        
        
        addMessage(true, text, null, false);
        history.appendLine("You: " + text.trim());
        persistUserUiMessage(text.trim());
        refreshSessionList(); 
        refreshChatHeaderTitle();
        
        
        AgentMessageUI placeholderUi = addMessage(false, null, null, true);
        placeholderUi.historyUiMessageId = createAssistantUiPlaceholder();
        
        
        callAgentStream(text.trim(), placeholderUi);
    }
    
    private void callAgentStream(String userMessage, AgentMessageUI initialUi) {
        stopRequestedByUser = false;
        pendingApprovalKeys.clear();
        setAwaitingUserApproval(false, null);
        setBusy(true, "Agent is thinking...");
        
        new Thread(() -> {
            activeStreamThread = Thread.currentThread();
            ConversationStateManager<AgentMessageUI> assistantState =
                    new ConversationStateManager<>(PENDING_ASSISTANT_KEY, assistantUiStateStore);
            assistantState.bindPending(initialUi);
            BiConsumer<String, String> eventHandler = (event, data) -> dispatchSseEvent(event, data, assistantState);
            ChatSseAdapter streamAdapter = new ChatSseAdapter();
            try {
                String sdkAlignNote = autoAlignProjectSdkIfNeeded();
                if (!sdkAlignNote.isBlank()) {
                    history.appendLine("System: " + sdkAlignNote);
                    SwingUtilities.invokeLater(() -> {
                        addSystemMessage(sdkAlignNote);
                        persistSystemUiMessage(sdkAlignNote);
                    });
                }
                String ideContext = SEND_VERBOSE_IDE_CONTEXT ? buildIdeContextWithTimeout(3000) : "";
                ObjectNode ideContextPayload = buildIdeContextPayloadWithTimeout(3500);
                
                ObjectNode json = mapper.createObjectNode();
                json.put("goal", userMessage);
                json.put("workspaceRoot", workspaceRoot);
                json.put("workspaceName", workspaceName);
                json.put("ideContextContent", ideContext);
                json.set("ideContext", ideContextPayload);
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
                List<String> lines = buildHistoryLinesForRequest();
                if (!lines.isEmpty()) {
                    int start = Math.max(0, lines.size() - HISTORY_SEND_MAX_LINES);
                    for (int i = start; i < lines.size(); i++) {
                        historyNode.add(lines.get(i));
                    }
                }
                
                String payload = mapper.writeValueAsString(json);
                String endpointUrl = System.getProperty("codeagent.endpoint", DEFAULT_AGENT_ENDPOINT + "/stream");
                
                int timeoutMinutes = Integer.getInteger("codeagent.stream.timeout.minutes", 15);
                HttpRequest req = HttpRequest.newBuilder(URI.create(endpointUrl))
                        .timeout(Duration.ofMinutes(timeoutMinutes))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();
                
                HttpResponse<Stream<String>> response = http.send(req, HttpResponse.BodyHandlers.ofLines());
                try (Stream<String> lineStream = response.body()) {
                    lineStream.forEach(line -> {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new CancellationException("SSE interrupted");
                        }
                        streamAdapter.acceptLine(line, eventHandler);
                    });
                } finally {
                    streamAdapter.finish(eventHandler);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!stopRequestedByUser) {
                    logger.warn("stream_interrupted", e);
                }
            } catch (Exception e) {
                boolean cancelled = stopRequestedByUser || isStreamCancellation(e);
                if (!cancelled) {
                    logger.warn("stream_error", e);
                    SwingUtilities.invokeLater(() -> {
                        if (initialUi.answerBuffer.length() == 0 && initialUi.answerPane != null) {
                            initialUi.answerPane.setText("Error: " + firstNonBlank(e.getMessage(), e.getClass().getSimpleName()));
                        }
                    });
                }
            } finally {
                if (Thread.currentThread() == activeStreamThread) {
                    activeStreamThread = null;
                }
                finalizePendingAssistantResponses(assistantState);
                setBusy(false, "Ready");
            }
        }).start();
    }

    private void dispatchSseEvent(String event, String data, ConversationStateManager<AgentMessageUI> assistantState) {
        if (data == null || data.isEmpty()) {
            return;
        }
        handleSseEvent(event, data, assistantState);
    }
    
    private void handleSseEvent(String event, String data, ConversationStateManager<AgentMessageUI> assistantState) {
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
                    AgentMessageUI ui = resolveAssistantUi(assistantState, extractMessageID(node));
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
                    AgentMessageUI ui = findAssistantUi(assistantState, extractMessageID(node));
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
                    AgentMessageUI ui = resolveAssistantUi(assistantState, extractMessageID(node));
                    appendAssistantText(ui, delta);
                } else if ("message_part".equals(event)) {
                    JsonNode node = mapper.readTree(data);
                    String delta = node.path("delta").asText(node.path("text_delta").asText(""));
                    String snapshotText = node.path("text").asText("");
                    if (delta.isBlank() && snapshotText.isBlank()) {
                        return;
                    }
                    AgentMessageUI ui = resolveAssistantUi(assistantState, extractMessageID(node));
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
                                recordSessionChange(lastAssistantUi(assistantState), change);
                            }
                        } catch (Exception ignored) {
                            
                        }
                    }
                } else if ("finish".equals(event)) {
                    JsonNode node = mapper.readTree(data);
                    String messageID = extractMessageID(node);
                    AgentMessageUI ui = resolveAssistantUi(assistantState, messageID);
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
                        setMarkdownContent(ui.answerPane, ans);
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
                    persistAssistantUiSnapshot(ui);
                    scrollToBottomSmart();
                    setBusy(false, "Ready");
                } else if ("error".equals(event)) {
                    AgentMessageUI ui = lastAssistantUi(assistantState);
                    ui.answerBuffer.append("\n\nError: ").append(firstNonBlank(data));
                    setMarkdownContent(ui.answerPane, ui.answerBuffer.toString());
                    ui.streamFinished = true;
                    persistAssistantUiSnapshot(ui);
                } else if ("tool_call".equals(event) || "tool_result".equals(event)) {
                    JsonNode node = mapper.readTree(data);
                    handleToolEvent(event, node, assistantState);
                } else if ("status".equals(event)) {
                    handleStatusEvent(data);
                } else if ("heartbeat".equals(event)) {
                    // keep-alive — no action needed
                } else if ("todo_updated".equals(event)) {
                    JsonNode node = mapper.readTree(data);
                    JsonNode todosNode = node.get("todos");
                    if (todosNode != null) {
                        todoPanel.updateFromJson(todosNode.toString());
                    }
                }
            } catch (Exception e) {
                logger.warn("sse_event_parse_error event=" + event, e);
            }
        });
    }

    private AgentMessageUI resolveAssistantUi(ConversationStateManager<AgentMessageUI> assistantState, String messageID) {
        AgentMessageUI ui = assistantState.resolve(messageID);
        String normalized = assistantUiStateStore.normalize(messageID);
        if (!normalized.isBlank()) {
            ui.messageID = normalized;
        }
        return ui;
    }

    private AgentMessageUI findAssistantUi(ConversationStateManager<AgentMessageUI> assistantState, String messageID) {
        return assistantState.find(messageID);
    }

    private AgentMessageUI lastAssistantUi(ConversationStateManager<AgentMessageUI> assistantState) {
        return assistantState.last();
    }

    private AgentMessageUI createPendingAssistantUi() {
        AgentMessageUI created = addMessage(false, null, null, true);
        created.historyUiMessageId = createAssistantUiPlaceholder();
        return created;
    }

    private AgentMessageUI createAssistantUiForMessageId(String messageId) {
        AgentMessageUI created = createPendingAssistantUi();
        created.messageID = messageId;
        return created;
    }

    private String extractMessageID(JsonNode node) {
        if (node == null || node.isMissingNode()) return null;
        if (node.has("messageID")) return node.path("messageID").asText(null);
        if (node.has("messageId")) return node.path("messageId").asText(null);
        if (node.has("id")) return node.path("id").asText(null);
        return null;
    }

    private void handleToolEvent(String event, JsonNode node, ConversationStateManager<AgentMessageUI> assistantState) {
        String messageID = null;
        if (node != null) {
            if (node.has("messageID")) {
                messageID = node.path("messageID").asText(null);
            } else if (node.has("messageId")) {
                messageID = node.path("messageId").asText(null);
            }
        }
        AgentMessageUI ui = (messageID == null || messageID.isBlank())
                ? lastAssistantUi(assistantState)
                : resolveAssistantUi(assistantState, messageID);
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
        JsonNode metaNode = extractToolMeta(node);
        toolEventStateMachine.apply(event, node, ui, state, argsNode, metaNode);

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
            setAwaitingUserApproval(false, null);
            setBusy(false, "Ready");
        } else if ("waiting_approval".equals(statusType) || "awaiting_approval".equals(statusType)) {
            setBusy(true, "Agent is thinking...");
            setAwaitingUserApproval(true, "Awaiting your approval...");
        } else if ("busy".equals(statusType) || "running".equals(statusType) || "pending".equals(statusType) || "retry".equals(statusType)) {
            setBusy(true, "Agent is thinking...");
        }
    }

    private ToolActivityState resolveToolActivity(AgentMessageUI ui, String callID, String toolName) {
        return toolActivityRenderer.resolveToolActivity(ui, callID, toolName);
    }

    private void ensureToolRenderType(AgentMessageUI ui, ToolActivityState state, ToolRenderType desiredType) {
        toolActivityRenderer.ensureToolRenderType(ui, state, desiredType);
    }

    private void rebuildToolHostContent(ToolActivityState state) {
        toolActivityRenderer.rebuildToolHostContent(state);
    }

    private void refreshInlineDiffCard(ToolActivityState state) {
        toolActivityRenderer.refreshInlineDiffCard(state);
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
        return toolActivityRenderer.ensureActivityContainer(ui);
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
        return toolMetaExtractor.extractToolMeta(node);
    }

    private PendingChangesManager.PendingChange extractPendingChange(JsonNode metaNode) {
        return toolMetaExtractor.extractPendingChange(metaNode, workspaceRoot, currentSessionId);
    }

    private PendingChangesManager.PendingChange synthesizePendingChangeFromArgs(String toolName, JsonNode argsNode) {
        return toolMetaExtractor.synthesizePendingChangeFromArgs(
                toolName,
                argsNode,
                workspaceRoot,
                currentSessionId,
                this::isModificationTool,
                this::isDeleteTool
        );
    }

    private PendingCommandInfo extractPendingCommand(JsonNode metaNode) {
        return toolMetaExtractor.extractPendingCommand(metaNode, workspaceRoot, currentSessionId);
    }

    private String extractMetaOutput(JsonNode metaNode) {
        return toolMetaExtractor.extractMetaOutput(metaNode, this::prettyJson);
    }

    private class ToolEventStateMachine {
        void apply(
                String event,
                JsonNode node,
                AgentMessageUI ui,
                ToolActivityState state,
                JsonNode argsNode,
                JsonNode metaNode
        ) {
            if ("tool_call".equals(event)) {
                applyToolCall(node, state, argsNode, metaNode);
                return;
            }
            if ("tool_result".equals(event)) {
                applyToolResult(node, ui, state, argsNode, metaNode);
            }
        }

        private void applyToolCall(JsonNode node, ToolActivityState state, JsonNode argsNode, JsonNode metaNode) {
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
            state.workspaceApplied = state.workspaceApplied || isWorkspaceApplied(metaNode);

            applyPendingCommand(state, extractPendingCommand(metaNode), false);
            PendingChangesManager.PendingChange extracted = extractPendingChange(metaNode);
            PendingChangesManager.PendingChange synthesized = synthesizePendingChangeFromArgs(state.tool, argsNode);
            PendingChangesManager.PendingChange pendingChange = mergePendingChangeCandidates(extracted, synthesized);
            pendingChange = hydratePendingChangeContent(pendingChange);
            applyPendingChange(state, pendingChange);
            state.exitCode = null;
        }

        private void applyToolResult(
                JsonNode node,
                AgentMessageUI ui,
                ToolActivityState state,
                JsonNode argsNode,
                JsonNode metaNode
        ) {
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
            state.workspaceApplied = state.workspaceApplied || isWorkspaceApplied(metaNode);
            state.output = extractToolOutput(node, metaNode);
            state.exitCode = extractExitCode(node, metaNode);

            JsonNode sourceArgs = (state.lastArgs != null && !state.lastArgs.isNull()) ? state.lastArgs : argsNode;
            PendingChangesManager.PendingChange extracted = extractPendingChange(metaNode);
            PendingChangesManager.PendingChange synthesized = synthesizePendingChangeFromArgs(state.tool, sourceArgs);
            PendingChangesManager.PendingChange pendingChange = mergePendingChangeCandidates(extracted, synthesized);
            pendingChange = hydratePendingChangeContent(pendingChange);
            applyPendingChange(state, pendingChange);
            applyPendingCommand(state, extractPendingCommand(metaNode), true);

            if ("completed".equalsIgnoreCase(state.status)
                    && state.pendingChange != null
                    && !state.deleteDecisionRequired) {
                applyAndRecordSessionChange(ui, state, state.pendingChange);
            }
        }

        private void applyPendingCommand(
                ToolActivityState state,
                PendingCommandInfo pendingCommand,
                boolean fillIntentFromDescription
        ) {
            if (pendingCommand == null) {
                return;
            }
            state.pendingCommand = pendingCommand;
            state.commandDecisionRequired = !state.commandDecisionMade;
            state.status = "awaiting_approval";
            if (state.commandSummary == null || state.commandSummary.isBlank()) {
                state.commandSummary = trimForUi(pendingCommand.command, 180);
            }
            if (fillIntentFromDescription
                    && (state.intentSummary == null || state.intentSummary.isBlank())
                    && pendingCommand.description != null
                    && !pendingCommand.description.isBlank()) {
                state.intentSummary = trimForUi(pendingCommand.description, 160);
            }
        }

        private void applyPendingChange(ToolActivityState state, PendingChangesManager.PendingChange pendingChange) {
            if (pendingChange == null) {
                return;
            }
            state.pendingChange = pendingChange;
            state.deleteDecisionRequired = isDeleteTool(state.tool)
                    && "DELETE".equalsIgnoreCase(pendingChange.type)
                    && !state.deleteDecisionMade;
        }
    }

    private class ToolActivityRenderer {
        ToolActivityState resolveToolActivity(AgentMessageUI ui, String callID, String toolName) {
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
            activityPanel.add(Box.createVerticalStrut(1));
            ensureToolRenderType(ui, created, classifyToolRenderType(toolName, null, null));
            ui.messagePanel.revalidate();
            ui.messagePanel.repaint();
            return created;
        }

        void ensureToolRenderType(AgentMessageUI ui, ToolActivityState state, ToolRenderType desiredType) {
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
                activityPanel.add(Box.createVerticalStrut(1));
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

        void rebuildToolHostContent(ToolActivityState state) {
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

        void refreshInlineDiffCard(ToolActivityState state) {
            if (state == null || state.hostPanel == null) {
                return;
            }
            // Remove existing diff card
            if (state.inlineDiffCard != null) {
                state.hostPanel.remove(state.inlineDiffCard);
                state.inlineDiffCard = null;
                state.hostPanel.revalidate();
                state.hostPanel.repaint();
            }
            // Show compact diff card only when tool has completed with a file change
            if (!"completed".equalsIgnoreCase(state.status) || state.pendingChange == null) {
                return;
            }
            PendingChangesManager.PendingChange displayChange = hydratePendingChangeContent(state.pendingChange);
            LineDiffStat stat = computeLineDiffStat(displayChange);

            JPanel card = new JPanel(new BorderLayout(6, 0));
            card.setOpaque(false);
            card.setBorder(JBUI.Borders.empty(1, 4, 2, 4));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(22)));
            card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JLabel nameLabel = new JLabel(shortFileName(state.pendingChange.path));
            nameLabel.setFont(nameLabel.getFont().deriveFont(JBUI.scaleFontSize(11f)));
            nameLabel.setForeground(UIUtil.getContextHelpForeground());

            JPanel stats = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            stats.setOpaque(false);
            if (stat.added > 0 || stat.removed > 0) {
                JLabel addLabel = new JLabel("+" + stat.added);
                addLabel.setFont(addLabel.getFont().deriveFont(JBUI.scaleFontSize(11f)));
                addLabel.setForeground(resolveSuccessColor());
                stats.add(addLabel);
                JLabel rmLabel = new JLabel("-" + stat.removed);
                rmLabel.setFont(rmLabel.getFont().deriveFont(JBUI.scaleFontSize(11f)));
                rmLabel.setForeground(resolveRemovalColor());
                stats.add(rmLabel);
            }

            card.add(nameLabel, BorderLayout.CENTER);
            card.add(stats, BorderLayout.EAST);

            PendingChangesManager.PendingChange finalChange = displayChange;
            card.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    diffService.showDiffExplicit(
                            finalChange.path,
                            finalChange.oldContent,
                            finalChange.newContent
                    );
                }
            });

            state.inlineDiffCard = card;
            state.hostPanel.add(card);
            state.hostPanel.revalidate();
            state.hostPanel.repaint();
        }

        JPanel ensureActivityContainer(AgentMessageUI ui) {
            if (ui.activityPanel != null) {
                return ui.activityPanel;
            }
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(JBUI.Borders.empty(1, 0, 1, 0));
            panel.setOpaque(false);

            int insertIndex = ui.messagePanel.getComponentCount();
            if (ui.answerPane != null) {
                int answerIndex = ui.messagePanel.getComponentZOrder(ui.answerPane);
                if (answerIndex >= 0) {
                    insertIndex = Math.min(ui.messagePanel.getComponentCount(), answerIndex + 1);
                }
            }
            ui.messagePanel.add(panel, insertIndex);
            ui.messagePanel.add(Box.createVerticalStrut(2), insertIndex + 1);
            ui.activityPanel = panel;
            ui.messagePanel.revalidate();
            ui.messagePanel.repaint();
            return panel;
        }
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

    private static Color withAlpha(Color color, int alpha) {
        if (color == null) {
            return new Color(0, 0, 0, Math.max(0, Math.min(255, alpha)));
        }
        int a = Math.max(0, Math.min(255, alpha));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), a);
    }

    private static Color resolveSuccessColor() {
        Color success = resolveUiColor("Label.successForeground", null);
        if (success != null) {
            return success;
        }
        success = resolveUiColor("Actions.Green", null);
        if (success != null) {
            return success;
        }
        success = resolveUiColor("Component.successFocusColor", null);
        if (success != null) {
            return success;
        }
        return new JBColor(new Color(0x36B336), new Color(0x59B85C));
    }

    private static Color resolveRemovalColor() {
        Color danger = resolveUiColor("Label.errorForeground", null);
        if (danger != null) {
            return danger;
        }
        danger = resolveUiColor("Actions.Red", null);
        if (danger != null) {
            return danger;
        }
        danger = resolveUiColor("Component.errorFocusColor", null);
        if (danger != null) {
            return danger;
        }
        return new JBColor(new Color(0xCF222E), new Color(0xF47067));
    }

    private JComponent createDiffStatPanel(LineDiffStat stat) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        panel.setOpaque(false);

        JLabel added = new JLabel("+" + Math.max(0, stat == null ? 0 : stat.added));
        added.setForeground(resolveSuccessColor());
        added.setFont(added.getFont().deriveFont(Font.BOLD));

        JLabel removed = new JLabel("-" + Math.max(0, stat == null ? 0 : stat.removed));
        removed.setForeground(resolveRemovalColor());
        removed.setFont(removed.getFont().deriveFont(Font.BOLD));

        panel.add(added);
        panel.add(removed);
        return panel;
    }

    private static Color resolveUiColor(String key, Color fallback) {
        Color color = key == null ? null : UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    private List<String> buildHistoryLinesForRequest() {
        List<String> normalized = new ArrayList<>();
        if (history == null) {
            return normalized;
        }
        List<String> lines = history.getLines();
        if (lines != null) {
            for (String line : lines) {
                if (line != null && !line.isBlank()) {
                    normalized.add(line);
                }
            }
        }
        if (!normalized.isEmpty()) {
            return normalized;
        }
        ChatHistoryService.ChatSession session = history.getCurrentSession();
        if (session == null || session.uiMessages == null) {
            return normalized;
        }
        for (ChatHistoryService.UiMessage message : session.uiMessages) {
            if (message == null) {
                continue;
            }
            String text = firstNonBlank(message.text);
            if (text.isBlank()) {
                continue;
            }
            String role = firstNonBlank(message.role).toLowerCase();
            if ("user".equals(role)) {
                normalized.add("You: " + text.trim());
            } else if ("assistant".equals(role) || "agent".equals(role)) {
                normalized.add("Agent: " + text.trim());
            } else if ("system".equals(role)) {
                normalized.add("System: " + text.trim());
            }
        }
        return normalized;
    }

    private boolean isWorkspaceApplied(JsonNode metaNode) {
        if (metaNode == null || metaNode.isMissingNode() || metaNode.isNull()) {
            return false;
        }
        JsonNode appliedNode = metaNode.get("workspace_applied");
        if (appliedNode != null && appliedNode.isBoolean()) {
            return appliedNode.asBoolean(false);
        }
        JsonNode legacyNode = metaNode.get("applied_to_workspace");
        if (legacyNode != null && legacyNode.isBoolean()) {
            return legacyNode.asBoolean(false);
        }
        return false;
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
                || "ide_context".equals(normalized)
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

    private boolean isInstallOrDownloadRisk(PendingCommandInfo pendingCommand) {
        if (pendingCommand == null) {
            return false;
        }
        if (pendingCommand.reasons != null) {
            for (String reason : pendingCommand.reasons) {
                String normalized = reason == null ? "" : reason.toLowerCase();
                if (normalized.contains("install")
                        || normalized.contains("download")
                        || normalized.contains("toolchain")
                        || normalized.contains("package")) {
                    return true;
                }
            }
        }
        String command = pendingCommand.command == null ? "" : pendingCommand.command.toLowerCase();
        return command.contains(" install ")
                || command.contains("winget")
                || command.contains("choco")
                || command.contains("apt-get")
                || command.contains("brew install")
                || command.contains("pip install")
                || command.contains("npm install")
                || command.contains("wget ")
                || command.contains("curl ");
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
            boolean installOrDownload = isInstallOrDownloadRisk(state.pendingCommand);
            if (state.pendingCommand.strictApproval) {
                state.panel.setDecisionActions(
                        installOrDownload ? "Approve install" : "Approve run",
                        "Skip",
                        true,
                        () -> handleCommandDecision(ui, state, CommandApprovalDecision.APPROVE_ONCE),
                        () -> handleCommandDecision(ui, state, CommandApprovalDecision.REJECT),
                        true
                );
            } else {
                state.panel.setCommandDecisionActions(
                        installOrDownload ? "Approve once" : "Run once",
                        "Whitelist",
                        "Always allow",
                        "Skip",
                        true,
                        () -> handleCommandDecision(ui, state, CommandApprovalDecision.APPROVE_ONCE),
                        () -> handleCommandDecision(ui, state, CommandApprovalDecision.APPROVE_WHITELIST),
                        () -> handleCommandDecision(ui, state, CommandApprovalDecision.APPROVE_ALWAYS_NON_DESTRUCTIVE),
                        () -> handleCommandDecision(ui, state, CommandApprovalDecision.REJECT),
                        true
                );
            }
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

    private void handleCommandDecision(AgentMessageUI ui, ToolActivityState state, CommandApprovalDecision decision) {
        if (state == null || state.pendingCommand == null || state.panel == null) {
            return;
        }
        state.panel.setDecisionEnabled(false);
        boolean reject = decision == CommandApprovalDecision.REJECT;
        String decisionMode = resolveDecisionMode(decision);
        resolvePendingCommand(state.pendingCommand, reject, decisionMode, result -> {
            state.commandDecisionMade = true;
            state.commandDecisionRequired = false;
            state.panel.clearDecisionActions();
            if (result.success) {
                if (!reject) {
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
                state.error = firstNonBlank(result.error, reject ? "Failed to reject command." : "Failed to execute approved command.");
            }
            refreshToolActivityUi(state);
            syncApprovalState(state);
            scrollToBottomSmart();
        });
    }

    private String resolveDecisionMode(CommandApprovalDecision decision) {
        if (decision == null) {
            return DECISION_MANUAL;
        }
        return switch (decision) {
            case APPROVE_WHITELIST -> DECISION_WHITELIST;
            case APPROVE_ALWAYS_NON_DESTRUCTIVE -> DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE;
            case APPROVE_ONCE, REJECT -> DECISION_MANUAL;
        };
    }

    private void resolvePendingCommand(
            PendingCommandInfo pendingCommand,
            boolean reject,
            String decisionMode,
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
                payload.put("decisionMode", firstNonBlank(decisionMode, DECISION_MANUAL));

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

    private void loadTodosAsync() {
        if (workspaceRoot == null || workspaceRoot.isBlank()) return;
        String url = resolveTodosEndpoint();
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                    todoPanel.updateFromJson(resp.body());
                }
            } catch (Exception e) {
                logger.warn("Failed to load todos from backend: " + e.getMessage());
            }
        });
    }

    private String resolveTodosEndpoint() {
        String base = System.getProperty("codeagent.endpoint", DEFAULT_AGENT_ENDPOINT);
        int idx = base.indexOf("/api/agent");
        String apiBase = idx >= 0 ? base.substring(0, idx) + "/api/agent" : base;
        try {
            String encoded = URLEncoder.encode(workspaceRoot, StandardCharsets.UTF_8);
            return apiBase + "/todos?workspaceRoot=" + encoded;
        } catch (Exception e) {
            return apiBase + "/todos?workspaceRoot=" + workspaceRoot.replace("\\", "/");
        }
    }

    private String resolvePendingCommandEndpoint() {        String override = System.getProperty("codeagent.pending.command.endpoint");
        if (override != null && !override.isBlank()) {
            return override;
        }
        String base = System.getProperty("codeagent.endpoint", DEFAULT_AGENT_ENDPOINT);
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
            if (state.pendingCommand != null && isInstallOrDownloadRisk(state.pendingCommand)) {
                sb.append("# explicit user consent required for installation/download").append('\n');
            }
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
        boolean running = isToolStatusRunning(state.status);
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
            state.panel.setRunning(running);
        }
        if (state.inlineRow != null) {
            state.inlineRow.setSummaryText(state.uiSummary);
            state.inlineRow.setMetaText(state.uiMeta, colorForToolStatus(state.status));
            state.inlineRow.setRunning(running);
            if (state.renderType == ToolRenderType.READ && state.targetNavigable) {
                state.inlineRow.setAction(true, () -> navigateToReadTarget(state));
            } else {
                state.inlineRow.setAction(false, null);
            }
        }
        refreshInlineDiffCard(state);
    }

    private boolean isToolStatusRunning(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = status.trim().toLowerCase();
        return "running".equals(normalized)
                || "pending".equals(normalized)
                || "retry".equals(normalized);
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
        return ToolActivityFormatter.resolveTerminalTypeLabel(toolName);
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
        return ToolActivityFormatter.toCommandPreview(command, maxLen);
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
        return workspacePathResolver.isNavigableFileTarget(rawPath);
    }

    private Path resolveWorkspaceFilePath(String rawPath) {
        return workspacePathResolver.resolveWorkspaceFilePath(rawPath);
    }

    private Path parseAnyPath(String rawPath) {
        return workspacePathResolver.parseAnyPath(rawPath);
    }

    private boolean isUnderWorkspaceRoot(Path target, Path root) {
        return workspacePathResolver.isUnderWorkspaceRoot(target, root);
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
        return ChatUiTextFormatter.summarizeInputDetails(argsNode);
    }

    private String summarizeArgs(JsonNode argsNode) {
        return ChatUiTextFormatter.summarizeArgs(argsNode);
    }

    private String trimForUi(String text, int maxLen) {
        return ChatUiTextFormatter.trimForUi(text, maxLen);
    }

    private String summarizeCommand(JsonNode argsNode, String fallback) {
        return ChatUiTextFormatter.summarizeCommand(argsNode, fallback);
    }

    private String extractBashCommand(JsonNode argsNode, String fallback) {
        return ChatUiTextFormatter.extractBashCommand(argsNode, fallback);
    }

    private Color colorForToolStatus(String status) {
        return ToolStatusFormatter.colorForToolStatus(status);
    }

    private String normalizeToolStatusLabel(String status) {
        return ToolStatusFormatter.normalizeToolStatusLabel(status);
    }

    private String formatDuration(long durationMs) {
        return ToolStatusFormatter.formatDuration(durationMs);
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
        setMarkdownContent(ui.answerPane, ui.answerBuffer.toString());
        scrollToBottomSmart();
    }

    private void setMarkdownContent(JEditorPane pane, String markdown) {
        if (pane == null) {
            return;
        }
        String source = markdown == null ? "" : markdown;
        pane.putClientProperty(MARKDOWN_SOURCE_CLIENT_KEY, source);
        pane.setText(MarkdownUtils.renderToHtml(source));
        reflowMarkdownPane(pane);
    }

    private JEditorPane createMarkdownPane() {
        JEditorPane pane = new JEditorPane() {
            @Override
            public Dimension getPreferredSize() {
                int targetWidth = resolveMarkdownPaneWidth(this);
                if (targetWidth > 0) {
                    super.setSize(targetWidth, Short.MAX_VALUE);
                }
                return super.getPreferredSize();
            }
        };
        pane.setEditable(false);
        pane.setContentType("text/html");
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.setOpaque(false);
        pane.setForeground(UIUtil.getLabelForeground());
        return pane;
    }

    private int resolveMarkdownPaneWidth(JEditorPane pane) {
        Container parent = pane.getParent();
        if (parent != null && parent.getWidth() > 0) {
            return Math.max(1, parent.getWidth());
        }
        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, pane);
        if (viewport != null && viewport.getWidth() > 0) {
            return Math.max(1, viewport.getWidth() - JBUI.scale(20));
        }
        return -1;
    }

    private void reflowMarkdownPane(JEditorPane pane) {
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed() || pane == null) {
                return;
            }
            int targetWidth = resolveMarkdownPaneWidth(pane);
            if (targetWidth > 0) {
                pane.setSize(new Dimension(targetWidth, Short.MAX_VALUE));
            }
            JScrollBar horizontal = scrollPane == null ? null : scrollPane.getHorizontalScrollBar();
            if (horizontal != null && horizontal.getValue() != 0) {
                horizontal.setValue(0);
            }
            pane.revalidate();
            pane.repaint();
            conversationList.revalidate();
            conversationList.repaint();
        });
    }

    private void refreshRenderedMarkdownInConversation() {
        if (conversationList == null) {
            return;
        }
        refreshMarkdownRecursively(conversationList);
    }

    private void refreshMarkdownRecursively(Component component) {
        if (component == null) {
            return;
        }
        if (component instanceof JEditorPane pane) {
            Object source = pane.getClientProperty(MARKDOWN_SOURCE_CLIENT_KEY);
            if (source instanceof String markdown) {
                setMarkdownContent(pane, markdown);
            }
            return;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                refreshMarkdownRecursively(child);
            }
        }
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
        setMarkdownContent(ui.answerPane, fullText);
        scrollToBottomSmart();
    }

    private void flushDeferredAnswer(AgentMessageUI ui) {
        if (ui == null || ui.deferredAnswerBuffer.length() == 0) {
            return;
        }
        ui.answerBuffer.append(ui.deferredAnswerBuffer);
        ui.deferredAnswerBuffer.setLength(0);
        if (ui.answerPane != null) {
            setMarkdownContent(ui.answerPane, ui.answerBuffer.toString());
            scrollToBottomSmart();
        }
    }

    private void finalizePendingAssistantResponses(ConversationStateManager<AgentMessageUI> assistantState) {
        SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            for (AgentMessageUI ui : assistantState.uniqueSnapshot()) {
                if (ui == null || ui.streamFinished) {
                    continue;
                }
                ui.thinkingOpen = false;
                flushDeferredAnswer(ui);
                if (ui.answerPane != null && ui.answerBuffer.length() > 0) {
                    setMarkdownContent(ui.answerPane, ui.answerBuffer.toString());
                }
                if (history != null && !ui.historyCommitted && ui.answerBuffer.length() > 0) {
                    history.appendLine("Agent: " + ui.answerBuffer);
                    ui.historyCommitted = true;
                }
                ui.streamFinished = true;
                persistAssistantUiSnapshot(ui);
            }
            finalizeSessionChangeSummary(assistantState);
            scrollToBottomSmart();
        });
    }

    private void finalizeSessionChangeSummary(ConversationStateManager<AgentMessageUI> assistantState) {
        if (assistantState == null) {
            return;
        }
        List<AgentMessageUI> snapshot = assistantState.uniqueSnapshot();
        if (snapshot.isEmpty()) {
            return;
        }

        AgentMessageUI finalUi = lastAssistantUi(assistantState);
        if (finalUi == null) {
            return;
        }

        LinkedHashMap<String, PendingChangesManager.PendingChange> merged = new LinkedHashMap<>();
        for (AgentMessageUI ui : snapshot) {
            if (ui == null || ui.sessionChanges == null || ui.sessionChanges.isEmpty()) {
                continue;
            }
            for (PendingChangesManager.PendingChange change : ui.sessionChanges.values()) {
                if (change == null || change.path == null || change.path.isBlank()) {
                    continue;
                }
                PendingChangesManager.PendingChange normalized = normalizeChangeForDirectApply(change);
                if (normalized == null) {
                    continue;
                }
                String key = sessionChangeKey(normalized);
                PendingChangesManager.PendingChange existing = merged.get(key);
                if (existing == null) {
                    merged.put(key, copyPendingChange(normalized));
                    continue;
                }
                PendingChangesManager.PendingChange mergedChange = new PendingChangesManager.PendingChange(
                        firstNonBlank(normalized.id, existing.id, java.util.UUID.randomUUID().toString()),
                        firstNonBlank(normalized.path, existing.path),
                        mergeChangeType(existing.type, normalized.type),
                        firstNonBlank(existing.oldContent, normalized.oldContent),
                        firstNonBlank(normalized.newContent, existing.newContent),
                        firstNonBlank(normalized.preview, existing.preview),
                        Math.max(normalized.timestamp, existing.timestamp),
                        firstNonBlank(normalized.workspaceRoot, existing.workspaceRoot, workspaceRoot),
                        firstNonBlank(normalized.sessionId, existing.sessionId, currentSessionId)
                );
                merged.put(key, mergedChange);
            }
        }

        for (AgentMessageUI ui : snapshot) {
            if (ui == null) {
                continue;
            }
            clearSessionChangeSummaryPanel(ui);
            if (ui != finalUi) {
                ui.sessionChanges.clear();
                ui.undoneSessionChangeKeys.clear();
                ui.sessionChangeSummaryShown = false;
            }
        }

        finalUi.sessionChanges.clear();
        finalUi.sessionChanges.putAll(merged);
        finalUi.sessionChangeSummaryShown = false;
        appendSessionChangeSummaryIfNeeded(finalUi);

        for (AgentMessageUI ui : snapshot) {
            if (ui == null) {
                continue;
            }
            persistAssistantUiSnapshot(ui);
        }
    }

    private void clearSessionChangeSummaryPanel(AgentMessageUI ui) {
        if (ui == null || ui.messagePanel == null || ui.sessionChangeSummaryPanel == null) {
            return;
        }
        ui.messagePanel.remove(ui.sessionChangeSummaryPanel);
        ui.sessionChangeSummaryPanel = null;
        ui.sessionChangeSummaryShown = false;
        ui.messagePanel.revalidate();
        ui.messagePanel.repaint();
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
                if (state.pendingChange != null) {
                    PendingChangesManager.PendingChange persistedChange = normalizeChangeForDirectApply(state.pendingChange);
                    if (persistedChange != null) {
                        activity.changePath = firstNonBlank(persistedChange.path);
                        activity.changeType = firstNonBlank(persistedChange.type, "EDIT");
                        activity.changeOldContent = firstNonBlank(persistedChange.oldContent);
                        activity.changeNewContent = firstNonBlank(persistedChange.newContent);
                    }
                }
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

        if (ui.sessionChanges != null && !ui.sessionChanges.isEmpty()) {
            for (PendingChangesManager.PendingChange change : ui.sessionChanges.values()) {
                if (change == null || change.path == null || change.path.isBlank()) continue;
                ChatHistoryService.PersistedChange pc = new ChatHistoryService.PersistedChange();
                pc.path = change.path;
                pc.type = change.type != null ? change.type : "EDIT";
                pc.oldContent = change.oldContent != null ? change.oldContent : "";
                pc.newContent = change.newContent != null ? change.newContent : "";
                message.sessionChanges.add(pc);
            }
        }
        if (ui.undoneSessionChangeKeys != null && !ui.undoneSessionChangeKeys.isEmpty()) {
            message.undoneSessionChangeKeys.addAll(ui.undoneSessionChangeKeys);
        }
        message.showSessionChangeSummary = ui.sessionChangeSummaryShown;

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
        label.setForeground(UIUtil.getContextHelpForeground());
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
        messagePanel.setBorder(JBUI.Borders.empty(6, 0));
        ui.messagePanel = messagePanel;
        
        if (isUser) {
            Color panelBg = UIUtil.getPanelBackground();
            Color selectionBg = UIManager.getColor("List.selectionBackground");
            if (selectionBg == null) {
                selectionBg = JBColor.BLUE;
            }
            JPanel bubble = new RoundedPanel(
                    12,
                    ColorUtil.mix(panelBg, selectionBg, UIUtil.isUnderDarcula() ? 0.26 : 0.12),
                    ColorUtil.mix(panelBg, selectionBg, UIUtil.isUnderDarcula() ? 0.42 : 0.24)
            );
            bubble.setLayout(new BorderLayout());
            bubble.setBorder(JBUI.Borders.empty(8, 9, 8, 9));
            
            JTextArea textArea = new JTextArea("You: " + text);
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            textArea.setEditable(false);
            textArea.setOpaque(false);
            textArea.setForeground(UIUtil.getLabelForeground());
            textArea.setBorder(JBUI.Borders.empty(0));
            
            bubble.add(textArea, BorderLayout.CENTER);
            messagePanel.add(bubble);
        } else {
            
            
            boolean hasThought = response != null && response.thought != null && !response.thought.isEmpty();
            if (hasThought) {
                String initialThought = response.thought;
                CollapsiblePanel thoughtPanel = new CollapsiblePanel("Thinking Process", initialThought, animate);
                messagePanel.add(thoughtPanel);
                messagePanel.add(Box.createVerticalStrut(5));
                ui.thoughtPanel = thoughtPanel;
            }
            
            
            String answerText = (response != null) ? response.answer : text;
            JEditorPane content = createMarkdownPane();
            
            if (animate && answerText != null && !answerText.isEmpty()) {
                typewriterEffect(content, answerText);
            } else {
                setMarkdownContent(content, answerText != null ? answerText : "");
            }
            
            if (answerText != null) {
                ui.answerBuffer.append(answerText);
            }
            
            messagePanel.add(content);
            ui.answerPane = content;
            
            
            if (response != null && response.changes != null && !response.changes.isEmpty()) {
                for (PendingChangesManager.PendingChange change : response.changes) {
                    applyAndRecordSessionChange(ui, null, change);
                }
            }
        }
        
        conversationList.add(messagePanel);
        
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
            boolean shouldApplyLocally = state == null || !state.workspaceApplied;
            if (shouldApplyLocally) {
                diffService.applyChange(change);
            }
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

        if ("EDIT".equals(normalizedType)) {
            if (oldContent.isBlank() && exists) {
                try {
                    oldContent = Files.readString(absolute, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    oldContent = "";
                }
            }
            if (!exists) {
                normalizedType = "CREATE";
            }
        } else if ("CREATE".equals(normalizedType)) {
            // Do NOT read oldContent from disk for CREATE: the file was just written,
            // reading it now would give newContent == oldContent → diff +0 -0.
            if (exists && !oldContent.isBlank()) {
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

    private PendingChangesManager.PendingChange mergePendingChangeCandidates(
            PendingChangesManager.PendingChange primary,
            PendingChangesManager.PendingChange fallback
    ) {
        if (primary == null) {
            return fallback == null ? null : copyPendingChange(fallback);
        }
        if (fallback == null) {
            return copyPendingChange(primary);
        }
        String path = firstNonBlank(primary.path, fallback.path);
        if (path.isBlank()) {
            return null;
        }
        return new PendingChangesManager.PendingChange(
                firstNonBlank(primary.id, fallback.id, java.util.UUID.randomUUID().toString()),
                path,
                mergeChangeType(primary.type, fallback.type),
                firstNonBlank(primary.oldContent, fallback.oldContent),
                firstNonBlank(primary.newContent, fallback.newContent),
                firstNonBlank(primary.preview, fallback.preview),
                Math.max(primary.timestamp, fallback.timestamp),
                firstNonBlank(primary.workspaceRoot, fallback.workspaceRoot, workspaceRoot),
                firstNonBlank(primary.sessionId, fallback.sessionId, currentSessionId)
        );
    }

    private PendingChangesManager.PendingChange hydratePendingChangeContent(PendingChangesManager.PendingChange source) {
        if (source == null || source.path == null || source.path.isBlank()) {
            return source;
        }
        String oldContent = firstNonBlank(source.oldContent);
        String newContent = firstNonBlank(source.newContent);
        String normalizedType = firstNonBlank(source.type, "EDIT").trim().toUpperCase(Locale.ROOT);
        if (!newContent.isBlank() || "DELETE".equals(normalizedType)) {
            return source;
        }
        Path absolute = resolvePathForChange(source.path, firstNonBlank(source.workspaceRoot, workspaceRoot));
        if (absolute == null || !Files.exists(absolute) || !Files.isRegularFile(absolute)) {
            return source;
        }
        try {
            newContent = Files.readString(absolute, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            newContent = "";
        }
        if (newContent.isBlank()) {
            return source;
        }
        return new PendingChangesManager.PendingChange(
                firstNonBlank(source.id, java.util.UUID.randomUUID().toString()),
                source.path,
                source.type,
                oldContent,
                newContent,
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
        // If tool activities exist and answerPane is currently before them,
        // move answerPane to appear AFTER the activity panel so the layout is:
        //   [tool activities + diff cards]
        //   [final answer text]
        //   [session summary + undo buttons]
        if (ui.activityPanel != null && ui.answerPane != null && ui.messagePanel != null) {
            int answerIdx = ui.messagePanel.getComponentZOrder(ui.answerPane);
            int activityIdx = ui.messagePanel.getComponentZOrder(ui.activityPanel);
            if (answerIdx >= 0 && activityIdx >= 0 && answerIdx < activityIdx) {
                ui.messagePanel.remove(ui.answerPane);
                ui.messagePanel.add(Box.createVerticalStrut(4));
                ui.messagePanel.add(ui.answerPane);
            }
        }
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
            LineDiffStat stat = computeLineDiffStat(hydratePendingChangeContent(change));
            totalAdded += stat.added;
            totalRemoved += stat.removed;
        }

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(JBUI.Borders.empty(6, 4, 4, 4));
        panel.setOpaque(false);

        // ── Header ──────────────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        JLabel title = new JLabel(changes.size() + " files changed  +" + totalAdded + "  -" + totalRemoved);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setForeground(UIUtil.getLabelForeground());
        header.add(title, BorderLayout.WEST);
        panel.add(header);
        panel.add(Box.createVerticalStrut(6));

        JSeparator topSep = new JSeparator();
        topSep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        panel.add(topSep);
        panel.add(Box.createVerticalStrut(4));

        // ── File rows ────────────────────────────────────────────────────────
        // Each row: [filename + diff stats (left)] | [Undo button (right)]
        // Clicking the left area opens the diff viewer.
        List<JButton> undoButtons = new ArrayList<>();
        for (PendingChangesManager.PendingChange change : changes) {
            PendingChangesManager.PendingChange displayChange = hydratePendingChangeContent(change);
            String changeKey = sessionChangeKey(change);
            LineDiffStat stat = computeLineDiffStat(displayChange);

            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            row.setBorder(JBUI.Borders.empty(3, 4, 3, 4));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(32)));

            // Left: filename + diff stats — clicking opens diff viewer
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            left.setOpaque(false);
            left.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JLabel nameLabel = new JLabel(shortFileName(change.path));
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
            left.add(nameLabel);
            left.add(createDiffStatPanel(stat));

            left.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    diffService.showDiffExplicit(
                            displayChange.path,
                            displayChange.oldContent,
                            displayChange.newContent
                    );
                }
            });

            // Right: Undo button
            JButton undoBtn = new JButton("Undo");
            undoBtn.setFocusable(false);
            if (ui.undoneSessionChangeKeys.contains(changeKey)) {
                undoBtn.setEnabled(false);
            }
            undoBtn.addActionListener(e -> {
                if (undoSessionChange(displayChange)) {
                    ui.undoneSessionChangeKeys.add(changeKey);
                    undoBtn.setEnabled(false);
                }
            });
            undoButtons.add(undoBtn);

            row.add(left, BorderLayout.CENTER);
            row.add(undoBtn, BorderLayout.EAST);
            panel.add(row);
        }

        // ── Undo All ─────────────────────────────────────────────────────────
        panel.add(Box.createVerticalStrut(4));
        JSeparator botSep = new JSeparator();
        botSep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        panel.add(botSep);
        panel.add(Box.createVerticalStrut(4));

        JPanel undoAllRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        undoAllRow.setOpaque(false);
        JButton undoAllBtn = new JButton("Undo All");
        undoAllBtn.setFocusable(false);
        if (changes.isEmpty()) {
            undoAllBtn.setEnabled(false);
        }
        undoAllBtn.addActionListener(e -> {
            List<PendingChangesManager.PendingChange> displayChanges =
                    new ArrayList<>(ui.sessionChanges.values()).stream()
                            .map(this::hydratePendingChangeContent)
                            .collect(java.util.stream.Collectors.toList());
            for (int i = 0; i < changes.size(); i++) {
                String changeKey = sessionChangeKey(changes.get(i));
                if (ui.undoneSessionChangeKeys.contains(changeKey)) {
                    continue;
                }
                if (undoSessionChange(displayChanges.get(i))) {
                    ui.undoneSessionChangeKeys.add(changeKey);
                }
            }
            for (JButton btn : undoButtons) {
                btn.setEnabled(false);
            }
            undoAllBtn.setEnabled(false);
        });
        undoAllRow.add(undoAllBtn);
        panel.add(undoAllRow);

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
        path.setForeground(UIUtil.getContextHelpForeground());
        path.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));

        textPanel.add(name);
        textPanel.add(path);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightPanel.setOpaque(false);

        PendingChangesManager.PendingChange displayChange = hydratePendingChangeContent(change);
        LineDiffStat stat = computeLineDiffStat(displayChange);

        JButton viewBtn = new JButton((actionLabel == null || actionLabel.isBlank()) ? "Show Diff" : actionLabel);
        viewBtn.setFocusable(false);
        viewBtn.addActionListener(e -> diffService.showDiffExplicit(
                displayChange.path,
                displayChange.oldContent,
                displayChange.newContent
        ));

        rightPanel.add(createDiffStatPanel(stat));
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

    private LineDiffStat computeLineDiffStat(PendingChangesManager.PendingChange change) {
        if (change == null) {
            return new LineDiffStat(0, 0);
        }
        String oldContent = firstNonBlank(change.oldContent);
        String newContent = firstNonBlank(change.newContent);
        if (oldContent.isBlank() && newContent.isBlank()) {
            PendingChangesManager.PendingChange hydrated = hydratePendingChangeContent(change);
            if (hydrated != null) {
                oldContent = firstNonBlank(hydrated.oldContent);
                newContent = firstNonBlank(hydrated.newContent);
            }
        }
        return computeLineDiffStat(oldContent, newContent);
    }

    private String[] splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return new String[0];
        }
        return content.split("\\R", -1);
    }
    private void typewriterEffect(JEditorPane pane, String fullMarkdown) {
        
        Timer timer = new Timer(10, null); 
        final int[] index = {0};
        final int length = fullMarkdown.length();
        final int chunk = 2; 
        
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
            setMarkdownContent(pane, partial);
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
            
            
            String fullPath = change.path;
            String fileName = fullPath;
            try {
                fileName = Path.of(fullPath).getFileName().toString();
            } catch (Exception e) {
                
            }

            JPanel textPanel = new JPanel(new GridLayout(2, 1));
            textPanel.setOpaque(false);
            
            JLabel nameLabel = new JLabel(fileName);
            
            
            JLabel pathLabel = new JLabel(fullPath);
            pathLabel.setForeground(UIUtil.getContextHelpForeground());
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
                Color base = UIUtil.getTextFieldBackground();
                Color borderColor = UIUtil.getBoundsColor();
                Color fill = getModel().isRollover()
                        ? ColorUtil.mix(base, UIUtil.getPanelBackground(), UIUtil.isUnderDarcula() ? 0.35 : 0.15)
                        : base;
                g2.setColor(fill);
                g2.fillOval(x, y, size, size);
                g2.setColor(borderColor);
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
        button.setForeground(UIUtil.getLabelForeground());
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMargin(JBUI.emptyInsets());
        return button;
    }
    
    private void scrollToBottom() {
        scrollController.scrollToBottom();
    }
    
    private void scrollToBottomSmart() {
        scrollController.scrollToBottomSmart();
    }

    private void installConversationScrollBehavior() {
        scrollController.installConversationScrollBehavior();
    }

    
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

    private enum CommandApprovalDecision {
        APPROVE_ONCE,
        APPROVE_WHITELIST,
        APPROVE_ALWAYS_NON_DESTRUCTIVE,
        REJECT
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
        boolean workspaceApplied;
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
        private final ShimmerLabel summaryLabel;
        private final JLabel metaLabel;
        private Runnable action;
        private boolean hover;
        private boolean running;

        InlineToolRowPanel(String marker) {
            setLayout(new BorderLayout(8, 0));
            setOpaque(false);
            setBorder(JBUI.Borders.empty(1, 4, 1, 4));
            setCursor(Cursor.getDefaultCursor());

            JPanel left = new JPanel(new BorderLayout(6, 0));
            left.setOpaque(false);

            markerLabel = new JLabel(marker == null ? "[tool]" : marker);
            markerLabel.setForeground(UIUtil.getContextHelpForeground());
            markerLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));

            summaryLabel = new ShimmerLabel();
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

                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
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
            repaint();
        }

        void setRunning(boolean running) {
            if (this.running == running) {
                return;
            }
            this.running = running;
            summaryLabel.setShimmerActive(running);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (hover) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color panelBg = UIUtil.getPanelBackground();
                Color selectionBg = resolveUiColor("List.selectionBackground", UIUtil.getLabelForeground());
                g2.setColor(ColorUtil.mix(panelBg, selectionBg, UIUtil.isUnderDarcula() ? 0.28 : 0.14));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    private static class SessionListRowPanel extends JPanel {
        private boolean hover;
        private boolean selected;

        SessionListRowPanel() {
            setOpaque(false);
        }

        void setSelectedRow(boolean selected) {
            this.selected = selected;
            repaint();
        }

        void setHoverRow(boolean hover) {
            if (this.hover == hover) {
                return;
            }
            this.hover = hover;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selected || hover) {
                Color selectedBg = UIManager.getColor("List.selectionBackground");
                if (selectedBg == null) {
                    selectedBg = ColorUtil.mix(
                            UIUtil.getPanelBackground(),
                            UIUtil.getLabelForeground(),
                            UIUtil.isUnderDarcula() ? 0.42 : 0.20
                    );
                }
                Color panelBg = UIUtil.getPanelBackground();
                Color hoverBg = ColorUtil.mix(panelBg, selectedBg, UIUtil.isUnderDarcula() ? 0.35 : 0.16);
                Color fill = selected ? selectedBg : hoverBg;
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static class HeaderBarPanel extends JPanel {
        HeaderBarPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            Color fill = getParent() != null ? getParent().getBackground() : UIUtil.getPanelBackground();
            g2.setColor(fill);
            g2.fillRect(0, 0, getWidth(), getHeight());
            int shadowHeight = Math.max(6, Math.min(12, getHeight() / 4));
            Color label = UIUtil.getLabelForeground();
            Color shadowStrong = withAlpha(label, UIUtil.isUnderDarcula() ? 56 : 24);
            Color shadowWeak = withAlpha(label, 0);
            GradientPaint shadow = new GradientPaint(
                    0f,
                    getHeight() - shadowHeight,
                    shadowStrong,
                    0f,
                    getHeight(),
                    shadowWeak
            );
            g2.setPaint(shadow);
            g2.fillRect(0, getHeight() - shadowHeight, getWidth(), shadowHeight);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static class RoundedPanel extends JPanel {
        private final int radius;
        private Color fill;
        private Color border;
        private Supplier<Color> fillSupplier;
        private Supplier<Color> borderSupplier;

        RoundedPanel(int radius, Color fill, Color border) {
            this.radius = Math.max(4, radius);
            this.fill = fill;
            this.border = border;
            setOpaque(false);
        }

        RoundedPanel(int radius, Supplier<Color> fillSupplier, Supplier<Color> borderSupplier) {
            this.radius = Math.max(4, radius);
            this.fillSupplier = fillSupplier;
            this.borderSupplier = borderSupplier;
            this.fill = resolveColor(fillSupplier);
            this.border = resolveColor(borderSupplier);
            setOpaque(false);
        }

        void setColorSuppliers(Supplier<Color> fillSupplier, Supplier<Color> borderSupplier) {
            this.fillSupplier = fillSupplier;
            this.borderSupplier = borderSupplier;
            this.fill = resolveColor(fillSupplier);
            this.border = resolveColor(borderSupplier);
            repaint();
        }

        private static Color resolveColor(Supplier<Color> supplier) {
            return supplier == null ? null : supplier.get();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color resolvedFill = fillSupplier == null ? fill : resolveColor(fillSupplier);
            Color resolvedBorder = borderSupplier == null ? border : resolveColor(borderSupplier);
            if (resolvedFill != null) {
                g2.setColor(resolvedFill);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            }
            if (resolvedBorder != null) {
                g2.setColor(resolvedBorder);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static class RoundedLineBorder extends javax.swing.border.AbstractBorder {
        private final Color color;
        private final int radius;

        RoundedLineBorder(Color color, int radius) {
            this.color = color == null ? UIUtil.getBoundsColor() : color;
            this.radius = Math.max(4, radius);
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(1, 1, 1, 1);
        }
    }

    /**
     * A JLabel that paints a left-to-right shimmer sweep <em>only on the text glyphs</em>.
     * The surrounding row/panel background is untouched.
     */
    private static final class ShimmerLabel extends JLabel {
        private boolean shimmerActive;
        private float phase;
        private Timer shimmerTimer;

        ShimmerLabel() {
            super();
        }

        void setShimmerActive(boolean active) {
            if (this.shimmerActive == active) return;
            this.shimmerActive = active;
            if (active) {
                phase = 0f;
                if (shimmerTimer == null || !shimmerTimer.isRunning()) {
                    shimmerTimer = new Timer(40, e -> {
                        phase += 0.04f;
                        if (phase > 1f) phase = 0f;
                        repaint();
                    });
                    shimmerTimer.start();
                }
            } else {
                if (shimmerTimer != null) {
                    shimmerTimer.stop();
                    shimmerTimer = null;
                }
                repaint();
            }
        }

        @Override
        public void removeNotify() {
            if (shimmerTimer != null) { shimmerTimer.stop(); shimmerTimer = null; }
            super.removeNotify();
        }

        @Override
        protected void paintComponent(Graphics g) {
            String text = getText();
            if (!shimmerActive || text == null || text.isEmpty() || text.startsWith("<html")) {
                super.paintComponent(g);
                return;
            }
            Font font = getFont();
            if (font == null) { super.paintComponent(g); return; }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            Insets ins = getInsets();
            int ix = ins != null ? ins.left : 0;
            int iy = ins != null ? ins.top  : 0;
            int availH = getHeight() - iy - (ins != null ? ins.bottom : 0);
            int tx = ix;
            int ty = iy + Math.max(0, (availH - fm.getAscent() - fm.getDescent()) / 2) + fm.getAscent();
            int textWidth = fm.stringWidth(text);
            if (textWidth <= 0) { g2.dispose(); super.paintComponent(g); return; }

            // Paint glyphs so gradient applies only to letter shapes
            Shape textShape = font.createGlyphVector(g2.getFontRenderContext(), text)
                                  .getOutline((float) tx, (float) ty);
            // Base color
            Color base = getForeground() != null ? getForeground() : UIUtil.getContextHelpForeground();
            g2.setColor(base);
            g2.fill(textShape);
            // Shimmer sweep
            float bandHalf = textWidth * 0.45f;
            float center   = (float) tx - bandHalf + phase * (textWidth + 2f * bandHalf);
            Color hi = withAlpha(UIUtil.getLabelForeground(), 155);
            LinearGradientPaint shimmer = new LinearGradientPaint(
                    center - bandHalf, 0f, center + bandHalf, 0f,
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{withAlpha(UIUtil.getLabelForeground(), 0), hi,
                                withAlpha(UIUtil.getLabelForeground(), 0)}
            );
            g2.setPaint(shimmer);
            g2.fill(textShape);
            g2.dispose();
        }
    }

    private static class ThinkingStatusPanel extends JPanel {
        private final ShimmerLabel textLabel;
        private boolean active;

        ThinkingStatusPanel() {
            setOpaque(false);
            setLayout(new FlowLayout(FlowLayout.LEFT, 12, 0));
            setBorder(JBUI.Borders.empty(0, 2, 6, 2));
            textLabel = new ShimmerLabel();
            textLabel.setText("正在思考");
            textLabel.setForeground(UIUtil.getContextHelpForeground());
            textLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL).deriveFont(Font.BOLD));
            add(textLabel);
            setPreferredSize(new Dimension(10, 22));
        }

        void setState(boolean active, String text) {
            if (text != null && !text.isBlank()) {
                textLabel.setText(text);
            }
            if (this.active == active) {
                return;
            }
            this.active = active;
            textLabel.setShimmerActive(active);
        }
    }

    private static class ActivityCommandPanel extends JPanel {
        private final JPanel headerPanel;
        private final JLabel chevronLabel;
        private final ShimmerLabel summaryLabel;
        private final JLabel subtitleLabel;
        private final JLabel metaLabel;
        private final JTextArea detailArea;
        private final JBScrollPane detailScroll;
        private final JPanel detailPanel;
        private final JPanel detailFooter;
        private final JLabel executionStatusLabel;
        private final JPanel decisionPanel;
        private final JButton approveButton;
        private final JButton whitelistButton;
        private final JButton alwaysAllowButton;
        private final JButton rejectButton;
        private Timer detailAnimation;
        private int detailAnimatedHeight;
        private String summary = "";
        private String expandedSummary = "";
        private String subtitle = "";
        private boolean expanded;
        private boolean headerHover;
        private boolean running;

        ActivityCommandPanel(String summary) {
            setLayout(new BorderLayout(0, 2));
            setOpaque(false);

            headerPanel = new JPanel(new BorderLayout(8, 0));
            headerPanel.setOpaque(false);
            headerPanel.setBorder(JBUI.Borders.empty(1, 4, 1, 4));
            headerPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JPanel left = new JPanel(new BorderLayout(6, 0));
            left.setOpaque(false);
            summaryLabel = new ShimmerLabel();
            summaryLabel.setForeground(UIUtil.getLabelForeground());
            subtitleLabel = new JLabel();
            subtitleLabel.setForeground(UIUtil.getContextHelpForeground());
            subtitleLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));

            JPanel textColumn = new JPanel();
            textColumn.setOpaque(false);
            textColumn.setLayout(new BoxLayout(textColumn, BoxLayout.Y_AXIS));
            textColumn.add(summaryLabel);
            textColumn.add(subtitleLabel);

            left.add(textColumn, BorderLayout.CENTER);

            metaLabel = new JLabel("");
            metaLabel.setForeground(UIUtil.getContextHelpForeground());
            metaLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));
            chevronLabel = new JLabel(">");
            chevronLabel.setForeground(UIUtil.getContextHelpForeground());
            chevronLabel.setVisible(false);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            right.setOpaque(false);
            right.add(metaLabel);
            right.add(chevronLabel);

            headerPanel.add(left, BorderLayout.CENTER);
            headerPanel.add(right, BorderLayout.EAST);

            MouseAdapter toggle = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    setExpanded(!expanded);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    setHeaderHover(true);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    setHeaderHover(false);
                }
            };
            headerPanel.addMouseListener(toggle);
            left.addMouseListener(toggle);
            textColumn.addMouseListener(toggle);
            summaryLabel.addMouseListener(toggle);
            subtitleLabel.addMouseListener(toggle);
            metaLabel.addMouseListener(toggle);
            chevronLabel.addMouseListener(toggle);
            right.addMouseListener(toggle);

            detailArea = new JTextArea();
            detailArea.setEditable(false);
            detailArea.setLineWrap(false);
            detailArea.setWrapStyleWord(false);
            detailArea.setOpaque(false);
            detailArea.setBackground(UIUtil.getTextFieldBackground());
            detailArea.setForeground(UIUtil.getLabelForeground());
            detailArea.setBorder(JBUI.Borders.empty(0));
            Font currentFont = detailArea.getFont();
            detailArea.setFont(new Font(Font.MONOSPACED, currentFont.getStyle(), currentFont.getSize()));

            detailScroll = new JBScrollPane(detailArea);
            detailScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            detailScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
            detailScroll.setBorder(BorderFactory.createEmptyBorder());
            detailScroll.getViewport().setOpaque(false);
            detailScroll.setOpaque(false);

            detailPanel = new JPanel(new BorderLayout());
            detailPanel.setOpaque(false);
            detailPanel.setBorder(JBUI.Borders.empty(0, 18, 0, 0));

            RoundedPanel detailCard = new RoundedPanel(
                    14,
                    UIUtil.getTextFieldBackground(),
                    UIUtil.getBoundsColor()
            );
            detailCard.setLayout(new BorderLayout(0, 4));
            detailCard.setBorder(JBUI.Borders.empty(8, 10, 6, 10));
            detailCard.add(detailScroll, BorderLayout.CENTER);

            detailFooter = new JPanel(new BorderLayout());
            detailFooter.setOpaque(false);
            detailFooter.setBorder(JBUI.Borders.empty(2, 0, 0, 0));
            executionStatusLabel = new JLabel("");
            executionStatusLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));
            executionStatusLabel.setForeground(UIUtil.getContextHelpForeground());
            detailFooter.add(executionStatusLabel, BorderLayout.EAST);
            detailFooter.setVisible(false);
            detailCard.add(detailFooter, BorderLayout.SOUTH);
            detailPanel.add(detailCard, BorderLayout.CENTER);
            detailPanel.setVisible(false);
            applyDetailHeight(0);

            decisionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            decisionPanel.setOpaque(false);
            decisionPanel.setBorder(JBUI.Borders.empty(3, 18, 0, 0));
            approveButton = new JButton("Approve");
            whitelistButton = new JButton("Whitelist");
            alwaysAllowButton = new JButton("Always allow");
            rejectButton = new JButton("Skip");
            approveButton.setFocusable(false);
            whitelistButton.setFocusable(false);
            alwaysAllowButton.setFocusable(false);
            rejectButton.setFocusable(false);
            decisionPanel.add(approveButton);
            decisionPanel.add(whitelistButton);
            decisionPanel.add(alwaysAllowButton);
            decisionPanel.add(rejectButton);
            whitelistButton.setVisible(false);
            alwaysAllowButton.setVisible(false);
            decisionPanel.setVisible(false);

            add(headerPanel, BorderLayout.NORTH);
            add(detailPanel, BorderLayout.CENTER);
            add(decisionPanel, BorderLayout.SOUTH);
            setSummary(summary);
            setSubtitle("");
            setExpanded(false);
        }

        private void setHeaderHover(boolean hover) {
            if (headerHover == hover) {
                return;
            }
            headerHover = hover;
            chevronLabel.setVisible(headerHover);
            repaint();
        }

        void setRunning(boolean running) {
            if (this.running == running) {
                return;
            }
            this.running = running;
            summaryLabel.setShimmerActive(running);
        }

        private void applyDetailHeight(int height) {
            int safe = Math.max(0, height);
            detailAnimatedHeight = safe;
            detailPanel.setPreferredSize(new Dimension(10, safe));
            detailPanel.setMinimumSize(new Dimension(10, safe));
            detailPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, safe));
            int scrollHeight = Math.max(72, safe - 28);
            detailScroll.setPreferredSize(new Dimension(10, scrollHeight));
            detailScroll.setMinimumSize(new Dimension(10, scrollHeight));
            detailScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, scrollHeight));
        }

        private void animateDetailHeight(boolean expand) {
            int target = expand ? 190 : 0;
            if (detailAnimation != null && detailAnimation.isRunning()) {
                detailAnimation.stop();
            }
            int start = detailAnimatedHeight;
            if (start == target) {
                detailPanel.setVisible(expand);
                return;
            }
            if (expand) {
                detailPanel.setVisible(true);
            }
            final int steps = 10;
            final int delayMs = 16;
            final int[] tick = {0};
            detailAnimation = new Timer(delayMs, null);
            detailAnimation.addActionListener(e -> {
                tick[0]++;
                float t = Math.min(1f, tick[0] / (float) steps);
                float eased = (float) (1.0 - Math.pow(1.0 - t, 3.0));
                int current = start + Math.round((target - start) * eased);
                applyDetailHeight(current);
                revalidate();
                repaint();
                if (tick[0] >= steps) {
                    detailAnimation.stop();
                    applyDetailHeight(target);
                    if (!expand) {
                        detailPanel.setVisible(false);
                    }
                    revalidate();
                    repaint();
                }
            });
            detailAnimation.start();
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
            clearButtonListeners();
            approveButton.setText(approveText == null || approveText.isBlank() ? "Approve" : approveText);
            rejectButton.setText(rejectText == null || rejectText.isBlank() ? "Skip" : rejectText);
            if (onApprove != null) {
                approveButton.addActionListener(e -> onApprove.run());
            }
            if (onReject != null) {
                rejectButton.addActionListener(e -> onReject.run());
            }
            whitelistButton.setVisible(false);
            alwaysAllowButton.setVisible(false);
            approveButton.setEnabled(enabled);
            rejectButton.setEnabled(enabled);
            decisionPanel.setVisible(true);
            revalidate();
            repaint();
        }

        void setCommandDecisionActions(
                String approveText,
                String whitelistText,
                String alwaysText,
                String rejectText,
                boolean visible,
                Runnable onApprove,
                Runnable onWhitelist,
                Runnable onAlways,
                Runnable onReject,
                boolean enabled
        ) {
            if (!visible) {
                clearDecisionActions();
                return;
            }
            clearButtonListeners();
            approveButton.setText(approveText == null || approveText.isBlank() ? "Run once" : approveText);
            whitelistButton.setText(whitelistText == null || whitelistText.isBlank() ? "Whitelist" : whitelistText);
            alwaysAllowButton.setText(alwaysText == null || alwaysText.isBlank() ? "Always allow" : alwaysText);
            rejectButton.setText(rejectText == null || rejectText.isBlank() ? "Skip" : rejectText);
            if (onApprove != null) {
                approveButton.addActionListener(e -> onApprove.run());
            }
            if (onWhitelist != null) {
                whitelistButton.addActionListener(e -> onWhitelist.run());
            }
            if (onAlways != null) {
                alwaysAllowButton.addActionListener(e -> onAlways.run());
            }
            if (onReject != null) {
                rejectButton.addActionListener(e -> onReject.run());
            }
            whitelistButton.setVisible(true);
            alwaysAllowButton.setVisible(true);
            approveButton.setEnabled(enabled);
            whitelistButton.setEnabled(enabled);
            alwaysAllowButton.setEnabled(enabled);
            rejectButton.setEnabled(enabled);
            decisionPanel.setVisible(true);
            revalidate();
            repaint();
        }

        void setDecisionEnabled(boolean enabled) {
            approveButton.setEnabled(enabled);
            whitelistButton.setEnabled(enabled);
            alwaysAllowButton.setEnabled(enabled);
            rejectButton.setEnabled(enabled);
        }

        void clearDecisionActions() {
            clearButtonListeners();
            decisionPanel.setVisible(false);
            whitelistButton.setVisible(false);
            alwaysAllowButton.setVisible(false);
            approveButton.setEnabled(true);
            whitelistButton.setEnabled(true);
            alwaysAllowButton.setEnabled(true);
            rejectButton.setEnabled(true);
            revalidate();
            repaint();
        }

        private void clearButtonListeners() {
            for (java.awt.event.ActionListener listener : approveButton.getActionListeners()) {
                approveButton.removeActionListener(listener);
            }
            for (java.awt.event.ActionListener listener : whitelistButton.getActionListeners()) {
                whitelistButton.removeActionListener(listener);
            }
            for (java.awt.event.ActionListener listener : alwaysAllowButton.getActionListeners()) {
                alwaysAllowButton.removeActionListener(listener);
            }
            for (java.awt.event.ActionListener listener : rejectButton.getActionListeners()) {
                rejectButton.removeActionListener(listener);
            }
        }

        @Override
        public void removeNotify() {
            super.removeNotify();
        }

        void setExpanded(boolean expanded) {
            this.expanded = expanded;
            chevronLabel.setText(expanded ? "v" : ">");
            refreshHeaderLabels();
            animateDetailHeight(expanded);
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
        String ideContextContent = "";
        ObjectNode ideContextPayload = mapper.createObjectNode();
        if (includeIde) {
            if (SEND_VERBOSE_IDE_CONTEXT) {
                ideContextContent = buildIdeContextWithTimeout(1500);
            }
            ideContextPayload = buildIdeContextPayloadWithTimeout(1500);
        }
        
        ObjectNode json = mapper.createObjectNode();
        json.put("goal", goal);
        json.put("workspaceRoot", workspaceRoot);
        json.put("workspaceName", workspaceName);
        json.put("ideContextContent", ideContextContent);
        json.put("ideContextPath", "");
        json.set("ideContext", ideContextPayload);
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
        
        List<String> lines = buildHistoryLinesForRequest();
        ArrayNode historyNode = json.putArray("history");
        if (!lines.isEmpty()) {
            int start = Math.max(0, lines.size() - HISTORY_SEND_MAX_LINES);
            for (int i = start; i < lines.size(); i++) {
                historyNode.add(lines.get(i));
            }
        }

        String payload = mapper.writeValueAsString(json);
        String endpointUrl = System.getProperty("codeagent.endpoint", DEFAULT_AGENT_ENDPOINT);
        
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

    private ObjectNode buildIdeContextPayloadWithTimeout(long timeoutMs) {
        Callable<ObjectNode> task = this::buildIdeContextPayload;
        Future<ObjectNode> future = IDE_CONTEXT_EXECUTOR.submit(task);
        try {
            return future.get(Math.max(200L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            ObjectNode fallback = mapper.createObjectNode();
            fallback.put("captureError", "IDE context payload timeout/failed");
            fallback.put("workspaceRoot", firstNonBlank(workspaceRoot));
            fallback.put("workspaceName", firstNonBlank(workspaceName));
            return fallback;
        }
    }

    private ObjectNode buildIdeContextPayload() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("projectName", firstNonBlank(project != null ? project.getName() : ""));
        payload.put("workspaceRoot", firstNonBlank(workspaceRoot));
        payload.put("workspaceName", firstNonBlank(workspaceName));
        payload.put("indexReady", project != null && !DumbService.isDumb(project));
        payload.put("ideBridgeUrl", firstNonBlank(resolveIdeBridgeUrl()));

        if (project == null) {
            payload.put("projectUnavailable", true);
            return payload;
        }

        return ReadAction.compute(() -> {
            ObjectNode node = mapper.createObjectNode();
            node.put("projectName", firstNonBlank(project.getName()));
            node.put("workspaceRoot", firstNonBlank(workspaceRoot));
            node.put("workspaceName", firstNonBlank(workspaceName));
            node.put("indexReady", !DumbService.isDumb(project));
            node.put("ideBridgeUrl", firstNonBlank(resolveIdeBridgeUrl()));

            Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
            String projectSdkResolvedHome = resolveSdkHome(projectSdk);
            if (projectSdk != null) {
                String sdkName = firstNonBlank(projectSdk.getName());
                String sdkVersion = firstNonBlank(projectSdk.getVersionString());
                String sdkHome = firstNonBlank(projectSdk.getHomePath());
                node.put("projectSdkName", sdkName);
                node.put("projectSdkVersion", sdkVersion);
                node.put("projectSdkHome", sdkHome);
                if (!projectSdkResolvedHome.isBlank()) {
                    node.put("projectSdkResolvedHome", projectSdkResolvedHome);
                }
                Integer sdkMajor = extractMajorVersion(sdkVersion);
                if (sdkMajor != null) {
                    node.put("projectSdkMajor", sdkMajor);
                }
            }
            node.put("autoAlignProjectSdkEnabled", AUTO_ALIGN_PROJECT_SDK);
            node.put("autoAlignGradleJvmEnabled", AUTO_ALIGN_GRADLE_JVM);
            node.put("autoAlignMavenRunnerJreEnabled", AUTO_ALIGN_MAVEN_RUNNER_JRE);
            node.put("autoAlignRunConfigJreEnabled", AUTO_ALIGN_RUN_CONFIGURATION_JRE);
            String gradleJvm = firstNonBlank(readGradleJvmSettingReflective());
            String mavenRunnerJre = firstNonBlank(readMavenRunnerJreReflective());
            String runConfigJres = firstNonBlank(readRunConfigurationJresReflective());
            node.put("gradleJvm", gradleJvm);
            node.put("mavenRunnerJre", mavenRunnerJre);
            node.put("runConfigJres", runConfigJres);
            String gradleJvmResolvedHome = resolveConfiguredJreHome(gradleJvm, projectSdk);
            if (!gradleJvmResolvedHome.isBlank()) {
                node.put("gradleJvmResolvedHome", gradleJvmResolvedHome);
            }
            String mavenRunnerResolvedHome = resolveConfiguredJreHome(mavenRunnerJre, projectSdk);
            if (!mavenRunnerResolvedHome.isBlank()) {
                node.put("mavenRunnerResolvedHome", mavenRunnerResolvedHome);
            }
            ArrayNode runConfigResolvedHomes = node.putArray("runConfigResolvedHomes");
            Set<String> runConfigResolvedSet = new LinkedHashSet<>();
            for (String token : splitCsvValues(runConfigJres)) {
                String home = resolveConfiguredJreHome(token, projectSdk);
                if (!home.isBlank()) {
                    runConfigResolvedSet.add(home);
                }
            }
            String firstRunConfigResolvedHome = "";
            for (String home : runConfigResolvedSet) {
                if (firstRunConfigResolvedHome.isBlank()) {
                    firstRunConfigResolvedHome = home;
                }
                runConfigResolvedHomes.add(home);
            }
            if (!firstRunConfigResolvedHome.isBlank()) {
                node.put("runConfigResolvedHome", firstRunConfigResolvedHome);
            }
            node.put("runConfigCount", countRunConfigurationsReflective());

            LanguageLevel level = LanguageLevelProjectExtension.getInstance(project).getLanguageLevel();
            if (level != null) {
                node.put("languageLevel", level.name());
                Integer levelMajor = extractMajorVersion(level.name());
                if (levelMajor != null) {
                    node.put("languageLevelMajor", levelMajor);
                }
            }

            Module[] modules = ModuleManager.getInstance(project).getModules();
            node.put("moduleCount", modules.length);
            ArrayNode moduleNames = node.putArray("moduleNames");
            ArrayNode moduleSdkMajors = node.putArray("moduleSdkMajors");
            int moduleSample = Math.min(modules.length, 12);
            for (int i = 0; i < moduleSample; i++) {
                moduleNames.add(firstNonBlank(modules[i].getName()));
                try {
                    Sdk moduleSdk = ModuleRootManager.getInstance(modules[i]).getSdk();
                    Integer major = extractMajorVersion(moduleSdk != null ? moduleSdk.getVersionString() : "");
                    if (major != null) {
                        moduleSdkMajors.add(major);
                    }
                } catch (Exception ignored) {
                    
                }
            }

            node.put("hasGradlewBat", existsWorkspaceFile("gradlew.bat"));
            node.put("hasGradlew", existsWorkspaceFile("gradlew"));
            node.put("hasBuildGradle", existsWorkspaceFile("build.gradle"));
            node.put("hasBuildGradleKts", existsWorkspaceFile("build.gradle.kts"));
            node.put("hasSettingsGradle", existsWorkspaceFile("settings.gradle"));
            node.put("hasSettingsGradleKts", existsWorkspaceFile("settings.gradle.kts"));
            node.put("hasPomXml", existsWorkspaceFile("pom.xml"));
            node.put("hasMvnwCmd", existsWorkspaceFile("mvnw.cmd"));
            node.put("hasMvnw", existsWorkspaceFile("mvnw"));

            return node;
        });
    }

    private String resolveIdeBridgeUrl() {
        if (ideBridgeServer == null) {
            return "";
        }
        try {
            ideBridgeServer.ensureStarted();
            return firstNonBlank(ideBridgeServer.getBaseUrl());
        } catch (Exception e) {
            logger.warn("resolve_ide_bridge_url_failed", e);
            return "";
        }
    }

    private String autoAlignProjectSdkIfNeeded() {
        if (!AUTO_ALIGN_PROJECT_SDK || project == null || project.isDisposed()) {
            return "";
        }
        boolean looksLikeJavaProject = existsWorkspaceFile("pom.xml")
                || existsWorkspaceFile("build.gradle")
                || existsWorkspaceFile("build.gradle.kts")
                || existsWorkspaceFile("settings.gradle")
                || existsWorkspaceFile("settings.gradle.kts")
                || existsWorkspaceFile("src/main/java")
                || existsWorkspaceFile("src/test/java");
        if (!looksLikeJavaProject) {
            return "";
        }
        try {
            Sdk currentSdk = ProjectRootManager.getInstance(project).getProjectSdk();
            String currentVersion = currentSdk != null ? firstNonBlank(currentSdk.getVersionString()) : "";
            Integer currentMajor = extractMajorVersion(currentVersion);
            LanguageLevel languageLevel = LanguageLevelProjectExtension.getInstance(project).getLanguageLevel();
            Integer requiredMajor = languageLevel == null ? null : extractMajorVersion(languageLevel.name());
            if (requiredMajor == null || requiredMajor <= 0) {
                return "";
            }
            if (currentMajor != null && currentMajor.equals(requiredMajor)) {
                List<String> alignmentNotes = autoAlignBuildToolJvmSettings(currentSdk);
                return String.join(" ", alignmentNotes).trim();
            }

            Sdk[] allJdks = ProjectJdkTable.getInstance().getAllJdks();
            Sdk candidate = null;
            for (Sdk sdk : allJdks) {
                if (sdk == null) {
                    continue;
                }
                Integer major = extractMajorVersion(firstNonBlank(sdk.getVersionString(), sdk.getName()));
                if (major != null && major.equals(requiredMajor)) {
                    candidate = sdk;
                    break;
                }
            }
            if (candidate == null) {
                return "";
            }
            if (currentSdk != null && firstNonBlank(currentSdk.getName()).equals(firstNonBlank(candidate.getName()))) {
                return "";
            }

            final Sdk targetSdk = candidate;
            WriteAction.runAndWait(() -> ProjectRootManager.getInstance(project).setProjectSdk(targetSdk));
            List<String> notes = new ArrayList<>();
            notes.add("Auto-aligned Project SDK to " + firstNonBlank(targetSdk.getName(), targetSdk.getVersionString(), "Java " + requiredMajor)
                    + " (language level: " + languageLevel.name() + ").");
            notes.addAll(autoAlignBuildToolJvmSettings(targetSdk));
            return String.join(" ", notes).trim();
        } catch (Exception e) {
            logger.warn("auto_align_project_sdk_failed", e);
            return "";
        }
    }

    private List<String> autoAlignBuildToolJvmSettings(Sdk sdk) {
        List<String> notes = new ArrayList<>();
        if (sdk == null || project == null || project.isDisposed()) {
            return notes;
        }
        String sdkName = firstNonBlank(sdk.getName(), sdk.getVersionString());
        if (sdkName.isBlank()) {
            return notes;
        }

        if (AUTO_ALIGN_GRADLE_JVM && alignGradleJvmSettingReflective(sdkName)) {
            notes.add("Aligned Gradle JVM to " + sdkName + ".");
        }
        if (AUTO_ALIGN_MAVEN_RUNNER_JRE && alignMavenRunnerJreSettingReflective(sdkName)) {
            notes.add("Aligned Maven Runner JRE to " + sdkName + ".");
        }
        if (AUTO_ALIGN_RUN_CONFIGURATION_JRE) {
            int changed = alignRunConfigurationJreReflective(sdkName);
            if (changed > 0) {
                notes.add("Aligned " + changed + " Run Configuration JRE setting(s) to " + sdkName + ".");
            }
        }
        return notes;
    }

    private boolean alignGradleJvmSettingReflective(String sdkName) {
        if (sdkName == null || sdkName.isBlank() || project == null || project.isDisposed()) {
            return false;
        }
        try {
            Class<?> gradleSettingsClass = Class.forName("org.jetbrains.plugins.gradle.settings.GradleSettings");
            Method getInstance = gradleSettingsClass.getMethod("getInstance", Project.class);
            Object gradleSettings = getInstance.invoke(null, project);
            if (gradleSettings == null) {
                return false;
            }

            Method getLinkedProjectsSettings = gradleSettingsClass.getMethod("getLinkedProjectsSettings");
            Object linked = getLinkedProjectsSettings.invoke(gradleSettings);
            if (!(linked instanceof Iterable<?> iterable)) {
                return false;
            }

            boolean changed = false;
            for (Object projectSetting : iterable) {
                if (projectSetting == null) {
                    continue;
                }
                Method getGradleJvm = projectSetting.getClass().getMethod("getGradleJvm");
                Method setGradleJvm = projectSetting.getClass().getMethod("setGradleJvm", String.class);
                String current = safeReflectString(getGradleJvm.invoke(projectSetting));
                if (!sdkName.equals(current)) {
                    WriteAction.runAndWait(() -> {
                        try {
                            setGradleJvm.invoke(projectSetting, sdkName);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                    changed = true;
                }
            }
            return changed;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            logger.warn("align_gradle_jvm_failed", e);
            return false;
        }
    }

    private boolean alignMavenRunnerJreSettingReflective(String sdkName) {
        if (sdkName == null || sdkName.isBlank() || project == null || project.isDisposed()) {
            return false;
        }
        try {
            Class<?> runnerClass = Class.forName("org.jetbrains.idea.maven.execution.MavenRunner");
            Method getInstance = runnerClass.getMethod("getInstance", Project.class);
            Object runner = getInstance.invoke(null, project);
            if (runner == null) {
                return false;
            }
            Method getSettings = runnerClass.getMethod("getSettings");
            Object settings = getSettings.invoke(runner);
            if (settings == null) {
                return false;
            }
            Method getJreName = settings.getClass().getMethod("getJreName");
            Method setJreName = settings.getClass().getMethod("setJreName", String.class);
            String current = safeReflectString(getJreName.invoke(settings));
            if (sdkName.equals(current)) {
                return false;
            }
            WriteAction.runAndWait(() -> {
                try {
                    setJreName.invoke(settings, sdkName);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            logger.warn("align_maven_runner_jre_failed", e);
            return false;
        }
    }

    private String readGradleJvmSettingReflective() {
        if (project == null || project.isDisposed()) {
            return "";
        }
        try {
            Class<?> gradleSettingsClass = Class.forName("org.jetbrains.plugins.gradle.settings.GradleSettings");
            Method getInstance = gradleSettingsClass.getMethod("getInstance", Project.class);
            Object gradleSettings = getInstance.invoke(null, project);
            if (gradleSettings == null) {
                return "";
            }
            Method getLinkedProjectsSettings = gradleSettingsClass.getMethod("getLinkedProjectsSettings");
            Object linked = getLinkedProjectsSettings.invoke(gradleSettings);
            if (!(linked instanceof Iterable<?> iterable)) {
                return "";
            }
            List<String> values = new ArrayList<>();
            for (Object projectSetting : iterable) {
                if (projectSetting == null) {
                    continue;
                }
                Method getGradleJvm = projectSetting.getClass().getMethod("getGradleJvm");
                String value = safeReflectString(getGradleJvm.invoke(projectSetting));
                if (!value.isBlank() && !values.contains(value)) {
                    values.add(value);
                }
            }
            if (values.isEmpty()) {
                return "";
            }
            return values.size() == 1 ? values.get(0) : String.join(",", values);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String readMavenRunnerJreReflective() {
        if (project == null || project.isDisposed()) {
            return "";
        }
        try {
            Class<?> runnerClass = Class.forName("org.jetbrains.idea.maven.execution.MavenRunner");
            Method getInstance = runnerClass.getMethod("getInstance", Project.class);
            Object runner = getInstance.invoke(null, project);
            if (runner == null) {
                return "";
            }
            Method getSettings = runnerClass.getMethod("getSettings");
            Object settings = getSettings.invoke(runner);
            if (settings == null) {
                return "";
            }
            Method getJreName = settings.getClass().getMethod("getJreName");
            return safeReflectString(getJreName.invoke(settings));
        } catch (Exception ignored) {
            return "";
        }
    }

    private int alignRunConfigurationJreReflective(String sdkName) {
        if (sdkName == null || sdkName.isBlank() || project == null || project.isDisposed()) {
            return 0;
        }
        try {
            Class<?> runManagerClass = Class.forName("com.intellij.execution.RunManager");
            Method getInstance = runManagerClass.getMethod("getInstance", Project.class);
            Object runManager = getInstance.invoke(null, project);
            if (runManager == null) {
                return 0;
            }
            Method getAllSettings = runManagerClass.getMethod("getAllSettings");
            Object allSettings = getAllSettings.invoke(runManager);
            if (!(allSettings instanceof Iterable<?> iterable)) {
                return 0;
            }
            int changed = 0;
            for (Object setting : iterable) {
                if (setting == null) {
                    continue;
                }
                Method getConfiguration = findNoArgMethod(setting.getClass(), "getConfiguration");
                if (getConfiguration == null) {
                    continue;
                }
                Object config = getConfiguration.invoke(setting);
                if (config == null) {
                    continue;
                }
                Method isAltEnabled = findNoArgMethod(config.getClass(), "isAlternativeJrePathEnabled");
                Method getAltPath = findNoArgMethod(config.getClass(), "getAlternativeJrePath");
                Method setAltPath = findMethod(config.getClass(), "setAlternativeJrePath", String.class);
                if (isAltEnabled == null || getAltPath == null || setAltPath == null) {
                    continue;
                }
                boolean enabled = safeReflectBoolean(isAltEnabled.invoke(config));
                if (!enabled) {
                    continue;
                }
                String current = safeReflectString(getAltPath.invoke(config));
                if (sdkName.equals(current)) {
                    continue;
                }
                final Object targetConfig = config;
                WriteAction.runAndWait(() -> {
                    try {
                        setAltPath.invoke(targetConfig, sdkName);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                changed++;
            }
            return changed;
        } catch (ClassNotFoundException e) {
            return 0;
        } catch (Exception e) {
            logger.warn("align_run_configuration_jre_failed", e);
            return 0;
        }
    }

    private int countRunConfigurationsReflective() {
        if (project == null || project.isDisposed()) {
            return 0;
        }
        try {
            Class<?> runManagerClass = Class.forName("com.intellij.execution.RunManager");
            Method getInstance = runManagerClass.getMethod("getInstance", Project.class);
            Object runManager = getInstance.invoke(null, project);
            if (runManager == null) {
                return 0;
            }
            Method getAllSettings = runManagerClass.getMethod("getAllSettings");
            Object allSettings = getAllSettings.invoke(runManager);
            if (!(allSettings instanceof Iterable<?> iterable)) {
                return 0;
            }
            int count = 0;
            for (Object ignored : iterable) {
                count++;
            }
            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String readRunConfigurationJresReflective() {
        if (project == null || project.isDisposed()) {
            return "";
        }
        try {
            Class<?> runManagerClass = Class.forName("com.intellij.execution.RunManager");
            Method getInstance = runManagerClass.getMethod("getInstance", Project.class);
            Object runManager = getInstance.invoke(null, project);
            if (runManager == null) {
                return "";
            }
            Method getAllSettings = runManagerClass.getMethod("getAllSettings");
            Object allSettings = getAllSettings.invoke(runManager);
            if (!(allSettings instanceof Iterable<?> iterable)) {
                return "";
            }
            List<String> jres = new ArrayList<>();
            for (Object setting : iterable) {
                if (setting == null) {
                    continue;
                }
                Method getConfiguration = findNoArgMethod(setting.getClass(), "getConfiguration");
                if (getConfiguration == null) {
                    continue;
                }
                Object config = getConfiguration.invoke(setting);
                if (config == null) {
                    continue;
                }
                Method isAltEnabled = findNoArgMethod(config.getClass(), "isAlternativeJrePathEnabled");
                Method getAltPath = findNoArgMethod(config.getClass(), "getAlternativeJrePath");
                if (isAltEnabled == null || getAltPath == null) {
                    continue;
                }
                boolean enabled = safeReflectBoolean(isAltEnabled.invoke(config));
                if (!enabled) {
                    continue;
                }
                String jre = safeReflectString(getAltPath.invoke(config));
                if (!jre.isBlank() && !jres.contains(jre)) {
                    jres.add(jre);
                }
            }
            if (jres.isEmpty()) {
                return "";
            }
            return jres.size() == 1 ? jres.get(0) : String.join(",", jres);
        } catch (Exception ignored) {
            return "";
        }
    }

    private Method findNoArgMethod(Class<?> type, String methodName) {
        if (type == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            return type.getMethod(methodName);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Method findMethod(Class<?> type, String methodName, Class<?>... parameterTypes) {
        if (type == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            return type.getMethod(methodName, parameterTypes);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean safeReflectBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return false;
        }
        return Boolean.parseBoolean(text);
    }

    private String safeReflectString(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return "";
        }
        return text;
    }

    private String buildIdeContext() {
        if (project == null) {
            return "IDEA Project Unavailable";
        }
        if (DumbService.isDumb(project)) {
            return "IDEA Index Not Ready";
        }
        return ReadAction.compute(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("IDEContext:\n");
            sb.append("projectName: ").append(firstNonBlank(project.getName())).append('\n');
            sb.append("workspaceRoot: ").append(firstNonBlank(workspaceRoot)).append('\n');

            Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (projectSdk != null) {
                sb.append("projectSdk: ")
                        .append(firstNonBlank(projectSdk.getName()))
                        .append(" | ")
                        .append(firstNonBlank(projectSdk.getVersionString()))
                        .append('\n');
            } else {
                sb.append("projectSdk: (unset)\n");
            }

            Module[] modules = ModuleManager.getInstance(project).getModules();
            sb.append("moduleCount: ").append(modules.length).append('\n');
            int moduleSample = Math.min(modules.length, 8);
            for (int i = 0; i < moduleSample; i++) {
                sb.append("module[").append(i).append("]: ")
                        .append(firstNonBlank(modules[i].getName()))
                        .append('\n');
            }

            sb.append("buildHints:\n");
            sb.append("  gradlewBat: ").append(detectBuildFile("gradlew.bat")).append('\n');
            sb.append("  gradlew: ").append(detectBuildFile("gradlew")).append('\n');
            sb.append("  buildGradle: ").append(detectBuildFile("build.gradle")).append('\n');
            sb.append("  buildGradleKts: ").append(detectBuildFile("build.gradle.kts")).append('\n');
            sb.append("  pomXml: ").append(detectBuildFile("pom.xml")).append('\n');
            sb.append("  mvnwCmd: ").append(detectBuildFile("mvnw.cmd")).append('\n');
            sb.append("  mvnw: ").append(detectBuildFile("mvnw")).append('\n');
            sb.append("toolJvmHints:\n");
            sb.append("  gradleJvm: ").append(firstNonBlank(readGradleJvmSettingReflective(), "(unset)")).append('\n');
            sb.append("  mavenRunnerJre: ").append(firstNonBlank(readMavenRunnerJreReflective(), "(unset)")).append('\n');
            sb.append("  runConfigJres: ").append(firstNonBlank(readRunConfigurationJresReflective(), "(unset)")).append('\n');
            sb.append("  runConfigCount: ").append(countRunConfigurationsReflective()).append('\n');
            sb.append("  autoAlignProjectSdk: ").append(AUTO_ALIGN_PROJECT_SDK).append('\n');
            sb.append("  autoAlignGradleJvm: ").append(AUTO_ALIGN_GRADLE_JVM).append('\n');
            sb.append("  autoAlignMavenRunnerJre: ").append(AUTO_ALIGN_MAVEN_RUNNER_JRE).append('\n');
            sb.append("  autoAlignRunConfigJre: ").append(AUTO_ALIGN_RUN_CONFIGURATION_JRE).append('\n');

            sb.append("javaClassIndex:\n");
            ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
            List<VirtualFile> files = new ArrayList<>();
            fileIndex.iterateContent(file -> {
                if (files.size() < 60 && !file.isDirectory() && file.getFileType() instanceof JavaFileType) {
                    files.add(file);
                }
                return true;
            });

            PsiManager psiManager = PsiManager.getInstance(project);
            int classCount = 0;
            for (VirtualFile file : files) {
                PsiFile psiFile = psiManager.findFile(file);
                if (psiFile instanceof PsiJavaFile) {
                    PsiClass[] classes = ((PsiJavaFile) psiFile).getClasses();
                    for (PsiClass clz : classes) {
                        if (classCount >= 120) {
                            sb.append("... (class list truncated)\n");
                            return sb.toString();
                        }
                        sb.append("class ")
                                .append(firstNonBlank(clz.getName(), "(anonymous)"))
                                .append('\n');
                        classCount++;
                    }
                }
            }
            return sb.toString();
        });
    }

    private Integer extractMajorVersion(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("(\\d{1,2})").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            if (major == 1 && matcher.find()) {
                major = Integer.parseInt(matcher.group(1));
            }
            return major;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String detectBuildFile(String relativePath) {
        if (workspaceRoot == null || workspaceRoot.isBlank() || relativePath == null || relativePath.isBlank()) {
            return "false";
        }
        try {
            return Files.exists(Path.of(workspaceRoot, relativePath)) ? "true" : "false";
        } catch (Exception ignored) {
            return "false";
        }
    }

    private boolean existsWorkspaceFile(String relativePath) {
        if (workspaceRoot == null || workspaceRoot.isBlank() || relativePath == null || relativePath.isBlank()) {
            return false;
        }
        try {
            return Files.exists(Path.of(workspaceRoot, relativePath));
        } catch (Exception ignored) {
            return false;
        }
    }

    private String resolveConfiguredJreHome(String configuredValue, Sdk projectSdk) {
        String raw = firstNonBlank(configuredValue);
        if (raw.isBlank()) {
            return "";
        }
        String normalized = raw.trim();
        if (normalized.contains(",")) {
            normalized = normalized.split(",")[0].trim();
        }
        if (normalized.isBlank()) {
            return "";
        }
        if ("project sdk".equalsIgnoreCase(normalized)
                || "project jdk".equalsIgnoreCase(normalized)
                || "use project jdk".equalsIgnoreCase(normalized)
                || "#JAVA_INTERNAL".equalsIgnoreCase(normalized)) {
            return resolveSdkHome(projectSdk);
        }
        String direct = normalizeAbsolutePathIfExists(normalized);
        if (!direct.isBlank()) {
            return direct;
        }
        Sdk exact = ProjectJdkTable.getInstance().findJdk(normalized);
        String fromExact = resolveSdkHome(exact);
        if (!fromExact.isBlank()) {
            return fromExact;
        }
        for (Sdk sdk : ProjectJdkTable.getInstance().getAllJdks()) {
            if (sdk == null) {
                continue;
            }
            String name = firstNonBlank(sdk.getName());
            if (name.isBlank()) {
                continue;
            }
            if (name.equalsIgnoreCase(normalized)
                    || name.toLowerCase().contains(normalized.toLowerCase())
                    || normalized.toLowerCase().contains(name.toLowerCase())) {
                String home = resolveSdkHome(sdk);
                if (!home.isBlank()) {
                    return home;
                }
            }
        }
        return "";
    }

    private String resolveSdkHome(Sdk sdk) {
        if (sdk == null) {
            return "";
        }
        return normalizeAbsolutePathIfExists(firstNonBlank(sdk.getHomePath()));
    }

    private String normalizeAbsolutePathIfExists(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            Path p = Path.of(raw.trim()).toAbsolutePath().normalize();
            if (Files.exists(p)) {
                return p.toString();
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private List<String> splitCsvValues(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String token : raw.split(",")) {
            if (token == null) {
                continue;
            }
            String trimmed = token.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private String extractJsonStringField(String json, String field) {
        try {
            JsonNode node = mapper.readTree(json);
            if (node.has(field)) return node.get(field).asText();
        } catch (Exception e) {}
        return null;
    }

    private boolean isStreamCancellation(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof InterruptedException || cursor instanceof CancellationException) {
                return true;
            }
            String message = cursor.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("connection reset")
                        || lower.contains("stream closed")
                        || lower.contains("socket closed")
                        || lower.contains("cancelled")
                        || lower.contains("interrupted")
                        || lower.contains("java.io.ioexception: closed")
                        || lower.equals("closed")) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
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
            send.setEnabled(true);
            inputController.updateSendButtonMode(effectiveBusy);
            if (thinkingStatusPanel != null) {
                String text = awaitingUserApproval ? "等待确认" : "正在思考";
                thinkingStatusPanel.setVisible(effectiveBusy);
                thinkingStatusPanel.setState(effectiveBusy, text);
            }
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
        scrollController.enableFollow();
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
            rebuildSessionChangesFromHistory(ui, message);
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
            if (!firstNonBlank(activity.changePath).isBlank()) {
                PendingChangesManager.PendingChange persistedChange = new PendingChangesManager.PendingChange(
                        firstNonBlank(activity.changePath),
                        firstNonBlank(activity.changeType, "EDIT"),
                        firstNonBlank(activity.changeOldContent),
                        firstNonBlank(activity.changeNewContent),
                        null
                );
                state.pendingChange = normalizeChangeForDirectApply(persistedChange);
            }
            ensureToolRenderType(ui, state, state.renderType);
            state.uiSummary = firstNonBlank(activity.summary, buildToolSummary(state));
            state.uiExpandedSummary = firstNonBlank(activity.expandedSummary, resolveExpandedSummary(state));
            state.uiMeta = firstNonBlank(activity.meta, buildToolMeta(state));
            state.uiDetails = firstNonBlank(activity.details, buildToolDetails(state));
            boolean running = isToolStatusRunning(state.status);
            if (state.panel != null) {
                state.panel.setSummary(state.uiSummary);
                state.panel.setExpandedSummary(state.uiExpandedSummary);
                state.panel.setSubtitle(buildToolSubtitle(state));
                state.panel.setMeta(state.uiMeta, colorForToolStatus(state.status));
                state.panel.setDetails(state.uiDetails);
                String executionStatus = state.renderType == ToolRenderType.TERMINAL ? buildTerminalExecutionStatus(state) : "";
                state.panel.setExecutionStatus(executionStatus, colorForToolStatus(state.status));
                state.panel.setRunning(running);
                state.panel.clearDecisionActions();
            }
            if (state.inlineRow != null) {
                state.inlineRow.setSummaryText(state.uiSummary);
                state.inlineRow.setMetaText(state.uiMeta, colorForToolStatus(state.status));
                state.inlineRow.setRunning(running);
                if (state.renderType == ToolRenderType.READ && state.targetNavigable) {
                    state.inlineRow.setAction(true, () -> navigateToReadTarget(state));
                } else {
                    state.inlineRow.setAction(false, null);
                }
            }
        }
    }
    
    private void rebuildSessionChangesFromHistory(AgentMessageUI ui, ChatHistoryService.UiMessage message) {
        if (ui == null || message == null) {
            return;
        }
        List<ChatHistoryService.PersistedChange> persisted = message.sessionChanges;
        if (persisted == null || persisted.isEmpty()) {
            return;
        }
        for (ChatHistoryService.PersistedChange pc : persisted) {
            if (pc == null || pc.path == null || pc.path.isBlank()) {
                continue;
            }
            PendingChangesManager.PendingChange change = new PendingChangesManager.PendingChange(
                    pc.path,
                    pc.type != null ? pc.type : "EDIT",
                    pc.oldContent != null ? pc.oldContent : "",
                    pc.newContent != null ? pc.newContent : "",
                    null
            );
            String key = sessionChangeKey(change);
            ui.sessionChanges.put(key, change);
        }
        if (message.undoneSessionChangeKeys != null && !message.undoneSessionChangeKeys.isEmpty()) {
            ui.undoneSessionChangeKeys.addAll(message.undoneSessionChangeKeys);
        }
        if (message.showSessionChangeSummary) {
            appendSessionChangeSummaryIfNeeded(ui);
        }
    }

    private static long longOrDefault(String val, long def) {
        try { return Long.parseLong(val); } catch (Exception e) { return def; }
    }

    private static final class ConversationListPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return JBUI.scale(18);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            if (orientation == SwingConstants.VERTICAL) {
                return Math.max(JBUI.scale(32), visibleRect.height - JBUI.scale(32));
            }
            return Math.max(JBUI.scale(32), visibleRect.width - JBUI.scale(32));
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
    
    
    
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
