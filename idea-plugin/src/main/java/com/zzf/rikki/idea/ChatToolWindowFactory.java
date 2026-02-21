package com.zzf.rikki.idea;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.zzf.rikki.idea.agent.LiteAgentServer;
import org.jetbrains.annotations.NotNull;

public final class ChatToolWindowFactory implements ToolWindowFactory {

    private static volatile LiteAgentServer agentServer;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ensureAgentServer(project);
        ChatPanel panel = new ChatPanel(project);
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(panel.getComponent(), "", false);
        toolWindow.getContentManager().addContent(content);
    }

    private static synchronized void ensureAgentServer(Project project) {
        if (agentServer == null) {
            LiteAgentServer server = new LiteAgentServer(project);
            server.start();
            agentServer = server;
        }
    }
}

