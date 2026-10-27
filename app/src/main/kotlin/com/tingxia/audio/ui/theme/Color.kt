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

// ── 警告（配额快用完）容器色对（2026-10-03 新增）────────────────────
// QuotaBanner 的「配额 ≤10%」分支原来直接写 Color(0xFFFFE0B2) /
// Color(0xFFE65100)，那是 Material 200 浅橙 / 900 深橙，一对**只适配浅色模式**
// 的常量。深色模式下：底色是一块亮橙，文字继承 onSurface（近白 #EFE9E1），
// 对比度约 1.1:1 —— 提示条在深色里等于消失。
// 语义色要有浅深两套，标量做不到，所以这里给成对定义，由主题挑。
/** 浅色模式下的警告容器底色：比 200 略深，13sp 正文能压住 */
val WarningContainerLight = Color(0xFFF5DFC2)
/** 浅色模式下的警告文字/图标色 */
val OnWarningContainerLight = Color(0xFF6B4A2A)
/** 深色模式下的警告容器底色：暖赭压暗，不是纯色提亮 */
val WarningContainerDark = Color(0xFF3D2F1F)
/** 深色模式下的警告文字/图标色 */
val OnWarningContainerDark = Color(0xFFE8CFAE)
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
// 浅色主题的色调阶（2026-10-27，Material 3 指南 R1.10 / R6.7）
// ─────────────────────────────────────────────────────────
//
// ## 为什么多了一组「浅色专用」的品牌色
//
// 原来浅色主题的 `primary` 直接用了品牌暖赭 #A67B5B。实测对比度：
//
//     #A67B5B 压 #F5F2EB = 3.35:1      （正文需 ≥4.5:1）
//
// 而 `primary` 在这个 App 里是**全 App 主按钮的底色**（剪藏/蒸馏/反馈提交…），
// 也是正文里的强调色 ⇒ 3.35:1 意味着**每一个主按钮上的文字都不达标**，
// 而且它同时是 App 的品牌主色 —— 一处改，全 App 受益。
//
// ## 怎么改的（不是随手挑的）
//
// 按 R1.10「色板要从种子色生成，不要手挑单档」：以 #A67B5B 为种子，
// 在 **OKLCH** 空间生成等感知色阶（保留色相 H≈57.7°），取 tone 45 作为
// 浅色主题的 primary。OKLCH 的好处是**等亮度步进**（HSL 不是 —— HSL 的
// "lightness" 在暖色上会明显跳变），所以整条色阶看起来是同一个颜色家族。
//
// 同样的方法用于 error（种子 #B87070，H≈20°，取 tone 40）。
//
// ## 契约
//
// 这些配对比度**不是估计值，是被单元测试钉住的**
// （见 app/src/test/kotlin/com/tingxia/audio/ui/theme/ThemeContrastTest.kt）：
// 改色板而忘了重测 → 测试直接红。
//
// 浅色主题：
//   primary  #734B2C ↔ onPrimary #F5F2EB = 6.78:1
//   primary  #734B2C ↔ surfaceContainerHigh #D4CFC6 = 4.88:1
//   error    #713033 ↔ surfaceContainerHigh #D4CFC6 = 6.23:1
//   onErrorContainer #410E0B ↔ errorContainer #F9DEDC = 12.77:1
//   inversePrimary #E1B391 ↔ inverseSurface #242019 = 8.53:1

