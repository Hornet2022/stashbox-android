package com.tingxia.audio.data.repository

import com.tingxia.audio.data.model.DistillStartRequest
import com.tingxia.audio.data.model.DistillStartResponse
import com.tingxia.audio.data.model.PlaybackSpeedBody
import com.tingxia.audio.data.model.TtsPreference
import com.tingxia.audio.data.model.TtsVoice
import com.tingxia.audio.data.model.TtsVoiceList
import com.tingxia.audio.data.model.VoiceSelectionBody
import com.tingxia.audio.data.remote.DistillationApi
import com.tingxia.audio.data.remote.TtsApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CP-TTS-VOICE: 音色 + 播放语速。
 *
 * 沿用本项目 Repository 的惯例 —— **极薄透传,不包 Result**,
 * 异常上抛给 ViewModel 由它转成中文文案
 * (见 ui/ErrorMessages.kt 的 friendlyError)。
 *
 * 两个写方法的区别不是「重复」,而是**语义必需**:
 *  - [setSpeed] 的请求体没有 `voice_id` 键 → 后端不动音色
 *  - [setVoice] 一定带 `voice_id`(可为 null) → 后端按值处理
 * 用一个「都可空」的参数表达不了这个区别,详见 TtsPreferences.kt 的注释。
 */
@Singleton
class TtsRepository @Inject constructor(
    private val api: TtsApi,
    private val distillApi: DistillationApi,
) {

    /**
     * 音色列表 + 服务端下发的语速档位。
     *
     * 返回整个 [TtsVoiceList] 而不是只返回 `List<TtsVoice>`：档位和列表在
     * 同一个响应里，拆成两个方法会多打一次 HTTP。
     */
    suspend fun getVoiceList(): TtsVoiceList = api.getVoices()

    suspend fun getVoices(): List<TtsVoice> = getVoiceList().voices

    suspend fun getPreference(): TtsPreference = api.getMyPreference()

    /** 只改语速,不动当前音色。 */
    suspend fun setSpeed(speed: Float): TtsPreference = api.updateSpeed(PlaybackSpeedBody(speed))

    /** 改音色。`voiceId = null` = 跟随全局默认音色。 */
    suspend fun setVoice(voiceId: String?): TtsPreference =
        api.updateVoice(VoiceSelectionBody(voiceId))

    /**
     * 重新蒸馏某一篇（用用户当前选的音色）。
     *
     * 与「剪藏」分开的方法，但打的是同一个端点 —— 见 `TtsApi` 的说明，
     * 刻意不另开端点以免出现第二种计费口径。
     */
    suspend fun regenerateArticle(articleId: String): DistillStartResponse =
        distillApi.startDistill(DistillStartRequest(articleId))
}
