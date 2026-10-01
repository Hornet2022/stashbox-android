package com.tingxia.audio.data.sync

import java.util.Calendar

/**
 * 通勤时段判断（2026-10-02）。
 *
 * ## 为什么不写死时间窗
 *
 * 方案 §1 闭环 3 说的是「基于用户通勤时间**规律**」。写死 7:00-9:30 /
 * 17:30-19:30 对朝九晚五的人成立，对夜班、值班、自由职业的人就是错的 ——
 * 而且写死的窗口还会在用户出差时持续误判，白白消耗流量和存储。
 *
 * 所以这里优先**从真实收听记录学**：用户通常在什么时段收听，那个时段就是
 * 他的通勤窗口。样本不够时（冷启动）才回落到默认窗口。
 *
 * 位置维度（方案里提到的「时段 + 位置 + 听感画像」）本次**没有做** ——
 * 那需要定位权限和一套隐私说明，不该在没有用户授权的情况下悄悄引入。
 * 现在只用时段，等权限和合规就绪再补。
 */
object CommuteWindowDetector {

    /**
     * 默认窗口（冷启动用）。只覆盖最常见的双高峰。
     * 单位：小时，闭开区间 [start, end)。
     */
    val DEFAULT_WINDOWS: List<IntRange> = listOf(7..9, 17..19)

    /** 学习规律所需的最少收听样本。低于此数不信任统计结果。 */
    const val MIN_SAMPLES_FOR_LEARNING = 8

    /** 命中窗口所需的最少占比：低于此说明「随时都在听」，不构成通勤规律。 */
    const val MIN_SHARE = 0.3

    /**
     * 从收听时间戳里学出「习惯收听的时段」。
     *
     * @param listeningAtMillis 用户历次收听时间（升序或乱序都行）
     * @return 命中占比最高的小时段；样本不足返回 null
     */
    fun learnWindows(listeningAtMillis: List<Long>): List<IntRange>? {
        if (listeningAtMillis.size < MIN_SAMPLES_FOR_LEARNING) return null

        // 按小时统计（不区分星期：通勤规律通常按"每天几点"更稳，
        // 周末作息差异交给默认窗口兜底，避免学出「周末下午」这种噪声）
        val byHour = IntArray(24)
        listeningAtMillis.forEach { millis ->
            val cal = Calendar.getInstance().apply { timeInMillis = millis }
            byHour[cal.get(Calendar.HOUR_OF_DAY)] += 1
        }

        val total = listeningAtMillis.size
        val best = (0..23).maxByOrNull { byHour[it] } ?: return null
        if (byHour[best].toDouble() / total < MIN_SHARE) return null

        // 以峰值为中心取最多 3 小时窗口。边界处**收缩**而不是平移 ——
        // 峰值在 23 点时应该是 22..23；若为了凑满 3 小时把 start 压到 21，
        // 就把 21 点这个高峰前时段也拖进来了，窗口反而偏了。
        val start = maxOf(0, best - 1)
        val end = minOf(23, best + 1)
        return listOf(start..end)
    }

    /**
     * 当前时刻是否处于「该预加载」的窗口内。
     *
     * @param learnedWindows 学到的窗口；null 表示数据不够，用默认窗口
     */
    fun shouldPrefetchNow(learnedWindows: List<IntRange>?, nowHour: Int = currentHour()): Boolean {
        val windows = learnedWindows?.takeIf { it.isNotEmpty() } ?: DEFAULT_WINDOWS
        return windows.any { nowHour in it }
    }

    /**
     * 预加载几集。
     *
     * 缓存上限 100MB（OfflineDownloadManager.MAX_CACHE_BYTES），而一篇
     * 30 分钟音频 128k 约 28MB —— 一次预加载太多会把用户已缓存的内容挤掉，
     * 那比不预加载更糟。取 1 是保守值：只备下一集，正是通勤场景真正会听的。
     */
    fun prefetchCount(): Int = 1

    private fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
}
