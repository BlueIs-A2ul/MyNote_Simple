# AI 回答 Markdown 渲染 — 设计文档

- 日期：2026-09-15
- 状态：设计已确认（用户确认 A 档语法 + 全部推荐项，并授权自行写文档与实现）
- 关联：`docs/backlog.md` 条目 61（代码块极简渲染，本设计将其升级为完整 Markdown）、`docs/superpowers/specs/2026-09-15-ai-assistant-optimization-design.md`（AI 助手基线）
- 范围：仅 AI 助手回答气泡与流式气泡的 Markdown 渲染；用户气泡、深度思考区、输入框保持纯文本。不改数据库、不改 ViewModel、不新增三方依赖（硬约束）。

## 1. 目标

1. DeepSeek 回答中的 Markdown 语法得到正确排版：标题、列表（含嵌套）、引用、分隔线、粗体/斜体/删除线、行内代码、链接、表格、围栏代码块。
2. 流式输出实时渲染，完成后无样式跳变。
3. 「插入正文 / 替换选中 / 存为新笔记」在纯文本笔记中落地时剥离 Markdown 标记；「复制」保持原始 Markdown。
4. 全部解析逻辑为纯 Kotlin 纯函数，可直接 JVM 单测；渲染层零新增依赖。

## 2. 冻结契约

### 2.1 块级解析（`ui/ai/MarkdownParser.kt`，替换 `CodeBlockParser.kt`）

```kotlin
object MarkdownParser {
    sealed interface MdBlock {
        sealed interface ListBlock : MdBlock { val items: List<MdListItem> }

        data class Heading(val level: Int, val text: String) : MdBlock          // level 1..6
        data class Paragraph(val text: String) : MdBlock
        data class Quote(val blocks: List<MdBlock>) : MdBlock
        data class CodeBlock(val language: String?, val code: String) : MdBlock
        data object Rule : MdBlock
        data class BulletList(override val items: List<MdListItem>) : ListBlock
        data class OrderedList(val start: Int, override val items: List<MdListItem>) : ListBlock
        data class Table(
            val alignments: List<MdAlign>,
            val header: List<String>,
            val rows: List<List<String>>
        ) : MdBlock
    }

    data class MdListItem(val text: String, val checked: Boolean?, val subList: MdBlock.ListBlock?)
    enum class MdAlign { START, CENTER, END }

    fun parse(text: String): List<MdBlock>
}
```

解析规则（先 CRLF / CR 归一化为 LF，再按行扫描）：

| 结构 | 规则 |
|---|---|
| 标题 | 行首（允许缩进）`#`×1–6 + 空白/行尾；内容 trim，尾部连续 `#` 去掉 |
| 分隔线 | trim 后去空格只由同一字符 `-`/`*`/`_` 组成且长度 ≥3（含 `- - -`） |
| 围栏代码块 | 行 trimStart 后以 ``` 开头；语言标记 trim 后空记 null；未闭合延续到文末；空代码块不产出块；空块两侧文本自然合并为一个段落 |
| 引用 | 连续 `>` 行剥一层 `>`（及后随一个空格）后递归块解析；引用内容可为任意块（含嵌套引用、列表、代码块） |
| 列表 | 无序 `- `/`* `/`+ `，有序 `N. `/`N) `（N 为 1–9 位数字）；缩进落在 `[基准, 基准+2)` 视为同级，≥ 基准 +2 归入当前项 `subList`（递归解析，支持多层嵌套）；列表类型切换即结束当前列表开新列表；空行后若下一非空行为同基准同类型列表项则列表继续，否则结束 |
| 任务列表 | 无序项文本以 `[ ] ` / `[x] `/`[X] ` 开头 → `checked = false/true` 并去掉标记；仅无序列表支持 |
| 续行 | 列表项后不含空行、且不构成其它块起始的普通行，以换行拼接到当前项文本（惰性续行） |
| 表格 | 当前行含 `|` 且下一行是仅由 `-:| ` 组成、含 `-` 与 `|` 的分隔行才成立；表体为后续含 `|` 的非空行，遇到空行或不含 `|` 的行结束；单元格按未转义 `|` 切分，`\|` 转义为 `|`，首尾边界空单元格丢弃，trim；列数取表头，对齐按分隔行 `:---`/`:---:`/`---:`，缺列补 `START` 与空串，多列截断 |
| 段落 | 连续普通行累积为一段，保留换行符拼接（不合成空格，避免中文被塞空格）；遇空行或其它块起始结束 |
| 兜底 | 不匹配任何结构的行按普通文本字面显示 |

### 2.2 行内解析（`ui/ai/MarkdownInline.kt`，纯 Kotlin + `AnnotatedString`）

```kotlin
object MarkdownInline {
    /** 行内文本 → AnnotatedString；codeBackground 为行内代码底色，linkColor 为链接文字色（注解内联主题色 + 下划线）。 */
    fun build(text: String, codeBackground: Color, linkColor: Color): AnnotatedString

