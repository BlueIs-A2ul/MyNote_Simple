package com.mynote.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class DeepSeekModelsTest {

    @Test
    fun defaultIsFlash() {
        assertEquals(DeepSeekModels.FLASH, DeepSeekModels.DEFAULT)
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), DeepSeekModels.all)
    }
}
