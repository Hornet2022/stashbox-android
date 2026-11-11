package com.tingxia.audio.ui.capture

import com.tingxia.audio.data.model.ArticleByUrlResponse
import com.tingxia.audio.data.remote.NetworkFailure
import com.tingxia.audio.ui.friendlyError

/**
 * 剪藏失败后「这件事到底怎么样了」的判定（2026-11-11）。
 *
 * ## 为什么要有这个模块
 *
 * 剪藏是**同步长请求**（服务端当场抓取，几秒到几十秒才返回）。
 * 客户端在等响应时，任何客户端侧抖动（切网 / NAT 回收 / HTTP2 流重置）
 * 都会变成「**服务端成功、客户端报错**」。
 *
 * 实测（uid 23，2026-11-11 17:40~17:43）：4 次剪藏服务端**全部 200**、
 * 文章**全部建好并进入蒸馏**，客户端却弹了「网络不给力」——
 * 用户以为 4 次都失败了，重启 App 才发现文章都在。
 *
 * ⇒ 光靠异常类型分不出"真失败"和"其实成功了"，必须**回查**服务端。
 *
 * ## 为什么要抽成纯函数
 *
 * 这段判定牵涉 ViewModel / WorkManager / Context，直接测就只能靠真机或
 * 整条 Hilt 链 ⇒ 护栏没牙齿。抽成 suspend 纯函数后，
 * 构造数据即可把每条分支单独测出来。
 */
object CaptureFailure {

    /** 这次剪藏到底怎么样了。 */
    enum class Outcome {
        /** 服务端其实已经建好了（响应没送到用户手上）。 */
        SUCCEEDED,

        /** 确实失败了（服务端明确拒绝，或回查确认没有建）。 */
        FAILED,

        /** **不知道**（网络层失败且回查也失败）—— 不能替用户下结论。 */
        UNKNOWN,
    }

    /** 判定结果。 */
    data class Result(
        val outcome: Outcome,
        /** SUCCEEDED 时的文章 id。 */
        val articleId: String? = null,
        /** 给用户看的文案（FAILED / UNKNOWN 时有值）。 */
        val message: String? = null,
        /** 稳定失败分类（给埋点用，不受 R8 混淆影响）。 */
        val kind: String,
        /** 回查结果：pending / actually_succeeded / not_found / server_rejected。 */
        val recheck: String,
    )

    /** 回查：按 URL 问服务端"这个链接你收过了吗"。 */
    fun interface UrlLookup {
        suspend fun lookup(url: String): ArticleByUrlResponse?
    }

    /**
     * 回查结果待确认时的文案。
     *
     * 为什么不是「剪藏失败，请稍后再试」：
     * 网络层失败时我们**不知道**服务端到底有没有建好文章 ——
     * 直说"失败"可能让用户重试（其实已经成功了），
     * 直说"成功"又可能让用户以为可以听（其实没建）。
     * ⇒ 如实说"待确认，去列表看一眼"。
     */
    const val UNKNOWN_MESSAGE = "网络不稳定，提交结果待确认，请稍后在「剪藏」列表里查看"

    /**
     * 判定一次剪藏失败到底意味着什么。
     *
     * @param url 用户提交的那个链接
     * @param error 客户端捕获到的异常
     * @param lookup 回查（按 URL 查服务端）；返回 null 表示"查不动"
     */
    suspend fun resolve(
        url: String,
        error: Throwable,
        lookup: UrlLookup,
    ): Result {
        val kind = NetworkFailure.kindOf(error)

        // 服务端明确拒绝（4xx）⇒ 不用回查，那是真的不行。
        if (NetworkFailure.isServerRejected(error)) {
            return Result(
                outcome = Outcome.FAILED,
                message = friendlyError(error, fallback = "剪藏失败，请稍后再试"),
                kind = kind,
                recheck = "server_rejected",
            )
        }

        // 回查：服务端答"在不在"。
        val checked = runCatching { lookup.lookup(url) }.getOrNull()
        val existingId = checked?.takeIf { it.exists }?.articleId
        if (existingId != null) {
            return Result(
                outcome = Outcome.SUCCEEDED,
                articleId = existingId,
                kind = kind,
                recheck = "actually_succeeded",
            )
        }

        if (checked == null) {
            // 回查也失败 ⇒ **不知道**。不替用户下结论。
            return Result(
                outcome = Outcome.UNKNOWN,
                message = UNKNOWN_MESSAGE,
                kind = kind,
                recheck = "pending",
            )
        }

        // 回查成功且服务端说"没有"⇒ 确实没建。
        return Result(
            outcome = Outcome.FAILED,
            message = friendlyError(error, fallback = "剪藏失败，请稍后再试"),
            kind = kind,
            recheck = "not_found",
        )
    }
}
