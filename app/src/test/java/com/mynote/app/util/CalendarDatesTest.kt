package com.mynote.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarDatesTest {

    @Test
    fun dayStartStripsTimeAndKeepsLocalDate() {
        val c = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 15, 23, 59, 58)
            set(Calendar.MILLISECOND, 123)
        }

        val start = CalendarDates.dayStart(c.timeInMillis)

        val check = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(2026, check.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, check.get(Calendar.MONTH))
        assertEquals(15, check.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, check.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, check.get(Calendar.MINUTE))
        assertEquals(0, check.get(Calendar.MILLISECOND))
        assertEquals(15, CalendarDates.dayOfMonth(start))
    }

    @Test
    fun nextDayCrossesMonthBoundary() {
        assertEquals(
            CalendarDates.dayStart(2026, 10, 1),
            CalendarDates.nextDay(CalendarDates.dayStart(2026, 9, 30))
        )
    }

    @Test
    fun firstDayOffsetIsMondayBased() {
        // 2026-09-01 是周二 → 周一为 0 时偏移 1
        assertEquals(1, CalendarDates.firstDayOffset(2026, 9))
        // 2026-06-01 是周一 → 偏移 0
        assertEquals(0, CalendarDates.firstDayOffset(2026, 6))
    }

    @Test
    fun daysInMonthHandlesLeapYear() {
        assertEquals(30, CalendarDates.daysInMonth(2026, 9))
        assertEquals(28, CalendarDates.daysInMonth(2026, 2))
        assertEquals(29, CalendarDates.daysInMonth(2028, 2))
    }

    @Test
    fun addMonthsRollsOverYearBoundary() {
        assertEquals(2027 to 1, CalendarDates.addMonths(2026, 12, 1))
        assertEquals(2025 to 12, CalendarDates.addMonths(2026, 1, -1))
        assertEquals(2026 to 1, CalendarDates.addMonths(2026, 9, -8))
        assertEquals(2028 to 9, CalendarDates.addMonths(2026, 9, 24))
    }

    @Test
    fun monthGridAlignsToWholeMondayWeeks() {
        val september = CalendarDates.monthGrid(2026, 9)
        // 偏移 1 + 30 天 = 31 格 → 5 行 35 格，首格留空
        assertEquals(35, september.size)
        assertNull(september.first())
        assertEquals(CalendarDates.dayStart(2026, 9, 1), september[1])
        assertEquals(CalendarDates.dayStart(2026, 9, 30), september[30])
        assertNull(september[31])

        val june = CalendarDates.monthGrid(2026, 6)
        // 周一起始 + 30 天 = 恰好 5 行，首格即 6 月 1 日
        assertEquals(35, june.size)
        assertEquals(CalendarDates.dayStart(2026, 6, 1), june.first())
        assertNull(june[30])
    }

    @Test
    fun pickerMillisRoundTripsBetweenUtcAndLocalDay() {
        val localDay = CalendarDates.dayStart(2026, 9, 15)

        val pickerMillis = CalendarDates.localDayToPickerMillis(localDay)

        // DatePicker 存的是所选日期的 UTC 零点（跨时区也不会偏移一天）
        val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = pickerMillis }
        assertEquals(2026, utc.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, utc.get(Calendar.MONTH))
        assertEquals(15, utc.get(Calendar.DAY_OF_MONTH))
        assertEquals(localDay, CalendarDates.pickerMillisToLocalDay(pickerMillis))
    }
}
