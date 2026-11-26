package com.mynote.app.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.ui.components.CategoryDot
import com.mynote.app.ui.components.EmptyState
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.PaperAlertDialog
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.theme.NoteColors
import com.mynote.app.ui.theme.PaperPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoriesScreen(repository: NoteRepository, onBack: () -> Unit) {
    val vm: CategoriesViewModel = viewModel(factory = CategoriesViewModel.factory(repository))
    val categoriesState by vm.categories.collectAsStateWithLifecycle()
    val noteCounts by vm.noteCounts.collectAsStateWithLifecycle()
    // 条目 92：null = 首帧加载中，避免进页面先闪「暂无分类」
    val categories = categoriesState.orEmpty()
    val snackbarHostState = remember { SnackbarHostState() }

    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
        if (categoriesState == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        } else if (categories.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Label,
                text = "暂无分类",
                actionLabel = "新建分类",
                onAction = { showAddDialog = true },
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                // 可发现性提示：行内色点与名称各自可点，光看界面猜不到
                item(key = "hint") {
                    Text(
                        "点按色点可循环换色，点按名称可重命名",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                itemsIndexed(categories, key = { _, cat -> cat.id }) { index, cat ->
                    CategoryRow(
                        cat = cat,
                        noteCount = noteCounts[cat.id] ?: 0,
                        snackbarHostState = snackbarHostState,
                        onRename = { newName, done -> vm.rename(cat, newName, done) },
                        onDelete = { vm.delete(cat) },
                        onCycleColor = { vm.cycleColor(cat) }
                    )
                    if (index < categories.lastIndex) HairlineDivider()
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        // 默认选中「最少使用色」：在既有分类中出现次数最少的色板颜色
        var selectedColor by remember { mutableStateOf(vm.leastUsedColor(categories)) }
        PaperAlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = "新建分类",
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "颜色",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    // 条目 99：每个色板 48dp 触达区包住 28dp 圆点，触屏与读屏都够用。
                    // 用 FlowRow：对话框内容宽度有限，6 × 48dp 在窄屏上会换行而不是被裁掉。
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NoteColors.forEachIndexed { index, color ->
                            val argb = color.toArgb()
                            val selected = argb == selectedColor
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "颜色 " + (index + 1) }
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = { selectedColor = argb }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .then(
                                            if (selected) {
                                                Modifier.border(
                                                    2.dp,
                                                    MaterialTheme.colorScheme.onSurface,
                                                    CircleShape
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.add(name, selectedColor) { showAddDialog = false } },
                    // 条目 49：空名不可提交（以前点了没反应）
                    enabled = name.isNotBlank()
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun CategoryRow(
    cat: CategoryEntity,
    noteCount: Int,
    snackbarHostState: SnackbarHostState,
    onRename: (String, (Boolean) -> Unit) -> Unit,
    onDelete: () -> Unit,
    onCycleColor: () -> Unit
) {
    var showDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(cat.name) }
    val scope = rememberCoroutineScope()

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
            TextButton(
                onClick = {
                    if (name.isBlank()) {
                        editing = false
                    } else {
                        onRename(name) { success ->
                            if (success) {
                                editing = false
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("该分类已存在") }
                            }
                        }
                    }
                }
            ) { Text("保存") }
            TextButton(onClick = { name = cat.name; editing = false }) { Text("取消") }
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { editing = true }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 色点可点击：循环切到色板下一个颜色（独立于整行点击进入编辑）
                // 条目 99：视觉 24dp、触达区 48dp，并给读屏一句描述
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { onCycleColor() }
                        .semantics { contentDescription = "切换「" + cat.name + "」的分类颜色" },
                    contentAlignment = Alignment.Center
                ) {
                    CategoryDot(PaperPalette.nearest(cat.color))
                }
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
            text = {
                // 条目 49：说清影响面（分类名 + 会移到未分类的笔记数）
                Text(
                    "删除分类「" + cat.name + "」后，" +
                        (if (noteCount > 0) noteCount.toString() + " 条笔记会移到「未分类」，" else "") +
                        "笔记本身不会被删除。"
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDelete = false }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            }
        )
    }
}