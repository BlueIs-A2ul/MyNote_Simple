package com.mynote.app.ui.notes

import androidx.compose.ui.text.input.TextFieldValue

/**
 * 编辑正文的撤销/重做栈。纯 Kotlin、无 Android 依赖，便于单元测试。
 * 用法：每次 onValueChange 前用「修改前的值」调用 record()；
 * 撤销时用「当前值」调用 undo() 并写回返回值。
 *
 * 合并策略：连续快速输入（相邻单字符增删）或纯选区移动会合并进同一格，
 * 一次撤销回退一整段；停顿超过 [MERGE_WINDOW_MS] 或传 `force = true`
 * （插图、贴 AI 结果等结构性变更）则强制新开一格。
 */
class NoteUndoController(private val maxEntries: Int = 100) {

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private var lastRecordMillis = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * 记录一次编辑前的快照；任何新编辑都会清空 redo 栈。超出 maxEntries 丢弃最旧。
     * @param before 修改前的编辑状态
     * @param atMillis 记录时刻，用于判定合并窗口
     * @param force true 时跳过合并、强制新开一格
     */
    fun record(before: TextFieldValue, atMillis: Long = System.currentTimeMillis(), force: Boolean = false) {
        redoStack.clear()
        if (!force && mergeable(before, atMillis)) {
            // 同一段连续输入/纯选区移动：用本次状态替换栈顶，避免一格一字符
            undoStack.removeLast()
        }
        undoStack.addLast(before)
        while (undoStack.size > maxEntries) {
            undoStack.removeFirst()
        }
        lastRecordMillis = atMillis
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
        lastRecordMillis = 0L
    }

    /** 窗口内 + 文本相同（仅选区变化）或相邻单字符增删时才合并。 */
    private fun mergeable(before: TextFieldValue, atMillis: Long): Boolean {
        if (undoStack.isEmpty()) return false
        if (atMillis - lastRecordMillis > MERGE_WINDOW_MS) return false
        val prev = undoStack.last()
        return prev.text == before.text || isAdjacentSingleCharEdit(prev.text, before.text)
    }

    /** 两文本长度差恰好为 1，且允许跳过较长串中一个字符后其余逐位相等（任意位置的单字符插入/删除）。 */
    private fun isAdjacentSingleCharEdit(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) != 1) return false
        var i = 0
        var j = 0
        var skipped = false
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
            } else if (!skipped) {
                skipped = true
                if (a.length > b.length) i++ else j++
            } else {
                return false
            }
        }
        return true
    }

    companion object {
        /** 合并窗口：两次 record 间隔在此内才考虑合并。 */
        const val MERGE_WINDOW_MS = 600L
    }
}
