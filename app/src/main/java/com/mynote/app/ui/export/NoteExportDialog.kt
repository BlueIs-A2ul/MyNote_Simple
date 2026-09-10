package com.mynote.app.ui.export

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.data.export.ImageExportManager
import com.mynote.app.data.export.NoteImageRenderer
import com.mynote.app.data.export.PageMode
import com.mynote.app.util.TimeFormat
import kotlinx.coroutines.launch
import java.io.File

/** 让预览 Dialog 拥有独立 ViewModel 生命周期，关闭时取消渲染与清空预览。 */
private class DialogViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteExportDialog(
    title: String,
    content: String,
    updatedAt: Long,
    renderer: NoteImageRenderer,
    exportManager: ImageExportManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val measurer = rememberTextMeasurer()
    val owner = remember { DialogViewModelStoreOwner() }
    val note = remember(title, content, updatedAt) {
        NoteImageRenderer.NoteData(title, content, TimeFormat.date(updatedAt))
    }
    val vm: NoteExportViewModel = viewModel(
        viewModelStoreOwner = owner,
        factory = NoteExportViewModel.factory(renderer, exportManager, measurer, note)
    )
    DisposableEffect(Unit) {
        onDispose { owner.viewModelStore.clear() }
    }

    val state by vm.state.collectAsState()
    val exporting by vm.exporting.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var pendingFiles by remember { mutableStateOf<List<File>?>(null) }
    var writing by remember { mutableStateOf(false) }

    fun showMessage(message: String) {
        scope.launch { snackbarHost.showSnackbar(message) }
    }

    fun dismiss() {
        if (writing) {
            showMessage("正在写入文件，请稍候")
        } else {
            onDismiss()
        }
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val files = pendingFiles
        if (uri != null && files != null) {
            writing = true
            scope.launch {
                try {
                    exportManager.copyPageToUri(uri, files.first())
                        .onSuccess { showMessage("已保存") }
                        .onFailure { showMessage("保存失败") }
                } finally {
                    writing = false
                }
            }
        }
        pendingFiles = null
    }

    val chooseTree = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val files = pendingFiles
        if (uri != null && files != null) {
            writing = true
            scope.launch {
                try {
                    exportManager.copyCacheToTree(uri, files)
                        .onSuccess { showMessage("已保存 $it 张") }
                        .onFailure { showMessage(it.message ?: "保存失败") }
                } finally {
                    writing = false
                }
            }
        }
        pendingFiles = null
    }

    fun save() {
        vm.renderToCache(
            onReady = { files ->
                pendingFiles = files
                if (files.size == 1) {
                    createDocument.launch(vm.suggestedFileName())
                } else {
                    chooseTree.launch(null)
                }
            },
            onError = { showMessage(it) }
        )
    }

    fun share() {
        vm.renderToCache(
            onReady = { files ->
                scope.launch {
                    vm.shareIntentFor(files)
                        .onSuccess { intent ->
                            try {
                                context.startActivity(Intent.createChooser(intent, "分享图片"))
                            } catch (e: ActivityNotFoundException) {
                                showMessage("没有可分享的应用")
                            }
                        }
                        .onFailure { showMessage(it.message ?: "分享失败") }
                }
            },
            onError = { showMessage(it) }
        )
    }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("导出图片") },
                        navigationIcon = {
                            IconButton(onClick = { dismiss() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                            }
                        }
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHost) },
                bottomBar = {
                    if (state is NoteExportViewModel.State.Ready) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (exporting) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { save() },
                                    enabled = !exporting && !writing,
                                    modifier = Modifier.weight(1f)
                                ) { Text("保存") }
                                Button(
                                    onClick = { share() },
                                    enabled = !exporting && !writing,
                                    modifier = Modifier.weight(1f)
                                ) { Text("分享") }
                            }
                        }
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    when (val current = state) {
                        is NoteExportViewModel.State.Loading -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }

                        is NoteExportViewModel.State.Error -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(current.message)
                                TextButton(onClick = vm::retry) { Text("重试") }
                            }
                        }

                        is NoteExportViewModel.State.Ready -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp)
                            ) {
                                if (current.pageCount > 1) {
                                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                        SegmentedButton(
                                            selected = current.mode == PageMode.PAGED,
                                            onClick = { vm.setMode(PageMode.PAGED) },
                                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                            enabled = !exporting && !writing,
                                            label = { Text("分页 ${current.pageCount} 张") }
                                        )
                                        SegmentedButton(
                                            selected = current.mode == PageMode.SINGLE,
                                            onClick = { vm.setMode(PageMode.SINGLE) },
                                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                            enabled = !exporting && !writing,
                                            label = { Text("单张长图") }
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                                if (current.longWarning) {
                                    Text(
                                        "长图较大，生成可能需要几秒",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                                current.pages.forEach { page ->
                                    Image(
                                        bitmap = page.bitmap.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
