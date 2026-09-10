package com.mynote.app.data.export

import android.graphics.Bitmap

/** 导出模式：自动分页 / 单张长图。 */
enum class PageMode { PAGED, SINGLE }

/** 一页渲染结果；index 从 0 开始。 */
data class RenderedPage(val bitmap: Bitmap, val index: Int)
