package com.tingxia.audio.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.BuildConfig
import com.tingxia.audio.data.model.AppUpdateInfo
import com.tingxia.audio.data.model.UpdateCheck
import com.tingxia.audio.data.model.UpdateStatus
import com.tingxia.audio.data.model.evaluateUpdate
import com.tingxia.audio.data.model.hasDownloadUrl
import com.tingxia.audio.data.model.versionLabel
import com.tingxia.audio.data.remote.ApiException
import com.tingxia.audio.data.remote.parseEnvelope
import com.tingxia.audio.data.repository.AppUpdateRepository
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
 * 应用内更新的状态机。
 *
 * 状态：空闲 → 检查中 → （已是最新 / 有新版本 / 检查失败）→ 下载中 → 拉起安装器。
 *
 * 为什么把"检查"做成**用户主动触发**而不是启动时自动跑：
 * 自动跑会在每次冷启动时多一次网络请求，而绝大多数用户根本不会点更新。
 * 设置页的「版本」行是用户表达"我想知道有没有新版本"的地方。
 */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val repository: AppUpdateRepository,
    private val installer: ApkInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    /**
     * 检查更新。
     *
     * 并发保护：连点两次「版本」行不该发出两次请求。
     */
    fun check() {
        if (_state.value.isChecking) return
        _state.update { it.copy(isChecking = true, error = null, message = null, check = null) }
        viewModelScope.launch {
            try {
                val info = repository.latestVersion()
                val check = evaluateUpdate(info, BuildConfig.VERSION_CODE)
                _state.update {
                    it.copy(
                        isChecking = false,
                        check = check,
                        message = check.summaryText(),
                        error = null,
                        showUpdateDialog = check.status == UpdateStatus.UPDATE_AVAILABLE,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(isChecking = false, check = null, message = null, error = updateErrorText(e))
                }
            }
        }
    }

    /**
     * 下载并安装新版本。
     *
     * 前提：状态里已经有一份可用的更新信息。下载地址为空时**直接说清楚**，
     * 而不是闷头失败 —— "暂未提供下载地址"和"下载失败"对用户是完全不同的两件事。
     */
    fun downloadAndInstall() {
        val info = _state.value.check?.info ?: return
        if (_state.value.isDownloading) return
        if (!info.hasDownloadUrl()) {
            _state.update { it.copy(error = "暂未提供下载地址") }
            return
        }
        _state.update { it.copy(isDownloading = true, downloadProgress = 0, error = null, message = null) }
        viewModelScope.launch {
            val result = installer.downloadAndInstall(
                url = info.downloadUrl,
                fileName = apkFileName(info),
            ) { downloaded, total ->
                if (total > 0) {
                    _state.update { it.copy(downloadProgress = ((downloaded * 100) / total).toInt()) }
                }
            }
            _state.update {
                it.copy(
                    isDownloading = false,
                    downloadProgress = 0,
                    message = result.userMessage(),
                    error = if (result is ApkInstallResult.Failed) result.reason else null,
                )
            }
        }
    }

    /** 关闭更新弹窗。 */
    fun dismissDialog() {
        _state.update { it.copy(showUpdateDialog = false) }
    }

    /** 关闭一次性提示（检查结果 / 错误）。 */
    fun dismissMessage() {
        _state.update { it.copy(message = null, error = null) }
    }
}

/** APK 文件名：带上版本号，方便在下载目录里分辨。 */
private fun apkFileName(info: AppUpdateInfo): String =
    "tingxia-update-${info.latestVersionCode}.apk"

/**
 * 检查失败的用户可见文案。
 *
 * ⚠️ 404 必须单独处理：网关的"接口不存在"用的是 code 40400，
 * 而 `isAudioNotReady` 判的也是 404 + 40400 → 通用文案会变成
 * "音频还在蒸馏中，请稍候"，把一个部署问题说成音频问题，纯误导。
 */
internal fun updateErrorText(e: Throwable): String {
    val apiEx: ApiException? = when (e) {
        is ApiException -> e
        else -> (e as? retrofit2.HttpException)?.let { runCatching { parseEnvelope(it) }.getOrNull() }
    }
    if (apiEx?.httpCode == 404) {
        return "检查失败：服务端未提供版本接口（404）"
    }
    return "检查失败：${friendlyError(e, "请稍后再试")}"
}

/** 安装结果 → 用户能看懂的一句话。 */
internal fun ApkInstallResult.userMessage(): String = when (this) {
    is ApkInstallResult.InstallerLaunched -> "已打开系统安装器，确认后即可完成更新"
    is ApkInstallResult.UnknownSourceSettingsOpened ->
        "请在系统设置里允许本应用安装未知来源应用，然后再次点击「立即更新」"
    is ApkInstallResult.BrowserFallback -> "无法直接安装（$reason），已在浏览器打开下载地址"
    is ApkInstallResult.Failed -> reason
}

/**
 * 检查结论 → 一句话结果（设置页「版本」行的副标题）。
 */
internal fun UpdateCheck.summaryText(): String = when (status) {
    UpdateStatus.UP_TO_DATE -> "已是最新版本"
    UpdateStatus.UPDATE_AVAILABLE -> {
        val label = info?.versionLabel().orEmpty()
        if (isUnsupported) {
            "当前版本已不再支持，请升级到 $label"
        } else {
            "有新版本 $label"
        }
    }
}

/**
 * 设置页的更新状态。
 */
data class AppUpdateUiState(
    val isChecking: Boolean = false,
    val check: UpdateCheck? = null,
    /** 检查结果一句话（已是最新 / 有新版本 vX） */
    val message: String? = null,
    /** 失败或下载相关的一句话 */
    val error: String? = null,
    val isDownloading: Boolean = false,
    /** 下载进度 0..100 */
    val downloadProgress: Int = 0,
    val showUpdateDialog: Boolean = false,
)
