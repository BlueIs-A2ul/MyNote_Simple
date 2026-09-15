package com.mynote.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.image.ImageStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private lateinit var imageStore: ImageStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        imageStore = ImageStore(context)
        repo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), imageStore, db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun saveNewNoteInsertsAndReturnsId() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        assertNotNull(id)
        assertEquals("t", repo.getNote(id)?.title)
    }

    @Test
    fun saveExistingNoteUpdatesContentAndKeepsCreatedAt() = runTest {
        val id = repo.saveNote(null, "t", "v1", null, false, null)
        val created = repo.getNote(id)!!.createdAt
        repo.saveNote(id, "t2", "v2", null, false, null)
        val updated = repo.getNote(id)!!
        assertEquals("v2", updated.content)
        assertEquals(created, updated.createdAt)
    }

    @Test
    fun deleteCategoryMovesNotesToUncategorized() = runTest {
        val catId = repo.addCategory("工作", 0)
        val noteId = repo.saveNote(null, "n", "c", catId, false, null)
        repo.deleteCategory(db.categoryDao().getById(catId)!!)
        assertNull(repo.getNote(noteId)?.categoryId)
    }

    @Test
    fun searchBlankReturnsAll() = runTest {
        repo.saveNote(null, "a", "x", null, false, null)
        repo.saveNote(null, "b", "y", null, false, null)
        assertEquals(2, repo.search("   ").first().size)
    }

    @Test
    fun saveExistingNoteAppliesPinToggle() = runTest {
        val id = repo.saveNote(null, "t", "c", null, true, null)
        repo.saveNote(id, "t", "c", null, false, null)
        assertEquals(false, repo.getNote(id)?.pinned)
    }

    @Test
    fun addCategoryIsIdempotentForSameName() = runTest {
        val first = repo.addCategory("工作", 0)
        val second = repo.addCategory("工作", 1)
        assertEquals(first, second)
        assertEquals(1, db.categoryDao().getAll().size)
    }

    @Test
    fun renameCategoryToUnusedNameSucceeds() = runTest {
        val catId = repo.addCategory("工作", 0)
        assertTrue(repo.renameCategory(db.categoryDao().getById(catId)!!, "生活"))
        assertEquals("生活", db.categoryDao().getById(catId)?.name)
    }

    @Test
    fun renameCategoryToExistingNameFailsAndKeepsOriginal() = runTest {
        val a = repo.addCategory("工作", 0)
        repo.addCategory("生活", 1)
        assertFalse(repo.renameCategory(db.categoryDao().getById(a)!!, "生活"))
        assertEquals("工作", db.categoryDao().getById(a)?.name)
        assertEquals(2, db.categoryDao().getAll().size)
    }

    @Test
    fun renameCategoryToItsOwnNameSucceeds() = runTest {
        val catId = repo.addCategory("工作", 0)
        assertTrue(repo.renameCategory(db.categoryDao().getById(catId)!!, "工作"))
        assertEquals("工作", db.categoryDao().getById(catId)?.name)
    }

    @Test
    fun updateCategoryColorPersistsNewColor() = runTest {
        val catId = repo.addCategory("工作", 0x111111)
        repo.updateCategoryColor(db.categoryDao().getById(catId)!!, 0x222222)
        assertEquals(0x222222, db.categoryDao().getById(catId)?.color)
    }

    @Test
    fun newNoteCreatesFirstRevision() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        val revisions = db.noteRevisionDao().getByNote(id)
        assertEquals(1, revisions.size)
        assertEquals("c", revisions[0].content)
        assertEquals(id, revisions[0].noteId)
    }

    @Test
    fun saveWithoutChangesDoesNotAddRevision() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        repo.saveNote(id, "t", "c", null, false, null)
        assertEquals(1, repo.countRevisions(id))
    }

    @Test
    fun firstSaveOfLegacyNoteCreatesBaselineAndNewRevision() = runTest {
        val legacyId = db.noteDao().insert(NoteEntity(0, "old", "old content", 111, 222, null, false, null))
        repo.saveNote(legacyId, "new", "new content", null, false, null)
        val revisions = db.noteRevisionDao().getByNote(legacyId)
        assertEquals(2, revisions.size)
        assertEquals("new content", revisions[0].content)
        assertEquals("old content", revisions[1].content)
        assertEquals(222L, revisions[1].savedAt)
        assertTrue(revisions[0].savedAt > 222L)
    }

    @Test
    fun trimKeepsAtMost50RevisionsPerNote() = runTest {
        val id = repo.saveNote(null, "t", "v0", null, false, null)
        for (i in 1..55) repo.saveNote(id, "t", "v$i", null, false, null)
        assertEquals(50, repo.countRevisions(id))
    }

    @Test
    fun deleteNoteMovesToTrashAndKeepsRevisions() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        repo.deleteNote(repo.getNote(id)!!)
        // 软删除：笔记仍在库中但带删除标记，历史快照保留
        assertNotNull(repo.getNote(id))
        assertNotNull(repo.getNote(id)?.deletedAt)
        assertEquals(1, repo.countRevisions(id))
        assertEquals(1, repo.observeDeletedNotes().first().size)
        assertEquals(0, repo.observeNotes().first().size)
    }

    @Test
    fun restoreNoteClearsDeletedAt() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        repo.deleteNote(repo.getNote(id)!!)
        repo.restoreNote(repo.getNote(id)!!)
        assertNull(repo.getNote(id)?.deletedAt)
        assertEquals(0, repo.observeDeletedNotes().first().size)
        assertEquals(1, repo.observeNotes().first().size)
    }

    @Test
    fun purgeNoteCascadesRevisions() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        repo.purgeNote(repo.getNote(id)!!)
        assertEquals(0, repo.countRevisions(id))
        assertNull(repo.getNote(id))
    }

    @Test
    fun purgeExpiredDeletedNotesOnlyRemovesNotesBeyondTtl() = runTest {
        val oldId = repo.saveNote(null, "old", "c", null, false, null)
        val freshId = repo.saveNote(null, "fresh", "c", null, false, null)
        repo.deleteNote(repo.getNote(oldId)!!)
        repo.deleteNote(repo.getNote(freshId)!!)
        val now = System.currentTimeMillis()
        db.noteDao().update(repo.getNote(oldId)!!.copy(deletedAt = now - 31L * 24 * 3600 * 1000))
        db.noteDao().update(repo.getNote(freshId)!!.copy(deletedAt = now - 3600_000))
        val purged = repo.purgeExpiredDeletedNotes(now = now)
        assertEquals(1, purged)
        assertNull(repo.getNote(oldId))
        assertNotNull(repo.getNote(freshId))
    }

    @Test
    fun softDeleteKeepsImagesUntilPurge() = runTest {
        imageStore.writeFile("trash.webp", byteArrayOf(1))
        val id = repo.saveNote(null, "t", "![](img/trash.webp)", null, false, null)
        repo.deleteNote(repo.getNote(id)!!)
        assertTrue(imageStore.physicalFile("trash.webp").exists())
        repo.purgeNote(repo.getNote(id)!!)
        assertFalse(imageStore.physicalFile("trash.webp").exists())
    }

    @Test
    fun restoreRevisionWritesBackAndAddsRevision() = runTest {
        val id = repo.saveNote(null, "t", "v1", null, false, null)
        repo.saveNote(id, "t", "v2", null, false, null)
        val firstRevision = db.noteRevisionDao().getByNote(id).last()
        assertTrue(repo.restoreRevision(id, firstRevision.id))
        assertEquals("v1", repo.getNote(id)?.content)
        assertEquals(3, repo.countRevisions(id))
    }

    @Test
    fun restoreRevisionWithDeletedCategoryFallsBackToNull() = runTest {
        val catId = repo.addCategory("工作", 0)
        val id = repo.saveNote(null, "t", "v1", catId, false, null)
        repo.saveNote(id, "t", "v2", catId, false, null)
        val firstRevision = db.noteRevisionDao().getByNote(id).last()
        repo.deleteCategory(db.categoryDao().getById(catId)!!)
        assertTrue(repo.restoreRevision(id, firstRevision.id))
        assertNull(repo.getNote(id)?.categoryId)
    }

    @Test
    fun restoreMissingRevisionReturnsFalse() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        assertEquals(false, repo.restoreRevision(id, 99999L))
    }

    @Test
    fun garbageCollectionKeepsImagesReferencedByHistory() = runTest {
        imageStore.writeFile("a.webp", byteArrayOf(1))
        val id = repo.saveNote(null, "t", "![](img/a.webp)", null, false, null)
        repo.saveNote(id, "t", "no image", null, false, null)
        // 通过彻底删除另一篇笔记触发 GC（GC 只在彻底删除/裁剪后执行）
        val other = repo.saveNote(null, "other", "x", null, false, null)
        repo.purgeNote(repo.getNote(other)!!)
        assertTrue(imageStore.physicalFile("a.webp").exists())
    }

    @Test
    fun garbageCollectionReclaimsImagesAfterTrim() = runTest {
        imageStore.writeFile("b.webp", byteArrayOf(1))
        val id = repo.saveNote(null, "t", "![](img/b.webp)", null, false, null)
        for (i in 1..50) repo.saveNote(id, "t", "v$i", null, false, null)
        assertEquals(false, imageStore.physicalFile("b.webp").exists())
    }

    @Test
    fun removingImageMarkersTriggersGarbageCollection() = runTest {
        // 磁盘上有一个没有任何引用的孤儿文件
        imageStore.writeFile("orphan.webp", byteArrayOf(1))
        val orphanFile = imageStore.physicalFile("orphan.webp")
        assertTrue(orphanFile.exists())

        // 笔记 A 先带图保存（历史快照引用 a.webp），随后保存移除图片标记 → 应触发 GC
        val id = repo.saveNote(null, "t", "![](img/a.webp)", null, false, null)
        imageStore.writeFile("a.webp", byteArrayOf(1))
        repo.saveNote(id, "t", "no image", null, false, null)

        // 无引用的孤儿被回收；仍被历史快照引用的 a.webp 保留（恢复旧版所需）
        assertFalse(orphanFile.exists())
        assertTrue(imageStore.physicalFile("a.webp").exists())
    }

    @Test
    fun restoreRevisionRejectsRevisionFromAnotherNote() = runTest {
        val a = repo.saveNote(null, "a", "va", null, false, null)
        val b = repo.saveNote(null, "b", "vb", null, false, null)
        val revisionOfA = db.noteRevisionDao().getByNote(a).first().id
        assertEquals(false, repo.restoreRevision(b, revisionOfA))
        assertEquals("vb", repo.getNote(b)?.content)
        assertEquals(1, repo.countRevisions(b))
    }

    @Test
    fun pinOnlyChangeCreatesRevision() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        repo.saveNote(id, "t", "c", null, true, null)
        assertEquals(2, repo.countRevisions(id))
    }

    @Test
    fun purgeNotesBatchDeletesAndReclaimsOrphans() = runTest {
        imageStore.writeFile("keep.webp", byteArrayOf(1))
        imageStore.writeFile("orphan.webp", byteArrayOf(1))
        val a = repo.saveNote(null, "a", "![](img/keep.webp)", null, false, null)
        val b = repo.saveNote(null, "b", "x", null, false, null)
        repo.deleteNote(repo.getNote(a)!!)
        repo.deleteNote(repo.getNote(b)!!)
        val inTrash = repo.observeDeletedNotes().first { it.size == 2 }

        repo.purgeNotes(repo.getNotesByIds(inTrash.map { it.id }))

        assertNull(db.noteDao().getById(a))
        assertNull(db.noteDao().getById(b))
        // 物理删除会级联清掉历史快照（图片引用链随之断开），两个文件都被一次 GC 回收
        assertFalse(imageStore.physicalFile("keep.webp").exists())
        assertFalse(imageStore.physicalFile("orphan.webp").exists())
    }

    @Test
    fun updateDraftUpdatesFieldsWithoutAddingRevision() = runTest {
        val catId = repo.addCategory("工作", 0)
        val id = repo.saveNote(null, "t", "c", null, false, null)
        val created = repo.getNote(id)!!.createdAt
        // 把 updatedAt 改成过去时刻，断言草稿保存确实刷新了它
        db.noteDao().update(repo.getNote(id)!!.copy(updatedAt = 111L))

        repo.updateDraft(id, "t2", "c2", catId, true, 0x123456)

        val updated = repo.getNote(id)!!
        assertEquals("t2", updated.title)
        assertEquals("c2", updated.content)
        assertEquals(catId, updated.categoryId)
        assertTrue(updated.pinned)
        assertEquals(0x123456, updated.color)
        assertEquals(created, updated.createdAt)
        assertTrue(updated.updatedAt > 111L)
        // 静默草稿不写历史快照，原快照内容保持首次保存时的值
        assertEquals(1, repo.countRevisions(id))
        assertEquals("c", db.noteRevisionDao().getByNote(id).single().content)
    }

    @Test
    fun purgeExpiredUsesSqlCutoff() = runTest {
        val expired = repo.saveNote(null, "expired", "c", null, false, null)
        val fresh = repo.saveNote(null, "fresh", "c", null, false, null)
        repo.deleteNote(repo.getNote(expired)!!)
        repo.deleteNote(repo.getNote(fresh)!!)
        val now = System.currentTimeMillis()
        db.noteDao().update(db.noteDao().getById(expired)!!.copy(deletedAt = now - 31L * 24 * 3600 * 1000))
        db.noteDao().update(db.noteDao().getById(fresh)!!.copy(deletedAt = now - 3600_000L))

        assertEquals(1, repo.purgeExpiredDeletedNotes())
        assertNull(repo.getNote(expired))
        assertNotNull(repo.getNote(fresh))
    }
}
