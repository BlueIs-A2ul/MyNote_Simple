package com.mynote.app.ui.notes

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.mynote.app.data.backup.BackupManager

import com.mynote.app.data.db.NoteSortMode
import com.mynote.app.ui.components.CategoryDot
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
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    // 多选模式下的批量操作弹层
    var showBatchCategorySheet by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
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
    // 多选模式：返回键退出多选（搜索收起优先）
    BackHandler(enabled = selectionMode && !searchActive) { viewModel.exitSelection() }

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

    val tabs = remember(categories) {
        listOf(CategoryFilter.All, CategoryFilter.Uncategorized) + categories.map { CategoryFilter.Single(it.id) }
    }
    // 分类列表首次非空后才校验：categories 初始为 emptyList()，等 Room 首次真实数据到了再判定
    // 「所选分类已删除」，命中则自动回落「全部」，避免列表恒空且 tab 高亮错位。
    var categoriesLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(categories) {
        if (categories.isNotEmpty()) categoriesLoaded = true
        val filter = selectedFilter
        if (categoriesLoaded && filter is CategoryFilter.Single &&
            categories.none { it.id == filter.categoryId }
        ) {
            viewModel.onFilterSelect(CategoryFilter.All)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PaperTopBar(
                title = if (selectionMode) "已选 ${selectedIds.size} 项" else "备忘录",
                onBack = if (selectionMode) ({ viewModel.exitSelection() }) else null,
                actions = {
                    if (selectionMode) {
                        PaperOverflowMenu(
                            expanded = menuOpen,
                            onExpandedChange = { menuOpen = it }
                        ) {
                            DropdownMenuItem(
                                text = { Text("全选") },
                                onClick = { menuOpen = false; viewModel.selectAll() }
                            )
                            DropdownMenuItem(
                                text = { Text("置顶") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.batchSetPinned(true) { n ->
                                        scope.launch { snackbarHostState.showSnackbar("已置顶 $n 条") }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("取消置顶") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.batchSetPinned(false) { n ->
                                        scope.launch { snackbarHostState.showSnackbar("已取消置顶 $n 条") }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("移动到分类") },
                                onClick = { menuOpen = false; showBatchCategorySheet = true }
                            )
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; showBatchDeleteDialog = true }
                            )
                        }
                    } else {
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
                            // 排序对所有视图生效（#11 口径；#16 的禁用说明已作废）
                            DropdownMenuItem(
                                text = { Text(if (sortMode == NoteSortMode.UPDATED_DESC) "✓ 排序：最近更新" else "排序：最近更新") },
                                onClick = { menuOpen = false; viewModel.onSortSelect(NoteSortMode.UPDATED_DESC) }
                            )
                            DropdownMenuItem(
                                text = { Text(if (sortMode == NoteSortMode.CREATED_DESC) "✓ 排序：最新创建" else "排序：最新创建") },
                                onClick = { menuOpen = false; viewModel.onSortSelect(NoteSortMode.CREATED_DESC) }
                            )
                            DropdownMenuItem(
                                text = { Text(if (sortMode == NoteSortMode.TITLE_ASC) "✓ 排序：按标题" else "排序：按标题") },
                                onClick = { menuOpen = false; viewModel.onSortSelect(NoteSortMode.TITLE_ASC) }
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
                }
            )
        },
        floatingActionButton = {
            if (!selectionMode) {
                SmallFloatingActionButton(
                    onClick = { onNewNote(viewModel.newNoteCategoryId) },
                    shape = MaterialTheme.shapes.large,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "新建笔记", modifier = Modifier.size(20.dp))
                }
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
                    selected = selectedFilter,
                    onSelect = viewModel::onFilterSelect,
                    label = { filter ->
                        when (filter) {
                            CategoryFilter.All -> "全部"
                            CategoryFilter.Uncategorized -> "未分类"
                            is CategoryFilter.Single ->
                                categories.firstOrNull { it.id == filter.categoryId }?.name ?: "未知分类"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }

            if (notes == null) {
                // 冷启动首帧：Room 结果未到，先显示轻量加载占位，避免「还没有笔记」闪屏乃至误点新建
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "加载中…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val displayNotes = notes ?: emptyList()
                if (displayNotes.isEmpty()) {
                    val searching = query.isNotBlank()
                    val emptyText = when {
                        searching -> "没有匹配的笔记"
                        selectedFilter is CategoryFilter.Uncategorized -> "没有未分类的笔记"
                        selectedFilter is CategoryFilter.Single -> "这个分类还没有笔记"
                        else -> "还没有笔记"
                    }
                    EmptyState(
                        icon = Icons.Outlined.Description,
                        text = emptyText,
                        actionLabel = if (searching) null else "写第一条",
                        onAction = if (searching) null else ({ onNewNote(viewModel.newNoteCategoryId) })
                    )
                } else {
                    // 分类 id → 实体映射缓存，避免每行 O(n) 线性查找（行数多时重复执行）
                    val categoriesById = remember(categories) { categories.associateBy { it.id } }
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(displayNotes, key = { _, note -> note.id }) { index, note ->
                            val prevPinned = if (index > 0) displayNotes[index - 1].pinned else false
                            if (note.pinned && !prevPinned) {
                                NotesSectionHeader("置顶")
                            } else if (!note.pinned && prevPinned) {
                                NotesSectionHeader("其他")
                            }
                            NoteRow(
                                note = note,
                                categoryColor = categoriesById[note.categoryId]
                                    ?.let { PaperPalette.nearest(it.color) },
                                onClick = {
                                    if (selectionMode) viewModel.toggleSelect(note.id) else onOpenNote(note.id)
                                },
                                onLongClick = { viewModel.enterSelection(note.id) },
                                now = now,
                                // 全部 tab 或搜索态显示分类名：搜索是全库检索，行内分类归属非常规信息
                                categoryName = if (selectedFilter is CategoryFilter.All || query.isNotBlank()) {
                                    categoriesById[note.categoryId]?.name
                                } else {
                                    null
                                },
                                // 搜索态给标题/摘要加关键词高亮
                                highlightQuery = query.takeIf { it.isNotBlank() },
                                // 多选高亮
                                selected = note.id in selectedIds,
                                modifier = Modifier.animateItem()
                            )
                            if (index < displayNotes.lastIndex) HairlineDivider()
                        }
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

    if (showBatchCategorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showBatchCategorySheet = false },
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                BatchCategorySheetRow(
                    label = "未分类",
                    onClick = {
                        showBatchCategorySheet = false
                        viewModel.batchSetCategory(null) { n ->
                            scope.launch { snackbarHostState.showSnackbar("已移动 $n 条") }
                        }
                    }
                )
                categories.forEach { cat ->
                    BatchCategorySheetRow(
                        label = cat.name,
                        color = PaperPalette.nearest(cat.color),
                        onClick = {
                            showBatchCategorySheet = false
                            viewModel.batchSetCategory(cat.id) { n ->
                                scope.launch { snackbarHostState.showSnackbar("已移动 $n 条") }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showBatchDeleteDialog) {
        PaperAlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = "删除所选笔记？",
            text = { Text("将把所选 ${selectedIds.size} 条笔记移入回收站，30 天后自动清理，期间可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatchDeleteDialog = false
                        viewModel.batchDelete { n ->
                            scope.launch { snackbarHostState.showSnackbar("已删除 $n 条") }
                        }
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 列表分组标题（置顶 / 其他）。 */
@Composable
private fun NotesSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun BatchCategorySheetRow(
    label: String,
    onClick: () -> Unit,
    color: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (color != null) {
            CategoryDot(color)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
