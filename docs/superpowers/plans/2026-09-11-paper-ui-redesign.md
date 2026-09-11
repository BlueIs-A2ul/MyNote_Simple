# 纸感极简 UI 改版 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/superpowers/specs/2026-09-11-paper-ui-redesign-design.md` 把全 App 6 屏与对话框改造成「纸感极简」风格。

**Architecture:** 先在 `ui/theme/` 建立设计系统（纸面中性色 + 衬线排版 + 小圆角 + 主题色只做强调），再抽出 `ui/components/` 复用组件，最后逐屏替换 Material 默认外观。纯逻辑（颜色映射、相对日期）走 TDD；UI 以编译 + 现有单测 + 手工核对验证。零新增依赖、不动数据层。

**Tech Stack:** Kotlin + Jetpack Compose (Material 3) + Robolectric/JUnit；`.\gradlew`（Windows PowerShell）。

**隔离要求:** 全部改动在独立 git worktree 的功能分支 `feature/paper-ui-redesign` 上进行，逐步提交；`master` 保持不变。

---

## Task 1: 隔离工作区与文档入库

**Files:**
- Create: `.worktrees/paper-ui-redesign`（worktree，已创建）
- Move: `docs/superpowers/specs/2026-09-11-paper-ui-redesign-design.md`
- Move: `docs/superpowers/plans/2026-09-11-paper-ui-redesign.md`

- [ ] **Step 1: 创建 worktree 与功能分支**

先按 `superpowers:using-git-worktrees` 技能确认本机可用方式，然后执行：

```powershell
git worktree add ".worktrees/paper-ui-redesign" -b feature/paper-ui-redesign master
```

Expected: `Preparing worktree (new branch 'feature/paper-ui-redesign')`。

- [ ] **Step 2: 把未入库的设计与计划文档搬进 worktree**

```powershell
Copy-Item "docs\superpowers\specs\2026-09-11-paper-ui-redesign-design.md" ".worktrees\paper-ui-redesign\docs\superpowers\specs\"
Copy-Item "docs\superpowers\plans\2026-09-11-paper-ui-redesign.md" ".worktrees\paper-ui-redesign\docs\superpowers\plans\"
Remove-Item "docs\superpowers\specs\2026-09-11-paper-ui-redesign-design.md"
Remove-Item "docs\superpowers\plans\2026-09-11-paper-ui-redesign.md"
```

另需把 gitignore 的构建凭据复制进 worktree（否则 release 配置阶段失败；debug/单测按 AGENTS.md 不受影响，但保持一致）：

```powershell
Copy-Item "keystore.properties" ".worktrees\paper-ui-redesign\keystore.properties"
Copy-Item "app\mynote-release.keystore" ".worktrees\paper-ui-redesign\app\mynote-release.keystore"
```

（以下所有命令都在 worktree 目录下执行。）

- [ ] **Step 3: 提交文档**

```powershell
git add docs/superpowers/specs/2026-09-11-paper-ui-redesign-design.md docs/superpowers/plans/2026-09-11-paper-ui-redesign.md
git commit -m "docs: 添加纸感极简 UI 改版设计与实施计划"
```

- [ ] **Step 4: 基线验证（确认 worktree 可构建）**

