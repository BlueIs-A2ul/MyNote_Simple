package com.mynote.app.ui.ai

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.data.ai.AiChatRepository
import com.mynote.app.data.ai.AiDriverRegistry
import com.mynote.app.data.ai.AiWebDriver
import com.mynote.app.data.ai.AiWebSession
import com.mynote.app.data.db.AiMessageEntity
import com.mynote.app.data.db.AiSessionEntity
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.AiSettingsStore
import com.mynote.app.util.TimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    noteId: Long,
    noteTitle: String,
    noteContent: String,
    hasSelection: Boolean,
    aiRepository: AiChatRepository,
    noteRepository: NoteRepository,
    registry: AiDriverRegistry,
    settingsStore: AiSettingsStore,
    externalScope: CoroutineScope,
    webSessionFactory: (AiWebDriver) -> AiWebSession,
    onApplyResult: (type: String, text: String) -> Unit,
    onBack: () -> Unit
) {
    val vm: AiChatViewModel = viewModel(
        key = "ai_chat_$noteId",
        factory = AiChatViewModel.factory(
            noteId, noteTitle, noteContent, aiRepository, noteRepository,
            settingsStore, externalScope, registry, webSessionFactory
        )
    )
    val state by vm.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    DisposableEffect(Unit) {
        onDispose {
            vm.onWebViewDetached()
            webView?.destroy()
            webView = null
        }
    }

    state.snackbar?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            vm.consumeSnackbar()
        }
    }

    BackHandler(enabled = state.webVisible) { vm.toggleWebVisible() }

    if (!state.privacyAccepted) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("发送到 DeepSeek 网页") },
            text = {
                Text("AI 助手会把你输入的内容与笔记正文发送到 DeepSeek 网页处理，回答由网页实时返回。请遵守服务条款，避免发送敏感信息。")
            },
            confirmButton = {
                TextButton(onClick = { vm.acceptPrivacy() }) { Text("同意并继续") }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !state.webVisible,
        drawerContent = {
            ModalDrawerSheet(drawerState = drawerState) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AI 会话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        vm.newChat()
                        scope.launch { drawerState.close() }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("新对话")
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.sessions, key = { it.id }) { session ->
                        SessionItem(
                            session = session,
                            selected = session.id == state.currentSessionId,
                            deleteEnabled = !(state.sending && session.id == state.currentSessionId),
                            onClick = {
                                vm.selectSession(session.id)
                                scope.launch { drawerState.close() }
                            },
                            onDelete = { vm.deleteSession(session.id) }
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            state.sessions.firstOrNull { it.id == state.currentSessionId }?.title
                                ?: "AI 助手",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "会话列表")
                        }
                        IconButton(onClick = { vm.toggleWebVisible() }) {
                            Icon(
                                Icons.Default.Public,
                                contentDescription = if (state.webVisible) "返回聊天" else "显示网页"
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                AndroidView(
                    factory = { context ->
                        WebView(context).also { view ->
                            webView = view
                            vm.onWebViewAttached(view)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (!state.webVisible) {
                    ChatLayer(
                        state = state,
                        hasSelection = hasSelection,
                        input = input,
                        onInputChange = { input = it },
                        onSend = {
                            if (vm.send(input)) input = ""
                        },
                        onStop = { vm.stop() },
                        onShowWeb = { vm.toggleWebVisible() },
                        onInsert = { text -> onApplyResult("insert", text) },
                        onReplace = { text -> onApplyResult("replace", text) },
                        onCopy = { text ->
                            clipboard.setText(AnnotatedString(text))
                            scope.launch { snackbarHostState.showSnackbar("已复制") }
                        },
                        onSaveAsNote = { text -> vm.saveAsNote(text) }
                    )
                } else {
                    SmallFloatingActionButton(
                        onClick = { vm.toggleWebVisible() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = "返回聊天")
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionItem(
    session: AiSessionEntity,
    selected: Boolean,
    deleteEnabled: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        headlineContent = { Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(TimeFormat.dateTime(session.updatedAt)) },
        trailingContent = {
            IconButton(onClick = onDelete, enabled = deleteEnabled) {
                Icon(Icons.Default.Delete, contentDescription = "删除会话")
            }
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun ChatLayer(
    state: AiChatViewModel.UiState,
    hasSelection: Boolean,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onShowWeb: () -> Unit,
    onInsert: (String) -> Unit,
    onReplace: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSaveAsNote: (String) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.sending) {
        val itemCount = state.messages.size +
            if (state.sending || state.streamingText.isNotEmpty()) 1 else 0
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            state.banner?.let { banner ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            banner,
                            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        if (!state.webVisible) {
                            TextButton(onClick = onShowWeb) { Text("显示网页") }
                        }
                    }
                }
            }
            if (state.messages.isEmpty() && !state.sending && state.streamingText.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "有问题随时问我，回答会留档在这篇笔记里",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            hasSelection = hasSelection,
                            onInsert = onInsert,
                            onReplace = onReplace,
                            onCopy = onCopy,
                            onSaveAsNote = onSaveAsNote
                        )
                    }
                    if (state.sending || state.streamingText.isNotEmpty()) {
                        item(key = "streaming") {
                            StreamingBubble(text = state.streamingText, sending = state.sending)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("问点什么…") },
                    maxLines = 5
                )
                FilledIconButton(
                    onClick = { if (state.sending) onStop() else onSend() },
                    enabled = state.sending || input.isNotBlank()
                ) {
                    Icon(
                        if (state.sending) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (state.sending) "停止" else "发送"
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: AiMessageEntity,
    hasSelection: Boolean,
    onInsert: (String) -> Unit,
    onReplace: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSaveAsNote: (String) -> Unit
) {
    val isUser = message.role == AiMessageEntity.ROLE_USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    message.content.ifEmpty { "（没有内容）" },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (message.status != AiMessageEntity.STATUS_DONE) {
                    Text(
                        if (message.status == AiMessageEntity.STATUS_INTERRUPTED) "（未完成）" else "（失败）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (!isUser && message.status == AiMessageEntity.STATUS_DONE) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onInsert(message.content) }) {
                    Text("插入正文", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onReplace(message.content) }, enabled = hasSelection) {
                    Text("替换选中", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onCopy(message.content) }) {
                    Text("复制", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onSaveAsNote(message.content) }) {
                    Text("存为新笔记", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String, sending: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.widthIn(max = 320.dp)
    ) {
        Text(
            text.ifEmpty { if (sending) "正在等待回答…" else "" },
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
