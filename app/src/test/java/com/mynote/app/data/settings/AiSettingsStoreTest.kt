package com.mynote.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiSettingsStoreTest {

    private lateinit var store: AiSettingsStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = AiSettingsStore(context)
    }

    @Test
    fun privacyAcceptanceIsPerService() {
        assertFalse(store.isPrivacyAccepted("deepseek"))
        store.acceptPrivacy("deepseek")
        assertTrue(store.isPrivacyAccepted("deepseek"))
        assertFalse(store.isPrivacyAccepted("doubao"))
    }

    @Test
    fun selectedServiceRoundTrips() {
        assertEquals(null, store.selectedServiceId())
        store.setSelectedServiceId("deepseek")
        assertEquals("deepseek", store.selectedServiceId())
    }
}
