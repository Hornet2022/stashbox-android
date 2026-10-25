package com.tingxia.audio.util

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * 部分定制 ROM 对所有设备返回同一个常量（`9774d56d682e549c`）。
 *
 * 这个值**不能**当设备指纹用 —— 那样所有设备的埋点会算成同一台。
 * 命中它就当「取不到可靠指纹」。
 */
internal const val KNOWN_BAD_ANDROID_ID = "9774d56d682e549c"

/**
 * 设备指纹后缀长度。
 *
 * 与 dev 登录码的后缀长度一致（[com.tingxia.audio.ui.screens.DEV_LOGIN_CODE_RANDOM_LENGTH]
 * 直接引用本常量）—— dev code 的后缀长度**必须与来源无关**：
 * 用设备指纹时是 12 位，退回随机时也得是 12 位，否则同一个账号会有两种长度的 code。
 */
internal const val DEVICE_ID_SUFFIX_LENGTH = 12

/** 设备指纹后缀：`ANDROID_ID` 的 SHA-256 前 [DEVICE_ID_SUFFIX_LENGTH] 位。 */
internal fun androidIdSuffix(context: Context): String? {
    val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    if (id.isNullOrBlank() || id == KNOWN_BAD_ANDROID_ID) return null
    return sha256Hex(id).take(DEVICE_ID_SUFFIX_LENGTH)
}

/** SHA-256 的小写十六进制。不引新依赖：MessageDigest 是 JDK 自带的。 */
private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * 埋点用的**稳定** device_id。
 *
 * ## 为什么埋点也要 device_id
 *
 * 后端 `POST /api/v1/events/collect` 的 device_id **必填**，而且限流是按它算的
 * （每设备 100 events/min，见 `backend/common/event_collect.py`）。
 * ⇒ 它必须**稳定**：每次都随机的话，限流桶会无限增长，而且同一个用户的
 * 多次上报会散到不同桶里，按设备做的分析全乱。
 *
 * ## 取值优先级
 *
 * 1. `androidIdSuffix()`（ANDROID_ID 的哈希）—— 卸载重装/清数据都不变，
 *    同一台手机始终是同一台。
 * 2. 取不到可靠指纹时（上面那个坏值）退回**持久化**的随机 UUID。
 *    持久化是必须的：每次启动都随机的话，限流就形同虚设。
 *
 * ## 前缀的用处
 *
 * `and-` / `rnd-` 前缀让后台一眼看出这个 id 的来源：
 * `and-` = 设备指纹（跨重装稳定），`rnd-` = 兜底随机（只在这台安装内稳定）。
 * 没有前缀的话，看到一串随机 hex 无法判断它是不是「所有设备都一样」的那种坏值。
 */
internal fun analyticsDeviceId(context: Context): String {
    androidIdSuffix(context)?.let { return "and-$it" }
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
    val generated = "rnd-" + UUID.randomUUID().toString().replace("-", "").take(RANDOM_SUFFIX_LENGTH)
    // 用 commit() 而不是 apply()。
    //
    // apply() 的落盘是异步的 —— 写完立刻被系统杀进程的话这个 id 就丢了，
    // 下次启动换一个 ⇒ 限流桶换桶、同一用户的上报散开。
    // commit() 同步落盘，代价只是首启几毫秒。
    // （与 SharedPreferencesDevLoginCodeStore 用 commit() 是同一条理由。）
    @Suppress("ApplySharedPref")
    prefs.edit().putString(KEY_DEVICE_ID, generated).commit()
    return generated
}

/** 埋点 SharedPreferences 的名字（与 dev 登录码的 `dev_login` 分开）。 */
private const val PREFS_NAME = "analytics"

/** 兜底随机 device_id 的随机段长度。 */
private const val RANDOM_SUFFIX_LENGTH = 16

/** 兜底随机 device_id 的存储 key。 */
private const val KEY_DEVICE_ID = "device_id"
