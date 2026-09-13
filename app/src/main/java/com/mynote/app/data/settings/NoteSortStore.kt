package com.mynote.app.data.settings

import android.content.Context
import com.mynote.app.data.db.NoteSortMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 笔记列表排序方式的持久化存储（SharedPreferences）。 */
class NoteSortStore(context: Context) {

    private val prefs = context.getSharedPreferences("note_sort_settings", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(read())
    val mode: StateFlow<NoteSortMode> = _mode.asStateFlow()

    fun setMode(mode: NoteSortMode) {
        prefs.edit().putString(KEY_NOTE_SORT, mode.name).apply()
        _mode.value = mode
    }

    private fun read(): NoteSortMode =
        prefs.getString(KEY_NOTE_SORT, null)
            ?.let { runCatching { NoteSortMode.valueOf(it) }.getOrNull() }
            ?: NoteSortMode.UPDATED_DESC

    private companion object {
        const val KEY_NOTE_SORT = "note_sort"
    }
}
