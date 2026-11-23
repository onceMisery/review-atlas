# HTML Atlas Spec

The atlas is one HTML fragment file. It renders standalone in any browser, inside the plugin's JCEF tool window, and inline in chat hosts (Qoder visualization).

## Fragment contract (must pass validator)

- No `<!doctype>/<html>/<head>/<body>`; one root element with a unique id (template: `#aw-root`).
- Zero network: no fetch/XHR/WebSocket/CDN/webfonts. All CSS/JS inline.
- Scope every selector under the root id (use CSS nesting `& …`).
- Host design variables with fallbacks: `var(--seed-fg, var(--aw-fg))` etc. Never set `:root`/`html` styles.
- No `position:fixed`, no viewport-height layout, no page-level horizontal scroll; wide grids scroll inside a wrapper.
- Body text 13px, secondary 12px, nothing below 11px; weights 400/500; native `button`/`input`; no `tabindex` additions; no `!important` except the screen-reader utility.
- No web storage (`localStorage`/`sessionStorage`) — sandboxes block it and static validators reject it. Theme persists per session only.
- All business content lives in plain JS arrays at the top of the script (`F`, `LANES`, `VIEWS`, `STATES`); render functions stay generic.

## Data schema

```js
function L(file, cls, method, line, sig, note) { … }
// sig:  callsite nuance, e.g. "saveAuto 调用 :144" (shown small, keeps card labeled by method)
// note: one-sentence plain-language: what this method actually does (required for review-grade cards)

VIEWS = {
  main: {
    title, lanes: [LANES.xxx, …],
    events: [{
      f, t,            // lane indexes; f===t renders "内部动作"
      k: "call|cb|db|gate|fail",
      imp: 3|2|1,      // importance: core mutation / gate / entry-branch (drives tint intensity)
      h,               // step title
      ep,              // endpoint or short technical line
      plain, trigger, gate, state, fail,   // 大白话 + verbatim gates + state deltas + failure path
      loc:  L(...),    // entry location (primary jump)
      locs: [L(...)],  // supporting locations in call order
      drill: "ledger"  // optional child view id
    }]
  },
  birth: {…}, ledger: {…}
}
STATES = [[变化, 条件, SQL 位置], …]  // state machine quick table
```

Required ids and globals (the plugin bridge and host tooling depend on them):
`#aw-root[data-aw-jump-base="http://aw-jump-base.local"]`, `#aw-nav #aw-head #aw-body #aw-detail #aw-hist #aw-announcer`, `window.__AW_CODE_MAP__` (with `projectRoot`, `views[].steps[].locations[].{order,file,absPath,class,method,line,signature,note,ideaUrl}`).

## Jump links

- `jumpHref(loc)` renders `http://127.0.0.1:PORT/jump?file=…&line=…` **when served by the plugin** (the server replaces the `aw-jump-base.local` token), else `idea://open?file=…&line=…`.
- Click chain must not depend on one mechanism. Page-side hook → CEF request intercept → server-side `/jump` are three independent fallbacks; the page hook must **not** `preventDefault()` when `window.awJumpQuery` is not a function (let navigation reach the request handler/server).
- Show a per-location「复制映射」button (declarative `command`/`commandfor` with click fallback) that copies the location JSON.

## Detail section color system (right pane)

Each explanation block carries a semantic hue (`data-kind`), applied to the label, a 6px dot, a 3px inline-start border, a ≤6% tinted background, and the inline-emphasis text inside it:

| kind | block | hue token |
| --- | --- | --- |
| `plain` | 大白话：这一步在做什么 | `--s1` (magenta) |
| `trigger` | 触发条件 | `--s6` (cyan) |
| `gate` | 关键判断 / 分支 | `--s3` (amber) |
| `state` | 状态变化 | `--s2` (green) |
| `fail` | 失败行为 | `--s4` (red) |

Inline markup inside section bodies (escape first, then replace; hue via `color: var(--sc)` inherited from the block):

- `**强调**` → `<b class="aw-b">` (weight 500, section hue)
- 「引用句」/『引用句』 (≤80 chars) → `<em class="aw-q">`, brackets kept, section hue

Body `<dd>` text itself stays `var(--fg)` so long paragraphs keep AA contrast; only labels and highlighted tokens take the hue. Tinted backgrounds must degrade to plain `var(--inset)` without `color-mix` support.

**Frosted glass**: the five section blocks and the per-method plain-language notes (`.aw-mnote`) render as glass chips — accent-tinted translucent background (≤8%), `backdrop-filter: blur(6px)` under `@supports (backdrop-filter: blur(2px))`, and a 1px inner top sheen (`inset 0 1px 0 color-mix(#fff …)`) inside the `color-mix` guard. Text keeps `var(--fg)`; the glass is decoration, never the only contrast carrier.

## Interaction contract

- Theme: 跟随系统/浅色/深色; explicit modes bypass host variables; `color-scheme` per theme on the root; smooth color transition.
- History: view-level history (drill/breadcrumb/goto commit; selection only updates current entry); ←/→ buttons beside the theme group + Alt+←/→; a 「返回上级」 button in the nav row.
- Split: draggable vertical splitter between columns (300px…container−380), double-click reset 430, ←/→ keys ±24, `aria-valuenow` kept in sync; columns below 1080px stack (splitter hidden).
- Detail: chain chips (call order) scroll+flash the matching method card; method cards show order badge, `Class.method`, line chip, plain-language note, signature, file path, jump/copy actions.
- Sequence: ordered list semantics (`ol role=list`), selected step `aria-current="step"`, ↑↓/Home/End moves focus, drill tag pulses; importance tint via `color-mix` (with `@supports` guard).
- Copy: one `copy()/report()`; visible bubble + `role=status`; 2s cleanup; `execCommand` only as last resort on non-secure contexts.

## JCEF pitfalls (learned the hard way)

1. **Background ruled-line textures look like noise in dark IDE themes** — paint the root background, keep it plain unless the user asks for texture.
2. **Opacity-only entrance animations without `animation-fill-mode: both` freeze at frame 0 in occluded/background JCEF panels** → invisible detail pane. All animations use `both`; never gate the detail pane's visibility on animation state.
3. `Script error. @:0` = opaque cross-origin error from plugin-injected scripts. The page banner must only display errors **with a filename** (same-origin); log opaque ones to console.
4. `idea://` is usually **not registered** on Windows; never make it the only jump path (see Jump links).
5. JCEF may be disabled/broken (see `idea.log` `StartupTest/JBCefApp`). The plugin needs a visible fallback panel + external-browser path, not a blank window.

## Acceptance checklist

- [ ] `scripts/verify_anchors.cjs` reports 0 hard issues.
- [ ] Fragment validator PASS (contract above).
- [ ] Click-through: all views, all drills incl. nested; theme trio; history back/forward/up; splitter drag/reset/keyboard; chain flash; copy feedback (success or visible failure).
- [ ] Zero `undefined/NaN/[object Object]` in detail text; `window.onerror` empty.
- [ ] No overlapping step cards; no page-level horizontal scroll at ~650px and ~480px widths.
- [ ] Dark root background is neutral graphite (max channel spread ≤ 4), table text ≥ 4.5:1, tinted cards ≥ 4.5:1.
- [ ] Served mode renders `/jump?file=…` links; standalone mode renders `idea://` links.
