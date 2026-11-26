package com.mynote.app.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 K（条目 118）：备份文件名带本地日期，多次导出不会互相覆盖。
 * 日期本身依赖系统时区，这里只锁「形状」与「跨天不同名」，避免在 CI 上因时区失败。
 */
class BackupFileNameTest {

    private val pattern = Regex("^mynote-backup-\\d{4}-\\d{2}-\\d{2}\\.zip$")

    @Test
    fun backupFileNameCarriesLocalDateAndZipSuffix() {
        val name = backupFileName(1_795_000_000_000L)
        assertTrue("实际=$name", pattern.matches(name))
    }

    @Test
    fun backupFileNameDiffersAcrossDays() {
        val dayOne = backupFileName(1_795_000_000_000L)
        val nextDay = backupFileName(1_795_000_000_000L + 86_400_000L)
        assertNotEquals(dayOne, nextDay)
        assertEquals(dayOne.take(15), nextDay.take(15))
    }
}
