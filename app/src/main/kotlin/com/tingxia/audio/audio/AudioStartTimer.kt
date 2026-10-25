package com.tingxia.audio.audio

import android.os.SystemClock

/**
 * 音频启动耗时计时器（2026-10-25）。
 *
 * ## 「音频启动」到底量什么
 *
 * 量的是**用户点了播放 → 播放器进入实际渲染态**（`isPlaying` 由 false 变 true）
 * 之间的毫秒数。
 *
 * 为什么终点是 `isPlaying == true` 而不是「调了 `player.play()`」：
 * `play()` 只是把 `playWhenReady` 置 true，音频还得下载/缓冲/解码 ——
 * 那段才是用户按了按钮却**听不到声音**的时间（也是内网 2G 环境下最难看的一段）。
 * ExoPlayer 的 `isPlaying` 语义正好是「state == READY 且 playWhenReady」，
 * 即「已就绪、正在出声」。
 *
 * 为什么起点是「点了播放」而不是「Activity 创建」：
 * 指标要能回答「用户点了播放之后多久出声」，起止点必须都在**这一次动作**之内。
 *
 * ## 为什么单独一个类
 *
 * 这个计时器有两处「不该上报」的分支（暂停、播放失败），它们最容易在
 * 反复重构里被漏掉 —— 漏了的表现是**上报一个假的、巨大的启动耗时**，
 * 直接把「音频启动 < 500ms」这条指标污染掉。
 * 抽成纯逻辑类 ⇒ 能被确定性地单测（不依赖 ExoPlayer 真加载出声音）。
 */
class AudioStartTimer(
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {

    /** 待上报的一次启动。null = 当前没有待上报的启动。 */
    private var pending: Pending? = null

    private data class Pending(val startedAtMs: Long, val articleId: String?)

    /**
     * 用户点了播放 —— 记起点。
     *
     * 每次 [PlayerController.play] 都会调它；重复调用会**覆盖**上一次
     * （连点两下只算最后一次，不该累加成两个时长）。
     */
    fun onPlayRequested(articleId: String?) {
        pending = Pending(now(), articleId)
    }

    /**
     * 播放失败 —— 这次启动永远不会「出声」，作废。
     *
     * 不作废的后果：pending 一直挂着，下次随便一次 resume 让 isPlaying 变 true，
     * 就会报出一个横跨好几分钟的「启动耗时」。
     */
    fun onPlaybackError() {
        pending = null
    }

    /**
     * 暂停 / 停止 —— 这次启动作废。
     *
     * 场景：用户点播放 → 还没出声就按了暂停 → 几分钟后按播放。
     * 那次「几分钟后」是**恢复**不是**启动**，不该算进启动耗时。
     */
    fun onPausedOrStopped() {
        pending = null
    }

    /**
     * 播放器进入实际渲染态 —— 取出并作废这次启动。
     *
     * @return 本次启动的耗时（毫秒）与文章 id；没有待上报的启动则返回 null
     *   （例如队列自动续播 —— 那种场景没有「用户点了播放」，不计入启动耗时）。
     */
    fun consumeOnPlaying(): AudioStart? {
        val current = pending ?: return null
        pending = null
        return AudioStart(
            durationMs = (now() - current.startedAtMs).coerceAtLeast(0L),
            articleId = current.articleId,
        )
    }

    /** 仅供单测：当前是否有待上报的启动。 */
    internal val hasPending: Boolean get() = pending != null
}

/** 一次「点播放 → 出声」的测量结果。 */
data class AudioStart(
    /** 从点播放到出声的毫秒数。 */
    val durationMs: Long,
    /** 这次播放对应的文章 id（可能为 null —— 全屏播放器的 IDLE 恢复不传 id）。 */
    val articleId: String?,
)
