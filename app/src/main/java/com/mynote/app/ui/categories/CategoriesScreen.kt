package com.mynote.app.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.repository.NoteRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(repository: NoteRepository, onBack: () -> Unit) {
    val vm: CategoriesViewModel = viewModel(factory = CategoriesViewModel.factory(repository))
    val categories by vm.categories.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分类管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新建分类")
                    }
                }
            )
        }
    ) { padding ->
        if (categories.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无分类", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories, key = { it.id }) { cat ->
                    CategoryRow(cat = cat, onRename = { vm.rename(cat, it) }, onDelete = { vm.delete(cat) })
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新建分类") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") })
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

    Card(modifier = Modifier.fillMaxWidth().clickable { editing = true }) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(12.dp).background(Color(cat.color), CircleShape))
            if (editing) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    singleLine = true
                )
                TextButton(onClick = { onRename(name); editing = false }) { Text("保存") }
            } else {
                Text(cat.name, modifier = Modifier.weight(1f).padding(start = 12.dp))
                IconButton(onClick = { showDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除分类？") },
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
