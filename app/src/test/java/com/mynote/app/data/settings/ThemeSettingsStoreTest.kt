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
class ThemeSettingsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("theme_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun defaultsWhenEmpty() {
        val settings = ThemeSettingsStore(context).settings.value
        assertEquals(DarkMode.SYSTEM, settings.darkMode)
        assertTrue(settings.dynamicColor)
        assertEquals(0, settings.themeColorIndex)
    }

    @Test
    fun updatesPersistAcrossInstances() {
        val store = ThemeSettingsStore(context)
        store.setDarkMode(DarkMode.DARK)
        store.setDynamicColor(false)
        store.setThemeColorIndex(3)

        val reloaded = ThemeSettingsStore(context).settings.value
        assertEquals(DarkMode.DARK, reloaded.darkMode)
        assertFalse(reloaded.dynamicColor)
        assertEquals(3, reloaded.themeColorIndex)
    }

    @Test
    fun updatesEmitToStateFlow() {
        val store = ThemeSettingsStore(context)
        store.setDarkMode(DarkMode.LIGHT)
        store.setDynamicColor(false)
        store.setThemeColorIndex(5)

        assertEquals(DarkMode.LIGHT, store.settings.value.darkMode)
        assertFalse(store.settings.value.dynamicColor)
        assertEquals(5, store.settings.value.themeColorIndex)
    }

    @Test
    fun invalidDarkModeFallsBackToSystem() {
        context.getSharedPreferences("theme_settings", Context.MODE_PRIVATE)
            .edit().putString("dark_mode", "NOPE").commit()
        assertEquals(DarkMode.SYSTEM, ThemeSettingsStore(context).settings.value.darkMode)
    }
}
