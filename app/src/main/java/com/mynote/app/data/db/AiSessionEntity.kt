package com.mynote.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_sessions",
    foreignKeys = [ForeignKey(
        entity = NoteEntity::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("noteId")]
)
data class AiSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val serviceId: String,
    val title: String,
    /** 旧网页版遗留列（API 模式不再写入）；保留避免 Room 迁移。 */
    val remoteChatId: String?,
    val createdAt: Long,
    val updatedAt: Long
)
