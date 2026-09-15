package com.mynote.app.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.util.CalendarDates
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

    private fun note(
        id: Long = 0,
        title: String,
        updatedAt: Long,
        pinned: Boolean = false,
        noteDate: Long? = null
    ) = NoteEntity(id, title, "c", 0L, updatedAt, null, pinned, null, noteDate = noteDate)

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

    @Test
    fun softDeletedNotesAreHiddenFromActiveQueries() = runTest {
        dao.insert(note(title = "正常", updatedAt = 1L))
        val deletedId = dao.insert(note(title = "已删", updatedAt = 2L))
        dao.update(dao.getById(deletedId)!!.copy(deletedAt = 5L))

        assertEquals(listOf("正常"), dao.observeAll().first().map { it.title })
        assertEquals(listOf("正常"), dao.observeAllBySort(NoteSortMode.UPDATED_DESC).first().map { it.title })
        assertEquals(0, dao.search("已删").first().size)
    }

    @Test
    fun softDeletedNotesAreHiddenFromCategoryView() = runTest {
        val catId = db.categoryDao().insert(CategoryEntity(0, "工作", 0))
        dao.insert(NoteEntity(0, "正常", "c", 0L, 1L, catId, false, null))
        val deletedId = dao.insert(NoteEntity(0, "已删", "c", 0L, 2L, catId, false, null))
        dao.update(dao.getById(deletedId)!!.copy(deletedAt = 5L))
        assertEquals(listOf("正常"), dao.observeByCategory(catId).first().map { it.title })
    }

    @Test
    fun observeUncategorizedReturnsOnlyNotesWithoutCategory() = runTest {
        val catId = db.categoryDao().insert(CategoryEntity(0, "工作", 0))
        dao.insert(NoteEntity(0, "有分类", "c", 0L, 1L, catId, false, null))
        dao.insert(note(title = "未分类", updatedAt = 2L))
        val deletedId = dao.insert(note(title = "已删未分类", updatedAt = 3L))
        dao.update(dao.getById(deletedId)!!.copy(deletedAt = 5L))

        assertEquals(listOf("未分类"), dao.observeUncategorized().first().map { it.title })
        // 有分类的笔记即使更新时间更新也不出现在未分类列表
        assertEquals(1, dao.observeUncategorized().first().size)
    }

    @Test
    fun observeUncategorizedOrdersPinnedFirstThenUpdatedDesc() = runTest {
        val catId = db.categoryDao().insert(CategoryEntity(0, "工作", 0))
        dao.insert(NoteEntity(0, "有分类-新", "c", 0L, 30L, catId, false, null))
        dao.insert(note(title = "未分类-旧", updatedAt = 1L))
        dao.insert(note(title = "未分类-置顶", updatedAt = 2L, pinned = true))
        dao.insert(note(title = "未分类-新", updatedAt = 3L))

        assertEquals(
            listOf("未分类-置顶", "未分类-新", "未分类-旧"),
            dao.observeUncategorized().first().map { it.title }
        )
    }

    @Test
    fun observeByCategoryHonorsSortModes() = runTest {
        val catId = db.categoryDao().insert(CategoryEntity(0, "工作", 0))
        dao.insert(NoteEntity(0, "banana", "c", 3L, 3L, catId, false, null))
        dao.insert(NoteEntity(0, "Apple", "c", 1L, 1L, catId, false, null))
        dao.insert(NoteEntity(0, "cherry", "c", 2L, 2L, catId, false, null))

        assertEquals(
            listOf("banana", "cherry", "Apple"),
            dao.observeByCategory(catId, NoteSortMode.CREATED_DESC).first().map { it.title }
        )
        assertEquals(
            listOf("Apple", "banana", "cherry"),
            dao.observeByCategory(catId, NoteSortMode.TITLE_ASC).first().map { it.title }
        )
    }

    @Test
    fun observeUncategorizedHonorsSortModes() = runTest {
        val catId = db.categoryDao().insert(CategoryEntity(0, "工作", 0))
        dao.insert(NoteEntity(0, "有分类", "c", 9L, 9L, catId, false, null))
        dao.insert(NoteEntity(0, "banana", "c", 3L, 3L, null, false, null))
        dao.insert(NoteEntity(0, "Apple", "c", 1L, 1L, null, false, null))
        dao.insert(NoteEntity(0, "cherry", "c", 2L, 2L, null, false, null))

        assertEquals(
            listOf("banana", "cherry", "Apple"),
            dao.observeUncategorized(NoteSortMode.CREATED_DESC).first().map { it.title }
        )
        assertEquals(
            listOf("Apple", "banana", "cherry"),
            dao.observeUncategorized(NoteSortMode.TITLE_ASC).first().map { it.title }
        )
    }

    @Test
    fun searchHonorsSortModes() = runTest {
        dao.insert(NoteEntity(0, "banana", "正文里含 apple 一词", 3L, 1L, null, false, null))
        dao.insert(NoteEntity(0, "Apple", "正文", 1L, 2L, null, false, null))

        assertEquals(
            listOf("banana", "Apple"),
            dao.search("apple", NoteSortMode.CREATED_DESC).first().map { it.title }
        )
        assertEquals(
            listOf("Apple", "banana"),
            dao.search("apple", NoteSortMode.TITLE_ASC).first().map { it.title }
        )
    }

    @Test
    fun observeDeletedReturnsOnlyDeletedOrderedByDeletedAtDesc() = runTest {
        dao.insert(note(title = "正常", updatedAt = 1L))
        val older = dao.insert(note(title = "旧删", updatedAt = 2L))
        val newer = dao.insert(note(title = "新删", updatedAt = 3L))
        dao.update(dao.getById(older)!!.copy(deletedAt = 10L))
        dao.update(dao.getById(newer)!!.copy(deletedAt = 20L))

        val deleted = dao.observeDeleted().first()
        assertEquals(listOf("新删", "旧删"), deleted.map { it.title })
    }

    @Test
    fun getByIdsReturnsOnlyRequestedRows() = runTest {
        val a = dao.insert(note(title = "a", updatedAt = 1L))
        dao.insert(note(title = "b", updatedAt = 2L))
        val c = dao.insert(note(title = "c", updatedAt = 3L))
        val result = dao.getByIds(listOf(a, c))
        assertEquals(setOf("a", "c"), result.map { it.title }.toSet())
    }

    @Test
    fun updateAllUpdatesBatch() = runTest {
        val a = dao.insert(note(title = "a", updatedAt = 1L))
        val b = dao.insert(note(title = "b", updatedAt = 2L))
        dao.updateAll(dao.getByIds(listOf(a, b)).map { it.copy(pinned = true) })
        assertEquals(listOf(true, true), dao.getByIds(listOf(a, b)).map { it.pinned })
    }

    @Test
    fun observeByDateRangeFiltersRangeAndExcludesUntaggedAndDeleted() = runTest {
        val day = CalendarDates.dayStart(2026, 9, 15)
        val nextDay = CalendarDates.nextDay(day)
        dao.insert(note(title = "当天普通", updatedAt = 1L, noteDate = day))
        dao.insert(note(title = "当天置顶", updatedAt = 2L, pinned = true, noteDate = day))
        dao.insert(note(title = "次日", updatedAt = 3L, noteDate = nextDay))
        dao.insert(note(title = "未标记", updatedAt = 4L))
        val deleted = dao.insert(note(title = "已删当天", updatedAt = 5L, noteDate = day))
        dao.update(dao.getById(deleted)!!.copy(deletedAt = 9L))

        // 含起不含止：只出现选中日；置顶优先；未标记与软删除不出现
        assertEquals(
            listOf("当天置顶", "当天普通"),
            dao.observeByDateRange(day, nextDay).first().map { it.title }
        )
    }

    @Test
    fun observeDateMarksCountsNotesPerDayWithinRange() = runTest {
        val day = CalendarDates.dayStart(2026, 9, 15)
        val nextDay = CalendarDates.nextDay(day)
        dao.insert(note(title = "a", updatedAt = 1L, noteDate = day))
        dao.insert(note(title = "b", updatedAt = 2L, noteDate = day))
        dao.insert(note(title = "c", updatedAt = 3L, noteDate = nextDay))
        dao.insert(note(title = "未标记", updatedAt = 4L))
        val deleted = dao.insert(note(title = "已删", updatedAt = 5L, noteDate = day))
        dao.update(dao.getById(deleted)!!.copy(deletedAt = 9L))

        val marks = dao.observeDateMarks(day, nextDay).first()
        assertEquals(1, marks.size)
        assertEquals(DateMark(day, 2), marks.single())
    }
}
