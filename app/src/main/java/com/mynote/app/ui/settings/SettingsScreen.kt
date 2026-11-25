package com.mynote.app.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.BuildConfig
import com.mynote.app.data.ai.AiApiClient
import com.mynote.app.data.ai.AiEndpoint
import com.mynote.app.data.ai.AiProbe
import com.mynote.app.data.ai.AiProvider
import com.mynote.app.data.db.NoteSortMode
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.AiSettingsStore
import com.mynote.app.data.settings.DarkMode
import com.mynote.app.data.settings.NoteSortStore
import com.mynote.app.data.settings.ThemeSettingsStore
import com.mynote.app.data.settings.TrashRetentionStore
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.components.TextTabRow
import com.mynote.app.ui.notes.sortLabel
import com.mynote.app.ui.theme.ThemePresets
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeStore: ThemeSettingsStore,
    sortStore: NoteSortStore,
    trashStore: TrashRetentionStore,
    repository: NoteRepository,
    aiSettingsStore: AiSettingsStore,
    clientFactory: (AiEndpoint) -> AiProbe = { AiApiClient(it) },
    onOpenPromptEditor: () -> Unit,
    onBack: () -> Unit
) {
    val vm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(
            themeStore, sortStore, trashStore, repository, aiSettingsStore, clientFactory
        )
    )
    val settings by vm.settings.collectAsStateWithLifecycle()
    val defaultSort by vm.defaultSort.collectAsStateWithLifecycle()
    val retentionDays by vm.retentionDays.collectAsStateWithLifecycle()
    val aiProvider by vm.aiProvider.collectAsStateWithLifecycle()
    val customBaseUrl by vm.customBaseUrl.collectAsStateWithLifecycle()
    val aiModel by vm.aiModel.collectAsStateWithLifecycle()
    val availableModels by vm.availableModels.collectAsStateWithLifecycle()
    val deepThinking by vm.deepThinking.collectAsStateWithLifecycle()
    val apiKeyConfigured by vm.apiKeyConfigured.collectAsStateWithLifecycle()
    val customSystemPrompt by vm.customSystemPrompt.collectAsStateWithLifecycle()
    // 条目 77：明文 Key 不进 saved instance state。rememberSaveable 会把用户刚粘贴、尚未保存的 Key
    // 序列化进 Activity 的 Bundle（系统进程持有且可能落盘），与本页「Key 仅本机加密保存」的承诺相悖；
    // 旋转后清空输入框可接受，已保存的 Key 不受影响。
    // 条目 88：输入框按服务商隔离，避免切 tab 后把硅基流动的 Key 存给 DeepSeek
    var apiKeyInput by remember(aiProvider) { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var baseUrlInput by rememberSaveable { mutableStateOf(customBaseUrl) }
    var customModelInput by rememberSaveable { mutableStateOf(aiModel) }
    // 条目 72：缩短保留期前的影响评估（days × 将被立即永久删除的条数）
    var retentionPrompt by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 从提示词编辑页返回时重读摘要（提示词为全局设置，不随服务商变化）
    LaunchedEffect(Unit) { vm.refreshSystemPrompt() }

    // 切换服务商时把自定义地址/模型输入框同步为该服务商的持久化值
    LaunchedEffect(aiProvider) {
        baseUrlInput = vm.customBaseUrl.value
        customModelInput = vm.aiModel.value
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { PaperTopBar(title = "设置", onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            // 设置页内容较长（主题色板 + 通用 + AI 全部分区）：分块组合后首帧只组合可视区，
            // AI 分区滚到才组合，避免进页面时一次性建满整棵子树。
            item(key = "appearance") {
                Text(
                    "外观",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                TextTabRow(
                    tabs = DarkMode.entries.toList(),
                    selected = settings.darkMode,
                    onSelect = { vm.setDarkMode(it) },
                    label = { darkModeLabel(it) }
                )
                Spacer(Modifier.height(16.dp))
                HairlineDivider()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = settings.dynamicColor,
                                onValueChange = { vm.setDynamicColor(it) },
                                role = Role.Switch
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "动态取色",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                "跟随系统壁纸自动配色",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = settings.dynamicColor, onCheckedChange = null)
                    }
                    HairlineDivider()
                }

                Text(
                    "主题色",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
                )
                val selectedColorIndex =
                    settings.themeColorIndex.takeIf { it in ThemePresets.all.indices } ?: 0
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ThemePresets.all.forEachIndexed { index, preset ->
                        val selected = index == selectedColorIndex
                        val dynamicOn = settings.dynamicColor
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                // 动态取色开启时色板降透明，表明实际生效的是系统壁纸取色
                                .background(
                                    preset.light.primary.copy(alpha = if (dynamicOn) 0.4f else 1f),
                                    CircleShape
                                )
                                .border(
                                    width = if (selected && !dynamicOn) 1.5.dp else 1.dp,
                                    color = if (selected && !dynamicOn) MaterialTheme.colorScheme.onBackground
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                                .semantics { contentDescription = preset.label }
                                .clip(CircleShape)
                                .selectable(
                                    selected = selected,
                                    onClick = {
                                        if (settings.dynamicColor) {
                                            vm.setDynamicColor(false)
                                            scope.launch {
                                                snackbarHostState.showSnackbar("已切换到手动取色")
                                            }
                                        }
                                        vm.setThemeColorIndex(index)
                                    },
                                    role = Role.RadioButton
                                )
                        )
                    }
                }
                if (settings.dynamicColor) {
                    Text(
                        "正在使用系统壁纸取色；点选上方颜色可切换到手动取色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                Spacer(Modifier.height(24.dp))
                HairlineDivider()
            }
            item(key = "general") {
                // ---------- 通用 ----------
                Text(
                    "通用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                TextTabRow(
                    tabs = NoteSortMode.entries.toList(),
                    selected = defaultSort,
                    onSelect = { vm.setDefaultSort(it) },
                    label = { it.sortLabel() }
                )
                Text(
                    "列表默认排序（主页 ⋮ 菜单可临时切换）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "回收站保留期",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(6.dp))
                TextTabRow(
                    tabs = TrashRetentionStore.OPTIONS.toList(),
                    selected = retentionDays,
                    onSelect = { days ->
                        if (days >= retentionDays) {
                            // 延长保留期不会删任何东西：直接生效
                            vm.setRetentionDays(days)
                        } else {
                            // 缩短保留期会立即永久删除超期笔记：先算影响条数再确认（条目 72）
                            scope.launch {
                                val expiring = vm.expiringCount(days)
                                if (expiring > 0) retentionPrompt = days to expiring else vm.setRetentionDays(days)
                            }
                        }
                    },
                    label = { "$it 天" }
                )
                Text(
                    "到期后自动清理，期间可在回收站恢复",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(24.dp))
                HairlineDivider()
            }
            item(key = "ai") {
                // ---------- AI 助手 ----------
                SettingsAiSection(
                    vm = vm,
                    aiProvider = aiProvider,
                    baseUrlInput = baseUrlInput,
                    onBaseUrlInputChange = { baseUrlInput = it },
                    customModelInput = customModelInput,
                    onCustomModelInputChange = { customModelInput = it },
                    aiModel = aiModel,
                    availableModels = availableModels,
                    deepThinking = deepThinking,
                    apiKeyConfigured = apiKeyConfigured,
                    customSystemPrompt = customSystemPrompt,
                    apiKeyInput = apiKeyInput,
                    onApiKeyInputChange = { apiKeyInput = it },
                    keyVisible = keyVisible,
                    onKeyVisibleChange = { keyVisible = it },
                    onOpenPromptEditor = onOpenPromptEditor,
                    snackbarHostState = snackbarHostState,
                    scope = scope
                )
            }
            item(key = "footer") {
                Spacer(Modifier.height(24.dp))
                HairlineDivider()
                Spacer(Modifier.height(24.dp))
                Text(
                    "MyNote ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                )
            }
        }
    }

    // 条目 72：缩短保留期会立即永久删除超期笔记，必须先确认并说明影响条数。
    // 确认框放在列表外：放进 item 会随该条目滚出视口一起被销毁
    retentionPrompt?.let { (days, expiring) ->
        AlertDialog(
            onDismissRequest = { retentionPrompt = null },
            title = { Text("缩短回收站保留期？") },
            text = {
                Text(
                    "改为 $days 天后，回收站中已有 $expiring 条笔记会立即被永久删除" +
                        "（含历史版本与内嵌图片），不可恢复。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        retentionPrompt = null
                        scope.launch {
                            val purged = vm.applyRetentionDays(days)
                            snackbarHostState.showSnackbar("已清理 $purged 条")
                        }
                    }
                ) { Text("缩短并清理") }
            },
            dismissButton = {
                TextButton(onClick = { retentionPrompt = null }) { Text("取消") }
            }
        )
    }
}

private fun darkModeLabel(mode: DarkMode): String = when (mode) {
    DarkMode.SYSTEM -> "跟随系统"
    DarkMode.LIGHT -> "浅色"
    DarkMode.DARK -> "深色"
}

