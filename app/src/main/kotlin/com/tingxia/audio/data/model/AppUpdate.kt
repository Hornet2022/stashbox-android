package com.tingxia.audio.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/app/version` 的响应体（**公开端点**，不需要 Authorization）。
 *
 * 契约来自后端 `backend/user-service/main.py::AppVersionResponse`：
 * ```json
 * {
 *   "latest_version_code": 12,
 *   "latest_version_name": "0.2.0",
 *   "min_supported_version_code": 5,
 *   "download_url": "https://.../app.apk",
 *   "release_notes": "……",
 *   "published_at": "2026-10-20T10:00:00Z",
 *   "mandatory": false
 * }
 * ```
 *
 * 字段全部带默认值：后端用环境变量驱动发布信息（`STASHBOX_APP_*`），
 * 任何一个没配都会少一个字段 —— 少字段不该让整个"检查更新"变成解析失败。
 */
@Serializable
data class AppVersionResponse(
    @SerialName("latest_version_code") val latestVersionCode: Int = 0,
    @SerialName("latest_version_name") val latestVersionName: String = "",
    @SerialName("min_supported_version_code") val minSupportedVersionCode: Int = 0,
    @SerialName("download_url") val downloadUrl: String = "",
    @SerialName("release_notes") val releaseNotes: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("mandatory") val mandatory: Boolean = false,
)

/**
 * 一次"检查更新"的领域结论（跟传输层的 snake_case 解耦）。
 */
data class AppUpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val minSupportedVersionCode: Int,
    val downloadUrl: String,
    val releaseNotes: String,
    val publishedAt: String?,
    /** 服务端自己算的"必须更新"标记 */
    val mandatory: Boolean,
)

/** 服务端响应 → 领域模型。 */
fun AppVersionResponse.toInfo(): AppUpdateInfo = AppUpdateInfo(
    latestVersionCode = latestVersionCode,
    latestVersionName = latestVersionName,
    minSupportedVersionCode = minSupportedVersionCode,
    downloadUrl = downloadUrl,
    releaseNotes = releaseNotes,
    publishedAt = publishedAt,
    mandatory = mandatory,
)

/** 检查结果的三种状态（不含"还没查"）。 */
enum class UpdateStatus {
    /** 已是最新（服务端没有比本机更新的版本） */
    UP_TO_DATE,

    /** 有新版本可装 */
    UPDATE_AVAILABLE,
}

/**
 * 检查结论 + 给用户看的判断依据。
 *
 * 为什么要单独把 [isUnsupported] / [isMandatory] 拎出来：
 * - [isUnsupported]：本机 versionCode 已经低于服务端允许运行的最低版本，
 *   这不是"有新版本"这种可选提示，而是"这个包已经不该继续用了"。
 * - [isMandatory]：必须升级（用户可以拒绝，但界面要说明后果）。
 */
data class UpdateCheck(
    val status: UpdateStatus,
    val info: AppUpdateInfo?,
    val installedVersionCode: Int,
    val isUnsupported: Boolean,
    val isMandatory: Boolean,
)

/**
 * 版本比较的唯一口径。
 *
 * ## 为什么不能只看服务端的 `mandatory`
 *
 * 后端 `mandatory = latest_code > 0 and min_code > 0 and min_code >= latest_code`
 * （`backend/user-service/main.py::get_app_version`）——它表达的是
 * "**当前发布版就是最低支持版**"这件**全局事实**，跟"本机装的是哪一版"无关。
 * 也就是说它**不能**回答"我这台机器还能不能继续用"。
 *
 * 真正回答这个问题的是本机版本号：`installedVersionCode < minSupportedVersionCode`
 * → 本机已经低于服务端允许的最低版本 → 这次更新是**强制**的。
 * 所以这里把两个信号**或**起来：
 * - 本机低于最低支持版本 → 强制（这是本机相关的事实，客户端自己算）；
 * - 服务端标了 mandatory 且确实有新版 → 也算强制（服务端说了算）。
 *
 * ## 为什么"本机比服务端还新"也算最新
 *
 * 内测包经常 versionCode 比线上发布版还大（开发机装的是未发布的构建）。
 * 那种情况下 `latest > installed` 不成立 → 判"已是最新"，
 * 否则会给开发者报一个根本不存在的"新版本"。
 */
fun evaluateUpdate(info: AppUpdateInfo, installedVersionCode: Int): UpdateCheck {
    val hasUpdate = info.latestVersionCode > installedVersionCode
    val isUnsupported = info.minSupportedVersionCode > 0 &&
        installedVersionCode < info.minSupportedVersionCode
    return UpdateCheck(
        status = if (hasUpdate) UpdateStatus.UPDATE_AVAILABLE else UpdateStatus.UP_TO_DATE,
        info = info,
        installedVersionCode = installedVersionCode,
        isUnsupported = isUnsupported,
        isMandatory = hasUpdate && (isUnsupported || info.mandatory),
    )
}

/**
 * 版本号展示用的标签。
 *
 * 后端用环境变量配 `STASHBOX_APP_LATEST_VERSION_NAME`，没配就是空串 ——
 * 空串不能直接显示给用户（界面会出现"有新版本 "这种半截话），
 * 所以退化成一个至少有信息的标签。
 */
fun AppUpdateInfo.versionLabel(): String =
    latestVersionName.ifBlank { if (latestVersionCode > 0) "版本 $latestVersionCode" else "新版本" }

/** 是不是"服务端根本没给下载地址"。 */
fun AppUpdateInfo.hasDownloadUrl(): Boolean = downloadUrl.isNotBlank()
