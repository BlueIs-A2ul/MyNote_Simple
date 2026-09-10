package com.mynote.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteRevisionDao {

    companion object {
        const val MAX_PER_NOTE = 50
        const val WARN_AT = 40
    }

    @Query("SELECT * FROM note_revisions WHERE noteId = :noteId ORDER BY savedAt DESC, id DESC")
    fun observeByNote(noteId: Long): Flow<List<NoteRevisionEntity>>

    @Query("SELECT * FROM note_revisions WHERE noteId = :noteId ORDER BY savedAt DESC, id DESC")
    suspend fun getByNote(noteId: Long): List<NoteRevisionEntity>

    @Query("SELECT * FROM note_revisions WHERE id = :id")
    suspend fun getById(id: Long): NoteRevisionEntity?

    @Query("SELECT COUNT(*) FROM note_revisions WHERE noteId = :noteId")
    suspend fun countByNote(noteId: Long): Int

    @Insert
    suspend fun insert(revision: NoteRevisionEntity): Long

    @Query(
        "DELETE FROM note_revisions WHERE noteId = :noteId AND id NOT IN " +
            "(SELECT id FROM note_revisions WHERE noteId = :noteId ORDER BY savedAt DESC, id DESC LIMIT :keep)"
    )
    suspend fun trimTo(noteId: Long, keep: Int): Int

    @Query("SELECT content FROM note_revisions")
    suspend fun getAllContents(): List<String>
}
