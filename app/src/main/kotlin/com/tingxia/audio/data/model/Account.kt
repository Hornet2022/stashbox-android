package com.tingxia.audio.data.model

/**
 * 账号合规相关的数据模型（2026-10-21）。
 *
 * 这一组是「隐私政策同意」与「注销账号」两条链路的领域对象：
 * - [PrivacyPolicy]：服务端下发的隐私政策正文（公开端点，登录前也能读）；
 * - [ConsentState]：某个用户的同意记录（同意的是哪一版、什么时候同意的）；
 * - [AccountDeleteResult]：注销的结果（软删了几篇文章、匿名化了哪些字段）。
 *
 * 放在 data/model 而不是 data/remote，是为了让 UI 层不直接依赖 Retrofit 的
 * 响应 DTO —— 远端字段名是 snake_case，领域字段是 camelCase，
 * 中间这层映射让「字段改名」这件事只发生在一个地方。
 */

/**
 * 注销确认短语。
 *
 * **必须与服务端 `backend/user-service/main.py` 的 `ACCOUNT_DELETE_CONFIRM_TEXT`
 * 逐字一致**，否则服务端直接 400（`confirm_text must be exactly '注销听匣'`）。
 *
 * 为什么要有"打字确认"：注销是**不可逆**的终局操作，一个误触就永久丢数据。
 * 这不是 UX 洁癖 —— 是让用户有机会意识到自己在做什么。
 */
const val ACCOUNT_DELETE_CONFIRM_TEXT = "注销听匣"

/**
 * 用户输入是否构成有效的注销确认。
 *
 * 判定口径与服务端一致：`trim()` 之后**逐字相等**。
 *
 * - 允许首尾空白：输入法在中文候选词后面常带一个空格，用户看着对了却被拒，
 *   是纯粹的无谓挫败；
 * - 不做大小写折叠：短语是中文，大小写折叠没有意义；真要归一化，
 *   归一化规则必须和服务端一致（服务端只 trim），客户端多折一次就会出现
 *   "客户端放行、服务端拒绝"的错位；
 * - 错短语一律不放行：这是整个确认环节存在的唯一理由。
 */
fun isValidAccountDeleteConfirmText(text: String): Boolean =
    text.trim() == ACCOUNT_DELETE_CONFIRM_TEXT

/**
 * 注销结果。
 *
 * @param deleted 服务端是否已把账号标记为注销（重复调用时仍为 true）
 * @param alreadyDeleted 这次调用之前账号**已经**被注销过（幂等重试的信号）
 * @param anonymizedFields 被匿名化的字段名列表（open_id / nickname / email …）
 * @param articlesSoftDeleted 被软删的文章数量
 */
data class AccountDeleteResult(
    val deleted: Boolean,
    val alreadyDeleted: Boolean,
    val anonymizedFields: List<String>,
    val articlesSoftDeleted: Int,
)

/**
 * 隐私政策正文。
 *
 * @param version 政策版本号（同意时按这个版本号记账；改版 = 要求所有用户重新同意）
 * @param updatedAt 最后更新日期
 * @param title 政策标题
 * @param content 正文，**Markdown 格式**（客户端负责渲染成可读文本）
 */
data class PrivacyPolicy(
    val version: String,
    val updatedAt: String,
    val title: String,
    val content: String,
)

/**
 * 用户的同意状态。
 *
 * @param privacyPolicyVersion 用户**已同意**的版本号（没同意过时服务端返空串）
 * @param privacyPolicyAccepted 是否已显式同意
 * @param privacyPolicyAcceptedAt 同意时间（ISO8601）
 */
data class ConsentState(
    val privacyPolicyVersion: String,
    val privacyPolicyAccepted: Boolean,
    val privacyPolicyAcceptedAt: String?,
    val personalizationEnabled: Boolean,
    val crossUserShareEnabled: Boolean,
) {
    /**
     * 用户是否已经同意了**当前这版**政策。
     *
     * 注意必须同时满足两个条件：
     * - `privacyPolicyAccepted`：合规上必须是**显式同意**，"没记录"不等于"同意了"；
     * - 版本号一致：政策改版后版本号会变，此时用户同意的是**旧版**，
     *   必须重新阅读新版才能算同意。
     */
    fun hasAccepted(currentPolicyVersion: String): Boolean =
        privacyPolicyAccepted && privacyPolicyVersion == currentPolicyVersion
}

/**
 * 政策版本不匹配（HTTP 409 / 业务码 40900）。
 *
 * ## 什么时候发生
 *
 * 用户在屏幕上看到的政策是 v1，但在他点「同意并继续」的那一刻，服务端已经
 * 换成了 v2。此时 `PUT /api/v1/users/me/consent` 会被拒（409）。
 *
 * ## 客户端必须怎么反应
 *
 * **重新拉政策、让用户再看一遍**，而不是拿着新版本号自动重试 ——
 * 自动重试等于"用户没看过 v2，却替用户同意了 v2"，那不是同意，是代签。
 *
 * 所以这个异常是一个**信号**（"你手上的版本过期了"），由 ViewModel 负责
 * 重新拉取并回到待确认状态。
 */
class PolicyVersionMismatchException(
    /** 客户端提交时带的版本号。 */
    val clientVersion: String,
    /** 服务端错误信息（原文，便于排查）。 */
    val serverMessage: String? = null,
) : RuntimeException(
    "隐私政策版本不匹配：客户端提交的是 $clientVersion，服务端已经更新（$serverMessage）",
)
