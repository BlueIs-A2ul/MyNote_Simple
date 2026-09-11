package com.mynote.app.ui.notes

import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
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
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.export.NoteExportDialog
import com.mynote.app.ui.notes.NoteContentParser.ContentBlock
import com.mynote.app.ui.theme.NoteColors
import com.mynote.app.ui.theme.PaperPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NoteEditViewModel(
    private val repository: NoteRepository,
    private val imageStore: ImageStore,
    private val noteId: Long?
) : ViewModel() {

    private val _categories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val categories: StateFlow<List<CategoryEntity>> = _categories

    private val _note = MutableStateFlow<NoteEntity?>(null)
    val note: StateFlow<NoteEntity?> = _note

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

    fun insertImage(uri: Uri, onInserted: (markup: String) -> Unit) {
        viewModelScope.launch {
            val target = imageStore.newImageFile("webp")
            if (imageStore.importAndCompress(uri, target)) {
                onInserted(NoteContentParser.makeImageMarkup(target.name))
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
        viewModelScope.launch {
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
        }
    }

    fun delete(onDone: () -> Unit) {
        val n = _note.value ?: return
        viewModelScope.launch {
            repository.deleteNote(n)
            onDone()
        }
    }

    companion object {
        const val HISTORY_WARNING =
            "该笔记历史已 ${NoteRevisionDao.WARN_AT} 条，满 ${NoteRevisionDao.MAX_PER_NOTE} 条后最旧记录会自动清理"

        fun factory(repo: NoteRepository, imageStore: ImageStore, noteId: Long?): ViewModelProvider.Factory =
            viewModelFactory { initializer { NoteEditViewModel(repo, imageStore, noteId) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(
    noteId: Long?,
    repository: NoteRepository,
    imageStore: ImageStore,
    backupManager: BackupManager,
    imageRenderer: NoteImageRenderer,
    exportManager: ImageExportManager,
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
    var content by rememberSaveable(noteId) { mutableStateOf("") }
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var pinned by rememberSaveable(noteId) { mutableStateOf(false) }
    var selectedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImageExport by remember { mutableStateOf(false) }

    LaunchedEffect(note) {
        if (note != null && title.isEmpty() && content.isEmpty()) {
            title = note!!.title
            content = note!!.content
            selectedCategoryId = note!!.categoryId
            pinned = note!!.pinned
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { vm.insertImage(it) { markup -> content += markup } }
    }

    val scope = rememberCoroutineScope()
    val exportTxtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { u ->
            val n = note
            if (n != null) scope.launch { backupManager.exportNoteAsTxt(u, n) }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PaperTopBar(
                onBack = onBack,
                actions = {
                    IconButton(onClick = { pinned = !pinned }) {
                        Icon(
                            imageVector = if (pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (pinned) "取消置顶" else "置顶",
                            tint = if (pinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        vm.save(title, content, selectedCategoryId, pinned, note?.color) { warning ->
                            if (warning != null) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(warning)
                                    onBack()
                                }
                            } else {
                                onBack()
                            }
                        }
                    }) {
                        Text("保存", style = MaterialTheme.typography.labelLarge)
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (noteId != null && noteId != 0L) {
                            DropdownMenuItem(
                                text = { Text("历史记录") },
                                onClick = { menuOpen = false; onOpenHistory() }
                            )
                        }
                        if (noteId != null || title.isNotBlank() || content.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("导出") },
                                onClick = { menuOpen = false; showExportDialog = true }
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
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
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

            if (previewMode) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(NoteContentParser.parse(content)) { block ->
                        when (block) {
                            is ContentBlock.Text -> Text(
                                block.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            is ContentBlock.Image -> AsyncImage(
                                model = imageStore.physicalFile(block.name),
                                contentDescription = "图片",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.large),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }
            } else {
                BasicTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 4.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        if (content.isEmpty()) {
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

    if (showDeleteDialog) {
        PaperAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = "删除笔记？",
            text = { Text("删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.delete(onBack) }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    if (showExportDialog) {
        val hasContent = title.isNotBlank() || content.isNotBlank()
        PaperAlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = "导出为…",
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showExportDialog = false
                            exportTxtLauncher.launch((note?.title ?: "note") + ".txt")
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
            content = content,
            updatedAt = note?.updatedAt ?: System.currentTimeMillis(),
            renderer = imageRenderer,
            exportManager = exportManager,
            onDismiss = { showImageExport = false }
        )
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
