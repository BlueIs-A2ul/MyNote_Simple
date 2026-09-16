package com.mynote.app.data.ai

/** DeepSeek 现行模型 id（2026-09 文档；旧名 deepseek-chat / deepseek-reasoner 已下线）。 */
object DeepSeekModels {

    const val FLASH = "deepseek-flash"
    const val V4_PRO = "deepseek-v4-pro"

    const val DEFAULT = FLASH

    val all = listOf(FLASH, V4_PRO)
}
