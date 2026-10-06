package com.tingxia.audio.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.BuildConfig
import com.tingxia.audio.audio.PlayerControllerEntryPoint
import com.tingxia.audio.auth.AuthRepository
import com.tingxia.audio.ui.friendlyError
import com.tingxia.audio.ui.tts.PlaybackSpeedSheet
import com.tingxia.audio.ui.feedback.FeedbackBottomSheet
import com.tingxia.audio.ui.tts.TtsPreferenceViewModel
import com.tingxia.audio.ui.tts.VoicePickerSheet
import com.tingxia.audio.ui.tts.formatSpeedLabel
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页（CP5.6.0 / 接口文档 §4 G2）。
 *
 * - **数据/隐私**：注销删数 / 历史反馈 / 调试入口（debug 构建可见）
 * - **朗读**：音色 + 播放速度（CP-TTS-VOICE）
 * - **预加载/缓存**：跳到 OfflineDownloadScreen
 *
 * 2026-10-06：§4 的「个性化听感开关」已整体移除（UI + UiState 字段）。
 * 原来它是一个 `enabled = false` 的 Switch + 锁图标 + 「即将开放」弹窗，
 * 而后端 G2 端点是否就绪始终没有确认 —— 出货界面里留一个点不动的开关，
 * 只会让用户以为 App 坏了。要重做请从 `GET/PUT /api/v1/user/consent` 端点开始。
 *
 * 不在主仓：注销功能（等 G2 上线）；推送设置（接口文档未定义）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToOfflineDownload: () -> Unit = {},
    onNavigateToFeedbackHistory: () -> Unit = {},
    onLogout: () -> Unit = {},
    // 2026-10-02: 供「意见反馈」打开通用反馈 sheet（由 MainActivity 注入传入，
    // 与 ArticleListScreen 同一套取法）
    feedbackRepository: com.tingxia.audio.data.repository.FeedbackRepository? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // CP-TTS-VOICE: 音色 + 语速
    var showVoicePicker by remember { mutableStateOf(false) }
    // 2026-10-02: 「意见反馈」不再是死按钮，直接复用已存在的通用反馈 sheet
    var showFeedbackSheet by remember { mutableStateOf(false) }

    val ttsViewModel: TtsPreferenceViewModel = hiltViewModel()
    val ttsState by ttsViewModel.uiState.collectAsStateWithLifecycle()
    val playerController = remember {
        EntryPointAccessors
            .fromApplication(context.applicationContext, PlayerControllerEntryPoint::class.java)
            .playerController()
    }
    val speed by playerController.speed.collectAsStateWithLifecycle()
    var showSpeedSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showFeedbackSheet && feedbackRepository != null) {
        val appVersion = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "unknown"
        }
        FeedbackBottomSheet(
            articleId = null, // 通用反馈，不针对具体文章
            feedbackRepository = feedbackRepository,
            appVersion = appVersion,
            onDismiss = { showFeedbackSheet = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 用户区 ──
            SettingsSection("账号") {
                // 2026-10-02：原来这里是 `onClick = { /* TODO: 跳用户详情 */ }`，
                // 是个点下去毫无反应的死按钮 —— 而「用户详情」页根本不存在。
                // 与其编一个假目的地，不如如实展示：这一行是信息，不是入口。
                SettingsRow(
                    icon = Icons.Filled.Person,
                    title = "用户",
                    subtitle = "uid=${uiState.userId ?: "未登录"}",
                )
                SettingsRow(
                    icon = Icons.Filled.History,
                    title = "反馈历史",
                    subtitle = "查看我提交的反馈与评分",
                    onClick = onNavigateToFeedbackHistory,
                )
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    title = "退出登录",
                    subtitle = "清空本地 token，回到登录页",
                    destructive = true,
                    onClick = {
                        // 只交给 onLogout（由 MainActivity 调 AuthViewModel.logout()）。
                        // 这里原本还额外调了 viewModel.logout() —— 那是 settings 作用域的
                        // SettingsViewModel，它清了 token 却碰不到 AuthRoot 持有的
                        // AuthState，UI 仍停在已登录态；而且它跟着当前 NavBackStackEntry
                        // 一起被销毁，viewModelScope 会在网络请求返回前被取消。
                        onLogout()
                    },
                )
            }

            // ── CP-TTS-VOICE: 朗读音色 + 播放速度 ──
            SettingsSection("朗读") {
                SettingsRow(
                    icon = Icons.Filled.RecordVoiceOver,
                    title = "朗读音色",
                    // 显示**实际生效**的音色而不是「我选中的」：用户从没设过偏好时
                    // 生效的是全局默认音色，只显示自己那栏会出现「未选择音色但听着
                    // 明显是某个人念的」这种说不通的界面。
                    //
                    // ⚠️ 加载失败时**不能**回落到「跟随系统默认音色」（BUG#9）：
                    // 那时 preference 是 null，和「加载成功且确实跟随默认」长得
                    // 一模一样，后端挂了界面却一脸笃定，是典型的静默失败。
                    // 语音/语速两条都受 load() 一次成败影响，所以一起降级。
                    subtitle = ttsState.error
                        ?.let { "加载失败：$it，点此重试" }
                        ?: ttsState.effectiveVoiceName
                        ?: "跟随系统默认音色",
                    subtitleIsError = ttsState.error != null,
                    // 出错时这一行是「重试」而不是「打开选择器」—— 副标题既然
                    // 写了「点此重试」就必须点得动。加载成功后恢复正常语义。
                    onClick = {
                        if (ttsState.error != null) ttsViewModel.load() else showVoicePicker = true
                    },
                )
                SettingsRow(
                    icon = Icons.Filled.Speed,
                    title = "播放速度",
                    // 同上：拿不到偏好时 speed 会停在本地默认 1.0x，
                    // 但那不是「用户设的就是 1.0x」，不能当成已知的值显示。
                    // 这里不承诺重试 —— PlaybackSpeedSheet 自带本地档位兜底，
                    // 断网时照样能调，点开就是有用的。
                    subtitle = ttsState.error?.let { "加载失败：$it" } ?: speedSubtitle(speed),
                    subtitleIsError = ttsState.error != null,
                    onClick = { showSpeedSheet = true },
                )
            }

            // ── 缓存 ──
            SettingsSection("缓存与离线") {
                SettingsRow(
                    icon = Icons.Filled.CloudDownload,
                    title = "通勤预加载",
                    subtitle = "把稍后听列表预热到本机",
                    onClick = onNavigateToOfflineDownload,
                )
            }

            // ── 关于 ──
            SettingsSection("关于") {
                SettingsRow(
                    icon = Icons.Filled.Info,
                    title = "版本",
                    subtitle = "${uiState.versionName} (${uiState.versionCode})",
                    onClick = {
                        Toast.makeText(context, "构建 ${uiState.commitHash.take(7)}", Toast.LENGTH_SHORT).show()
                    },
                )
                // 2026-10-02：原来是死按钮。这里接上**已经存在**的
                // FeedbackBottomSheet（articleId 传 null = 不针对具体文章的
                // 通用反馈）—— 不必新造一个「反馈页」，那个 sheet 本来就能用。
                SettingsRow(
                    icon = Icons.Filled.HelpOutline,
                    title = "意见反馈",
                    subtitle = "通过反馈页提交 bug/建议",
                    // 仓库没注入时这一行不可点（而不是点了没反应）
                    onClick = if (feedbackRepository != null) {
                        { showFeedbackSheet = true }
                    } else {
                        null
                    },
                )
            }

            // ── 调试入口(仅 debug 构建可见) ──
            if (uiState.isDebug) {
                SettingsSection("调试(仅 debug)") {
                    SettingsRow(
                        icon = Icons.Filled.Science,
                        title = "BuildConfig.DEBUG_USER_ID",
                        subtitle = "当前：${BuildConfig.DEBUG_USER_ID}",
                        onClick = {
                            Toast.makeText(context, "改 BuildConfig 重新编译生效", Toast.LENGTH_LONG).show()
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // CP-TTS-VOICE: 音色选择 + 播放速度两个 sheet。
    // 设置页没有文章上下文 → currentArticleId 传 null，
    // 于是「用新音色重新生成这一篇」那行不显示（重生成入口在文章详情页）。
    if (showVoicePicker) {
        VoicePickerSheet(
            onDismiss = { showVoicePicker = false },
            currentArticleId = null,
            onRequestRegenerate = { /* 设置页无文章上下文，不会走到这里 */ },
        )
    }
    if (showSpeedSheet) {
        PlaybackSpeedSheet(
            currentSpeed = speed,
            availableSpeeds = ttsState.preference?.availableSpeeds.orEmpty(),
            onDismiss = { showSpeedSheet = false },
            onSpeedSelected = { value ->
                // 与全屏播放器同一套：先改播放器(立即生效)再同步云端
                playerController.setSpeed(value)
                scope.launch {
                    runCatching { ttsViewModel.persistSpeed(value) }
                        .onFailure { friendlyError(it, "语速已生效，但同步到云端失败") }
                }
            },
        )
    }
}

/** 播放速度的副标题文案（抽出来是为了和 BUG#9 的降级分支对称）。 */
private fun speedSubtitle(speed: Float): String =
    "${formatSpeedLabel(speed)} — 立即生效，不影响已生成的音频"

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    destructive: Boolean = false,
    /**
     * 副标题是不是「错误信息」而不是「一个值」。
     *
     * 存在的原因（BUG#9）：加载失败时若照常渲染一个笃定的值（比如
     * 「跟随系统默认音色」「1.0x」），界面就和「加载成功且恰好是这个值」
     * 完全无法区分 —— 后端挂了用户却看不出来。用 error 色区分。
     */
    subtitleIsError: Boolean = false,
    /**
     * null = 纯信息行，**不渲染可点击态**（2026-10-02）。
     *
     * 之前这里是必填，于是「没有对应页面」的行只能塞 `{}` 或注释掉的 TODO，
     * 两者都表现为「点下去毫无反应」的死按钮。信息就该是信息。
     *
     * 2026-10-06：原来的 `trailing` 插槽（行尾挂 Switch）随个性化开关一起删除 ——
     * 那一处是它唯一的调用方。行尾真要放控件时，按当时的需要再加回来。
     */
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (destructive) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (subtitleIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val uid = authRepository.getUserId()
            _uiState.update { it.copy(userId = uid) }
        }
    }

    // 登出入口改由 MainActivity 注入的 onLogout 处理（见上方"退出登录"行注释）：
    // 设置页不再自己清 token，避免清了 token 却切不动 AuthRoot 的登录态。
    // 登出逻辑唯一实现在 AuthViewModel.logout()。

    data class UiState(
        val userId: Long? = null,
        val versionName: String = BuildConfig.VERSION_NAME,
        val versionCode: Int = BuildConfig.VERSION_CODE,
        val commitHash: String = "ae6c594",
        val isDebug: Boolean = BuildConfig.DEBUG,
        // 2026-10-06：原 `personalizationEnabled`（§4 个性化开关）已连同设置页里那个
        // 永远灰置的 Switch 一起删除 —— 后端 G2 端点是否就绪一直没人确认，
        // 留一个点不动的开关 + 「即将开放」弹窗，等于在出货界面里挂一个假入口。
        // 真要重做这个功能时，从 GET/PUT /api/v1/user/consent 端点开始补。
    )
}