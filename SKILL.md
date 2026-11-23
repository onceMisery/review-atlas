---
name: build-review-atlas
description: Turn a business flow in any codebase into a review-grade interactive HTML atlas (sequence left / code anchors right, plain-language notes, drill-down, theme, history, adjustable split) plus a thin IntelliJ plugin that renders it in an IDE tool window and jumps to the exact file:line on click. Use when asked to visualize or hand over a code flow for review, produce a 时序图 + 代码位置映射, make a business-process explainer bound to real code, or set up an in-IDE HTML review viewer with click-to-code. Also use for 大白话流程图、代码走读页、review 用交互页、IDEA 内嵌 review HTML.
---

# Build Review Atlas

Produce one evidence-backed deliverable pair: **(A)** a single-file, zero-dependency HTML atlas that maps a flow to exact code anchors with plain-language annotations, and **(B)** a thin IntelliJ plugin that hosts that HTML in a JCEF tool window and jumps to `file:line` on click. The HTML is the single source of truth; the plugin contains zero business content.

## Hard rules (non-negotiable)

1. **Every box traces to a `file:line` you opened.** No line reference, no node. When docs and code disagree, code wins; record the conflict as an item to re-verify.
2. **The HTML must pass the fragment contract** (validator in `scripts/validate_fragment.cjs` of the `code-to-interactive-flow` skill if installed; otherwise follow `references/html-atlas-spec.md`): fragment only, zero network, one root id, host CSS variables with fallbacks, fonts ≥11px, native controls, no `position:fixed`, data-driven arrays.
3. **Never claim the IDE plugin works without the user installing it.** Compiling is your verification limit; the user installs and clicks. Guide them explicitly (Phase 5).
4. **Neutralize the shipped assets**: the template, plugin sources and docs under this skill must never contain project-specific content — no real table/service/class names, domain terms, tenant or org identifiers, or copied flows. Keep the demo purely generic (`PaymentScanService`, `t_settlement_task`, `org-A`); real names live only in the per-project deliverable.

## Phase 1 — Pin the truth to code

Budget reading. Toolchain: grep for entry points (`*Controller`, jobs, consumers), then read the state-mutating helpers and failure paths, not just the happy line.

Inventory per step before drawing anything:

| Item | Capture |
| --- | --- |
| Actors | Who acts: scheduler, external system, module A→B |
| Gates | Preconditions with **verbatim** rejection messages |
| State fields | What moves, sign/direction of each mutation, terminal sentinel values |
| Idempotency | Operation keys, CAS WHERE clauses, retry budget ownership |
| Timing | Which moment the value actually changes (submit? callback? commit?) |
| Failure paths | What rolls back, what only logs, what silently no-ops |

If the flow was recently refactored, run `git log --oneline -12` and `git show --stat <sha>` on the touched packages first — line maps rot faster than architecture.

## Phase 2 — Plan the atlas structure

Sizes that work:

- **Views (tabs via breadcrumbs)**: 1 main chain (8–14 steps), 1 "birth/entry points" view (3–4 steps), 3–5 drill-downs (bookkeeping internals, per-branch executors, sub-flows).
- **Per step**: `plain` (大白话, one paragraph, no jargon), `trigger`, `gate` (verbatim messages), `state` (fields + direction), `fail`.
- **Per step locations**: an ordered list `[entry, ...supporting]`. Each location gets `sig` (callsite suffix) and `note` (one-sentence plain-language: what this method actually does).
- **Importance tiers** for card tinting: `imp:3` core mutation (posting/claim/complete), `imp:2` gates/planning, `imp:1` entries/branches.
- **Kind hues**: call / callback / db / gate / fail — hue=kind, intensity=importance.

Copy `assets/atlas-template.html` and replace the demo `VIEWS` / `STATES` / `F` / `ROOT` blocks. Keep all IDs, `JUMP_BASE` token logic, `__AW_CODE_MAP__`, copy-button wiring and error banner intact.

## Phase 3 — Build the HTML

Edit only the data blocks at the top of the script. Rules that keep it review-grade:

- Amounts/units with direction; quote rejection strings verbatim.
- Terminal state values shown literally (e.g. sentinel timestamps), not paraphrased.
- Notes are per-method plain language; summaries live in `plain`.
- Right-pane sections use the five-hue system (`data-kind`: plain/trigger/gate/state/fail → magenta/cyan/amber/green/red on label, dot, start border and ≤6% tint). Emphasize inside section bodies with `**关键点**` and 「引用句」/『引用句』 — they render in the section's hue while body text stays `--fg` for contrast (see `references/html-atlas-spec.md`).
- Method-card notes (大白话) get a frosted-glass treatment: translucent accent tint + `backdrop-filter: blur(6px)` + inner sheen, with a plain `var(--inset)` fallback when `color-mix`/backdrop-filter are unsupported. Section blocks get the same blur + a subtle inner highlight.
- Jump links: `jumpHref()` renders `http://127.0.0.1:PORT/jump?...` when the plugin serves the page (token `http://aw-jump-base.local` replaced server-side), else falls back to `idea://open?file=…&line=…`. Never depend on the `idea://` protocol being registered.
- Animations must use `animation-fill-mode: both` and must not gate the detail panel's visibility with opacity-only keyframes (JCEF freezes background animations at frame 0 → empty panel). The template already complies.

