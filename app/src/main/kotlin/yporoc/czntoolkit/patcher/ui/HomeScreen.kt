package yporoc.czntoolkit.patcher.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import yporoc.czntoolkit.patcher.FileCardView
import yporoc.czntoolkit.patcher.PatcherViewModel
import yporoc.czntoolkit.patcher.UiState
import yporoc.czntoolkit.patcher.shizuku.ShizukuAvail
import rikka.shizuku.Shizuku

private const val SHIZUKU_RELEASE_URL = "https://github.com/RikkaApps/Shizuku/releases"
private const val BROWSE_ROOT = "/storage/emulated/0/Android/data"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: PatcherViewModel) {
    val state by vm.state.collectAsState()
    val shizuku by vm.shizuku.collectAsState()
    val context = LocalContext.current

    var showPathDialog by remember { mutableStateOf(false) }
    var showApplyConfirm by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        vm.attach(context)
        val received = Shizuku.OnBinderReceivedListener { vm.onBinderAlive() }
        val dead = Shizuku.OnBinderDeadListener { vm.onBinderDead() }
        val perm = Shizuku.OnRequestPermissionResultListener { _, _ -> vm.refreshShizuku() }
        Shizuku.addBinderReceivedListenerSticky(received)
        runCatching { Shizuku.addBinderDeadListener(dead) }
        Shizuku.addRequestPermissionResultListener(perm)
        vm.refreshShizuku()
        onDispose {
            runCatching { Shizuku.removeBinderReceivedListener(received) }
            runCatching { Shizuku.removeBinderDeadListener(dead) }
            runCatching { Shizuku.removeRequestPermissionResultListener(perm) }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("CZN 繁转简") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShizukuCard(shizuku, onRequestPermission = { vm.requestPermission() })

            if (showPathDialog) {
                PathDialog(
                    initial = vm.path.value,
                    onBrowse = { dir -> vm.browse(context, dir) },
                    onConfirm = { p ->
                        vm.setPath(p)
                        showPathDialog = false
                        vm.detect(context, p.trim().trimEnd('/'))
                    },
                    onDismiss = { showPathDialog = false },
                )
            }

            GameCard(
                state = state,
                shizuku = shizuku,
                onDetect = { vm.detect(context) },
                onEditPath = { showPathDialog = true },
            )

            when (val s = state) {
                is UiState.Detecting -> {}
                is UiState.Detected -> {
                    FilesCard(s.view.cards)
                    if (!s.view.writeOk) {
                        WarnCard("写探针失败：当前 Shizuku 身份无法写入游戏目录（可能被系统限制或游戏更新锁定），应用补丁会失败")
                    }
                    Button(
                        onClick = { vm.build(context) },
                        enabled = s.view.ready && shizuku is ShizukuAvail.Ready,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) { Text("繁转简（构建补丁）") }
                    if (s.view.etagMatch) {
                        Text(
                            "身份记录已匹配：此前应用过补丁或官方即为简体，重复构建/应用是幂等的。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is UiState.Building -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("正在构建补丁…", fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            LogsBox(s.logs)
                        }
                    }
                }

                is UiState.Built -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionTitle("构建完成")
                            Text(s.summary, style = MaterialTheme.typography.bodyMedium)
                            LogsBox(s.logs)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { showApplyConfirm = true }) { Text("应用补丁") }
                                OutlinedButton(onClick = { vm.reset() }) { Text("放弃") }
                            }
                        }
                    }
                }

                is UiState.Applying -> ProgressCard("正在应用补丁（备份 → 写入 → 同步身份记录）…", s.logs)
                is UiState.Applied -> DoneCard(
                    title = "补丁已应用",
                    body = "原文件已备份到游戏目录 czn_backup_original/。启动游戏验证：patching 阶段不应重新下载资源，文本为简体中文。",
                    actionText = "重新检测",
                    onAction = { vm.detect(context) },
                )
                is UiState.Restoring -> ProgressCard("正在还原官方繁中…", s.logs)
                is UiState.Restored -> DoneCard(
                    title = "已还原官方繁中",
                    body = "三个文件已从备份恢复，manifest.ssra.prev 已移除。",
                    actionText = "重新检测",
                    onAction = { vm.detect(context) },
                )
                is UiState.Failed -> ErrorCard(s.message, s.logs, onRetry = { vm.reset() })
                UiState.Idle -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionTitle("开始")
                            Text(
                                "确认 Shizuku 就绪后，点击「检测」自动定位 3 个汉化目标文件；找不到时用「手动选择路径」。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            RestoreCard(
                enabled = (state as? UiState.Detected)?.view?.backupExists == true,
                onRestore = { vm.restore(context) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showApplyConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyConfirm = false },
            title = { Text("应用补丁？") },
            text = { Text("将替换 manifest.ssra、chunks/lang_zht_b03_0.ssrc 并同步 etag。原文件会自动备份，可随时还原。建议先关闭游戏。") },
            confirmButton = {
                TextButton(onClick = {
                    showApplyConfirm = false
                    vm.apply(context)
                }) { Text("应用") }
            },
            dismissButton = { TextButton(onClick = { showApplyConfirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ShizukuCard(avail: ShizukuAvail, onRequestPermission: () -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Shizuku")
            when (avail) {
                ShizukuAvail.Ready -> StatusRow(true, "就绪（shell 权限已授予）")
                ShizukuAvail.NoPermission -> {
                    StatusRow(false, "已连接，等待授权")
                    Button(onClick = onRequestPermission) { Text("授权") }
                }
                ShizukuAvail.NotRunning -> {
                    StatusRow(false, "未运行：打开 Shizuku 应用 → 通过「无线调试」启动（先在开发者选项开启无线调试并配对）")
                    Button(onClick = onRequestPermission) { Text("重试授权") }
                }
                ShizukuAvail.NotInstalled -> {
                    StatusRow(false, "未安装 Shizuku")
                    Button(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_RELEASE_URL)))
                        }
                    }) { Text("打开下载页") }
                }
            }
        }
    }
}

