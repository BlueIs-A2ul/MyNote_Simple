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
        controller.record(value("a"))
        controller.record(value("ab"))
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
}
