package com.mynote.app.ui.notes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.ui.notes.NoteContentParser.ContentBlock
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

    fun save(title: String, content: String, categoryId: Long?, pinned: Boolean, color: Int?, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.saveNote(noteId, title, content, categoryId, pinned, color)
            onDone()
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
    var showDeleteDialog by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (noteId == null) "新建笔记" else "编辑笔记") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { pinned = !pinned }) {
                        Icon(
                            imageVector = if (pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (pinned) "取消置顶" else "置顶",
                            tint = if (pinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        vm.save(title, content, selectedCategoryId, pinned, note?.color, onBack)
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "保存")
                    }
                    if (noteId != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = previewMode,
                    onClick = { previewMode = !previewMode },
                    label = { Text("预览") }
                )
                categories.forEach { cat ->
                    FilterChip(
                        selected = selectedCategoryId == cat.id,
                        onClick = { selectedCategoryId = cat.id },
                        label = { Text(cat.name) }
                    )
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("标题") },
                singleLine = true
            )

            if (previewMode) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(NoteContentParser.parse(content)) { block ->
                        when (block) {
                            is ContentBlock.Text -> Text(
                                block.text,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            is ContentBlock.Image -> AsyncImage(
                                model = imageStore.physicalFile(block.name),
                                contentDescription = "图片",
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("开始记录…") }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) {
                    Icon(Icons.Default.Image, contentDescription = "插入图片")
                }
                Text("插入图片", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除笔记？") },
            text = { Text("删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.delete(onBack) }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}
