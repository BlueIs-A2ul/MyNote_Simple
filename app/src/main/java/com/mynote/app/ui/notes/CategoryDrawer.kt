package com.mynote.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.ui.components.CategoryDot
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.theme.PaperPalette

/**
 * 侧边栏条目的纯数据模型：顺序、文案与选中标记都在 [categoryDrawerEntries] 里算好，
 * 便于单测，也避免 Composable 内重复做 O(n) 查找。
 */
internal data class CategoryDrawerEntry(
    val filter: CategoryFilter,
    val label: String,
    /** 分类色（ARGB）；「全部」「未分类」没有色点，为 null。 */
    val colorArgb: Int?,
    val selected: Boolean
)

/** 「全部」「未分类」固定置顶，其后按仓库返回顺序列出各分类。 */
internal fun categoryDrawerEntries(
    categories: List<CategoryEntity>,
    selected: CategoryFilter
): List<CategoryDrawerEntry> = buildList {
    add(
        CategoryDrawerEntry(
            filter = CategoryFilter.All,
            label = CategoryFilter.All.menuLabel(categories),
            colorArgb = null,
            selected = selected == CategoryFilter.All
        )
    )
    add(
        CategoryDrawerEntry(
            filter = CategoryFilter.Uncategorized,
            label = CategoryFilter.Uncategorized.menuLabel(categories),
            colorArgb = null,
            selected = selected == CategoryFilter.Uncategorized
        )
    )
    categories.forEach { category ->
        val filter = CategoryFilter.Single(category.id)
        add(
            CategoryDrawerEntry(
                filter = filter,
                label = category.name,
                colorArgb = category.color,
                selected = selected == filter
            )
        )
    }
}

/**
 * 分类侧边栏内容（无业务状态）：负责浏览与切换分类，并保留「分类管理」入口。
 *
 * @param onSelect 选中某条筛选；调用方负责应用筛选并收起抽屉。
 * @param onManageCategories 进入分类管理页。
 * @param onClose 收起抽屉（顶部关闭按钮）。
 */
@Composable
internal fun CategoryDrawerContent(
    categories: List<CategoryEntity>,
    selected: CategoryFilter,
    onSelect: (CategoryFilter) -> Unit,
    onManageCategories: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entries = remember(categories, selected) { categoryDrawerEntries(categories, selected) }
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "分类",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "按分类查看笔记",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "关闭分类侧边栏",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HairlineDivider()

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
        ) {
            items(entries.take(2), key = { "fixed-${it.filter}" }) { entry ->
                CategoryDrawerRow(entry = entry, onClick = { onSelect(entry.filter) })
            }
            item(key = "section-title") {
                Text(
                    text = "我的分类",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp)
                )
            }
            if (categories.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "还没有分类",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = onManageCategories,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("去创建", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            } else {
                items(entries.drop(2), key = { "category-${it.filter}" }) { entry ->
                    CategoryDrawerRow(entry = entry, onClick = { onSelect(entry.filter) })
                }
            }
        }

        HairlineDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onManageCategories)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "分类管理",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CategoryDrawerRow(
    entry: CategoryDrawerEntry,
    onClick: () -> Unit
) {
    val selected = entry.selected
    // 用主题色低透明度铺底而不是 secondaryContainer：纸感更轻，
    // 且动态取色时深色模式的容器色可能偏亮，浅色高亮块会过重。
    val background = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        Color.Transparent
    }
    // 文字保持 onSurface：选中态靠底色与对勾表达，避免文字比未选中项更暗
    val contentColor = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 有色点的分类与无圆点的「全部 / 未分类」共用同一文字起点
        if (entry.colorArgb != null) {
            CategoryDot(PaperPalette.nearest(entry.colorArgb))
            Spacer(Modifier.width(10.dp))
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