@Composable
private fun GameCard(
    state: UiState,
    shizuku: ShizukuAvail,
    onDetect: () -> Unit,
    onEditPath: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("游戏目录")
                Spacer(Modifier.width(8.dp))
                if (state is UiState.Detecting) CircularProgressIndicator(Modifier.size(16.dp))
            }
            val path = when (state) {
                is UiState.Detected -> state.view.path
                is UiState.Detecting -> state.path
                else -> PatcherViewModel.DEFAULT_PATH
            }
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDetect, enabled = shizuku is ShizukuAvail.Ready) { Text("检测") }
                OutlinedButton(onClick = onEditPath) { Text("手动选择路径") }
            }
        }
    }
}

@Composable
private fun FilesCard(cards: List<FileCardView>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("汉化目标文件（3 个）")
            cards.forEach { c -> FileRow(c) }
        }
    }
}

@Composable
private fun FileRow(c: FileCardView) {
    val ok = c.exists && c.good
    Row(verticalAlignment = Alignment.Top) {
        Text("●", style = MaterialTheme.typography.titleMedium, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(10.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.label, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (c.exists) formatSize(c.size) else "缺失",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                c.rel,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                c.note,
                style = MaterialTheme.typography.bodySmall,
                color = if (c.good) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RestoreCard(enabled: Boolean, onRestore: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("还原")
            Text(
                "从 czn_backup_original/ 恢复官方繁中三件套，并移除 manifest.ssra.prev。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRestore, enabled = enabled) { Text("还原官方繁中") }
        }
    }
}

@Composable
private fun ProgressCard(text: String, logs: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(text, fontWeight = FontWeight.SemiBold)
            }
            LogsBox(logs)
        }
    }
}

@Composable
private fun DoneCard(title: String, body: String, actionText: String, onAction: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAction) { Text(actionText) }
        }
    }
}

@Composable
private fun WarnCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("注意", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ErrorCard(message: String, logs: List<String>, onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("出错了", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (logs.isNotEmpty()) LogsBox(logs)
            OutlinedButton(onClick = onRetry) { Text("返回") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("●", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusRow(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("●", style = MaterialTheme.typography.bodyMedium, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LogsBox(logs: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val visible = logs.takeLast(120)
        LazyColumn(modifier = Modifier.heightIn(max = 220.dp).padding(8.dp)) {
            items(visible) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PathDialog(
    initial: String,
    onBrowse: suspend (String) -> List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var browsing by remember { mutableStateOf(false) }
    var currentDir by remember { mutableStateOf(BROWSE_ROOT) }
    var dirs by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(browsing, currentDir) {
        if (browsing) dirs = onBrowse(currentDir)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择 gameres 目录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!browsing) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("gameres 完整路径") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilledTonalButton(onClick = {
                        currentDir = BROWSE_ROOT
                        browsing = true
                    }) { Text("浏览选择（Shizuku）") }
                } else {
                    Text(
                        currentDir,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (dirs.isEmpty()) {
                        Text("（无子目录或不可读）", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                            items(dirs) { d ->
                                TextButton(onClick = { currentDir = currentDir.trimEnd('/') + "/" + d }) {
                                    Text(d, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { value = currentDir; browsing = false }) { Text("用当前目录") }
                        OutlinedButton(onClick = { browsing = false }) { Text("返回输入") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("确定并检测") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
