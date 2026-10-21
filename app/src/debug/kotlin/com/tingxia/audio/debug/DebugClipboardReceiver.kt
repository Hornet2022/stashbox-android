package com.tingxia.audio.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * **调试专用**的剪贴板写入广播（2026-10-21）。
 *
 * ## 为什么需要它
 *
 * 真机 E2E 要验证「注销听匣」这条流程，界面上要输入中文「注销听匣」四个字。
 * 但这台设备（小米 11 / Android 14）上 `adb shell input text` **打不了非 ASCII** ——
 * 命令行传中文会变成乱码或被直接吞掉，UI 自动化没法点中目标。
 * 于是改走系统剪贴板：先用这个广播把中文塞进剪贴板，再让输入框读剪贴板内容。
 *
 * ## 为什么放在 src/debug
 *
 * 这个广播**任何应用都能发**（exported），能把任意文本写进用户的剪贴板 ——
 * 那是实打实的安全隐患，绝不能进 release 包。
 * 放在 `src/debug` 源集里，AGP 只会把它打进 debug 变体，release 的合并清单里
 * 根本没有这个 receiver（release 的 applicationId 也没有 `.debug` 后缀）。
 *
 * 用法（debug 包已装、且允许 adb 广播）：
 *
 * ```
 * adb shell am broadcast -a com.tingxia.audio.debug.SET_CLIPBOARD \
 *     -n com.tingxia.audio.debug/com.tingxia.audio.debug.DebugClipboardReceiver \
 *     --es text 注销听匣
 * ```
 */
class DebugClipboardReceiver : android.content.BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT)
        if (text.isNullOrEmpty()) {
            Log.w(TAG, "缺少 extra $EXTRA_TEXT，忽略")
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Log.w(TAG, "拿不到 ClipboardManager，忽略")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, text))
        Log.i(TAG, "已写入剪贴板：$text")
    }

    companion object {
        /** 广播 action。debug 包的 applicationId 带 .debug 后缀，所以用这个名字对齐。 */
        const val ACTION = "com.tingxia.audio.debug.SET_CLIPBOARD"

        /** 要写入剪贴板的文本，放在 intent extra "text" 里。 */
        const val EXTRA_TEXT = "text"

        private const val TAG = "DebugClipboard"
        private const val LABEL = "tingxia-debug"
    }
}
