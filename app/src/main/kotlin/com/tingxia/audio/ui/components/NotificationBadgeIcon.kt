package com.tingxia.audio.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 供 UI 测试精确定位节点用的 tag。
 *
 * 单独抽出来是为了让测试和生产引用**同一份字符串** ——
 * 写在两边的话，改了一边测试就默默失效（找不到节点会报错，
 * 但如果测试用的是 contentDescription 而不是 tag，就会变成断言漂）。
 */
object NotificationBadgeIconTestTags {
    /** 铃铛图标本身（不是 IconButton 容器）。 */
    const val BELL = "notification_bell_icon"
}

/**
 * 顶栏的「通知」入口 = 铃铛 + 未读角标（2026-10-27）。
 *
 * ## 为什么单独抽成一个组件
 *
 * 原来这段是 HomeScreen 里的内联代码。它有两个**只有渲染后才看得见**的缺陷：
 *
 * ① 角标叠在铃铛右上角 ⇒ 铃铛几乎整个被盖住
 * ② 角标放进 IconButton 的 content 里 ⇒ 被 IconButton 的 24dp 内容区压扁
 *
 * 这两条都不是"读代码能看出来"的问题 —— 编译绿、单测绿、lint 绿，
 * 只有真机看得见。而**写一条能测住它们的测试**，就必须有一个能被测试
 * 直接调用的组件：否则测试里会复刻一份实现，测试和实现一起漂移，
 * 等于没测（这正是"测试夹具不许是复制品"的同一条纪律）。
 *
 * ⇒ 抽到这里，测试直接测**生产用的这个组件**。
 *
 * ## 布局契约（被 NotificationBadgeIconTest 钉住）
 *
 * · 角标**叠在**铃铛右上角（不是并排）—— 角标是铃铛的"一角提示"，
 *   不是顶栏里的第四个入口
 * · 但**只像素级侵入**：重叠面积占比 ≤ 20%（实测 2.8%），
 *   铃铛图形仍然完整可辨
 * · 角标本身**明显小于**铃铛（16dp vs 24dp），主次分明
 * · "99+" 不被压缩（角标高度固定、宽度按内容长）
 *
 * ## 两次改错的过程（留着当反面教材）
 *
 * | 版本 | 形态 | 问题 |
 * |---|---|---|
 * | v1 | 20dp 角标 `align(TopEnd)` 叠在 **24dp 图标盒**上 | 角标完全落在图标框内，重叠 **69%**；实测角标区 4900px 里只剩 **83px** 是铃铛线条 ⇒ 铃铛几乎消失 |
 * | v2 | 20dp 角标**并排**在铃铛右侧 | 角标成了一个独立的大圆点，读起来像顶栏**多了一个入口**，主次不分（用户：「角标数字只是通知按钮的一角提示」） |
 * | v3 | 16dp 角标 + 官方 `BadgedBox` | BadgedBox 的避让逻辑把角标推到内容盒**外面**、还向上探出 22dp ⇒ 角标飘在标题行，且与铃铛零重叠（不是"一角提示"） |
 * | v4 ✅ | **16dp 角标 + `align(TopEnd)` 叠在 48dp 的 IconButton 盒角上** | 角标骑在铃铛右上角，只侵入 2.8% |
 *
 * 关键差别在 v1 → v4：**角标挂在 48dp 的 IconButton 盒角上**，
 * 而不是 24dp 的图标盒上 —— 铃铛图标在 48dp 里居中，
 * 角标因此自然骑在它右上角外侧，只轻微相交。
 */
@Composable
fun NotificationBadgeIcon(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ## 为什么不用官方 `BadgedBox`
    //
    // BadgedBox 的内容盒是 **IconButton（48dp）**，它有自己的避让逻辑：
    // 实测语义树，它把 16dp 的角标推到 **x 36..52 / y -22..14** ——
    // 角标跑到内容盒**外面**、还向上探出 22dp。
    //   · 与铃铛图标零重叠 ⇒ 不是"一角提示"，是飘在旁边的孤立数字
    //   · 向上探出 22dp ⇒ 在真机顶栏里会顶进标题行
    // ⇒ BadgedBox 的前提是"content 就是一个裸图标"，而这里 content 是
    //   带 48dp 最小触控区的 IconButton，前提不成立。
    //
    // ## 这里的做法
    //
    // 外层 Box 尺寸 = IconButton（48dp），角标 `align(TopEnd)` 挂在这个
    // 48dp 盒的右上角，而铃铛图标是 24dp、**居中**在其中：
    //
    //   铃铛图标  x 12..36dp
    //   角标      x 32..48dp / y 0..16dp
    //   ⇒ 重叠    x 32..36 (4dp) × y 12..16 (4dp) = 16dp²
    //   ⇒ 占铃铛面积 **2.8%** —— 真正的"像素级侵入"
    //
    // 而且角标**不会探出** 48dp 盒（不撞下一个图标），**也不会向上探进标题区**。
    //
    // ⚠️ 角标必须放在 IconButton **外面**：IconButton 会 merge descendants，
    // 放进去的话角标的 testTag 会和铃铛的 testTag 合并到同一个语义节点上，
    // 测试就分不出谁是谁了。
    Box(modifier = modifier) {
        IconButton(onClick = onClick) {
            Icon(
                Icons.Filled.Notifications,
                contentDescription = "通知",
                // ⚠️ 必须用 testTag 精确定位**图标本身**。
                //
                // 用 contentDescription 去查是**不可靠**的：IconButton 把子节点的
                // contentDescription 收进自己的语义节点，而合并后的节点在
                // Robolectric 与真机上**边界不同** ——
                //   真机（density 3.5）查到的是 Icon 本身 24dp；
                //   Robolectric（density 1.0）查到的是 IconButton 容器 40dp。
                // 同一句查询在两个环境代表不同的东西 ⇒ 断言会飘。
                //
                // testTag 钉的是**渲染节点自己**，与环境无关。
                modifier = Modifier.testTag(NotificationBadgeIconTestTags.BELL),
            )
        }
        UnreadBadge(
            count = count,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}
