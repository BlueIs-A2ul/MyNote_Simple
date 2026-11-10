package com.mynote.app.data.db

/** 分类下的可见笔记数（条目 49：删除确认要说明影响面）。 */
data class CategoryNoteCount(
    val categoryId: Long,
    val count: Int
)
