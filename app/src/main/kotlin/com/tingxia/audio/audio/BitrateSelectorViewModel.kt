package com.tingxia.audio.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.model.AudioVariant
import com.tingxia.audio.data.repository.VariantRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * §3 多码率选档 ViewModel。
 *
 * 用法：
 * 1. 调 [load](taskId) 拉 variants
 * 2. UI 展示 [uiState].variants 列表
 * 3. 用户点档 → [pickAndApply](bitrate) → 切档 + 通知 PlayerController
 */
@HiltViewModel
class BitrateSelectorViewModel @Inject constructor(
    private val variantRepository: VariantRepository,
    private val variantSelection: VariantSelectionUseCase,
    private val playerController: PlayerController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var taskId: String? = null

    fun load(taskId: String) {
        this.taskId = taskId
        _uiState.value = UiState(isLoading = true)
        viewModelScope.launch {
            runCatching { variantRepository.getVariants(taskId) }
                .onSuccess { resp ->
                    _uiState.value = UiState(
                        isLoading = false,
                        variants = resp.variants,
                        scenario = variantSelection.detectScenario(),
                        preferredBitrate = variantSelection.preferredBitrate(
                            variantSelection.detectScenario(),
                        ),
                    )
                }
                .onFailure { e ->
                    _uiState.value = UiState(
                        isLoading = false,
                        error = friendlyError(e, fallback = "获取码率失败"),
                    )
                }
        }
    }

    /** 切换到指定码率：替换 MediaItem 但保持当前位置。失败时 [UiState.switchError] 给出错误。 */
    fun pickAndApply(bitrate: Int) {
        val tid = taskId ?: return
        val variant = _uiState.value.variants.firstOrNull { it.bitrate == bitrate && it.available }
            ?: run {
                _uiState.update { it.copy(switchError = "该码率不可用") }
                return
            }
        val url = variant.url
        if (url == null) {
            _uiState.update { it.copy(switchError = "该码率无 URL") }
            return
        }
        _uiState.update { it.copy(isSwitching = true, switchError = null) }
        // 切档：调用 PlayerController.switchVariant 保持当前位置
        playerController.switchVariant(url, bitrate)
        _uiState.update {
            it.copy(
                isSwitching = false,
                currentBitrate = bitrate,
            )
        }
        // 后台调 warm 一次（让服务端缓存该档，后续秒开）
        viewModelScope.launch {
            if (bitrate == 96 || bitrate == 64) {
                runCatching { variantRepository.warmVariant(tid, bitrate) }
            }
        }
    }

    fun clearSwitchError() {
        _uiState.update { it.copy(switchError = null) }
    }

    data class UiState(
        val isLoading: Boolean = false,
        val variants: List<AudioVariant> = emptyList(),
        val scenario: VariantSelectionUseCase.Scenario = VariantSelectionUseCase.Scenario.WIFI_ONLINE,
        val preferredBitrate: Int = 128,
        val currentBitrate: Int = 128,
        val isSwitching: Boolean = false,
        val switchError: String? = null,
        val error: String? = null,
    )
}