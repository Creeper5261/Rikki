package com.zzf.codeagent.idea;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.editor.Document;
import com.intellij.ui.EditorNotificationPanel;
import com.zzf.codeagent.core.tool.PendingChangesManager;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public class DiffService {
    private final Project project;
    private final Map<FileEditor, EditorNotificationPanel> notificationPanels = Collections.synchronizedMap(new WeakHashMap<>());

    public DiffService(Project project) {
        this.project = project;
    }

    public void applyChange(PendingChangesManager.PendingChange change) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                applyPendingChange(change, false);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void confirmChange(PendingChangesManager.PendingChange change) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                applyPendingChange(change, true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void revertChange(PendingChangesManager.PendingChange change) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                // Reject path: do not apply changes to real files.
                VirtualFile file = findVirtualFile(change.path);
                if (file != null) removeNotification(file);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void openFile(PendingChangesManager.PendingChange change) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                VirtualFile file = findVirtualFile(change.path);
                if (file != null) {
                    FileEditorManager.getInstance(project).openFile(file, true);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private VirtualFile findVirtualFile(String pathStr) {
        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(pathStr);
        if (virtualFile == null && project.getBasePath() != null) {
            Path absPath = java.nio.file.Paths.get(project.getBasePath(), pathStr);
            virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath.toString());
        }
        return virtualFile;
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
                // 1. Resolve File
                String pathStr = change.path;
                VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(pathStr);
                if (virtualFile == null && project.getBasePath() != null) {
                     Path absPath = java.nio.file.Paths.get(project.getBasePath(), pathStr);
                     virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath.toString());
                }

                // 2. Show Notification in Editor (do not apply until user confirms)
                if (virtualFile != null) {
                    FileEditorManager manager = FileEditorManager.getInstance(project);
                    manager.openFile(virtualFile, true); // Focus editor
                    
                    final VirtualFile finalFile = virtualFile;
                    for (FileEditor editor : manager.getEditors(virtualFile)) {
                        // Remove existing panel if any
                        EditorNotificationPanel existing = notificationPanels.remove(editor);
                        if (existing != null) {
                            manager.removeTopComponent(editor, existing);
                        }

                        EditorNotificationPanel panel = new EditorNotificationPanel();
                        panel.setText("Agent generated changes for " + finalFile.getName());
                        
                        // Store reference
                        notificationPanels.put(editor, panel);
                        
                        // Accept: Apply changes now
                        panel.createActionLabel("Accept", () -> {
                            if (project.isDisposed()) return;
                            applyPendingChange(change, false);
                            if (onAccept != null) onAccept.run();
                        });
                        
                        // Reject: Clear notification only
                        panel.createActionLabel("Reject", () -> {
                            if (project.isDisposed()) return;
                            manager.removeTopComponent(editor, panel);
                            notificationPanels.remove(editor);
                            if (onReject != null) onReject.run();
                        });
                        
                        // Show Diff: Open the Diff Window for detailed comparison
                        panel.createActionLabel("Show Diff", () -> {
                            if (project.isDisposed()) return;
                            // Show diff between original and proposed.
                            showDiffExplicit(change.path, change.oldContent, change.newContent);
                        });
                        
                        manager.addTopComponent(editor, panel);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (!project.isDisposed()) {
                    Messages.showErrorDialog(project, "Failed to apply changes: " + e.getMessage(), "Error");
                }
            }
        });
    }

    private void applyPendingChange(PendingChangesManager.PendingChange change, boolean openAfter) {
        if (project.isDisposed() || change == null) return;
        String pathStr = change.path;
        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(pathStr);
        if (virtualFile == null && project.getBasePath() != null) {
            Path absPath = java.nio.file.Paths.get(project.getBasePath(), pathStr);
            virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath.toString());
        }

        if ("DELETE".equalsIgnoreCase(change.type)) {
            if (virtualFile != null) {
                final VirtualFile toDelete = virtualFile;
                ApplicationManager.getApplication().runWriteAction(() -> {
                    try {
                        toDelete.delete(this);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }
        } else if ("CREATE".equalsIgnoreCase(change.type) && virtualFile == null) {
            if (project.getBasePath() != null) {
                createFile(java.nio.file.Paths.get(project.getBasePath(), pathStr).toString(), change.newContent);
                virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(java.nio.file.Paths.get(project.getBasePath(), pathStr).toString());
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
             } catch(Exception e) { e.printStackTrace(); }
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
                e.printStackTrace();
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
                e.printStackTrace();
            }
        });
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