Run: `.\gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，113 个测试全绿。

---

## Task 2: 纸感调色板与旧色映射（TDD）

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/theme/Color.kt`
- Test: `app/src/test/java/com/mynote/app/ui/theme/PaperPaletteTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `PaperPaletteTest.kt`：

```kotlin
package com.mynote.app.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperPaletteTest {

    @Test
    fun paletteHasSixColors() {
        assertEquals(6, PaperCategoryColors.size)
    }

    @Test
    fun paperColorsMapToThemselves() {
        PaperCategoryColors.forEach { color ->
            assertEquals(color, PaperPalette.nearest(color.toArgb()))
        }
    }

    @Test
    fun saturatedRedMapsToOchre() {
        assertEquals(PaperCategoryColors[0], PaperPalette.nearest(0xFFEF5350.toInt()))
    }

    @Test
    fun saturatedGreenMapsToMoss() {
        assertEquals(PaperCategoryColors[1], PaperPalette.nearest(0xFF66BB6A.toInt()))
    }

    @Test
    fun saturatedBlueMapsToIndigoGray() {
        assertEquals(PaperCategoryColors[2], PaperPalette.nearest(0xFF42A5F5.toInt()))
    }

    @Test
    fun lowSaturationFallsBackToFirst() {
        assertEquals(PaperCategoryColors[0], PaperPalette.nearest(0xFF9E9E9E.toInt()))
    }

    @Test
    fun nearestResultAlwaysInPalette() {
        val samples = listOf(0xFFEF9A9A, 0xFFFFCC80, 0xFFFFF59D, 0xFFA5D6A7, 0xFF80DEEA, 0xFFB39DDB)
        samples.forEach { argb ->
            assertTrue(PaperPalette.nearest(argb.toInt()) in PaperCategoryColors)
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.theme.PaperPaletteTest"`
Expected: 编译失败或测试失败（`PaperCategoryColors`/`PaperPalette` 未定义）。

- [ ] **Step 3: 实现 Color.kt**

整体替换 `Color.kt` 为：

```kotlin
package com.mynote.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// 纸面中性色（浅色）
val PaperLightBackground = Color(0xFFFAF8F4)
val PaperLightSurface = Color(0xFFFFFDF9)
val PaperLightSurfaceVariant = Color(0xFFF1EDE6)
val PaperLightSurfaceContainerLow = Color(0xFFF6F2EB)
val PaperLightSurfaceContainer = Color(0xFFF1EDE6)
val PaperLightSurfaceContainerHigh = Color(0xFFEDE8E0)
val PaperLightOnSurface = Color(0xFF2B2A27)
val PaperLightOnSurfaceVariant = Color(0xFF8A857D)
val PaperLightOutline = Color(0xFFD8D2C8)
val PaperLightOutlineVariant = Color(0xFFE8E4DC)

// 纸面中性色（深色）
val PaperDarkBackground = Color(0xFF1C1B19)
val PaperDarkSurface = Color(0xFF242320)
val PaperDarkSurfaceVariant = Color(0xFF2C2A27)
val PaperDarkSurfaceContainerLow = Color(0xFF211F1D)
val PaperDarkSurfaceContainer = Color(0xFF2C2A27)
val PaperDarkSurfaceContainerHigh = Color(0xFF35322E)
val PaperDarkOnSurface = Color(0xFFEDEAE4)
val PaperDarkOnSurfaceVariant = Color(0xFFA39D93)
val PaperDarkOutline = Color(0xFF45423D)
val PaperDarkOutlineVariant = Color(0xFF35332F)

val IndigoPrimary = Color(0xFF3F51B5)
val IndigoContainer = Color(0xFFDDE1FF)
val TealAccent = Color(0xFF00897B)

/** 纸感分类色板（低饱和）：赭石 / 苔绿 / 黛蓝 / 藤黄 / 绛紫 / 青灰。 */
val PaperCategoryColors = listOf(
    Color(0xFFC98A6B),
    Color(0xFF7FA588),
    Color(0xFF8E9BC4),
    Color(0xFFB8A06E),
    Color(0xFFA58AA8),
    Color(0xFF7FA8A5)
)

/** 兼容旧调用点：新建分类的随机取色改用纸感色板。 */
val NoteColors = PaperCategoryColors

/** 把库里已存的高饱和分类色按色相最近邻映射到纸感色板（不改数据库）。 */
object PaperPalette {

    private const val LOW_SATURATION = 0.1f

    fun nearest(argb: Int): Color {
        val color = Color(argb)
        val (hue, saturation) = hueAndSaturation(color)
        if (saturation < LOW_SATURATION) return PaperCategoryColors.first()
        var best = PaperCategoryColors.first()
        var bestDistance = Float.MAX_VALUE
        PaperCategoryColors.forEach { candidate ->
            val distance = hueDistance(hue, hueAndSaturation(candidate).first)
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return best
    }

    private fun hueAndSaturation(color: Color): Pair<Float, Float> {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = max(r, max(g, b))
        val min = min(r, min(g, b))
        val delta = max - min
        val saturation = if (max <= 0f) 0f else delta / max
        if (delta == 0f) return 0f to saturation
        val hue = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return (hue + 360f) % 360f to saturation
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val diff = abs(a - b) % 360f
        return if (diff > 180f) 360f - diff else diff
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.theme.PaperPaletteTest"`
Expected: PASS（7 个测试）。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/theme/Color.kt app/src/test/java/com/mynote/app/ui/theme/PaperPaletteTest.kt
git commit -m "feat: 纸感调色板与旧分类色映射"
```

---

## Task 3: 排版、形状与主题应用

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/theme/Type.kt`
- Create: `app/src/main/java/com/mynote/app/ui/theme/Shape.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/theme/Theme.kt`

- [ ] **Step 1: 替换 Type.kt**

```kotlin
package com.mynote.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = Serif, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = Serif, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Serif, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 22.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 16.sp
    )
)
```

- [ ] **Step 2: 新建 Shape.kt**

```kotlin
package com.mynote.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 纸感小圆角：全 App 圆角上限 8dp。 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)
```

- [ ] **Step 3: 修改 Theme.kt 覆盖纸面中性色**

```kotlin
package com.mynote.app.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
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
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> preset.dark
        else -> preset.light
    }
    val colorScheme = if (darkTheme) base.paperDark() else base.paperLight()
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

private fun ColorScheme.paperLight(): ColorScheme = copy(
    background = PaperLightBackground,
    onBackground = PaperLightOnSurface,
    surface = PaperLightSurface,
    onSurface = PaperLightOnSurface,
    surfaceVariant = PaperLightSurfaceVariant,
    onSurfaceVariant = PaperLightOnSurfaceVariant,
    surfaceContainerLowest = PaperLightSurface,
    surfaceContainerLow = PaperLightSurfaceContainerLow,
    surfaceContainer = PaperLightSurfaceContainer,
    surfaceContainerHigh = PaperLightSurfaceContainerHigh,
    surfaceContainerHighest = PaperLightSurfaceContainerHigh,
    outline = PaperLightOutline,
    outlineVariant = PaperLightOutlineVariant
)

private fun ColorScheme.paperDark(): ColorScheme = copy(
    background = PaperDarkBackground,
    onBackground = PaperDarkOnSurface,
    surface = PaperDarkSurface,
    onSurface = PaperDarkOnSurface,
    surfaceVariant = PaperDarkSurfaceVariant,
    onSurfaceVariant = PaperDarkOnSurfaceVariant,
    surfaceContainerLowest = PaperDarkSurface,
    surfaceContainerLow = PaperDarkSurfaceContainerLow,
    surfaceContainer = PaperDarkSurfaceContainer,
    surfaceContainerHigh = PaperDarkSurfaceContainerHigh,
    surfaceContainerHighest = PaperDarkSurfaceContainerHigh,
    outline = PaperDarkOutline,
    outlineVariant = PaperDarkOutlineVariant
)
```

- [ ] **Step 4: 编译与全量测试**

Run: `.\gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部测试绿（含 `ThemePresetsTest`，预设色未动）。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/theme/Type.kt app/src/main/java/com/mynote/app/ui/theme/Shape.kt app/src/main/java/com/mynote/app/ui/theme/Theme.kt
git commit -m "feat: 纸感排版、小圆角与主题覆盖"
```

---

## Task 4: 相对日期格式化（TDD）

**Files:**
- Modify: `app/src/main/java/com/mynote/app/util/TimeFormat.kt`
- Test: `app/src/test/java/com/mynote/app/util/TimeFormatTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.mynote.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class TimeFormatTest {

    private fun millis(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    @Test
    fun todayShowsToday() {
        val now = millis(2026, 9, 11, 15)
        assertEquals("今天", TimeFormat.relativeDate(millis(2026, 9, 11, 8), now))
    }

    @Test
    fun yesterdayShowsYesterday() {
        val now = millis(2026, 9, 11, 1)
        assertEquals("昨天", TimeFormat.relativeDate(millis(2026, 9, 10, 23), now))
    }

    @Test
    fun sameYearShowsMonthDay() {
        val now = millis(2026, 9, 11)
        assertEquals("9月9日", TimeFormat.relativeDate(millis(2026, 9, 9), now))
        assertEquals("1月1日", TimeFormat.relativeDate(millis(2026, 1, 1), now))
    }

    @Test
    fun otherYearShowsFullDate() {
        val now = millis(2026, 9, 11)
        assertEquals("2025年12月31日", TimeFormat.relativeDate(millis(2025, 12, 31), now))
    }

    @Test
    fun midnightBoundaryIsToday() {
        val now = millis(2026, 9, 11, 0)
        assertEquals("今天", TimeFormat.relativeDate(millis(2026, 9, 11, 0), now))
        assertEquals("昨天", TimeFormat.relativeDate(millis(2026, 9, 10, 23), now))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.util.TimeFormatTest"`
Expected: 编译失败（`relativeDate` 未定义）。

- [ ] **Step 3: 实现 relativeDate**

在 `TimeFormat.kt` 的 `object TimeFormat` 内追加（保留现有 `dateTime`/`date`）：

```kotlin
    /** 列表用相对日期：今天 / 昨天 / M月d日 / yyyy年M月d日。 */
    fun relativeDate(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            isSameDay(target, today) -> "今天"
            isSameDay(target, yesterday) -> "昨天"
            target.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
                "${target.get(Calendar.MONTH) + 1}月${target.get(Calendar.DAY_OF_MONTH)}日"
            else ->
                "${target.get(Calendar.YEAR)}年${target.get(Calendar.MONTH) + 1}月${target.get(Calendar.DAY_OF_MONTH)}日"
        }
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.ERA) == b.get(Calendar.ERA) &&
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
```

文件顶部补 `import java.util.Calendar`。

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.util.TimeFormatTest"`
Expected: PASS（5 个测试）。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/mynote/app/util/TimeFormat.kt app/src/test/java/com/mynote/app/util/TimeFormatTest.kt
git commit -m "feat: 新增相对日期格式化"
```

---

## Task 5: 纸感复用组件

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/components/PaperTopBar.kt`
- Create: `app/src/main/java/com/mynote/app/ui/components/TextTabRow.kt`
- Create: `app/src/main/java/com/mynote/app/ui/components/NoteRow.kt`
- Create: `app/src/main/java/com/mynote/app/ui/components/EmptyState.kt`
- Create: `app/src/main/java/com/mynote/app/ui/components/PaperAlertDialog.kt`
- Create: `app/src/main/java/com/mynote/app/ui/components/CategoryDot.kt`

- [ ] **Step 1: 创建 PaperTopBar.kt**

```kotlin
package com.mynote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 纸感顶栏：56dp 高，20sp 衬线标题，无阴影。 */
@Composable
fun PaperTopBar(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
        }
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (onBack != null) 4.dp else 8.dp)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
```

- [ ] **Step 2: 创建 TextTabRow.kt**

```kotlin
package com.mynote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 纸感文字 tab：选中 = 主文字色 + 2dp 强调色下划线。 */
@Composable
fun <T> TextTabRow(
    tabs: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        items(tabs) { tab ->
            val isSelected = tab == selected
            Column(
                modifier = Modifier
                    .clickable { onSelect(tab) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label(tab),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(2.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                        )
                )
            }
        }
    }
}
```

- [ ] **Step 3: 创建 NoteRow.kt**

```kotlin
package com.mynote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.ui.notes.NoteContentParser
import com.mynote.app.util.TimeFormat

/** 纸感列表行：行首 3dp 分类色条 + 衬线标题 + 摘要 + 相对日期。 */
@Composable
fun NoteRow(
    note: NoteEntity,
    categoryColor: Color?,
    onClick: () -> Unit,
    now: Long = System.currentTimeMillis(),
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.5.dp))
                .background(categoryColor ?: Color.Transparent)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (note.pinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "置顶",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = note.title.ifBlank { "无标题" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (note.content.isNotBlank()) {
                Text(
                    text = NoteContentParser.plainText(note.content),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = TimeFormat.relativeDate(note.updatedAt, now),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

- [ ] **Step 4: 创建 EmptyState.kt、PaperAlertDialog.kt、CategoryDot.kt**

```kotlin
// EmptyState.kt
package com.mynote.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun EmptyState(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
```

```kotlin
// PaperAlertDialog.kt
package com.mynote.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun PaperAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    text: @Composable () -> Unit = {},
    dismissButton: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton
    )
}
```

```kotlin
// CategoryDot.kt
package com.mynote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun CategoryDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).clip(CircleShape).background(color))
}
```

- [ ] **Step 5: 编译**

Run: `.\gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/components
git commit -m "feat: 新增纸感复用组件"
```

---

## Task 6: 笔记列表页改版

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt`（整体替换）

