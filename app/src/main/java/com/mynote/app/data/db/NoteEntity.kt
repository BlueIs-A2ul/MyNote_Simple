package com.mynote.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
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
    val deletedAt: Long? = null
)
