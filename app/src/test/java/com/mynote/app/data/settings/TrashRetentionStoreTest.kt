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

    @Test
    fun ttlMsConvertsDays() {
        assertEquals(7L * 24 * 3600 * 1000, TrashRetentionStore.ttlMs(7))
        assertEquals(90L * 24 * 3600 * 1000, TrashRetentionStore.ttlMs(90))
    }
}