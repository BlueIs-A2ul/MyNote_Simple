package com.mynote.app.ui.categories

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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
}
