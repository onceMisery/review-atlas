package com.review.atlas;

import java.io.IOException;
import java.nio.file.Path;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

/** Tool window shell: one atlas page per window, sharing one loopback jump service. */
public final class AtlasToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        AtlasSettings settings = AtlasSettings.getInstance(project);
        AtlasHttpServer server;
        try {
            server = new AtlasHttpServer(project, () -> Path.of(settings.htmlPath(project)));
        } catch (IOException ex) {
            toolWindow.getContentManager().addContent(ContentFactory.getInstance()
                    .createContent(new javax.swing.JLabel("Local jump service failed: " + ex.getMessage()), "", false));
            return;
        }
        Disposer.register(toolWindow.getDisposable(), server::stop);

        AtlasTab tab = new AtlasTab(project, server, settings);
        Content content = ContentFactory.getInstance().createContent(tab.component(), "Atlas", false);
        Disposer.register(content, tab::dispose);
        toolWindow.getContentManager().addContent(content);
    }
}
