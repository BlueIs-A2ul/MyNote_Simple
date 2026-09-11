package com.mynote.app.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.BuildConfig
import com.mynote.app.data.settings.DarkMode
import com.mynote.app.data.settings.ThemeSettingsStore
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.components.TextTabRow
import com.mynote.app.ui.theme.ThemePresets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: ThemeSettingsStore, onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(store))
    val settings by vm.settings.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { PaperTopBar(title = "设置", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "外观",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
            TextTabRow(
                tabs = DarkMode.entries.toList(),
                selected = settings.darkMode,
                onSelect = { vm.setDarkMode(it) },
                label = { darkModeLabel(it) }
            )
            Spacer(Modifier.height(16.dp))
            HairlineDivider()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = settings.dynamicColor,
                            onValueChange = { vm.setDynamicColor(it) },
                            role = Role.Switch
                        )
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "动态取色",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            "跟随系统壁纸自动配色",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = settings.dynamicColor, onCheckedChange = null)
                }
                HairlineDivider()
            }

            Text(
                "主题色",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
            )
            val selectedColorIndex =
                settings.themeColorIndex.takeIf { it in ThemePresets.all.indices } ?: 0
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ThemePresets.all.forEachIndexed { index, preset ->
                    val selected = index == selectedColorIndex
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(preset.light.primary, CircleShape)
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.onBackground
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            )
                            .semantics { contentDescription = preset.label }
                            .clip(CircleShape)
                            .selectable(
                                selected = selected,
                                onClick = {
                                    if (settings.dynamicColor) vm.setDynamicColor(false)
                                    vm.setThemeColorIndex(index)
                                },
                                role = Role.RadioButton
                            )
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "MyNote ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            )
        }
    }
}

private fun darkModeLabel(mode: DarkMode): String = when (mode) {
    DarkMode.SYSTEM -> "跟随系统"
    DarkMode.LIGHT -> "浅色"
    DarkMode.DARK -> "深色"
}
