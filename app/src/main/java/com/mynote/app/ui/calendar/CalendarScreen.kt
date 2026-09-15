package com.mynote.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.ui.components.EmptyState
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.NoteRow
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.theme.PaperPalette
import com.mynote.app.util.CalendarDates
import com.mynote.app.util.TimeFormat
import kotlinx.coroutines.delay

/**
 * 日历页（只读）：月网格 + 选中日笔记列表。
 * 有笔记的日子带小圆点；点日期刷新下方列表，点笔记进编辑页；「今天」回到当前月与今天。
 */
@Composable
fun CalendarScreen(
    repository: NoteRepository,
    onOpenNote: (Long) -> Unit,
    onBack: () -> Unit
) {
    val vm: CalendarViewModel = viewModel(factory = CalendarViewModel.factory(repository))
    val year by vm.year.collectAsState()
    val month by vm.month.collectAsState()
    val selectedDay by vm.selectedDay.collectAsState()
    val marks by vm.marks.collectAsState()
    val dayNotes by vm.dayNotes.collectAsState()
    val categories by vm.categories.collectAsState()

    // 行内相对时间每分钟 tick（与列表页一致）
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    val today = remember(now) { CalendarDates.dayStart(now) }
    val grid = remember(year, month) { CalendarDates.monthGrid(year, month) }
    val categoriesById = remember(categories) { categories.associateBy { it.id } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PaperTopBar(
                title = TimeFormat.monthLabel(year, month),
                onBack = onBack,
                actions = {
                    TextButton(onClick = { vm.goToToday() }) { Text("今天") }
                    IconButton(onClick = { vm.prevMonth() }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "上个月")
                    }
                    IconButton(onClick = { vm.nextMonth() }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "下个月")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item(key = "weekday") { WeekdayHeader() }
            item(key = "grid") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    grid.chunked(CalendarDates.DAYS_PER_WEEK).forEach { week ->
                        Row(Modifier.fillMaxWidth()) {
                            week.forEach { day ->
                                DayCell(
                                    day = day,
                                    selected = day != null && day == selectedDay,
                                    isToday = day != null && day == today,
                                    hasNotes = day != null && marks.containsKey(day),
                                    onClick = { day?.let(vm::selectDay) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            item(key = "divider") { HairlineDivider() }
            item(key = "header") { DaySectionHeader(selectedDay, dayNotes?.size) }
            when {
                dayNotes == null -> item(key = "loading") { LoadingPlaceholder() }
                dayNotes!!.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        icon = Icons.Outlined.Description,
                        text = "这天还没有笔记",
                        hint = "在编辑页底部给笔记添加日期后会出现在这里",
                        modifier = Modifier.height(220.dp)
                    )
                }
                else -> {
                    val notes = dayNotes!!
                    items(notes, key = { it.id }) { item ->
                        NoteRow(
                            item = item,
                            categoryColor = categoriesById[item.categoryId]
                                ?.let { PaperPalette.nearest(it.color) },
                            onClick = { onOpenNote(item.id) },
                            now = now,
                            categoryName = categoriesById[item.categoryId]?.name
                        )
                        HairlineDivider()
                    }
                }
            }
        }
    }
}

/** 周标题：固定周一起始，不随系统区域变化。 */
@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 月网格单元：今天描边、选中填充、有笔记带小圆点；非本月留白。 */
@Composable
private fun DayCell(
    day: Long?,
    selected: Boolean,
    isToday: Boolean,
    hasNotes: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.height(44.dp), contentAlignment = Alignment.Center) {
        if (day == null) return@Box
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .then(
                    if (isToday && !selected) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    }
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = CalendarDates.dayOfMonth(day).toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onBackground
            )
            if (hasNotes) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.primary
                        )
                )
            }
        }
    }
}

/** 选中日区段头：9月10日 · 3 篇（加载中时不显示篇数）。 */
@Composable
private fun DaySectionHeader(day: Long, count: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = TimeFormat.dayLabel(day),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (count != null) {
            Text(
                text = " · $count 篇",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LoadingPlaceholder() {
    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}
