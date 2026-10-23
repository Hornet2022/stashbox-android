package com.tingxia.audio.audio

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import com.tingxia.audio.R
import com.tingxia.audio.audio.di.AudioPlayerEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * 音频播放前台服务（CP4.4）。
 *
 * 持有 [MediaSession]，其底层 [androidx.media3.exoplayer.ExoPlayer] 来自 [PlayerModule]
 * 提供的**共享单例**——与 [PlayerController] 是同一实例。系统据此接管锁屏 / 通知控制，
 * 后台播放也由该 MediaSessionService 守护。
 *
 * CP11.0.7 P2.1: 共享 ExoPlayer 已注入 cache data source factory（见 [PlayerModule]），
 * 服务内播放同样命中本地音频缓存。
 *
 * ## 2026-10-23：通知栏控制补齐
 *
 * 这个服务是「系统怎么操控本 App」的唯一定义处。三处补齐：
 *
 * 1. **通知点击要回到播放器**（`setSessionActivity`）。
 *    缺了它，`DefaultMediaNotificationProvider` 源码里的
 *    `builder.setContentIntent(mediaSession.getSessionActivity())` 拿到 null
 *    → **点通知什么都不会发生**（不是"跳到首页"，是"点了没反应"）。
 * 2. **通知要多几个控制**（`setCustomLayout` + 自定义命令）。
 *    Media3 自带的通知只有 播放/暂停（外加有下一项时的上下一首），
 *    而「快退 10 秒 / 快进 30 秒」是长音频的刚需（App 内 UI 早就有，
 *    通知里却没有 —— 同一个动作，两个入口的能力不一样）。
 * 3. **自定义命令要在 `onConnect` 里放行**（`availableSessionCommands`）。
 *    ⚠️ Media3 的文档明写着：custom layout 里的命令**必须**同时出现在
 *    连接结果里，否则按钮点了没反应。漏了这一步是最隐蔽的一种失败：
 *    按钮画出来了，按下去什么都不发生。
 */
