package com.tingxia.audio.ui.privacy

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * 极简 Markdown 渲染：**不引入任何 Markdown 库**。
 *
 * ## 为什么自己写
 *
 * 隐私政策正文是服务端下发的 **Markdown**（`backend/common/policies/privacy_policy.md`）。
 * 常见做法是引一个 Markdown 解析库，但：
 * - 政策正文只需要"读起来像回事"，不需要完整的 CommonMark 语法树；
 * - 为了十几行标题/加粗/列表去背一个库的体积和 API 风险不划算。
 *
 * 所以这里只处理**政策正文真正用到的**那几种标记，处理不掉的就当普通文字留下
 * （宁可多显示几个 `**`，也不要丢内容）。
 *
 * ## 支持的标记
 *
 * | 标记 | 处理方式 |
 * |---|---|
 * | `# 标题` ~ `###### 标题` | 变成标题段落（保留层级） |
 * | `**加粗**` | 加粗片段 |
 * | `*斜体*` / `_斜体_` | 斜体片段 |
 * | `- 项目` / `* 项目` / `+ 项目` | 无序列表项 |
 * | `1. 项目` | 有序列表项（保留序号） |
 * | `> 引用` | 引用段落 |
 * | `---` | 分隔线 |
 * | `[文字](链接)` | 只显示文字（链接 URL 与文字不同时附在后面） |
 * | `\| 表格 \|` | 渲染成"表头：值"的成对文本 |
 */
enum class PolicyBlockKind {
    HEADING,
    PARAGRAPH,
    BULLET,
    ORDERED,
    QUOTE,
    RULE,
    TABLE_ROW,
}

/** 解析出来的一段。 */
data class PolicyBlock(
    val text: AnnotatedString,
    val kind: PolicyBlockKind,
    /** 标题层级（1 起），非标题为 0。 */
    val level: Int = 0,
    /** 有序列表的序号，非有序列表为 0。 */
    val order: Int = 0,
)

/**
 * Markdown → [PolicyBlock] 列表。
 *
 * 单独成对象（而不是塞进 composable）是为了能直接跑单测：
 * 政策正文是**服务端**下发的，客户端改坏了渲染只有真机才发现得了 ——
 * 所以"标题/加粗/列表有没有被正确剥掉"必须有测试兜着。
 */
object PolicyMarkdown {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val QUOTE = Regex("^>\\s?(.*)$")
    private val BULLET = Regex("^[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^(\\d+)[.)]\\s+(.*)$")

    /**
     * 行内标记。
     *
     * 顺序很重要：**加粗必须排在斜体前面** —— 否则 `**x**` 会被斜体那条
     * 规则先吃掉一半（匹配到 `*x*`），剩下一个 `*` 变成正文里的星号。
     */
    private val INLINE = Regex(
        """(\*\*[^*]+\*\*)|(\[[^\]]*\]\([^)]*\))|(\*[^*\n]+\*)|(_[^_\n]+_)""",
    )

    /** 表格的分隔行（`|---|---|` / `| :--- | :---: |`）。 */
    private val TABLE_SEPARATOR = Regex("^:?-{2,}:?$")

