package com.mynote.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingStoreTest {

    private lateinit var context: Context
    private lateinit var store: OnboardingStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        store = OnboardingStore(context)
    }

    @Test
    fun welcomeSeededDefaultsOff() {
        assertFalse(store.isWelcomeSeeded())
    }

    @Test
    fun markWelcomeSeededPersistsAcrossInstances() {
        store.markWelcomeSeeded()
        assertTrue(store.isWelcomeSeeded())
        assertTrue(OnboardingStore(context).isWelcomeSeeded())
    }
}
