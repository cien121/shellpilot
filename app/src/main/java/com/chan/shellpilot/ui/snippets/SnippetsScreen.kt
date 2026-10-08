package com.chan.shellpilot.ui.snippets

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chan.shellpilot.data.Snippet
import kotlinx.coroutines.launch

/**
 * 代码片段页：双页签——
 * - 内置模板：系统更新/装工具/查软件包/重启 nginx/xray 等，分 Ubuntu/Debian 与
 *   Rocky/RHEL/Alma 两类展示，支持复制和一键执行（发送到当前活跃终端）。
 * - 我的代码片段：用户自定义，分类标签 + 卡片列表（标题/命令/复制/编辑/删除），
 *   右下角 + 添加。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen(
    onBack: () -> Unit,
    /** 发送命令到当前活跃终端并执行；无活跃连接时返回 false。 */
    onRun: (String) -> Boolean,
    viewModel: SnippetViewModel = viewModel(),
) {
    val snippets by viewModel.snippets.collectAsState()
    val groups by viewModel.groups.collectAsState()
    var tab by remember { mutableStateOf(0) } // 0=内置模板，1=我的代码片段
    var selectedGroup by remember { mutableStateOf("") }
    var selectedOs by remember { mutableStateOf("") }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var toDelete by remember { mutableStateOf<Snippet?>(null) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val filtered = if (selectedGroup.isBlank()) snippets
    else snippets.filter { it.group == selectedGroup }

    val templates = if (selectedOs.isBlank()) BuiltinSnippets.all
    else BuiltinSnippets.all.filter { it.os == selectedOs }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("代码片段", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            )
        },
        floatingActionButton = {
            if (tab == 1) {
                FloatingActionButton(onClick = {
                    editing = null
                    showEditor = true
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "添加片段")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("内置模板") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("我的代码片段") },
                )
            }
            if (tab == 0) {
                BuiltinTemplatesTab(
                    templates = templates,
                    selectedOs = selectedOs,
                    onSelectOs = { selectedOs = it },
                    onCopy = { cmd ->
                        clipboard.setText(AnnotatedString(cmd))
                        scope.launch { snackbar.showSnackbar("已复制") }
                    },
                    onRun = { cmd ->
                        if (onRun(cmd)) {
                            scope.launch { snackbar.showSnackbar("已发送到终端执行") }
                        } else {
                            scope.launch { snackbar.showSnackbar("没有活跃连接，请先连接服务器") }
                        }
                    },
                )
            } else {
                // 分类标签
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        AssistChip(
                            onClick = { selectedGroup = "" },
                            label = { Text("全部") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (selectedGroup.isBlank())
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface
                            ),
                        )
                    }
                    items(groups) { g ->
                        AssistChip(
                            onClick = { selectedGroup = g },
                            label = { Text(g) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (selectedGroup == g)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface
                            ),
                        )
                    }
                }
                // 卡片列表
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filtered, key = { it.id }) { snippet ->
                        SnippetCard(
                            snippet = snippet,
                            onCopy = {
                                clipboard.setText(AnnotatedString(snippet.command))
                                scope.launch { snackbar.showSnackbar("已复制") }
                            },
                            onEdit = {
                                editing = snippet
                                showEditor = true
                            },
                            onDelete = { toDelete = snippet },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showEditor) {
        SnippetEditorDialog(
            snippet = editing,
            onDismiss = { showEditor = false },
            onConfirm = { title, command, group ->
                viewModel.save(title, command, group, editing?.id ?: 0)
                showEditor = false
            },
        )
    }

    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("删除片段") },
            text = { Text("确定删除「${s.title}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(s)
                    toDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SnippetCard(
    snippet: Snippet,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    snippet.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                if (snippet.group.isNotBlank()) {
                    Text(
                        snippet.group,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                snippet.command,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SnippetAction(
                    icon = Icons.Filled.ContentCopy,
                    label = "复制",
                    onClick = onCopy,
                )
                SnippetAction(
                    icon = Icons.Filled.Edit,
                    label = "编辑",
                    onClick = onEdit,
                )
                SnippetAction(
                    icon = Icons.Filled.Delete,
                    label = "删除",
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun SnippetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = 8.dp),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.width(36.dp)) {
            Icon(
                icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SnippetEditorDialog(
    snippet: Snippet?,
    onDismiss: () -> Unit,
    onConfirm: (title: String, command: String, group: String) -> Unit,
) {
    var title by remember { mutableStateOf(snippet?.title ?: "") }
    var command by remember { mutableStateOf(snippet?.command ?: "") }
    var group by remember { mutableStateOf(snippet?.group ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (snippet == null) "添加片段" else "编辑片段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("分类（可选）") },
                    placeholder = { Text("如：General、docker") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("命令 *") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, command, group) },
                enabled = title.isNotBlank() && command.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 内置模板页签：系统分类筛选 + 模板卡片（复制/一键执行），不可编辑删除。 */
@Composable
private fun BuiltinTemplatesTab(
    templates: List<BuiltinTemplate>,
    selectedOs: String,
    onSelectOs: (String) -> Unit,
    onCopy: (String) -> Unit,
    onRun: (String) -> Unit,
) {
    Column {
        // 系统分类
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OsChip(
                    label = "全部",
                    selected = selectedOs.isBlank(),
                    onClick = { onSelectOs("") },
                )
            }
            item {
                OsChip(
                    label = BuiltinSnippets.OS_DEBIAN,
                    selected = selectedOs == BuiltinSnippets.OS_DEBIAN,
                    onClick = { onSelectOs(BuiltinSnippets.OS_DEBIAN) },
                )
            }
            item {
                OsChip(
                    label = BuiltinSnippets.OS_RHEL,
                    selected = selectedOs == BuiltinSnippets.OS_RHEL,
                    onClick = { onSelectOs(BuiltinSnippets.OS_RHEL) },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(templates, key = { it.title + it.os }) { t ->
                BuiltinTemplateCard(
                    template = t,
                    onCopy = { onCopy(t.command) },
                    onRun = { onRun(t.command) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun OsChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surface
        ),
    )
}

@Composable
private fun BuiltinTemplateCard(
    template: BuiltinTemplate,
    onCopy: () -> Unit,
    onRun: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    template.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    template.os,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                template.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                template.command,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SnippetAction(
                    icon = Icons.Filled.PlayArrow,
                    label = "执行",
                    onClick = onRun,
                )
                SnippetAction(
                    icon = Icons.Filled.ContentCopy,
                    label = "复制",
                    onClick = onCopy,
                )
            }
        }
    }
}
