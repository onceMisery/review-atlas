package com.review.atlas;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "ReviewAtlasSettings", storages = @Storage("reviewAtlas.xml"))
public final class AtlasSettings implements PersistentStateComponent<AtlasSettings.State> {

    public static final class State {
        public String htmlPath = "";
        public boolean closed;
    }

    private State state = new State();

    public static AtlasSettings getInstance(Project project) {
        return project.getService(AtlasSettings.class);
    }

    public String htmlPath(Project project) {
        if (state.htmlPath == null || state.htmlPath.isBlank()) {
            String base = project.getBasePath();
            return base == null ? "" : base + "/docs/review-atlas.html";
        }
        return state.htmlPath;
    }

    public void setHtmlPath(String path) {
        state.htmlPath = path;
        state.closed = false;
    }

    /** 用户显式关闭后记住空态；点「打开 HTML」或拖入文件会清除。 */
    public boolean closed() {
        return state.closed;
    }

    public void setClosed(boolean closed) {
        state.closed = closed;
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        state = loaded;
    }
}
