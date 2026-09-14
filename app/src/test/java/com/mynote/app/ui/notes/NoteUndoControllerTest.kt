package com.mynote.app.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NoteUndoController 的纯 JVM 单元测试（无需 Robolectric），覆盖撤销/重做栈的压栈、弹栈与上限行为。 */
class NoteUndoControllerTest {

    /** 构造文本快照，光标置于末尾，便于比较。 */
    private fun value(text: String): TextFieldValue = TextFieldValue(text, TextRange(text.length))

    @Test
    fun recordEnablesUndoAndDisablesRedo() {
        val controller = NoteUndoController()
        controller.record(value("a"))
        assertTrue(controller.canUndo)
        assertTrue(!controller.canRedo)
    }

    @Test
    fun undoReturnsLastSnapshotAndRedoRestoresCurrent() {
        val controller = NoteUndoController()
        val before = value("abc")
        val current = value("abcd")
        controller.record(before)

        // 撤销返回最近一次 record 的快照，并把当前值压入 redo 栈
        assertEquals(before, controller.undo(current))
        assertTrue(!controller.canUndo)
        assertTrue(controller.canRedo)

        // 重做返回此前压入 redo 栈的当前值，两个栈恢复原状
        assertEquals(current, controller.redo(value("x")))
        assertTrue(controller.canUndo)
        assertTrue(!controller.canRedo)
    }

    @Test
    fun undoAndRedoReturnNullWhenStacksEmpty() {
        val controller = NoteUndoController()
        assertNull(controller.undo(value("a")))
        assertNull(controller.redo(value("a")))
    }

    @Test
    fun consecutiveRecordsUndoInReverseOrderAndRedoForward() {
        val controller = NoteUndoController()
        val a = value("a")
        val b = value("b")
        controller.record(a)
        controller.record(b)

        // 连续撤销依次得到 B、A，之后无快照可撤销
        assertEquals(b, controller.undo(b))
        assertEquals(a, controller.undo(a))
        assertNull(controller.undo(a))

        // 重做按与撤销相反的顺序恢复：先 A 后 B
        assertEquals(a, controller.redo(a))
        assertEquals(b, controller.redo(b))
    }

    @Test
    fun newRecordAfterUndoClearsRedoStack() {
        val controller = NoteUndoController()
        controller.record(value("a"))
        controller.record(value("ab"))
        controller.undo(value("abc"))
        assertTrue(controller.canRedo)

        // 撤销后的新编辑使 redo 栈失效
        controller.record(value("x"))
        assertTrue(!controller.canRedo)
        assertTrue(controller.canUndo)
    }

    @Test
    fun maxEntriesDropsOldestSnapshots() {
        val controller = NoteUndoController(maxEntries = 2)
        controller.record(value("1"))
        controller.record(value("2"))
        controller.record(value("3"))

        // 最旧的第 1 次快照被丢弃，只能撤销到第 2、第 1 次（按入栈序为第 2、第 3 次 record）
        assertEquals(value("3"), controller.undo(value("4")))
        assertEquals(value("2"), controller.undo(value("3")))
        assertNull(controller.undo(value("2")))
    }

    @Test
    fun resetClearsBothStacks() {
        val controller = NoteUndoController()
        // 显式拉开记录时刻，避免命中 600ms 合并窗口（"a"→"ab" 是相邻插入会被合并）
        controller.record(value("a"), atMillis = 0L)
        controller.record(value("ab"), atMillis = 700L)
        controller.undo(value("abc"))
        assertTrue(controller.canUndo)
        assertTrue(controller.canRedo)

        controller.reset()
        assertTrue(!controller.canUndo)
        assertTrue(!controller.canRedo)
        assertNull(controller.undo(value("x")))
        assertNull(controller.redo(value("x")))
    }

    @Test
    fun undoPushesCurrentValueIntoRedoStack() {
        val controller = NoteUndoController()
        controller.record(value("a"))

        // undo 用当前值压栈：undo("b") 之后 redo 应返回 "b"
        assertEquals(value("a"), controller.undo(value("b")))
        assertEquals(value("b"), controller.redo(value("a")))
    }

    // ---------- 连续输入合并/防抖（backlog 条目 22） ----------

    private fun valueWithSelection(text: String, cursor: Int): TextFieldValue =
        TextFieldValue(text, TextRange(cursor))

    /** 构造带「记录时刻」的快捷调用，方便测试合并窗口。 */
    private fun recordAt(controller: NoteUndoController, text: String, atMillis: Long, force: Boolean = false) {
        controller.record(value(text), atMillis = atMillis, force = force)
    }

    @Test
    fun typingChainMergesIntoSingleUndoStep() {
        val controller = NoteUndoController()
        recordAt(controller, "", 0L)
        recordAt(controller, "a", 100L)
        recordAt(controller, "ab", 200L)
        recordAt(controller, "abc", 300L)

        // 整段连打只占一格：一次撤销回退整段，再撤销无可用快照
        assertEquals(value("abc"), controller.undo(value("abcd")))
        assertNull(controller.undo(value("abc")))
    }

    @Test
    fun deletionChainMerges() {
        val controller = NoteUndoController()
        recordAt(controller, "abc", 0L)
        recordAt(controller, "ab", 100L)

        assertEquals(value("ab"), controller.undo(value("a")))
        assertNull(controller.undo(value("ab")))
    }

    @Test
    fun windowElapsedBreaksMerge() {
        val controller = NoteUndoController()
        recordAt(controller, "a", 0L)
        recordAt(controller, "ab", 700L) // 超过 600ms 窗口

        // 两个独立快照：连撤两次各回退一格
        assertEquals(value("ab"), controller.undo(value("abc")))
        assertEquals(value("a"), controller.undo(value("ab")))
        assertNull(controller.undo(value("a")))
    }

    @Test
    fun forceBreaksMerge() {
        val controller = NoteUndoController()
        recordAt(controller, "a", 0L)
        recordAt(controller, "ab", 100L, force = true) // 结构性变更强制新格：与「a」断开
        recordAt(controller, "abc", 200L) // 其后普通输入照常合并进「ab」格

        // 撤销依次回退「abc」与「a」两格（force 在「ab」处断开），而非三格
        assertEquals(value("abc"), controller.undo(value("abcd")))
        assertEquals(value("a"), controller.undo(value("abc")))
        assertNull(controller.undo(value("a")))
    }

    @Test
    fun middleInsertionMerges() {
        val controller = NoteUndoController()
        recordAt(controller, "ac", 0L)
        recordAt(controller, "abc", 100L) // 中间插入一个字符

        assertEquals(value("abc"), controller.undo(value("abcd")))
        assertNull(controller.undo(value("abc")))
    }

    @Test
    fun selectionOnlyChangeCollapses() {
        val controller = NoteUndoController()
        controller.record(valueWithSelection("a", 0), atMillis = 0L)
        controller.record(valueWithSelection("a", 1), atMillis = 50L)

        // 同文本仅选区变化：合并为一条
        assertEquals(value("a"), controller.undo(valueWithSelection("a", 1)))
        assertNull(controller.undo(value("a")))
    }

    @Test
    fun replacementDoesNotMerge() {
        val controller = NoteUndoController()
        recordAt(controller, "ab", 0L)
        recordAt(controller, "ax", 100L) // 替换不是单字符增删

        assertEquals(value("ax"), controller.undo(value("axx")))
        assertEquals(value("ab"), controller.undo(value("ax")))
        assertNull(controller.undo(value("ab")))
    }
}
