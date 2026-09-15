package com.mynote.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekModelsTest {

    @Test
    fun defaultIsFlash() {
        assertEquals(DeepSeekModels.FLASH, DeepSeekModels.DEFAULT)
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), DeepSeekModels.all)
    }

    @Test
    fun validityCheck() {
        assertTrue(DeepSeekModels.isValid(DeepSeekModels.FLASH))
        assertTrue(DeepSeekModels.isValid(DeepSeekModels.V4_PRO))
        assertFalse(DeepSeekModels.isValid("deepseek-chat"))
        assertFalse(DeepSeekModels.isValid(""))
    }

    @Test
    fun labelFallsBackToId() {
        assertEquals("deepseek-flash", DeepSeekModels.label("deepseek-flash"))
        assertEquals("custom", DeepSeekModels.label("custom"))
    }
}
