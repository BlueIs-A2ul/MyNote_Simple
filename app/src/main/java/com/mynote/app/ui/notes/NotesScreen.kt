package com.mynote.app.ui.notes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.mynote.app.data.backup.BackupManager
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.ui.components.EmptyState
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.NoteRow
import com.mynote.app.ui.components.PaperOverflowMenu
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.components.TextTabRow
import com.mynote.app.ui.theme.PaperPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    viewModel: NotesViewModel,
    backupManager: BackupManager,
    onOpenNote: (Long) -> Unit,
    onNewNote: () -> Unit,
    onManageCategories: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val notes by viewModel.notes.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val query by viewModel.query.collectAsState()
    val selectedCategoryId by viewModel.selectedCategoryId.collectAsState()

    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { scope.launch { backupManager.exportZip(it) } }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { scope.launch { backupManager.importZip(it) } }
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
                onClick = onNewNote,
                shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建笔记", modifier = Modifier.size(20.dp))
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
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
                    IconButton(onClick = {
                        viewModel.onQueryChange("")
                        searchActive = false
                        keyboard?.hide()
                    }) {
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
                    onAction = if (searching) null else onNewNote
                )
            } else {
                val now = System.currentTimeMillis()
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(notes, key = { _, note -> note.id }) { index, note ->
                        NoteRow(
                            note = note,
                            categoryColor = categories.firstOrNull { it.id == note.categoryId }
                                ?.let { PaperPalette.nearest(it.color) },
                            onClick = { onOpenNote(note.id) },
                            now = now,
                            modifier = Modifier.animateItem()
                        )
                        if (index < notes.lastIndex) HairlineDivider()
                    }
                }
            }
        }
    }
}
