package com.mynote.app.data.db

/**
 * 列表行投影：只携带展示所需字段，正文截取前 400 字作摘要，避免列表查询全表携带正文。
 * 搜索/回收站/分类/未分类等全部列表查询均返回它；单条查询（getById 等）仍返回 NoteEntity。
 */
data class NoteListItem(
    val id: Long,
    val title: String,
    val categoryId: Long?,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val summary: String
)
