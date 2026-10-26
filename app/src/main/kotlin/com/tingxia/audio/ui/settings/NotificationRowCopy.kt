package com.tingxia.audio.ui.settings

import com.tingxia.audio.ui.update.NotificationPermissionAction

/**
 * 通知设置行的文案（2026-10-26 新增）。
 *
 * ## 为什么要单独抽出来
 *
 * 因为**文案本身就是判据的一部分**。
 *
 * 真机实测（MIUI 14 / Android 14）踩到的坑是：设置页写着「点击开启」，
 * 用户点了**什么都没发生** —— 因为用户已经永久拒绝过，系统框不会再弹。
 * 那句「点击开启」当时是**假的**：它承诺了一个不存在的动作。
 *
 * ⇒ 文案必须跟着 [NotificationPermissionAction] 一起变：
 * 每一档动作都有它**对应**的那句话，不允许出现「说了能点、其实点了没反应」。
 * 所以这段文案写成纯函数，让单测能逐档锁住
 * 「永久拒绝 ⇒ 文案说的是去系统设置，**不是**『点击开启』」。
 */
internal enum class NotificationRowPurpose {
    /** 推送通知（新文章 / 蒸馏完成提醒）。 */
    PUSH,

    /** 播放通知（通知栏 / 锁屏的播放控制条）。 */
    PLAYBACK,
}

/**
 * 通知设置行的副标题。
 *
 * @param action 统一判据给出的动作（见 `NotificationPermissionPolicy.kt`）
 * @param purpose 这一行管的是哪一种通知
 */
internal fun notificationRowSubtitle(
    action: NotificationPermissionAction,
    purpose: NotificationRowPurpose,
): String = when (action) {
    NotificationPermissionAction.ALREADY_ON -> when (purpose) {
        NotificationRowPurpose.PUSH -> "已开启：新文章与蒸馏完成会提醒你"
        NotificationRowPurpose.PLAYBACK -> "已开启：后台播放时通知栏可暂停 / 快进"
    }

    NotificationPermissionAction.REQUEST_SYSTEM_DIALOG -> when (purpose) {
        NotificationRowPurpose.PUSH -> "点击开启：接收新文章与蒸馏完成提醒"
        NotificationRowPurpose.PLAYBACK -> "点击开启：后台播放时通知栏可控制播放"
    }

    // ⚠️ 这一档**不能**再写「点击开启」——系统授权框已经不会再弹了，
    // 写「点击开启」就是在承诺一个做不到的动作（真机实测 result=2）。
    NotificationPermissionAction.OPEN_SYSTEM_SETTINGS -> when (purpose) {
        NotificationRowPurpose.PUSH -> "通知已关闭，点这里去系统设置开启"
        NotificationRowPurpose.PLAYBACK -> "通知栏控制条已关闭，点这里去系统设置开启"
    }
}
