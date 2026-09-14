package com.mynote.app.ui.notes

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.mynote.app.data.backup.BackupManager
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.db.NoteRevisionDao
import com.mynote.app.data.export.ImageExportManager
import com.mynote.app.data.export.NoteImageRenderer
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.ui.components.CategoryDot
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.PaperAlertDialog
import com.mynote.app.ui.components.PaperOverflowMenu
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.export.NoteExportDialog
import com.mynote.app.ui.notes.NoteContentParser.ContentBlock
import com.mynote.app.ui.theme.NoteColors
import com.mynote.app.ui.theme.PaperPalette
import com.mynote.app.util.FileNameSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class NoteEditViewModel(
    private val repository: NoteRepository,
    private val imageStore: ImageStore,
    private val noteId: Long?
) : ViewModel() {

    private val _categories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val categories: StateFlow<List<CategoryEntity>> = _categories

    private val _note = MutableStateFlow<NoteEntity?>(null)
    val note: StateFlow<NoteEntity?> = _note

    // 保存/删除在途标志：连点第二次调用直接忽略，防止重复插入与双重退出回调
    private var saving = false
    private var deleting = false

    init {
        viewModelScope.launch {
            repository.observeCategories().collectLatest { _categories.value = it }
        }
        if (noteId != null && noteId != 0L) {
            viewModelScope.launch {
                repository.observeNote(noteId).collectLatest { _note.value = it }
            }
        }
    }

    fun insertImage(uri: Uri, onInserted: (markup: String) -> Unit, onFailed: () -> Unit) {
        viewModelScope.launch {
            val target = imageStore.newImageFile("webp")
            if (imageStore.importAndCompress(uri, target)) {
                onInserted(NoteContentParser.makeImageMarkup(target.name))
            } else {
                onFailed()
            }
        }
    }

    fun addCategory(name: String, onCreated: (Long) -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repository.addCategory(name.trim(), NoteColors.random().toArgb())
            onCreated(id)
        }
    }

    fun save(title: String, content: String, categoryId: Long?, pinned: Boolean, color: Int?, onDone: (String?) -> Unit) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                val before = if (noteId != null && noteId != 0L) repository.countRevisions(noteId) else 0
                val id = repository.saveNote(noteId, title, content, categoryId, pinned, color)
                val warning = if (noteId != null && noteId != 0L &&
                    before < NoteRevisionDao.WARN_AT &&
                    repository.countRevisions(id) == NoteRevisionDao.WARN_AT
                ) {
                    HISTORY_WARNING
                } else {
                    null
                }
                onDone(warning)
            } finally {
                saving = false
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        if (deleting) return
        val n = _note.value ?: return
        deleting = true
        viewModelScope.launch {
            try {
                repository.deleteNote(n)
                onDone()
            } finally {
                deleting = false
            }
        }
    }

    companion object {
        const val HISTORY_WARNING =
            "该笔记历史已 ${NoteRevisionDao.WARN_AT} 条，满 ${NoteRevisionDao.MAX_PER_NOTE} 条后最旧记录会自动清理"

        fun factory(repo: NoteRepository, imageStore: ImageStore, noteId: Long?): ViewModelProvider.Factory =
            viewModelFactory { initializer { NoteEditViewModel(repo, imageStore, noteId) } }
    }
}

/**
 * 判断编辑页是否存在未保存变更。
 * - 新建笔记：标题/正文非空，或分类/置顶相对进入时的初始状态有变化（分类页带入的预选分类不算变更）。
 * - 已有笔记未加载完（saved 为 null）：标题/正文非空即视为有变更（保守防丢）。
 * - 已有笔记已加载：标题/正文/分类/置顶与已保存值逐项比较。
 */
