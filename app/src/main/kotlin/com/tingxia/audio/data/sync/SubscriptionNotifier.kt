package com.tingxia.audio.data.sync

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import android.app.PendingIntent
import android.content.Intent
import com.tingxia.audio.MainActivity

/**
 * 「订阅更新」的**本地系统通知**。
 *
 * ## 为什么是本地通知而不是推送
 *
 * 全仓**没有任何厂商推送通道代码**（APNs / FCM / 个推 / 极光 零命中），
 * App 登记的 device token 是**自己生成的 UUID**（`provider = none`），
 * 服务端 `STASHBOX_PUSH_WEBHOOK_URL` 也没配（那是「自建推送网关」的入口，
 * 需要用户自己有一个把 webhook 转成厂商通道的服务）。
 *
 * ⇒ 现阶段唯一能让「有更新」真的出现在**手机通知栏**的通路，就是
 * **App 主动去拉 → 自己弹本地通知**（与「文章好了」用的是同一套机制，
 * 见 [DistillNotifier] 与 [DistillReadyWorker]）。
 *
 * ## 渠道为什么无声
 *
 * 与「蒸馏进度」渠道**同口径**：`IMPORTANCE_LOW` + `setSound(null)` + 保留振动。
 *
 * 原因在 [DistillNotifier.ensureChannel] 里已经写死了：播放器的
 * `handleAudioFocus = true` ⇒ **系统通知音会抢走音频焦点、把正在播的音频停掉**。
 * 用户正在听一篇时，一记「叮」会把声音掐掉 —— 而用户只会归因成「App 把声音弄没了」。
 *
 * 「有更新」不需要声音：它有**角标**（铃铛红点）+ 通知中心列表 + 通知栏条目。
 *
 * ## 与「文章已就绪」通知的区别
 *
 * · [DistillNotifier]：**属主**自己的文章蒸馏完了 → 点进去是那篇自己的文章；
 * · 本通知：**订阅**到的内容（含运营录入的）→ 点进去是那篇订阅内容
 *   （可能不属于当前用户，靠订阅授权放行，见后端 `_get_readable`）。
 */
object SubscriptionNotifier {

    const val CHANNEL_ID = "subscription_updates"
    private const val CHANNEL_NAME = "订阅更新"
    private const val TAG = "SubscriptionNotifier"

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        val existing = mgr.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        mgr.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "订阅的分类有新内容时提醒"
                // ⚠️ **不要出声** —— 理由见 [DistillNotifier.ensureChannel] 的注释。
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 200L, 100L, 200L)
            }
        )
    }

    /**
     * 弹一条订阅更新通知。
     *
     * @param articleId 目标文章 id（没有就只弹通知、不带跳转）
     * @param title     通知标题（服务端给的，例如「新文章：xxx」）
     * @param body      通知正文（服务端给的，例如「你订阅的 科技 标签有新文章蒸馏完成」）
     * @return 是否真的发出去了（false = 没权限或发送失败）
     */
    fun post(
        context: Context,
        articleId: String?,
        title: String,
        body: String,
    ): Boolean {
        ensureChannel(context)

        if (!DistillNotifier.canNotify(context)) {
            Log.w(
                TAG,
                "POST_NOTIFICATIONS 未授权，订阅通知被系统丢弃（targetSdk=" +
                    "${context.applicationInfo.targetSdkVersion}）",
            )
            return false
        }

        val notifyId = (articleId ?: title).hashCode()
        var pending: PendingIntent? = null
        if (!articleId.isNullOrBlank()) {
            val intent = Intent(
                Intent.ACTION_VIEW,
                "stashbox://detail/$articleId".toUri(),
                context,
                MainActivity::class.java,
            ).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            pending = PendingIntent.getActivity(
                context,
                notifyId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
        if (pending != null) builder.setContentIntent(pending)

        return runCatching {
            NotificationManagerCompat.from(context).notify(notifyId, builder.build())
            true
        }.getOrElse {
            Log.w(TAG, "订阅通知发送失败: ${it.message}", it)
            false
        }
    }
}
