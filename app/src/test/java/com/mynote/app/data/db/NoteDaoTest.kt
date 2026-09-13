package com.mynote.app.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NoteDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.noteDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun note(id: Long = 0, title: String, updatedAt: Long, pinned: Boolean = false) =
        NoteEntity(id, title, "c", 0L, updatedAt, null, pinned, null)

    @Test
    fun insertAndGetById() = runTest {
        val id = dao.insert(note(title = "标题", updatedAt = 1L))
        val loaded = dao.getById(id)
        assertEquals("标题", loaded?.title)
    }

    @Test
    fun searchMatchesTitleAndContent() = runTest {
        dao.insert(note(title = "购物清单", updatedAt = 1L))
        dao.insert(NoteEntity(0, "无关键词", "里面有 苹果 二字", 0L, 2L, null, false, null))
        val hits = dao.search("苹果").first()
        assertEquals(1, hits.size)
        assertEquals("无关键词", hits[0].title)
    }

    @Test
    fun observeAllOrdersPinnedThenUpdatedDesc() = runTest {
        dao.insert(note(title = "旧", updatedAt = 1L))
        dao.insert(note(title = "新", updatedAt = 3L))
        dao.insert(note(title = "置顶", updatedAt = 2L, pinned = true))
        val all = dao.observeAll().first()
        assertEquals(listOf("置顶", "新", "旧"), all.map { it.title })
    }

    @Test
    fun clearCategoryNullifiesReferences() = runTest {
        val catId = db.categoryDao().insert(CategoryEntity(0, "工作", 0xFF000000.toInt()))
        dao.insert(NoteEntity(0, "n", "c", 0L, 1L, catId, false, null))
        dao.clearCategory(catId)
        val all = dao.getAll()
        assertNull(all[0].categoryId)
    }

    @Test
    fun deleteRemovesNote() = runTest {
        val id = dao.insert(note(title = "待删", updatedAt = 1L))
        val loaded = dao.getById(id)!!
        dao.delete(loaded)
        assertNull(dao.getById(id))
    }

    @Test
    fun observeAllBySortCreatedDescOrdersByCreatedAt() = runTest {
        dao.insert(NoteEntity(0, "最旧", "c", 1L, 10L, null, false, null))
        dao.insert(NoteEntity(0, "中间", "c", 2L, 20L, null, false, null))
        dao.insert(NoteEntity(0, "最新", "c", 3L, 30L, null, false, null))
        val all = dao.observeAllBySort(NoteSortMode.CREATED_DESC).first()
        assertEquals(listOf("最新", "中间", "最旧"), all.map { it.title })
    }

    @Test
    fun observeAllBySortTitleAscIsCaseInsensitive() = runTest {
        dao.insert(NoteEntity(0, "Banana", "c", 0L, 1L, null, false, null))
        dao.insert(NoteEntity(0, "cherry", "c", 0L, 2L, null, false, null))
        dao.insert(NoteEntity(0, "apple", "c", 0L, 3L, null, false, null))
        val all = dao.observeAllBySort(NoteSortMode.TITLE_ASC).first()
        assertEquals(listOf("apple", "Banana", "cherry"), all.map { it.title })
    }

    @Test
    fun observeAllBySortPutsPinnedFirstForAllModes() = runTest {
        dao.insert(NoteEntity(0, "apple", "c", 1L, 1L, null, false, null))
        dao.insert(NoteEntity(0, "pinned", "c", 2L, 2L, null, true, null))
        dao.insert(NoteEntity(0, "zebra", "c", 3L, 3L, null, false, null))
        assertEquals(
            listOf("pinned", "zebra", "apple"),
            dao.observeAllBySort(NoteSortMode.UPDATED_DESC).first().map { it.title }
        )
        assertEquals(
            listOf("pinned", "zebra", "apple"),
            dao.observeAllBySort(NoteSortMode.CREATED_DESC).first().map { it.title }
        )
        assertEquals(
            listOf("pinned", "apple", "zebra"),
            dao.observeAllBySort(NoteSortMode.TITLE_ASC).first().map { it.title }
        )
    }
}
