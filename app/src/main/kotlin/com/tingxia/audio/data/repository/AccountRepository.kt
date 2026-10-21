package com.tingxia.audio.data.repository

import com.tingxia.audio.data.local.UserDataCleaner
import com.tingxia.audio.auth.TokenManager
import com.tingxia.audio.data.model.AccountDeleteResult
import com.tingxia.audio.data.model.isValidAccountDeleteConfirmText
import com.tingxia.audio.data.remote.AccountApi
import com.tingxia.audio.data.remote.AccountDeleteRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 注销账号的仓库：调服务端注销 + 把本机清干净。
 *
 * ## 为什么注销后要"本地登出"，而且是本仓库自己做
 *
 * 注销之后服务端的账号已经没了（open_id 被换成随机串、token 被全部吊销），
 * 这时候再去调 `POST /api/v1/auth/logout` 是**没有意义的**：
 * - 服务端会 401（token 已吊销）→ [com.tingxia.audio.auth.AuthInterceptor]
 *   会尝试 refresh → 也失败 → 发出 [com.tingxia.audio.auth.SessionExpirySignal]
 *   → 界面上冒出一句"登录已失效，请重新登录"。
 *   用户刚主动注销，却看到一句"登录已失效" —— 纯噪声，还容易被当成 bug。
 * - 而且这个 401 会和"我们主动清 token"这两件事缠在一起，谁先谁后说不清。
 *
 * 所以这里**只做本地清理**：清 token + 清本机数据（[UserDataCleaner]）。
 * 这正是 [com.tingxia.audio.auth.AuthRepository.logout] 里 `clearLocalState()`
 * 干的那两件事，区别只在于不通知服务端。
 *
 * ## 顺序：先清 token，再清数据
 *
 * 和 AuthRepository 一致：后台补传 worker 靠 token 鉴权，token 一清，
 * 它们的请求就 401，不会把上一个用户的播放进度记到新账号头上。
 */
class AccountRepository @Inject constructor(
    private val api: AccountApi,
    private val tokenManager: TokenManager,
    private val userDataCleaner: UserDataCleaner,
    private val consentLocalStore: ConsentLocalStore,
) {

    /**
     * 注销当前账号。
     *
     * @param confirmText 用户在输入框里打的确认短语（必须逐字等于 `注销听匣`）
     * @param reason 注销原因（可选）
     * @throws IllegalArgumentException 确认短语不匹配 —— 正常路径下 UI 已经拦住了，
     *   这里再挡一道是为了不让"绕过 UI 的调用"把一个必失败的请求发出去。
     */
    suspend fun deleteAccount(
        confirmText: String,
        reason: String? = null,
    ): AccountDeleteResult {
        // 与服务端同口径：先 trim 再逐字比较。客户端多判一次和服务端少判一次
        // 都会导致"界面放行但服务端 400"，所以这里必须和服务端一致。
        val normalized = confirmText.trim()
        require(isValidAccountDeleteConfirmText(normalized)) {
            "注销确认短语不匹配，拒绝发起注销请求"
        }
        val resp = api.deleteAccount(
            AccountDeleteRequest(confirm_text = normalized, reason = reason),
        )
        return AccountDeleteResult(
            deleted = resp.deleted,
            alreadyDeleted = resp.already_deleted,
            anonymizedFields = resp.anonymized_fields,
            articlesSoftDeleted = resp.articles_soft_deleted,
        )
    }

    /**
     * 本地登出：清 token + 清本机数据（Room 下载台账/播放进度、Media3 音频缓存、后台任务）。
     *
     * 整段跑在 [NonCancellable] 里：清本地数据是 IO（DataStore 写盘 + Room 事务），
     * 撞上协程取消就等于"清了半个" —— 那比不清更糟
     * （token 没了但缓存还在，共享手机上下一个人还能捡到音频文件）。
     *
     * 取消信号照常往上抛（不吞协程契约），只是清的动作必须做完。
     */
    suspend fun logoutLocalOnly() {
        try {
            withContext(NonCancellable) {
                // userId 必须**先**读出来：tokenManager.clear() 会把 DataStore 里的
                // user_id 一起清掉，事后就读不到了（读到的会是 null，
                // 于是清的是"匿名用户"那个键，前一个人的同意记录还留在本机）。
                val userId = tokenManager.getUserId()
                tokenManager.clear()
                userDataCleaner.clearAll()
                // 本地的"已同意"记录也要清 —— 它是关于"这个人"的个人信息，
                // 而且共享设备上下一个人不该继承前一个人的同意记录。
                consentLocalStore.clear(userId)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}
