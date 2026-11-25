package com.mynote.app.ui.notes

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
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
import com.mynote.app.data.settings.TrashRetentionStore
import com.mynote.app.ui.common.launchSafely
import com.mynote.app.ui.components.CategoryDot
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.PaperAlertDialog
import com.mynote.app.ui.components.PaperOverflowMenu
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.export.NoteExportDialog
import com.mynote.app.util.NoteContentParser
import com.mynote.app.util.NoteContentParser.ContentBlock
import com.mynote.app.ui.theme.NoteColors
import com.mynote.app.ui.theme.PaperPalette
import com.mynote.app.util.CalendarDates
import com.mynote.app.util.FileNameSanitizer
import com.mynote.app.util.TimeFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** 条目 100⑦：尚未入库的新笔记在 [EditorTextCache] 里的键。 */
private const val NEW_NOTE_CACHE_KEY = -1L

/**
 * 性能：字数/图数统计的防抖窗口。
 * 计算要跑两次全文正则（plainText 是 Regex.replace，会整串复制），逐键算会让长笔记输入发涩；
 * 连续输入时 LaunchedEffect 不断被取消，效果就是「停下来再算」。
 */
private const val COUNTER_DEBOUNCE_MS = 150L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NoteEditScreen(
    noteId: Long?,
    initialCategoryId: Long?,
    retentionDays: Int,
    repository: NoteRepository,
    imageStore: ImageStore,
    backupManager: BackupManager,
    imageRenderer: NoteImageRenderer,
    exportManager: ImageExportManager,
    draftIdHandle: SavedStateHandle,
    aiResultType: String?,
    aiResultText: String?,
    onAiResultConsumed: () -> Unit,
    restoredAt: Long? = null,
    onRestoreConsumed: () -> Unit = {},
    onNoteDeleted: (Long) -> Unit = {},
    onOpenAi: (selStart: Int, selEnd: Int, noteTitle: String, noteContent: String) -> Unit,
    onOpenHistory: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val vm: NoteEditViewModel = viewModel(
        key = "note_edit_$noteId",
        factory = NoteEditViewModel.factory(repository, imageStore, noteId, draftIdHandle, backupManager)
    )
    val note by vm.note.collectAsStateWithLifecycle()
    val noteMissing by vm.noteMissing.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val draftId by vm.draftId.collectAsStateWithLifecycle()
    val restoredNote by vm.restoredNote.collectAsStateWithLifecycle()

    var title by rememberSaveable(noteId) { mutableStateOf("") }
    // 条目 100⑦：Bundle 只存键（noteId），正文放进程内缓存，超长正文不再进 saved instance state
    val contentSaver = remember(noteId) {
        Saver<TextFieldValue, Long>(
            save = { value ->
                val key = noteId ?: NEW_NOTE_CACHE_KEY
                EditorTextCache.put(key, value)
                key
            },
            restore = { key -> EditorTextCache.get(key) ?: TextFieldValue("") }
        )
    }
    var content by rememberSaveable(noteId, stateSaver = contentSaver) {
        mutableStateOf(TextFieldValue(""))
    }
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var pinned by rememberSaveable(noteId) { mutableStateOf(false) }
    var selectedCategoryId by rememberSaveable(noteId) { mutableStateOf(initialCategoryId) }
    // 归属日期（本地零点毫秒），null = 未标记
    var noteDate by rememberSaveable(noteId) { mutableStateOf<Long?>(null) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var showCategorySheet by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showAddCategoryDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showImageExport by rememberSaveable { mutableStateOf(false) }
    var showUnsavedDialog by rememberSaveable { mutableStateOf(false) }
    // 全屏图片预览：记录被点开的图片文件，非空即渲染 NoteImagePreviewDialog
    var previewFile by remember { mutableStateOf<File?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    // 条目 95：用户明确选「不保存」后，ON_STOP 的草稿兜底必须被抑制，否则仍会静默入库
    var discarded by remember { mutableStateOf(false) }
    // 条目 88：AI 结果落地时的提示（在 snackbar 宿主声明之前就要能写入）
    var pendingSnackbar by remember { mutableStateOf<String?>(null) }
    // 条目 97：预览态长按待删的图片名
    var pendingDeleteImage by remember { mutableStateOf<String?>(null) }
    // 导出在途标志与结果都在 VM 里（条目 78）
    val exportState by vm.exportState.collectAsStateWithLifecycle()
    val exporting = exportState.exporting
    // 是否已从已保存笔记回填过字段：只回填一次，清空内容后旋转/重建不再被旧内容覆盖
    var initialized by rememberSaveable(noteId) { mutableStateOf(false) }

    // 条目 100⑦：正文缓存是否命中。未命中通常说明进程被杀后重建（或缓存被淘汰），Bundle 里只有键，
    // 正文已回到空串；此时标题却能从 Bundle 恢复，不能再用“标题为空”当作回填条件。
    val bodyCacheKey = noteId ?: NEW_NOTE_CACHE_KEY
    val bodyCacheHit = remember(noteId) { EditorTextCache.get(bodyCacheKey) != null }

    // 正文焦点（插图后恢复用）与撤销/重做栈；标题焦点（新建笔记自动聚焦用）
    val contentFocusRequester = remember { FocusRequester() }
    val titleFocusRequester = remember { FocusRequester() }
    val undoController = remember { NoteUndoController() }
    // 预览模式下正文输入框不在组合中，直接 requestFocus() 会抛 IllegalStateException
    val bodyFocusGate = remember { BodyFocusGate() }

    // 由预览切回编辑时，补一次被延后的正文聚焦（插图中/贴入 AI 结果时处于预览态的情况）
    LaunchedEffect(previewMode) {
        if (bodyFocusGate.onPreviewModeChanged(previewMode)) contentFocusRequester.requestFocus()
    }

    LaunchedEffect(note) {
        val n = note ?: return@LaunchedEffect
        val action = decideEditorRestore(
            initialized = initialized,
            title = title,
            content = content.text,
            noteContent = n.content,
            bodyCacheHit = bodyCacheHit
        )
        // 首次拿到数据行后，无论是否回填都要标记已初始化，避免后续 note 变更反复覆盖用户输入。
        if (!initialized) initialized = true
        when (action) {
            EditorRestoreAction.FILL_ALL -> {
                title = n.title
                content = TextFieldValue(n.content)
                selectedCategoryId = n.categoryId
                pinned = n.pinned
                noteDate = n.noteDate
                // 回填后的内容作为撤销基线，不回退到空文本
                undoController.reset()
            }
            EditorRestoreAction.FILL_BODY -> {
                // 进程重建：标题已恢复，只补丢掉的正文，光标放到末尾方便继续写。
                content = TextFieldValue(n.content, selection = TextRange(n.content.length))
                undoController.reset()
            }
            EditorRestoreAction.NONE -> Unit
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
        // 条目 88：替换需要非空选区；选区已折叠时改为插入并提示，避免点「替换选中」毫无反应
        val collapsedReplace = type == "replace" && content.selection.collapsed
        if (collapsedReplace) pendingSnackbar = "未选中文本，已插入到光标处"
        content = AiResultApplier.apply(
            content,
            if (type == "replace" && !collapsedReplace) AiResultApplier.Type.REPLACE
            else AiResultApplier.Type.INSERT,
            text
        )
        // 贴入结果后恢复正文焦点，与插图路径一致；预览态无法聚焦，延后到切回编辑
        if (bodyFocusGate.requestBodyFocus(previewMode)) contentFocusRequester.requestFocus()
        onAiResultConsumed()
    }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 条目 88：AI 落地提示（选区折叠时改为插入）
    LaunchedEffect(pendingSnackbar) {
        val message = pendingSnackbar ?: return@LaunchedEffect
        pendingSnackbar = null
        snackbarHostState.showSnackbar(message)
    }

    // 条目 93：历史页恢复成功后弹提示（先消费再展示，避免旋转重弹或中途离开永不消费）；
    // 同时重新读库回填——只弹提示不刷新的话，编辑页仍拿着恢复前的内容，
    // 下次保存或退后台静默存草稿就会把刚恢复的版本又写回去。
    LaunchedEffect(restoredAt) {
        if (restoredAt == null) return@LaunchedEffect
        onRestoreConsumed()
        vm.refreshAfterRestore()
        // 先消费会让 restoredAt 变 null、重启本 effect；提示放到独立作用域，避免随之被取消
        scope.launch { snackbarHostState.showSnackbar("已恢复历史版本") }
    }

    // 用恢复后的数据行覆盖本地编辑态，并把撤销基线重置到恢复后的文本
    LaunchedEffect(restoredNote) {
        val restored = restoredNote ?: return@LaunchedEffect
        title = restored.title
        content = TextFieldValue(restored.content, selection = TextRange(restored.content.length))
        selectedCategoryId = restored.categoryId
        pinned = restored.pinned
        noteDate = restored.noteDate
        initialized = true
        undoController.reset()
        vm.consumeRestoredNote()
    }

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
                    // 选图返回后正文焦点已丢：恢复焦点，光标落在插入点之后（预览态延后到切回编辑）
                    if (bodyFocusGate.requestBodyFocus(previewMode)) contentFocusRequester.requestFocus()
                },
                onFailed = {
                    scope.launch { snackbarHostState.showSnackbar("图片插入失败，请换一张") }
                }
            )
        }
    }

    // txt 导出写编辑态现值（未保存的新笔记也能导出），结果用 snackbar 反馈
    val exportTxtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { vm.exportTxt(it, title, content.text) }
    }

    LaunchedEffect(exportState.message) {
        val message = exportState.message ?: return@LaunchedEffect
        vm.consumeExportMessage()
        snackbarHostState.showSnackbar(message)
    }

    // 条目 100：字数只在正文变化时重算，避免每次重组都跑一遍正则。
    // 性能：改为防抖计算，连续输入期间不再逐键对全文做两次正则扫描。
    var wordCount by remember { mutableIntStateOf(0) }
    // 条目 97：图片数量随时可见，删图后立即变化
    var imageCount by remember { mutableIntStateOf(0) }
    // 首帧（打开已有笔记）立刻算一次，避免先闪一下「字数 0 · 图 0」；之后才走防抖
    var countersPrimed by remember { mutableStateOf(false) }
    LaunchedEffect(content.text) {
        if (countersPrimed && content.text.isNotEmpty()) delay(COUNTER_DEBOUNCE_MS)
        wordCount = NoteContentParser.plainText(content.text).length
        imageCount = NoteContentParser.extractImageNames(content.text).size
        countersPrimed = true
    }

    val context = LocalContext.current

    val isNewNote = (noteId == null || noteId == 0L) && draftId == null
    // 条目 94：存量笔记在回填完成前不能当空文本保存，否则会用空正文覆盖旧内容
    val loaded = isNewNote || note != null
    // 性能：dirty 只在返回/保存/历史入口的 lambda 里读取，用 derivedStateOf 把全文比较移出组合期，
    // 避免每次按键都因它把整屏（Scaffold/顶栏/底部栏）带进重组。
    val dirty by remember(note, isNewNote) {
        derivedStateOf {
            hasUnsavedChanges(isNewNote, note, title, content.text, selectedCategoryId, pinned, noteDate)
        }
    }
    val canSave = loaded && !(isNewNote && title.isBlank() && content.text.isBlank())

    // 退后台静默保存草稿：标题/正文非空的新笔记会入库，空白新笔记不落库
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (dirty && !isSaving && !discarded) {
            vm.saveDraft(title, content.text, selectedCategoryId, pinned, note?.color, noteDate)
        }
    }

    BackHandler(enabled = true) {
        if (dirty) showUnsavedDialog = true else onBack()
    }

    val saveAndExit: () -> Unit = {
        if (!isSaving) {
            isSaving = true
            vm.save(
                title, content.text, selectedCategoryId, pinned, note?.color, noteDate,
                onDone = { warning ->
                    isSaving = false
                    if (warning != null) {
                        scope.launch {
                            snackbarHostState.showSnackbar(warning)
                            onBack()
                        }
                    } else {
                        onBack()
                    }
                },
                onError = { message ->
                    // 条目 94：保存失败要留在编辑页并说明原因，不能静默退出
                    isSaving = false
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
            )
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
                    TextButton(onClick = saveAndExit, enabled = !isSaving && canSave) {
                        Text(
                            if (isSaving) "保存中…" else "保存",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    PaperOverflowMenu(
                        expanded = menuOpen,
                        onExpandedChange = { menuOpen = it }
                    ) {
                        if (noteId != null && noteId != 0L) {
                            DropdownMenuItem(
                                text = { Text("历史记录") },
                                onClick = { menuOpen = false; onOpenHistory(dirty) }
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
            // 深链失效/别处彻底删除：即使正文里有未保存内容也要明确告知，而不是只把输入框变成只读
            if (noteMissing && !isNewNote) {
                Text(
                    "笔记不存在或已被删除",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = title,
                    onValueChange = { title = it },
                    // 条目 95：保存期间只读，避免「点保存后补敲的字」不属于本次保存
                    readOnly = isSaving || !loaded,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(titleFocusRequester)
                        // 条目 99：TalkBack 之前读作两个无名编辑框
                        .semantics { contentDescription = "标题" },
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    // 「下一项」直接跳到正文，避免标题写完再手动点正文
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = {
                            // 预览态下正文输入框不在组合中，只能延后聚焦，否则会抛异常
                            if (bodyFocusGate.requestBodyFocus(previewMode)) contentFocusRequester.requestFocus()
                        }
                    ),
                    decorationBox = { innerTextField ->
                        if (title.isEmpty()) {
                            Text(
                                when {
                                    loaded -> "标题"
                                    noteMissing -> "笔记不存在或已被删除"
                                    else -> "加载中…"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                )
                // 字数与列表摘要同口径：图片标记不计入
                Text(
                    "字数 ${wordCount} · 图 ${imageCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            if (previewMode) {
                // 预览块解析按正文缓存：否则预览态下任何无关重组都会重跑一次全文解析
                val previewBlocks = remember(content.text) { NoteContentParser.parse(content.text) }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(previewBlocks) { block ->
                        when (block) {
                            is ContentBlock.Text -> Text(
                                block.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            is ContentBlock.Image -> {
                                // 预览直接加载原图：Coil 按显示尺寸自动降采样解码，内存由 LRU 缓存托管
                                val file = remember(block.name) { imageStore.physicalFile(block.name) }
                                AsyncImage(
                                    model = file,
                                    contentDescription = "图片",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.large)
                                        // 点开全屏预览（NoteImagePreviewDialog 接线，用原图）；
                                        // 条目 97：长按可删除这张图片，不必手工抠标记
                                        .combinedClickable(
                                            onClick = { previewFile = imageStore.physicalFile(block.name) },
                                            onLongClick = { pendingDeleteImage = block.name }
                                        ),
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
                    // 条目 94/95：未回填完成或保存中都不接受输入
                    readOnly = isSaving || !loaded,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 4.dp)
                        .focusRequester(contentFocusRequester)
                        .semantics { contentDescription = "正文" },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        if (content.text.isEmpty()) {
                            Text(
                                when {
                                    !loaded && noteMissing -> "笔记不存在或已被删除"
                                    !loaded -> "加载中…"
                                    else -> "开始记录…"
                                },
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
                        onClick = {
                            undoController.undo(content)?.let {
                                content = it
                                // 条目 97：撤销掉插图后，刚落盘的文件不该留在磁盘上
                                vm.cleanupUnusedInsertedImages(it.text)
                            }
                        },
                        enabled = undoController.canUndo
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "撤销",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            undoController.redo(content)?.let {
                                content = it
                                vm.cleanupUnusedInsertedImages(it.text)
                            }
                        },
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
                    TextButton(
                        onClick = { showDatePicker = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(noteDate?.let { TimeFormat.dateLabel(it) } ?: "日期")
                    }
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

    if (showDatePicker) {
        // DatePicker 的初始值与返回值都是「所选日期的 UTC 零点」，经 CalendarDates 与本地零点互转
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = noteDate?.let { CalendarDates.localDayToPickerMillis(it) }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            noteDate = CalendarDates.pickerMillisToLocalDay(it)
                        }
                        showDatePicker = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                Row {
                    if (noteDate != null) {
                        TextButton(onClick = { noteDate = null; showDatePicker = false }) { Text("清除") }
                    }
                    TextButton(onClick = { showDatePicker = false }) { Text("取消") }
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showAddCategoryDialog) {
        var name by rememberSaveable { mutableStateOf("") }
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
                    // 条目 95：先置抑制标志再退出，否则 ON_STOP 仍会静默把内容写进库；
                    // 条目 97：放弃编辑时清理本次插入且未被正文引用的图片文件
                    TextButton(onClick = {
                        discarded = true
                        vm.cleanupUnusedInsertedImages("")
                        onBack()
                    }) { Text("不保存") }
                    TextButton(
                        onClick = { showUnsavedDialog = false; saveAndExit() },
                        enabled = !isSaving && canSave
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

    // 条目 97：删除图片 = 移除正文里的标记（保存时既有 GC 会回收文件）
    pendingDeleteImage?.let { imageName ->
        PaperAlertDialog(
            onDismissRequest = { pendingDeleteImage = null },
            title = "删除这张图片？",
            text = { Text("将从正文中移除这张图片；保存后图片文件也会被回收。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteImage = null
                    undoController.record(content, force = true)
                    content = TextFieldValue(
                        NoteContentParser.removeImageMarkup(content.text, imageName),
                        content.selection
                    )
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteImage = null }) { Text("取消") }
            }
        )
    }

    if (showDeleteDialog) {
        PaperAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = "删除笔记？",
            text = { Text(TrashRetentionStore.deleteHint(retentionDays)) },
            confirmButton = {
                TextButton(onClick = {
                    val deletedId = vm.currentNoteId()
                    vm.delete(
                        onDone = {
                            // 条目 48：把 id 回传给列表页，由它弹「已移入回收站 / 撤销」
                            deletedId?.let(onNoteDeleted)
                            onBack()
                        },
                        onError = {
                            scope.launch { snackbarHostState.showSnackbar("删除失败，请重试") }
                        }
                    )
                }) { Text("删除") }
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
                            exportTxtLauncher.launch(FileNameSanitizer.sanitize(title.ifBlank { "note" }) + ".txt")
                        },
                        enabled = hasContent && !exporting,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("文本文档 (txt)") }
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
