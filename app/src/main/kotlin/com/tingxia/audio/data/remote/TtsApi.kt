package com.tingxia.audio.data.remote

import com.tingxia.audio.data.model.PlaybackSpeedBody
import com.tingxia.audio.data.model.TtsPreference
import com.tingxia.audio.data.model.TtsVoiceList
import com.tingxia.audio.data.model.VoiceSelectionBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

/**
 * CP-TTS-VOICE: 音色列表 + 我的音色/语速偏好。
 *
 * 端点挂在 content-service,api-gateway 显式注册转发
 * (见 backend/api-gateway/config.py —— 新前缀 `tts` / `users` 都不在
 * fallback 的猜测表里,漏注册就是 404,历史坑 CP7.3.5 同款)。
 */
interface TtsApi {

    /** 可选购音色 + 服务端下发的语速档位 */
    @GET("api/v1/tts/voices")
    suspend fun getVoices(): TtsVoiceList

    /** 我的偏好(音色 + 语速 + 实际生效的音色) */
    @GET("api/v1/users/me/tts-preference")
    suspend fun getMyPreference(): TtsPreference

    /**
     * 改播放语速。**只动语速**,请求体里不含 `voice_id` 键 → 后端 UNSET。
     */
    @PUT("api/v1/users/me/tts-preference")
    suspend fun updateSpeed(@Body body: PlaybackSpeedBody): TtsPreference

    /**
     * 改音色。`voiceId = null` 表示「跟随默认音色」,请求体**一定带** `voice_id` 键
     * （靠 @EncodeDefault(ALWAYS),见 TtsPreferences.kt 的说明）。
     */
    @PUT("api/v1/users/me/tts-preference")
    suspend fun updateVoice(@Body body: VoiceSelectionBody): TtsPreference
}
