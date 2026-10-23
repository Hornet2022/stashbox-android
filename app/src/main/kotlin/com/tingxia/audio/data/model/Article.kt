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
 */
@Serializable
data class ContentParts(
    @SerialName("sections") val sections: List<String> = emptyList(),
    @SerialName("insights") val insights: List<String> = emptyList(),
    @SerialName("related") val related: List<String> = emptyList(),
    @SerialName("follow_ups") val followUps: List<String> = emptyList(),
) {
    /** 有没有任何分区内容（老数据 / 降级稿 → false）。 */
    val isEmpty: Boolean
        get() = sections.isEmpty() && insights.isEmpty() && related.isEmpty() && followUps.isEmpty()
}
