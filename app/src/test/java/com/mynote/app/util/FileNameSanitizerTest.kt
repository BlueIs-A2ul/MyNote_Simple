package com.mynote.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNameSanitizerTest {

    @Test
    fun slashIsReplacedWithUnderscore() {
        assertEquals("2026_09_13 会议", FileNameSanitizer.sanitize("2026/09/13 会议"))
    }

    @Test
    fun illegalCharactersAreReplacedWithUnderscore() {
        assertEquals("a_b_c_d_e_f_g_h_i", FileNameSanitizer.sanitize("a:b*c?d\"e<f>g|h\\i"))
    }

    @Test
    fun leadingAndTrailingWhitespaceIsTrimmed() {
        assertEquals("会议纪要", FileNameSanitizer.sanitize("  会议纪要  "))
    }

    @Test
    fun leadingAndTrailingDotsAreTrimmed() {
        assertEquals("标题", FileNameSanitizer.sanitize("..标题.."))
    }

    @Test
    fun longTitleIsTruncatedTo80Chars() {
        assertEquals("a".repeat(80), FileNameSanitizer.sanitize("a".repeat(120)))
    }

    @Test
    fun emptyOrBlankFallsBackToDefault() {
        assertEquals("note", FileNameSanitizer.sanitize(""))
        assertEquals("note", FileNameSanitizer.sanitize("   "))
    }

    @Test
    fun onlyIllegalCharsAreReplacedWithoutFallback() {
        assertEquals("___", FileNameSanitizer.sanitize("///"))
    }

    @Test
    fun pureChineseTitleIsUnchanged() {
        assertEquals("我的笔记", FileNameSanitizer.sanitize("我的笔记"))
    }

    @Test
    fun blankWithCustomFallbackReturnsFallback() {
        assertEquals("mynote", FileNameSanitizer.sanitize("   ", fallback = "mynote"))
    }

    @Test
    fun truncationDoesNotLeaveTrailingDot() {
        // 81 个字符：截断到 80 后结尾是 "."，应被去除，最终 79 个字符。
        assertEquals("a".repeat(79), FileNameSanitizer.sanitize("a".repeat(79) + ".."))
    }
}
