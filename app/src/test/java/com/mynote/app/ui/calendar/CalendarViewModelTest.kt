package com.mynote.app.ui.calendar

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.util.CalendarDates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private lateinit var vm: CalendarViewModel

    /** 固定「现在」：2026-09-15 12:00（本地时区）。 */
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 15, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), ImageStore(context), db)
        vm = CalendarViewModel(repo, now = now)
    }

    @After
    fun teardown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun defaultsToCurrentMonthAndToday() {
        assertEquals(2026, vm.year.value)
        assertEquals(9, vm.month.value)
        assertEquals(CalendarDates.dayStart(2026, 9, 15), vm.selectedDay.value)
    }

    @Test
    fun dayNotesFollowSelectedDay() = runTest(dispatcher) {
        val day = CalendarDates.dayStart(2026, 9, 15)
        val nextDay = CalendarDates.dayStart(2026, 9, 16)
        repo.saveNote(null, "当天", "c", null, false, null, day)
        repo.saveNote(null, "次日", "c", null, false, null, nextDay)

        vm.selectDay(day)

        assertEquals(listOf("当天"), vm.dayNotes.first { it != null }!!.map { it.title })
    }

    @Test
    fun marksFollowDisplayedMonth() = runTest(dispatcher) {
        val september = CalendarDates.dayStart(2026, 9, 15)
        val august = CalendarDates.dayStart(2026, 8, 15)
        repo.saveNote(null, "九月", "c", null, false, null, september)
        repo.saveNote(null, "八月", "c", null, false, null, august)

        assertEquals(mapOf(september to 1), vm.marks.first { it.isNotEmpty() })

        vm.prevMonth()

        assertEquals(2026, vm.year.value)
        assertEquals(8, vm.month.value)
        // 切月后选中日回到该月 1 号，避免网格与列表不同月
        assertEquals(CalendarDates.monthStart(2026, 8), vm.selectedDay.value)
        assertEquals(mapOf(august to 1), vm.marks.first { it.containsKey(august) })
    }

    @Test
    fun monthArrowsCrossYearBoundary() {
        vm.prevMonth()
        vm.prevMonth()
        vm.prevMonth()
        vm.prevMonth()
        vm.prevMonth()
        vm.prevMonth()
        vm.prevMonth()
        vm.prevMonth()
        vm.prevMonth()

        assertEquals(2025, vm.year.value)
        assertEquals(12, vm.month.value)
        assertEquals(CalendarDates.monthStart(2025, 12), vm.selectedDay.value)
    }

    @Test
    fun goToTodayResetsMonthAndSelection() {
        vm.prevMonth()
        vm.prevMonth()
        assertEquals(7, vm.month.value)

        vm.goToToday(now)

        assertEquals(2026, vm.year.value)
        assertEquals(9, vm.month.value)
        assertEquals(CalendarDates.dayStart(2026, 9, 15), vm.selectedDay.value)
    }
}