- [ ] **Step 1: 整体替换 NotesScreen.kt**

```kotlin
package com.mynote.app.ui.notes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.mynote.app.data.backup.BackupManager
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.ui.components.EmptyState
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.NoteRow
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.components.TextTabRow
import com.mynote.app.ui.theme.PaperPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    viewModel: NotesViewModel,
    backupManager: BackupManager,
    onOpenNote: (Long) -> Unit,
    onNewNote: () -> Unit,
    onManageCategories: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val notes by viewModel.notes.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val query by viewModel.query.collectAsState()
    val selectedCategoryId by viewModel.selectedCategoryId.collectAsState()

    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { scope.launch { backupManager.exportZip(it) } }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { scope.launch { backupManager.importZip(it) } }
    }

    LaunchedEffect(searchActive) {
        if (searchActive) focusRequester.requestFocus()
    }

    val tabs = remember(categories) { listOf<CategoryEntity?>(null) + categories }
    val selectedTab = remember(categories, selectedCategoryId) {
        categories.firstOrNull { it.id == selectedCategoryId }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PaperTopBar(
                title = "备忘录",
                actions = {
                    IconButton(onClick = { searchActive = true }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("分类管理") },
                            onClick = { menuOpen = false; onManageCategories() }
                        )
                        DropdownMenuItem(
                            text = { Text("导出备份") },
                            onClick = { menuOpen = false; exportLauncher.launch("mynote-backup.zip") }
                        )
                        DropdownMenuItem(
                            text = { Text("导入备份") },
                            onClick = { menuOpen = false; importLauncher.launch(arrayOf("application/zip")) }
                        )
                        DropdownMenuItem(
                            text = { Text("设置") },
                            onClick = { menuOpen = false; onOpenSettings() }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            SmallFloatingActionButton(
                onClick = onNewNote,
                shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建笔记", modifier = Modifier.size(20.dp))
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searchActive) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            if (query.isEmpty()) {
                                Text(
                                    "搜索笔记…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    )
                    IconButton(onClick = {
                        viewModel.onQueryChange("")
                        searchActive = false
                        keyboard?.hide()
                    }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭搜索",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                HairlineDivider()
            } else {
                TextTabRow(
                    tabs = tabs,
                    selected = selectedTab,
                    onSelect = { viewModel.onCategorySelect(it?.id) },
                    label = { it?.name ?: "全部" },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }

            if (notes.isEmpty()) {
                val searching = query.isNotBlank()
                EmptyState(
                    icon = Icons.Outlined.Description,
                    text = if (searching) "没有匹配的笔记" else "还没有笔记",
                    actionLabel = if (searching) null else "写第一条",
                    onAction = if (searching) null else onNewNote
                )
            } else {
                val now = System.currentTimeMillis()
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(notes, key = { _, note -> note.id }) { index, note ->
                        NoteRow(
                            note = note,
                            categoryColor = categories.firstOrNull { it.id == note.categoryId }
                                ?.let { PaperPalette.nearest(it.color) },
                            onClick = { onOpenNote(note.id) },
                            now = now,
                            modifier = Modifier.animateItem()
                        )
                        if (index < notes.lastIndex) HairlineDivider()
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: 编译与全量测试**

Run: `.\gradlew :app:testDebugUnitTest` 然后 `.\gradlew :app:assembleDebug`
Expected: 全部 PASS，BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/notes/NotesScreen.kt
git commit -m "feat: 笔记列表页纸感改版"
```

