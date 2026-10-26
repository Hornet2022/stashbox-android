package com.tingxia.audio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 文章实体（对应后端 GET /api/v1/articles / GET /api/v1/articles/{id}）。
 *
 * 后端字段是 snake_case（audio_url / task_id / created_at / duration_sec），
 * kotlinx-serialization 默认不做命名转换 —— 必须用 @SerialName 显式映射，
 * 否则详情页 article.audioUrl 永远为 null，auto-play 永远不触发。
 *
 * @param id        文章唯一 id
 * @param url       原文链接（详情页可复制）
 * @param title     标题
 * @param source    来源标识：wechat / douyin / general
 * @param status    蒸馏状态（见 [DistillStatus]）
 * @param taskId    关联的蒸馏任务 id（status != ready 时用于轮询）
 * @param audioUrl  蒸馏完成后的音频地址（status == ready 时存在）
 * @param createdAt 创建时间（ISO 字符串）
 * @param durationSec 蒸馏音频时长（秒，列表 / 详情页可显示）
 */
@Serializable
data class Article(
    val id: String,
    val url: String = "",
    val title: String? = null,
    val source: String = "general",
    val status: DistillStatus = DistillStatus.PENDING,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("audio_url") val audioUrl: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("duration_sec") val durationSec: Int? = null,
    // CP-TIME：蒸馏完成时间（distilled_articles.updated_at，ready/failed 时有）
    @SerialName("distilled_at") val distilledAt: String? = null,
    // articles 表的 updated_at（用于失败列表的「失败于」时间）
    @SerialName("updated_at") val updatedAt: String? = null,
    // CP-TAG-FILTER：蒸馏 LLM 自动生成的标签（中文名，pending 时为 null）
    @SerialName("tags") val tags: List<String>? = null,
    // CP-DISTILL-TEXT：LLM 听感改写稿正文（hook/body/outro 以空行分段）。
    // 详情页展示"整理后的正文"；列表页为 120 字摘要。蒸馏未完成时为 null。
    @SerialName("script_text") val scriptText: String? = null,
    // 2026-10-23：**三类再创作内容的分区**。
    //
    // 这三类（延伸判断 insights / 关联思考 related / 拓展方向 follow_ups）
    // 在服务端落库时本来是分开的，但 TTS 要连着念整稿 ⇒ 被合并进了
    // `script_text`。合并之后客户端只能把所有段落平铺显示，**分不清**
    // 哪段是正文、哪段是关联思考。
    //
    // `content_parts` 是服务端另存的一份**未合并**的分区视图，让详情页
    // 能分区展示。**只有详情页带这个字段**（列表恒为 null）。
    //
    // 蒸馏未完成 / 老数据 → null（客户端按「无分区」处理）。
    @SerialName("content_parts") val contentParts: ContentParts? = null,
    // CP-TTS-VOICE 溯源：这段音频当时用哪个音色合成的。
    // null 有两种含义，客户端不必区分：迁移前跑的历史文章（无从得知）、
    // 以及用全局 TTS 配置合成的（来源不可溯源，服务端刻意不塞假音色）。
    // **只有详情页会带这个字段**，列表接口恒为 null（见 backend 的 schema 注释）。
    @SerialName("tts_voice") val ttsVoice: TtsVoiceBrief? = null,
    // 蒸馏当前阶段（fetch/rewrite/tts/concat），仅 distilling 时非空。
    //
    // 为什么要它：蒸馏是异步长任务，**TTS 合成占时延大头**（上游 RTF≈0.3，
    // 1000 字稿要合成 4 分钟音频）。只显示「蒸馏中」时用户无从判断是在
    // 正常干活还是卡死 —— 这个字段让 UI 能显示「正在合成语音…」。
    //
    // 阶段值直接取自服务端的 agent current_step，不另造一套枚举。
    @SerialName("stage") val stage: String? = null,
    // 0041：用户剪藏内容共享（2026-10-26 产品拍板）。
    //
    // `shared` = 这篇是否已共享给订阅者（true 表示订阅了 `shared_tags` 里
    // 任一标签的其他用户能通过订阅流看到它）。**属主永远能看自己的内容**，
    // 这个字段只描述「对别人是否可见」。
    //
    // `sharedTags` = 用户共享时手动选的公开标签（中文标签名数组）。
    // 与 `tags`（LLM 分类）**并存**：共享是「额外公开」，不覆盖 LLM 的分类。
    //
    // ⚠️ 这两个字段**只对属主**有意义，订阅者视角恒为 `shared=false` /
    // `sharedTags=null`（不泄露属主的共享意愿）。所以只有属主自己的文章
    // 详情/列表才会带它们。
    @SerialName("shared") val shared: Boolean = false,
    @SerialName("shared_tags") val sharedTags: List<String>? = null,
)

