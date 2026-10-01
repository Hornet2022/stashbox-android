package com.tingxia.audio.data.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * CP-TTS-VOICE: 音色与播放语速偏好。
 *
 * 服务端契约:
 *  - GET  /api/v1/tts/voices              → [TtsVoiceList]
 *  - GET  /api/v1/users/me/tts-preference → [TtsPreference]
 *  - PUT  /api/v1/users/me/tts-preference → [TtsPreference]
 * 实现见 backend/content-service/tts_voice_router.py
 *
 * 字段全 nullable / 带默认值: 后端任一字段缺失都不该让反序列化炸掉
 * (与 QuotaResponse / CreateArticleResponse 同一设计思路)。
 *
 * ⚠️ `availableSpeeds` 是**服务端单一事实源**,App 不要硬编码档位 ——
 * 后台改了这里就改了全端行为,不需要发 App 版本。此前语速的假闭环
 * (FullScreenPlayerScreen 里五档写死 + 纯本地 state) 正是因为没有这个契约。
 */
@Serializable
data class TtsVoice(
    val id: String,
    val slug: String = "",
    @SerialName("display_name") val displayName: String = "",
    val description: String? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
)

@Serializable
data class TtsVoiceList(
    val voices: List<TtsVoice> = emptyList(),
    @SerialName("available_speeds") val availableSpeeds: List<Float> = emptyList(),
    @SerialName("default_speed") val defaultSpeed: Float = 1.0f,
)

/**
 * 「这段音频是谁念的」（CP-TTS-VOICE 溯源，回挂到 [Article]）。
 *
 * 与 [TtsVoice] 的区别：那是**现在能选**的音色（`GET /tts/voices` 的列表项），
 * 这是**当时用的**音色。两个都要 `available`，但语义相反 ——
 * 溯源为 false 表示「这个音色已被下架/删除」，但**名字照样要显示**：
 * 这段音频确实是它合成的，藏掉名字等于让来源凭空消失，用户会以为是自己记错了。
 */
@Serializable
data class TtsVoiceBrief(
    val id: String,
    val name: String = "",
    val available: Boolean = true,
)

/**
 * 我的音色/语速偏好。
 *
 * [effectiveVoiceName] / [effectiveSource] 是**实际生效**的音色,不是用户选的:
 * 用户可能从没设过偏好,这时生效的是全局默认音色或全局 TTS 配置。
 * 界面应该显示 effective* —— 只显示自己选的那栏会出现
 * 「未选择音色,但听着明显是某个人念的」这种说不通的界面
 * (本项目反复出现的假闭环形态)。
 *
 * [effectiveSource] 取值: `user` / `default` / `global_config`
 */
@Serializable
data class TtsPreference(
    @SerialName("voice_id") val voiceId: String? = null,
    @SerialName("voice_name") val voiceName: String? = null,
    val speed: Float = 1.0f,
    @SerialName("available_speeds") val availableSpeeds: List<Float> = emptyList(),
    @SerialName("effective_voice_name") val effectiveVoiceName: String? = null,
    @SerialName("effective_source") val effectiveSource: String = "global_config",
)

/**
 * PUT 请求体 —— **按意图拆成两个形状**,因为「不传 voice_id」和「显式传 null」
 * 在 JSON 里是两种不同的东西,用单个可空字段表达不了:
 *
 *  - [PlaybackSpeedBody] 根本不含 `voice_id` 键 → 后端 UNSET → 只改语速,不动音色
 *  - [VoiceSelectionBody] 永远带 `voice_id` 键(值可能是 null) → 后端按值处理
 *
 * 为什么不能图省事用 `encodeDefaults = ALWAYS` 的单个可空字段:
 * 那样「只改语速」也会带上 `voice_id: null`,后端收到就**把用户的音色清掉了**。
 * kotlinx.serialization 默认 `encodeDefaults = false`,所以下面 [VoiceSelectionBody]
 * 必须显式标 `@EncodeDefault(ALWAYS)`,否则 null 会被静默省略,
 * 「清空音色」退化成「没改音色」—— 用户点了没反应,且没有任何报错。
 *
 * 两个形状打的是**同一个端点**,不存在两条路径两种口径的问题
 * (本项目在 CP-DISTILL-START-HARDEN 第 2 条上吃过这个亏)。
 */
@Serializable
data class PlaybackSpeedBody(val speed: Float)

@Serializable
data class VoiceSelectionBody(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("voice_id") val voiceId: String?,
)

