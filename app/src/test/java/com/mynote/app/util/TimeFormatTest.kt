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
        // 同一天 7 小时前 → 小时档（条目 30 后，分钟/小时档取代了同一天的「今天」粒度）
        assertEquals("7 小时前", TimeFormat.relativeDate(millis(2026, 9, 11, 8), now))
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
        // 同一自然日且 0 秒差 → 刚刚（原来显示「今天」）
        assertEquals("刚刚", TimeFormat.relativeDate(millis(2026, 9, 11, 0), now))
        assertEquals("昨天", TimeFormat.relativeDate(millis(2026, 9, 10, 23), now))
    }

    // ---------- backlog 30：分钟/小时级相对时间 ----------

    /** 精确到秒的构造器：秒级/分钟级档位的测试用，分钟、秒默认 0。 */
    private fun millisAt(
        year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, second: Int = 0
    ): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.timeInMillis

    @Test
    fun lessThanOneMinuteShowsJustNow() {
        val now = millisAt(2026, 9, 11, 12, 0, 0)
        // 59 秒前
        assertEquals("刚刚", TimeFormat.relativeDate(millisAt(2026, 9, 11, 11, 59, 1), now))
        // 0 秒（同一自然日）
        assertEquals("刚刚", TimeFormat.relativeDate(now, now))
    }

    @Test
    fun minutesAgoFloorsToWholeMinutes() {
        val now = millisAt(2026, 9, 11, 12, 0, 0)
        // 整 5 分钟
        assertEquals("5 分钟前", TimeFormat.relativeDate(millisAt(2026, 9, 11, 11, 55, 0), now))
        // 90 秒前 → 向下取整为 1 分钟
        assertEquals("1 分钟前", TimeFormat.relativeDate(millisAt(2026, 9, 11, 11, 58, 30), now))
    }

    @Test
    fun thresholdsAtOneMinuteAndOneHour() {
        val now = millisAt(2026, 9, 11, 12, 0, 0)
        // 恰好 60 秒 → 1 分钟前
        assertEquals("1 分钟前", TimeFormat.relativeDate(millisAt(2026, 9, 11, 11, 59, 0), now))
        // 59 分 59 秒 → 59 分钟前（未满 60 分钟）
        assertEquals("59 分钟前", TimeFormat.relativeDate(millisAt(2026, 9, 11, 11, 0, 1), now))
        // 恰好 60 分钟 → 1 小时前
        assertEquals("1 小时前", TimeFormat.relativeDate(millisAt(2026, 9, 11, 11, 0, 0), now))
    }

    @Test
    fun hoursAgoWithinSameDay() {
        val now = millisAt(2026, 9, 11, 12, 0, 0)
        assertEquals("3 小时前", TimeFormat.relativeDate(millisAt(2026, 9, 11, 9, 0, 0), now))
    }

    @Test
    fun crossDayPrefersYesterdayOverHours() {
        // now 今天 00:30，target 昨天 23:30：只差 1 小时，但按口径「跨天优先昨天」→ 昨天
        val now = millisAt(2026, 9, 11, 0, 30, 0)
        assertEquals("昨天", TimeFormat.relativeDate(millisAt(2026, 9, 10, 23, 30, 0), now))
    }

    @Test
    fun sameDayNearMidnightStillUsesHours() {
        // now 今天 23:59，target 今天 00:00：差 23h59m，仍属同一自然日 → 小时档（取整为 23）
        assertEquals(
            "23 小时前",
            TimeFormat.relativeDate(millisAt(2026, 9, 11, 0, 0, 0), millisAt(2026, 9, 11, 23, 59, 0))
        )
        // target 今天 23:58（now 今天 23:59）：跨午夜前一刻仍走分钟档，不回落「今天」
        assertEquals(
            "1 分钟前",
            TimeFormat.relativeDate(millisAt(2026, 9, 11, 23, 58, 0), millisAt(2026, 9, 11, 23, 59, 0))
        )
    }

    @Test
    fun futureTimestampKeepsDayGranularity() {
        // 目标晚于 now（未来时间）不进入分钟/小时档，保持既有天粒度行为
        val now = millisAt(2026, 9, 11, 12, 0, 0)
        assertEquals("今天", TimeFormat.relativeDate(millisAt(2026, 9, 11, 15, 0, 0), now))
    }
}
