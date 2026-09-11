package com.mynote.app.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperPaletteTest {

    @Test
    fun paletteHasSixColors() {
        assertEquals(6, PaperCategoryColors.size)
    }

    @Test
    fun paperColorsMapToThemselves() {
        PaperCategoryColors.forEach { color ->
            assertEquals(color, PaperPalette.nearest(color.toArgb()))
        }
    }

    @Test
    fun saturatedRedMapsToOchre() {
        assertEquals(PaperCategoryColors[0], PaperPalette.nearest(0xFFEF5350.toInt()))
    }

    @Test
    fun saturatedGreenMapsToMoss() {
        assertEquals(PaperCategoryColors[1], PaperPalette.nearest(0xFF66BB6A.toInt()))
    }

    @Test
    fun saturatedBlueMapsToIndigoGray() {
        assertEquals(PaperCategoryColors[2], PaperPalette.nearest(0xFF42A5F5.toInt()))
    }

    @Test
    fun lowSaturationFallsBackToFirst() {
        assertEquals(PaperCategoryColors[0], PaperPalette.nearest(0xFF9E9E9E.toInt()))
    }

    @Test
    fun nearestResultAlwaysInPalette() {
        val samples = listOf(0xFFEF9A9A, 0xFFFFCC80, 0xFFFFF59D, 0xFFA5D6A7, 0xFF80DEEA, 0xFFB39DDB)
        samples.forEach { argb ->
            assertTrue(PaperPalette.nearest(argb.toInt()) in PaperCategoryColors)
        }
    }
}
