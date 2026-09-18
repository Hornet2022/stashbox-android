package com.tingxia.audio.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────
// 听匣 Brand Colors
// 安静 / 留白 / 印刷感 / 工具感
// ─────────────────────────────────────────────────────────

/** 墨黑 — 主文字、主色调 */
val Ink = Color(0xFF1A1A1A)

/** 米白 — 浅色背景 */
val Cream = Color(0xFFF5F2EB)

/** 暖赭 — 主高亮色 */
val WarmOchre = Color(0xFFA67B5B)

// ─────────────────────────────────────────────────────────
// Warm Neutral Scale
// ─────────────────────────────────────────────────────────

val Neutral50  = Color(0xFFF5F2EB)
val Neutral100 = Color(0xFFE8E4DD)
val Neutral200 = Color(0xFFD4CFC6)
val Neutral300 = Color(0xFFB8B2A8)
val Neutral400 = Color(0xFF8C8680)
val Neutral500 = Color(0xFF6A665F)
val Neutral600 = Color(0xFF4A4642)
val Neutral700 = Color(0xFF353129)
val Neutral800 = Color(0xFF242019)
val Neutral900 = Color(0xFF1A1614)

// ─────────────────────────────────────────────────────────
// Semantic Colors (low saturation)
// ─────────────────────────────────────────────────────────

val Success = Color(0xFF6B8E7F)
val Warning = Color(0xFFC4956A)
val Error   = Color(0xFFB87070)

// ─────────────────────────────────────────────────────────
// Dark Mode
// ─────────────────────────────────────────────────────────

val DarkBg      = Color(0xFF1A1614)   // 暖深色背景（不是纯黑）
val DarkSurface = Color(0xFF242019)
val DarkBorder  = Color(0xFF3A352E)
val DarkText    = Color(0xFFE8E4DD)
val DarkMuted   = Color(0xFF8C8680)

// ─────────────────────────────────────────────────────────
// Semantic colors for DistillStatus badges
// ─────────────────────────────────────────────────────────

val StatusPending   = Color(0xFF9E9E9E)
val StatusDistilling = Color(0xFF2196F3)
val StatusReady     = Color(0xFF6B8E7F)  // 改用 success 色
val StatusFailed    = Color(0xFFB87070)  // 改用 error 色
val StatusListened  = Color(0xFFA67B5B)  // 暖赭

// ─────────────────────────────────────────────────────────
// Semantic colors for SourceBadge
// ─────────────────────────────────────────────────────────

val SourceWechat = Color(0xFF07C160)
val SourceDouyin = Color(0xFFFE2C55)

// ─────────────────────────────────────────────────────────
// Semantic colors for NotificationCenterScreen
// ─────────────────────────────────────────────────────────

val NotificationUnreadBackground = Color(0xFFEEF3FF)
val NotificationReadBackground = Color(0xFFF5F5F5)
val NotificationUnreadDot = Color(0xFF2196F3)

// ─────────────────────────────────────────────────────────
// Semantic colors for tag chips (低饱和度版本)
// ─────────────────────────────────────────────────────────

val TagTech        = Color(0xFF2196F3)
val TagScience     = Color(0xFF6B8E7F)
val TagHistory     = Color(0xFFC4956A)
val TagFinance     = Color(0xFF9C27B0)
val TagSports      = Color(0xFFE91E63)
val TagEntertainment = Color(0xFFFF5722)
val TagDefault     = Color(0xFF8C8680)
