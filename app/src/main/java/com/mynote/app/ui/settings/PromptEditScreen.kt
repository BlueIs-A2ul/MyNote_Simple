package com.mynote.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mynote.app.data.ai.AiPromptBuilder
import com.mynote.app.data.settings.AiSettingsStore
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.PaperAlertDialog
import com.mynote.app.ui.components.PaperTopBar

/**
 * 系统提示词编辑页：留空 = 使用内置默认（[AiPromptBuilder.SYSTEM_PROMPT]）。
 *
 * 逐字写入 [AiSettingsStore]（与设置页「接口地址」同一先例），因此没有保存按钮；
 * 改动对下一次提问立即生效，历史消息不受影响。输入框硬性截断到
 * [AiPromptBuilder.MAX_SYSTEM_PROMPT_CHARS]，保证「界面显示」与「已存储」始终一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptEditScreen(aiSettingsStore: AiSettingsStore, onBack: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(aiSettingsStore.customSystemPrompt()) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { PaperTopBar(title = "系统提示词", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "留空即使用内置默认；改动对下一次提问立即生效，不影响已保存的对话记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    // 超出上限的部分直接丢弃：不截断已输入内容，感知明确
                    if (it.length <= AiPromptBuilder.MAX_SYSTEM_PROMPT_CHARS) {
                        text = it
                        aiSettingsStore.setCustomSystemPrompt(it)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                label = { Text("自定义提示词") },
                placeholder = { Text("留空 = 使用下方内置默认") },
                supportingText = {
                    Text("${text.length} / ${AiPromptBuilder.MAX_SYSTEM_PROMPT_CHARS}")
                }
            )
            if (text.isNotEmpty()) {
                TextButton(onClick = { confirmReset = true }) { Text("恢复默认") }
            }
            HairlineDivider(Modifier.padding(top = 4.dp, bottom = 12.dp))
            Text(
                "内置默认（留空时使用）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                AiPromptBuilder.SYSTEM_PROMPT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmReset) {
        PaperAlertDialog(
            onDismissRequest = { confirmReset = false },
            title = "恢复内置默认？",
            text = { Text("当前自定义提示词将被清除，之后使用内置默认提示词。") },
            confirmButton = {
                TextButton(onClick = {
                    text = ""
                    aiSettingsStore.setCustomSystemPrompt("")
                    confirmReset = false
                }) { Text("恢复默认") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } }
        )
    }
}
