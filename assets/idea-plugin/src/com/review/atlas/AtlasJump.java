package com.review.atlas;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

/** Shared editor jump used by the JS bridge, the CEF request interceptor and the local /jump endpoint. */
final class AtlasJump {

    private static final String GROUP = "Review Atlas";

    private AtlasJump() {
    }

    static void open(Project project, String path, String lineText) {
        int line;
        try {
            line = Integer.parseInt(lineText == null ? "1" : lineText.trim());
        } catch (NumberFormatException invalid) {
            Notifications.Bus.notify(new Notification(GROUP, "跳转参数无效", String.valueOf(lineText),
                    NotificationType.WARNING), project);
            return;
        }
        if (path == null || path.isBlank()) {
            Notifications.Bus.notify(new Notification(GROUP, "跳转参数缺失", "file 为空", NotificationType.WARNING), project);
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            System.out.println("[Review Atlas] jump request: " + path + ":" + line);
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
            if (file == null) {
                Notifications.Bus.notify(new Notification(GROUP, "跳转目标不存在", path, NotificationType.WARNING), project);
                return;
            }
            new OpenFileDescriptor(project, file, Math.max(line - 1, 0), 0).navigate(true);
        });
    }
}
