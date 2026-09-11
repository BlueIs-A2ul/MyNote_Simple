package com.mynote.app.ui.categories

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.ui.components.CategoryDot
import com.mynote.app.ui.components.EmptyState
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.PaperAlertDialog
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.theme.PaperPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(repository: NoteRepository, onBack: () -> Unit) {
    val vm: CategoriesViewModel = viewModel(factory = CategoriesViewModel.factory(repository))
    val categories by vm.categories.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PaperTopBar(
                title = "分类管理",
                onBack = onBack,
                actions = {
                    TextButton(onClick = { showAddDialog = true }) { Text("新建") }
                }
            )
        }
    ) { padding ->
        if (categories.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Label,
                text = "暂无分类",
                actionLabel = "新建分类",
                onAction = { showAddDialog = true },
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                itemsIndexed(categories, key = { _, cat -> cat.id }) { index, cat ->
                    CategoryRow(
                        cat = cat,
                        onRename = { vm.rename(cat, it) },
                        onDelete = { vm.delete(cat) }
                    )
                    if (index < categories.lastIndex) HairlineDivider()
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        PaperAlertDialog(
            onDismissRequest = { showAddDialog = false },
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
                TextButton(onClick = { vm.add(name) { showAddDialog = false } }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun CategoryRow(cat: CategoryEntity, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var showDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(cat.name) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (editing) {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    if (name.isEmpty()) {
                        Text(
                            "分类名称",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            )
            TextButton(onClick = { onRename(name); editing = false }) { Text("保存") }
            TextButton(onClick = { name = cat.name; editing = false }) { Text("取消") }
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { editing = true }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryDot(PaperPalette.nearest(cat.color))
                Spacer(Modifier.width(10.dp))
                Text(
                    cat.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(onClick = { showDelete = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showDelete) {
        PaperAlertDialog(
            onDismissRequest = { showDelete = false },
            title = "删除分类？",
            text = { Text("该分类下的笔记将移入未分类。") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDelete = false }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            }
        )
    }
}
