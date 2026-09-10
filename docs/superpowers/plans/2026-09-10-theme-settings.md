# 主题颜色管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增加主题设置功能：深浅色三态切换、动态取色开关（Android 12+）、8 档预设主题色，设置持久化并即时生效。

**Architecture:** 新增 `ThemeSettingsStore`（SharedPreferences + StateFlow）承载设置，AppContainer 持有；`MyNoteTheme` 改为接收解析后的参数；新增设置页 `SettingsScreen`/`SettingsViewModel`，经 Navigation 接入，入口在笔记列表更多菜单。

**Tech Stack:** Kotlin、Jetpack Compose Material3、Navigation Compose、SharedPreferences、Robolectric（单测）。

**设计文档：** `docs/superpowers/specs/2026-09-10-theme-settings-design.md`

**通用验证命令：**
- 单个测试类：`.\gradlew :app:testDebugUnitTest --tests "<全限定类名>"`
- 全量单测：`.\gradlew :app:testDebugUnitTest`
- debug 构建：`.\gradlew :app:assembleDebug`
- release 构建：`.\gradlew :app:assembleRelease`

**提交约定：** 每个任务末尾提交一次；如用户未授权提交，则跳过 commit 步骤并记录。

---

### Task 1: 主题设置数据层（ThemeSettings + ThemeSettingsStore）

**Files:**
- Create: `app/src/main/java/com/mynote/app/data/settings/ThemeSettings.kt`
- Create: `app/src/main/java/com/mynote/app/data/settings/ThemeSettingsStore.kt`
- Test: `app/src/test/java/com/mynote/app/data/settings/ThemeSettingsStoreTest.kt`

- [x] **Step 1: 写失败测试**

创建 `app/src/test/java/com/mynote/app/data/settings/ThemeSettingsStoreTest.kt`：

```kotlin
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
```

- [x] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.settings.ThemeSettingsStoreTest"`
Expected: 编译失败，`Unresolved reference: ThemeSettingsStore` / `DarkMode`

- [x] **Step 3: 创建数据模型**

创建 `app/src/main/java/com/mynote/app/data/settings/ThemeSettings.kt`：

```kotlin
package com.mynote.app.data.settings

enum class DarkMode { SYSTEM, LIGHT, DARK }

data class ThemeSettings(
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val themeColorIndex: Int = 0
)
```

- [x] **Step 4: 创建存储实现**

创建 `app/src/main/java/com/mynote/app/data/settings/ThemeSettingsStore.kt`：

```kotlin
package com.mynote.app.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemeSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("theme_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<ThemeSettings> = _settings.asStateFlow()

    fun setDarkMode(mode: DarkMode) {
        prefs.edit().putString(KEY_DARK_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(darkMode = mode)
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
        _settings.value = _settings.value.copy(dynamicColor = enabled)
    }

    fun setThemeColorIndex(index: Int) {
        prefs.edit().putInt(KEY_THEME_COLOR_INDEX, index).apply()
        _settings.value = _settings.value.copy(themeColorIndex = index)
    }

    private fun read(): ThemeSettings {
        val darkMode = prefs.getString(KEY_DARK_MODE, null)
            ?.let { runCatching { DarkMode.valueOf(it) }.getOrNull() }
            ?: DarkMode.SYSTEM
        return ThemeSettings(
            darkMode = darkMode,
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
            themeColorIndex = prefs.getInt(KEY_THEME_COLOR_INDEX, 0)
        )
    }

    private companion object {
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_THEME_COLOR_INDEX = "theme_color_index"
    }
}
```

- [x] **Step 5: 运行测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.settings.ThemeSettingsStoreTest"`
Expected: `tests="4" failures="0"`，BUILD SUCCESSFUL

- [x] **Step 6: 提交**

```bash
git add app/src/main/java/com/mynote/app/data/settings app/src/test/java/com/mynote/app/data/settings
git commit -m "feat: 主题设置数据层（ThemeSettings/ThemeSettingsStore + 测试）"
```

---