---

## Task 7: 笔记编辑页改版

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt`（整体替换）

- [ ] **Step 1: 替换 ViewModel 之上的导入与文件其余部分**

保留 `NoteEditViewModel` 类实现（第 78-149 行）完全不变；仅替换 import 区与 `NoteEditScreen` 组合函数。import 区改为：

```kotlin
package com.mynote.app.ui.notes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.mynote.app.data.backup.BackupManager
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.db.NoteRevisionDao
import com.mynote.app.data.export.ImageExportManager
import com.mynote.app.data.export.NoteImageRenderer
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.ui.components.CategoryDot
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.PaperAlertDialog
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.export.NoteExportDialog
import com.mynote.app.ui.notes.NoteContentParser.ContentBlock
import com.mynote.app.ui.theme.NoteColors
import com.mynote.app.ui.theme.PaperPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
```

- [ ] **Step 2: 用以下实现替换 `NoteEditScreen` 组合函数（从 `@OptIn(...)` 到文件末尾）**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(
    noteId: Long?,
    repository: NoteRepository,
    imageStore: ImageStore,
    backupManager: BackupManager,
    imageRenderer: NoteImageRenderer,
    exportManager: ImageExportManager,
    onOpenHistory: () -> Unit,
    onBack: () -> Unit
) {
    val vm: NoteEditViewModel = viewModel(
        key = "note_edit_$noteId",
        factory = NoteEditViewModel.factory(repository, imageStore, noteId)
    )
    val note by vm.note.collectAsState()
    val categories by vm.categories.collectAsState()

    var title by rememberSaveable(noteId) { mutableStateOf("") }
    var content by rememberSaveable(noteId) { mutableStateOf("") }
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var pinned by rememberSaveable(noteId) { mutableStateOf(false) }
    var selectedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImageExport by remember { mutableStateOf(false) }

    LaunchedEffect(note) {
        if (note != null && title.isEmpty() && content.isEmpty()) {
            title = note!!.title
            content = note!!.content
            selectedCategoryId = note!!.categoryId
            pinned = note!!.pinned
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { vm.insertImage(it) { markup -> content += markup } }
    }

    val scope = rememberCoroutineScope()
    val exportTxtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { u ->
            val n = note
            if (n != null) scope.launch { backupManager.exportNoteAsTxt(u, n) }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PaperTopBar(
                onBack = onBack,
                actions = {
                    IconButton(onClick = { pinned = !pinned }) {
                        Icon(
                            imageVector = if (pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (pinned) "取消置顶" else "置顶",
                            tint = if (pinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        vm.save(title, content, selectedCategoryId, pinned, note?.color) { warning ->
                            if (warning != null) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(warning)
                                    onBack()
                                }
                            } else {
                                onBack()
                            }
                        }
                    }) {
                        Text("保存", style = MaterialTheme.typography.labelLarge)
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (noteId != null && noteId != 0L) {
                            DropdownMenuItem(
                                text = { Text("历史记录") },
                                onClick = { menuOpen = false; onOpenHistory() }
                            )
                        }
                        if (noteId != null || title.isNotBlank() || content.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("导出") },
                                onClick = { menuOpen = false; showExportDialog = true }
                            )
                        }
                        if (noteId != null) {
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; showDeleteDialog = true }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                decorationBox = { innerTextField ->
                    if (title.isEmpty()) {
                        Text(
                            "标题",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            )

            if (previewMode) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(NoteContentParser.parse(content)) { block ->
                        when (block) {
                            is ContentBlock.Text -> Text(
                                block.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            is ContentBlock.Image -> AsyncImage(
                                model = imageStore.physicalFile(block.name),
                                contentDescription = "图片",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.large),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }
            } else {
                BasicTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 4.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        if (content.isEmpty()) {
                            Text(
                                "开始记录…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                )
            }

            HairlineDivider()
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text("图片") }
                TextButton(
                    onClick = { showCategorySheet = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(
                        (categories.firstOrNull { it.id == selectedCategoryId }?.name ?: "分类") + " ▾"
                    )
                }
                TextButton(onClick = { previewMode = !previewMode }) {
                    Text(
                        "预览",
                        color = if (previewMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showCategorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showCategorySheet = false },
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                CategorySheetRow(
                    label = "未分类",
                    selected = selectedCategoryId == null,
                    onClick = { selectedCategoryId = null; showCategorySheet = false }
                )
                categories.forEach { cat ->
                    CategorySheetRow(
                        label = cat.name,
                        color = PaperPalette.nearest(cat.color),
                        selected = selectedCategoryId == cat.id,
                        onClick = { selectedCategoryId = cat.id; showCategorySheet = false }
                    )
                }
                HairlineDivider()
                CategorySheetRow(
                    label = "+ 新建分类",
                    selected = false,
                    onClick = { showCategorySheet = false; showAddCategoryDialog = true }
                )
            }
        }
    }

    if (showAddCategoryDialog) {
        var name by remember { mutableStateOf("") }
        PaperAlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = "新建分类",
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.addCategory(name) { id ->
                            selectedCategoryId = id
                            showAddCategoryDialog = false
                        }
                    },
                    enabled = name.isNotBlank()
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategoryDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDeleteDialog) {
        PaperAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = "删除笔记？",
            text = { Text("删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.delete(onBack) }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    if (showExportDialog) {
        val hasContent = title.isNotBlank() || content.isNotBlank()
        PaperAlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = "导出为…",
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showExportDialog = false
                            exportTxtLauncher.launch((note?.title ?: "note") + ".txt")
                        },
                        enabled = note != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("文本文档 (txt)") }
                    if (note == null) {
                        Text("保存后可导出 txt", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        onClick = {
                            showExportDialog = false
                            showImageExport = true
                        },
                        enabled = hasContent,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("图片 (PNG)") }
                    if (!hasContent) {
                        Text("还没有内容", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("取消") }
            }
        )
    }

    if (showImageExport) {
        NoteExportDialog(
            title = title,
            content = content,
            updatedAt = note?.updatedAt ?: System.currentTimeMillis(),
            renderer = imageRenderer,
            exportManager = exportManager,
            onDismiss = { showImageExport = false }
        )
    }
}

@Composable
private fun CategorySheetRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    color: androidx.compose.ui.graphics.Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (color != null) {
            CategoryDot(color)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}
```

