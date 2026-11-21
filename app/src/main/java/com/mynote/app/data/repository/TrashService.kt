package com.mynote.app.data.repository

import androidx.room.withTransaction
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.db.NoteDao
import com.mynote.app.data.db.NoteEntity

/**
 * 回收站生命周期：到期计数/清理、彻底删除。
 *
 * 与笔记 CRUD 分开：这些操作都涉及“物理删除 + 图片 GC”的固定顺序，集中一处，
 * 避免每个调用方各自拼事务。
 */
class TrashService(
    private val database: AppDatabase,
    private val noteDao: NoteDao,
    private val imageGarbageCollector: ImageGarbageCollector
) {

    suspend fun countExpiredDeletedNotes(
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = TRASH_TTL_MS
    ): Int = noteDao.countDeletedBefore(now - ttlMs)

    /** 清理回收站中超过 ttlMs 的笔记；返回清理条数。SQL 过滤 + 事务批量删，避免全表读正文。 */
    suspend fun purgeExpiredDeletedNotes(
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = TRASH_TTL_MS
    ): Int {
        val cutoff = now - ttlMs
        val expired = database.withTransaction {
            noteDao.getDeletedBefore(cutoff).also { list -> list.forEach { noteDao.delete(it) } }
        }
        if (expired.isNotEmpty()) imageGarbageCollector.collect()
        return expired.size
    }

    /** 彻底删除：物理删除并级联清历史，再回收孤儿图片。 */
    suspend fun purgeNote(note: NoteEntity) {
        noteDao.delete(note)
        imageGarbageCollector.collect()
    }

    /** 批量彻底删除（清空回收站）：单事务物理删除 + 只做一次图片 GC。 */
    suspend fun purgeNotes(notes: List<NoteEntity>) {
        if (notes.isEmpty()) return
        database.withTransaction { notes.forEach { noteDao.delete(it) } }
        imageGarbageCollector.collect()
    }

    companion object {
        /** 回收站保留时长：30 天。 */
        const val TRASH_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