    /** 行内文本 → 纯文本：剥离标记、链接展开为「标签（url）」、图片取 alt。 */
    fun plainText(text: String): String
}
```

支持语法：

- 粗体 `**x**` / `__x__`；斜体 `*x*` / `_x_`；粗斜体 `***x***`；删除线 `~~x~~`
- 行内代码 `` `x` ``（单反引号；等宽 + 底色；内部不再解析其它语法；首尾各去掉一个空格当且仅当两侧都有空格且内容不全为空格）
- 链接 `[标签](url)` → `LinkAnnotation.Url(url)`，标签内部继续支持行内语法
- 图片 `![alt](url)` → 只显示 alt 文字（空 alt 显示「图片」），不联网加载
- 转义 `\` + ``\` * _ ~ [ ] ( ) # >`` 等符号 → 输出该符号字面
- 其它情况（未闭合定界符、`***x**` 这类病态嵌套、引用式链接、裸 URL、HTML、数学公式 `$` 等）一律按字面输出

定界符规则（务实子集，按 CommonMark 精神简化）：

- 开定界符后必须是非空白；闭定界符前必须是非空白
- `_` / `__` 不做词内强调：开定界符前一字符是字母数字、或闭定界符后一字符是字母数字时不成立
- 同一位置按 `***` → `**` → `*` → `___` → `__` → `_` → `~~` 顺序尝试开定界符，取第一个能找到合法闭定界符的；失败则字面输出一个字符
- 强调内容递归解析（支持嵌套），行内代码优先级最高

### 2.3 纯文本转换（`ui/ai/MarkdownPlainText.kt`）

```kotlin
object MarkdownPlainText {
    fun convert(markdown: String): String   // parse + 逐块转换 + 行内 plainText
}
```

| 块 | 转换 |
|---|---|
| 标题 | 文字（去 `#`） |
| 段落 | 原文本（行内已剥离） |
| 无序/有序 | 每项一行 `- x` / `N. x`（N 按 start 递增）；子列表前缀 `  - ` / `  1. ` |
| 任务 | `- [ ] x` / `- [x] x` |
| 引用 | 内部块转换后，非空行前缀 `> ` |
| 代码块 | 代码原文（去围栏、丢语言标记） |
| 分隔线 | `---` |
| 表格 | 每行单元格以 ` | ` 连接（含表头行） |
| 块间隔 | 块之间空一行；输出首尾 trim |

链接转换由 `MarkdownInline.plainText` 展开为「标签（url）」。

### 2.4 渲染层（`ui/ai/MarkdownContent.kt`）

```kotlin
@Composable fun MarkdownContent(content: String, modifier: Modifier = Modifier)
```

- `remember(content) { MarkdownParser.parse(content) }` 后按块渲染；`CodeBlockView` 从 `AiChatScreen.kt` 迁入（视觉不变：圆角 8dp、`surface` 底、语言标签、等宽 `bodySmall`、横向滚动）
- 标题字号：H1 `titleMedium`、H2 `titleSmall`、H3 `bodyLarge`、H4–H6 `bodyMedium`（加粗）
- 段落/列表/表格单元格用 `bodyMedium`；块间距 6dp
- 列表：标记列固定宽 24dp（无序 `•`、有序 `N.`、任务 `☐`/`☑`），子列表缩进 16dp
- 引用：`Row(Modifier.height(IntrinsicSize.Min))`，左侧 3dp 竖线（`outlineVariant`）+ 内容区 `LocalContentColor = onSurfaceVariant`，内容递归 `MarkdownBlocks`
- 分隔线：`HorizontalDivider`
- 表格：`BoxWithConstraints` 取可用宽度，列宽 = `max(可用宽 / 列数, 80.dp)`，整表置于 `horizontalScroll` 中；列宽等分，单元格按对齐方式 `textAlign`，表头加粗 + 表头下 `HorizontalDivider`
- 行内代码底色取 `MaterialTheme.colorScheme.surface`；链接用 `LinkAnnotation.Url`（注解内联 `TextLinkStyles`：主题色 + 下划线）由 `Text` 自动处理点击（`LocalUriHandler`），无浏览器时系统静默忽略
- 表格单元格、列表项、标题、段落文本均走 `MarkdownInline.build`

