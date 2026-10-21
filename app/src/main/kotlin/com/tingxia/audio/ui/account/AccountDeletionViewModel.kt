package com.tingxia.audio.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.model.ACCOUNT_DELETE_CONFIRM_TEXT
import com.tingxia.audio.data.model.AccountDeleteResult
import com.tingxia.audio.data.model.isValidAccountDeleteConfirmText
import com.tingxia.audio.data.repository.AccountRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 注销账号的 ViewModel。
 *
 * ## 状态机
 *
 * ```
 *   输入确认短语 ──(逐字匹配)──> 按钮可点 ──> 提交中 ──> 已注销（显示结果）
 *                                        └────> 失败（显示错误，可重试）
 * ```
 *
 * 按钮能不能点由 [UiState.canSubmit] 说了算，**不靠 UI 层自己判断** ——
 * 判定规则只写一份（[isValidAccountDeleteConfirmText]），避免"界面能点但服务端 400"。
 */
@HiltViewModel
class AccountDeletionViewModel @Inject constructor(
    private val repository: AccountRepository,
) : ViewModel() {

    data class UiState(
        /** 用户在确认输入框里打的字。 */
        val confirmText: String = "",
        /** 是否正在请求服务端。 */
        val isSubmitting: Boolean = false,
        /** 注销结果（成功才有）。 */
        val result: AccountDeleteResult? = null,
        /** 错误文案（中文，可直接显示）。 */
        val error: String? = null,
    ) {
        /**
         * 注销按钮是否可点。
         *
         * 三个条件缺一不可：
         * - 确认短语**逐字**匹配（trim 后等于 `注销听匣`）；
         * - 没在提交中（否则会连发两个注销请求）；
         * - 还没成功（成功之后按钮必须锁死 —— 再点一次没有意义，
         *   服务端会返回 already_deleted，界面却像"又注销了一次"）。
         */
        val canSubmit: Boolean
            get() = !isSubmitting &&
                result == null &&
                isValidAccountDeleteConfirmText(confirmText)
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 确认短语变化。 */
    fun onConfirmTextChange(text: String) {
        _uiState.update {
            // 用户重新开始打字时把上一次的错误清掉 ——
            // 报错还挂在界面上会让人以为"刚才那下没生效"。
            it.copy(confirmText = text, error = null)
        }
    }

    /**
     * 提交注销。
     *
     * 顺序是**硬要求**：
     * 1. 先调服务端注销；
     * 2. 成功后立刻清本机（token + 本机数据）；
     * 3. 最后把结果摆到界面上。
     *
     * 第 2 步之所以不能拖到"用户点完成"：这台设备上不能还留着他的数据，
     * 哪怕用户还没看完结果文案。界面上的"结果"只是一句告知，
     * 本机数据必须**当场**清干净。
     */
    fun submit() {
        val text = _uiState.value.confirmText
        if (!isValidAccountDeleteConfirmText(text)) {
            _uiState.update {
                it.copy(error = "请输入「$ACCOUNT_DELETE_CONFIRM_TEXT」以确认注销")
            }
            return
        }
        if (_uiState.value.isSubmitting || _uiState.value.result != null) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            // 分两段 try：服务端注销和本机清理的失败**不是一回事**。
            //
            // 服务端注销失败 → 账号还在，重试是有意义的，报错是对的。
            // 本机清理失败 → 账号**已经**没了（不可逆），这时候报"注销失败"是
            // 骗人的：用户会以为账号还在，再点一次也还是这句。
            // 所以本机清理失败只作为附带提示，结果照样摆出来。
            val result = try {
                repository.deleteAccount(text)
            } catch (e: CancellationException) {
                // 取消照常往上抛：吞掉它等于破坏协程契约，
                // "点了注销没反应"的老毛病就是这么来的。
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        error = friendlyError(e, fallback = "注销失败，请稍后再试"),
                    )
                }
                return@launch
            }

            var cleanupError: String? = null
            try {
                repository.logoutLocalOnly()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cleanupError = friendlyError(e, fallback = "本机数据清理失败，请手动清除应用缓存")
            }

            _uiState.update {
                it.copy(isSubmitting = false, result = result, error = cleanupError)
            }
        }
    }
}
