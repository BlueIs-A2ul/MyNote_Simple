package com.mynote.app.data.ai

import com.mynote.app.ui.notes.NoteContentParser

object AiPromptBuilder {

    /** 正文超限被截断时追加的提示语。 */
    private const val TRUNCATED_NOTICE = "（正文过长，已截断）"

    /**
     * 网页上下文未建立时（新会话 / 上下文丢失）附带笔记正文；
     * 已有上下文时只发用户输入（设计 §7）。
     *
     * @param maxNoteChars 正文（剥离图片标记后的纯文本）字符上限，超出部分截断并追加
     *   「（正文过长，已截断）」；标题不计入上限。默认不限，保持旧行为不变。
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
        return "【笔记正文】\n$titleLine$body\n\n【要求】\n$input"
    }
}