### Task 2: 预设色板（ThemePresets）与解析

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/theme/ThemePresets.kt`
- Test: `app/src/test/java/com/mynote/app/ui/theme/ThemePresetsTest.kt`

- [x] **Step 1: 写失败测试**

创建 `app/src/test/java/com/mynote/app/ui/theme/ThemePresetsTest.kt`：

```kotlin
package com.mynote.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePresetsTest {

    @Test
    fun hasEightPresets() {
        assertEquals(8, ThemePresets.all.size)
    }

    @Test
    fun firstPresetKeepsCurrentDefaultColors() {
        val first = ThemePresets.all.first()
        assertEquals(IndigoPrimary, first.light.primary)
        assertEquals(IndigoContainer, first.light.primaryContainer)
        assertEquals(TealAccent, first.light.secondary)
    }

    @Test
    fun resolveOutOfRangeFallsBackToFirst() {
        assertEquals(ThemePresets.all.first(), ThemePresets.resolve(99))
        assertEquals(ThemePresets.all.first(), ThemePresets.resolve(-1))
    }

    @Test
    fun resolveInRangeReturnsPreset() {
        assertEquals(ThemePresets.all[3], ThemePresets.resolve(3))
    }

    @Test
    fun labelsAreUnique() {
        assertEquals(ThemePresets.all.size, ThemePresets.all.map { it.label }.distinct().size)
    }
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.theme.ThemePresetsTest"`
Expected: 编译失败，`Unresolved reference: ThemePresets`

- [x] **Step 3: 创建色板实现**

创建 `app/src/main/java/com/mynote/app/ui/theme/ThemePresets.kt`：

```kotlin
package com.mynote.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

data class ThemePreset(
    val label: String,
    val light: ColorScheme,
    val dark: ColorScheme
)

object ThemePresets {

    val all: List<ThemePreset> = listOf(
        ThemePreset(
            label = "靛蓝",
            light = lightColorScheme(
                primary = IndigoPrimary,
                primaryContainer = IndigoContainer,
                secondary = TealAccent
            ),
            dark = darkColorScheme(
                primary = IndigoPrimary,
                primaryContainer = IndigoContainer,
                secondary = TealAccent
            )
        ),
        ThemePreset(
            label = "玫红",
            light = lightColorScheme(
                primary = Color(0xFFC2185B),
                primaryContainer = Color(0xFFF8BBD0),
                secondary = Color(0xFF7B1FA2)
            ),
            dark = darkColorScheme(
                primary = Color(0xFFF48FB1),
                primaryContainer = Color(0xFF880E4F),
                secondary = Color(0xFFCE93D8)
            )
        ),
        ThemePreset(
            label = "翠绿",
            light = lightColorScheme(
                primary = Color(0xFF2E7D32),
                primaryContainer = Color(0xFFC8E6C9),
                secondary = Color(0xFF00695C)
            ),
            dark = darkColorScheme(
                primary = Color(0xFFA5D6A7),
                primaryContainer = Color(0xFF1B5E20),
                secondary = Color(0xFF80CBC4)
            )
        ),
        ThemePreset(
            label = "橙",
            light = lightColorScheme(
                primary = Color(0xFFE65100),
                primaryContainer = Color(0xFFFFE0B2),
                secondary = Color(0xFFBF360C)
            ),
            dark = darkColorScheme(
                primary = Color(0xFFFFB74D),
                primaryContainer = Color(0xFF8F2E00),
                secondary = Color(0xFFFF8A65)
            )
        ),
        ThemePreset(
            label = "紫",
            light = lightColorScheme(
                primary = Color(0xFF6A1B9A),
                primaryContainer = Color(0xFFE1BEE7),
                secondary = Color(0xFF4527A0)
            ),
            dark = darkColorScheme(
                primary = Color(0xFFCE93D8),
                primaryContainer = Color(0xFF4A148C),
                secondary = Color(0xFF9FA8DA)
            )
        ),
        ThemePreset(
            label = "青绿",
            light = lightColorScheme(
                primary = Color(0xFF00695C),
                primaryContainer = Color(0xFFB2DFDB),
                secondary = Color(0xFF00838F)
            ),
            dark = darkColorScheme(
                primary = Color(0xFF80CBC4),
                primaryContainer = Color(0xFF004D40),
                secondary = Color(0xFF80DEEA)
            )
        ),
        ThemePreset(
            label = "天蓝",
            light = lightColorScheme(
                primary = Color(0xFF1565C0),
                primaryContainer = Color(0xFFBBDEFB),
                secondary = Color(0xFF0277BD)
            ),
            dark = darkColorScheme(
                primary = Color(0xFF90CAF9),
                primaryContainer = Color(0xFF0D47A1),
                secondary = Color(0xFF81D4FA)
            )
        ),
        ThemePreset(
            label = "棕",
            light = lightColorScheme(
                primary = Color(0xFF5D4037),
                primaryContainer = Color(0xFFD7CCC8),
                secondary = Color(0xFF8D6E63)
            ),
            dark = darkColorScheme(
                primary = Color(0xFFBCAAA4),
                primaryContainer = Color(0xFF3E2723),
                secondary = Color(0xFFD7CCC8)
            )
        )
    )

    fun resolve(index: Int): ThemePreset = all.getOrElse(index) { all.first() }
}
```

（`IndigoPrimary`、`IndigoContainer`、`TealAccent` 来自同包 `Color.kt`，无需 import。）

- [x] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.theme.ThemePresetsTest"`
Expected: `tests="5" failures="0"`，BUILD SUCCESSFUL

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/com/mynote/app/ui/theme/ThemePresets.kt app/src/test/java/com/mynote/app/ui/theme/ThemePresetsTest.kt
git commit -m "feat: 8 档预设主题色板与 resolve 兜底"
```

---

### Task 3: 主题应用接线（Theme.kt + AppContainer + MainActivity）

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/theme/Theme.kt`（整体替换）
- Modify: `app/src/main/java/com/mynote/app/di/AppContainer.kt`
- Modify: `app/src/main/java/com/mynote/app/MainActivity.kt`

- [x] **Step 1: 重写 Theme.kt**

整体替换 `app/src/main/java/com/mynote/app/ui/theme/Theme.kt` 为：

```kotlin
package com.mynote.app.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun MyNoteTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    preset: ThemePreset,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> preset.dark
        else -> preset.light
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
```

（删除原 `LightColors`/`DarkColors` 与默认参数；`isSystemInDarkTheme` 不再在此使用。）

- [x] **Step 2: AppContainer 增加 store**

修改 `app/src/main/java/com/mynote/app/di/AppContainer.kt`：新增 import 与属性（放在 `imageStore` 之前或之后均可）：

```kotlin
import com.mynote.app.data.settings.ThemeSettingsStore
```

```kotlin
    val themeSettingsStore: ThemeSettingsStore by lazy { ThemeSettingsStore(context) }
```

- [x] **Step 3: MainActivity 接线**

整体替换 `app/src/main/java/com/mynote/app/MainActivity.kt` 为：

```kotlin
package com.mynote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.mynote.app.data.settings.DarkMode
import com.mynote.app.ui.navigation.AppNavHost
import com.mynote.app.ui.theme.MyNoteTheme
import com.mynote.app.ui.theme.ThemePresets

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MyNoteApp).container
        setContent {
            val settings by container.themeSettingsStore.settings.collectAsState()
            MyNoteTheme(
                darkTheme = when (settings.darkMode) {
                    DarkMode.SYSTEM -> isSystemInDarkTheme()
                    DarkMode.LIGHT -> false
                    DarkMode.DARK -> true
                },
                dynamicColor = settings.dynamicColor,
                preset = ThemePresets.resolve(settings.themeColorIndex)
            ) {
                AppNavHost(container)
            }
        }
    }
}
```

- [x] **Step 4: 编译验证**

Run: `.\gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [x] **Step 5: 回归测试**

Run: `.\gradlew :app:testDebugUnitTest`
Expected: 全部通过（26 + Task1 的 4 + Task2 的 5 = 35 个）

- [x] **Step 6: 提交**

```bash
git add app/src/main/java/com/mynote/app/ui/theme/Theme.kt app/src/main/java/com/mynote/app/di/AppContainer.kt app/src/main/java/com/mynote/app/MainActivity.kt
git commit -m "feat: 主题改为可配置应用（Theme.kt/AppContainer/MainActivity）"
```

---

### Task 4: SettingsViewModel + 测试

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/settings/SettingsViewModel.kt`
- Test: `app/src/test/java/com/mynote/app/ui/settings/SettingsViewModelTest.kt`

- [x] **Step 1: 写失败测试**

创建 `app/src/test/java/com/mynote/app/ui/settings/SettingsViewModelTest.kt`：

```kotlin
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
```

- [x] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.settings.SettingsViewModelTest"`
Expected: 编译失败，`Unresolved reference: SettingsViewModel`

- [x] **Step 3: 创建 ViewModel**

创建 `app/src/main/java/com/mynote/app/ui/settings/SettingsViewModel.kt`：

```kotlin
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
```

- [x] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.settings.SettingsViewModelTest"`
Expected: `tests="3" failures="0"`，BUILD SUCCESSFUL

- [x] **Step 5: 提交**

```bash
git add app/src/main/java/com/mynote/app/ui/settings/SettingsViewModel.kt app/src/test/java/com/mynote/app/ui/settings/SettingsViewModelTest.kt
git commit -m "feat: 设置页 ViewModel（薄封装 ThemeSettingsStore + 测试）"
```

---

### Task 5: 设置页 UI（SettingsScreen）

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/settings/SettingsScreen.kt`

- [x] **Step 1: 创建设置页**

创建 `app/src/main/java/com/mynote/app/ui/settings/SettingsScreen.kt`：

```kotlin
package com.mynote.app.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.data.settings.DarkMode
import com.mynote.app.data.settings.ThemeSettingsStore
import com.mynote.app.ui.theme.ThemePresets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: ThemeSettingsStore, onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(store))
    val settings by vm.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("深色模式", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                DarkMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.darkMode == mode,
                        onClick = { vm.setDarkMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = DarkMode.entries.size
                        ),
                        label = { Text(darkModeLabel(mode)) }
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("动态取色", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "跟随系统壁纸自动配色",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.dynamicColor,
                        onCheckedChange = { vm.setDynamicColor(it) }
                    )
                }
            }

            Text("主题色", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ThemePresets.all.forEachIndexed { index, preset ->
                    val selected = settings.themeColorIndex == index
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(preset.light.primary, CircleShape)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            )
                            .clickable { vm.setThemeColorIndex(index) }
                            .semantics { contentDescription = preset.label }
                    )
                }
            }
        }
    }
}