/**
 * 浅色主题的 primary（品牌暖赭 tone 47）—— 全 App 主按钮底色。
 *
 * ## 2026-10-27：从 #734B2C 微调到 #795031
 *
 * 用户反馈「浅色主题主色被压深了」。这轮没有直接回退到品牌色
 * #A67B5B —— 实测那条路是**回退 WCAG 缺陷**（见下），不是解法。
 *
 * ### 为什么 primary 提亮空间这么小（拆解结论）
 *
 * primary 在这个 App 里身兼三职：
 *   ① **主按钮底色**（onPrimary 压它）—— 全 App 5+ 个主按钮
 *   ② **页面正文色**（它压 surface/background #F5F2EB）
 *   ③ **弹窗/Sheet 上的强调色**（它压 surfaceContainerHigh #D4CFC6 ——
 *      AlertDialog 的底，见 EvaluationDialog / ShareArticleDialog；
 *      ModalBottomSheet 见 PlaybackSpeedSheet / VoicePickerSheet）
 *
 * 三职叠加后，**最紧的一条是 ③**：
 *
 *   #A67B5B（品牌色）压 surfaceContainerHigh #D4CFC6 = **2.42:1** ✗
 *   #A67B5B 压 surface #F5F2EB                = **3.35:1** ✗
 *   #A67B5B 压 onPrimary #F5F2EB（按钮文字）  = **3.35:1** ✗
 *
 * ⇒ 回退品牌色会让「每个主按钮上的文字」「弹窗里的选中文字」全部不达标。
 *
 * 而在**全部现有 WCAG 约束下**，primary 的提亮上限就是 #795031
 * （压 surfaceContainerHigh = 4.51:1，刚好过 4.5 的线）。
 * 所以这轮**没有把「主色变亮」当成主要手段**，而是：
 *
 *   · primary：#734B2C → **#795031**（技术上限，肉眼约 2% 亮度差）
 *   · primaryContainer：中性灰 Neutral100 → **品牌暖赭浅色档**（见下）
 *
 * ⚠️ 4.51 贴着 4.5 的线是**故意**留的：它由 ThemeContrastTest 钉住，
 * 将来谁动色板而忘了重测，测试直接红。数值见本常量注释与测试。
 */
val PrimaryLight = Color(0xFF795031)

/**
 * 浅色主题的 primaryContainer —— 品牌暖赭的**浅色档**。
 *
 * ## 为什么单独加一档（2026-10-27）
 *
 * 原来 primaryContainer 直接用中性刻度 Neutral100(#E8E4DD)，也就是说
 * **所有"品牌色容器"其实都是灰的** —— 主色那一侧的暖赭只出现在
 * 文字/图标上，没有一处"底"是品牌色。
 *
 * Material 3 的语义里 primaryContainer 是 primary 的低强调版容器
 * （相当于 tone 90 档），本该带同一色相。改成品牌暖赭浅色档后：
 *
 *   · 技能卡片（HomeScreen 的 6 张大卡）底色从冷灰变成暖赭，
 *     首页一眼就有品牌温度 —— 这是「浅色主题太素」的观感来源
 *   · 标签筛选条（ArticleListScreen 的 ActiveTagChip）同理
 *
 * ### 配对数值（全部由 ThemeContrastTest 钉住）
 *
 *   #EBD9C8 压 onPrimaryContainer #1A1A1A = **12.67:1** ✓（正文）
 *   #795031 压 #EBD9C8（技能卡图标，图形） = **5.09:1** ✓（≥3:1）
 *   #EBD9C8@30% 合成到对话框底 #D4CFC6 = #DAD1C6，
 *     压 onSurface #1A1A1A = **11.53:1** ✓（EvaluationDialog 的总评块）
 */
val PrimaryContainerLight = Color(0xFFEBD9C8)

/** 浅色主题的 error（品牌陶红 tone 40）—— 破坏性操作与错误态。 */
val ErrorLight = Color(0xFF713033)

/** 浅色主题的 errorContainer（tone 90，不透明）。 */
val ErrorContainerLight = Color(0xFFF9DEDC)

/** 浅色主题的 onErrorContainer（tone 30）—— 压在 errorContainer 上的文字。 */
val OnErrorContainerLight = Color(0xFF410E0B)

/** 浅色主题的 inversePrimary（Snackbar 等反色容器上的强调色，tone 80）。 */
val InversePrimaryLight = Color(0xFFE1B391)

