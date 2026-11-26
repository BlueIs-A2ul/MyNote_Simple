package com.mynote.app.data.settings

import android.content.Context

/**
 * 主页分类筛选的持久化存储（SharedPreferences）。
 *
 * 只存「最后一次选择」的 token，编码/解码在 ui 层（`CategoryFilter.encodeToken` /
 * `decodeCategoryFilter`）：这里刻意不引用 ui 包，避免 data → ui 的反向依赖。
 * 不做 StateFlow —— 唯一写入方是 NotesViewModel，且只在构造时读一次。
 */
class NoteFilterStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 上次选择的筛选 token；从未选择过时为 null。 */
    fun read(): String? = prefs.getString(KEY_NOTE_FILTER, null)

    /** 任何一次筛选切换都覆写，保证「回落的全部」也同步落到存储里。 */
    fun write(token: String) {
        prefs.edit().putString(KEY_NOTE_FILTER, token).apply()
    }

    companion object {
        /** 仅测试复位用；业务侧请勿跨包直接操作这个命名空间。 */
        internal const val PREFS_NAME = "note_filter_settings"
        internal const val KEY_NOTE_FILTER = "note_filter"
    }
}
