# 主题颜色管理 — 设计文档

- 日期：2026-09-10
- 状态：已确认（待实现）
- 关联：`docs/superpowers/specs/2026-08-13-mynote-design.md`（§3 主题、§12 里程碑 4）

## 1. 背景与问题

当前主题完全自动、无任何用户设置入口：

- 深浅色跟随系统（`Theme.kt:26` `isSystemInDarkTheme()`），无法手动指定。
- 动态取色写死开启（`Theme.kt:27` `dynamicColor = true`），Android 12+ 强制跟随壁纸，用户无法关闭。
- Android 11 及以下固定内置靛蓝/青配色，不可更换。

用户需要主题颜色管理：深浅色手动切换、动态取色开关、预设主题色选择。

## 2. 目标与非目标

**目标**

- 设置页提供：深色模式三态（跟随系统/浅色/深色）、动态取色开关（仅 Android 12+ 显示）、预设主题色板（6-8 档）。
- 修改即时生效（无需保存按钮），重启后保持。
- 零新增第三方依赖，符合「内存小、设计简洁」硬约束。

**非目标（本次不做）**

- 自由取色器 / 自定义任意颜色。
- 单独为界面指定主题色（如仅笔记列表变色）。
- 字体、字号、圆角等其它外观设置。
- 每档色板的完整 Material 色 token 自定义（只定义 primary/primaryContainer/secondary，其余由 `lightColorScheme`/`darkColorScheme` 默认值补齐）。

## 3. 数据模型与存储

### 3.1 `ThemeSettings`（`data/settings/ThemeSettings.kt`）

```kotlin
enum class DarkMode { SYSTEM, LIGHT, DARK }

data class ThemeSettings(
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val themeColorIndex: Int = 0
)
```

### 3.2 `ThemeSettingsStore`（`data/settings/ThemeSettingsStore.kt`）

- 包装 `context.getSharedPreferences("theme_settings", MODE_PRIVATE)`。
- 键：`dark_mode`（String，枚举名）、`dynamic_color`（Boolean）、`theme_color_index`（Int）。
- 暴露：
  - `settings: StateFlow<ThemeSettings>`（构造时同步读取初值，写入后同步更新 StateFlow）
  - `setDarkMode(DarkMode)` / `setDynamicColor(Boolean)` / `setThemeColorIndex(Int)`
- 读取容错：
  - `dark_mode` 非法/缺失 → `DarkMode.SYSTEM`
  - `theme_color_index` 缺失 → `0`；越界值由 `ThemePresets.resolve()` 兜底为第 0 档（数据层不感知色板数量）
- `AppContainer` 新增 `themeSettingsStore: ThemeSettingsStore by lazy { ThemeSettingsStore(context) }`。

## 4. 预设主题色板（`ui/theme/ThemePresets.kt`）

- `data class ThemePreset(val label: String, val light: ColorScheme, val dark: ColorScheme)`。
- `ThemePresets.all: List<ThemePreset>` 共 8 档；每档手工定义浅/深两套 scheme（primary/primaryContainer/secondary 三字段，沿用 `Color.kt` 现有风格），保证深色下对比度可用。
- 第 0 档 = 现有靛蓝+青（保持默认外观不变）；其余档位：玫红、翠绿、橙、紫、青绿、天蓝、棕，共 7 档新增。
- `label` 用于无障碍 contentDescription（如「靛蓝」）。

## 5. 主题应用

### 5.1 `MyNoteTheme` 签名调整（`ui/theme/Theme.kt`）

```kotlin
@Composable
fun MyNoteTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    preset: ThemePreset,
    content: @Composable () -> Unit
)
```

- 解析优先级：`dynamicColor && Build.VERSION.SDK_INT >= S` → `dynamicDark/LightColorScheme(context)`（忽略 preset）；否则 `preset.dark` / `preset.light`。
- 删除现有写死的 `LightColors`/`DarkColors`（逻辑由第 0 档 preset 承接）。

### 5.2 `MainActivity`（`MainActivity.kt`）