/** 深色主题的 onErrorContainer（浅色 tone 80）—— 压在深色 errorContainer 上。 */
val OnErrorContainerDark = Color(0xFFF2B8B5)

/** 深色主题的 errorContainer（不透明的暗红）—— 故意不用半透明。 */
val ErrorContainerDark = Color(0xFF3E2426)

/**
 * 深色主题的 primary（品牌暖赭 tone 70）。
 *
 * 为什么深色侧要比浅色侧亮不少：深色主题里 `primary` **既当按钮底色、又当正文色**
 * —— 而 Material 3 的对话框/Sheet 底是 `surfaceContainerHigh`（深色下是 Neutral700
 * #353129，比页面底亮一档），对话框里的「取消」按钮默认就是 primary 色。
 * ⇒ primary 必须同时压得住 surface / background / surfaceContainerHigh 三种底。
 *
 *   #C99A76 压 #242019(surface)          = 6.46:1  ✓
 *   #C99A76 压 #1A1614(background)       = 7.16:1  ✓
 *   #C99A76 压 #353129(surfaceContainerHigh) = 5.16:1  ✓
 *   onPrimary #3A2110 压 #C99A76          = 6.4:1   ✓
 *
 * （原来的 #A67B5B 压 surfaceContainerHigh 只有 3.70:1 —— 差得不少。）
 */
val PrimaryDark = Color(0xFFC99A76)

/** 深色主题的 onPrimary（tone 30，暖深棕）—— 压在浅色 primary 上。 */
val OnPrimaryDark = Color(0xFF3A2110)

/**
 * 深色主题的 error（品牌陶红 tone 75）。
 *
 * 原值 #B87070 压深色 surface 只有 4.31:1（正文需 4.5）。
 *   #CE8A8A 压 #242019(surface)             = 5.89:1 ✓
 *   #CE8A8A 压 #1A1614(background)          = 6.53:1 ✓
 *   #CE8A8A 压 #353129(surfaceContainerHigh)= 4.70:1 ✓
 *   onError #1A1614 压 #CE8A8A               = 6.53:1 ✓
 *
 * ⚠️ 压 surfaceContainerHighest（#4A4642）只有 3.40:1 —— 这是**结构性的**：
 * 深色主题里最亮的那档容器本身就亮，而红到能压住它的程度就不再像"错误色"了。
 * 该配对在 App 里不存在（错误文字不会出现在最高层容器上），
 * 所以对比度测试把它归到**非文本**档（≥3:1），而不是逼色板变歪。
 */
val ErrorDark = Color(0xFFCE8A8A)

// ─────────────────────────────────────────────────────────
// 通知中心的深色底色（2026-10-27，P0）
// ─────────────────────────────────────────────────────────
//
// ## 原来的问题
//
// 通知卡片底色用的是**浅色专用**常量（`NotificationUnreadBackground` #F0EBE1 /
// `NotificationReadBackground` Cream），而卡片里的文字取的是 `colorScheme.onSurface`
// / `onSurfaceVariant` —— 深色模式下那是**近白**色。
//
// 结果：深色模式的通知屏 = 一块块**亮奶油色**卡片浮在暗底上，文字近白压亮底，
// 实测对比度 1.07:1 / 1.13:1 ⇒ **整屏通知在深色模式下等于消失**。
//
// ## 为什么之前没被发现
//
// 这个仓里 Color.kt 的注释早就写下了约定：「语义色要有浅深两套，标量做不到」，
// QuotaBanner 也是照这个做的 —— 只是通知中心没照做。
//
// ⇒ 这里补齐深色版（与浅色版一一对应），通知卡片按当前主题挑。

/** 深色模式：未读通知卡片底色（比已读略亮，一眼能分出未读）。 */
val NotificationUnreadBackgroundDark = Color(0xFF2E2823)

/** 深色模式：已读通知卡片底色（等于深色 surface）。 */
val NotificationReadBackgroundDark = Color(0xFF242019)

