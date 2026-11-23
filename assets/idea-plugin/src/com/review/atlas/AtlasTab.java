package com.review.atlas;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;
import org.jetbrains.annotations.NotNull;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

/**
 * One atlas page: JCEF render + 3-layer jump fallback (JS bridge → CEF request intercept → server /jump),
 * status-driven watchdog and an explicit fallback card. JCEF quirks handled here:
 * injected scripts are opaque/cross-origin (all guarded), animations may freeze in occluded panels
 * (the page must not gate visibility on animation state).
 */
final class AtlasTab {

    private static final String CARD_BROWSER = "browser";
    private static final String CARD_FALLBACK = "fallback";
    private static final String CARD_EMPTY = "empty";
    private static final long LOAD_TIMEOUT_NANOS = 8_000_000_000L;

    private final Project project;
    private final AtlasHttpServer server;
    private final AtlasSettings settings;

    private final JPanel cardsHost = new JPanel(new CardLayout());
    private final CardLayout cards = (CardLayout) cardsHost.getLayout();
    private final JBCefBrowser browser;
    private final CefBrowser cef;
    private final Timer watchdog;

    private JBCefJSQuery jumpQuery;
    private JComponent fallbackPane;
    private JComponent emptyPane;
    private volatile boolean loaded;
    private volatile long attemptStartNanos;
    private int reloadSeq;

    AtlasTab(Project project, AtlasHttpServer server, AtlasSettings settings) {
        this.project = project;
        this.server = server;
        this.settings = settings;
        watchdog = new Timer(1000, e -> tick());
        watchdog.setRepeats(true);
        if (AtlasJcef.isSupported()) {
            browser = new JBCefBrowser(server.baseUrl() + "/");
            browser.setPageBackgroundColor(String.format("#%06x", JBColor.background().getRGB() & 0xFFFFFF));
            cef = browser.getCefBrowser();
            cardsHost.add(browser.getComponent(), CARD_BROWSER);
            installBridge();
            if (settings.closed()) {
                showEmpty();
            } else {
                attemptStartNanos = System.nanoTime();
                cards.show(cardsHost, CARD_BROWSER);
                watchdog.start();
            }
        } else {
            browser = null;
            cef = null;
            showFallback(null, false);
        }
    }

    JComponent component() {
        JPanel root = new JPanel(new BorderLayout());
        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("ReviewAtlasToolbar", toolbarActions(), true);
        toolbar.setTargetComponent(root);
        JComponent bar = toolbar.getComponent();
        bar.setBorder(JBUI.Borders.empty(4, 8));
        root.add(bar, BorderLayout.NORTH);
        root.add(cardsHost, BorderLayout.CENTER);
        installDnd(root);
        if (browser != null) {
            installDnd(browser.getComponent());
        }
        return root;
    }

    void dispose() {
        watchdog.stop();
        if (browser != null) {
            Disposer.dispose(browser);
        }
    }

    private String targetUrl() {
        return server.baseUrl() + "/";
    }

    private void tick() {
        if (loaded) {
            watchdog.stop();
            return;
        }
        if (cef != null) {
            String url = cef.getURL();
            if (url != null && url.startsWith(targetUrl())) {
                markLoaded();
                return;
            }
        }
        if (System.nanoTime() - attemptStartNanos >= LOAD_TIMEOUT_NANOS) {
            watchdog.stop();
            showFallback(null, true);
        }
    }

    private void markLoaded() {
        loaded = true;
        watchdog.stop();
        cards.show(cardsHost, CARD_BROWSER);
        System.out.println("[Review Atlas] page loaded: " + (cef == null ? "-" : cef.getURL()));
        injectJumpHook();
    }

    private void showFallback(String errorText, boolean timeout) {
        if (fallbackPane != null) {
            cardsHost.remove(fallbackPane);
        }
        fallbackPane = buildFallback(errorText, timeout);
        cardsHost.add(fallbackPane, CARD_FALLBACK);
        cards.show(cardsHost, CARD_FALLBACK);
        cardsHost.revalidate();
        cardsHost.repaint();
    }

