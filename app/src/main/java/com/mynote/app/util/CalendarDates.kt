package com.mynote.app.util

import java.util.Calendar
import java.util.TimeZone

/**
 * 日历计算纯函数（`java.util.Calendar` 实现：minSdk 24 未开启 desugaring，不用 `java.time`）。
 *
 * 约定：
 * - 所有「天」都以**本地零点毫秒**表示（与 `NoteEntity.noteDate` 存储一致）；
 * - 周固定**周一起始**（中文语境，不随系统区域变化）；
 * - 输入输出全部来自参数，便于单测。
 */
object CalendarDates {

    const val DAYS_PER_WEEK = 7

    /** 当天本地零点毫秒。 */
    fun dayStart(millis: Long): Long {
        val c = calendarOf(millis)
        return dayStart(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** 指定年月日（month 1–12）的本地零点毫秒。 */
    fun dayStart(year: Int, month: Int, day: Int): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day)
    }.timeInMillis

    /** 次日零点（用 DAY_OF_YEAR 推进，跨夏令时也得到下一个本地零点）。 */
    fun nextDay(dayStartMillis: Long): Long = calendarOf(dayStartMillis).apply {
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    /** 某月 1 号的本地零点毫秒。 */
    fun monthStart(year: Int, month: Int): Long = dayStart(year, month, 1)

    /** 本地零点毫秒对应的「日」数字（1–31）。 */
    fun dayOfMonth(dayStartMillis: Long): Int = calendarOf(dayStartMillis).get(Calendar.DAY_OF_MONTH)

    /** 月份加减（delta 可为负），返回新的 (year, month)。 */
    fun addMonths(year: Int, month: Int, delta: Int): Pair<Int, Int> {
        val total = year * 12 + (month - 1) + delta
        return Math.floorDiv(total, 12) to (Math.floorMod(total, 12) + 1)
    }

    fun daysInMonth(year: Int, month: Int): Int = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1)
    }.getActualMaximum(Calendar.DAY_OF_MONTH)

    /** 月首相对周一的对齐偏移（周一 = 0 … 周日 = 6）。 */
    fun firstDayOffset(year: Int, month: Int): Int = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1)
    }.let { (it.get(Calendar.DAY_OF_WEEK) + 5) % DAYS_PER_WEEK }

    /**
     * 月网格：前导 null（占位上月空档）+ 本月每日零点毫秒，总格数为 7 的整数倍（5–6 行）。
     */
    fun monthGrid(year: Int, month: Int): List<Long?> {
        val offset = firstDayOffset(year, month)
        val days = daysInMonth(year, month)
        val cells = (offset + days + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK * DAYS_PER_WEEK
        return List(cells) { index ->
            val day = index - offset + 1
            if (day in 1..days) dayStart(year, month, day) else null
        }
    }

    /**
     * Material3 DatePicker 的 `selectedDateMillis` 是所选日期的 **UTC 零点**，
     * 展示/入库前必须换算成本地零点，否则 UTC+8 等时区会偏移一天。
     */
    fun pickerMillisToLocalDay(utcMillis: Long): Long {
        val utc = utcCalendarOf(utcMillis)
        return dayStart(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH) + 1, utc.get(Calendar.DAY_OF_MONTH))
    }

    /** 本地零点毫秒 → DatePicker 初始值（所选日期的 UTC 零点毫秒）。 */
    fun localDayToPickerMillis(localMillis: Long): Long {
        val local = calendarOf(localMillis)
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    private fun calendarOf(millis: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = millis }

    private fun utcCalendarOf(millis: Long): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
}
