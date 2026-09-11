package com.mynote.app.data.ai

import com.mynote.app.ui.notes.NoteContentParser

object AiPromptBuilder {

    /**
     * 网页上下文未建立时（新会话 / 上下文丢失）附带笔记正文；
     * 已有上下文时只发用户输入（设计 §7）。
     */
    fun build(
        noteTitle: String,
        noteContent: String,
        userInput: String,
        includeNoteContext: Boolean
    ): String {
        val input = userInput.trim()
        if (!includeNoteContext) return input
        val plain = NoteContentParser.plainText(noteContent).trim()
        if (plain.isEmpty()) return input
        val titleLine = noteTitle.trim().takeIf { it.isNotEmpty() }?.let { "# $it\n" } ?: ""
        return "【笔记正文】\n$titleLine$plain\n\n【要求】\n$input"
    }
}
