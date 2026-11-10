package com.mynote.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrashRetentionStoreTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("trash_retention_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun defaultRetentionIs30Days() {
        assertEquals(30, TrashRetentionStore(context).retentionDays.value)
    }

    @Test
    fun setRetentionPersistsAcrossInstances() {
        val store = TrashRetentionStore(context)
        store.setRetentionDays(7)
        assertEquals(7, TrashRetentionStore(context).retentionDays.value)
    }

    @Test
    fun setRetentionCoercesIntoAllowedOptions() {
        val store = TrashRetentionStore(context)
        store.setRetentionDays(999)
        assertEquals(90, store.retentionDays.value)
        store.setRetentionDays(3)
        assertEquals(7, store.retentionDays.value)
    }

    /** 条目 90：确认框文案跟着保留期走，不再写死 30 天。 */
    @Test
    fun deleteHintFollowsConfiguredDays() {
        assertEquals("删除后将移入回收站，保留 7 天，期间可随时恢复。", TrashRetentionStore.deleteHint(7))
        assertEquals("删除后将移入回收站，保留 90 天，期间可随时恢复。", TrashRetentionStore.deleteHint(90))
        assertEquals(
            "将把所选 3 条笔记移入回收站，保留 30 天，期间可恢复。",
            TrashRetentionStore.batchDeleteHint(3, 30)
        )
    }

    /** 条目 90：剩余天数向上取整，不足 1 天不应显示「还有 0 天」。 */
    @Test
    fun remainingDaysRoundsUpAndClampsAtZero() {
        val now = 1_000_000_000_000L
        val day = TrashRetentionStore.DAY_MS
        // 刚删除：剩 30 天
        assertEquals(30L, TrashRetentionStore.remainingDays(now, 30, now))
        // 还剩 1 小时：向上取整为 1 天（不是 0）
        assertEquals(1L, TrashRetentionStore.remainingDays(now - 30 * day + 3600_000, 30, now))
        // 已过期：0
        assertEquals(0L, TrashRetentionStore.remainingDays(now - 31 * day, 30, now))
    }

    @Test
    fun ttlMsConvertsDays() {
        assertEquals(7L * 24 * 3600 * 1000, TrashRetentionStore.ttlMs(7))
        assertEquals(90L * 24 * 3600 * 1000, TrashRetentionStore.ttlMs(90))
    }
}