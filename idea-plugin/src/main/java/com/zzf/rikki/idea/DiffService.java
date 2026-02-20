package com.zzf.rikki.idea;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.editor.Document;
import com.intellij.ui.EditorNotificationPanel;
import com.zzf.rikki.core.tool.PendingChangesManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

public class DiffService {
    private static final Logger LOG = Logger.getInstance(DiffService.class);
    private final Project project;
    private final Map<FileEditor, EditorNotificationPanel> notificationPanels = Collections.synchronizedMap(new WeakHashMap<>());
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String pendingEndpoint;

    public DiffService(Project project, HttpClient http, ObjectMapper mapper) {
        this.project = project;
        this.http = http != null ? http : HttpClient.newHttpClient();
        this.mapper = mapper != null ? mapper : new ObjectMapper();
        this.pendingEndpoint = resolvePendingEndpoint();
    }

    public void applyChange(PendingChangesManager.PendingChange change) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                applyPendingChange(change, false);
            } catch (Exception e) {
                LOG.error(e);
            }
        });
    }

    public void confirmChange(PendingChangesManager.PendingChange change) {
        confirmChange(change, null, null);
    }

    public void confirmChange(PendingChangesManager.PendingChange change, Runnable onSuccess, Runnable onFailure) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                if (isPendingWorkflowEnabled()) {
                    resolvePendingChange(change, false, onSuccess, onFailure);
                } else {
                    applyPendingChange(change, true);
                    if (onSuccess != null) onSuccess.run();
                }
            } catch (Exception e) {
                LOG.error(e);
                if (onFailure != null) onFailure.run();
            }
        });
    }

    public void revertChange(PendingChangesManager.PendingChange change) {
        revertChange(change, null, null);
    }

    public void revertChange(PendingChangesManager.PendingChange change, Runnable onSuccess, Runnable onFailure) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                if (isPendingWorkflowEnabled()) {
                    resolvePendingChange(change, true, onSuccess, onFailure);
                } else {
                    VirtualFile file = findVirtualFile(change.path, change.workspaceRoot);
                    if (file != null) removeNotification(file);
                    if (onSuccess != null) onSuccess.run();
                }
            } catch (Exception e) {
                LOG.error(e);
                if (onFailure != null) onFailure.run();
            }
        });
    }

    public void openFile(PendingChangesManager.PendingChange change) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                VirtualFile file = findVirtualFile(change.path, change.workspaceRoot);
                if (file != null) {
                    FileEditorManager.getInstance(project).openFile(file, true);
                }
            } catch (Exception e) {
                LOG.error(e);
            }
        });
    }

    private VirtualFile findVirtualFile(String pathStr, String workspaceRoot) {
        Path absPath = resolveAbsolutePath(pathStr, workspaceRoot);
        if (absPath == null) {
            return null;
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath.toString());
    }

    private Path resolveAbsolutePath(String pathStr, String workspaceRoot) {
        if (pathStr == null || pathStr.isBlank()) {
            return null;
        }
        Path p = parsePath(pathStr);
        if (p.isAbsolute()) {
            return p.normalize();
        }

        Path projectBase = null;
        String basePath = project.getBasePath();
        if (basePath != null && !basePath.isBlank()) {
            projectBase = parsePath(basePath).toAbsolutePath().normalize();
        }

        Path workspaceBase = null;
        if (workspaceRoot != null && !workspaceRoot.isBlank()) {
            workspaceBase = parsePath(workspaceRoot).toAbsolutePath().normalize();
        }

        Path base = workspaceBase;
        if (base == null || (projectBase != null && !sameRoot(base, projectBase))) {
            base = projectBase;
        }
        if (base == null) {
            return p.toAbsolutePath();
        }
        return base.resolve(p).normalize();
    }

    private void removeNotification(VirtualFile file) {
        if (project.isDisposed()) return;
        FileEditorManager manager = FileEditorManager.getInstance(project);
        for (FileEditor editor : manager.getEditors(file)) {
            EditorNotificationPanel panel = notificationPanels.remove(editor);
            if (panel != null) {
                manager.removeTopComponent(editor, panel);
            }
        }
    }

    public void applyWithNotification(PendingChangesManager.PendingChange change, Runnable onAccept, Runnable onReject) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                
                String pathStr = change.path;
                VirtualFile virtualFile = findVirtualFile(pathStr, change.workspaceRoot);

                
                if (virtualFile != null) {
                    FileEditorManager manager = FileEditorManager.getInstance(project);
                    manager.openFile(virtualFile, true); 
                    
                    final VirtualFile finalFile = virtualFile;
                    for (FileEditor editor : manager.getEditors(virtualFile)) {
                        
                        EditorNotificationPanel existing = notificationPanels.remove(editor);
                        if (existing != null) {
                            manager.removeTopComponent(editor, existing);
                        }

                        EditorNotificationPanel panel = new EditorNotificationPanel();
                        panel.setText("Agent generated changes for " + finalFile.getName());
                        
                        
                        notificationPanels.put(editor, panel);
                        
                        
                        panel.createActionLabel("Accept", () -> {
                            if (project.isDisposed()) return;
                            confirmChange(change, onAccept, null);
                        });
                        
                        
                        panel.createActionLabel("Reject", () -> {
                            if (project.isDisposed()) return;
                            revertChange(change, onReject, null);
                        });
                        
                        
                        panel.createActionLabel("Show Diff", () -> {
                            if (project.isDisposed()) return;
                            
                            showDiffExplicit(change.path, change.oldContent, change.newContent);
                        });
                        
                        manager.addTopComponent(editor, panel);
                    }
                }
            } catch (Exception e) {
                LOG.error(e);
                if (!project.isDisposed()) {
                    Messages.showErrorDialog(project, "Failed to apply changes: " + e.getMessage(), "Error");
                }
            }
        });
    }

    private void resolvePendingChange(PendingChangesManager.PendingChange change, boolean reject, Runnable onSuccess, Runnable onFailure) {
        if (change == null) {
            if (onFailure != null) onFailure.run();
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                String wsRoot = change.workspaceRoot == null ? "" : change.workspaceRoot;
                if (wsRoot.isBlank()) {
                    wsRoot = project.getBasePath() == null ? "" : project.getBasePath();
                }
                Map<String, Object> payload = Map.of(
                        "traceId", change.sessionId == null ? "" : change.sessionId,
                        "workspaceRoot", wsRoot,
                        "path", change.path == null ? "" : change.path,
                        "reject", reject
                );
                String body = mapper.writeValueAsString(payload);
                HttpRequest req = HttpRequest.newBuilder(URI.create(pendingEndpoint))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
                if (ok) {
                    try {
                        JsonNode root = mapper.readTree(resp.body());
                        String error = root.path("error").asText("");
                        String status = root.path("status").asText("");
                        if (!error.isEmpty() || ("error".equalsIgnoreCase(status))) {
                            ok = false;
                        }
                    } catch (Exception ignored) {
                    }
                }
                boolean finalOk = ok;
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!finalOk) {
                        if (!project.isDisposed()) {
                            Messages.showErrorDialog(project, "Failed to apply pending change via backend.", "Pending Change");
                        }
                        if (onFailure != null) onFailure.run();
                        return;
                    }
                    VirtualFile file = findVirtualFile(change.path, change.workspaceRoot);
                    if (file != null) {
                        file.refresh(false, false);
                        removeNotification(file);
                    }
                    if (onSuccess != null) onSuccess.run();
                });
            } catch (Exception e) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) {
                        Messages.showErrorDialog(project, "Failed to contact backend: " + e.getMessage(), "Pending Change");
                    }
                    if (onFailure != null) onFailure.run();
                });
            }
        });
    }

    private void applyPendingChange(PendingChangesManager.PendingChange change, boolean openAfter) {
        if (project.isDisposed() || change == null) return;
        String pathStr = change.path;
        VirtualFile virtualFile = findVirtualFile(pathStr, change.workspaceRoot);

        if ("DELETE".equalsIgnoreCase(change.type)) {
            if (virtualFile != null) {
                final VirtualFile toDelete = virtualFile;
                ApplicationManager.getApplication().runWriteAction(() -> {
                    try {
                        toDelete.delete(this);
                    } catch (IOException e) {
                        LOG.error(e);
                    }
                });
            } else {
                Path absPath = resolveAbsolutePath(pathStr, change.workspaceRoot);
                if (absPath != null) {
                    try {
                        Files.deleteIfExists(absPath);
                    } catch (IOException e) {
                        LOG.error(e);
                    }
                }
            }
        } else if ("CREATE".equalsIgnoreCase(change.type) && virtualFile == null) {
            Path absPath = resolveAbsolutePath(pathStr, change.workspaceRoot);
            if (absPath != null) {
                createFile(absPath.toString(), change.newContent);
                virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath.toString());
            }
        }

        if (virtualFile != null && change.newContent != null && !"DELETE".equalsIgnoreCase(change.type)) {
            applyChanges(virtualFile, change.newContent);
        }

        if (virtualFile != null) {
            removeNotification(virtualFile);
            if (openAfter) {
                FileEditorManager.getInstance(project).openFile(virtualFile, true);
            }
        }
    }

    public void showDiffExplicit(String path, String oldContent, String newContent) {
        ApplicationManager.getApplication().invokeLater(() -> {
             if (project.isDisposed()) return;
             try {
                com.intellij.diff.contents.DiffContent c1 = DiffContentFactory.getInstance().create(oldContent != null ? oldContent : "");
                com.intellij.diff.contents.DiffContent c2 = DiffContentFactory.getInstance().create(newContent != null ? newContent : "");
                SimpleDiffRequest request = new SimpleDiffRequest("Review Changes: " + path, c1, c2, "Original", "Current (Agent)");
                new DiffDialog(project, request, null, null).show();
             } catch(Exception e) { LOG.error(e); }
        });
    }

    private void applyChanges(VirtualFile file, String content) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                Document doc = FileDocumentManager.getInstance().getDocument(file);
                if (doc != null) {
                    doc.setText(content);
                } else {
                    file.setBinaryContent(content.getBytes());
                }
                FileDocumentManager.getInstance().saveDocument(doc);
            } catch (IOException e) {
                LOG.error(e);
            }
        });
    }
    
    private void createFile(String path, String content) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                File file = new File(path);
                if (!file.getParentFile().exists()) {
                    file.getParentFile().mkdirs();
                }
                if (!file.exists()) {
                    file.createNewFile();
                }
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
                if (vf != null) {
                    vf.setBinaryContent(content.getBytes());
                }
            } catch (Exception e) {
                LOG.error(e);
            }
        });
    }

    private boolean isPendingWorkflowEnabled() {
        String sys = System.getProperty("rikki.pending.enabled");
        String env = System.getenv("CODEAGENT_PENDING_ENABLED");
        String val = (sys != null && !sys.isBlank()) ? sys : env;
        if (val == null || val.isBlank()) {
            return true;
        }
        return "true".equalsIgnoreCase(val);
    }

    private String resolvePendingEndpoint() {
        String override = System.getProperty("rikki.pending.endpoint");
        if (override != null && !override.isBlank()) {
            return override;
        }
        String base = System.getProperty("rikki.endpoint", "http://localhost:18080/api/agent/chat");
        if (base.endsWith("/chat/stream")) {
            base = base.substring(0, base.length() - "/stream".length());
        }
        if (base.endsWith("/chat")) {
            return base.substring(0, base.length() - "/chat".length()) + "/pending";
        }
        int idx = base.indexOf("/api/agent");
        if (idx >= 0) {
            return base.substring(0, idx) + "/api/agent/pending";
        }
        return base + "/pending";
    }

    private Path parsePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return Paths.get(".");
        }
        String normalized = raw.trim();
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        if (windows && normalized.matches("^/[a-zA-Z]/.*")) {
            char drive = Character.toUpperCase(normalized.charAt(1));
            normalized = drive + ":" + normalized.substring(2);
        }
        return Paths.get(normalized);
    }

    private boolean sameRoot(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        Path aRoot = a.getRoot();
        Path bRoot = b.getRoot();
        if (aRoot == null || bRoot == null) {
            return false;
        }
        return aRoot.toString().equalsIgnoreCase(bRoot.toString());
    }

    private static class DiffDialog extends DialogWrapper {
        private final DiffRequestPanel panel;

        public DiffDialog(Project project, SimpleDiffRequest request, Runnable onAccept, Runnable onDiscard) {
            super(project, true);
            setTitle("Review Changes");
            panel = DiffManager.getInstance().createRequestPanel(project, this.getDisposable(), null);
            panel.setRequest(request);
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            return panel.getComponent();
        }
    }
}
