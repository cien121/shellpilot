# ShellPilot Milestone 3.5 — 修粘贴显示重叠 + 特殊键盘加粘贴键

## 用户反馈（2026-10-08，构建 #25）
粘贴长命令后终端显示重叠错乱：粘贴的 `bash -c "$(curl ...)"` 与 curl 进度输出重叠在一起。

## 根因
`AnsiTerminal` 没有自动换行逻辑。pty 按 80 列分配（`allocatePTY("xterm-256color", 80, 24, ...)`），
远端按 80 列排版（换行是终端的职责，pty 只管发字节流）。
超长粘贴行全挤在一个逻辑行里（比如 200 字符一行），curl 进度条的 `\r` 回车覆盖
只重写前 80 列，行尾残留造成显示重叠。

Python 复刻验证：100 字符无换行 → 1 个逻辑行；加换行后 → 80+20 两个逻辑行，
`\r` 只影响当前行，不污染前面的换行内容。

## 修复（`terminal/AnsiTerminal.kt`）
1. 新增 `cols` 参数（默认 80，与 pty 对齐）+ `wrapPending` 标记。
2. `putChar`：写满 80 列后下一次写字符时自动换行（标准终端 wrap 行为）。
3. `\r`、`\b`、`\t`、CSI 光标移动、`resetScreen` 清除 `wrapPending`（回车后从行首覆盖，不换行）。
4. `saveCursor`/`restoreCursor` 保存/恢复 `wrapPending`。

## 附加需求：特殊键盘加「粘贴」键
`terminal/TerminalScreen.kt`：
1. `SpecialKeyRow` 新增 `onPaste` 参数，ALT 后面加「粘贴」键。
2. 用 `LocalClipboardManager` 读剪贴板，走已有 `handlePaste` 逻辑
  （bracketed paste 包裹 + 整段原子发送）。

## 构建
- versionCode 8 / versionName 0.3.4
- 待构建 #26

## 用户验证要点
1. 粘贴一个超长命令并执行，命令显示换行正常，curl 进度条不重叠。
2. 点特殊键盘行的「粘贴」，剪贴板内容直接进终端。