private fun darkModeLabel(mode: DarkMode): String = when (mode) {
    DarkMode.SYSTEM -> "跟随系统"
    DarkMode.LIGHT -> "浅色"
    DarkMode.DARK -> "深色"
}
```

说明：8 个 36dp 圆点按 `SpaceBetween` 排布适配 328dp 内容宽；`preset.light.primary` 直接作为背景色，无需转换。

- [x] **Step 2: 编译验证**

Run: `.\gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [x] **Step 3: 提交**

```bash
git add app/src/main/java/com/mynote/app/ui/settings/SettingsScreen.kt
git commit -m "feat: 设置页 UI（深浅色/动态取色/主题色）"
```

---

### Task 6: 入口与导航接线 + 最终验证

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt`

- [x] **Step 1: NotesScreen 增加设置入口**

在 `NotesScreen` 函数签名（`NotesScreen.kt:56-62`）新增参数：

```kotlin
    onManageCategories: () -> Unit,
    onOpenSettings: () -> Unit
```

在 `DropdownMenu` 中（导入/导出菜单项之后）新增：

```kotlin
                        DropdownMenuItem(
                            text = { Text("设置") },
                            onClick = { menuOpen = false; onOpenSettings() }
                        )
```

- [x] **Step 2: AppNavHost 接线**

在 `composable("notes")` 调用中新增：

```kotlin
                onOpenSettings = { navController.navigate("settings") }
