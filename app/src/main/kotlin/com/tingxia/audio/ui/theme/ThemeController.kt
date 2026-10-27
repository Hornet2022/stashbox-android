package com.tingxia.audio.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主题模式（Material 3 指南 R1.9）。
 *
 * ## 为什么需要这个
 *
 * 之前 App 只有「跟随系统」这一种（`isSystemInDarkTheme()`）。但深色模式
 * 不是所有人的选择 —— 不少人在系统是深色时仍想看浅色（户外强光下尤其），
 * 反之亦然。系统只给一个全局开关，App 内没有覆盖入口 ⇒ 用户只能去改系统设置，
 * 代价是**整个手机**都跟着变。
 *
 * ⇒ 补一个 App 内的「跟随系统 / 浅色 / 深色」三选一。
 *
 * ## 为什么是「跟着设置实时切换」而不是「重启 App 生效」
 *
 * 切主题要重建整棵组合树，最朴素的做法是 `activity.recreate()` ——
 * 但那会**丢掉当前页面与播放状态**（导航栈回到首页，正在播的音频断掉）。
 * 这里用 StateFlow 驱动，主题一变就重新组合，页面与播放都不受影响。
 */
enum class ThemeMode {
    /** 跟随系统（`isSystemInDarkTheme()`）。 */
    SYSTEM,

    /** 始终浅色。 */
    LIGHT,

    /** 始终深色。 */
    DARK;

    /** 设置页展示用的中文名。 */
    val displayName: String
        get() = when (this) {
            SYSTEM -> "跟随系统"
            LIGHT -> "浅色"
            DARK -> "深色"
        }
}

/**
 * 主题模式控制器：持有当前模式并在改动时通知 UI。
 *
 * 持久化用 SharedPreferences（与 App 里其它轻量偏好一致，如 `user_prefs`），
 * 不用 DataStore —— 这个值只在切换瞬间读一次，没有流式读取需求。
 */
@Singleton
class ThemeController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(readStoredMode())
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    /**
     * 切换模式。
     *
     * 先落盘再改内存：万一进程在落盘前被杀，下次启动读到的仍是旧值 ——
     * 宁可是"这次没生效"，也不要"存了个半截的值"。
     */
    fun setMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    private fun readStoredMode(): ThemeMode {
        val raw = prefs.getString(KEY_MODE, null) ?: return ThemeMode.SYSTEM
        // 存了不认识的值（版本回滚 / 手改 prefs）时回落到 SYSTEM，
        // 不能崩 —— 主题读不出来不该让 App 起不来。
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.SYSTEM)
    }

    companion object {
        const val PREFS_NAME = "user_prefs"
        const val KEY_MODE = "theme_mode"
    }
}

/**
 * 从 Application 作用域取 [ThemeController] 的入口点。
 *
 * 照着 `PlayerControllerEntryPoint` 的既有写法（见 `audio/PlayerController.kt`）——
 * 根主题与设置页都要拿到**同一个**实例（@Singleton），而主题在 NavHost 之外，
 * 拿不到 hiltViewModel 的作用域。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThemeControllerEntryPoint {
    fun themeController(): ThemeController
}

/** 在任意组合里拿到全局唯一的 [ThemeController]。 */
@Composable
fun rememberThemeController(): ThemeController {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, ThemeControllerEntryPoint::class.java)
            .themeController()
    }
}

/**
 * 把「主题模式」解析成「实际用哪套配色」。
 *
 * 单一真相源：根主题和任何需要判断深浅的组件都走这里，
 * 避免出现"根主题按手动设置走、某个组件还在问系统"的分裂。
 */
fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * 当前**实际**是否处于深色。
 *
 * ⚠️ 组件里**不要**再直接调 `isSystemInDarkTheme()` ——
 * 有了手动主题开关后，"系统是深色" ≠ "App 是深色"。
 * 直接问系统会让组件与根主题脱钩（2026-10-27 在 QuotaBanner 上就踩到了：
 * 用户手动选浅色，系统是深色 → 警告条仍取深色的一套，浮在浅色页面上，
 * 且文字对比度约 1.1:1，等于看不见）。
 */
@Composable
fun isDarkTheme(): Boolean {
    val controller = rememberThemeController()
    val mode by controller.mode.collectAsState()
    return resolveDarkTheme(mode, isSystemInDarkTheme())
}
