package com.mynote.app.ui.notes

import androidx.compose.ui.text.input.TextFieldValue

/**
 * 编辑正文的撤销/重做栈。纯 Kotlin、无 Android 依赖，便于单元测试。
 * 用法：每次 onValueChange 前用「修改前的值」调用 record()；
 * 撤销时用「当前值」调用 undo() 并写回返回值。
 */
class NoteUndoController(private val maxEntries: Int = 100) {

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 记录一次编辑前的快照；任何新编辑都会清空 redo 栈。超出 maxEntries 丢弃最旧。 */
    fun record(before: TextFieldValue) {
        redoStack.clear()
        undoStack.addLast(before)
        while (undoStack.size > maxEntries) {
            undoStack.removeFirst()
        }
    }

    /** 弹出最近快照返回之，同时把 current 压入 redo 栈；undo 栈空返回 null。 */
    fun undo(current: TextFieldValue): TextFieldValue? {
        val before = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return before
    }

    /** 与 undo 对称：弹出最近 redo 快照返回之，同时把 current 压入 undo 栈；空返回 null。 */
    fun redo(current: TextFieldValue): TextFieldValue? {
        val after = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return after
    }

    /** 清空两个栈（切换笔记/回填时调用）。 */
    fun reset() {
        undoStack.clear()
        redoStack.clear()
    }
}
