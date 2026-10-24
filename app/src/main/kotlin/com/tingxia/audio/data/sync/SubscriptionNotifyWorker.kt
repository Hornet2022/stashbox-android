package com.tingxia.audio.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tingxia.audio.data.repository.NotificationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 轮询通知中心 → 对「还没通知过」的条目发**本地系统通知** → 回报服务端「已投递」。
 *
 * ## 为什么要有这个 Worker（推送的「投递」差的那一步）
 *
 * 没有厂商推送通道（APNs / FCM / 个推 / 极光全仓零命中），
 * 服务端 `STASHBOX_PUSH_WEBHOOK_URL` 也没配 ⇒
 * `NullPushProvider` 如实回报「未配置推送通道」⇒ 通知行是 `failed`。
 *
 * 但**通知本身是好的**：它进了通知中心（铃铛角标 + 通知页列表），
 * 也进了订阅流。缺的只是「弹到手机通知栏」这一步 ——
 * 那一步没有厂商通道就没人做，于是用户「有更新」这件事**永远不知道**。
 *
 * ⇒ 这一步由 App 自己做：拉通知中心 → 对没通知过的发本地通知 →
 * **回报服务端**（`POST /notifications/{id}/delivered`），
 * 让服务端的投递状态变成真实的「已送达」而不是「失败」。
 *
 * ## 为什么用 WorkManager
 *
 * ViewModel 里的轮询在用户切走 app、进程被回收后就没了。
 * WorkManager 的任务能跨进程存活 —— 这与 [DistillReadyWorker] 是同一个理由。
 *
 * ## 去重：记「已通知到的最大 id」
 *
 * 通知 id 是自增的 ⇒ 递增。所以只记一个数（已通知到的最大 id）就够，
 * 不需要存一串。**已经通知过的不再弹**（否则每 15 分钟弹一次同样的内容）。
 */
@HiltWorker
class SubscriptionNotifyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val notificationRepository: NotificationRepository,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val NAME = "subscription-notify"

        private const val TAG = "SubscriptionNotifyWorker"
        private const val PREFS = "subscription_notify_state"
        private const val KEY_LAST_NOTIFIED_ID = "last_notified_id"

        /** 连续失败几次后放弃本轮（不无限重试）。 */
        private const val MAX_FAILURES = 3
    }

    override suspend fun doWork(): Result {
        // 没有 POST_NOTIFICATIONS 权限 ⇒ 弹了也是被系统丢弃（静默失败）。
        // 那就别白跑一轮网络请求（省电），等用户授权后自然恢复
        // （周期任务一直在，下一轮会重新检查）。
        if (!DistillNotifier.canNotify(applicationContext)) {
            Log.i(TAG, "未授予 POST_NOTIFICATIONS，跳过本轮订阅通知检查")
            return Result.success()
        }

        val notifications = try {
            notificationRepository.list(unreadOnly = false)
        } catch (e: Exception) {
            Log.w(TAG, "拉通知中心失败: ${e.message}")
            return if (runAttemptCount < MAX_FAILURES) Result.retry() else Result.success()
        }

        val lastNotified = prefs().getInt(KEY_LAST_NOTIFIED_ID, 0)
        val pending = SubscriptionNotifyLogic.selectPending(notifications, lastNotified)
        val notifiedIds = mutableListOf<Int>()

        for (item in pending) {
            val ok = SubscriptionNotifier.post(
                context = applicationContext,
                articleId = item.article_id,
                title = item.title,
                body = item.body,
            )
            if (ok) notifiedIds.add(item.id)

            // 回报「已投递」：**失败不阻断**（本地通知已经发出去了，
            // 服务端状态只是台账，回报失败下次再补）。
            runCatching { notificationRepository.markDelivered(item.id) }
                .onFailure { Log.w(TAG, "回报已投递失败 id=${item.id}: ${it.message}") }
        }

        // ⚠️ 水位**只按成功发出的**推进 —— 若因为「通知权限没授予」而没发出去
        // 却推进了水位，用户后来授权了也**补不回来**。
        val watermark = SubscriptionNotifyLogic.nextWatermark(notifiedIds, lastNotified)
        if (watermark > lastNotified) {
            prefs().edit().putInt(KEY_LAST_NOTIFIED_ID, watermark).apply()
        }
        if (notifiedIds.isNotEmpty()) Log.i(TAG, "已发出 ${notifiedIds.size} 条订阅更新通知")
        return Result.success()
    }

    /**
     * ⚠️ 返回类型必须**显式**写。
     *
     * 写成表达式体（`private fun prefs() = applicationContext.getSharedPreferences(...)`）
     * 时 KSP 解析不出推断类型 → `error.NonExistentClass` →
     * 整个 worker 的 AssistedInject 处理失败（编译报的是 KSP 错，不是类型错，
     * 很难一眼看出是这个原因）。
     */
    private fun prefs(): android.content.SharedPreferences =
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
