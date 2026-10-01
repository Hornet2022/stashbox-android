package com.tingxia.audio.ui.tts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.audio.PlayerController
import com.tingxia.audio.data.model.DistillStartResponse
import com.tingxia.audio.data.model.TtsPreference
import com.tingxia.audio.data.model.TtsVoice
import com.tingxia.audio.data.repository.TtsRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * CP-TTS-VOICE: 音色 + 播放语速的 ViewModel。
 *
 * 存在的意义是**把「持久化」和「生效」接在一起**。此前 App 里的语速只是个
 * `remember` 出来的本地变量,既没落盘也没进播放器 —— 典型的假闭环。
 * 现在:
 *   - [load]      启动/登录后拉一次,把服务端偏好转成播放器状态
 *   - [persistSpeed] / [selectVoice] 先改本地,再同步服务端
 *
 * 「先本地后云端」是有意的: 播放速度是**当下就能感知**的东西,不该因为
 * 网络慢而让用户等;云端失败只提示,不回滚(体验已经生效了)。
 */
@HiltViewModel
class TtsPreferenceViewModel @Inject constructor(
    private val repository: TtsRepository,
    private val playerController: PlayerController,
) : ViewModel() {

    data class UiState(
        val voices: List<TtsVoice> = emptyList(),
        val preference: TtsPreference? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
    ) {
        /** 实际生效的音色名。用户可能从没设过偏好,这时显示的是全局默认/配置音色。 */
        val effectiveVoiceName: String?
            get() = preference?.effectiveVoiceName

        /** 「跟随默认」时为 true —— 界面上应把默认音色也作为可选项列出来 */
        val followingDefault: Boolean
            get() = preference?.voiceId == null
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /**
     * 拉音色列表 + 我的偏好,并把语速**真正落到播放器**。
     *
     * 失败时用本地默认档位兜底,不阻塞 App 启动 —— 语速拿不到就用 1.0x,
     * 音色拿不到就显示「跟随默认」,都比让 App 卡在加载态强。
     */
    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val list = repository.getVoiceList()
                val pref = repository.getPreference()
                playerController.applySpeedPreference(
                    preferred = pref.speed,
                    // 偏好接口和列表接口都带 availableSpeeds，兜底到本地默认档位
                    options = pref.availableSpeeds
                        .ifEmpty { list.availableSpeeds }
                        .ifEmpty { PlayerController.DEFAULT_AVAILABLE_SPEEDS },
                )
                _uiState.value = UiState(
                    voices = list.voices,
                    preference = pref,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = friendlyError(e, "加载音色设置失败"),
                )
            }
        }
    }

    /**
     * 改播放语速。
     *
     * 语速是**播放端行为**,不触发重跑蒸馏 —— 实测单篇蒸馏约 23 分钟,
     * 让用户为调速等一刻钟不可接受。
     */
    suspend fun persistSpeed(speed: Float) {
        playerController.setSpeed(speed) // 本地立即生效
        val pref = repository.setSpeed(speed) // 云端同步(失败由调用方处理)
        _uiState.value = _uiState.value.copy(preference = pref)
    }

    /**
     * 选音色。
     *
     * `voiceId = null` = 跟随默认音色。音色是**合成期**参数，本次只改设置；
     * 存量文章要换音色得走 [regenerateWithCurrentVoice]。
     */
    suspend fun selectVoice(voiceId: String?) {
        val pref = repository.setVoice(voiceId)
        _uiState.value = _uiState.value.copy(preference = pref)
    }

    /**
     * 用当前音色重新蒸馏某一篇（CP-TTS-VOICE 的「回溯重跑」入口）。
     *
     * 走 `POST /api/v1/distill/start` 而不是新开端点 —— 后端刻意复用了这个
     * 端点：另开 `/re-distill` 会重演「同一件事两条路径两种计费口径」的老问题
     * （见 ai-service/main.py 的 CP-DISTILL-START-HARDEN 第 2 条）。
     *
     * 该端点**保留旧产物**：重跑期间旧音频仍能听，跑成功才覆盖；也不会重复扣配额
     * （它已有蒸馏行时跳过 consume）。
     *
     * ⚠️ 实测单篇约 **23 分钟**（16 段 TTS），所以 UI 必须先确认再调，
     * 且不能做成「一键静默重跑」。
     */
    suspend fun regenerateWithCurrentVoice(articleId: String): DistillStartResponse =
        repository.regenerateArticle(articleId)
}
