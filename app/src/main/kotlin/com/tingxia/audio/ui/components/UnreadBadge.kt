package com.tingxia.audio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

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
 *
 * ## 2026-10-27（第二次）：尺寸 20dp → 16dp，并改回**叠放**
 *
 * 上一轮把角标从 16dp 放大到 20dp，理由是「16dp 装不下 "99+"」。
 * 那个理由成立，但**结论错了** —— 真正的问题不是数字装不装得下，
 * 而是角标**盖住了铃铛**（实测重叠 69%，角标区只剩 83px 是铃铛线条）。
 *
 * 两次改法都不对：
 *
 * | 方案 | 问题 |
 * |---|---|
 * | 20dp 叠在图标右上 | 完全盖住铃铛（重叠 69%） |
 * | 20dp 改并排、放在铃铛右侧 | 角标成了一个**独立圆点**，读起来像顶栏**多了个入口**，主次不分 |
 *
 * 用户原话：「角标数字只是通知按钮的一角提示」。
 *
 * ⇒ 正确形态是 **Material 3 官方做法**（`BadgedBox` + `Badge`）：
 * **小尺寸角标 + 叠在图标右上角**。
 *
 * 尺寸回到 **16dp**（M3 Badge 小号的标准直径），数字用 `labelSmall`（11sp）。
 *
 * ### 为什么 16dp 装得下 "99+"
 *
 * 角标是**高度固定、宽度按内容**的（药丸形），不是正方形：
 *   · "1"    → 16dp 见方（正圆）
 *   · "99+"  → 高 16dp、宽约 23dp（药丸）
 * 16dp 限制的是**高度**，"99+" 的宽度不设上限 ⇒ 不会被裁。
 *
 * 字号仍用 `labelSmall`（11sp）而不是更小的 9sp ——
 * 9sp 低于 Material 的 11sp 标签字号下限（R4.2）。
 *
 * ### 叠放的侵入量
 *
 * `BadgedBox` 把角标挂在**内容盒的右上角**。这里内容盒是 IconButton
 * （48dp），而铃铛图标是 24dp、居中在其中 ⇒ 角标自然骑在图标右上角
 * 外侧，只与铃铛**轻微相交**。实测（见 NotificationBadgeIconTest）：
 * 重叠面积只占铃铛的个位数百分比。
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
            // ⚠️ 高度固定 16dp，宽度按内容长 ⇒ "99+" 变成药丸而不是被裁。
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.error)
            // `mergeDescendants = true`：角标里那个 "99+" 文字本身也是一个语义节点，
            // 不合并的话读屏会把「99+」和「120 条未读通知」念两遍。
            // 合并后只剩一条完整描述（内容描述覆盖子文本）。
            .semantics(mergeDescendants = true) {
                contentDescription = "$count 条未读通知"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onError,
            // R4.2：标签字号下限 11sp —— 写死更小的 9sp 会让数字读不出来。
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 3.dp),
        )
    }
}
