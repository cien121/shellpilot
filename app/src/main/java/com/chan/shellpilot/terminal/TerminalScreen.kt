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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.AnnotatedString
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    title: String,
    bridge: TerminalBridge?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val version by bridge?.version?.collectAsState() ?: remember { mutableStateOf(0L) }
    val connected by bridge?.connected?.collectAsState() ?: remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    var stickToBottom by remember { mutableStateOf(true) }
    var ctrlSticky by remember { mutableStateOf(false) }
    var altSticky by remember { mutableStateOf(false) }

    val (snapshot, cursorOffset) = remember(version) {
        bridge?.snapshotWithCursor() ?: (AnnotatedString("") to 0)
    }
    // 块状光标 500ms 闪烁：只切换 overlay 透明度，不重建文本
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

    // 跟踪用户是否滚到底：到底才自动跟随新输出
    LaunchedEffect(Unit) {
        snapshotFlow { scrollState.value to scrollState.maxValue }
            .collect { (v, max) -> stickToBottom = max - v < 80 }
    }
    LaunchedEffect(version) {
        if (stickToBottom) scrollState.scrollTo(scrollState.maxValue)
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
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { focusKeyboard() })
                    }
            ) {
                if (bridge == null) {
                    Text(
                        "连接中…",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    TerminalTextWithCursor(
                        snapshot = snapshot,
                        cursorOffset = cursorOffset,
                        cursorVisible = cursorVisible,
                        scrollState = scrollState,
                    )
                }
                // 隐藏输入框：承载软键盘输入，字符直发 shell（pty 回显）。
                // 独立 composable，避免每次按键重组整个终端界面。
                HiddenInputField(
                    focusRequester = focusRequester,
                    onChar = ::handleChar,
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
 * 终端文本 + 块状光标覆盖层。
 * 光标用 TextLayoutResult 定位，闪烁只切换透明度，不触发文本重建。
 */
@Composable
private fun TerminalTextWithCursor(
    snapshot: AnnotatedString,
    cursorOffset: Int,
    cursorVisible: Boolean,
    scrollState: ScrollState,
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    SelectionContainer(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(8.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = snapshot,
                color = Color(0xFFE8E8E8),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth(),
                onTextLayout = { layoutResult = it },
            )
            CursorBlock(
                layoutResult = layoutResult,
                offset = cursorOffset,
                textLength = snapshot.length,
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
 */
@Composable
private fun HiddenInputField(
    focusRequester: FocusRequester,
    onChar: (Char) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    var hidden by remember { mutableStateOf(" ") }
    BasicTextField(
        value = hidden,
        onValueChange = { new ->
            val old = hidden
            if (new.length > old.length) {
                for (c in new.substring(old.length)) onChar(c)
            } else if (new.length < old.length) {
                val n = old.length - new.length
                SpLog.d("TerminalInput", "delete x$n via onValueChange")
                repeat(n) { onDelete() }
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
