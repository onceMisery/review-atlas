# Review Atlas Bridge — Plugin Spec

A thin IntelliJ shell: JCEF tool window + loopback HTTP service + click-to-code jump. Zero business content; the HTML atlas is the source of truth and is re-read from disk on every request.

## Architecture

```
docs/review-atlas.html ──interpolated──▶ AtlasHttpServer (127.0.0.1:ephemeral)
      ▲ token replace: http://aw-jump-base.local → http://127.0.0.1:PORT          │
      │                                                                           ▼
AtlasTab (JCEF) ◀── loadURL http://127.0.0.1:PORT/ ── user click ──▶ 3-layer jump:
      1) JS bridge  window.awJumpQuery(line\tfile)   → JBCefJSQuery handler
      2) CEF request intercept idea:// + /jump?…     → cancel navigation, jump
      3) GET /jump?file&line on the local server     → jump + 302 back to /
AtlasJump.open(project, file, line) → LocalFileSystem.findFileByPath + OpenFileDescriptor.navigate(true)
```

Why three layers: `idea://` is frequently unregistered; `executeJavaScript` injection is treated as an anonymous/cross-origin script (silent failures); the request handler is deterministic; the server endpoint survives even if all CEF interception dies (external browser uses it directly).

## Server

- `com.sun.net.httpserver`, bind `127.0.0.1` only, port 0 (ephemeral). No auth, no exec surface: `/jump` can only open a file in the editor.
- `GET /` serves the active atlas with `.replace(BASE_TOKEN, baseUrl())`; 3 read retries + last-good cache (Windows file locks while regenerating).
- File resolution: configured path → if missing, the **newest sibling `docs/*.html`** is served automatically (dated atlas names are the common case).
- `GET /jump?file=…&line=…` → `AtlasJump.open`, then `302 Location: /`.
- Log every request and every jump to stdout: `[Review Atlas] HTTP …`, `[Review Atlas] jump request: file:line` — these lines land in `idea.log` and are the debugging backbone.
- No drawable file → self-themed hint page (`color-scheme: light dark` + `light-dark()` with media fallback, self-painted background, 14px centered card, lists sibling candidates). It must not rely on the IDE panel background — a transparent page with tiny gray text is unreadable in dark IDEs.

## Tool window tab

- `JBCefBrowser(targetUrl)` constructed once; `setPageBackgroundColor` from theme to avoid white flash.
- Watchdog: 1s `Timer` compares `cef.getURL()` with the target URL — self-heals when `onLoadEnd` is lost to CEF init races; only after 8s without a committed target does it show the fallback card (external browser + retry).
- `onLoadEnd` (main frame, target URL) → `markLoaded()` → `injectJumpHook()`.
- Injection is **fully guarded**: `try{ inject }catch{}` + IIFE-wrapped hook with per-handler try/catch + a `__awping__` probe through the bridge (`jump hook alive` log). Never let the injected file throw into the page (it surfaces as opaque `Script error. @:0`).
- Hook contract: if `window.awJumpQuery` is missing, do nothing (no `preventDefault`) so the anchor navigates and the request layer/server takes over; on bridge call failure fall back to `window.location.href = a.href`.
- Request handler (`CefRequestHandlerAdapter.onBeforeBrowse`): cancel and jump on `idea://` and `<base>/jump?…`, log `intercept navigate:`. Note the CEF 2026.3 signatures: `onLoadError(CefBrowser, CefFrame, CefLoadHandler.ErrorCode, String, String)`, `JBCefClient.addRequestHandler(handler, cef)` / `removeRequestHandler(handler, cef)` (2 args), `browser.getCefBrowser()`, `browser.getJBCefClient()`.
- DnD: drop `.html` onto the tab/root switches the served file. Toolbar: 打开 HTML / 重载 (cache-bust `&r=N`, enabled only when a file is resolvable) / **关闭** (resets to an empty Swing card and persists `closed=true`, so a restart stays closed until the next Open/drag) / 外部浏览器 (`BrowserUtil.browse(server.baseUrl())`).
- Settings: `@State` project service persisting `htmlPath` + `closed`; default is `docs/review-atlas.html` under the project root, with the newest-`docs/*.html` fallback above.

## plugin.xml essentials

