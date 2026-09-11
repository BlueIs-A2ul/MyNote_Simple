package com.mynote.app.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiResultApplierTest {

    @Test
    fun insertAtCursorKeepsSurroundingText() {
        val current = TextFieldValue("ab", TextRange(1))
        val result = AiResultApplier.apply(current, AiResultApplier.Type.INSERT, "XY")
        assertEquals("aXYb", result.text)
        assertEquals(TextRange(3), result.selection)
    }

    @Test
    fun insertAtEnd() {
        val current = TextFieldValue("ab", TextRange(2))
        val result = AiResultApplier.apply(current, AiResultApplier.Type.INSERT, "!")
        assertEquals("ab!", result.text)
        assertEquals(TextRange(3), result.selection)
    }

    @Test
    fun replaceSelection() {
        val current = TextFieldValue("hello world", TextRange(6, 11))
        val result = AiResultApplier.apply(current, AiResultApplier.Type.REPLACE, "地球")
        assertEquals("hello 地球", result.text)
        assertEquals(TextRange(8), result.selection)
    }

    @Test
    fun replaceWithCollapsedSelectionIsNoOp() {
        val current = TextFieldValue("hello", TextRange(2))
        val result = AiResultApplier.apply(current, AiResultApplier.Type.REPLACE, "X")
        assertEquals("hello", result.text)
    }

    @Test
    fun selectionOutOfRangeIsClamped() {
        val current = TextFieldValue("ab", TextRange(5))
        val result = AiResultApplier.apply(current, AiResultApplier.Type.INSERT, "X")
        assertEquals("abX", result.text)
    }
}