注意：`CategorySheetRow` 用到 `clickable`，需要在 import 区补 `androidx.compose.foundation.clickable`。

- [ ] **Step 3: 编译与全量测试**

Run: `.\gradlew :app:testDebugUnitTest` 然后 `.\gradlew :app:assembleDebug`
Expected: 全部 PASS，BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt
git commit -m "feat: 笔记编辑页纸感改版"
```

---

## Task 8: 分类管理页改版

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/categories/CategoriesScreen.kt`（整体替换）

- [ ] **Step 1: 整体替换**

```kotlin
package com.mynote.app.ui.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.ui.components.CategoryDot
import com.mynote.app.ui.components.EmptyState
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.PaperAlertDialog
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.theme.PaperPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(repository: NoteRepository, onBack: () -> Unit) {
    val vm: CategoriesViewModel = viewModel(factory = CategoriesViewModel.factory(repository))
    val categories by vm.categories.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PaperTopBar(
                title = "分类管理",
                onBack = onBack,
                actions = {
                    TextButton(onClick = { showAddDialog = true }) { Text("新建") }
                }
            )
        }
    ) { padding ->
        if (categories.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Label,
                text = "暂无分类",
                actionLabel = "新建分类",
                onAction = { showAddDialog = true },
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                itemsIndexed(categories, key = { _, cat -> cat.id }) { index, cat ->
                    CategoryRow(
                        cat = cat,
                        onRename = { vm.rename(cat, it) },
                        onDelete = { vm.delete(cat) }
                    )
                    if (index < categories.lastIndex) HairlineDivider()
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        PaperAlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = "新建分类",
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.add(name) { showAddDialog = false } }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun CategoryRow(cat: CategoryEntity, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var showDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(cat.name) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (editing) {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    if (name.isEmpty()) {
                        Text(
                            "分类名称",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            )
            TextButton(onClick = { onRename(name); editing = false }) { Text("保存") }
            TextButton(onClick = { name = cat.name; editing = false }) { Text("取消") }
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { editing = true }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryDot(PaperPalette.nearest(cat.color))
                Spacer(Modifier.width(10.dp))
                Text(
                    cat.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(onClick = { showDelete = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showDelete) {
        PaperAlertDialog(
            onDismissRequest = { showDelete = false },
            title = "删除分类？",
            text = { Text("该分类下的笔记将移入未分类。") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDelete = false }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            }
        )
    }
}
```

