package com.tingxia.audio.ui.screens

import android.content.Context
import android.provider.Settings
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tingxia.audio.BuildConfig
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import java.security.MessageDigest
import java.util.UUID

/**
 * 登录页。
 *
 * ## 两条路径（2026-10-02 修正）
 *
 * 之前只有一个按钮，且无论 release / debug 都调 gateway 的 `/api/v1/auth/token`
 * ——那是个**无鉴权后门**（传任意 user_id 换真 token），现已默认关闭。
 *
 * - **主按钮「微信登录」** → `wechat-login`（user-service 真实实现）。
 *   微信 OAuth AppID 尚未申请，[onLogin] 目前收到的是一个稳定 code 串，
 *   服务端按 `wx_<code>` 查/建用户 —— 语义与真 OAuth 完全一致，接入时无需改结构。
 * - **debug 专属「联调登录」** → dev token 端点，用于在真机上冒充指定账号
 *   （比如带历史数据的 user 1）。服务端默认关闭，失败会给出可照做的提示。
 *
 * release 包不渲染任何联调入口，登录路径只有一条。
 *
 * ## dev code 是**每安装一个**的（2026-10-21）
 *
 * 原来默认 code 写死成 `dev_wechat_${BuildConfig.DEBUG_USER_ID}`。后端按
 * `open_id = "wx_" + code` 派生账号，于是**每个没手改这个输入框的测试员
 * 都登进同一个账号**：文章、配额、收藏、反馈、评分全部串给下一个人，
 * 而且配额是共享的 —— 第一个登录的人把大家这个月的额度用掉一半。
 * 那是数据串台，不是界面难看。
 *
 * 现在默认 code 由 [DevLoginCodeProvider] 生成：**每安装一个随机串**、
 * 装一次存一次（杀进程重开还是同一个账号）。详见该类的说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLogin: (code: String) -> Unit = {},
    onDevLogin: ((userId: String) -> Unit)? = null,
    errorMessage: String? = null,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // dev 登录 code：**每安装一个**（见文件下方 DevLoginCodeProvider 的说明）。
    //
    // 用 remember(context) 缓存：组合期读一次 SharedPreferences 就够，
    // 重组时不该反复读盘；context 真变了才重新取。
    val context = LocalContext.current
    val devCode = remember(context) {
        DevLoginCodeProvider(
            SharedPreferencesDevLoginCodeStore(context),
            deviceSuffix = { androidIdSuffix(context) },
        ).code()
    }
    var codeInput by remember(devCode) { mutableStateOf(devCode) }
    var userIdInput by remember { mutableStateOf(BuildConfig.DEBUG_USER_ID) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("登录听匣") }) },
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 2026-10-03：补 imePadding + verticalScroll
                // imePadding：这是全 App 第一处有输入框的屏，键盘一弹就把
                //   密码框和登录按钮顶出屏幕，而整列是 Arrangement.Center
                //   且不可滚动 —— 用户看得见「登录」但点不到。
                // verticalScroll：系统字号放大到 1.3x 时，logo + 两行副标题 +
                //   48dp 间距 + 两个输入框 + 按钮 + 协议行放不下，
                //   没有滚动条就只能放弃登录。
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("听匣", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("AI 蒸馏你的音频订阅", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(48.dp))

            OutlinedTextField(
                value = codeInput,
                onValueChange = { codeInput = it },
                label = { Text("登录 code（微信 OAuth 接入前的临时占位）") },
                // 说清楚这个框现在到底是什么：它**仍然只是 OAuth 的替身**，
                // 只不过替身不再共用一个账号了。
                supportingText = {
                    Text("临时占位：本机随机生成，接入真 OAuth 后由微信下发")
                },
                singleLine = true,
                enabled = !isLoading,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Button(
                onClick = { onLogin(codeInput.trim().ifBlank { "anonymous" }) },
                enabled = !isLoading,
            ) {
                Text(if (isLoading) "登录中..." else "微信登录")
            }

            if (onDevLogin != null) {
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = userIdInput,
                    onValueChange = { userIdInput = it },
                    label = { Text("联调 user_id（dev token 端点）") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedButton(
                    onClick = { userIdInput.trim().takeIf { it.isNotBlank() }?.let(onDevLogin) },
                    enabled = !isLoading && userIdInput.isNotBlank(),
                ) {
                    Text("联调登录（需服务端开 STASHBOX_ALLOW_DEV_TOKEN）")
                }
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "登录失败：$errorMessage",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "微信 OAuth AppID 待申请，当前按本机 code 派生独立测试账号",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * dev 登录 code 的**每设备唯一**生成与持久化。
 *
 * ## 后端怎么用这个 code
 *
 * 后端按 `open_id = "wx_" + code` 派生账号（user-service 的 `wechat_login`），
 * 所以 **code 相同 = 同一个账号**，code 不同 = 不同账号。
 * 后端那边**逻辑本来就是幂等的**（按 open_id 查、查不到才建）。
 *
 * ## 为什么不能是"每安装一个"（2026-10-22 改）
 *
 * 原来 code 是**每安装一个随机串**（2026-10-21 的改动，为了修更早的
 * "所有人共用一个写死常量账号"）。但随机串存在 SharedPreferences 里，
 * **清数据 / 重装就换一个 code → 换一个新 uid**：
 *
 *   · 内测用户重装一次 → 后台用户列表多一行
 *   · 开发/测试反复 pm clear → 每次多一行（实测一天 pm clear 4 次
 *     就多出 4 个用户，管理后台的用户列表被测试行为淹没）
 *
 * 用户的要求是对的：**同一台手机就该是一个内测 uid**。
 * 现在改成**按设备恒定** —— 同一台设备（同一 App 签名、同一手机用户）
 * 无论重装多少次，都是同一个 uid。
 *
 * ## 为什么是 ANDROID_ID 而不是 SharedPreferences 里的 UUID
 *
 * `Settings.Secure.ANDROID_ID` 在 Android 8+ 是**按 App 签名 + 按用户**
 * 隔离的：同一个 App、同一台设备，**卸载重装/清数据都不变**。
 * 而 SharedPreferences 里的 UUID（D9Receiver 用的 device_id）卸载就没了。
 *
 * ## 零迁移
 *
 * **已经存过 code 的用户继续用自己那个**（[DevLoginCodeStore.read] 命中就直接
 * 返回）→ 他们的 uid、文章、配额全都不受影响。
 * 只有「没存过 code」的（新装 / 清过数据）才走设备指纹。
 *
 * ## 为什么落盘而不是每次启动现生成
 *
 * 现生成的 code 每次冷启动都变 → 同一个测试员在地铁里退出重进 App 就变成
 * 另一个账号，之前下的音频、记的进度全对不上。所以 code 必须落盘。
 *
 * ## 为什么是 SharedPreferences 而不是 DataStore
 *
 * 只需要一个字符串，而且要在**组合期同步**拿到（输入框的默认值），
 * DataStore 的 Flow + 挂起读在这里只会更绕。工程里没有为它引新依赖。
 */