/**
 * 稿子的**分区**结构（2026-10-23）。
 *
 * 服务端把「延伸判断 / 关联思考 / 拓展方向」单独存了一份（因为它们在
 * `script_text` 里已被合并进正文段落，客户端分不清）。
 *
 * · [sections] 正文要点（**不含** hook / outro，也不含下面三类）
 * · [insights] 延伸判断 —— 「往后看会怎样、什么时候会失效」
 * · [related] 关联思考 —— 「和什么相关 / 矛盾 / 互相印证」
 * · [followUps] 拓展方向 —— 「接下来该盯住什么」
 *
 * 四个列表都可能为空（模型判断不成立时就不写），不是「必须有内容」。
 *
 * ## CP-TITLE-ANNOUNCE（2026-10-26）：hook / outro / intro
 *
 * 服务端给音频开头加了一句**标题播报**（"本期分享的标题是：…"），
 * 而播报句为了跟音频对齐**也进了 `script_text`** ——
 * 于是「script_text 第一段 = 开场钩子」这个旧约定**不再成立**。
 *
 * 所以服务端把三样东西**明确**交出来，客户端不必去猜：
 * · [hook]  真正的开场钩子（详情页放大作导语的就是它）
 * · [outro] 收束段
 * · [intro] 标题播报句（是口播，不是正文 —— 不作为正文展示）
 *
 * ⚠️ 老数据没有这三个字段（null）⇒ 客户端必须回退到「首段/末段」的旧取法。
 */
@Serializable
data class ContentParts(
    @SerialName("sections") val sections: List<String> = emptyList(),
    @SerialName("insights") val insights: List<String> = emptyList(),
    @SerialName("related") val related: List<String> = emptyList(),
    @SerialName("follow_ups") val followUps: List<String> = emptyList(),
    @SerialName("hook") val hook: String? = null,
    @SerialName("outro") val outro: String? = null,
    @SerialName("intro") val intro: String? = null,
) {
    /** 有没有任何分区内容（老数据 / 降级稿 → false）。 */
    val isEmpty: Boolean
        get() = sections.isEmpty() && insights.isEmpty() && related.isEmpty() && followUps.isEmpty()
}

/**
 * 稿子的**展示视图**（CP-TITLE-ANNOUNCE，2026-10-26）。
 *
 * 为什么要单独抽一个纯函数：详情页那套「哪段是导语 / 哪段是正文 / 播报句要不要
 * 摘掉」的判断一旦写在 Composable 里，就**没法用单测钉住** ——
 * 而它恰恰是最容易悄悄错的一处（服务端加字段、客户端改展示顺序，
 * 两边一错位，导语就变成了「本期分享的标题是：…」，而 App 不报任何错）。
 *
 * @param hook           导语（放大显示的那段）
 * @param outro          收束段
 * @param bodyParagraphs 正文段落（**不含**播报句）
 * @param hasParts       走分区渲染还是平铺渲染
 */
data class ScriptView(
    val hook: String?,
    val outro: String?,
    val bodyParagraphs: List<String>,
    val hasParts: Boolean,
)

/**
 * 把 `script_text` + `content_parts` 解析成展示视图。
 *
 * ## 规则（与服务端一一对应）
 *
 * 1. **播报句不当正文**：服务端给音频开头加了一句标题播报
 *    （"本期分享的标题是：…"），它为了跟音频对齐也进了 script_text ——
 *    但它是**口播**，不是内容，详情页不该把它当正文/导语显示。
 *    服务端用 `content_parts.intro` 明确指出它是谁 ⇒ 客户端按它摘，不靠猜。
 * 2. **导语用服务端给的 hook**：`content_parts.hook` 才是真正的开场钩子。
 *    没有（老数据）才回退「正文第一段」。
 * 3. **平铺 vs 分区**：`hasParts` 只看四个分区列表有没有内容 ——
 *    分区渲染只画「四类 + hook + outro」，若分区列表全空却走分区渲染，
 *    中间的正文段落会整段消失。
 */
fun resolveScriptView(scriptText: String?, contentParts: ContentParts?): ScriptView? {
    if (scriptText.isNullOrBlank()) return null
    val paragraphs = scriptText.trim()
        .split(Regex("\\n{2,}"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (paragraphs.isEmpty()) return null

    val intro = contentParts?.intro?.takeIf { it.isNotBlank() }
    val body = if (intro != null) paragraphs.filter { it != intro } else paragraphs
    if (body.isEmpty()) return null

    val hook = contentParts?.hook?.takeIf { it.isNotBlank() } ?: body.firstOrNull()
    val outro = contentParts?.outro?.takeIf { it.isNotBlank() }
        ?: body.lastOrNull().takeIf { body.size > 1 }

    return ScriptView(
        hook = hook,
        outro = outro,
        bodyParagraphs = body,
        hasParts = contentParts != null && !contentParts.isEmpty,
    )
}