- [ ] **Step 2: 编译与全量测试**

Run: `.\gradlew :app:testDebugUnitTest` 然后 `.\gradlew :app:assembleDebug`
Expected: 全部 PASS，BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/categories/CategoriesScreen.kt
git commit -m "feat: 分类管理页纸感改版"
```

---

## Task 9: 设置页改版

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/settings/SettingsScreen.kt`（整体替换）

- [ ] **Step 1: 整体替换**

```kotlin
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
        }
    }
}

private fun darkModeLabel(mode: DarkMode): String = when (mode) {
    DarkMode.SYSTEM -> "跟随系统"
    DarkMode.LIGHT -> "浅色"
    DarkMode.DARK -> "深色"
}
```

- [ ] **Step 2: 编译与全量测试**

Run: `.\gradlew :app:testDebugUnitTest` 然后 `.\gradlew :app:assembleDebug`
Expected: 全部 PASS，BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/settings/SettingsScreen.kt
git commit -m "feat: 设置页纸感改版"
```

---

## Task 10: 历史记录页改版

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/history/NoteHistoryScreen.kt`（整体替换）

- [ ] **Step 1: 整体替换**

```kotlin
package com.mynote.app.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.data.db.NoteRevisionDao
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.ui.components.HairlineDivider
import com.mynote.app.ui.components.PaperAlertDialog
import com.mynote.app.ui.components.PaperTopBar
import com.mynote.app.ui.components.TextTabRow
import com.mynote.app.util.TimeFormat

private val RemovedBg = Color(0x33EF5350)
private val AddedBg = Color(0x334CAF50)
private val RemovedEmphasis = Color(0x66EF5350)
private val AddedEmphasis = Color(0x664CAF50)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteHistoryScreen(
    noteId: Long,
    repository: NoteRepository,
    onRestored: () -> Unit,
    onBack: () -> Unit
) {
    val vm: NoteHistoryViewModel = viewModel(
        key = "note_history_$noteId",
        factory = NoteHistoryViewModel.factory(repository, noteId)
    )
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRestoreDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = state.detail != null) { vm.closeDetail() }

    LaunchedEffect(state.restoreSucceeded) {
        if (state.restoreSucceeded) {
            vm.consumeRestoreSuccess()
            onRestored()
        }
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeMessage()
    }
    LaunchedEffect(state.detail) {
        if (state.detail == null) showRestoreDialog = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PaperTopBar(
                title = "历史记录 (${state.count}/${NoteRevisionDao.MAX_PER_NOTE})",
                onBack = { if (state.detail != null) vm.closeDetail() else onBack() }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            val detail = state.detail
            if (detail == null) {
                state.bannerText?.let { banner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.shapes.medium
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            banner,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (state.revisions.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "保存一次后开始记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(
                            state.revisions,
                            key = { _, item -> item.revision.id }
                        ) { index, item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.selectRevision(item.revision.id) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        TimeFormat.dateTime(item.revision.savedAt),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (item.isCurrent) {
                                        Text(
                                            "当前版本",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    item.labels.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            if (index < state.revisions.lastIndex) HairlineDivider()
                        }
                    }
                }
            } else {
                DetailContent(
                    state = state,
                    detail = detail,
                    vm = vm,
                    onRestore = { showRestoreDialog = true }
                )
            }
        }
    }

    if (showRestoreDialog) {
        PaperAlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = "恢复此版本？",
            text = { Text("将用此版本覆盖当前内容，并生成一条新的历史记录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreDialog = false
                        vm.restore()
                    },
                    enabled = !state.restoring
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DetailContent(
    state: NoteHistoryViewModel.UiState,
    detail: NoteHistoryViewModel.DetailState,
    vm: NoteHistoryViewModel,
    onRestore: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                TimeFormat.dateTime(detail.revision.savedAt),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                detail.labels.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!detail.isFirst) {
                Spacer(Modifier.height(8.dp))
                TextTabRow(
                    tabs = listOf(false, true),
                    selected = detail.showFullText,
                    onSelect = { fullText -> if (fullText != detail.showFullText) vm.toggleFullText() },
                    label = { if (it) "全文" else "对比" }
                )
            }
        }
        HairlineDivider()
        if (detail.showFullText || detail.isFirst) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                Text(
                    detail.revision.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        } else if (detail.loadingDiff) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(detail.diffLines) { line -> DiffLineRow(line) }
            }
        }
        HairlineDivider()
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            if (!detail.isCurrent) {
                Button(
                    onClick = onRestore,
                    enabled = !state.restoring,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(if (state.restoring) "恢复中…" else "恢复此版本")
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: NoteDiff.Line) {
    val background = when (line.type) {
        NoteDiff.Type.REMOVED -> RemovedBg
        NoteDiff.Type.ADDED -> AddedBg
        NoteDiff.Type.UNCHANGED -> Color.Transparent
    }
    val emphasisColor = when (line.type) {
        NoteDiff.Type.REMOVED -> RemovedEmphasis
        NoteDiff.Type.ADDED -> AddedEmphasis
        NoteDiff.Type.UNCHANGED -> Color.Transparent
    }
    val text = buildAnnotatedString {
        var cursor = 0
        for (range in line.emphasis) {
            val start = range.first.coerceAtLeast(cursor)
            val end = (range.last + 1).coerceAtMost(line.text.length)
            if (end <= start) continue
            if (start > cursor) append(line.text.substring(cursor, start))
            withStyle(SpanStyle(background = emphasisColor)) {
                append(line.text.substring(start, end))
            }
            cursor = end
        }
        if (cursor < line.text.length) append(line.text.substring(cursor))
        if (line.text.isEmpty()) append(" ")
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodyMedium,
        textDecoration = if (line.type == NoteDiff.Type.REMOVED) TextDecoration.LineThrough else null
    )
}
```

