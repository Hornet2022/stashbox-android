package com.tingxia.audio.data.repository

import android.content.Context
import com.tingxia.audio.data.model.ConsentState
import com.tingxia.audio.data.model.PolicyVersionMismatchException
import com.tingxia.audio.data.model.PrivacyPolicy
import com.tingxia.audio.data.remote.ConsentApi
import com.tingxia.audio.data.remote.ConsentUpdateRequest
import com.tingxia.audio.data.remote.parseEnvelope
import dagger.hilt.android.qualifiers.ApplicationContext
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 隐私政策 + 同意记录的仓库。
 *
 * ## 409（政策版本不匹配）是这一层最重要的职责
 *
 * 用户在屏幕上看到的政策是 v1，点「同意」的那一刻服务端已经换成 v2 ——
 * 服务端会 409（`code 40900`）。客户端**不能**拿着新版本号自动重试：
 * 那等于"用户没看过 v2，却替用户同意了 v2"，不是同意，是代签。
 *
 * 所以这里把 409 转成 [PolicyVersionMismatchException] 抛出去，
 * 由 ViewModel 负责重新拉政策、让用户再看一遍。
 */
class ConsentRepository @Inject constructor(
    private val api: ConsentApi,
) {

    /** 当前隐私政策正文（公开端点，未登录也能读）。 */
    suspend fun getPrivacyPolicy(): PrivacyPolicy {
        val resp = api.privacyPolicy()
        return PrivacyPolicy(
            version = resp.version,
            updatedAt = resp.updated_at,
            title = resp.title,
            content = resp.content,
        )
    }

    /** 我当前的同意状态。 */
    suspend fun getConsentState(): ConsentState {
        val resp = api.consentState()
        return ConsentState(
            privacyPolicyVersion = resp.privacy_policy_version,
            privacyPolicyAccepted = resp.privacy_policy_accepted,
            privacyPolicyAcceptedAt = resp.privacy_policy_accepted_at,
            personalizationEnabled = resp.personalization_enabled,
            crossUserShareEnabled = resp.cross_user_share_enabled,
        )
    }

    /**
     * 记录同意（版本号 = 用户实际看过的那一版）。
     *
     * @param version 用户在屏幕上看到并同意的版本号
     * @throws PolicyVersionMismatchException 服务端当前版本已经不是 [version]（HTTP 409）
     */
    suspend fun acceptPolicy(
        version: String,
        personalizationEnabled: Boolean? = null,
        crossUserShareEnabled: Boolean? = null,
    ): ConsentState {
        val resp = try {
            api.updateConsent(
                ConsentUpdateRequest(
                    privacy_policy_version = version,
                    accepted = true,
                    personalization_enabled = personalizationEnabled,
                    cross_user_share_enabled = crossUserShareEnabled,
                ),
            )
        } catch (e: HttpException) {
            // Retrofit 的 suspend 函数在非 2xx 时抛 HttpException；
            // 统一解析成业务信封，客户端才能按业务码分流。
            val apiEx = parseEnvelope(e)
            if (apiEx.httpCode == HTTP_CONFLICT || apiEx.bizCode == BIZ_CODE_POLICY_MISMATCH) {
                throw PolicyVersionMismatchException(
                    clientVersion = version,
                    serverMessage = apiEx.message,
                )
            }
            throw apiEx
        }
        return ConsentState(
            privacyPolicyVersion = resp.privacy_policy_version,
            privacyPolicyAccepted = resp.privacy_policy_accepted,
            privacyPolicyAcceptedAt = resp.privacy_policy_accepted_at,
            personalizationEnabled = resp.personalization_enabled,
            crossUserShareEnabled = resp.cross_user_share_enabled,
        )
    }

    private companion object {
        /** HTTP 409 Conflict。 */
        const val HTTP_CONFLICT = 409

        /** 后端 `common/exceptions.py::_STATUS_CODE_MAP` 里 409 对应的业务码。 */
        const val BIZ_CODE_POLICY_MISMATCH = 40900
    }
}

/**
 * 本地"已同意"记录。
 *
 * ## 为什么服务端之外还要存一份
 *
 * 服务端的 `GET /api/v1/users/me/consent` 是**权威来源**（同意这件事必须落在服务端，
 * 否则换台设备就查不到"这人同意过没有"）。本地这一份的作用是：
 *
 * 1. **不闪屏**：本地有记录 = 用户以前同意过 → 启动时先按"已同意"处理，
 *    网络请求回来之前不弹同意页（弹一下再消失是最糟的体验）。
 * 2. **服务端够不着时的兜底**：同意记录写服务端失败（弱网）时，本地这份
 *    仍然是"用户确实做过这个动作"的证据；只要它记的版本号与服务端当前版本一致，
 *    就可以放行。反过来（版本对不上）照样弹同意页 —— 政策改版了就得重新看。
 *
 * 存的是**版本号**而不是布尔值：布尔值表达不了"同意的是哪一版"，
 * 而"同意的是哪一版"恰恰是政策改版后要重新征询的唯一依据。
 */
@Singleton
class ConsentLocalStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 本地记录的"已同意版本号"，没有记录时返回空串。
     *
     * @param userId 当前登录用户；不同用户分开记 —— 共享手机上前一个人的
     *   同意记录不能让下一个人直接跳过同意页。
     */
    fun getAcceptedVersion(userId: Long?): String =
        prefs.getString(keyFor(userId), "").orEmpty()

    /**
     * 记录"用户已同意这一版"。
     *
     * @param userId 当前登录用户
     */
    fun setAcceptedVersion(userId: Long?, version: String) {
        prefs.edit().putString(keyFor(userId), version).apply()
    }

    /**
     * 清掉本地记录。
     *
     * 注销账号时必须清 —— 那是关于"这个人"的个人信息；
     * 共享设备上下一个人不该继承前一个人的同意记录。
     *
     * @param userId 当前登录用户
     */
    fun clear(userId: Long?) {
        prefs.edit().remove(keyFor(userId)).apply()
    }

    private fun keyFor(userId: Long?): String =
        "$KEY_ACCEPTED_VERSION${userId?.toString() ?: "_anonymous"}"

    private companion object {
        /**
         * 复用 MainActivity 里 `has_onboarded` 用的那个 SharedPreferences 文件
         * （"user_prefs"）—— 用户的本地开关就住在一个地方。
         */
        const val PREFS_NAME = "user_prefs"
        const val KEY_ACCEPTED_VERSION = "privacy_policy_accepted_version_"
    }
}
