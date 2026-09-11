package com.mynote.app.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** AI 回答落入编辑器的纯逻辑：插入到光标 / 替换选区。 */
object AiResultApplier {

    enum class Type { INSERT, REPLACE }

    fun apply(current: TextFieldValue, type: Type, text: String): TextFieldValue {
        val length = current.text.length
        return when (type) {
            Type.INSERT -> {
                val at = current.selection.start.coerceIn(0, length)
                val newText = current.text.substring(0, at) + text + current.text.substring(at)
                TextFieldValue(newText, TextRange(at + text.length))
            }
            Type.REPLACE -> {
                if (current.selection.collapsed) return current
                val start = current.selection.min.coerceIn(0, length)
                val end = current.selection.max.coerceIn(0, length)
                val newText = current.text.substring(0, start) + text + current.text.substring(end)
                TextFieldValue(newText, TextRange(start + text.length))
            }
        }
    }
}
