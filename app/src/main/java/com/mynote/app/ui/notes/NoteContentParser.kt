package com.mynote.app.ui.notes

object NoteContentParser {

    sealed interface ContentBlock {
        data class Text(val text: String) : ContentBlock
        data class Image(val name: String) : ContentBlock
    }

    private val IMAGE_REGEX = Regex("""!\[\]\(img/([^)\s]+)\)""")

    fun parse(content: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        var last = 0
        for (m in IMAGE_REGEX.findAll(content)) {
            if (m.range.first > last) {
                blocks += ContentBlock.Text(content.substring(last, m.range.first))
            }
            blocks += ContentBlock.Image(m.groupValues[1])
            last = m.range.last + 1
        }
        if (last < content.length) {
            blocks += ContentBlock.Text(content.substring(last))
        }
        return blocks
    }

    fun extractImageNames(content: String): List<String> =
        IMAGE_REGEX.findAll(content).map { it.groupValues[1] }.toList()

    fun makeImageMarkup(name: String): String = "![](img/$name)"

    /** 剥离图片标记得到纯文本（列表预览等场景用，修订记录第 7 条）。 */
    fun plainText(content: String): String = IMAGE_REGEX.replace(content, "")
}
