package com.mynote.app.data.ai

class AiDriverRegistry(private val drivers: List<AiWebDriver>) {

    init {
        require(drivers.isNotEmpty()) { "至少注册一个 AI 服务驱动" }
        require(drivers.map { it.id }.distinct().size == drivers.size) { "AI 服务驱动 id 不能重复" }
    }

    val all: List<AiWebDriver> get() = drivers.toList()

    val default: AiWebDriver get() = drivers.first()

    fun find(id: String?): AiWebDriver? = drivers.firstOrNull { it.id == id }
}
