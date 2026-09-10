package com.mynote.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.settings.DarkMode
import com.mynote.app.data.settings.ThemeSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {

    private lateinit var store: ThemeSettingsStore
    private lateinit var vm: SettingsViewModel

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("theme_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = ThemeSettingsStore(context)
        vm = SettingsViewModel(store)
    }

    @Test
    fun setDarkModeDelegatesToStore() {
        vm.setDarkMode(DarkMode.DARK)
        assertEquals(DarkMode.DARK, store.settings.value.darkMode)
        assertEquals(DarkMode.DARK, vm.settings.value.darkMode)
    }

    @Test
    fun setDynamicColorDelegatesToStore() {
        vm.setDynamicColor(false)
        assertFalse(store.settings.value.dynamicColor)
    }

    @Test
    fun setThemeColorIndexDelegatesToStore() {
        vm.setThemeColorIndex(5)
        assertEquals(5, store.settings.value.themeColorIndex)
    }
}
