package com.mynote.app.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.data.db.NoteRevisionDao
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.PaperAlertDialog
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.components.TextTabRow
import com.mynote.app.util.TimeFormat

private val RemovedBg = Color(0x33EF5350)
private val AddedBg = Color(0x334CAF50)
private val RemovedEmphasis = Color(0x66EF5350)
private val AddedEmphasis = Color(0x664CAF50)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteHistoryScreen(
    noteId: Long,
    hadUnsavedDraft: Boolean,
    repository: NoteRepository,
    onRestored: () -> Unit,
    onBack: () -> Unit
) {
    val vm: NoteHistoryViewModel = viewModel(
        key = "note_history_$noteId",
        factory = NoteHistoryViewModel.factory(repository, noteId)
    )
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRestoreDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = state.detail != null) { vm.closeDetail() }

    LaunchedEffect(state.restoreSucceeded) {
        if (state.restoreSucceeded) {
            vm.consumeRestoreSuccess()
            onRestored()
        }
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeMessage()
    }
    LaunchedEffect(state.detail) {
        if (state.detail == null) showRestoreDialog = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PaperTopBar(
                title = "历史记录 (${state.count}/${NoteRevisionDao.MAX_PER_NOTE})",
                onBack = { if (state.detail != null) vm.closeDetail() else onBack() }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            val detail = state.detail
            if (detail == null) {
                state.bannerText?.let { banner ->
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
                            banner,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (state.revisions.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "保存一次后开始记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(
                            state.revisions,
                            key = { _, item -> item.revision.id }
                        ) { index, item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.selectRevision(item.revision.id) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        TimeFormat.dateTime(item.revision.savedAt),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (item.isCurrent) {
                                        Text(
                                            "当前版本",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    item.labels.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            if (index < state.revisions.lastIndex) HairlineDivider()
                        }
                    }
                }
            } else {
                DetailContent(
                    state = state,
                    detail = detail,
                    vm = vm,
                    onRestore = { showRestoreDialog = true }
                )
            }
        }
    }

    if (showRestoreDialog) {
        PaperAlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = "恢复此版本？",
            text = {
                Text(
                    "将用此版本覆盖当前内容，并生成一条新的历史记录。" +
                        if (hadUnsavedDraft) "编辑页未保存的修改将一并丢弃。" else ""
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreDialog = false
                        vm.restore()
                    },
                    enabled = !state.restoring
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DetailContent(
    state: NoteHistoryViewModel.UiState,
    detail: NoteHistoryViewModel.DetailState,
    vm: NoteHistoryViewModel,
    onRestore: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                TimeFormat.dateTime(detail.revision.savedAt),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                detail.labels.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!detail.isFirst) {
                Spacer(Modifier.height(8.dp))
                TextTabRow(
                    tabs = listOf(false, true),
                    selected = detail.showFullText,
                    onSelect = { fullText -> if (fullText != detail.showFullText) vm.toggleFullText() },
                    label = { if (it) "全文" else "对比" }
                )
            }
        }
        HairlineDivider()
        if (detail.showFullText || detail.isFirst) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                Text(
                    detail.revision.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        } else if (detail.loadingDiff) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(detail.diffLines) { line -> DiffLineRow(line) }
            }
        }
        HairlineDivider()
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            if (!detail.isCurrent) {
                Button(
                    onClick = onRestore,
                    enabled = !state.restoring,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(if (state.restoring) "恢复中…" else "恢复此版本")
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: NoteDiff.Line) {
    val background = when (line.type) {
        NoteDiff.Type.REMOVED -> RemovedBg
        NoteDiff.Type.ADDED -> AddedBg
        NoteDiff.Type.UNCHANGED -> Color.Transparent
    }
    val emphasisColor = when (line.type) {
        NoteDiff.Type.REMOVED -> RemovedEmphasis
        NoteDiff.Type.ADDED -> AddedEmphasis
        NoteDiff.Type.UNCHANGED -> Color.Transparent
    }
    val text = buildAnnotatedString {
        var cursor = 0
        for (range in line.emphasis) {
            val start = range.first.coerceAtLeast(cursor)
            val end = (range.last + 1).coerceAtMost(line.text.length)
            if (end <= start) continue
            if (start > cursor) append(line.text.substring(cursor, start))
            withStyle(SpanStyle(background = emphasisColor)) {
                append(line.text.substring(start, end))
            }
            cursor = end
        }
        if (cursor < line.text.length) append(line.text.substring(cursor))
        if (line.text.isEmpty()) append(" ")
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodyMedium,
        textDecoration = if (line.type == NoteDiff.Type.REMOVED) TextDecoration.LineThrough else null
    )
}
