package com.mynote.app.ui.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.TrashRetentionStore
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.PaperAlertDialog
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.notes.NoteContentParser
import com.mynote.app.util.TimeFormat
import kotlinx.coroutines.launch

/** 回收站：软删除笔记列表，支持恢复、单条彻底删除与一键清空。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    repository: NoteRepository,
    retentionStore: TrashRetentionStore,
    onBack: () -> Unit
) {
    val vm: TrashViewModel = viewModel(
        key = "trash",
        factory = TrashViewModel.factory(repository, retentionStore)
    )
    val notes by vm.notes.collectAsState()
    val retentionDays by vm.retentionDays.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var purgeTarget by remember { mutableStateOf<NoteEntity?>(null) }
    var showPurgeAllDialog by remember { mutableStateOf(false) }
    // 回收站条目只读预览：彻底删除前可核对内容
    var previewTarget by remember { mutableStateOf<NoteEntity?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PaperTopBar(
                title = "回收站",
                onBack = onBack,
                actions = {
                    if (notes.isNotEmpty()) {
                        TextButton(onClick = { showPurgeAllDialog = true }) { Text("清空") }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.shapes.medium
                    )
                    .padding(12.dp)
            ) {
                Text(
                    "笔记删除后保留 $retentionDays 天，到期自动清理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (notes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "回收站是空的",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(notes, key = { _, note -> note.id }) { index, note ->
                        TrashRow(
                            note = note,
                            onPreview = { previewTarget = note },
                            onRestore = {
                                vm.restore(note) {
                                    scope.launch { snackbarHostState.showSnackbar("已恢复") }
                                }
                            },
                            onPurge = { purgeTarget = note }
                        )
                        if (index < notes.lastIndex) HairlineDivider()
                    }
                }
            }
        }
    }

    purgeTarget?.let { note ->
        PaperAlertDialog(
            onDismissRequest = { purgeTarget = null },
            title = "彻底删除？",
            text = {
                Column {
                    Text("彻底删除「${note.title.ifBlank { "无标题" }}」后不可恢复，历史记录也会一并清除。")
                    val summary = NoteContentParser.plainText(note.content).take(60)
                    if (summary.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        purgeTarget = null
                        vm.purge(note) {
                            scope.launch { snackbarHostState.showSnackbar("已彻底删除") }
                        }
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { purgeTarget = null }) { Text("取消") }
            }
        )
    }

    // 回收站条目只读预览：标题 + 摘要 + 图片数 + 剩余清理天数
    previewTarget?.let { note ->
        val summary = NoteContentParser.plainText(note.content)
        val imageCount = NoteContentParser.extractImageNames(note.content).size
        val deletedAt = note.deletedAt ?: 0L
        val remainingDays = ((deletedAt + com.mynote.app.data.settings.TrashRetentionStore.ttlMs(retentionDays) -
            System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0L)
        PaperAlertDialog(
            onDismissRequest = { previewTarget = null },
            title = "回收站预览",
            text = {
                Column {
                    Text(
                        note.title.ifBlank { "无标题" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    if (summary.isNotBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        "图片 $imageCount 张${if (imageCount > 0) "" else "（无）"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (remainingDays > 0) "还有 $remainingDays 天自动清理" else "即将自动清理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "删除于 ${TimeFormat.dateTime(deletedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { previewTarget = null }) { Text("关闭") }
            }
        )
    }

    if (showPurgeAllDialog) {
        PaperAlertDialog(
            onDismissRequest = { showPurgeAllDialog = false },
            title = "清空回收站？",
            text = { Text("将彻底删除全部 ${notes.size} 条笔记，不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPurgeAllDialog = false
                        vm.purgeAll { count ->
                            scope.launch { snackbarHostState.showSnackbar("已彻底删除 $count 条") }
                        }
                    }
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showPurgeAllDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TrashRow(
    note: NoteEntity,
    onPreview: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onPreview)
        ) {
            Text(
                note.title.ifBlank { "无标题" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (note.content.isNotBlank()) {
                Text(
                    NoteContentParser.plainText(note.content),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                "删除于 ${note.deletedAt?.let { TimeFormat.dateTime(it) } ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRestore) { Text("恢复") }
        TextButton(onClick = onPurge) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        }
    }
}
