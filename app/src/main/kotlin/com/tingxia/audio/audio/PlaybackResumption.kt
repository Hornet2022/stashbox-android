package com.tingxia.audio.audio

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession

/**
 * 「继续播放」的状态（2026-10-23 新增）。
 *
 * ## 为什么需要
 *
 * Android 13+ 的系统媒体面板（锁屏 / 下拉通知的媒体区）会记住「上次在听什么」，
 * 并给用户一个「继续播放」按钮 —— 按了之后系统会向媒体服务发一个
 * **恢复请求**（Media3 的 `onPlaybackResumption`）。
 *
 * 不实现它，那个按钮就是**死的**（或者压根不出现）。
 * 本项目此前没实现 → 系统媒体面板里没有「继续播放」。
 *
 * ## 为什么存 SharedPreferences 而不是 Room
 *
 * 恢复请求是**冷启动**路径：进程可能刚被系统杀掉，Room 的初始化、WakeLock、
 * 协程调度都还没就绪。SharedPreferences 是同步读的、进程内即可用，
 * 在 `onPlaybackResumption` 那种「必须立刻给出一个 MediaItem」的场合
 * 是最省事也最不容易出错的载体。
 *
 * 存的东西很少：一条 MediaItem 的必要字段 + 一个播放位置。
 */
object PlaybackResumption {

    private const val PREFS = "stashbox_playback_resumption"
    private const val KEY_ARTICLE_ID = "article_id"
    private const val KEY_AUDIO_URL = "audio_url"
    private const val KEY_TITLE = "title"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_SAVED_AT = "saved_at"

    /** 恢复请求的合理有效期：超过这个时间就不给恢复了（内容可能已失效）。 */
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 记下「刚才在听什么」。
     *
     * 由 [PlayerController] 在播放开始时调用。
     */
    fun save(context: Context, articleId: String?, audioUrl: String, title: String?) {
        if (audioUrl.isBlank()) return
        prefs(context).edit()
            .putString(KEY_ARTICLE_ID, articleId.orEmpty())
            .putString(KEY_AUDIO_URL, audioUrl)
            .putString(KEY_TITLE, title.orEmpty())
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    /** 记下当前的播放位置（用户暂停/退出时）。 */
    fun savePosition(context: Context, positionMs: Long) {
        prefs(context).edit().putLong(KEY_POSITION_MS, positionMs).apply()
    }

    /**
     * 取出可恢复的条目；没有或过期返回 null。
     *
     * 过期判断用「保存时刻」而不是「位置」：位置是 0 也可能是合法的开头，
     * 而「三天前在听的东西」多半已经不想听了。
     */
    fun read(context: Context): Resumable? {
        val p = prefs(context)
        val url = p.getString(KEY_AUDIO_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val savedAt = p.getLong(KEY_SAVED_AT, 0L)
        if (savedAt <= 0L) return null
        if (System.currentTimeMillis() - savedAt > MAX_AGE_MS) return null
        return Resumable(
            articleId = p.getString(KEY_ARTICLE_ID, null)?.takeIf { it.isNotBlank() },
            audioUrl = url,
            title = p.getString(KEY_TITLE, null),
            positionMs = p.getLong(KEY_POSITION_MS, 0L),
        )
    }

    /** 清除（用户登出 / 换账号时不该把上一个人留下的东西恢复出来）。 */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    data class Resumable(
        val articleId: String?,
        val audioUrl: String,
        val title: String?,
        val positionMs: Long,
    ) {
        /**
         * 还原成 MediaItem。
         *
         * `mediaId` 承载 articleId —— 与 [PlayerController] 里播放列表的约定一致，
         * 恢复后「当前是哪一篇」照样能从播放器直接读出来。
         */
        fun toMediaItem(): MediaItem {
            val builder = MediaItem.Builder()
                .setUri(audioUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title ?: "")
                        .build(),
                )
            if (!articleId.isNullOrBlank()) builder.setMediaId(articleId)
            return builder.build()
        }
    }
}
