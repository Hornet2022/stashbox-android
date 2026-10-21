package com.tingxia.audio.ui.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.auth.AuthRepository
import com.tingxia.audio.data.model.ConsentState
import com.tingxia.audio.data.model.PolicyVersionMismatchException
import com.tingxia.audio.data.model.PrivacyPolicy
import com.tingxia.audio.data.remote.ApiException
import com.tingxia.audio.data.remote.isAuthExpired
import com.tingxia.audio.data.repository.ConsentLocalStore
import com.tingxia.audio.data.repository.ConsentRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

/**
 * 首次启动的隐私政策同意闸门。
 *
 * ## 状态机
 *
 * ```
 *   CHECKING --(已同意当前版本)--> AGREED --> 放行
 *       |
 *       +--(未同意 / 政策改版)--> NEEDS_CONSENT --[同意并继续]--> AGREED
 *       |                             ^
 *       |                             +--(409 政策已更新)--> 重新拉政策 --> NEEDS_CONSENT
 *       |
 *       +--(拉取失败)----------> FAILED --[重试]--> CHECKING
 * ```
 *
 * ## 为什么同意之前不放行
 *
 * 合规要求是**显式同意**：没有记录不等于同意了。所以 [ConsentState.hasAccepted]
 * 同时要求"已同意"和"同意的就是当前这一版"，缺一不可。
 *
 * ## 409 是怎么处理的（这里最容易写错）
 *
 * 用户在屏幕上看到的是 v1，点「同意」时服务端已经是 v2 -- 服务端回 409。
 *
 * **绝对不能**拿着 v2 自动重试一次：那等于"用户没看过 v2，却替用户同意了 v2"，
 * 不是同意，是代签。所以这里只做两件事：重新拉政策、把界面换成新版本，
 * 让用户**再看一遍再点一次**。
 */
@HiltViewModel
class PrivacyConsentViewModel @Inject constructor(
    private val repository: ConsentRepository,
    private val localStore: ConsentLocalStore,
    private val authRepository: AuthRepository,
) : ViewModel() {

    /** 闸门状态。 */
    enum class GateStatus {
        /** 正在查政策与同意状态（**这段时间不显示任何东西**，避免闪一下同意页）。 */
        CHECKING,

        /** 需要用户阅读并同意。 */
        NEEDS_CONSENT,

        /** 已经同意（或本来就同意过）-- 放行。 */
        AGREED,

        /** 拉取失败，可重试。 */
        FAILED,
    }

    data class UiState(
        val status: GateStatus = GateStatus.CHECKING,
        val policy: PrivacyPolicy? = null,
        val isSubmitting: Boolean = false,
        /** 错误文案（中文，可直接显示）。 */
        val error: String? = null,
        /** 提示文案（例如"隐私政策已更新，请重新阅读后同意"）。 */
        val notice: String? = null,
    ) {
        /** 「同意并继续」是否可点。 */
        val canAgree: Boolean
            get() = !isSubmitting && status == GateStatus.NEEDS_CONSENT && policy != null
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        checkConsent()
    }

    /**
     * 查一遍政策与同意状态。
     *
     * 判定顺序（**服务端是权威来源**）：
     * 1. 拉当前政策（公开端点）-- 拿到"用户该同意哪一版"；
     * 2. 拉同意记录 -- 已同意且版本一致 --> 放行；
     * 3. 拉同意记录失败（网络问题）-- 退回本地记录：
     *    本地记的版本与服务端当前版本一致才放行（用户确实做过这个动作），
     *    不一致照样弹同意页（政策改版了必须重新看）。
     */
    fun checkConsent() {
        viewModelScope.launch {
            _uiState.update { it.copy(status = GateStatus.CHECKING, error = null) }
            val userId = authRepository.getUserId()

            val policy = try {
                repository.getPrivacyPolicy()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        status = GateStatus.FAILED,
                        error = friendlyError(e, fallback = "无法加载隐私政策，请检查网络后重试"),
                    )
                }
                return@launch
            }

            val consent: ConsentState = try {
                repository.getConsentState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 同意记录拿不到（多半是网络断了）-- 先看本地有没有记录。
                if (!isAuthFailure(e) && localStore.getAcceptedVersion(userId) == policy.version) {
                    _uiState.update {
                        it.copy(status = GateStatus.AGREED, policy = policy, error = null)
                    }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        status = GateStatus.FAILED,
                        error = friendlyError(e, fallback = "无法确认同意状态，请稍后重试"),
                    )
                }
                return@launch
            }

            if (consent.hasAccepted(policy.version)) {
                _uiState.update {
                    it.copy(status = GateStatus.AGREED, policy = policy, error = null)
                }
            } else {
                _uiState.update {
                    it.copy(status = GateStatus.NEEDS_CONSENT, policy = policy, error = null)
                }
            }
        }
    }

    /**
     * 用户点了「同意并继续」。
     *
     * 提交的是**界面上正在显示的那一版**版本号 -- 不是"服务端当前版本"。
     * 用当前版本去提交等于绕过"用户看过没有"这个前提，409 也就永远不会发生，
     * 于是"政策改版要重新征询同意"这条规矩被客户端自己废掉了。
     */
    fun agree() {
        val policy = _uiState.value.policy ?: return
        if (_uiState.value.isSubmitting) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            try {
                repository.acceptPolicy(policy.version)
                localStore.setAcceptedVersion(authRepository.getUserId(), policy.version)
                _uiState.update {
                    it.copy(
                        status = GateStatus.AGREED,
                        isSubmitting = false,
                        notice = null,
                        error = null,
                    )
                }
            } catch (e: PolicyVersionMismatchException) {
                // 409：政策在我们展示之后又更新了。
                // 重新拉一份新正文，让用户再看一遍 -- **不自动重试**。
                reloadPolicyAfterMismatch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        error = friendlyError(e, fallback = "提交失败，请稍后再试"),
                    )
                }
            }
        }
    }

    /**
     * 409 之后重新拉政策，回到"待同意"。
     *
     * 拉不到新政策时**保留旧正文**（总比把界面清空强），但状态一样是
     * 待同意 -- 用户再点「同意」会再次撞 409（服务端版本确实没变），
     * 那时错误信息会告诉他发生了什么。
     */
    private suspend fun reloadPolicyAfterMismatch() {
        val fresh = try {
            repository.getPrivacyPolicy()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        _uiState.update {
            it.copy(
                status = GateStatus.NEEDS_CONSENT,
                isSubmitting = false,
                policy = fresh ?: it.policy,
                notice = "隐私政策已更新，请重新阅读后同意",
                error = null,
            )
        }
    }

    /**
     * 是不是"会话失效"类的失败。
     *
     * 会话失效时**不能用本地记录兜底**：token 都没了，放行也只是让用户
     * 进到一个什么都做不了的界面，还顺手掩盖了"需要重新登录"这件事。
     */
    private fun isAuthFailure(e: Throwable): Boolean = when (e) {
        is ApiException -> e.isAuthExpired
        is HttpException -> e.code() == 401
        else -> false
    }
}
