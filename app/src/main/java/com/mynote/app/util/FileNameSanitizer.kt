package com.mynote.app.util

/**
 * 把任意标题清洗成合法的导出文件名（Android SAF 场景）。
 * 规则：将 \ / : * ? " < > | 及控制字符替换为 "_"；去除首尾空白与 "."；
 * 超过 80 字符截断；清洗后为空则回退 fallback。
 */
object FileNameSanitizer {

    /** 导出文件名最大长度，超过则截断。 */
    private const val MAX_LENGTH = 80

    /** SAF 场景下文件名不允许出现的字符。 */
    private val ILLEGAL_CHARS = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    /**
     * 把任意标题清洗成合法的导出文件名。
     *
     * @param raw 原始标题。
     * @param fallback 清洗结果为空时使用的回退文件名。
     * @return 清洗后的文件名。
     */
    fun sanitize(raw: String, fallback: String = "note"): String {
        // 逐个字符映射：非法字符与控制字符替换为下划线，其余保留。
        val replaced = buildString(raw.length) {
            for (c in raw) {
                append(if (c in ILLEGAL_CHARS || c.code < 32) '_' else c)
            }
        }
        // 去除首尾空白，再去掉开头与结尾连续的 "."。
        var result = replaced.trim().trim('.')
        // 超过最大长度则截断，截断后若结尾是 "." 再去除。
        if (result.length > MAX_LENGTH) {
            result = result.substring(0, MAX_LENGTH).trimEnd('.')
        }
        // 为空串或纯点则回退到 fallback。
        if (result.isEmpty() || result.all { it == '.' }) {
            return fallback
        }
        return result
    }
}