注意：`DetailContent` 的 diff 列表用到 `items`，import 区需补 `androidx.compose.foundation.lazy.items`。

- [ ] **Step 2: 编译与全量测试**

Run: `.\gradlew :app:testDebugUnitTest` 然后 `.\gradlew :app:assembleDebug`
Expected: 全部 PASS，BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/history/NoteHistoryScreen.kt
git commit -m "feat: 历史记录页纸感改版"
```

---

## Task 11: 导出预览页改版

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/export/NoteExportDialog.kt`

- [ ] **Step 1: 替换顶栏、分段按钮与预览图样式**

- import 区：删除 `TopAppBar`、`SegmentedButton`、`SegmentedButtonDefaults`、`SingleChoiceSegmentedButtonRow`；新增 `androidx.compose.ui.draw.clip`、`com.mynote.app.ui.components.PaperTopBar`、`com.mynote.app.ui.components.TextTabRow`。
- 顶栏替换为：

```kotlin
                topBar = {
                    PaperTopBar(
                        title = "导出图片",
                        onBack = { dismiss() }
                    )
                },
```

- `current.pageCount > 1` 分支里的 `SingleChoiceSegmentedButtonRow { ... }` 替换为：

```kotlin
                                if (current.pageCount > 1) {
                                    TextTabRow(
                                        tabs = listOf(PageMode.PAGED, PageMode.SINGLE),
                                        selected = current.mode,
                                        onSelect = { vm.setMode(it) },
                                        label = {
                                            if (it == PageMode.PAGED) "分页 ${current.pageCount} 张" else "单张长图"
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
```

