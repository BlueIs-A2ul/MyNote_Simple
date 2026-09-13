package com.mynote.app.ui.notes

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.mynote.app.data.backup.BackupManager
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteSortMode
import com.mynote.app.ui.components.EmptyState
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.NoteRow
import com.mynote.app.ui.components.PaperAlertDialog
import com.mynote.app.ui.components.PaperOverflowMenu
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.components.TextTabRow
import com.mynote.app.ui.theme.PaperPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    viewModel: NotesViewModel,
    backupManager: BackupManager,
    onOpenNote: (Long) -> Unit,
    onNewNote: (Long?) -> Unit,
    onManageCategories: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val notes by viewModel.notes.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val query by viewModel.query.collectAsState()
    val selectedCategoryId by viewModel.selectedCategoryId.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    // 选好文件后先弹确认框，确认后才执行导入（合并策略对用户可见，见 PaperAlertDialog）
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    // 列表相对时间的基准：每分钟刷新一次，界面停留时「5 分钟前」等文案保持准确
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 关闭搜索：清空关键词 + 收起搜索栏 + 隐藏键盘（X 按钮与系统返回键共用）
    fun closeSearch() {
        viewModel.onQueryChange("")
        searchActive = false
        keyboard?.hide()
    }

    BackHandler(enabled = searchActive) { closeSearch() }

    // 相对时间每分钟 tick 一次
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            scope.launch {
                val message = runCatching { backupManager.exportZip(it) }
                    .fold(onSuccess = { "已导出 $it 条笔记" }, onFailure = { "导出失败" })
                snackbarHostState.showSnackbar(message)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 只暂存 Uri：先弹确认框说明合并策略，确认后才真正导入
        uri?.let { pendingImportUri = it }
    }

    fun runImport(uri: Uri) {
        scope.launch {
            val message = runCatching { backupManager.importZip(uri) }
                .fold(onSuccess = { "已导入 $it 条笔记" }, onFailure = { "导入失败" })
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(searchActive) {
        if (searchActive) focusRequester.requestFocus()
    }

    val tabs = remember(categories) { listOf<CategoryEntity?>(null) + categories }
    val selectedTab = remember(categories, selectedCategoryId) {
        categories.firstOrNull { it.id == selectedCategoryId }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PaperTopBar(
                title = "备忘录",
                actions = {
                    IconButton(onClick = { searchActive = true }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                    PaperOverflowMenu(
                        expanded = menuOpen,
                        onExpandedChange = { menuOpen = it }
                    ) {
                        DropdownMenuItem(
                            text = { Text("分类管理") },
                            onClick = { menuOpen = false; onManageCategories() }
                        )
                        DropdownMenuItem(
                            text = { Text("回收站") },
                            onClick = { menuOpen = false; onOpenTrash() }
                        )
                        // 排序只作用于「全部」且非搜索态
                        val sortEnabled = selectedCategoryId == null && query.isBlank()
                        DropdownMenuItem(
                            text = { Text(if (sortMode == NoteSortMode.UPDATED_DESC) "✓ 排序：最近更新" else "排序：最近更新") },
                            onClick = { menuOpen = false; viewModel.onSortSelect(NoteSortMode.UPDATED_DESC) },
                            enabled = sortEnabled
                        )
                        DropdownMenuItem(
                            text = { Text(if (sortMode == NoteSortMode.CREATED_DESC) "✓ 排序：最早创建" else "排序：最早创建") },
                            onClick = { menuOpen = false; viewModel.onSortSelect(NoteSortMode.CREATED_DESC) },
                            enabled = sortEnabled
                        )
                        DropdownMenuItem(
                            text = { Text(if (sortMode == NoteSortMode.TITLE_ASC) "✓ 排序：按标题" else "排序：按标题") },
                            onClick = { menuOpen = false; viewModel.onSortSelect(NoteSortMode.TITLE_ASC) },
                            enabled = sortEnabled
                        )
                        DropdownMenuItem(
                            text = { Text("导出备份") },
                            onClick = { menuOpen = false; exportLauncher.launch("mynote-backup.zip") }
                        )
                        DropdownMenuItem(
                            text = { Text("导入备份") },
                            onClick = { menuOpen = false; importLauncher.launch(arrayOf("application/zip")) }
                        )
                        DropdownMenuItem(
                            text = { Text("设置") },
                            onClick = { menuOpen = false; onOpenSettings() }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            SmallFloatingActionButton(
                onClick = { onNewNote(selectedCategoryId) },
                shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建笔记", modifier = Modifier.size(20.dp))
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            if (searchActive) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            if (query.isEmpty()) {
                                Text(
                                    "搜索笔记…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    )
                    IconButton(onClick = { closeSearch() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭搜索",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                HairlineDivider()
            } else {
                TextTabRow(
                    tabs = tabs,
                    selected = selectedTab,
                    onSelect = { viewModel.onCategorySelect(it?.id) },
                    label = { it?.name ?: "全部" },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }

            if (notes.isEmpty()) {
                val searching = query.isNotBlank()
                EmptyState(
                    icon = Icons.Outlined.Description,
                    text = if (searching) "没有匹配的笔记" else "还没有笔记",
                    actionLabel = if (searching) null else "写第一条",
                    onAction = if (searching) null else ({ onNewNote(selectedCategoryId) })
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(notes, key = { _, note -> note.id }) { index, note ->
                        NoteRow(
                            note = note,
                            categoryColor = categories.firstOrNull { it.id == note.categoryId }
                                ?.let { PaperPalette.nearest(it.color) },
                            onClick = { onOpenNote(note.id) },
                            now = now,
                            // 仅在「全部」tab 且非搜索态显示分类名，避免与顶部 tab 重复
                            categoryName = if (selectedCategoryId == null && query.isBlank()) {
                                categories.firstOrNull { it.id == note.categoryId }?.name
                            } else {
                                null
                            },
                            // 搜索态给标题/摘要加关键词高亮
                            highlightQuery = query.takeIf { it.isNotBlank() },
                            modifier = Modifier.animateItem()
                        )
                        if (index < notes.lastIndex) HairlineDivider()
                    }
                }
            }
        }
    }

    pendingImportUri?.let { uri ->
        PaperAlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = "导入备份？",
            text = {
                Text("将从所选备份合并导入：备份中较新的笔记会覆盖本地版本，本地较新的笔记保留，本地没有的笔记将新增。此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImportUri = null
                        runImport(uri)
                    }
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) { Text("取消") }
            }
        )
    }
}