    /** 解析整篇政策正文。 */
    fun parse(markdown: String): List<PolicyBlock> {
        val lines = markdown.lines()
        val blocks = mutableListOf<PolicyBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }
            // 表格：一连串以 | 开头的行
            if (line.startsWith("|")) {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    tableLines += lines[i].trim()
                    i++
                }
                blocks += parseTable(tableLines)
                continue
            }
            i++
            when {
                HEADING.matches(line) -> {
                    val m = HEADING.find(line)!!
                    blocks += PolicyBlock(
                        text = inline(m.groupValues[2].trim()),
                        kind = PolicyBlockKind.HEADING,
                        level = m.groupValues[1].length,
                    )
                }

                RULE.matches(line) -> {
                    blocks += PolicyBlock(AnnotatedString(""), PolicyBlockKind.RULE)
                }

                QUOTE.matches(line) -> {
                    val m = QUOTE.find(line)!!
                    blocks += PolicyBlock(inline(m.groupValues[1].trim()), PolicyBlockKind.QUOTE)
                }

                BULLET.matches(line) -> {
                    val m = BULLET.find(line)!!
                    blocks += PolicyBlock(inline(m.groupValues[1].trim()), PolicyBlockKind.BULLET)
                }

                ORDERED.matches(line) -> {
                    val m = ORDERED.find(line)!!
                    blocks += PolicyBlock(
                        text = inline(m.groupValues[2].trim()),
                        kind = PolicyBlockKind.ORDERED,
                        order = m.groupValues[1].toIntOrNull() ?: 0,
                    )
                }

                else -> blocks += PolicyBlock(inline(line), PolicyBlockKind.PARAGRAPH)
            }
        }
        return blocks
    }

    /**
     * 解析行内标记（加粗 / 斜体 / 链接）。
     *
     * 处理不了的标记**原样留下**（比如没配对的 `**`）——
     * 宁可多显示两个星号，也不要把用户的政策正文吞掉一段。
     */
    fun inline(text: String): AnnotatedString {
        val builder = AnnotatedString.Builder()
        var last = 0
        INLINE.findAll(text).forEach { m ->
            if (m.range.first > last) builder.append(text.substring(last, m.range.first))
            val token = m.value
            when {
                token.startsWith("**") -> builder.withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold),
                ) { append(token.removeSurrounding("**")) }

                token.startsWith("[") -> {
                    val linkText = token.substringAfter('[').substringBefore(']')
                    val url = token.substringAfter("](").removeSuffix(")")
                    builder.append(linkText)
                    // 文字和 URL 不一样时把 URL 也带上 ——
                    // 政策里的链接是"用户可以去核对的地址"，光留四个字等于把地址丢了。
                    if (url.isNotBlank() && url != linkText) {
                        builder.append("（$url）")
                    }
                }

                token.startsWith("*") -> builder.withStyle(
                    SpanStyle(fontStyle = FontStyle.Italic),
                ) { append(token.removeSurrounding("*")) }

                else -> builder.withStyle(
                    SpanStyle(fontStyle = FontStyle.Italic),
                ) { append(token.removeSurrounding("_")) }
            }
            last = m.range.last + 1
        }
        if (last < text.length) builder.append(text.substring(last))
        return builder.toAnnotatedString()
    }

    /**
     * 表格 → "表头：值" 的成对文本。
     *
     * 纯文本渲染没有"列"的概念，`| 目的 | 涉及的信息 |` 这种表格直接印出来
     * 是一行竖线，读起来像乱码。所以这里用表头把每一行翻译成
     * "目的：生成音频；涉及的信息：剪藏的链接与正文" 这种人能读的句子。
     */
    private fun parseTable(rows: List<String>): List<PolicyBlock> {
        val cellRows = rows.map { row ->
            row.trim().trim('|').split('|').map { it.trim() }
        }
        val hasSeparator = cellRows.getOrNull(1)?.all { cell ->
            cell.isNotEmpty() && TABLE_SEPARATOR.matches(cell)
        } == true
        val header = if (hasSeparator) cellRows.firstOrNull().orEmpty() else emptyList()
        val body = if (hasSeparator) cellRows.drop(2) else cellRows.drop(1)

        return body.mapNotNull { cells ->
            val pairs = cells.mapIndexed { index, cell ->
                if (cell.isBlank()) return@mapIndexed null
                val head = header.getOrNull(index)
                if (!head.isNullOrBlank()) "$head：$cell" else cell
            }.filterNotNull()
            if (pairs.isEmpty()) {
                null
            } else {
                PolicyBlock(inline(pairs.joinToString("；")), PolicyBlockKind.TABLE_ROW)
            }
        }
    }
}
