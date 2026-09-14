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

        /** 天数转毫秒。 */
        fun ttlMs(days: Int): Long = days * 24L * 60 * 60 * 1000
    }
}