### 2.5 接线（`ui/ai/AiChatScreen.kt`）

- `AssistantContent` 删除，助手气泡改用 `MarkdownContent`
- `StreamingBubble` 正文改用 `MarkdownContent`（不再区分行内/块级，未闭合标记字面显示、闭合后自动成样式）；空文本时的「正在等待回答…」文案保留
- `MessageBubble`：`val plain = remember(message.content) { MarkdownPlainText.convert(message.content) }`；「插入正文 / 替换选中 / 存为新笔记」传 `plain`；「复制」仍传原始 Markdown
- `AiChatViewModel`、`data/`、`di/`、数据库均零改动

## 3. 非目标

- 语法高亮、图片加载（含网络图）、HTML / 数学公式 / 脚注 / `~~~` 围栏 / 引用式链接 / 裸 URL 自动链接、列表嵌套超过一层、表格嵌套块
- 用户气泡、深度思考折叠区、笔记正文的 Markdown 渲染
- 复制按钮转纯文本、渲染结果的富文本分享
- 新增三方依赖、数据库或 ViewModel 改动

## 4. 测试策略

全部纯 JVM 单测（沿用 `HighlightTextTest` 用 `AnnotatedString`、`Color` 的先例，无需 Robolectric）：

- `MarkdownParserTest`：段落合并与换行保留、标题（等级/无空格不成立/尾部 `#`）、分隔线三种字符与 `- - -`、围栏全部旧用例移植（语言标记/未闭合/空块忽略与相邻文本合并/缩进/CRLF/代码内空行/行内反引号不成立）、引用（单行/多行/嵌套/含列表）、列表（无序/有序 start/类型切换/子列表/三层递归嵌套/任务勾选/续行/空行后续行判定）、表格（基本/对齐/缺列补空/多列截断/`\|` 转义/无分隔行降级段落/非管道行结束）、`~~~` 字面降级
- `MarkdownInlineTest`：纯文本不变、粗/斜/粗斜/删除线的文本与 span 范围、`_` 词内不强调、行内代码等宽与底色且内部不解析、代码首尾空格规则、链接 `LinkAnnotation.Url` 范围与 URL、图片 alt/空 alt、转义、未闭合字面、嵌套强调、`plainText` 的链接展开与图片 alt
- `MarkdownPlainTextTest`：各块转换、列表嵌套前缀与递增序号、任务框、引用前缀、代码去围栏、表格 ` | ` 连接、链接展开、块间空行
- 删除 `CodeBlockParser.kt` / `CodeBlockParserTest.kt`（用例移植后）
- 回归：全量 `.\gradlew :app:testDebugUnitTest` + `.\gradlew :app:assembleDebug` 通过

## 5. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 行内定界符边界（病态嵌套、词内 `_`） | 务实子集 + 明确字面降级；专项目利用例覆盖 |
| 流式每 chunk 重解析长文本 | 纯函数 O(n)；回答量级（数十 KB 以内）单次解析毫秒级；`remember(content)` 避免重复解析同一文本 |
| 表格等宽列在窄气泡中观感 | 列宽最小 80dp + 横向滚动；单元格换行 |
| `LinkAnnotation.Url` / `Text(linkStyles)` API 版本 | Compose BOM 2024.09.03（ui 1.7.x）已具备；编译验证，不可用则退回自定义点击方案 |
| 旧 `CodeBlockParser` 行为回归 | 12 条既有用例原样移植到新测试 |

## 6. 修订记录

- 2026-09-15：初版。用户确认：A 档语法（含表格）、流式实时渲染、链接可点、复制保留原文而插入/存笔记转纯文本、表格横向滚动、支持任务列表、仅 AI 回答气泡、图片语法降级为 alt；授权自行写文档与实现。