- 预览图 `Image` 的修饰符替换为：

```kotlin
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(MaterialTheme.shapes.large)
                                            .border(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant,
                                                MaterialTheme.shapes.large
                                            )
```

- 底部 `OutlinedButton` / `Button` 各加 `shape = MaterialTheme.shapes.small`。
- 其余（Loading / Error / Ready 状态、保存、分享、snackbar）逻辑不动。

- [ ] **Step 2: 编译与全量测试**

Run: `.\gradlew :app:testDebugUnitTest` 然后 `.\gradlew :app:assembleDebug`
Expected: 全部 PASS，BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```powershell
git add app/src/main/java/com/mynote/app/ui/export/NoteExportDialog.kt
git commit -m "feat: 导出预览页纸感改版"
```

---

## Task 12: 全量验证与收尾

- [ ] **Step 1: 全量单测**

Run: `.\gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，113 个旧测试 + 12 个新增（7 个 PaperPaletteTest + 5 个 TimeFormatTest）= 125 个全绿。

- [ ] **Step 2: debug 构建**

Run: `.\gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，产物在 `app/build/outputs/apk/debug/`。

- [ ] **Step 3: 检查 Material 默认控件残留**

Run: `rg -n "FilterChip|SegmentedButton|Card\(|TopAppBar" app/src/main/java/com/mynote/app/ui`
Expected: 无输出（`OutlinedTextField` 允许仅存在于新建/重命名分类对话框）。

- [ ] **Step 4: 手工核对清单（模拟器或真机）**

| 检查项 | 预期 |
|---|---|
| 浅色 / 深色 / 跟随系统 | 纸面暖白 / 暖黑，无默认紫白 |
| 8 档主题色 + 动态取色 | 只变强调色，纸面不变 |
| 列表空状态 | 图标 + 文案 +「写第一条」可点 |
| 搜索 | 点图标展开、自动聚焦、输入过滤、关闭清空并恢复分类 tab |
| 分类 tab | 横向可滑，下划线选中态，切换过滤正确 |
| 列表行 | 色条为纸感色（旧分类不刺眼）、置顶 pin、相对日期 |
| 编辑页 | 标题/正文无边框；图片/分类/预览工具条；键盘弹出不被遮挡 |
| 分类弹层 | 「未分类」可清除分类；新建分类后自动选中 |
| 历史 | 对比/全文 tab；恢复按钮与确认对话框 |
| 导出预览 | 分页/长图切换、保存/分享、PNG 仍纯白 |
| 备份导出/导入 | 行为不变 |

- [ ] **Step 5: 如有修复则提交**

```powershell
git add -A
git commit -m "fix: 纸感改版收尾修正"
```

- [ ] **Step 6: 汇报**

汇总：改动文件列表、测试结果、构建结果、手工核对结论、分支名与提交数；合并与否交由用户决定（不自行合并 master、不 push）。

---

## 修订记录

| 日期 | 修订 |
|---|---|
| 2026-09-11 | 初稿：按设计文档拆分 12 个任务，含完整代码、命令与验收标准 |

