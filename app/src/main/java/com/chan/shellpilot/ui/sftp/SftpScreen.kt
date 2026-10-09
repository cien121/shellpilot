package com.chan.shellpilot.ui.sftp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chan.shellpilot.data.Server
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SFTP 文件管理（终端内入口）：
 * 目录浏览 / 上级切换 / 文本文件打开编辑保存 / 上传 / 下载 / 删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpScreen(
    server: Server,
    onBack: () -> Unit,
    vm: SftpViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var editingEntry by remember { mutableStateOf<SftpEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<SftpEntry?>(null) }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    // 上传文件选择器
    val pickUpload = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            } ?: "upload_${System.currentTimeMillis()}"
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                toast("无法读取文件")
                return@launch
            }
            vm.upload(bytes.inputStream(), name)
                .onSuccess { toast("已上传 $name") }
                .onFailure { toast("上传失败：${it.message?.take(80)}") }
        }
    }

    LaunchedEffect(server.id) { vm.start(server) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (state.path.ifBlank { "文件管理" }) +
                            if (state.execOnly) " · 兼容模式" else "",
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.goUp() }) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "上级目录")
                    }
                    IconButton(onClick = { pickUpload.launch("*/*") }) {
                        Icon(Icons.Filled.Upload, contentDescription = "上传")
                    }
                    IconButton(onClick = { vm.showDiag() }) {
                        Icon(Icons.Filled.BugReport, contentDescription = "连接诊断")
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                (state.loading && !state.ready) || (state.connecting && !state.ready) -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "正在连接 ${server.name}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.error != null && !state.ready -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            state.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (!state.execOnly) {
                            TextButton(onClick = { vm.setExecOnly(true) }) {
                                Text("切换到兼容模式（纯命令）")
                            }
                        }
                        TextButton(onClick = { vm.showDiag() }) { Text("连接诊断") }
                        TextButton(onClick = { vm.refresh() }) { Text("重试") }
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.entries, key = { it.path }) { entry ->
                            SftpRow(
                                entry = entry,
                                onOpen = {
                                    if (entry.isDir) {
                                        vm.enterDir(entry)
                                    } else {
                                        scope.launch {
                                            vm.readText(entry.path)
                                                .onSuccess { editingEntry = entry }
                                                .onFailure {
                                                    toast(it.message?.take(100) ?: "打开失败")
                                                }
                                        }
                                    }
                                },
                                onDownload = {
                                    scope.launch {
                                        downloadToPublic(
                                            context, vm, entry,
                                            onOk = { toast("已下载到 Download/$it") },
                                            onErr = { toast("下载失败：${it.take(80)}") },
                                        )
                                    }
                                },
                                onDelete = { deleteTarget = entry },
                            )
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
            if (state.loading && state.ready) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                )
            }
        }
    }

    // 主机指纹确认（首次连接 / 密钥变更）
    val hk = state.hostKeyInfo
    if (hk != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissHostKey() },
            title = {
                Text(
                    if (hk.changed) "警告：主机密钥已变更！" else "确认服务器指纹",
                    color = if (hk.changed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column {
                    if (hk.changed) {
                        Text(
                            "该服务器的主机密钥与上次记录的不一致，可能是服务器重装，也可能是中间人攻击。请核对后再决定。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("旧指纹：${hk.oldFingerprint ?: "--"}")
                    } else {
                        Text("首次连接该服务器，请核对指纹无误后信任：")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("${hk.host}:${hk.port}")
                    Text(
                        "${hk.keyType}\n${hk.fingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmHostKey() }) {
                    Text(if (hk.changed) "信任新密钥并连接" else "信任并连接")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissHostKey() }) { Text("取消") }
            },
        )
    }

    // 连接诊断弹窗：逐步展示建连→exec→会话通道→sftp子系统→列目录→并发连接的结果
    if (state.showDiag) {
        SftpDiagDialog(
            steps = state.diagSteps,
            running = state.diagRunning,
            execOnly = state.execOnly,
            onRun = { vm.runDiagnostics() },
            onUseExecOnly = { vm.setExecOnly(true) },
            onDismiss = { vm.dismissDiag() },
        )
    }

    // 文本编辑器（全屏）
    val target = editingEntry
    if (target != null) {
        SftpEditorScreen(
            entry = target,
            vm = vm,
            onBack = { editingEntry = null },
            onSaved = {
                editingEntry = null
                toast("已保存")
            },
            onError = { toast("保存失败：${it.take(80)}") },
        )
    }

    // 删除确认
    val del = deleteTarget
    if (del != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除确认") },
            text = { Text("确定删除「${del.name}」吗？${if (del.isDir) "（仅空目录可删）" else ""}") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        vm.delete(del)
                            .onSuccess { toast("已删除") }
                            .onFailure { toast("删除失败：${it.message?.take(80)}") }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SftpRow(
    entry: SftpEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (entry.isDir) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(
                    if (entry.isDir) "目录"
                    else "${formatSize(entry.size)} · ${formatTime(entry.mtimeSec)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!entry.isDir) {
                IconButton(onClick = onOpen) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = "下载")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 文本文件编辑器（全屏）：顶部标题栏（文件名+关闭/保存），下方全屏文本编辑区。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SftpEditorScreen(
    entry: SftpEntry,
    vm: SftpViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    LaunchedEffect(entry.path) {
        vm.readText(entry.path)
            .onSuccess { text = it }
            .onFailure { onError(it.message ?: "读取失败"); onBack() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        entry.name,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
                    }
                },
                actions = {
                    TextButton(
                        enabled = text != null && !saving,
                        onClick = {
                            val t = text ?: return@TextButton
                            saving = true
                            scope.launch {
                                vm.writeText(entry.path, t)
                                    .onSuccess { onSaved() }
                                    .onFailure { onError(it.message ?: "保存失败") }
                                saving = false
                            }
                        },
                    ) { Text(if (saving) "保存中…" else "保存") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            )
        },
    ) { padding ->
        val t = text
        if (t == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            OutlinedTextField(
                value = t,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                ),
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun formatTime(sec: Long): String {
    if (sec <= 0) return ""
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        .format(Date(sec * 1000))
}

/** 下载到系统 Download 目录（Q+ 用 MediaStore，低版本用应用外部文件目录）。 */
private suspend fun downloadToPublic(
    context: Context,
    vm: SftpViewModel,
    entry: SftpEntry,
    onOk: (String) -> Unit,
    onErr: (String) -> Unit,
) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, entry.name)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw IllegalStateException("无法创建下载项")
            resolver.openOutputStream(uri)?.let { out ->
                vm.download(entry, out).getOrThrow()
            } ?: throw IllegalStateException("无法写入下载项")
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IllegalStateException("无外部存储")
            val file = File(dir, entry.name)
            file.outputStream().let { out ->
                vm.download(entry, out).getOrThrow()
            }
        }
        entry.name
    }.onSuccess { onOk(it) }.onFailure { onErr(it.message ?: "下载失败") }
}

/** 连接诊断弹窗：每一步 ✓/✗ + 说明，方便截图定位服务端/客户端问题。 */
@Composable
private fun SftpDiagDialog(
    steps: List<SftpDiagStep>,
    running: Boolean,
    execOnly: Boolean,
    onRun: () -> Unit,
    onUseExecOnly: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接诊断", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (running && steps.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                    ) {
                        items(steps) { s ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (s.ok) "✓ " else "✗ ",
                                        color = if (s.ok) androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                        else MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(s.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                }
                                Text(
                                    s.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
                if (!running && steps.isNotEmpty() && !execOnly) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "若 SFTP 子系统失败但命令执行正常，可切换兼容模式（纯命令）使用文件管理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            Row {
                if (!running) {
                    TextButton(onClick = onRun) { Text("重新诊断") }
                }
                if (!running && steps.isNotEmpty() && !execOnly) {
                    TextButton(onClick = onUseExecOnly) { Text("兼容模式") }
                }
            }
        },
    )
}
