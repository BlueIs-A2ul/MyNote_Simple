package com.mynote.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 笔记列表排序方式。 */
enum class NoteSortMode { UPDATED_DESC, CREATED_DESC, TITLE_ASC }

/**
 * 列表类查询（全部/搜索/分类/未分类/回收站）一律走 [NoteListItem] 投影：
 * 只取展示字段 + `substr(content, 1, 400) AS summary`，避免 Flow 每次写表整表重发全量正文。
 * 单条查询（observeById/getById/getByIds/getAll）仍返回 [NoteEntity] 全字段。
 */
@Dao
interface NoteDao {

    @Query("SELECT id, title, categoryId, pinned, createdAt, updatedAt, deletedAt, substr(content, 1, 400) AS summary FROM notes WHERE deletedAt IS NULL ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<NoteListItem>>

    /** 按指定排序方式观察全部笔记。 */
    fun observeAllBySort(mode: NoteSortMode): Flow<List<NoteListItem>> = when (mode) {
        NoteSortMode.UPDATED_DESC -> observeAll()
        NoteSortMode.CREATED_DESC -> observeAllByCreated()
        NoteSortMode.TITLE_ASC -> observeAllByTitle()
    }

    @Query("SELECT id, title, categoryId, pinned, createdAt, updatedAt, deletedAt, substr(content, 1, 400) AS summary FROM notes WHERE deletedAt IS NULL ORDER BY pinned DESC, createdAt DESC")
    fun observeAllByCreated(): Flow<List<NoteListItem>>

    @Query("SELECT id, title, categoryId, pinned, createdAt, updatedAt, deletedAt, substr(content, 1, 400) AS summary FROM notes WHERE deletedAt IS NULL ORDER BY pinned DESC, title COLLATE NOCASE ASC")
    fun observeAllByTitle(): Flow<List<NoteListItem>>

    /** 回收站列表：已软删除的笔记，按删除时间倒序。 */
    @Query("SELECT id, title, categoryId, pinned, createdAt, updatedAt, deletedAt, substr(content, 1, 400) AS summary FROM notes WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(): Flow<List<NoteListItem>>

    /** 日历：按日期范围取笔记（含起不含止），排序与主列表一致（月/日视图共用）。 */
    @Query("SELECT id, title, categoryId, pinned, createdAt, updatedAt, deletedAt, substr(content, 1, 400) AS summary FROM notes WHERE deletedAt IS NULL AND noteDate >= :startInclusive AND noteDate < :endExclusive ORDER BY pinned DESC, updatedAt DESC")
    fun observeByDateRange(startInclusive: Long, endExclusive: Long): Flow<List<NoteListItem>>

    /** 日历圆点：范围内每天各有多少篇已标记日期的笔记。 */
    @Query("SELECT noteDate, COUNT(*) AS count FROM notes WHERE deletedAt IS NULL AND noteDate IS NOT NULL AND noteDate >= :startInclusive AND noteDate < :endExclusive GROUP BY noteDate")
    fun observeDateMarks(startInclusive: Long, endExclusive: Long): Flow<List<DateMark>>

    /** 回收站中删除时间早于截止时刻的笔记（到期清理用，SQL 过滤避免全表读正文）。 */
    @Query("SELECT * FROM notes WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun getDeletedBefore(cutoff: Long): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: Long): Flow<NoteEntity?>

    @Query("SELECT id, title, categoryId, pinned, createdAt, updatedAt, deletedAt, substr(content, 1, 400) AS summary FROM notes WHERE deletedAt IS NULL AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') ORDER BY pinned DESC, updatedAt DESC")
    fun search(query: String): Flow<List<NoteListItem>>

    @Query("SELECT id, title, categoryId, pinned, createdAt, updatedAt, deletedAt, substr(content, 1, 400) AS summary FROM notes WHERE deletedAt IS NULL AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') ORDER BY pinned DESC, createdAt DESC")
    fun searchByCreated(query: String): Flow<List<NoteListItem>>

    @Query("SELECT id, title, categoryId, pinned, createdAt, updatedAt, deletedAt, substr(content, 1, 400) AS summary FROM notes WHERE deletedAt IS NULL AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') ORDER BY pinned DESC, title COLLATE NOCASE ASC")
    fun searchByTitle(query: String): Flow<List<NoteListItem>>

    /** 按排序方式搜索。 */
    fun search(query: String, mode: NoteSortMode): Flow<List<NoteListItem>> = when (mode) {
        NoteSortMode.UPDATED_DESC -> search(query)
        NoteSortMode.CREATED_DESC -> searchByCreated(query)
        NoteSortMode.TITLE_ASC -> searchByTitle(query)
    }

    @Query("SELECT id, title, categoryId, pinned, createdAt, updatedAt, deletedAt, substr(content, 1, 400) AS summary FROM notes WHERE deletedAt IS NULL AND categoryId = :categoryId ORDER BY pinned DESC, updatedAt DESC")
    fun observeByCategory(categoryId: Long): Flow<List<NoteListItem>>

    @Query("SELECT id, title, categoryId, pinned, createdAt, updatedAt, deletedAt, substr(content, 1, 400) AS summary FROM notes WHERE deletedAt IS NULL AND categoryId = :categoryId ORDER BY pinned DESC, createdAt DESC")
    fun observeByCategoryByCreated(categoryId: Long): Flow<List<NoteListItem>>

    @Query("SELECT id, title, categoryId, pinned, createdAt, updatedAt, deletedAt, substr(content, 1, 400) AS summary FROM notes WHERE deletedAt IS NULL AND categoryId = :categoryId ORDER BY pinned DESC, title COLLATE NOCASE ASC")
    fun observeByCategoryByTitle(categoryId: Long): Flow<List<NoteListItem>>

    /** 按排序方式观察某分类笔记。 */
    fun observeByCategory(categoryId: Long, mode: NoteSortMode): Flow<List<NoteListItem>> = when (mode) {
        NoteSortMode.UPDATED_DESC -> observeByCategory(categoryId)
        NoteSortMode.CREATED_DESC -> observeByCategoryByCreated(categoryId)
        NoteSortMode.TITLE_ASC -> observeByCategoryByTitle(categoryId)
    }

    /** 未分类笔记（categoryId 为空，未删除）：排序与 observeByCategory 保持一致。 */
    @Query("SELECT id, title, categoryId, pinned, createdAt, updatedAt, deletedAt, substr(content, 1, 400) AS summary FROM notes WHERE deletedAt IS NULL AND categoryId IS NULL ORDER BY pinned DESC, updatedAt DESC")
    fun observeUncategorized(): Flow<List<NoteListItem>>

    @Query("SELECT id, title, categoryId, pinned, createdAt, updatedAt, deletedAt, substr(content, 1, 400) AS summary FROM notes WHERE deletedAt IS NULL AND categoryId IS NULL ORDER BY pinned DESC, createdAt DESC")
    fun observeUncategorizedByCreated(): Flow<List<NoteListItem>>

    @Query("SELECT id, title, categoryId, pinned, createdAt, updatedAt, deletedAt, substr(content, 1, 400) AS summary FROM notes WHERE deletedAt IS NULL AND categoryId IS NULL ORDER BY pinned DESC, title COLLATE NOCASE ASC")
    fun observeUncategorizedByTitle(): Flow<List<NoteListItem>>

    /** 按排序方式观察未分类笔记。 */
    fun observeUncategorized(mode: NoteSortMode): Flow<List<NoteListItem>> = when (mode) {
        NoteSortMode.UPDATED_DESC -> observeUncategorized()
        NoteSortMode.CREATED_DESC -> observeUncategorizedByCreated()
        NoteSortMode.TITLE_ASC -> observeUncategorizedByTitle()
    }

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): NoteEntity?

    /** 按 id 集合取笔记（无 deletedAt 过滤，批量操作只对可见笔记调用）。 */
    @Query("SELECT * FROM notes WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<NoteEntity>

    @Update
    suspend fun updateAll(notes: List<NoteEntity>)

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    /** 主页可见笔记数（不含回收站），欢迎笔记种子判断用，不读正文。 */
    @Query("SELECT COUNT(*) FROM notes WHERE deletedAt IS NULL")
    suspend fun countVisible(): Int

    @Query("UPDATE notes SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)
}