```kotlin
setContent {
    val settings by (application as MyNoteApp).container.themeSettingsStore.settings.collectAsState()
    val preset = ThemePresets.resolve(settings.themeColorIndex)
    MyNoteTheme(
        darkTheme = when (settings.darkMode) {
            DarkMode.SYSTEM -> isSystemInDarkTheme()
            DarkMode.LIGHT -> false
            DarkMode.DARK -> true
        },
        dynamicColor = settings.dynamicColor,
        preset = preset
    ) { AppNavHost(...) }
}
```

## 6. 设置页 UI

### 6.1 文件

- `ui/settings/SettingsScreen.kt`
- `ui/settings/SettingsViewModel.kt`：构造注入 `ThemeSettingsStore`，暴露 `settings: StateFlow<ThemeSettings>` 与三个 update 方法（薄封装），factory 模式与现有 ViewModel 一致。

### 6.2 页面内容（Scaffold + TopAppBar「设置」+ 返回箭头）

1. **深色模式**：`SingleChoiceSegmentedButtonRow` 三选一（跟随系统/浅色/深色），点击即写 store。
2. **动态取色**：`Switch` 行（标题 + 说明文字），仅 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` 时显示；关闭后使用所选主题色。
3. **主题色**：横向排列各档色板圆点（颜色取该档 `light.primary`），选中档描边/外环比其他更醒目；每档带 contentDescription = preset.label。

### 6.3 入口与导航

- `NotesScreen` 更多菜单（⋮ DropdownMenu）新增「设置」项，新增 `onOpenSettings: () -> Unit` 参数。
- `AppNavHost` 新增 `composable("settings")`，构造 `SettingsViewModel` 并渲染 `SettingsScreen`，onBack 走 `popBackStack()`。
- 主页 `composable("notes")` 传入 `onOpenSettings = { navController.navigate("settings") }`。

## 7. 边界与错误处理

| 场景 | 行为 |
|---|---|
| SharedPreferences 中枚举名非法 | 回退 `DarkMode.SYSTEM` |
| `theme_color_index` 越界 | 回退 `0` |
| Android < 12 | 设置页不显示动态取色开关；`MyNoteTheme` 忽略 dynamicColor（SDK 判断兜底） |
| 设置页频繁切换 | 每次点击同步写 prefs + 更新 StateFlow，立即重组主题 |
| 首次启动 | 无存储时使用默认值，外观与现状一致 |

## 8. 测试策略

- **新增** `ThemeSettingsStoreTest`（Robolectric + `ApplicationProvider`）：
  - 空存储返回默认值（SYSTEM / true / 0）。
  - 各字段更新后持久化，新建 store 实例可读回。
  - 更新后 StateFlow 同步发射。
  - 非法 `dark_mode` 字符串回退 SYSTEM。
- **新增** `ThemePresetsTest`（纯 JVM）：共 8 档；第 0 档保持现有默认色；`resolve()` 越界回退第 0 档；label 唯一。
- **新增** `SettingsViewModelTest`（Robolectric）：三个 update 方法透传到 store 的 `settings` StateFlow（薄测）。
- 现有 26 个单测保持全绿；`assembleDebug` 与 `assembleRelease` 编译通过；真机手工验证三档深浅色 + 动态取色开关 + 色板切换即时生效、重启保持。

## 9. 涉及文件

| 文件 | 变更 |
|---|---|
| `data/settings/ThemeSettings.kt` | 新增 |
| `data/settings/ThemeSettingsStore.kt` | 新增 |
| `ui/theme/ThemePresets.kt` | 新增 |
| `ui/settings/SettingsScreen.kt` | 新增 |
| `ui/settings/SettingsViewModel.kt` | 新增 |
| `di/AppContainer.kt` | 新增 store |
| `MainActivity.kt` | 收集设置并传入主题 |
| `ui/theme/Theme.kt` | 签名与逻辑调整 |
| `ui/navigation/AppNavHost.kt` | 新增 settings 路由 |
| `ui/notes/NotesScreen.kt` | 更多菜单加「设置」入口 |
| `app/src/test/.../ThemeSettingsStoreTest.kt` | 新增测试 |