internal class DevLoginCodeProvider(
    private val store: DevLoginCodeStore,
    /**
     * 设备指纹后缀：同一台设备恒定（重装/清数据后不变）。
     * 返回 null 表示"取不到可靠设备指纹" → 退回随机。
     */
    private val deviceSuffix: () -> String? = { null },
    /** 设备指纹取不到时的兜底随机源。可注入：单测据此模拟不同设备。 */
    private val randomSuffix: () -> String = { newDevLoginCodeSuffix() },
) {

    /**
     * 本设备的 dev 登录 code：存过就复用，没存过就按设备指纹生成并落盘。
     *
     * 空串视作"没存过" —— 宁可换一个新账号，也不要拿空 code 去登录
     * （空 code 会被 `ifBlank { "anonymous" }` 兜成 "anonymous"，
     * 那还是一个共享账号）。
     */
    fun code(): String {
        store.read()?.takeIf { it.isNotBlank() }?.let { return it }
        val suffix = deviceSuffix()?.takeIf { it.isNotBlank() } ?: randomSuffix()
        val code = DEV_LOGIN_CODE_PREFIX + suffix
        store.write(code)
        return code
    }
}

/** dev code 的存储：抽成接口是为了让单测能注入两个互不相干的"安装"。 */
internal interface DevLoginCodeStore {
    fun read(): String?
    fun write(code: String)
}

