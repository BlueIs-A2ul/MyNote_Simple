package com.mynote.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 索引说明（Room v6）：
 * - `deletedAt`：回收站列表/到期清理（软删除过滤）
 * - `categoryId`：分类 tab 过滤
 * - `pinned, updatedAt`：全部列表默认排序「置顶优先 + 更新时间倒序」
 * - `noteDate`：日历视图按日期范围查询
 */
@Entity(
    tableName = "notes",
    indices = [
        Index("deletedAt"),
        Index("categoryId"),
        Index(value = ["pinned", "updatedAt"]),
        Index("noteDate")
    ]
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val categoryId: Long?,
    val pinned: Boolean,
    val color: Int?,
    /** 软删除时间戳：非空表示在回收站中，到期自动彻底清理。 */
    val deletedAt: Long? = null,
    /** 归属日期（所选日期的本地零点毫秒）；null = 未标记，不出现在日历。 */
    val noteDate: Long? = null
)