@UnstableApi
class AudioPlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // 共享 ExoPlayer（与 PlayerController 同一实例），通过 Hilt EntryPoint 取到
        val app = applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(app, AudioPlayerEntryPoint::class.java)
        val player = entryPoint.exoPlayer()
        // 同样共享的 PlayerController —— 自定义命令（上一首/下一首/快进快退/
        // 睡眠定时器）要落到它身上，而不是在服务里另造一套队列。
        val playback = entryPoint.playerController()

        val callback = TingxiaMediaSessionCallback(playback, this)
        mediaSession = MediaSession.Builder(this, player)
            // 会话 id 必须**显式给**且**稳定**：Media3 要求同一进程内会话 id 唯一
            // （重复会抛 "Session ID must be unique"），而默认 id 是空串 ——
            // 空串在「服务被重建但旧会话还没释放」时会撞。
            .setId(SESSION_ID)
            .setCallback(callback)
            // 通知栏点「播放」要回到**播放器**，而不是停在首页。
            //
            // ⚠️ 这一行是**必填**的：DefaultMediaNotificationProvider 的源码写的是
            // `builder.setContentIntent(mediaSession.getSessionActivity())` ——
            // 没设 sessionActivity，contentIntent 就是 null，
            // 于是**点通知什么都不会发生**（不是"跳到首页"，是"点了没反应"）。
            .setSessionActivity(buildSessionActivity())
            .build()

        // CP4.5: 用 Media3 自带 DefaultMediaNotificationProvider 自动处理锁屏 UI + MediaStyle 通知
        // （含 play/pause + skip previous/next + close actions），无需自定义 NotificationBuilder
        //
        // ⚠️ **必须在 addSession 之前设**：服务一旦接管会话就会发第一条通知，
        // 而那时 provider 还是默认的 → 通知会落在 Media3 的
        // `default_channel_id` 渠道上，而不是我们自己的
        // `stashbox_audio_playback`（真机 dumpsys notification 里看得到
        // `channel=default_channel_id`）。渠道名与重要性都是我们说了算的，
        // 不能让默认渠道替我们决定。
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationIdProvider { _ -> NOTIFICATION_ID }
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.audio_player_channel_name)
                .build(),
        )

        // ⚠️⚠️ **必须**把会话登记给服务（2026-10-23 补，真机实测出来的）。
        //
        // Media3 从 **1.4.0** 起改了这件事：`MediaSessionService` 不再自动
        // 接管 `onGetSession` 返回的会话 —— 应用必须自己调 `addSession()`。
        //
        // 漏了它的后果（真机实测，dumpsys activity services 里看得很清楚）：
        //     * ServiceRecord{... com.tingxia.audio/.audio.AudioPlayerService}
        //       startForegroundCount=0        ← 从没进过前台
        //       通知栏里一条本 App 的通知都没有  ← 通知压根没发
        // 也就是说：**播放通知、锁屏控制、后台播放的前台服务保护，全都因为
        // 这一行缺失而没有生效**。声音照放（因为 App 直接用共享 ExoPlayer 播），
        // 但系统层面「这���是一个媒体 App」这件事从来没被登记过。
        //
        // 症状极具迷惑性：声音正常、进度正常回传、MediaSession 在
        // `dumpsys media_session` 里也看得到（因为会话本身建出来了），
        // 只有通知不见 —— 于是很容易被归因成「Android 13+ 通知权限」，
        // 去申请权限、加渠道、改 IMPORTANCE，**全都不是这里**。
        mediaSession?.let { session -> addSession(session) }

        // 通知里额外的控制（快退 / 快进）。
        //
        // 为什么不靠 player 命令：COMMAND_SEEK_BACK / COMMAND_SEEK_FORWARD 是
        // **相对当前媒体项**的快退快进，而我们要的是「按固定步长跳」；
        // 交给 App 侧算更准（App 知道时长，能夹住边界）。
        //
        // ⚠️ 折叠视图（compact view）**故意不占位**：Media3 的规则是
        // 「只要有按钮显式声明了 compact 索引，默认的 [上一首, 播放/暂停, 下一首]
        // 就整个不生效」—— 于是折叠视图里会**连播放键都没有**。
        // 折叠视图留给默认的三个，展开视图才有快退/快进。
        mediaSession?.setCustomLayout(buildCustomLayout())
    }

    /**
     * 通知里的自定义按钮。
     *
     * 图标用 Media3 自带的 `media3_icon_skip_back_10` / `..._forward_30` ——
     * 步长与 App 内 UI 一致（退 10s / 进 30s），图标上也写着数字，
     * 三者（步长 / 图标 / 文案）对得上。
     */
    private fun buildCustomLayout(): List<CommandButton> = listOf(
        // ⚠️ `CommandButton.Builder(int)` 收的是**图标常量**
        // （CommandButton.ICON_*），不是 drawable 资源 id ——
        // 它内部会 `getIconResIdForIconConstant()` 把常量映射成资源 id。
        // 传错（比如直接传 R.drawable.xxx）→ 映射到 0 →
        // Media3 建 CustomAction 时抛
        // "You must specify an icon resource id to build a CustomAction"
        // → **服务在 onCreate 里就崩**（真机实测：App 一起身就退）。
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setSessionCommand(PlaybackCommands.of(PlaybackCommands.ACTION_SEEK_BACK))
            .setDisplayName("后退 10 秒")
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setSessionCommand(PlaybackCommands.of(PlaybackCommands.ACTION_SEEK_FORWARD))
            .setDisplayName("前进 30 秒")
            .build(),
    )

    /**
     * 通知点击的落地页：全屏播放器。
     *
     * 显式 Intent（带组件）+ extra 路由名 —— 不用 deep link，
     * 因为全项目没有任何地方调 `navController.handleDeepLink()`，
     * 靠 scheme 匹配导航等于埋一个不动的机关（见 MainActivity.pendingRoute 的说明）。
     */
    private fun buildSessionActivity(): android.app.PendingIntent {
        val intent = android.content.Intent(this, com.tingxia.audio.MainActivity::class.java).apply {
            putExtra(
                com.tingxia.audio.MainActivity.EXTRA_NAVIGATE_TO,
                com.tingxia.audio.MainActivity.ROUTE_PLAYER,
            )
            // MainActivity 是 singleTask：复用已有实例而不是叠一个新的
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            // FLAG_IMMUTABLE 是 Android 12+ 的硬要求（S+ 必须声明可变性），
            // FLAG_UPDATE_CURRENT 保证 extra 变化时能刷新
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CHANNEL_ID = "stashbox_audio_playback"

        /** MediaSession 的会话 id（进程内唯一，必须稳定）。 */
        const val SESSION_ID = "stashbox_audio_playback"
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return super.onTaskRemoved(rootIntent)
        // 没有在播放 / 没有媒体时，任务被移除就直接停服务，避免后台空跑
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 仅释放 MediaSession；共享 ExoPlayer 生命周期随 App（由 PlayerModule 提供），
        // 此处不应 release，否则会破坏 PlayerController 正在使用的同一实例。
        mediaSession?.let { session ->
            // 配对 addSession（见 onCreate 的说明）：不摘掉的话，
            // 服务会继续给一个已释放的会话发通知。
            removeSession(session)
            session.release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
