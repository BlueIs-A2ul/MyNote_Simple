package com.mynote.app.ui.categories

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.ui.theme.NoteColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoriesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private lateinit var vm: CategoriesViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), ImageStore(context), db)
        vm = CategoriesViewModel(repo)
    }

    @After
    fun teardown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun renameBlankNameDoesNotInvokeCallback() = runTest(dispatcher) {
        val catId = repo.addCategory("工作", 0)
        var called = false
        vm.rename(db.categoryDao().getById(catId)!!, "   ") { called = true }
        assertFalse(called)
    }

    @Test
    fun renameToExistingNameReportsFailure() = runTest(dispatcher) {
        val a = repo.addCategory("工作", 0)
        repo.addCategory("生活", 1)
        val result = CompletableDeferred<Boolean>()
        vm.rename(db.categoryDao().getById(a)!!, "生活") { result.complete(it) }
        assertFalse(result.await())
        assertFalse(db.categoryDao().getById(a)!!.name == "生活")
    }

    @Test
    fun renameToUnusedNameReportsSuccess() = runTest(dispatcher) {
        val catId = repo.addCategory("工作", 0)
        val result = CompletableDeferred<Boolean>()
        vm.rename(db.categoryDao().getById(catId)!!, "生活") { result.complete(it) }
        assertTrue(result.await())
    }

    // ---- 条目 33：分类颜色可选可改 ----

    @Test
    fun createCategoryUsesGivenColor() = runTest(dispatcher) {
        val palette = NoteColors.map { it.toArgb() }
        val target = palette[3]
        val done = CompletableDeferred<Unit>()
        vm.add("颜色测试", target) { done.complete(Unit) }
        done.await()
        assertEquals(target, db.categoryDao().getByName("颜色测试")!!.color)
    }

    @Test
    fun leastUsedColorPicksLeastUsedPaletteColor() {
        val palette = NoteColors.map { it.toArgb() }
        // 色板第一色用 2 次、第二色用 1 次、其余 0 次 → 选计数最小的（优先完全未用色）= palette[2]
        val cats = listOf(
            CategoryEntity(0, "a", palette[0]),
            CategoryEntity(0, "b", palette[0]),
            CategoryEntity(0, "c", palette[1])
        )
        assertEquals(palette[2], vm.leastUsedColor(cats))
    }

    @Test
    fun leastUsedColorDefaultsToFirstPaletteColorWhenNoneUsed() {
        val palette = NoteColors.map { it.toArgb() }
        assertEquals(palette[0], vm.leastUsedColor(emptyList()))
        // 非色板颜色（旧版随机高饱和色）不计入任何色板色的次数
        val legacy = listOf(CategoryEntity(0, "a", 0xFFFF0000.toInt()))
        assertEquals(palette[0], vm.leastUsedColor(legacy))
    }

    @Test
    fun leastUsedColorTiesBreakInPaletteOrder() {
        val palette = NoteColors.map { it.toArgb() }
        // 色板第三、四色各用 1 次，其余 0 次 → 并列取色板顺序第一个
        val cats = listOf(
            CategoryEntity(0, "a", palette[2]),
            CategoryEntity(0, "b", palette[3])
        )
        assertEquals(palette[0], vm.leastUsedColor(cats))
    }

    @Test
    fun cycleColorPersistsNextPaletteColor() = runTest(dispatcher) {
        val palette = NoteColors.map { it.toArgb() }
        val catId = repo.addCategory("工作", palette[0])
        vm.cycleColor(db.categoryDao().getById(catId)!!)
        // 等待持久化：观察流中出现下一色
        repo.observeCategories().first { cats ->
            cats.any { it.id == catId && it.color == palette[1] }
        }
        assertEquals(palette[1], db.categoryDao().getById(catId)!!.color)
    }

    @Test
    fun nextColorWrapsAroundAndHandlesLegacyArgb() {
        val palette = NoteColors.map { it.toArgb() }
        assertEquals(palette[1], vm.nextColor(palette[0]))
        // 最后一色 → 循环回第一色
        assertEquals(palette[0], vm.nextColor(palette.last()))
        // 旧版随机高饱和红色（色相接近赭石）→ 下一色为色板第二色
        assertEquals(palette[1], vm.nextColor(0xFFFF0000.toInt()))
    }
}
