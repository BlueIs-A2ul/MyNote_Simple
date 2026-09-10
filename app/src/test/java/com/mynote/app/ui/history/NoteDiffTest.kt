package com.mynote.app.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDiffTest {

    @Test
    fun identicalContentIsAllUnchanged() {
        val lines = NoteDiff.diff("a\nb", "a\nb")
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.type == NoteDiff.Type.UNCHANGED })
    }

    @Test
    fun appendedLineIsAdded() {
        val lines = NoteDiff.diff("a", "a\nb")
        assertEquals(listOf(NoteDiff.Type.UNCHANGED, NoteDiff.Type.ADDED), lines.map { it.type })
    }

    @Test
    fun removedLineIsRemoved() {
        val lines = NoteDiff.diff("a\nb", "a")
        assertEquals(listOf(NoteDiff.Type.UNCHANGED, NoteDiff.Type.REMOVED), lines.map { it.type })
    }

    @Test
    fun changedLinePairsAndEmphasizesOnlyChangedChars() {
        val lines = NoteDiff.diff("abc", "axc")
        assertEquals(2, lines.size)
        assertEquals(NoteDiff.Type.REMOVED, lines[0].type)
        assertEquals(NoteDiff.Type.ADDED, lines[1].type)
        assertEquals(listOf(1..1), lines[0].emphasis)
        assertEquals(listOf(1..1), lines[1].emphasis)
    }

    @Test
    fun unchangedAffixesAreNotEmphasized() {
        val lines = NoteDiff.diff("hello world", "hello there")
        assertEquals(listOf(6..10), lines[0].emphasis)
        assertEquals(listOf(6..10), lines[1].emphasis)
    }

    @Test
    fun crlfIsTreatedAsLf() {
        val lines = NoteDiff.diff("a\r\nb", "a\nb")
        assertTrue(lines.all { it.type == NoteDiff.Type.UNCHANGED })
    }

    @Test
    fun emptyOldMeansAllAdded() {
        val lines = NoteDiff.diff("", "a\nb")
        assertEquals(listOf(NoteDiff.Type.ADDED, NoteDiff.Type.ADDED), lines.map { it.type })
    }

    @Test
    fun emptyNewMeansAllRemoved() {
        val lines = NoteDiff.diff("a\nb", "")
        assertEquals(listOf(NoteDiff.Type.REMOVED, NoteDiff.Type.REMOVED), lines.map { it.type })
    }

    @Test
    fun unpairedLineIsFullyEmphasized() {
        val lines = NoteDiff.diff("a\nb", "a\nc\nd")
        // 删除 b / 新增 c、d：b 与 c 配对，d 未配对 → 整行强调
        val added = lines.filter { it.type == NoteDiff.Type.ADDED }
        assertEquals(2, added.size)
        assertTrue(added[1].emphasis.isNotEmpty())
    }

    @Test
    fun largeDiffFallsBackToRemoveAllThenAddAll() {
        val old = (1..1100).joinToString("\n") { "old $it" }
        val new = (1..1100).joinToString("\n") { "new $it" }
        val lines = NoteDiff.diff(old, new)
        assertEquals(2200, lines.size)
        assertTrue(lines.take(1100).all { it.type == NoteDiff.Type.REMOVED })
        assertTrue(lines.drop(1100).all { it.type == NoteDiff.Type.ADDED })
    }
}
