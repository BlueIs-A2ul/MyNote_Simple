package com.mynote.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mynote.app.data.ai.AiProvider
import com.mynote.app.ui.components.TextTabRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 模型 tab 的展示上限；超过改用可搜索弹窗（硅基流动等可达数十个）。 */
private const val MODEL_TABS_MAX = 8

@Composable
internal fun SettingsAiSection(
    vm: SettingsViewModel,
    aiProvider: AiProvider,
    baseUrlInput: String,
    onBaseUrlInputChange: (String) -> Unit,
    customModelInput: String,
    onCustomModelInputChange: (String) -> Unit,
    aiModel: String,
    availableModels: List<String>,
    deepThinking: Boolean,
    apiKeyConfigured: Boolean,
    customSystemPrompt: String,
    apiKeyInput: String,
    onApiKeyInputChange: (String) -> Unit,
    keyVisible: Boolean,
    onKeyVisibleChange: (Boolean) -> Unit,
    onOpenPromptEditor: () -> Unit,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope
) {
    /** 测试连接/查询余额前的输入校验；返回非空表示缺少哪一项。 */
    fun missingInputHint(): String? = when {
        aiProvider == AiProvider.CUSTOM && vm.customBaseUrl.value.isBlank() -> "请先填写接口地址"
        resolveApiKey(apiKeyInput) { vm.savedApiKey() }.isEmpty() -> "请先填写或保存 API Key"
        else -> null
    }

    // ---------- AI 助手 ----------
    Text(
        "AI 助手",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
    Text(
        "服务商",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(6.dp))
    TextTabRow(
        tabs = AiProvider.entries.toList(),
        selected = aiProvider,
        onSelect = { vm.setProvider(it) },
        label = { it.displayName }
    )
    if (aiProvider == AiProvider.CUSTOM) {
        Spacer(Modifier.height(12.dp))
        Text(
            "接口地址",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        val baseUrlInvalid = !isBaseUrlInputValid(baseUrlInput)
        OutlinedTextField(
            value = baseUrlInput,
            onValueChange = {
                onBaseUrlInputChange(it)
                if (isBaseUrlInputValid(it)) vm.setCustomBaseUrl(it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = baseUrlInvalid,
            placeholder = { Text("https://example.com/v1") }
        )
        // 条目 86：明文 http 在 Android 9+ 会被系统拦截，保存前就提示，别等发送时才报「网络错误」
        val cleartextHint = baseUrlInput.trim().startsWith("http://")
        Text(
            when {
                baseUrlInvalid -> "地址需以 http:// 或 https:// 开头"
                cleartextHint -> "明文 http 会被 Android 9+ 拦截，建议改用 https（局域网自建需自签证书或反代）"
                else -> "需包含版本路径（如 /v1），请求将直连该地址"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when {
                baseUrlInvalid -> MaterialTheme.colorScheme.error
                cleartextHint -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 6.dp)
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "模型",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(6.dp))
    if (aiProvider.freeModelInput) {
        OutlinedTextField(
            value = customModelInput,
            onValueChange = {
                onCustomModelInputChange(it)
                vm.setAiModel(it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("例如 gpt-4o-mini") }
        )
    } else if (availableModels.size <= MODEL_TABS_MAX) {
        TextTabRow(
            tabs = availableModels,
            selected = aiModel,
            onSelect = { vm.setAiModel(it) },
            label = { it }
        )
    } else {
        var modelPickerOpen by rememberSaveable { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                aiModel.ifBlank { "未选择模型" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { modelPickerOpen = true }) { Text("选择模型") }
        }
        if (modelPickerOpen) {
            ModelPickerDialog(
                models = availableModels,
                selected = aiModel,
                onSelect = {
                    vm.setAiModel(it)
                    modelPickerOpen = false
                },
                onDismiss = { modelPickerOpen = false }
            )
        }
    }
    Text(
        aiProvider.modelHint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
    if (aiProvider.supportsThinking) {
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
    }
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPromptEditor() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "系统提示词",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                customSystemPrompt.ifBlank { "内置默认" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = { onOpenPromptEditor() }) { Text("编辑") }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "API Key",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = apiKeyInput,
        onValueChange = { onApiKeyInputChange(it) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text(if (apiKeyConfigured) "已保存（输入新 Key 可替换）" else aiProvider.apiKeyPlaceholder)
        },
        visualTransformation = if (keyVisible) VisualTransformation.None
        else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { onKeyVisibleChange(!keyVisible) }) {
                Icon(
                    if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (keyVisible) "隐藏 Key" else "显示 Key"
                )
            }
        }
    )
    Text(
        "Key 仅保存在本机（Keystore 加密），不会上传；" + aiProvider.keyHint,
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
                onApiKeyInputChange("")
                scope.launch { snackbarHostState.showSnackbar("已保存") }
            } else {
                scope.launch { snackbarHostState.showSnackbar("保存失败：系统密钥库不可用") }
            }
        }) { Text("保存") }
        if (apiKeyConfigured) {
            TextButton(onClick = {
                vm.clearApiKey()
                onApiKeyInputChange("")
                scope.launch { snackbarHostState.showSnackbar("已清除 API Key") }
            }) { Text("清除") }
        }
        TextButton(onClick = {
            scope.launch {
                val hint = missingInputHint()
                if (hint != null) {
                    snackbarHostState.showSnackbar(hint)
                } else {
                    // 条目 88：测试连接同时保存输入框里的 Key，
                    // 否则「测试成功」后进聊天仍提示未配置
                    val typed = apiKeyInput.trim()
                    if (typed.isNotEmpty() && vm.saveApiKey(typed)) {
                        onApiKeyInputChange("")
                    }
                    val key = resolveApiKey(apiKeyInput) { vm.savedApiKey() }
                    snackbarHostState.showSnackbar(vm.testConnection(key))
                }
            }
        }) { Text("测试连接") }
        if (aiProvider.supportsBalance) {
            TextButton(onClick = {
                scope.launch {
                    val hint = missingInputHint()
                    if (hint != null) {
                        snackbarHostState.showSnackbar(hint)
                    } else {
                        val key = resolveApiKey(apiKeyInput) { vm.savedApiKey() }
                        snackbarHostState.showSnackbar(vm.queryBalance(key))
                    }
                }
            }) { Text("查询余额") }
        }
    }

}

/** 取待用 API Key：优先输入框内容，为空回退已保存的 Key。 */
private fun resolveApiKey(input: String, saved: () -> String?): String =
    input.trim().ifEmpty { saved().orEmpty() }

/** 自定义接口地址输入是否可接受：未填写（等价清除）或 http(s) 开头。 */
private fun isBaseUrlInputValid(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.isEmpty() || trimmed.startsWith("http://") || trimmed.startsWith("https://")
}
