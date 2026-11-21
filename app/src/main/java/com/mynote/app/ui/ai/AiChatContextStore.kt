package com.mynote.app.ui.ai

/**
 * 编辑页 → AI 页的进程内上下文交接。
 *
 * 不能把整篇正文放进 SavedStateHandle（会随 Activity state 写入 Binder，超长笔记有
 * TransactionTooLargeException 风险）；这里只保存最近 3 篇的引用，进程重建后缓存为空，
 * AI 页会回退到仓库读取（退后台时已有草稿落库）。
 */
internal object AiChatContextStore {

    data class Context(
        val title: String,
        val content: String,
        val selectionStart: Int,
        val selectionEnd: Int
    ) {
        val hasSelection: Boolean get() = selectionEnd > selectionStart
    }

    private const val MAX_ENTRIES = 3

    private val entries = object : LinkedHashMap<Long, Context>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Context>?): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun put(noteId: Long, title: String, content: String, selectionStart: Int, selectionEnd: Int) {
        entries[noteId] = Context(title, content, selectionStart, selectionEnd)
    }

    @Synchronized
    fun get(noteId: Long): Context? = entries[noteId]

    @Synchronized
    fun remove(noteId: Long) {
        entries.remove(noteId)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
