package com.chan.shellpilot.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chan.shellpilot.util.SpLog
import kotlinx.coroutines.delay

private val ESC = 0x1B.toByte()
private val DEL = 0x7F.toByte()

private fun escSeq(s: String): ByteArray =
    byteArrayOf(ESC) + s.toByteArray(Charsets.US_ASCII)

/**
 * 真终端：点按终端直接输入（隐藏输入框），长按选择复制，
 * ANSI 颜色/清屏/光标，底部特殊键盘行。黑色背景、等宽字体。
 *
 * 渲染用 LazyColumn 按行显示：打字时只有最后一行重排，
 * 不再全量重排 500 行——解决输入延迟。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    title: String,
    bridge: TerminalBridge?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 行快照由 TerminalBridge 在 IO 线程构建好后发布，UI 只做增量重排
    val lines by bridge?.lines?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val connected by bridge?.connected?.collectAsState() ?: remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    var stickToBottom by remember { mutableStateOf(true) }
    var ctrlSticky by remember { mutableStateOf(false) }
    var altSticky by remember { mutableStateOf(false) }

    // 块状光标 500ms 闪烁：只重组光标所在行，不重建文本
    var cursorVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            cursorVisible = !cursorVisible
        }
    }

    fun sendBytes(b: ByteArray) = bridge?.sendBytes(b) ?: Unit
    fun sendText(t: String) = bridge?.sendText(t) ?: Unit

    fun focusKeyboard() {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    fun handleChar(c: Char) {
        when {
            c == '\n' -> sendText("\r")
            ctrlSticky -> {
                ctrlSticky = false
                val code = c.uppercaseChar() - 'A' + 1
                if (code in 1..26) sendBytes(byteArrayOf(code.toByte()))
                else sendText(c.toString())
            }
            altSticky -> {
                altSticky = false
                sendBytes(byteArrayOf(ESC))
                sendText(c.toString())
            }
            else -> sendText(c.toString())
        }
    }

    /**
     * 粘贴（修粘贴乱码）：
     * 多字符增量不再逐字符拆成 N 次 sendText（之前每个字符起独立协程并发写，
     * 写入交错导致字符顺序错乱）。整段经 sendPaste 单次原子写入；
     * 远端若启用了 bracketed paste（bash 默认开），用 ESC[200~...ESC[201~
     * 包裹，shell 把整段当作粘贴插入、不逐行执行，特殊字符也不会截断。
     */
    fun handlePaste(text: String) {
        if (text.isEmpty()) return
        // 清掉文本里自带的粘贴标记，避免提前闭合包裹
        val clean = text
            .replace("\u001B[201~", "")
            .replace("\u001B[200~", "")
        val bracketed = bridge?.isBracketedPasteEnabled() == true
        val payload = if (bracketed) "\u001B[200~$clean\u001B[201~" else clean
        SpLog.d("TerminalInput", "paste ${clean.length} chars, bracketed=$bracketed")
        bridge?.sendPaste(payload)
    }

    // 跟踪用户是否在底部：在底部才自动跟随新输出
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) 0 else {
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                total - 1 - lastVisible
            }
        }.collect { distanceFromEnd ->
            stickToBottom = distanceFromEnd < 3
        }
    }
    // 新输出时，若在底部则滚到底
    LaunchedEffect(lines.size) {
        if (stickToBottom && lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
    }
    // 进终端自动弹键盘
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { focusKeyboard() }) {
                        Icon(Icons.Filled.Keyboard, contentDescription = "键盘", tint = Color.Gray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black)
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (bridge == null) {
                    Text(
                        "连接中…",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    SelectionContainer(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { focusKeyboard() })
                                },
                        ) {
                            items(
                                count = lines.size,
                                key = { idx -> lines[idx].id },
                            ) { idx ->
                                val line = lines[idx]
                                TerminalLineItem(
                                    line = line,
                                    // 只有光标行才订阅闪烁状态，其他行不受影响
                                    cursorVisible = if (line.isCursorRow) cursorVisible else false,
                                )
                            }
                        }
                    }
                }
                // 隐藏输入框：承载软键盘输入，字符直发 shell（pty 回显）。
                // 独立 composable，避免每次按键重组整个终端界面。
                HiddenInputField(
                    focusRequester = focusRequester,
                    onChar = ::handleChar,
                    onPaste = ::handlePaste,
                    onDelete = { sendBytes(byteArrayOf(DEL)) },
                    onDone = { sendText("\r") },
                )
            }
            if (bridge != null && !connected) {
                Text(
                    "连接已断开",
                    color = Color(0xFFFF6B6B),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            SpecialKeyRow(
                ctrlSticky = ctrlSticky,
                altSticky = altSticky,
                onToggleCtrl = {
                    ctrlSticky = !ctrlSticky
                    if (ctrlSticky) altSticky = false
                },
                onToggleAlt = {
                    altSticky = !altSticky
                    if (altSticky) ctrlSticky = false
                },
                onBytes = ::sendBytes,
            )
        }
    }
}

/**
 * 单行终端文本 + 光标（如果光标在该行）。
 * LazyColumn 按行复用：打字只重组最后一行。
 */
