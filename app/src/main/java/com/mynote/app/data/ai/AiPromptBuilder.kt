package com.mynote.app.data.ai

import com.mynote.app.ui.notes.NoteContentParser

object AiPromptBuilder {

    /** 附带笔记正文的消息表头；`AiApiMessageBuilder` 用它做幂等判定。 */
    const val NOTE_CONTEXT_HEADER = "【笔记正文】"

    /** 正文超限被截断时追加的提示语。 */
    private const val TRUNCATED_NOTICE = "（正文过长，已截断）"

    /** 该消息是否已附带笔记正文（避免重复包裹）。 */
    fun hasNoteContext(text: String): Boolean = text.trimStart().startsWith(NOTE_CONTEXT_HEADER)

    /**
     * 组装首条用户消息：`includeNoteContext` 为 true 时（历史里还没有 user 消息）附带笔记正文，
     * 之后各轮只回传用户输入，避免每轮重复整篇正文（接线见 `AiApiMessageBuilder`）。
     *
     * @param maxNoteChars 正文（剥离图片标记后的纯文本）字符上限，超出部分截断并追加
     *   「（正文过长，已截断）」；标题不计入上限。生产调用方总会显式传入预算值，
     *   默认值仅供不关心预算的调用方使用。
     */
    fun build(
        noteTitle: String,
        noteContent: String,
        userInput: String,
        includeNoteContext: Boolean,
        maxNoteChars: Int = Int.MAX_VALUE
    ): String {
        val input = userInput.trim()
        if (!includeNoteContext) return input
        val plain = NoteContentParser.plainText(noteContent).trim()
        if (plain.isEmpty()) return input
        val body = if (plain.length > maxNoteChars) {
            plain.take(maxNoteChars.coerceAtLeast(0)) + TRUNCATED_NOTICE
        } else {
            plain
        }
        val titleLine = noteTitle.trim().takeIf { it.isNotEmpty() }?.let { "# $it\n" } ?: ""
        return "$NOTE_CONTEXT_HEADER\n$titleLine$body\n\n【要求】\n$input"
    }
}
