# Review Atlas · 代码走读交互图谱 + IDEA 内嵌查看器

把一段业务流程变成**可点击的 HTML 时序图谱**（左侧时序、右侧代码位置，大白话注释，精确 `file:line` 锚点），并配一个极薄的 **IntelliJ 插件**：工具窗内直接渲染这份 HTML，点「在 IDEA 中打开」即跳到编辑器对应行。

- HTML 是唯一数据源：零依赖、单文件 fragment、可离线打开、可在 IDE 内嵌、可在对话宿主内联
- 插件零业务内容：每次请求实时读盘，重新生成 HTML 后点「重载」即可，无需重装
- 跳转三层兜底：JS 桥 → CEF 请求拦截 → 本地 `/jump` 服务（外部浏览器同样可跳，不依赖 `idea://` 协议注册）

## 仓库内容

```
SKILL.md                      # 技能方法论（钉 file:line → 抽门禁/状态 → 套模板 → 校验 → 装插件）
references/
  html-atlas-spec.md          # HTML 数据契约、交互规范、JCEF 常见坑、验收清单
  plugin-spec.md              # 插件架构、构建/安装/排障手册
scripts/
  verify_anchors.cjs          # 行号对账：校验图谱里每个锚点的文件与行号
  install_plugin.sh           # 插件自动安装/检查/卸载提示（--check | --dry-run | --install）
assets/
  atlas-template.html         # 通用模板（只改顶部数据数组即可换业务）
  idea-plugin/                # 通用插件源码（包名 com.review.atlas）+ 免 Gradle 构建脚本 + 三勾玉图标
```

## 作为 Qoder 技能使用

1. 将本仓库放入技能目录（目录名建议 `build-review-atlas`）：

   ```bash
   # Linux / macOS
   ln -s /path/to/review-atlas ~/.qoder/skills/build-review-atlas
   # Windows：直接复制整个目录到 %USERPROFILE%\.qoder\skills\build-review-atlas
   ```

2. 新会话中直接说：**「给 XX 模块做一份 review atlas / 时序图 + 代码位置映射」**。
   技能会按流程：把每个环节钉到 `file:line`、抽取门禁与状态变化、套模板生成 HTML、跑 `verify_anchors.cjs` 对账、构建并安装插件。

策略：**首次使用自动安装插件**（拷贝 jar 进 IDE 插件目录，免管理员、可回滚），IDE 正在运行时只需重启一次；**后续使用只重新生成 HTML**，插件实时读盘，无需重复安装。

## 插件单独使用

构建（无需 Gradle）：

```bash
bash assets/idea-plugin/build.sh
# 产物：assets/idea-plugin/dist/review-atlas-bridge.jar
# 说明：用 IDEA 自带的 JBR javac 编译（IDEA_HOME 可选，自动探测 Program Files/Toolbox//Applications//opt），
#      打包用 PATH 上的任意 JDK `jar`
```

安装：

```bash
bash scripts/install_plugin.sh --check   --jar assets/idea-plugin/dist/review-atlas-bridge.jar
bash scripts/install_plugin.sh --install --jar assets/idea-plugin/dist/review-atlas-bridge.jar
```

- 输出含 `TARGET` / `RUNNING` / `RESTART_REQUIRED` / 现成的卸载命令；重复安装幂等，旧版自动备份 `.bak.时间戳`
- 手动安装也行：Settings → Plugins → ⚙ → Install Plugin from Disk

使用：

- 右侧工具窗 **Review Atlas**；默认自动打开项目 `docs` 下最新的 `.html`（配置路径缺失时的兜底）
- 工具栏：打开 HTML / 重载 / **关闭**（回到空态并持久化，重启后保持关闭）/ 外部浏览器
- 拖拽任意 `.html` 进窗口即切换；选中过的文件会被记住

## HTML 图谱特性

- 左时序右代码：泳道配色区分调用类型（跨服务/回调/落库/判断/失败），浓度区分重要性（核心动作/闸门/入口）
- 右侧：五色说明块（大白话/触发/判断/状态/失败）+ 调用顺序 chips + 方法卡（大白话毛玻璃注释、行号、文件、跳转、复制映射 JSON）
- 下钻子流程、面包屑 + 前进/后退（Alt+←/→）、主题三档（跟随系统/浅色/深色）、可拖拽分栏
- 正文内联高亮：`**关键点**`、「引用句」/『引用句』自动染成所在块的颜色
- 严格 fragment 契约：零网络、单根节点、宿主变量可覆盖、深浅主题文字对比 ≥ 4.5:1
- 内置错误横幅：同源脚本异常直接显示在页面顶部；插件注入脚本全部 try/catch（不产生 `Script error. @:0`）

## 校验图谱

```bash
node scripts/verify_anchors.cjs path/to/atlas.html --root /path/to/project
# 输出 anchors/ok/issues 统计；调用点锚（sig 注明）自动降级为 WARN
```

## 环境要求

- IntelliJ IDEA（JCEF 默认启用；2026.3 的 JCEF 独立模块由插件内 optional descriptor 处理）
- 构建机：Windows 下用 Git Bash；PATH 上有任意 JDK 的 `jar`
- 安装脚本自动定位 IDE 配置目录（Windows `%APPDATA%/JetBrains`、macOS `~/Library/Application Support/JetBrains`、Linux `$XDG_CONFIG_HOME/JetBrains`；可用 `IDEA_CONFIG_DIR` 覆盖）

## 故障排查

| 现象 | 先看 |
| --- | --- |
| 工具窗白屏 | `idea.log` 里 `JBCefApp/StartupTest`：JCEF 内核不可用 → 用「外部浏览器」降级 |
| 点击不跳转 | `idea.log` 的 `[Review Atlas]` 系列：HTTP 请求 / 页面加载 / 桥存活 ping / 导航拦截 / 跳转请求 |
| 右栏整块空白（仅 JCEF） | 是否有子元素用了不带 `both` 的透明度动画（JCEF 会把动画冻结在首帧） |
| `Script error. @:0` | 注入脚本的跨源不透明异常；保持注入 try/catch，页面只展示带文件名的同源异常 |
| 提示页小字看不清 | 提示页必须自绘明暗背景与 14px 字号，不得依赖 IDE 面板背景 |