/**
 * 深色模式：通知卡**正文**色（标题之下那一档）。
 *
 * ## 为什么需要这一档
 *
 * 深色通知卡里标题与正文曾经**同为 `onSurface`(#E8E4DD)** —— 层级只能靠
 * 字重/字号承载，看着像两行同等的文字。
 *
 * 而把正文换成 `onSurfaceVariant`(#8C8680) 也不行：它压**未读**卡底
 * (#2E2823) 只有 **4.04:1**（正文需 ≥4.5:1，差一点点也是不达标）。
 * 根因是未读卡底比已读卡底**亮一档**（#2E2823 vs #242019），
 * onSurfaceVariant 压已读底刚好 4.50:1 达标、压未读底就不够了。
 *
 * ⇒ 于是取「比 onSurfaceVariant 略亮、比 onSurface 暗」的一档：**#948F88**。
 *
 *   #948F88 压未读卡底 #2E2823 = **4.53:1** ✓
 *   #948F88 压已读卡底 #242019 = **5.05:1** ✓
 *   #948F88 与标题 onSurface #E8E4DD 明度差 = 2.53:1（层级回来了）
 *
 * 这些数值被 ThemeContrastTest 钉住 —— 改色而忘了重测，测试直接红。
 */
val NotificationBodyDark = Color(0xFF948F88)

// ─────────────────────────────────────────────────────────
// 全屏播放器的本地深色配色（2026-10-27）
// ─────────────────────────────────────────────────────────
//
// 播放器是**独立的深色界面**（不跟随全局明暗）—— 那是产品决定，
// 但它自己的 4 个深棕原先是散在 FullScreenPlayerScreen.kt 里的字面量 hex，
// 无法统一调整（R1.2）。收进调色板后，一处可改。
/** 播放器背景顶部。 */
val PlayerBgTop = Color(0xFF3A2F26)

/** 播放器背景中部。 */
val PlayerBgMid = Color(0xFF5C4A3A)

/** 播放器背景底部。 */
val PlayerBgBottom = Color(0xFF2E251E)

/** 播放器弹窗（sheet）底色。 */
val PlayerSheetBg = Color(0xFF2E251E)

/** 播放器次级文字/弱分隔色。 */
val PlayerMuted = Color(0xFF7A6650)

/**
 * 播放器里选中态的白色叠加层（12% / 16%）。
 *
 * 命名而不是写 `Color(0xFFFFFFFF)`：R1.2 要求组件里不出现裸 hex，
 * 而这里出现 4 次。
 */
val PlayerHighlight = Color(0xFFFFFFFF)

/**
 * 播放器播放失败条的错误底（20% 红，压在 PlayerBgTop 上）。
 *
 * 原来是 Material 的 `Color(0x33FF5252)` + `0xFFFFE0E0`/`0xFFFFCDD2` ——
 * 一对 Material 200/100 错误色，**只适配浅色底**，和 App 调色板里的
 * `Error`(#B87070) 也不是一套，两处都在用、看着像两套系统。
 *
 * 现在统一到 App 的错误色家族：底是 ErrorDark 的 20% 叠加，
 * 字用浅粉红（压合成底 5.98:1）。
 */
val PlayerErrorBg = Color(0x33CE8A8A)

/** 播放器失败条的文字色（压 PlayerErrorBg 合成底 = 5.98:1）。 */
val PlayerErrorText = Color(0xFFF5C2BF)

/**
 * 播放器失败条的描边色。
 *
 * 原来是裸 `Color(0x66FF5252)`（40% 纯红）—— 和 App 的错误色家族
 * (`ErrorDark` #CE8A8A) 不是一套色，两处并排看着像两套系统。
 * 现在取 ErrorDark 的 40% 叠加，与底色/文字色同族。
 */
val PlayerErrorBorder = Color(0x66CE8A8A)

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
