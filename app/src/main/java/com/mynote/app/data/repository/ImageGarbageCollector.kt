package com.mynote.app.data.repository

import com.mynote.app.data.db.NoteDao
import com.mynote.app.data.db.NoteRevisionDao
import com.mynote.app.data.image.ImageStore
import com.mynote.app.util.NoteContentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片孤儿回收：引用集 = 当前正文 ∪ 历史快照中含图片标记的正文。
 *
 * 独立出来是为了让 NoteRepository 只负责笔记聚合，同时保证保存/彻底删除/到期清理
 * 都走同一条回收规则（历史快照引用不能被误删）。
 */
class ImageGarbageCollector(
    private val noteDao: NoteDao,
    private val revisionDao: NoteRevisionDao,
    private val imageStore: ImageStore
) {
    suspend fun collect() = withContext(Dispatchers.IO) {
        val referenced = noteDao.getContentsWithImageMarkup()
            .flatMap { NoteContentParser.extractImageNames(it) }
            .toMutableSet()
        referenced += revisionDao.getContentsWithImageMarkup()
            .flatMap { NoteContentParser.extractImageNames(it) }
        imageStore.collectGarbage(referenced)
    }
}
