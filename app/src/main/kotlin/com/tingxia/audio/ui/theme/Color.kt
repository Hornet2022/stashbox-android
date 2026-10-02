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

// 这里原本混着 Material 500 原色：StatusPending = #9E9E9E（灰）、
// StatusDistilling = #2196F3（蓝）。问题不在于「蓝代表进行中」这个直觉，
// 而在于这块蓝在暖赭 + 米白的版面里是唯一的冷色高饱和点 —— 一行状态徽标
// 里只要有一个「蒸馏中」，整行的注意力就被它拽走，而它的语义权重其实
// 最低（进行中是个常态，不是需要警觉的异常）。
// 改成品牌色板内的暖赭：进行中 = 品牌色，一眼可辨且不抢戏。
val StatusPending   = Color(0xFF8C8680)    // Neutral400
val StatusDistilling = WarmOchre
val StatusReady     = Color(0xFF6B8E7F)  // 改用 success 色
val StatusFailed    = Color(0xFFB87070)  // 改用 error 色
val StatusListened  = Color(0xFFA67B5B)  // 暖赭

// ─────────────────────────────────────────────────────────
// Semantic colors for SourceBadge
// ─────────────────────────────────────────────────────────

// 保留微信绿和抖音红：这两个是**平台识别色**，用户看到就知道内容从哪来，
// 换成品牌色反而丢信息。它们是全套色板里唯一允许的高饱和色。
val SourceWechat = Color(0xFF07C160)
val SourceDouyin = Color(0xFFFE2C55)

// ─────────────────────────────────────────────────────────
// Semantic colors for NotificationCenterScreen
// ─────────────────────────────────────────────────────────

// 原本是 #EEF3FF（冷蓝白）和 #F5F5F5（冷灰白）—— 在米白底 #F5F2EB 上，
// 这两块底色会显出蓝/绿的偏色，一张列表里能看出「有的行偏冷」，
// 像图层没对齐。改用品牌中性刻度。
val NotificationUnreadBackground = Color(0xFFF0EBE1)  // 米白加深一档
val NotificationReadBackground = Cream
val NotificationUnreadDot = WarmOchre

// ─────────────────────────────────────────────────────────
// Semantic colors for tag chips
// ─────────────────────────────────────────────────────────

// 原来六个里有四个是 Material 500 原色（#2196F3 蓝 / #9C27B0 紫 /
// #E91E63 粉 / #FF5722 橙），在低饱和的暖中性版面里像贴上去的色块。
//
// 但**不能**简单地把它们全压暗来配文字色：chip 是 12px 文字压在同色 15%
// 底上，文字色一旦压到 4.5:1，六个色相就全部塌成近黑，分类信息反而没了
// （实测把彩度压到最暗达标时，六色两两 ΔE 只剩 4.7，全部挤在墨色区间）。
//
// 所以分工换掉：**色相只负责底色 tint，文字一律用高对比中性色**。
// 这样「柔和」和「可读」不再互相打架 —— 底色给 15% alpha 的色彩暗示，
// 文字给 onSurface 级别的对比度，任何一版色板都不会失效。
// 见 NotificationCenterScreen.kt::TagChip。
val TagTech          = Color(0xFF5B7C99)  // 雾蓝
val TagScience       = Color(0xFF6B8E7F)  // 鼠尾草绿
val TagHistory       = Color(0xFF8C8680)  // 暖石灰
val TagFinance       = Color(0xFF8A6E8F)  // 灰紫
val TagSports        = Color(0xFFB87070)  // 陶红
val TagEntertainment = Color(0xFFC77B4A)  // 琥珀
val TagDefault       = Color(0xFF8C8680)