internal fun hasUnsavedChanges(
    isNew: Boolean,
    saved: NoteEntity?,
    initialCategoryId: Long?,
    title: String,
    content: String,
    categoryId: Long?,
    pinned: Boolean
): Boolean = when {
    isNew -> title.isNotBlank() || content.isNotBlank() ||
        categoryId != initialCategoryId || pinned
    saved == null -> title.isNotBlank() || content.isNotBlank()
    else -> title != saved.title || content != saved.content ||
        categoryId != saved.categoryId || pinned != saved.pinned
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(
    noteId: Long?,
    initialCategoryId: Long?,
    repository: NoteRepository,
    imageStore: ImageStore,
    backupManager: BackupManager,
    imageRenderer: NoteImageRenderer,
    exportManager: ImageExportManager,
    aiResultType: String?,
    aiResultText: String?,
    onAiResultConsumed: () -> Unit,
    onOpenAi: (selStart: Int, selEnd: Int, noteTitle: String, noteContent: String) -> Unit,
    onOpenHistory: () -> Unit,
    onBack: () -> Unit
) {
    val vm: NoteEditViewModel = viewModel(
        key = "note_edit_$noteId",
        factory = NoteEditViewModel.factory(repository, imageStore, noteId)
    )
    val note by vm.note.collectAsState()
    val categories by vm.categories.collectAsState()

    var title by rememberSaveable(noteId) { mutableStateOf("") }
    var content by rememberSaveable(noteId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var pinned by rememberSaveable(noteId) { mutableStateOf(false) }
    var selectedCategoryId by rememberSaveable(noteId) { mutableStateOf(initialCategoryId) }
    var menuOpen by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImageExport by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    // 全屏图片预览：记录被点开的图片文件，非空即渲染 NoteImagePreviewDialog
    var previewFile by remember { mutableStateOf<File?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    // 是否已从已保存笔记回填过字段：只回填一次，清空内容后旋转/重建不再被旧内容覆盖
    var initialized by rememberSaveable(noteId) { mutableStateOf(false) }

    // 正文焦点（插图后恢复用）与撤销/重做栈；标题焦点（新建笔记自动聚焦用）
    val contentFocusRequester = remember { FocusRequester() }
    val titleFocusRequester = remember { FocusRequester() }
    val undoController = remember { NoteUndoController() }

    LaunchedEffect(note) {
        val n = note ?: return@LaunchedEffect
        if (!initialized) {
            initialized = true
            if (title.isEmpty() && content.text.isEmpty()) {
                title = n.title
                content = TextFieldValue(n.content)
                selectedCategoryId = n.categoryId
                pinned = n.pinned
            }
            // 回填后的内容作为撤销基线，不回退到空文本
            undoController.reset()
        }
    }

    // 新建笔记（无已保存内容）自动聚焦标题；进程重建恢复出内容则不抢焦点
    LaunchedEffect(noteId, initialized) {
        if (noteId == null && title.isEmpty() && content.text.isEmpty() && !initialized) {
            titleFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(aiResultType, aiResultText) {
        val type = aiResultType ?: return@LaunchedEffect
        val text = aiResultText ?: return@LaunchedEffect
        // 记录应用 AI 结果前的状态，使「贴入 AI 结果」也可撤销；结构性变更强制新开一格
        undoController.record(content, force = true)
        content = AiResultApplier.apply(
            content,
            if (type == "replace") AiResultApplier.Type.REPLACE else AiResultApplier.Type.INSERT,
            text
        )
        // 贴入结果后恢复正文焦点，与插图路径一致
        contentFocusRequester.requestFocus()
        onAiResultConsumed()
    }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            vm.insertImage(
                uri = it,
                onInserted = { markup ->
                    // 记录插图前的状态，插图可撤销；结构性变更强制新开一格
                    undoController.record(content, force = true)
                    content = AiResultApplier.apply(content, AiResultApplier.Type.INSERT, markup)
                    // 选图返回后正文焦点已丢：恢复焦点，光标落在插入点之后
                    contentFocusRequester.requestFocus()
                },
                onFailed = {
                    scope.launch { snackbarHostState.showSnackbar("图片插入失败，请换一张") }
                }
            )
        }
    }

    val exportTxtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { u ->
            val n = note
            if (n != null) scope.launch { backupManager.exportNoteAsTxt(u, n) }
        }
    }

    val context = LocalContext.current

    val isNewNote = noteId == null || noteId == 0L
    val dirty = hasUnsavedChanges(isNewNote, note, initialCategoryId, title, content.text, selectedCategoryId, pinned)

    BackHandler(enabled = true) {
        if (dirty) showUnsavedDialog = true else onBack()
    }

    val saveAndExit: () -> Unit = {
        if (!isSaving) {
            isSaving = true
            vm.save(title, content.text, selectedCategoryId, pinned, note?.color) { warning ->
                isSaving = false
                if (warning != null) {
                    scope.launch {
                        snackbarHostState.showSnackbar(warning)
                        onBack()
                    }
                } else {
                    onBack()
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PaperTopBar(
                onBack = { if (dirty) showUnsavedDialog = true else onBack() },
                actions = {
                    if (noteId != null && noteId != 0L) {
                        IconButton(onClick = {
                            val sel = content.selection
                            onOpenAi(sel.start, sel.end, title, content.text)
                        }) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = "AI 助手")
                        }
                    }
                    IconButton(onClick = { pinned = !pinned }) {
                        Icon(
                            imageVector = if (pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (pinned) "取消置顶" else "置顶",
                            tint = if (pinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = saveAndExit, enabled = !isSaving) {
                        Text("保存", style = MaterialTheme.typography.labelLarge)
                    }
                    PaperOverflowMenu(
                        expanded = menuOpen,
                        onExpandedChange = { menuOpen = it }
                    ) {
                        if (noteId != null && noteId != 0L) {
                            DropdownMenuItem(
                                text = { Text("历史记录") },
                                onClick = { menuOpen = false; onOpenHistory() }
                            )
                        }
                        if (noteId != null || title.isNotBlank() || content.text.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("导出") },
                                onClick = { menuOpen = false; showExportDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("分享") },
                                onClick = {
                                    menuOpen = false
                                    // 分享走纯文本：图片标记剥掉，文案里说明含图数量
                                    val imageCount = NoteContentParser.extractImageNames(content.text).size
                                    val shareText = buildString {
                                        append(title.ifBlank { "无标题" })
                                        append("\n\n")
                                        append(NoteContentParser.plainText(content.text))
                                        if (imageCount > 0) {
                                            append("\n\n（含 $imageCount 张图片，未包含在文本中）")
                                        }
                                    }
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                    }
                                    context.startActivity(Intent.createChooser(send, "分享笔记"))
                                }
                            )
                        }
                        if (noteId != null) {
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; showDeleteDialog = true }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(titleFocusRequester),
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    // 「下一项」直接跳到正文，避免标题写完再手动点正文
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { contentFocusRequester.requestFocus() }),
                    decorationBox = { innerTextField ->
                        if (title.isEmpty()) {
                            Text(
                                "标题",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                )
                // 字数与列表摘要同口径：图片标记不计入
                Text(
                    "字数 ${NoteContentParser.plainText(content.text).length}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            if (previewMode) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(NoteContentParser.parse(content.text)) { block ->
                        when (block) {
                            is ContentBlock.Text -> Text(
                                block.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            is ContentBlock.Image -> {
                                // 预览优先缩略图（省内存，约 1/20 体积），缺失回退原图；点开仍看原图
                                val file = remember(block.name) {
                                    imageStore.thumbFile(block.name).takeIf { it.exists() }
                                        ?: imageStore.physicalFile(block.name)
                                }
                                AsyncImage(
                                    model = file,
                                    contentDescription = "图片",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.large)
                                        // 点开全屏预览（NoteImagePreviewDialog 接线，用原图）
                                        .clickable { previewFile = imageStore.physicalFile(block.name) },
                                    contentScale = ContentScale.FillWidth
                                )
                            }
                        }
                    }
                }
            } else {
                BasicTextField(
                    value = content,
                    onValueChange = {
                        // 每次修改前记录旧值，供撤销
                        undoController.record(content)
                        content = it
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 4.dp)
                        .focusRequester(contentFocusRequester),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        if (content.text.isEmpty()) {
                            Text(
                                "开始记录…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                )
            }

            HairlineDivider()
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { undoController.undo(content)?.let { content = it } },
                        enabled = undoController.canUndo
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "撤销",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { undoController.redo(content)?.let { content = it } },
                        enabled = undoController.canRedo
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "重做",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    TextButton(
                        onClick = {
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) { Text("图片") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { showCategorySheet = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(
                            (categories.firstOrNull { it.id == selectedCategoryId }?.name ?: "分类") + " ▾"
                        )
                    }
                    TextButton(onClick = { previewMode = !previewMode }) {
                        Text(
                            "预览",
                            color = if (previewMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showCategorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showCategorySheet = false },
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                CategorySheetRow(
                    label = "未分类",
                    selected = selectedCategoryId == null,
                    onClick = { selectedCategoryId = null; showCategorySheet = false }
                )
                categories.forEach { cat ->
                    CategorySheetRow(
                        label = cat.name,
                        color = PaperPalette.nearest(cat.color),
                        selected = selectedCategoryId == cat.id,
                        onClick = { selectedCategoryId = cat.id; showCategorySheet = false }
                    )
                }
                HairlineDivider()
                CategorySheetRow(
                    label = "+ 新建分类",
                    selected = false,
                    onClick = { showCategorySheet = false; showAddCategoryDialog = true }
                )
            }
        }
    }

    if (showAddCategoryDialog) {
        var name by remember { mutableStateOf("") }
        PaperAlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = "新建分类",
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.addCategory(name) { id ->
                            selectedCategoryId = id
                            showAddCategoryDialog = false
                        }
                    },
                    enabled = name.isNotBlank()
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategoryDialog = false }) { Text("取消") }
            }
        )
    }

    if (showUnsavedDialog) {
        PaperAlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = "尚未保存的更改",
            text = { Text("当前内容尚未保存，要保存后再退出吗？") },
            confirmButton = {
                Row {
                    TextButton(onClick = onBack) { Text("不保存") }
                    TextButton(
                        onClick = { showUnsavedDialog = false; saveAndExit() },
                        enabled = !isSaving
                    ) {
                        Text("保存并退出")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDeleteDialog) {
        PaperAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = "删除笔记？",
            text = { Text("删除后将移入回收站，30 天后自动清理，期间可随时恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.delete(onBack) }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    if (showExportDialog) {
        val hasContent = title.isNotBlank() || content.text.isNotBlank()
        PaperAlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = "导出为…",
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showExportDialog = false
                            exportTxtLauncher.launch(FileNameSanitizer.sanitize(note?.title ?: "note") + ".txt")
                        },
                        enabled = note != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("文本文档 (txt)") }
                    if (note == null) {
                        Text("保存后可导出 txt", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        onClick = {
                            showExportDialog = false
                            showImageExport = true
                        },
                        enabled = hasContent,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("图片 (PNG)") }
                    if (!hasContent) {
                        Text("还没有内容", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("取消") }
            }
        )
    }

    if (showImageExport) {
        NoteExportDialog(
            title = title,
            content = content.text,
            updatedAt = note?.updatedAt ?: System.currentTimeMillis(),
            renderer = imageRenderer,
            exportManager = exportManager,
            onDismiss = { showImageExport = false }
        )
    }

    previewFile?.let { file ->
        NoteImagePreviewDialog(file = file, onDismiss = { previewFile = null })
    }
}

@Composable
private fun CategorySheetRow(
    label: String,
    selected: Boolean,
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
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}
