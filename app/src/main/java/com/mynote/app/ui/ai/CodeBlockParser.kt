package com.mynote.app.ui.ai

/**
 * 把 AI 回答拆成普通文本与围栏代码块片段，供聊天界面按段渲染（代码等宽 + 横向滚动）。
 * 纯 Kotlin 实现，无 Android 依赖，可直接 JVM 单测。
 */
object CodeBlockParser {

    sealed interface Segment {
        /** 代码块之外的普通文本，相邻文本已合并。 */
        data class Text(val text: String) : Segment

        /** 三个反引号围栏包起来的代码块；language 取围栏首行语言标记，缺省为 null。 */
        data class Code(val language: String?, val code: String) : Segment
    }

    private const val FENCE = "```"

    /**
     * 解析规则：
     * - 围栏行 = 行首（允许空格/Tab）以 ``` 开头的行，其后内容 trim 后作为 language，空串记 null；
     * - 未闭合围栏视为延续到文末；
     * - 空代码块（围栏之间无任何字符）忽略，其两侧文本按连续文本合并；
     * - CRLF / CR 先统一为 LF 再按行处理。
     */
    fun parse(text: String): List<Segment> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val segments = mutableListOf<Segment>()
        val pending = mutableListOf<String>()

        fun flushText() {
            if (pending.isEmpty()) return
            val joined = pending.joinToString("\n")
            pending.clear()
            if (joined.isNotEmpty()) segments += Segment.Text(joined)
        }

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()
            if (!trimmed.startsWith(FENCE)) {
                pending += line
                index++
                continue
            }
            val language = trimmed.substring(FENCE.length).trim().ifEmpty { null }
            var codeEnd = index + 1
            while (codeEnd < lines.size && !lines[codeEnd].trimStart().startsWith(FENCE)) {
                codeEnd++
            }
            val code = lines.subList(index + 1, codeEnd).joinToString("\n")
            if (code.isNotEmpty()) {
                flushText()
                segments += Segment.Code(language, code)
            }
            index = if (codeEnd < lines.size) codeEnd + 1 else codeEnd
        }
        flushText()
        return segments
    }
}
