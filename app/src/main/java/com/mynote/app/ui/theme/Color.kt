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
