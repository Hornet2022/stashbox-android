package com.tingxia.audio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tingxia.audio.ui.update.NotificationPermissionAction

/**
 * 通知栏控制条不可用时的提示（2026-10-26 新增）。
 *
 * ## 为什么单独一个文件
 *
 * 一是这段 UI 本身自成一块（文案 + 提示条），二是**它要能被单测锁住**：
 * 文案跟动作必须一一对应（说能做的就真能做），那就需要一个纯函数 +
 * 一个独立的组件，而不是散在播放器大文件里。
 */
internal fun notificationControlHintText(action: NotificationPermissionAction): String =
    when (action) {
        NotificationPermissionAction.ALREADY_ON -> ""
        NotificationPermissionAction.REQUEST_SYSTEM_DIALOG ->
            "通知栏还没有播放控制条，点这里开启"
        NotificationPermissionAction.OPEN_SYSTEM_SETTINGS ->
            "通知栏没有播放控制条（系统里已拒绝），点这里去系统设置开启"
    }

/**
 * 提示条。
 *
 * 配色刻意**不用**错误红：这不是「播放失败」，而是「少了一个入口」，
 * 用红会让用户以为音频也出了问题（而音频是好的）。
 */
@Composable
internal fun NotificationControlHintBanner(
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x33FFC107), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0x66FFC107), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = Color(0xFFFFECB3),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "去开启",
            color = Color(0xFFFFD54F),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
