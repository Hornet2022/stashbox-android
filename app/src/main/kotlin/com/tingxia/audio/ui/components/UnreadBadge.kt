package com.tingxia.audio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 未读角标（2026-10-23）。
 *
 * 之前首页铃铛**完全没有角标** —— 后端 `GET /api/v1/notifications` 早就返回了
 * `unread_count`，安卓的 `NotificationsResponse` 却没声明这个字段 → 角标无从谈起。
 *
 * 后果不是「少个装饰」：用户不知道「订阅有更新」，于是「订阅」看起来像个
 * 没有回报的开关 —— 这正是内测反馈「订阅没有功能闭环」的一环。
 *
 * 数字上限显示 `99+`：未读上百时把两位数摊在铃铛上会盖住图标。
 */
@Composable
fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    val label = if (count > 99) "99+" else count.toString()
    Box(
        modifier = modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error)
            .semantics { contentDescription = "$count 条未读通知" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 9.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 1.dp),
        )
    }
}