@Composable
private fun TerminalLineItem(
    line: LineSnapshot,
    cursorVisible: Boolean,
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = line.text,
            color = Color(0xFFE8E8E8),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth(),
            onTextLayout = { layoutResult = it },
        )
        if (line.isCursorRow) {
            CursorBlock(
                layoutResult = layoutResult,
                offset = line.cursorCol.coerceIn(0, line.text.length),
                textLength = line.text.length,
                visible = cursorVisible,
                density = density,
            )
        }
    }
}

/** 块状光标：按字符 advance 精确定位，主题紫色，500ms 闪烁。 */
@Composable
private fun CursorBlock(
    layoutResult: TextLayoutResult?,
    offset: Int,
    textLength: Int,
    visible: Boolean,
    density: Density,
) {
    val lr = layoutResult ?: return
    val off = offset.coerceIn(0, textLength)
    val rect = runCatching { lr.getCursorRect(off) }.getOrNull() ?: return
    // 块宽：取相邻字符的 advance（等宽字体下恒定）；取不到时按字号估算
    val advancePx = runCatching {
        when {
            off < textLength -> lr.getCursorRect(off + 1).left - rect.left
            off > 0 -> rect.left - lr.getCursorRect(off - 1).left
            else -> with(density) { 13.sp.toPx() * 0.6f }
        }
    }.getOrDefault(with(density) { 13.sp.toPx() * 0.6f }).coerceAtLeast(2f)
    val lineHpx = rect.height.takeIf { it > 0f }
        ?: with(density) { 18.sp.toPx() }
    val xDp: androidx.compose.ui.unit.Dp
    val yDp: androidx.compose.ui.unit.Dp
    val wDp: androidx.compose.ui.unit.Dp
    val hDp: androidx.compose.ui.unit.Dp
    with(density) {
        xDp = rect.left.toDp()
        yDp = rect.top.toDp()
        wDp = advancePx.toDp()
        hDp = lineHpx.toDp()
    }
    Box(
        modifier = Modifier
            .offset(x = xDp, y = yDp)
            .size(width = wDp, height = hDp)
            .background(Color(0xFFBB86FC))
            .alpha(if (visible) 1f else 0f)
    )
}

/**
 * 隐藏输入框（独立 composable）：承载软键盘输入。
 *
 * 删除键双通道捕获（修"删不掉"）：
 * 1. onValueChange 哨兵空格：多数软键盘退格走 deleteSurroundingText，
 *    删掉哨兵空格即触发；
 * 2. onPreviewKeyEvent 拦截 Key.Backspace：部分输入法（尤其中文键盘）
 *    的退格以 KeyEvent 形式下发，不经过 onValueChange。
 * 两条路径互斥（KeyEvent 被消费后文本不变，不会再触发 onValueChange），
 * 不会重复发送 DEL。
 *
 * 粘贴（修粘贴乱码）：多字符增量走 onPaste 整段原子发送，不再逐字符
 * 拆成 N 次 sendText（之前每个字符起独立协程并发写导致交错乱码）。
 * diff 用公共前缀算法，兼容输入法各种提交方式。
 */
@Composable
private fun HiddenInputField(
    focusRequester: FocusRequester,
    onChar: (Char) -> Unit,
    onPaste: (String) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    var hidden by remember { mutableStateOf(" ") }
    BasicTextField(
        value = hidden,
        onValueChange = { new ->
            val old = hidden
            val common = old.commonPrefixWith(new).length
            val removed = old.length - common
            val added = new.substring(common)
            if (removed > 0) {
                SpLog.d("TerminalInput", "delete x$removed via onValueChange")
                repeat(removed) { onDelete() }
            }
            if (added.isNotEmpty()) {
                if (added.length == 1) onChar(added[0])
                else onPaste(added)
            }
            if (new != " ") hidden = " "
        },
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Backspace && event.type == KeyEventType.KeyDown) {
                    SpLog.d("TerminalInput", "delete via KeyEvent")
                    onDelete()
                    true
                } else {
                    false
                }
            },
        keyboardOptions = KeyboardOptions(
            autoCorrect = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = { onDone() }
        ),
        cursorBrush = SolidColor(Color.Transparent),
    )
}

@Composable
private fun SpecialKeyRow(
    ctrlSticky: Boolean,
    altSticky: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onBytes: (ByteArray) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TKey("ESC") { onBytes(byteArrayOf(ESC)) }
        TKey("TAB") { onBytes(byteArrayOf(0x09.toByte())) }
        TToggle("CTRL", ctrlSticky, onToggleCtrl)
        TToggle("ALT", altSticky, onToggleAlt)
        TKey("↑") { onBytes(escSeq("[A")) }
        TKey("↓") { onBytes(escSeq("[B")) }
        TKey("←") { onBytes(escSeq("[D")) }
        TKey("→") { onBytes(escSeq("[C")) }
        TKey("HOME") { onBytes(escSeq("[H")) }
        TKey("END") { onBytes(escSeq("[F")) }
        TKey("PGUP") { onBytes(escSeq("[5~")) }
        TKey("PGDN") { onBytes(escSeq("[6~")) }
    }
}

@Composable
private fun TKey(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Color(0xFFBB86FC),
        )
    }
}

@Composable
private fun TToggle(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (active) Color(0xFFBB86FC) else Color.Transparent,
        ),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (active) Color.Black else Color(0xFFBB86FC),
        )
    }
}
