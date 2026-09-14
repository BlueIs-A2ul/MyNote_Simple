package com.mynote.app.ui.notes

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