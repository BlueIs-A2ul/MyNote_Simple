package com.mynote.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.settings.DarkMode
import com.mynote.app.data.settings.ThemeSettings
import com.mynote.app.data.settings.ThemeSettingsStore
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(private val store: ThemeSettingsStore) : ViewModel() {

    val settings: StateFlow<ThemeSettings> = store.settings

    fun setDarkMode(mode: DarkMode) = store.setDarkMode(mode)

    fun setDynamicColor(enabled: Boolean) = store.setDynamicColor(enabled)

    fun setThemeColorIndex(index: Int) = store.setThemeColorIndex(index)

    companion object {
        fun factory(store: ThemeSettingsStore): ViewModelProvider.Factory =
            viewModelFactory { initializer { SettingsViewModel(store) } }
    }
}
