package com.mynote.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 纸片面板：贴底推入的分组操作面板，自绘而非使用 [androidx.compose.material3.ModalBottomSheet]。
 *
 * 与原生底部弹层的差异：直边、无阴影、无拖拽把手与高度档位；用一条「纸边」短横线表示可关闭，
 * 组标题沿用印刷目录式排版（标题 + 右侧延伸 hairline）。遮罩与面板同帧演出，返回键 / 点遮罩关闭。
 */
@Composable
fun PaperActionPanel(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // 位移用可中断的 Animatable：收起途中再次展开能从当前位置续走，而不是跳回起点
    val rise = remember { Animatable(1f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            rise.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow)
            )
        } else {
            rise.animateTo(targetValue = 1f, animationSpec = tween(160))
        }
    }
    BackHandler(enabled = expanded, onBack = onDismissRequest)

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(expanded) {
        if (expanded) {
            // 等这一帧布局完成，节点挂载后再请求焦点；焦点只是无障碍增强，失败不影响面板使用
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(160))
    ) {
        BoxWithConstraints(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            // 遮罩：把页面压暗一层，但不引入阴影；点空白处关闭
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f))
                    .pointerInput(Unit) {
                        detectTapGestures { onDismissRequest() }
                    }
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.85f)
                    .graphicsLayer { translationY = 32.dp.toPx() * rise.value }
                    .background(MaterialTheme.colorScheme.background)
                    .focusRequester(focusRequester)
                    .focusable()
                    .semantics { paneTitle = "更多操作" }
            ) {
                HairlineDivider()
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        // 兜底：搜索态键盘仍在时，面板整体抬到 IME 之上（外层已先尽力收起键盘）
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(bottom = 10.dp)
                ) {
                    PaperPanelGrip()
                    content()
                }
            }
        }
    }
}

/** 面板分组：印刷目录式的小标题 + 向右延伸的 hairline，替代原生弹层的分组留白。 */
@Composable
fun PaperPanelGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            HairlineDivider(Modifier.weight(1f))
        }
        content()
    }
}

/**
 * 面板条目：48dp 行高。
 *
 * [showChevron] 为 true 时行尾画细箭头，表示「去某一页」；就地动作 / 危险操作应传 false，
 * 否则箭头会暗示一次页面跳转，与真实行为不符。
 */
@Composable
fun PaperPanelItem(
    text: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else contentColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 面板单选行（排序）：自绘圆环 + 实心圆点，不用系统 RadioButton，避免原生观感。 */
@Composable
fun PaperPanelChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChoiceDot(selected)
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ChoiceDot(selected: Boolean) {
    val ringColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .size(16.dp)
            .border(1.5.dp, ringColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

/** 纸边短横线：替代原生弹层的拖拽把手，只作「可关闭」的视觉线索。 */
@Composable
private fun PaperPanelGrip() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(width = 28.dp, height = 2.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    shape = CircleShape
                )
        )
    }
}