    private DefaultActionGroup toolbarActions() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new AnAction("打开 HTML", "选择要渲染的 review atlas", AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                VirtualFile picked = FileChooser.chooseFile(
                        FileChooserDescriptorFactory.createSingleFileDescriptor("html"), project, null);
                if (picked != null) {
                    openFile(Path.of(picked.getPath()));
                }
            }
        });
        group.add(new AnAction("重载", "重新加载当前 HTML", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                reload();
            }

            @Override
            public void update(@NotNull AnActionEvent event) {
                event.getPresentation().setEnabled(!settings.closed() && server.hasFile());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }
        });
        group.add(new AnAction("关闭", "关闭当前 HTML，回到空态（下次启动保持关闭）", AllIcons.Actions.Close) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                closeCurrent();
            }

            @Override
            public void update(@NotNull AnActionEvent event) {
                event.getPresentation().setEnabled(!settings.closed());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }
        });
        group.add(new AnAction("外部浏览器", "在外部浏览器打开（跳转仍可用）", AllIcons.General.Web) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                BrowserUtil.browse(server.baseUrl());
            }

            @Override
            public void update(@NotNull AnActionEvent event) {
                event.getPresentation().setEnabled(!settings.closed() && server.hasFile());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }
        });
        return group;
    }

    /** 打开并记住一个 HTML：清空关闭态，切到浏览器卡片并重载。 */
    private void openFile(Path file) {
        settings.setHtmlPath(file.toAbsolutePath().normalize().toString());
        server.setActive(file.toAbsolutePath().normalize());
        removeEmpty();
        reload();
    }

    /** 关闭：停止调度与加载，清掉服务端当前文件，切到空态并持久化。 */
    private void closeCurrent() {
        settings.setClosed(true);
        server.close();
        loaded = false;
        watchdog.stop();
        showEmpty();
    }

    private void showEmpty() {
        if (emptyPane == null) {
            emptyPane = buildEmpty();
            cardsHost.add(emptyPane, CARD_EMPTY);
        }
        cards.show(cardsHost, CARD_EMPTY);
        cardsHost.revalidate();
        cardsHost.repaint();
    }

    private void removeEmpty() {
        if (emptyPane != null) {
            cardsHost.remove(emptyPane);
            emptyPane = null;
        }
    }

    private JComponent buildEmpty() {
        JPanel pane = new JPanel(new BorderLayout());
        pane.setBackground(UIUtil.getPanelBackground());
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        JBLabel head = new JBLabel("还没有打开 Review HTML", AllIcons.General.Information, SwingConstants.LEADING);
        head.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel body = new JBLabel("<html><div style=\"text-align:center;width:420px;\">"
                + "点工具栏「打开 HTML」选择文件，或把 .html 拖进本窗口。"
                + "<br>默认会自动打开项目 docs 下最新的 .html。</div></html>");
        body.setAlignmentX(Component.CENTER_ALIGNMENT);
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        JButton open = new JButton("打开 HTML…");
        open.addActionListener(e -> {
            VirtualFile picked = FileChooser.chooseFile(
                    FileChooserDescriptorFactory.createSingleFileDescriptor("html"), project, null);
            if (picked != null) {
                openFile(Path.of(picked.getPath()));
            }
        });
        actions.add(open);
        actions.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(Box.createVerticalStrut(24));
        column.add(head);
        column.add(Box.createVerticalStrut(10));
        column.add(body);
        column.add(Box.createVerticalStrut(16));
        column.add(actions);
        pane.add(column, BorderLayout.CENTER);
        return pane;
    }

    private void reload() {
        if (browser == null) {
            BrowserUtil.browse(server.baseUrl());
            return;
        }
        loaded = false;
        attemptStartNanos = System.nanoTime();
        cards.show(cardsHost, CARD_BROWSER);
        watchdog.restart();
        try {
            cef.loadURL(targetUrl() + "?r=" + reloadSeq++);
        } catch (Throwable t) {
            showFallback("CEF unavailable: " + t.getMessage(), false);
        }
    }

    private JComponent buildFallback(String errorText, boolean timeout) {
        boolean hasError = errorText != null && !errorText.isBlank();
        String title = browser == null ? "内嵌渲染不可用" : (hasError ? "页面加载失败" : "内嵌页面加载超时");
        String detail = browser == null
                ? "当前 IDE 的 JCEF 内核不可用（见 idea.log 中 JBCefApp/StartupTest 记录）。可以在外部浏览器打开，跳转依然生效。"
                : (hasError ? escape(errorText) : "8 秒内没有完成加载。可重试内嵌，或在外部浏览器打开——跳转仍可用。");
        JPanel pane = new JPanel(new BorderLayout());
        pane.setBackground(UIUtil.getPanelBackground());
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        JBLabel head = new JBLabel(title, hasError ? AllIcons.General.Error : AllIcons.General.Information, SwingConstants.LEADING);
        head.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel body = new JBLabel("<html><div style=\"text-align:center;width:420px;\">" + detail + "</div></html>");
        body.setAlignmentX(Component.CENTER_ALIGNMENT);
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        JButton external = new JButton("外部浏览器打开（可跳转）");
        external.addActionListener(e -> BrowserUtil.browse(server.baseUrl()));
        actions.add(external);
        if (browser != null) {
            JButton retry = new JButton("重试内嵌渲染");
            retry.addActionListener(e -> reload());
            actions.add(retry);
        }
        actions.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(Box.createVerticalStrut(24));
        column.add(head);
        column.add(Box.createVerticalStrut(10));
        column.add(body);
        column.add(Box.createVerticalStrut(16));
        column.add(actions);
        pane.add(column, BorderLayout.CENTER);
        return pane;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String[] jumpParams(String url) {
        String[] out = {null, null};
        int q = url.indexOf('?');
        if (q < 0) {
            return out;
        }
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            if ("file".equals(key)) {
                out[0] = value;
            } else if ("line".equals(key)) {
                out[1] = value;
            }
        }
        return out;
    }

    private void installBridge() {
        CefBrowser cefRef = cef;
        CefRequestHandlerAdapter requestHandler = new CefRequestHandlerAdapter() {
            @Override
            public boolean onBeforeBrowse(CefBrowser cefBrowser, CefFrame frame, CefRequest request,
                                          boolean userGesture, boolean isRedirect) {
                String url = request.getURL();
                if (url == null) {
                    return false;
                }
                if (url.startsWith("idea://") || url.startsWith(server.baseUrl() + "/jump?")) {
                    System.out.println("[Review Atlas] intercept navigate: " + url);
                    String[] params = jumpParams(url);
                    if (params[0] != null) {
                        AtlasJump.open(project, params[0], params[1]);
                    }
                    return true;
                }
                return false;
            }
        };
        browser.getJBCefClient().addRequestHandler(requestHandler, cefRef);
        Disposer.register(browser, () -> browser.getJBCefClient().removeRequestHandler(requestHandler, cefRef));

        jumpQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        jumpQuery.addHandler(payload -> {
            int sep = payload.indexOf('\t');
            if (sep > 0) {
                String file = payload.substring(sep + 1);
                if ("__ping__".equals(file)) {
                    System.out.println("[Review Atlas] jump hook alive");
                } else {
                    AtlasJump.open(project, file, payload.substring(0, sep));
                }
            }
            return new JBCefJSQuery.Response(null);
        });

        CefLoadHandlerAdapter loadHandler = new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                if (!frame.isMain()) {
                    return;
                }
                String url = frame.getURL();
                if (url != null && url.startsWith(targetUrl())) {
                    markLoaded();
                }
            }

            @Override
            public void onLoadError(CefBrowser cefBrowser, CefFrame frame, CefLoadHandler.ErrorCode errorCode,
                                    String errorText, String failedUrl) {
                if (!frame.isMain()) {
                    return;
                }
                String failed = failedUrl == null ? "" : failedUrl;
                if (!failed.startsWith(targetUrl())) {
                    return;
                }
                loaded = false;
                watchdog.stop();
                showFallback(errorCode + " " + errorText + " " + failedUrl, false);
            }
        };
        browser.getJBCefClient().addLoadHandler(loadHandler, cefRef);
        Disposer.register(browser, () -> browser.getJBCefClient().removeLoadHandler(loadHandler, cefRef));
    }

    private void injectJumpHook() {
        if (jumpQuery == null || cef == null) {
            return;
        }
        // Injected scripts are treated as anonymous/cross-origin by JCEF: any throw becomes an opaque
        // "Script error. @:0". Guard everything; when the bridge is missing the hook must NOT
        // preventDefault — let the anchor navigate so the request layer / server /jump takes over.
        String hook = """
                (function () {
                  try {
                    if (!window.__raJumpInstalled) {
                      window.__raJumpInstalled = true;
                      document.addEventListener('click', function (e) {
                        try {
                          var t = e.target;
                          var a = t && t.closest ? t.closest('a[href^="idea://"], a[href*="/jump?"], a[data-aw-jump]') : null;
                          if (!a) return;
                          if (typeof window.awJumpQuery !== 'function') return;
                          var payload = a.getAttribute('data-aw-jump');
                          if (!payload) {
                            var u = new URL(a.getAttribute('href'));
                            payload = (u.searchParams.get('line') || '1') + '\\t' + (u.searchParams.get('file') || '');
                          }
                          e.preventDefault();
                          e.stopPropagation();
                          try {
                            window.awJumpQuery(payload);
                          } catch (err) {
                            window.location.href = a.href;
                          }
                        } catch (ignored) {}
                      }, true);
                    }
                    if (typeof window.awJumpQuery === 'function') {
                      window.awJumpQuery('0\\t__ping__');
                    }
                  } catch (ignored) {}
                })();
                """;
        cef.executeJavaScript("try{\n" + jumpQuery.inject("window.awJumpQuery") + "\n}catch(e){}\n" + hook,
                cef.getURL(), 0);
    }

    private void installDnd(Component target) {
        new DropTarget(target, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent event) {
                event.acceptDrop(DnDConstants.ACTION_COPY);
                try {
                    if (!event.getTransferable().isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                        event.dropComplete(false);
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) event.getTransferable()
                            .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    File html = files.stream()
                            .filter(f -> f.isFile() && f.getName().toLowerCase().endsWith(".html"))
                            .findFirst().orElse(null);
                    if (html == null) {
                        event.dropComplete(false);
                        return;
                    }
                    openFile(html.toPath());
                    event.dropComplete(true);
                } catch (Exception ex) {
                    event.dropComplete(false);
                }
            }
        }, true);
    }
}
