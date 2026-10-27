package com.tingxia.audio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tingxia.audio.ui.theme.OnWarningContainerDark
import com.tingxia.audio.ui.theme.Warning
import com.tingxia.audio.ui.theme.WarningContainerDark
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
 *
 * ## 配色为什么不用 MaterialTheme.colorScheme（2026-10-27）
 *
 * 这条提示**只出现在全屏播放器里**，而播放器是**独立的深色界面**
 * （底色是 PlayerBgTop→PlayerBgMid→PlayerMuted 的暖棕渐变，不跟随全局明暗）。
 * ⇒ 这里的底色**不是** App 的 surface，而是播放器自己的渐变；
 * 若按全局明暗去取容器色，浅色主题下就会在深色播放器上盖一块米白/浅灰块 ——
 * 这正是 2026-10-27 刚修过的同类 bug（播放器的 sheet 原来用
 * `MaterialTheme.colorScheme.surface`，浅色主题下是一块白 sheet 盖在深色播放器上，
 * 当时改成了播放器本地底色 PlayerSheetBg）。
 *
 * 所以这里走**播放器本地那一套暖棕语义色**，两套全局主题下**长得一样**：
 *   · 底 WarningContainerDark #3D2F1F（**不透明**）
 *   · 正文 OnWarningContainerDark #E8CFAE
 *   · 描边 / 动作 Warning #C4956A
 *
 * 实测对比度（WCAG AA，正文需 ≥4.5:1）：
 *   正文 #E8CFAE 压 #3D2F1F = **8.60:1** ✓
 *   动作 #C4956A 压 #3D2F1F = **4.83:1** ✓
 *   描边 #C4956A 压 #3D2F1F = 4.83:1 ✓（非文本需 ≥3:1）
 *   描边 #C4956A 压播放器渐变 PlayerBgTop #3A2F26 = 4.86:1 ✓
 *
 * ⚠️ 底色为什么必须**不透明**：原来的 `Color(0x33FFC107)` 是 20% 透明琥珀，
 * 它的实际底色取决于压在渐变哪一段上 —— 压 PlayerBgTop 时正文 6.98:1，
 * 压渐变中段只剩 4.35:1，压最浅的 PlayerMuted 直接掉到 3.13:1（不达标）。
 * 半透明底的对比度是**不可控**的（同 ThemeContrastTest 里 errorContainer 那条判据），
 * 所以换成不透明底。
 */
@Composable
internal fun NotificationControlHintBanner(
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WarningContainerDark, RoundedCornerShape(12.dp))
            .border(1.dp, Warning, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = OnWarningContainerDark,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(10.dp))
        // 2026-10-27：原来这里是 `Text` + `clickable`（上下 4dp padding，高约 28dp）——
        // 触控区不到 48dp，而且无障碍读出来只是一段文字，用户不知道它能点。
        //
        // 现在换 TextButton：自带 **48dp 最小触控区**、涟漪反馈、以及
        // `Role.Button` 语义（读屏会念出"按钮"）。写法与同一屏的
        // PlaybackErrorBanner（播放器失败条）保持一致。
        TextButton(
            onClick = onClick,
            colors = ButtonDefaults.textButtonColors(
                contentColor = Warning,
            ),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = "去开启",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