## Phase 4 — Verify before delivering

Run, in order:

1. `node scripts/verify_anchors.cjs <atlas.html> --root <project-root>` — checks every `L()` anchor's file exists and the method name appears within ±3 lines of the claimed line. Fix flagged anchors (call-site anchors are legitimate when `sig` says `调用 :行`).
2. Fragment contract validator (if `code-to-interactive-flow` skill exists): `node <skill>/scripts/validate_fragment.cjs <atlas.html>`.
3. Browser click-through: every step, every drill (including nested), every theme, back/forward, splitter drag/reset/keyboard, copy feedback, zero `undefined/NaN/[object]`, no overlapping cells, no page-level horizontal scroll at narrow width, screenshot at least once.

Report honestly what was verified and what wasn't (e.g., "narrow-width geometry pending because the preview panel was hidden").

## Phase 5 — IDE plugin: install once automatically, then just regenerate HTML

Default policy (user opted in): **install the plugin automatically on first use, skip it later.**

1. **First use** — copy `assets/idea-plugin/` into the target repo (e.g. `tools/review-atlas-plugin/`) and build once: `bash tools/review-atlas-plugin/build.sh` → `dist/review-atlas-bridge.jar`。构建脚本不写死安装路径：`IDEA_HOME` 优先，否则按 OS 常见位置（Program Files/JetBrains、Toolbox apps、/Applications、/opt）自动探测并按 `bin/build.txt` 时间取最新。Then (script paths below are relative to this skill directory):
   - `bash scripts/install_plugin.sh --check --jar <repo>/tools/review-atlas-plugin/dist/review-atlas-bridge.jar`
   - If `STATUS=INSTALLED_CURRENT` → nothing to do. Otherwise `bash scripts/install_plugin.sh --install --jar …` (copies the jar into `<IDE config>/plugins/`, backs up any previous jar, no admin, reversible via the printed `UNINSTALL=` command).
   - **Always close the loop on the restart**: `RESTART_REQUIRED=yes` → tell the user **重启 IDEA 一次**（运行中放入的 jar 只能在下次启动生效；图形安装向导无法脚本化，这是唯一可靠的无 UI 路线）; `RESTART_REQUIRED=no` → also tell the user once that the jar loads on the **next IDE start**.
   - Then guide the one-time UI check: right dock "Review Atlas" → 若配置路径不存在，工具窗会自动打开 `docs` 下最新的 `.html`；也可用工具栏「打开 HTML」或直接拖入；点一个锚点验证跳转，再点「关闭」确认回到空态（重启后保持关闭，直到下次打开）。
2. **Subsequent uses** — run only `bash scripts/install_plugin.sh --check --jar …`:
   - `INSTALLED_CURRENT` → **do not rebuild, do not reinstall**; only regenerate the HTML (the plugin re-reads the file on every request, so a 重载 button press is enough).
   - `INSTALLED_OUTDATED` → install the new jar + announce the restart; `MISSING_CONFIG/MISSING_JAR` → first-use flow.
   - If the user explicitly says "不要装/手动装", stop automating and print the manual steps below.
3. Manual fallback (permissions, remote IDE, or user preference): Settings → Plugins → ⚙ → Install Plugin from Disk → `dist/review-atlas-bridge.jar` → reload/restart.
4. Troubleshooting (details in `references/plugin-spec.md`): check `[Review Atlas]` lines in `idea.log` first — HTTP requests, page load, hook ping, intercepted navigation and jump requests each log one line. `idea://` unregistered is expected — served pages use local `/jump` links. `Script error. @:0` is an opaque injected-script error (ignored by the page). Empty right panel in JCEF only → an element wrapped in an opacity-only keyframe without `both`.

## Resources

- `assets/atlas-template.html` — data-driven fragment: sequence lanes + drill-down + method cards + chain + theme/history/splitter + copy + jump links + error banner. Replace demo data only.
- `assets/idea-plugin/` — generic plugin source (package `com.review.atlas`): local HTTP server (`/`, `/jump`), JCEF tool window with 3-layer jump fallback (JS bridge → request-layer intercept → server `/jump`), 8s status-driven watchdog with fallback panel, DnD, diagnostics logs, original tomoe-iris icon.
- `scripts/verify_anchors.cjs` — anchor audit for any atlas HTML.
- `references/html-atlas-spec.md` — data schema, interaction contract, JCEF pitfalls, acceptance checklist.
- `references/plugin-spec.md` — plugin architecture, endpoints, build pitfalls (JBR javac / argfile / cygpath / jcef module split), install & troubleshooting playbook.
