package com.mynote.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeFormat {
    private const val SECOND_MS = 1_000L
    private const val MINUTE_MS = 60 * SECOND_MS
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS

    private fun formatter(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault())

    fun dateTime(timestamp: Long): String = formatter("yyyy-MM-dd HH:mm").format(Date(timestamp))

    fun date(timestamp: Long): String = formatter("yyyy-MM-dd").format(Date(timestamp))

    /**
     * 列表用相对时间：刚刚 / N 分钟前 / N 小时前 / 今天 / 昨天 / M月d日 / yyyy年M月d日。
     *
     * 分钟、小时级档位插在原有天粒度档位之前，但只在目标与 now 处于「同一自然日」且目标
     * 不晚于 now 时生效：跨天时优先沿用既有「昨天」档，避免与天粒度语义打架（例如今天
     * 00:30 看昨天 23:30，虽然只差 1 小时，仍显示「昨天」）。主页每分钟 tick 一次，
     * 同一天内的改动因此能看到分钟级变化。
     */
    fun relativeDate(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val sameDay = isSameDay(target, today)
        recentLabel(timestamp, now, sameDay)?.let { return it }
        return when {
            sameDay -> "今天"
            isSameDay(target, yesterday) -> "昨天"
            target.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
                "${target.get(Calendar.MONTH) + 1}月${target.get(Calendar.DAY_OF_MONTH)}日"
            else ->
                "${target.get(Calendar.YEAR)}年${target.get(Calendar.MONTH) + 1}月${target.get(Calendar.DAY_OF_MONTH)}日"
        }
    }

    /**
     * 分钟/小时级档位的纯函数：不读系统时钟，输入全部来自参数，便于单测。
     *
     * 不适用时返回 null（跨天、目标晚于 now、或已满 24 小时），由调用方回落到天粒度档位：
     * - `[0, 60s)`  → 刚刚
     * - `[60s, 60min)` → N 分钟前（整分向下取整）
     * - `[60min, 24h)` → N 小时前（整小时向下取整）
     */
    internal fun recentLabel(targetMillis: Long, nowMillis: Long, sameDay: Boolean): String? {
        if (!sameDay) return null
        val elapsed = nowMillis - targetMillis
        if (elapsed < 0) return null
        return when {
            elapsed < MINUTE_MS -> "刚刚"
            elapsed < HOUR_MS -> "${elapsed / MINUTE_MS} 分钟前"
            elapsed < DAY_MS -> "${elapsed / HOUR_MS} 小时前"
            else -> null
        }
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.ERA) == b.get(Calendar.ERA) &&
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