/** [DevLoginCodeStore] 的 SharedPreferences 实现。 */
internal class SharedPreferencesDevLoginCodeStore(context: Context) : DevLoginCodeStore {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_CODE, null)

    /**
     * 用 `commit()` 而不是 `apply()`。
     *
     * 这是**首启一次性**的写盘，而 `apply()` 的落盘是异步的 —— 写完立刻被系统
     * 杀进程的话 code 就丢了，下次启动会换一个账号（用户以为还是刚才那个号）。
     * `commit()` 同步落盘，代价只是首启几毫秒，换来"写完就一定在"。
     *
     * （lint 的 ApplySharedPref 建议用 apply()，这里刻意不采纳 —— 理由同上。）
     */
    @Suppress("ApplySharedPref")
    override fun write(code: String) {
        prefs.edit().putString(KEY_CODE, code).commit()
    }

    companion object {
        const val PREFS_NAME = "dev_login"
        const val KEY_CODE = "wechat_code"
    }
}

/** dev code 前缀：一眼看出是占位串，不是真 OAuth code。 */
internal const val DEV_LOGIN_CODE_PREFIX = "dev_wechat_"

/** code 后缀长度（十六进制字符）。 */
internal const val DEV_LOGIN_CODE_RANDOM_LENGTH = 12

/**
 * ANDROID_ID 的**已知坏值**：部分定制 ROM 对所有设备返回同一个常量
 * （值就是这段字符串的十六进制形态的定长串）。
 *
 * 拿到它就等于"没拿到设备标识" —— 这时必须退回随机，
 * 否则**所有这类设备会共用同一个账号**（数据串台 + 配额共享，
 * 就是 2026-10-21 那个 bug 的翻版）。
 */
internal const val KNOWN_BAD_ANDROID_ID = "9774d56d682e549c"

/** 默认随机源：UUID 去掉分隔符取前 N 位。 */
internal fun newDevLoginCodeSuffix(): String =
    UUID.randomUUID().toString().replace("-", "").take(DEV_LOGIN_CODE_RANDOM_LENGTH)

/**
 * 设备指纹后缀：`ANDROID_ID` 的 SHA-256 前 12 位。
 *
 * ## 为什么是 ANDROID_ID
 * Android 8+ 的 `Settings.Secure.ANDROID_ID` 是**按 App 签名 + 按用户**隔离的：
 * 同一台设备、同一个签名、同一个手机用户，**卸载重装 / 清数据都不变**。
 * → 同一台手机始终是同一个 uid → 后台用户列表不会因为「重装一次」而膨胀。
 *
 * ## 为什么哈希而不是直接用
 * ANDROID_ID 是**设备标识**（属于个人信息）。内测期也不该把它明文上送自己的
 * 服务端 —— 哈希后不可逆，12 位十六进制对内测规模足够区分。
 *
 * ## 为什么不用 SharedPreferences 里的 UUID
 * 那东西**卸载就没了**（D9Receiver 的 device_id 就是它）→ 重装一次照样换号。
 *
 * @return null 表示取不到可靠设备指纹（调用方应退回随机）。
 */
internal fun androidIdSuffix(context: Context): String? {
    val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    if (id.isNullOrBlank() || id == KNOWN_BAD_ANDROID_ID) return null
    return sha256Hex(id).take(DEV_LOGIN_CODE_RANDOM_LENGTH)
}

/** SHA-256 的小写十六进制。不引新依赖：MessageDigest 是 JDK 自带的。 */
private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
