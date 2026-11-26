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

// ---------- 持久化（记忆上次选择） ----------

/**
 * token 前缀：分类用 `category:<id>`，id 只接受正数。
 *
 * 这些字面量会被写进 SharedPreferences，**改格式等于让老用户丢记忆**，
 * 因此由 CategoryFilterCodecTest 锁住具体取值。
 */
private const val TOKEN_ALL = "all"
private const val TOKEN_UNCATEGORIZED = "uncategorized"
private const val TOKEN_CATEGORY_PREFIX = "category:"

/** 编成存储用的短字符串（见设计文档「存储设计」）。 */
internal fun CategoryFilter.encodeToken(): String = when (this) {
    CategoryFilter.All -> TOKEN_ALL
    CategoryFilter.Uncategorized -> TOKEN_UNCATEGORIZED
    is CategoryFilter.Single -> TOKEN_CATEGORY_PREFIX + categoryId
}

/** 记住的筛选是否已指向不存在的分类（启动时据此静默回落「全部」）。 */
internal fun CategoryFilter.isMissingFrom(categories: List<CategoryEntity>): Boolean =
    this is CategoryFilter.Single && categories.none { it.id == categoryId }

/**
 * 解码存储值。null、空串、未知前缀、非法或非正 id 一律回落「全部」——
 * 脏值绝不能把主页卡在一个不存在的分类上。
 */
internal fun decodeCategoryFilter(token: String?): CategoryFilter = when {
    token == null -> CategoryFilter.All
    token == TOKEN_ALL -> CategoryFilter.All
    token == TOKEN_UNCATEGORIZED -> CategoryFilter.Uncategorized
    token.startsWith(TOKEN_CATEGORY_PREFIX) ->
        token.removePrefix(TOKEN_CATEGORY_PREFIX).toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { CategoryFilter.Single(it) }
            ?: CategoryFilter.All
    else -> CategoryFilter.All
}
