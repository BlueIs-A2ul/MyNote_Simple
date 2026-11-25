package com.mynote.app.ui.notes

import com.mynote.app.data.db.CategoryEntity

/**
 * 主页分类筛选条件。
 *
 * 「全部」= 不筛选（仅此模式支持排序切换）；「未分类」= `categoryId IS NULL`
 * 的笔记（新建未选分类、或所属分类被删除后自动置空）；「某个分类」= 按分类 id 过滤。
 */
sealed interface CategoryFilter {

    /** 全部笔记，不按分类过滤。 */
    data object All : CategoryFilter

    /** 未分类：categoryId 为空的笔记。 */
    data object Uncategorized : CategoryFilter

    /** 某个具体分类。 */
    data class Single(val categoryId: Long) : CategoryFilter
}

/** 分类抽屉条目文案（「全部」「未分类」、分类名）。分类已不存在时兜底「未知分类」。 */
fun CategoryFilter.menuLabel(categories: List<CategoryEntity>): String = when (this) {
    CategoryFilter.All -> "全部"
    CategoryFilter.Uncategorized -> "未分类"
    is CategoryFilter.Single -> categories.firstOrNull { it.id == categoryId }?.name ?: "未知分类"
}

/** 顶栏副标题文案：比抽屉条目更完整，避免单独的「全部」被误读成开关。 */
fun CategoryFilter.summaryLabel(categories: List<CategoryEntity>): String = when (this) {
    CategoryFilter.All -> "全部笔记"
    CategoryFilter.Uncategorized -> "未分类"
    is CategoryFilter.Single -> categories.firstOrNull { it.id == categoryId }?.name ?: "未知分类"
}
