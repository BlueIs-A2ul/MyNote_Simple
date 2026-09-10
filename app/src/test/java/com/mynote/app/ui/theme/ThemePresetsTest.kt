package com.mynote.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePresetsTest {

    @Test
    fun hasEightPresets() {
        assertEquals(8, ThemePresets.all.size)
    }

    @Test
    fun firstPresetKeepsCurrentDefaultColors() {
        val first = ThemePresets.all.first()
        assertEquals(IndigoPrimary, first.light.primary)
        assertEquals(IndigoContainer, first.light.primaryContainer)
        assertEquals(TealAccent, first.light.secondary)
    }

    @Test
    fun resolveOutOfRangeFallsBackToFirst() {
        assertEquals(ThemePresets.all.first(), ThemePresets.resolve(99))
        assertEquals(ThemePresets.all.first(), ThemePresets.resolve(-1))
    }

    @Test
    fun resolveInRangeReturnsPreset() {
        assertEquals(ThemePresets.all[3], ThemePresets.resolve(3))
    }

    @Test
    fun labelsAreUnique() {
        assertEquals(ThemePresets.all.size, ThemePresets.all.map { it.label }.distinct().size)
    }
}
