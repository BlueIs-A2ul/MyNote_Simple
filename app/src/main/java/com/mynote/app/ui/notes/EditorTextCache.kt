package com.mynote.app.ui.notes

import androidx.compose.ui.text.input.TextFieldValue

/**
 * 条目 100⑦：编辑态正文的进程内缓存。
 *
 * 正文原先整体进 `rememberSaveable`（saved instance state / Bundle），超长笔记有
 * `TransactionTooLargeException` 风险。改为：Bundle 只存 noteId 键，正文放这里。
 * - 旋转/配置变更：同进程，缓存命中，正文不丢；
 * - 进程被杀：缓存清空，正文由退后台落库的草稿回填（ON_STOP 兜底）。
 *
 * 容量很小（最近 4 篇），避免成为变相的内存缓存。
 */
object EditorTextCache {

    private const val MAX_ENTRIES = 4

    /** accessOrder = true + 重写 removeEldestEntry：最近访问的 4 篇留在内存里。 */
    private val lru = object : LinkedHashMap<Long, TextFieldValue>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TextFieldValue>?): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun put(key: Long, value: TextFieldValue) {
        lru[key] = value
    }

    @Synchronized
    fun get(key: Long): TextFieldValue? = lru[key]

    @Synchronized
    fun clear() {
        lru.clear()
    }
}
