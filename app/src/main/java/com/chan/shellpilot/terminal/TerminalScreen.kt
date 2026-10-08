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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    // 哨兵空格：保证退格键总有字符可删，从而能被 onValueChange 捕获
    var hidden by remember { mutableStateOf(" ") }

    val snapshot: AnnotatedString = remember(version) {
        bridge?.snapshot() ?: AnnotatedString("")
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
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = snapshot,
                            color = Color(0xFFE8E8E8),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
                // 隐藏输入框：承载软键盘输入，字符直发 shell（pty 回显）
                BasicTextField(
                    value = hidden,
                    onValueChange = { new ->
                        val old = hidden
                        if (new.length > old.length) {
                            for (c in new.substring(old.length)) handleChar(c)
                        } else if (new.length < old.length) {
                            repeat(old.length - new.length) { sendBytes(byteArrayOf(DEL)) }
                        }
                        hidden = " "
                    },
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(
                        autoCorrect = false,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { sendText("\r") }
                    ),
                    cursorBrush = SolidColor(Color.Transparent),
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
