package com.mynote.app.ui.notes

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mynote.app.data.backup.BackupFormatException
import com.mynote.app.data.backup.BackupManager

import com.mynote.app.data.settings.TrashRetentionStore
import com.mynote.app.ui.components.CategoryDot
import com.mynote.app.ui.components.EmptyState
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.NoteRow
import com.mynote.app.ui.components.OPERATION_FAILED
import com.mynote.app.ui.components.PaperAlertDialog
import com.mynote.app.ui.components.PaperActionPanel
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.components.PaperPanelChoice
import com.mynote.app.ui.components.PaperPanelGroup
import com.mynote.app.ui.components.PaperPanelItem
import com.mynote.app.ui.theme.PaperPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    viewModel: NotesViewModel,
    backupManager: BackupManager,
    retentionDays: Int,
    deletedNoteId: Long? = null,
    onDeletedNoteConsumed: () -> Unit = {},
    onOpenNote: (Long) -> Unit,
    onNewNote: (Long?) -> Unit,
    onManageCategories: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenSettings: () -> Unit,
    // 批次 K（条目 105）：编辑页返回后的非阻塞提示入口（默认实现保证现有调用点零改动）
    editorMessage: String? = null,
    onEditorMessageConsumed: () -> Unit = {}
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val backupUi by viewModel.backupUi.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    // 多选模式下的批量操作弹层
    var showBatchCategorySheet by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    // 选好文件后先弹确认框，确认后才执行导入（合并策略对用户可见，见 PaperAlertDialog）
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    // 条目 78：在途标记与提示都在 ViewModel 里，旋转不会丢（也不再绑在 composition 作用域）
    val exporting = backupUi.exporting
    val importing = backupUi.importing
    // 列表相对时间的基准：每分钟刷新一次，界面停留时「5 分钟前」等文案保持准确
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    // 只有用户主动点搜索图标时才抢焦点；compose 重建（如从编辑页返回）不该弹键盘
    var searchFocusRequested by remember { mutableStateOf(false) }

    // 关闭搜索：清空关键词 + 收起搜索栏 + 隐藏键盘（X 按钮与系统返回键共用）
    fun closeSearch() {
        viewModel.onQueryChange("")
        viewModel.exitSelection()
        searchActive = false
        keyboard?.hide()
    }

    // 抽屉打开时返回键优先关闭抽屉；搜索 / 多选让位，避免一次返回同时收两层
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = searchActive && !drawerState.isOpen) { closeSearch() }
    // 多选模式：返回键退出多选（搜索收起优先）
    BackHandler(enabled = selectionMode && !searchActive && !drawerState.isOpen) { viewModel.exitSelection() }

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
        uri?.let { viewModel.exportBackup(it) }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 只暂存 Uri：先弹确认框说明合并策略，确认后才真正导入
        uri?.let { pendingImportUri = it }
    }

    fun runImport(uri: Uri) {
        viewModel.importBackup(uri)
    }

    // 备份结果提示：由 VM 发回，先消费再展示（旋转不重弹）
    LaunchedEffect(backupUi.message) {
        val message = backupUi.message ?: return@LaunchedEffect
        viewModel.consumeBackupMessage()
        snackbarHostState.showSnackbar(message)
    }

    LaunchedEffect(searchActive, searchFocusRequested) {
        if (searchActive && searchFocusRequested) {
            searchFocusRequested = false
            focusRequester.requestFocus()
        }
    }

    // 条目 48：编辑页删除后给「已移入回收站 + 撤销」，先消费再展示
    LaunchedEffect(deletedNoteId) {
        val id = deletedNoteId ?: return@LaunchedEffect
        onDeletedNoteConsumed()
        val result = snackbarHostState.showSnackbar(
            message = "已移入回收站",
            actionLabel = "撤销",
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.restoreDeleted(id)
    }

    // 编辑页提示（如保存结果）回传到列表页：同样先消费再展示，旋转不重弹
    LaunchedEffect(editorMessage) {
        val message = editorMessage ?: return@LaunchedEffect
        onEditorMessageConsumed()
        snackbarHostState.showSnackbar(message)
    }

    // 分类 id → 纸感色缓存：PaperPalette.nearest() 含 HSV 转换 + 6 次色相距离，
    // 只在分类变化时算一次，列表行与批量分类弹层共用，避免每行每次重组重复计算。
    val categoryColorById = remember(categories) {
        categories.associate { it.id to PaperPalette.nearest(it.color) }
    }

    // 分类列表首次非空后才校验：categories 初始为 emptyList()，等 Room 首次真实数据到了再判定
    // 「所选分类已删除」，命中则自动回落「全部」，避免列表恒空且抽屉高亮错位。
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

    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // 多选态不提供侧滑，避免与列表长按/选择手势冲突
            gesturesEnabled = !selectionMode,
            drawerContent = {
                ModalDrawerSheet(
                    drawerState = drawerState,
                    modifier = Modifier.width(300.dp),
                    drawerShape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
                    drawerContainerColor = MaterialTheme.colorScheme.surface
                ) {
                    CategoryDrawerContent(
                        categories = categories,
                        selected = selectedFilter,
                        onSelect = { filter ->
                            // 搜索是全库检索，先退出搜索再应用分类，否则选了分类也看不到效果
                            if (searchActive) closeSearch()
                            viewModel.onFilterSelect(filter)
                            scope.launch { drawerState.close() }
                        },
                        onManageCategories = {
                            scope.launch { drawerState.close() }
                            onManageCategories()
                        },
                        onClose = { scope.launch { drawerState.close() } }
                    )
                }
            }
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    PaperTopBar(
                        title = if (selectionMode) "已选 ${selectedIds.size} 项" else "备忘录",
                        subtitle = when {
                            selectionMode -> null
                            // 结果未到（notes 为 null）或为空时保持纯「全库搜索」，避免闪「0 条」
                            searchActive -> if (notes.isNullOrEmpty()) "全库搜索" else "全库搜索 · ${notes?.size ?: 0} 条"
                            else -> selectedFilter.summaryLabel(categories)
                        },
                        onBack = if (selectionMode) ({ viewModel.exitSelection() }) else null,
                        // 展开 / 收起分类抽屉：chevron 紧贴「备忘录」，展开后旋转 180°
                        titleTrailing = if (selectionMode) null else {
                            {
                                val open = drawerState.targetValue == DrawerValue.Open
                                val rotation by animateFloatAsState(
                                    targetValue = if (open) 180f else 0f,
                                    label = "categoryChevron"
                                )
                                IconButton(
                                    onClick = {
                                        if (searchActive) keyboard?.hide()
                                        scope.launch {
                                            if (drawerState.isOpen) drawerState.close() else drawerState.open()
                                        }
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = if (open) "收起分类" else "展开分类",
                                        modifier = Modifier.size(20.dp).rotate(rotation),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        actions = {
                            if (!selectionMode) {
                                IconButton(onClick = {
                                    viewModel.exitSelection()
                                    searchFocusRequested = true
                                    searchActive = true
                                }) {
                                    Icon(Icons.Default.Search, contentDescription = "搜索")
                                }
                            }
                            // 「更多」触发自绘纸片面板，菜单内容在 Scaffold 之后组装
                            IconButton(onClick = {
                                // 搜索态下键盘常驻，先收起再弹面板（与分类抽屉入口同款处理）
                                keyboard?.hide()
                                menuOpen = true
                            }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多")
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
                                // 键盘右下角给「搜索」，按下即收起键盘（检索本身已由防抖触发）
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
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
                                actionLabel = if (searching) "清空搜索" else "写第一条",
                                onAction = if (searching) ({ closeSearch() }) else ({ onNewNote(viewModel.newNoteCategoryId) })
                            )
                        } else {
                            // 分类 id → 实体映射缓存，避免每行 O(n) 线性查找（行数多时重复执行）
                            val categoriesById = remember(categories) { categories.associateBy { it.id } }
                            // FAB 悬浮在列表之上且不在 Scaffold padding 里，底部留白避免压住最后一条
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 72.dp)
                            ) {
                                itemsIndexed(displayNotes, key = { _, item -> item.id }) { index, item ->
                                    val prevPinned = if (index > 0) displayNotes[index - 1].pinned else false
                                    if (item.pinned && !prevPinned) {
                                        NotesSectionHeader("置顶")
                                    } else if (!item.pinned && prevPinned) {
                                        NotesSectionHeader("其他")
                                    }
                                    NoteRow(
                                        item = item,
                                        categoryColor = item.categoryId?.let { categoryColorById[it] },
                                        onClick = {
                                            if (selectionMode) viewModel.toggleSelect(item.id) else onOpenNote(item.id)
                                        },
                                        onLongClick = {
                                            viewModel.enterSelection(item.id)
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        now = now,
                                        // 全部 tab 或搜索态显示分类名：搜索是全库检索，行内分类归属非常规信息
                                        categoryName = if (selectedFilter is CategoryFilter.All || query.isNotBlank()) {
                                            categoriesById[item.categoryId]?.name
                                        } else {
                                            null
                                        },
                                        // 搜索态给标题/摘要加关键词高亮（与仓库层同口径：去掉首尾空白，
                                        // 否则中文输入法常见的尾随空格会出现「能搜到却不高亮」）
                                        highlightQuery = query.trim().takeIf { it.isNotBlank() },
                                        // 多选高亮与读屏语义只在多选态生效
                                        selected = item.id in selectedIds,
                                        selectionMode = selectionMode,
                                        modifier = Modifier.animateItem()
                                    )
                                    if (index < displayNotes.lastIndex) HairlineDivider()
                                }
                            }
                        }
                    }
                }
            }

        }
        // 纸片面板：正常态分「浏览 / 整理 / 数据」，多选态分「选择 / 操作」
        PaperActionPanel(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            if (selectionMode) {
                PaperPanelGroup("选择") {
                    PaperPanelItem(
                        text = "全选",
                        enabled = !notes.isNullOrEmpty(),
                        showChevron = false
                    ) {
                        menuOpen = false
                        viewModel.selectAll()
                    }
                }
                PaperPanelGroup("操作") {
                    PaperPanelItem(
                        text = "置顶",
                        enabled = selectedIds.isNotEmpty(),
                        showChevron = false
                    ) {
                        menuOpen = false
                        viewModel.batchSetPinned(true, onDone = { n ->
                            scope.launch { snackbarHostState.showSnackbar("已置顶 $n 条") }
                        }, onFailed = { scope.launch { snackbarHostState.showSnackbar(OPERATION_FAILED) } })
                    }
                    PaperPanelItem(
                        text = "取消置顶",
                        enabled = selectedIds.isNotEmpty(),
                        showChevron = false
                    ) {
                        menuOpen = false
                        viewModel.batchSetPinned(false, onDone = { n ->
                            scope.launch { snackbarHostState.showSnackbar("已取消置顶 $n 条") }
                        }, onFailed = { scope.launch { snackbarHostState.showSnackbar(OPERATION_FAILED) } })
                    }
                    PaperPanelItem(
                        text = "移动到分类",
                        enabled = selectedIds.isNotEmpty(),
                        showChevron = false
                    ) {
                        menuOpen = false
                        showBatchCategorySheet = true
                    }
                    PaperPanelItem(
                        text = "删除",
                        enabled = selectedIds.isNotEmpty(),
                        destructive = true,
                        showChevron = false
                    ) {
                        menuOpen = false
                        showBatchDeleteDialog = true
                    }
                }
            } else {
                PaperPanelGroup("浏览") {
                    PaperPanelItem("日历") { menuOpen = false; onOpenCalendar() }
                    PaperPanelItem("回收站") { menuOpen = false; onOpenTrash() }
                }
                PaperPanelGroup("整理") {
                    Column(Modifier.selectableGroup()) {
                        noteSortOptions(sortMode).forEach { option ->
                            PaperPanelChoice(
                                text = option.label,
                                selected = option.selected
                            ) {
                                menuOpen = false
                                viewModel.onSortSelect(option.mode)
                            }
                        }
                    }
                }
                PaperPanelGroup("数据") {
                    PaperPanelItem(
                        text = "导出备份",
                        enabled = !exporting,
                        showChevron = false
                    ) {
                        menuOpen = false
                        exportLauncher.launch(backupFileName(System.currentTimeMillis()))
                    }
                    PaperPanelItem("导入备份", showChevron = false) {
                        menuOpen = false
                        importLauncher.launch(arrayOf("application/zip"))
                    }
                }
                Spacer(Modifier.height(6.dp))
                HairlineDivider()
                Spacer(Modifier.height(6.dp))
                PaperPanelItem("设置") { menuOpen = false; onOpenSettings() }
            }
        }
    }
    pendingImportUri?.let { uri ->
        PaperAlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = "导入备份？",
            text = {
                Text("将从所选备份合并导入：备份中较新的笔记会覆盖本地版本，本地较新的笔记保留，本地没有的笔记将新增。分类名称与颜色以备份为准；备份中在回收站的条目会导入回收站。此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    enabled = !importing,
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
                        viewModel.batchSetCategory(null, onDone = { n ->
                            scope.launch { snackbarHostState.showSnackbar("已移动 $n 条") }
                        }, onFailed = { scope.launch { snackbarHostState.showSnackbar(OPERATION_FAILED) } })
                    }
                )
                categories.forEach { cat ->
                    BatchCategorySheetRow(
                        label = cat.name,
                        color = categoryColorById[cat.id],
                        onClick = {
                            showBatchCategorySheet = false
                            viewModel.batchSetCategory(cat.id, onDone = { n ->
                                scope.launch { snackbarHostState.showSnackbar("已移动 $n 条") }
                            }, onFailed = { scope.launch { snackbarHostState.showSnackbar(OPERATION_FAILED) } })
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
            text = { Text(TrashRetentionStore.batchDeleteHint(selectedIds.size, retentionDays)) },
            confirmButton = {
                TextButton(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = {
                        showBatchDeleteDialog = false
                        viewModel.batchDelete(onDone = { ids ->
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = "已移入回收站 ${ids.size} 条",
                                    actionLabel = "撤销",
                                    duration = SnackbarDuration.Long
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.restoreDeleted(ids)
                                    snackbarHostState.showSnackbar("已恢复 ${ids.size} 条")
                                }
                            }
                        }, onFailed = { scope.launch { snackbarHostState.showSnackbar(OPERATION_FAILED) } })
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 备份文件名：带本地日期（如 mynote-backup-2026-11-26.zip），多次备份不会互相覆盖。 */
internal fun backupFileName(now: Long): String =
    "mynote-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now)) + ".zip"

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