```

在 `composable("categories")` 之后新增路由：

```kotlin
        composable("settings") {
            SettingsScreen(
                store = container.themeSettingsStore,
                onBack = { navController.popBackStack() }
            )
        }
```

并新增 import：

```kotlin
import com.mynote.app.ui.settings.SettingsScreen
```

- [x] **Step 3: 全量测试**

Run: `.\gradlew :app:testDebugUnitTest`
Expected: 38 个测试全部通过（26 存量 + 4 + 5 + 3 新增），`failures=0`

- [x] **Step 4: debug 构建**

Run: `.\gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，APK 更新（`app\build\outputs\apk\debug\app-debug.apk`）

- [x] **Step 5: release 构建**

Run: `.\gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL，`app\build\outputs\apk\release\app-release.apk`（约 1.5-1.6MB）

- [x] **Step 6: 提交**

```bash
git add app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt
git commit -m "feat: 设置入口（更多菜单）与 settings 路由接线"
```

- [ ] **Step 7: 手工验证清单（真机/模拟器）**

- 主页 ⋮ → 设置，进入设置页；返回键回到主页。
- 「深色模式」切浅色/深色立即生效；切「跟随系统」后随系统变化。
- Android 12+ 显示动态取色开关：关闭后用所选主题色；打开后跟随壁纸。
- 点击任一主题色圆点，界面主色立即变化，选中项描边加粗。
- 杀进程重启，三项设置保持。
- Android 12 以下（如有设备/模拟器）：设置页无动态取色开关；主题色生效。

---

## 自审记录

- **Spec 覆盖**：§3 数据与存储 → Task 1；§4 色板 → Task 2；§5 主题应用 → Task 3；§6.1 ViewModel → Task 4；§6.2 UI → Task 5；§6.3 入口与导航 → Task 6；§7 边界（枚举回退 Task 1、越界 resolve Task 2、SDK<12 隐藏 Task 5/主题忽略 Task 3）；§8 测试 → Task 1/2/4 + Task 6 Step 3；§9 文件清单全覆盖。
- **占位符**：无 TBD/TODO；所有步骤含完整代码与命令。
- **类型一致性**：`ThemeSettings`/`DarkMode`（Task 1）→ `SettingsViewModel`（Task 4）；`ThemePreset`/`ThemePresets.resolve`（Task 2）→ `Theme.kt`/`MainActivity`（Task 3）；`SettingsViewModel.factory`（Task 4）→ `SettingsScreen`（Task 5）；`container.themeSettingsStore`（Task 3）→ `AppNavHost`（Task 6）。键名 `theme_settings`/`dark_mode`/`dynamic_color`/`theme_color_index` 在 Task 1 定义、Task 1/4 测试一致引用。
