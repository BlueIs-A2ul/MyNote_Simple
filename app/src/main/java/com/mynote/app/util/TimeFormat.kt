package com.mynote.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeFormat {
    private fun formatter(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault())

    fun dateTime(timestamp: Long): String = formatter("yyyy-MM-dd HH:mm").format(Date(timestamp))

    fun date(timestamp: Long): String = formatter("yyyy-MM-dd").format(Date(timestamp))

    /** 列表用相对日期：今天 / 昨天 / M月d日 / yyyy年M月d日。 */
    fun relativeDate(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            isSameDay(target, today) -> "今天"
            isSameDay(target, yesterday) -> "昨天"
            target.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
                "${target.get(Calendar.MONTH) + 1}月${target.get(Calendar.DAY_OF_MONTH)}日"
            else ->
                "${target.get(Calendar.YEAR)}年${target.get(Calendar.MONTH) + 1}月${target.get(Calendar.DAY_OF_MONTH)}日"
        }
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.ERA) == b.get(Calendar.ERA) &&
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
