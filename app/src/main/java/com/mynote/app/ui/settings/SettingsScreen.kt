package com.mynote.app.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.BuildConfig
import com.mynote.app.data.ai.DeepSeekApiClient
import com.mynote.app.data.ai.DeepSeekModels
import com.mynote.app.data.db.NoteSortMode
import com.mynote.app.data.settings.AiSettingsStore
import com.mynote.app.data.settings.DarkMode
import com.mynote.app.data.settings.NoteSortStore
import com.mynote.app.data.settings.ThemeSettingsStore
import com.mynote.app.data.settings.TrashRetentionStore
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.components.TextTabRow
import com.mynote.app.ui.theme.ThemePresets
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeStore: ThemeSettingsStore,
    sortStore: NoteSortStore,
    trashStore: TrashRetentionStore,
    aiSettingsStore: AiSettingsStore,
    apiClient: DeepSeekApiClient,
    onBack: () -> Unit
) {
    val vm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(themeStore, sortStore, trashStore, aiSettingsStore, apiClient)
    )
    val settings by vm.settings.collectAsState()
    val defaultSort by vm.defaultSort.collectAsState()
    val retentionDays by vm.retentionDays.collectAsState()
    val aiModel by vm.aiModel.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val deepThinking by vm.deepThinking.collectAsState()
    val apiKeyConfigured by vm.apiKeyConfigured.collectAsState()
    var apiKeyInput by rememberSaveable { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { PaperTopBar(title = "设置", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
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
                label = { sortModeLabel(it) }
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
                onSelect = { vm.setRetentionDays(it) },
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

            // ---------- AI 助手 ----------
            Text(
                "AI 助手",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
            Text(
                "模型",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            TextTabRow(
                tabs = availableModels,
                selected = aiModel,
                onSelect = { vm.setAiModel(it) },
                label = { DeepSeekModels.label(it) }
            )
            Text(
                "deepseek-flash 更快更省；deepseek-v4-pro 更强。测试连接成功后会更新为官方最新模型列表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = deepThinking,
                        onValueChange = { vm.setDeepThinking(it) },
                        role = Role.Switch
                    )
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "深度思考",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "回答更严谨但更慢，思维链按输出计费",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = deepThinking, onCheckedChange = null)
            }
            Text(
                "API Key",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(if (apiKeyConfigured) "已保存（输入新 Key 可替换）" else "sk-…")
                },
                visualTransformation = if (keyVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (keyVisible) "隐藏 Key" else "显示 Key"
                        )
                    }
                }
            )
            Text(
                "Key 仅保存在本机（Keystore 加密），不会上传；在 DeepSeek 开放平台创建。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val key = apiKeyInput.trim()
                    if (key.isEmpty()) {
                        scope.launch { snackbarHostState.showSnackbar("请先输入 API Key") }
                    } else if (vm.saveApiKey(key)) {
                        apiKeyInput = ""
                        scope.launch { snackbarHostState.showSnackbar("已保存") }
                    } else {
                        scope.launch { snackbarHostState.showSnackbar("保存失败：系统密钥库不可用") }
                    }
                }) { Text("保存") }
                if (apiKeyConfigured) {
                    TextButton(onClick = {
                        vm.clearApiKey()
                        apiKeyInput = ""
                        scope.launch { snackbarHostState.showSnackbar("已清除 API Key") }
                    }) { Text("清除") }
                }
                TextButton(onClick = {
                    scope.launch {
                        val key = resolveApiKey(apiKeyInput) { vm.savedApiKey() }
                        if (key.isEmpty()) {
                            snackbarHostState.showSnackbar("请先填写或保存 API Key")
                        } else {
                            snackbarHostState.showSnackbar(vm.testConnection(key))
                        }
                    }
                }) { Text("测试连接") }
                TextButton(onClick = {
                    scope.launch {
                        val key = resolveApiKey(apiKeyInput) { vm.savedApiKey() }
                        if (key.isEmpty()) {
                            snackbarHostState.showSnackbar("请先填写或保存 API Key")
                        } else {
                            snackbarHostState.showSnackbar(vm.queryBalance(key))
                        }
                    }
                }) { Text("查询余额") }
            }
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

private fun darkModeLabel(mode: DarkMode): String = when (mode) {
    DarkMode.SYSTEM -> "跟随系统"
    DarkMode.LIGHT -> "浅色"
    DarkMode.DARK -> "深色"
}

private fun sortModeLabel(mode: NoteSortMode): String = when (mode) {
    NoteSortMode.UPDATED_DESC -> "最近更新"
    NoteSortMode.CREATED_DESC -> "最新创建"
    NoteSortMode.TITLE_ASC -> "按标题"
}

/** 取待用 API Key：优先输入框内容，为空回退已保存的 Key。 */
private fun resolveApiKey(input: String, saved: () -> String?): String =
    input.trim().ifEmpty { saved().orEmpty() }
