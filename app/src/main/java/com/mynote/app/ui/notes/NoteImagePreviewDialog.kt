package com.mynote.app.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File

/**
 * 图片全屏预览对话框。
 *
 * 背景固定纯黑（不随深色模式变化，与项目"导出图片固定纯白"风格一致），
 * 点击图片任意处或按系统返回键关闭。
 */
@Composable
fun NoteImagePreviewDialog(file: File, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 系统返回键关闭预览
        BackHandler { onDismiss() }
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
            model = file,
            contentDescription = "图片预览",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
            )
            // 条目 99：给读屏/不便点图的人一个明确的关闭入口
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Text("关闭", color = Color.White)
            }
        }
    }
}
