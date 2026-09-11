package com.mynote.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class TimeFormatTest {

    private fun millis(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    @Test
    fun todayShowsToday() {
        val now = millis(2026, 9, 11, 15)
        assertEquals("今天", TimeFormat.relativeDate(millis(2026, 9, 11, 8), now))
    }

    @Test
    fun yesterdayShowsYesterday() {
        val now = millis(2026, 9, 11, 1)
        assertEquals("昨天", TimeFormat.relativeDate(millis(2026, 9, 10, 23), now))
    }

    @Test
    fun sameYearShowsMonthDay() {
        val now = millis(2026, 9, 11)
        assertEquals("9月9日", TimeFormat.relativeDate(millis(2026, 9, 9), now))
        assertEquals("1月1日", TimeFormat.relativeDate(millis(2026, 1, 1), now))
    }

    @Test
    fun otherYearShowsFullDate() {
        val now = millis(2026, 9, 11)
        assertEquals("2025年12月31日", TimeFormat.relativeDate(millis(2025, 12, 31), now))
    }

    @Test
    fun midnightBoundaryIsToday() {
        val now = millis(2026, 9, 11, 0)
        assertEquals("今天", TimeFormat.relativeDate(millis(2026, 9, 11, 0), now))
        assertEquals("昨天", TimeFormat.relativeDate(millis(2026, 9, 10, 23), now))
    }
}
