package com.mynote.app.data.export

import android.graphics.Bitmap

/** 导出模式：自动分页 / 单张长图。 */
enum class PageMode { PAGED, SINGLE }

/** 一页渲染结果；index 从 0 开始。 */
data class RenderedPage(
    val bitmap: Bitmap,
    val index: Int,
    /** 条目 98：本页绘制时解码失败、被静默跳过的图片名（与预览的缺图统计同一口径）。 */
    val skippedImages: Set<String> = emptySet()
)
