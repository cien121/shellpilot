package com.chan.shellpilot.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * 轻量 ANSI 终端解析器（自研，不依赖 termlib）。
 *
 * 处理：
 * - SGR 颜色（标准/高亮/256 色/真彩）、粗体、反显
 * - 光标移动（CUU/CUD/CUF/CUB/CUP/CHA/VPA/CNL/CPL）
 * - 擦除（ED/EL）、插行删行（IL/DL）、插删字符（ICH/DCH/ECH）、滚屏（SU/SD）
 * - 清屏、保存/恢复光标
 * - 吞掉 bracketed paste（[?2004h/l）、OSC 标题、DCS 等，避免 "?[?2004h" 乱码
 *
 * 行模型：每行 = 文本 + 逐字符样式，支持 \r 覆盖（进度条）、\b 回退。
 * 全屏应用（vim/htop）只能近似渲染，后续可换 cell-grid 实现。
 */

private val DEFAULT_FG = Color(0xFF33FF66)
private val NO_BG = Color.Transparent

private data class CellStyle(val fg: Color, val bg: Color, val bold: Boolean)

private class StyledLine(val id: Long) {
    val text = StringBuilder()
    val styles = ArrayList<CellStyle>()
}

/**
 * 行级快照：给 LazyColumn 按行渲染用。
 * [id] 是行的稳定标识（LazyColumn 的 key），内容变化时同 id 复用。
 */
data class LineSnapshot(
    val id: Long,
    val text: AnnotatedString,
    val isCursorRow: Boolean,
    val cursorCol: Int,
)

private fun xtermColor(n: Int): Color {
    val std = longArrayOf(
        0x000000, 0xAA0000, 0x00AA00, 0xAA5500,
        0x0000AA, 0xAA00AA, 0x00AAAA, 0xAAAAAA,
    )
    val bright = longArrayOf(
        0x555555, 0xFF5555, 0x55FF55, 0xFFFF55,
        0x5555FF, 0xFF55FF, 0x55FFFF, 0xFFFFFF,
    )
    val rgb: Long = when {
        n < 0 -> return DEFAULT_FG
        n < 8 -> std[n]
        n < 16 -> bright[n - 8]
        n < 232 -> {
            val i = n - 16
            val r = (i / 36) % 6
            val g = (i / 6) % 6
            val b = i % 6
            fun v(x: Int) = if (x == 0) 0 else 55 + 40 * x
            (v(r).toLong() shl 16) or (v(g).toLong() shl 8) or v(b).toLong()
        }
        else -> {
            val v = (8 + 10 * (n - 232)).toLong()
            (v shl 16) or (v shl 8) or v
        }
    }
    return Color(0xFF000000L or rgb)
}

private fun rgbColor(r: Int, g: Int, b: Int): Color {
    val v = (r.coerceIn(0, 255).toLong() shl 16) or
        (g.coerceIn(0, 255).toLong() shl 8) or
        b.coerceIn(0, 255).toLong()
    return Color(0xFF000000L or v)
}

private enum class PState { GROUND, ESC, ESC_SKIP_ONE, CSI, OSC, OSC_ESC, ST_SWALLOW, ST_ESC }

class AnsiTerminal(private val maxLines: Int = 2000, private val cols: Int = 80) {

    private val lines = ArrayList<StyledLine>()
    private var row = 0
    private var col = 0
    /** 自动换行：写满一行后下一次 putChar 先换行（与 pty 宽度 80 对齐）。 */
    private var wrapPending = false

    private var fg: Color = DEFAULT_FG
    private var bg: Color = NO_BG
    private var bold = false

    private var sRow = 0
    private var sCol = 0
    private var sFg: Color = DEFAULT_FG
    private var sBg: Color = NO_BG
    private var sBold = false

    private var state = PState.GROUND
    private val csiParams = StringBuilder()
    private var csiPrivate = false

    /**
     * 远端是否启用了 bracketed paste（ESC[?2004h 开 / ESC[?2004l 关）。
     * 粘贴时若启用，用 ESC[200~...ESC[201~ 包裹文本：
     * shell 把整段当作粘贴插入、不逐行执行，也不会被特殊字符截断。
     * @Volatile：IO 线程（解析）写、主线程（发送粘贴）读。
     */
    @Volatile
    var bracketedPasteEnabled: Boolean = false
        private set

    /** 行 ID 计数器：每创建一个 StyledLine 分配一个，保证 LazyColumn key 稳定。 */
    private var nextLineId = 0L
    private fun newStyledLine() = StyledLine(nextLineId++)

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private fun curStyle() = CellStyle(fg, bg, bold)
    private fun defaultCell() = CellStyle(DEFAULT_FG, NO_BG, false)

    /** 线程安全：解析与快照共用一把锁。 */
    @Synchronized
    fun processBytes(data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val bb = ByteBuffer.wrap(data, off, len)
        val cb = CharBuffer.allocate(len)
        decoder.decode(bb, cb, false)
        cb.flip()
        while (cb.hasRemaining()) processChar(cb.get())
    }

    @Synchronized
    fun snapshot(): AnnotatedString = snapshotWithCursor(Int.MAX_VALUE).first

    /**
     * 构建显示快照（只取末尾 [maxDisplayLines] 行，控制 Compose 布局开销），
     * 并返回光标在快照文本中的字符偏移。单次同步调用，保证文本与偏移一致。
     */
    @Synchronized
    fun snapshotWithCursor(maxDisplayLines: Int = 500): Pair<AnnotatedString, Int> {
        val total = lines.size
        val start = maxOf(0, total - maxDisplayLines)
        val text = buildAnnotatedString {
            for (li in start until total) {
                val line = lines[li]
                var i = 0
                val t = line.text
                while (i < t.length) {
                    val st = line.styles[i]
                    var j = i + 1
                    while (j < t.length && line.styles[j] == st) j++
                    pushStyle(
                        SpanStyle(
                            color = st.fg,
                            background = st.bg,
                            fontWeight = if (st.bold) FontWeight.Bold else null,
                        )
                    )
                    append(t.substring(i, j))
                    pop()
                    i = j
                }
                if (li != total - 1) append('\n')
            }
        }
        // 光标在快照中的扁平偏移（行内换行符各占 1）
        var off = 0
        val endRow = minOf(row, total - 1)
        for (li in start until maxOf(start, endRow)) {
            off += lines[li].text.length + 1
        }
        if (total > 0 && endRow >= start) {
            off += col.coerceIn(0, lines[endRow].text.length)
        }
        return text to off
    }

    /**
     * 行级快照（给 LazyColumn 按行渲染）：只取末尾 [maxDisplayLines] 行，
     * 每行独立 AnnotatedString。打字时只有最后一行变化，LazyColumn 仅重排该行，
     * 不再全量重排 500 行——解决输入延迟。
     *
     * 空行用零宽空格占位，保证每行都有行高。
     * 注意：AnnotatedString 是纯数据类，可在后台线程构建。
     */
    @Synchronized
    fun snapshotLines(maxDisplayLines: Int = 500): List<LineSnapshot> {
        val total = lines.size
        if (total == 0) return emptyList()
        val start = maxOf(0, total - maxDisplayLines)
        val cursorRow = row.coerceIn(0, total - 1)
        return ArrayList<LineSnapshot>(total - start).apply {
            for (li in start until total) {
                val line = lines[li]
                val text = buildAnnotatedString {
                    val t = line.text
                    if (t.isEmpty()) {
                        // 零宽空格：空行保持行高，不可见
                        append("\u200B")
                    } else {
                        var i = 0
                        while (i < t.length) {
                            val st = line.styles[i]
                            var j = i + 1
                            while (j < t.length && line.styles[j] == st) j++
                            pushStyle(
                                SpanStyle(
                                    color = st.fg,
                                    background = st.bg,
                                    fontWeight = if (st.bold) FontWeight.Bold else null,
                                )
                            )
                            append(t.substring(i, j))
                            pop()
                            i = j
                        }
                    }
                }
                add(
                    LineSnapshot(
                        id = line.id,
                        text = text,
                        isCursorRow = li == cursorRow,
                        cursorCol = if (li == cursorRow) col else 0,
                    )
                )
            }
        }
    }

    // ---------- 光标与行操作 ----------

    private fun ensureRow(r: Int) {
        while (lines.size <= r) lines.add(newStyledLine())
    }

    private fun trim() {
        while (lines.size > maxLines) {
            lines.removeAt(0)
            if (row > 0) row--
        }
    }

    private fun newLine() {
        row++
        col = 0
        wrapPending = false
        ensureRow(row)
        trim()
    }

    private fun putChar(c: Char) {
        // 自动换行（修粘贴长命令与 curl 进度条重叠）：
        // pty 宽度 80，远端按 80 列排版；解析器之前无换行逻辑，
        // 超长行全挤在一个逻辑行里，curl 的 \r 回车覆盖只重写前 80 列，
        // 行尾残留造成显示重叠。这里写满后换行，与远端对齐。
        if (wrapPending) {
            wrapPending = false
            newLine()
        }
        ensureRow(row)
        val line = lines[row]
        if (col < line.text.length) {
            line.text.setCharAt(col, c)
            line.styles[col] = curStyle()
        } else {
            while (line.text.length < col) {
                line.text.append(' ')
                line.styles.add(defaultCell())
            }
            line.text.append(c)
            line.styles.add(curStyle())
        }
        col++
        if (col >= cols) {
            // 到达行尾：下一次写字符时换行（标准终端 wrap 行为）。
            // 注意 \r 会清掉 wrapPending（回车后从行首覆盖，不换行）。
            wrapPending = true
            col = cols - 1
        }
    }

    private var sWrapPending = false

    private fun saveCursor() {
        sRow = row; sCol = col; sFg = fg; sBg = bg; sBold = bold
        sWrapPending = wrapPending
    }

    private fun restoreCursor() {
        row = sRow; col = sCol; fg = sFg; bg = sBg; bold = sBold
        wrapPending = sWrapPending
        ensureRow(row)
    }

    private fun resetStyle() {
        fg = DEFAULT_FG; bg = NO_BG; bold = false
    }

    private fun resetScreen() {
        lines.clear()
        row = 0; col = 0
        wrapPending = false
        resetStyle()
    }

    private fun eraseLineTail() {
        if (row >= lines.size) return
        val line = lines[row]
        if (col < line.text.length) {
            line.text.delete(col, line.text.length)
            line.styles.subList(col, line.styles.size).clear()
        }
    }

    private fun eraseLineHead() {
        if (row >= lines.size) return
        val line = lines[row]
        val end = col.coerceAtMost(line.text.length)
        repeat(end) {
            line.text.setCharAt(it, ' ')
            line.styles[it] = defaultCell()
        }
    }

    private fun eraseWholeLine() {
        if (row >= lines.size) return
        lines[row] = newStyledLine()
    }

    private fun insertSpaces(n: Int) {
        ensureRow(row)
        val line = lines[row]
        while (line.text.length < col) {
            line.text.append(' ')
            line.styles.add(defaultCell())
        }
        repeat(n) {
            line.text.insert(col, ' ')
            line.styles.add(col, curStyle())
        }
    }

    private fun deleteChars(n: Int) {
        if (row >= lines.size) return
        val line = lines[row]
        repeat(n) {
            if (col < line.text.length) {
                line.text.deleteCharAt(col)
                line.styles.removeAt(col)
            }
        }
    }

    private fun eraseChars(n: Int) {
        if (row >= lines.size) return
        val line = lines[row]
        for (i in 0 until n) {
            val c = col + i
            if (c < line.text.length) {
                line.text.setCharAt(c, ' ')
                line.styles[c] = defaultCell()
            }
        }
    }

    // ---------- SGR ----------

    private fun sgr(params: IntArray) {
        if (params.isEmpty()) {
            resetStyle()
            return
        }
        var i = 0
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> resetStyle()
                1 -> bold = true
                22 -> bold = false
                7 -> {
                    val t = fg
                    fg = if (bg == NO_BG) Color.Black else bg
                    bg = t
                }
                in 30..37 -> fg = xtermColor(p - 30)
                in 90..97 -> fg = xtermColor(p - 90 + 8)
                in 40..47 -> bg = xtermColor(p - 40)
                in 100..107 -> bg = xtermColor(p - 100 + 8)
                39 -> fg = DEFAULT_FG
                49 -> bg = NO_BG
                38, 48 -> {
                    val isFg = p == 38
                    fun set(c: Color) { if (isFg) fg = c else bg = c }
                    if (i + 2 < params.size && params[i + 1] == 5) {
                        set(xtermColor(params[i + 2]))
                        i += 2
                    } else if (i + 4 < params.size && params[i + 1] == 2) {
                        set(rgbColor(params[i + 2], params[i + 3], params[i + 4]))
                        i += 4
                    }
                }
                // 2 dim / 3 italic / 4 underline / 9 strike：暂忽略
            }
            i++
        }
    }

    // ---------- CSI 分发 ----------

    private fun dispatchCsi(final: Char) {
        // 显式光标移动取消待换行（标准终端行为）
        wrapPending = false
        if (csiPrivate) {
            // 跟踪 bracketed paste 开关，其他 ? 私有序列一律吞掉
            if (final == 'h' || final == 'l') {
                val nums = csiParams.toString().split(';').mapNotNull { it.toIntOrNull() }
                if (2004 in nums) bracketedPasteEnabled = (final == 'h')
            }
            return
        }
        val p = csiParams.toString().split(';').map { it.toIntOrNull() ?: 0 }.toIntArray()
        fun n(i: Int) = (if (i < p.size && p[i] != 0) p[i] else 1).coerceAtLeast(1)
        fun v0() = p.getOrElse(0) { 0 }
        when (final) {
            'm' -> sgr(p)
            'A' -> row = maxOf(0, row - n(0))
            'B' -> { row += n(0); ensureRow(row); trim() }
            'C' -> col += n(0)
            'D' -> col = maxOf(0, col - n(0))
            'E' -> { row += n(0); col = 0; ensureRow(row); trim() }
            'F' -> { row = maxOf(0, row - n(0)); col = 0 }
            'G' -> col = maxOf(0, n(0) - 1)
            'd' -> { row = maxOf(0, n(0) - 1); ensureRow(row) }
            'H', 'f' -> {
                row = maxOf(0, (if (p.isNotEmpty()) p[0] else 1) - 1)
                col = maxOf(0, (if (p.size > 1) p[1] else 1) - 1)
                ensureRow(row)
            }
            'J' -> when (v0()) {
                1 -> {
                    repeat(row.coerceAtMost(lines.size)) { if (lines.isNotEmpty()) lines.removeAt(0) }
                    row = 0
                    eraseLineHead()
                }
                2, 3 -> resetScreen()
                else -> {
                    eraseLineTail()
                    while (lines.size > row + 1) lines.removeAt(lines.lastIndex)
                }
            }
            'K' -> when (v0()) {
                1 -> eraseLineHead()
                2 -> eraseWholeLine()
                else -> eraseLineTail()
            }
            'L' -> { repeat(n(0)) { lines.add(row.coerceAtMost(lines.size), newStyledLine()) }; trim() }
            'M' -> repeat(n(0)) { if (row < lines.size) lines.removeAt(row) }
            '@' -> insertSpaces(n(0))
            'P' -> deleteChars(n(0))
            'X' -> eraseChars(n(0))
            'S' -> { repeat(n(0)) { if (row < lines.size) lines.removeAt(row) }; trim() }
            'T' -> { repeat(n(0)) { lines.add(row.coerceAtMost(lines.size), newStyledLine()) }; trim() }
            's' -> saveCursor()
            'u' -> restoreCursor()
            // 'r' 滚屏区域 / 'c' / 'n' / 't' / 'h' / 'l'：忽略
        }
    }

    // ---------- 主状态机 ----------

    private fun processChar(c: Char) {
        when (state) {
            PState.GROUND -> when (c) {
                '\u001B' -> state = PState.ESC
                '\u0007' -> {}
                '\b' -> {
                    wrapPending = false
                    if (col > 0) col--
                }
                '\t' -> {
                    wrapPending = false
                    col = ((col + 8) / 8) * 8
                }
                '\n', '\u000B', '\u000C' -> newLine()
                '\r' -> {
                    // 回车：清除待换行标记（回车后从行首覆盖，不换行）
                    col = 0
                    wrapPending = false
                }
                '\u0000' -> {}
                else -> if (c >= ' ' && c != '') putChar(c)
            }
            PState.ESC -> when (c) {
                '[' -> { state = PState.CSI; csiParams.clear(); csiPrivate = false }
                ']' -> state = PState.OSC
                'P', 'X', '^', '_' -> state = PState.ST_SWALLOW
                '7' -> { saveCursor(); state = PState.GROUND }
                '8' -> { restoreCursor(); state = PState.GROUND }
                'M' -> { if (row > 0) row--; state = PState.GROUND }
                'E' -> { newLine(); state = PState.GROUND }
                'D' -> { row++; ensureRow(row); trim(); state = PState.GROUND }
                'c' -> { resetScreen(); state = PState.GROUND }
                '(', ')', '#' -> state = PState.ESC_SKIP_ONE
                else -> state = PState.GROUND
            }
            PState.ESC_SKIP_ONE -> state = PState.GROUND
            PState.CSI -> when {
                c in '0'..'9' || c == ';' -> csiParams.append(c)
                c == '?' -> csiPrivate = true
                c in ' '..'/' -> {}
                c in '@'..'~' -> { dispatchCsi(c); state = PState.GROUND }
                else -> state = PState.GROUND
            }
            PState.OSC -> when (c) {
                '\u0007' -> state = PState.GROUND
                '\u001B' -> state = PState.OSC_ESC
                else -> {}
            }
            PState.OSC_ESC -> state = if (c == '\\') PState.GROUND else PState.OSC
            PState.ST_SWALLOW -> when (c) {
                '\u0007' -> state = PState.GROUND
                '\u001B' -> state = PState.ST_ESC
                else -> {}
            }
            PState.ST_ESC -> state = if (c == '\\') PState.GROUND else PState.ST_SWALLOW
        }
    }
}