- `<depends>com.intellij.modules.platform</depends>`
- **2026.3+ splits JCEF into its own module**: add `<depends optional="true" config-file="atlas-jcef.xml">com.intellij.modules.jcef</depends>`; the fragment is a minimal `<idea-plugin><depends>com.intellij.modules.jcef</depends></idea-plugin>`. Without it, `JBCefApp` is `NoClassDefFoundError` on 2026.3.
- Tool window icon: original tomoe-iris SVG (`icons/reviewAtlas.svg`), single accent hue + graphite, legible at 16px. Do not copy Naruto artwork; the tomoe (巴纹) motif itself is traditional/public-domain.
- Optional `<version>` omitted — dynamic install from disk works; the IDE assigns/keeps whatever the zip declares.

## Build on Windows without Gradle

`assets/idea-plugin/build.sh`:

1. IDE discovery (`build.sh`) never hardcodes a user path: `IDEA_HOME` wins; otherwise candidates come from common OS roots (Windows `Program Files/JetBrains`, Toolbox `apps/IDEA*`, macOS `/Applications/IntelliJ IDEA*.app/Contents`, Linux `/opt/idea*` + Toolbox) and the one with the newest `bin/build.txt` is used; failure prints "set IDEA_HOME".
2. Compile with **JBR's javac** (`"$IDE/jbr/bin/javac" --release 21`) against `lib/*.jar` **plus, on 2026.3+, `plugins/jcef-plugin/lib/modules/*.jar`** (JCEF classes moved out of `lib/` into that module directory — omitting it yields `cannot find symbol JBCefApp/CefBrowser`); the classpath is ~20KB → must go through a `@argfile`, and every path needs `cygpath -m` (Windows javac cannot read `/c/...`).
3. Package with **any JDK `jar`** on PATH (JBR ships no `jar` tool): `jar cf dist/review-atlas-bridge.jar -C out .` with `META-INF/` and `icons/` at the jar root.
4. Install = Settings → Plugins → ⚙ → Install Plugin from Disk → jar; dynamic plugins usually load without restart; verify, don't assume.

## Automated install (copy-into-plugins-dir)

The IDE's graphical Install-from-Disk flow cannot be scripted, but the underlying effect can: a jar placed at `<config>/plugins/review-atlas-bridge.jar` is loaded on the next IDE start.

- Config root: Windows `%APPDATA%/JetBrains`, macOS `~/Library/Application Support/JetBrains`, Linux `${XDG_CONFIG_HOME:-~/.config}/JetBrains`; target dir = newest `IntelliJIdea*` (override with `IDEA_CONFIG_DIR`).
- `scripts/install_plugin.sh --check|--dry-run|--install --jar dist/review-atlas-bridge.jar`: hash-compares built vs installed jar (`INSTALLED_CURRENT/OUTDATED`), backs up the previous jar, prints `TARGET`, `RUNNING`, `RESTART_REQUIRED` and the exact `UNINSTALL=rm …` command. No admin rights, no registry changes.
- A jar copied while the IDE runs becomes active only after a restart — that restart is the single manual step; everything else (build, copy, upgrade detection, uninstall hint) is automated.
- Policy: first use = install automatically; later uses = `--check` only, regenerate the HTML (the plugin re-reads it per request). Honor explicit user opt-outs by printing the manual fallback steps instead.

## Troubleshooting quick map

| Symptom | First check |
| --- | --- |
| Blank/white tool window | `idea.log`: `StartupTest/JBCefApp` (JCEF kernel dead) → fallback panel/external browser; also default HTML path missing (notification) |
| Click does nothing | `[Review Atlas]` lines: no request & no hook alive → bridge injection failed (request layer should still fire on `/jump`); none at all → page not served by plugin (open via toolbar「打开 HTML」) |
| Error page ERR_UNKNOWN_URL_SCHEME | `idea://` navigated because the hook swallowed `preventDefault` before the bridge existed — keep the "don't intercept when bridge missing" rule |
| `Script error. @:0` banner | opaque injected-script error; ensure injection is try/catch-wrapped and the page ignores filename-less errors |
| Empty right panel in JCEF only | detail pane gated by opacity-only animation without `both` (JCEF freezes frame 0) |
| Renderer crash on 2026.3 | missing optional jcef module descriptor |
