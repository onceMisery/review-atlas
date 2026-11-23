package com.review.atlas;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Loopback-only service: GET / serves the atlas with the jump base token interpolated;
 * GET /jump?file=&line= opens the editor at that line and 302s back to /. When the configured
 * file is missing, the newest sibling docs/*.html is served automatically (the common case:
 * the atlas was generated under a dated name). Read failures retry and fall back to the last
 * good body; the hint page is self-themed and works on dark and light IDE backgrounds.
 */
final class AtlasHttpServer {

    static final String BASE_TOKEN = "http://aw-jump-base.local";
    private static final int READ_ATTEMPTS = 3;
    private static final long READ_RETRY_MS = 50;
    private static final int HINT_CANDIDATES = 8;

    private final HttpServer server;
    private final int port;
    private final Supplier<Path> configured;
    private volatile Path activePath;
    private volatile byte[] lastGood;

    AtlasHttpServer(Project project, Supplier<Path> htmlPath) throws IOException {
        this.configured = htmlPath;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            try {
                System.out.println("[Review Atlas] HTTP " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
                if ("/jump".equals(exchange.getRequestURI().getPath())) {
                    jump(project, exchange);
                } else {
                    serveHtml(exchange);
                }
            } catch (Exception ex) {
                System.err.println("[Review Atlas] HTTP failed: " + ex);
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(null);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    void setActive(Path file) {
        activePath = file;
        lastGood = null;
    }

    void close() {
        activePath = null;
    }

    boolean hasFile() {
        return resolve() != null;
    }

    void stop() {
        server.stop(0);
    }

    private Path candidate() {
        Path active = activePath;
        return active != null ? active : configured.get();
    }

    /** 配置路径存在则用之；否则在同一个 docs 目录里挑最新的 .html（时序文档常带日期名）。 */
    private Path resolve() {
        Path file = candidate();
        if (file != null && Files.isRegularFile(file)) {
            return file;
        }
        List<Path> siblings = siblings(file);
        return siblings.isEmpty() ? null : siblings.get(0);
    }

    private static List<Path> siblings(Path configuredFile) {
        Path dir = configuredFile == null ? null : configuredFile.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(dir)) {
            List<Path> htmls = new ArrayList<>();
            list.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".html"))
                    .filter(Files::isRegularFile)
                    .forEach(htmls::add);
            htmls.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
            return htmls;
        } catch (IOException ex) {
            return List.of();
        }
    }

    private void serveHtml(HttpExchange exchange) throws IOException {
        Path file = resolve();
        byte[] body = file == null ? null : readWithRetry(file);
        if (body == null) {
            body = hintPage(candidate(), siblings(candidate())).getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private byte[] readWithRetry(Path file) {
        for (int attempt = 0; attempt < READ_ATTEMPTS; attempt++) {
            try {
                if (Files.isRegularFile(file)) {
                    lastGood = Files.readString(file, StandardCharsets.UTF_8)
                            .replace(BASE_TOKEN, baseUrl())
                            .getBytes(StandardCharsets.UTF_8);
                    return lastGood;
                }
            } catch (IOException ex) {
                if (attempt < READ_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(READ_RETRY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return lastGood;
    }

    private void jump(Project project, HttpExchange exchange) throws IOException {
        String file = queryParam(exchange, "file");
        String line = queryParam(exchange, "line");
        if (file != null) {
            AtlasJump.open(project, file, line);
        }
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(302, -1);
    }

    /** 自绘明暗两套配色的提示页：不依赖 IDE 面板背景，小窗口下也清晰可读。 */
    private static String hintPage(Path configuredFile, List<Path> siblings) {
        StringBuilder items = new StringBuilder();
        int shown = 0;
        for (Path p : siblings) {
            if (shown++ >= HINT_CANDIDATES) {
                break;
            }
            items.append("<li>").append(escape(p.getFileName().toString())).append("</li>");
        }
        String list = items.length() == 0
                ? "<p class=\"muted\">目录下也没有其他 .html 文件。</p>"
                : "<p class=\"muted\">同目录下可用的 HTML（最新的会被自动选中）：</p><ul>" + items + "</ul>";
        String path = configuredFile == null ? "(未设置)" : configuredFile.toString();
        return """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <meta name="color-scheme" content="light dark"><title>Review Atlas</title><style>
                :root{color-scheme:light dark;
                  --bg:light-dark(#fdfcf9,#1e1f22);--fg:light-dark(#1c1b18,#dfe1e6);
                  --muted:light-dark(#5a6478,#9da1a9);--card:light-dark(#ffffff,#2b2d30);
                  --bd:light-dark(#d8d5cc,#3d3f45);--pri:light-dark(#1f3a5f,#a8bfd9);--inset:light-dark(#efeee8,#26282c)}
                @media (prefers-color-scheme: dark){:root{--bg:#1e1f22;--fg:#dfe1e6;--muted:#9da1a9;
                  --card:#2b2d30;--bd:#3d3f45;--pri:#a8bfd9;--inset:#26282c}}
                html,body{margin:0;min-height:100%;background:var(--bg);color:var(--fg);
                  font:14px/1.7 "Segoe UI","Microsoft YaHei",sans-serif}
                main{max-width:560px;margin:48px auto;padding:22px 26px;background:var(--card);
                  border:1px solid var(--bd);border-radius:12px}
                h1{font-size:17px;margin:0 0 8px} p{margin:8px 0}
                .muted{color:var(--muted);font-size:12.5px}
                code{display:block;margin:6px 0;padding:7px 9px;background:var(--inset);border:1px solid var(--bd);
                  border-radius:6px;font:12px/1.5 ui-monospace,Consolas,monospace;word-break:break-all}
                ul{margin:4px 0 10px;padding-inline-start:20px}
                b{color:var(--pri);font-weight:500}
                </style></head><body><main><h1>还没有可显示的 Review Atlas</h1>
                <p>当前配置的文件不存在：</p><code>%s</code>
                %s
                <p>用工具栏 <b>「打开 HTML」</b> 选择文件，或把 .html 直接拖进本窗口；成功后会自动记住。</p>
                </main></body></html>
                """.formatted(escape(path), list);
    }

    private static String queryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
