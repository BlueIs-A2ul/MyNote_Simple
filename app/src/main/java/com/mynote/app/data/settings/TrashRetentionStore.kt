package com.mynote.app.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 回收站保留期设置：天数（默认 30 天，可选 7/30/90）。
 * SharedPreferences 持久化 + StateFlow 暴露，模式与 ThemeSettingsStore 一致。
 */
class TrashRetentionStore(context: Context) {

    private val prefs = context.getSharedPreferences("trash_retention_settings", Context.MODE_PRIVATE)

    private val _retentionDays = MutableStateFlow(
        prefs.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS).coerceIn(OPTIONS.min(), OPTIONS.max())
    )
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

    fun setRetentionDays(days: Int) {
        val v = days.coerceIn(OPTIONS.min(), OPTIONS.max())
        prefs.edit().putInt(KEY_RETENTION_DAYS, v).apply()
        _retentionDays.value = v
    }

    companion object {
        /** 保留期可选档位：7 / 30 / 90 天。 */
        val OPTIONS = intArrayOf(7, 30, 90)

        const val DEFAULT_RETENTION_DAYS = 30

        private const val KEY_RETENTION_DAYS = "retention_days"

        /** 一天毫秒数。 */
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** 天数转毫秒。 */
        fun ttlMs(days: Int): Long = days * DAY_MS

        /**
         * 条目 90：删除确认框的统一文案。保留期可配置（7/30/90），
         * 原先两处确认框把「30 天」写死，与设置页口径不一致。
         */
        fun deleteHint(days: Int): String =
            "删除后将移入回收站，保留 " + days + " 天，期间可随时恢复。"

        /** 批量删除确认文案（条目 90）：与单条删除同口径，只多了条数。 */
        fun batchDeleteHint(count: Int, days: Int): String =
            "将把所选 " + count + " 条笔记移入回收站，保留 " + days + " 天，期间可恢复。"

        /**
         * 条目 90：剩余可恢复天数，向上取整（避免「还有 0 天」这种误导），已到期返回 0。
         */
        fun remainingDays(deletedAt: Long, days: Int, now: Long = System.currentTimeMillis()): Long {
            val left = deletedAt + ttlMs(days) - now
            if (left <= 0L) return 0L
            return (left + DAY_MS - 1) / DAY_MS
        }
    }
}