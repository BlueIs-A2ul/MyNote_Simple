package com.mynote.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.db.NoteSortMode
import com.mynote.app.data.settings.DarkMode
import com.mynote.app.data.settings.NoteSortStore
import com.mynote.app.data.settings.ThemeSettings
import com.mynote.app.data.settings.ThemeSettingsStore
import com.mynote.app.data.settings.TrashRetentionStore
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
    private val store: ThemeSettingsStore,
    private val sortStore: NoteSortStore,
    private val trashStore: TrashRetentionStore
) : ViewModel() {

    val settings: StateFlow<ThemeSettings> = store.settings

    /** 通用分区：列表默认排序（读写 NoteSortStore）。 */
    val defaultSort: StateFlow<NoteSortMode> = sortStore.mode

    /** 通用分区：回收站保留期（天）。 */
    val retentionDays: StateFlow<Int> = trashStore.retentionDays

    fun setDarkMode(mode: DarkMode) = store.setDarkMode(mode)

    fun setDynamicColor(enabled: Boolean) = store.setDynamicColor(enabled)

    fun setThemeColorIndex(index: Int) = store.setThemeColorIndex(index)

    fun setDefaultSort(mode: NoteSortMode) = sortStore.setMode(mode)

    fun setRetentionDays(days: Int) = trashStore.setRetentionDays(days)

    companion object {
        fun factory(
            store: ThemeSettingsStore,
            sortStore: NoteSortStore,
            trashStore: TrashRetentionStore
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(store, sortStore, trashStore) }
        }
    }
}