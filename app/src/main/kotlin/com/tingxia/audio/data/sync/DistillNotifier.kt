package com.tingxia.audio.data.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.tingxia.audio.MainActivity
import com.tingxia.audio.R

/**
 * 蒸馏完成 / 失败的本地通知。
 *
 * 为什么是**本地通知**而不是推送：全仓没有任何推送通道代码（APNs/FCM/个推/极光
 * 零命中），`push_notifications` 表 0 行 —— 也就是说「文章好了」这件事**没有任何一条
 * 通路能到达用户**。这是产品主动作上的断点：用户剪藏完只能自己想起来再打开 app。
 *
 * 接真推送要先有厂商通道和设备 token，属于要凭据的事；本地通知不依赖任何外部服务，
 * 配合 [DistillReadyWorker]（WorkManager，进程被杀也能续）先把闭环闭上。
 */
object DistillNotifier {

    const val CHANNEL_ID = "distill_status"
    private const val CHANNEL_NAME = "蒸馏进度"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "文章蒸馏完成或失败时提醒" }
        )
    }

    /**
     * 推一条通知。Android 13+ 需要 POST_NOTIFICATIONS 运行时权限，没授权时
     * notify() 是静默失败 —— 所以先查再发，不要指望它抛异常。
     */
    fun notifyReady(context: Context, articleId: String, title: String?, durationSec: Int?) {
        val mins = durationSec?.takeIf { it > 0 }?.let { (it + 59) / 60 }
        val body = buildString {
            append(title?.takeIf { it.isNotBlank() } ?: "你剪藏的文章")
            if (mins != null) append(" · 约 $mins 分钟")
        }
        post(
            context = context,
            articleId = articleId,
            title = "文章已就绪，可以收听了",
            text = body,
        )
    }

    fun notifyFailed(context: Context, articleId: String, title: String?) {
        post(
            context = context,
            articleId = articleId,
            title = "这篇没能生成音频",
            text = title?.takeIf { it.isNotBlank() } ?: "可以稍后在文章页重新蒸馏",
        )
    }

    private fun post(context: Context, articleId: String, title: String, text: String) {
        // 通知 id 用 articleId 的 hash（要的是 int），但 deep link 里必须放**原始 id** ——
        // 之前写成 id.toString() 会拼出 stashbox://detail/-123456 这种打不开的链接。
        val notifyId = articleId.hashCode()
        ensureChannel(context)

        // 权限门必须按 **targetSdk** 判断，不能按系统版本（SDK_INT）。
        //
        // 真机实测（华为 JEF-AN20 / Android 12 / SDK 31，2026-10-02）：
        // 本项目 targetSdk=35，而 Android 把「targetSdk≥33 的 app 必须有
        // POST_NOTIFICATIONS」这条规则**回移到了 Android 12**。原判断写成
        // `Build.VERSION.SDK_INT >= TIRAMISU`，在 SDK 31 上为 false → 检查被跳过 →
        // 直接调 notify() → 系统静默丢弃：
        //   HwNotificationService: isNeedInterceptStart true, interceptionState:BACKGROUND
        //   NotificationService: blockNotificationByPermission,
        //       Suppressing notification from package com.tingxia.audio.debug
        // 现象是「Worker 跑到了、渠道建了、notify() 调了，但通知栏什么都没有」，
        // 而且不报任何错 —— 只看「有没有调 notify()」会误判成成功。
        //
        // 判据是 targetSdk 不是系统版本：门槛由 app 自己决定，与手机是 12 还是 14 无关。
        if (requiresNotificationPermission(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(
                "DistillNotifier",
                "POST_NOTIFICATIONS 未授权，通知被系统丢弃（targetSdk=" +
                    "${context.applicationInfo.targetSdkVersion}, device=${Build.VERSION.SDK_INT}）",
            )
            return
        }

        // 复用详情页已注册的 deep link（MainActivity: route "detail/{id}" +
        // navDeepLink "stashbox://detail/{id}"），不用自己再造一套 Intent extra 跳转。
        val intent = Intent(
            Intent.ACTION_VIEW,
            "stashbox://detail/$articleId".toUri(),
            context,
            MainActivity::class.java,
        ).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            notifyId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notifyId, notification)
        }.onFailure {
            // 之前 runCatching 什么都不做，失败完全不可见 —— 这正是上面那个 bug
            // 难被发现的原因。至少要留下一行日志。
            Log.w("DistillNotifier", "notify 失败: ${it.message}", it)
        }
    }

    /**
     * 本 app 是否需要 POST_NOTIFICATIONS —— 判据是 **targetSdk ≥ 33**。
     *
     * 不能写 `SDK_INT >= TIRAMISU`：targetSdk 35 的 app 跑在 Android 12 上时，
     * 系统照样强制要求这个权限，而按 SDK_INT 判断会漏掉。
     */
    fun requiresNotificationPermission(context: Context): Boolean =
        context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.TIRAMISU

    /** 供调用方（Worker / UI）查询当前是否已具备发通知的条件。 */
    fun canNotify(context: Context): Boolean =
        !requiresNotificationPermission(context) ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

}
