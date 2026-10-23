package com.tingxia.audio.audio

/**
 * 字幕时间轴（2026-10-23 新增）。
 *
 * ## 数据从哪来
 *
 * 服务端**没有任何字幕/时间轴数据**（查过：`subtitle` / `caption` /
 * `timing` 一无所获），但有完整��**听感稿** `script_text` ——
 * 详情接口返回全文，列表接口只给 120 字摘要。
 * 而 TTS 是**按 500 字句读边界分块合成**的，分块边界天然就是字幕段落边界。
 *
 * ## 为什么是「估算」而不是精确
 *
 * 生产用的 TTS provider（OpenAI 兼容的 Qwen3-TTS）**不返回词级时间戳**；
 * edge-tts 有（`SubMaker` / WordBoundary 事件），但生产没在用它。
 *
 * 所以 v1 用**按字数比例分配**：把听感稿按句切开，按各句的「说话字数」
 * 占全文的比例分配音频时长。TTS 语速实测 4.31~4.78 字/秒（相当均匀），
 * 误差约 ±1~3 秒 —— 够跟得上，不追求逐字精确。
 *
 * 将来服务端若在蒸馏时记下每段的实测时长（`subtitle_cues`），
 * 客户端优先用服务端的，**这一层就退化成兜底**。
 *
 * ## 为什么纯函数
 *
 * 时间轴计算是纯计算（输入：文本 + 时长；输出：若干条 cue），
 * 做成纯函数才能**用单测钉住边界**：空文本、零时长、只有标点、
 * 超长无标点段落、末尾无标点。
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

class SubtitleTimeline(val cues: List<SubtitleCue>) {

    /**
     * 某一时刻该显示哪一条。
     *
     * 用二分查找而不是线性扫：长文能有几百条，每 500ms 查一次，
     * 线性扫在低端机上会明显费电。
     */
    fun cueAt(positionMs: Long): SubtitleCue? {
        if (cues.isEmpty()) return null
        var lo = 0
        var hi = cues.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val cue = cues[mid]
            if (positionMs < cue.startMs) {
                hi = mid - 1
            } else {
                found = mid
                lo = mid + 1
            }
        }
        // 播完了（position 超过最后一条的结束）→ 不显示
        if (found >= 0) {
            val cue = cues[found]
            if (positionMs <= cue.endMs) return cue
        }
        return null
    }

    companion object {
        val EMPTY = SubtitleTimeline(emptyList())

        /** 句末标点 —— 在这些地方断句。 */
        private val SENTENCE_END = charArrayOf('。', '！', '？', '；', '\n', '!', '?', ';')

        /** 句中标点 —— 长段落在这里再切一刀。 */
        private val CLAUSE_END = charArrayOf('，', '、', ',', '：', ':')

        /** 不发音的标点 —— 分配时长时要**排除**，否则停顿会被算成说话。 */
        private val NON_SPOKEN = charArrayOf(
            '。', '！', '？', '；', '\n', '，', '、', '：',
            '.', '!', '?', ';', ',', ':', ' ', '\t', '　',
        )

        /** 一条字幕的最长字数 —— 超过就再切（太长的字幕在手机上会占满屏）。 */
        private const val MAX_SEGMENT_CHARS = 34

        /**
         * 一条字幕的最短时长（毫秒）—— 估算出来不足这个数的段落会并进上一段。
         *
         * 为什么按**时长**而不是按字数判断：
         * 字数是绝对量，而「一闪一闪」是**相对**感受 ——
         * 同样 4 个字，在 30 秒的音频里占 10 秒（很舒服），
         * 在 5 分钟的音频里只占 0.5 秒（一闪而过，来不及看）。
         * 所以阈值必须跟着语速走。
         */
        private const val MIN_CUE_MS = 1_200L

        /**
         * 由听感稿 + 音频时长算出时间轴。
         *
         * @param scriptText 听感稿全文（详情接口的 `script_text`）
         * @param durationMs 音频总时长（毫秒）
         */
        fun from(scriptText: String?, durationMs: Long): SubtitleTimeline {
            if (scriptText.isNullOrBlank() || durationMs <= 0L) return EMPTY
            val segments = splitIntoSegments(scriptText)
            if (segments.isEmpty()) return EMPTY

            val totalWeight = segments.sumOf { it.spokenChars }
            if (totalWeight <= 0) return EMPTY

            // 语速（字/毫秒）—— 用来判断某条字幕是不是「一闪而过」。
            val charsPerMs = totalWeight.toDouble() / durationMs

            // 太碎的并进**上一段**（不是下一段）：
            // 并进下一段会把后面那句「推迟」，而并进上一段只影响自己这一条。
            //
            // 判据是估算时长（见 [MIN_CUE_MS]）—— 不足 1.2 秒的句子在字幕里
            // 等于没出现（用户眼睛还没聚焦它就跳走了）。
            val merged = ArrayList<Segment>()
            for (seg in segments) {
                val estimatedMs = if (charsPerMs > 0) (seg.spokenChars / charsPerMs).toLong() else 0L
                val last = merged.lastOrNull()
                if (last != null && estimatedMs < MIN_CUE_MS) {
                    merged[merged.lastIndex] = Segment(
                        last.text + seg.text,
                        last.spokenChars + seg.spokenChars,
                    )
                } else {
                    merged += seg
                }
            }

            var acc = 0L
            val cues = merged.map { seg ->
                val start = durationMs * acc / totalWeight
                acc += seg.spokenChars
                val end = durationMs * acc / totalWeight
                SubtitleCue(startMs = start, endMs = end, text = seg.text)
            }
            return SubtitleTimeline(cues)
        }

        private data class Segment(val text: String, val spokenChars: Int)

        /**
         * 把听感稿切成适合做字幕的段落。
         *
         * 三条规则：
         * 1. 在句末标点处断；
         * 2. 超过 [MAX_SEGMENT_CHARS] 的段落，在句中标点处再切一刀；
         * 3. 太碎的段落（估算不足 [MIN_CUE_MS]）由调用方并进上一段。
         */
        private fun splitIntoSegments(text: String): List<Segment> {
            val raw = ArrayList<String>()
            val sb = StringBuilder()
            for (ch in text) {
                sb.append(ch)
                if (ch in SENTENCE_END) {
                    raw += sb.toString()
                    sb.setLength(0)
                }
            }
            if (sb.isNotEmpty()) raw += sb.toString()

            // 太长的段落再切一刀（按句中标点；实在没有标点就按字数硬切）
            val chunks = ArrayList<String>()
            for (piece in raw) {
                val trimmed = piece.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.length <= MAX_SEGMENT_CHARS) {
                    chunks += trimmed
                    continue
                }
                val buffer = StringBuilder()
                for (ch in trimmed) {
                    buffer.append(ch)
                    if (ch in CLAUSE_END && buffer.length >= MAX_SEGMENT_CHARS / 2) {
                        chunks += buffer.toString().trim()
                        buffer.setLength(0)
                    }
                }
                if (buffer.isNotEmpty()) chunks += buffer.toString().trim()
                // 句中标点也切不动时（整段没有逗号）→ 按字数硬切
                for (piece2 in chunks.filter { it.length > MAX_SEGMENT_CHARS }) {
                    chunks.remove(piece2)
                    var i = 0
                    while (i < piece2.length) {
                        val end = (i + MAX_SEGMENT_CHARS).coerceAtMost(piece2.length)
                        chunks += piece2.substring(i, end)
                        i = end
                    }
                }
            }

            return chunks.filter { it.isNotBlank() }.map {
                Segment(it, it.count { ch -> ch !in NON_SPOKEN })
            }
        }
    }
}
