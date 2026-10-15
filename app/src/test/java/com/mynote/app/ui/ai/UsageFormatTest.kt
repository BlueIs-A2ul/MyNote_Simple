package com.mynote.app.ui.ai

import com.mynote.app.data.ai.AiUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageFormatTest {

    @Test
    fun usageLineWithoutCacheHitOmitsCacheSegment() {
        assertEquals(
            "上次用量：输入 12 / 输出 34 / 合计 46 tokens",
            formatUsageLine(AiUsage(12, 34, 46))
        )
    }

    @Test
    fun usageLineWithCacheHitShowsCacheSegment() {
        assertEquals(
            "上次用量：输入 120（缓存命中 96）/ 输出 34 / 合计 154 tokens",
            formatUsageLine(AiUsage(120, 34, 154, 96))
        )
    }

    @Test
    fun sessionUsageLineWithoutCacheHitOmitsCacheSegment() {
        assertEquals(
            "本会话累计：输入 512 / 输出 96 / 合计 608 tokens",
            formatSessionUsageLine(AiUsage(512, 96, 608))
        )
    }

    @Test
    fun sessionUsageLineWithCacheHitShowsCacheSegment() {
        assertEquals(
            "本会话累计：输入 512（缓存命中 300）/ 输出 96 / 合计 608 tokens",
            formatSessionUsageLine(AiUsage(512, 96, 608, 300))
        )
    }
}
