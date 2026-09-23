package com.tingxia.audio.util

import java.time.ZonedDateTime
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * CP-TIME：把 ISO 时间字符串格式化为「X 分钟前 / X 小时前 / X 天前 / MM-dd」相对时间。
 *
 * 抽出原 NotificationCenterScreen 私有函数，避免各处重复；ArticleListScreen /
 * CaptureScreen / DistillScreen / ArticleDetailScreen 共享同一份语义。
 */
fun formatRelativeTime(isoTime: String?): String {
    if (isoTime.isNullOrBlank()) return ""
    return try {
        val dateTime = ZonedDateTime.parse(isoTime)
        val now = ZonedDateTime.now()
        val diff = Duration.between(dateTime, now)

        when {
            diff.toMinutes() < 1 -> "刚刚"
            diff.toMinutes() < 60 -> "${diff.toMinutes()} 分钟前"
            diff.toHours() < 24 -> "${diff.toHours()} 小时前"
            diff.toDays() < 7 -> "${diff.toDays()} 天前"
            else -> dateTime.format(DateTimeFormatter.ofPattern("MM-dd"))
        }
    } catch (_: Exception) {
        isoTime.take(10)
    }
}

/** 把 ISO 时间格式化为 HH:MM（绝对时间，用于「今天 HH:MM 提交」类场景） */
fun formatClockTime(isoTime: String?): String {
    if (isoTime.isNullOrBlank()) return ""
    return try {
        val dateTime = ZonedDateTime.parse(isoTime)
        dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
    } catch (_: Exception) {
        isoTime.take(5)
    }
}