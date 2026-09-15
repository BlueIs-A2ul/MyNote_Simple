package com.mynote.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteListItem
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.util.CalendarDates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

/**
 * 日历页 ViewModel：展示月份、选中日与当日笔记（只读，不支持在日历页新建/改日期）。
 *
 * 约定：
 * - 进入默认选中「今天」；
 * - 切换月份时选中日落在该月 1 号：网格与下方列表始终同月，避免「看 9 月网格、列表却是 8 月笔记」；
 * - 从编辑页返回时状态保留（VM 挂在导航返回栈条目上）；再次进入才回到今天。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val repository: NoteRepository,
    now: Long = System.currentTimeMillis()
) : ViewModel() {

    private val initial = Calendar.getInstance().apply { timeInMillis = now }

    private val _year = MutableStateFlow(initial.get(Calendar.YEAR))
    val year: StateFlow<Int> = _year

    private val _month = MutableStateFlow(initial.get(Calendar.MONTH) + 1)
    val month: StateFlow<Int> = _month

    /** 选中日（本地零点毫秒）。 */
    private val _selectedDay = MutableStateFlow(CalendarDates.dayStart(now))
    val selectedDay: StateFlow<Long> = _selectedDay

    val categories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当月「每天有几篇笔记」，驱动月网格圆点。 */
    val marks: StateFlow<Map<Long, Int>> = combine(_year, _month) { year, month -> year to month }
        .flatMapLatest { (year, month) ->
            val (nextYear, nextMonth) = CalendarDates.addMonths(year, month, 1)
            repository.observeDateMarks(
                CalendarDates.monthStart(year, month),
                CalendarDates.monthStart(nextYear, nextMonth)
            )
        }
        .map { list -> list.associate { it.noteDate to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 选中日的笔记；null = 加载中（沿用列表页冷启动口径，避免闪空态）。 */
    val dayNotes: StateFlow<List<NoteListItem>?> = _selectedDay
        .flatMapLatest { day -> repository.observeNotesByDateRange(day, CalendarDates.nextDay(day)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun prevMonth() = shiftMonth(-1)

    fun nextMonth() = shiftMonth(1)

    /** 回到今天（月份与选中日一起复位）。 */
    fun goToToday(now: Long = System.currentTimeMillis()) {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        _year.value = c.get(Calendar.YEAR)
        _month.value = c.get(Calendar.MONTH) + 1
        _selectedDay.value = CalendarDates.dayStart(now)
    }

    fun selectDay(dayStartMillis: Long) {
        _selectedDay.value = dayStartMillis
    }

    private fun shiftMonth(delta: Int) {
        val (year, month) = CalendarDates.addMonths(_year.value, _month.value, delta)
        _year.value = year
        _month.value = month
        _selectedDay.value = CalendarDates.monthStart(year, month)
    }

    companion object {
        fun factory(repository: NoteRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { CalendarViewModel(repository) } }
    }
}
