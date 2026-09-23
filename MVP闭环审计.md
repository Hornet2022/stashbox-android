# 听匣 Android 客户端 · MVP 闭环审计

> 审计日期：2026-09-22
> 审计范围：`stashbox-android/` 全部 Kotlin 源码（UI / ViewModel / Repository / API / audio）
> 方法：逐文件通读 + 关键链路端到端追踪（UI → ViewModel → Repository → Retrofit API → 后端端点）
> 结论：**尚未达到可交付 MVP**，但后端集成层骨架是真实可用的，问题集中在 3 处硬伤（播放架构 / 蒸馏假进度 / 登录身份伪造）+ 若干半闭环项。

---

## 一、功能闭环状态矩阵

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 1 | 剪藏 Capture | ✅ CLOSED-LOOP | 真实 POST `/api/v1/articles` + 配额拦截；D9 分享为真实请求 |
| 2 | 回听 Later-listens | ✅ CLOSED-LOOP | snooze/list/unsnooze 全部真实 |
| 3 | 收藏 Favorites | ✅ CLOSED-LOOP | 增删查 + 分文件夹真实 |
| 4 | 反馈 Feedback | ✅ CLOSED-LOOP | 评分+文字提交 + 历史真实 |
| 5 | 通知 Notifications | ✅ CLOSED-LOOP | 列表 + 标记已读 + deeplink 跳转真实 |
| 6 | 首页 Home | ✅ CLOSED-LOOP | 文章列表 + 跳转真实 |
| 7 | 配额 Quota | ✅ CLOSED-LOOP | GET 真实，驱动剪藏/付费墙 |
| 8 | 蒸馏 Distill | ⚠️ PARTIAL | 触发真实，但进度是**伪造的 30s 倒计时**，从不回查真实状态 |
| 9 | 订阅 Subscribe | ⚠️ PARTIAL | 订阅真实，但无"已订阅列表"拉取 → 取消订阅不可达 |
| 10 | 收听 Playback | ⚠️ PARTIAL | 声音能放，但**锁屏/通知/后台全断链**（双 ExoPlayer 架构缺陷） |
| 11 | Onboarding | ⚠️ PARTIAL | UI 真实，后端埋点被 `runCatching` 软失败吞掉（等同 no-op） |
| 12 | 账号 Auth | 🔴 MOCK | 登录走 dev-only 端点 + **固定 user_id="6892"**，微信 OAuth 未接 |
| 13 | 付费墙 Paywall | 🔴 MOCK | `mockUpgrade()` 仅 `delay(800)`，无任何支付集成 |
| 14 | 离线下载 Offline | 🔴 STUB | 按钮 no-op，`isCached()` 永远 false |

---

## 二、P0 阻断项（不修则核心体验不可用）

### P0-1 · 播放器双实例架构断裂（最严重）
- `PlayerController.kt:68` 持有**自己的 ExoPlayer** 真正出声（`play(audioUrl)` 在 162-174 行 `setMediaItem+prepare+play`）。
- `AudioPlayerService.kt:54` 在 `MediaSessionService` 里 **new 了第二个独立 ExoPlayer** 并绑到 `MediaSession`（60 行），但**从未 `setMediaItem`**——它是空播放器。
- 后果：系统锁屏/通知/MediaStyle 控制绑定的是**空播放器**；真正出声的 PlayerController 不是 MediaSession，后台易被系统回收、无锁屏可控。核心"听"体验在真机上形同残废。
- 修复方向：全 App 仅保留**一个** ExoPlayer，由 `AudioPlayerService`（前台 MediaSessionService）持有并暴露给 UI（PlayerController 改为引用 service 的 player 或直接合并）。

### P0-2 · 蒸馏进度是假倒计时（用户信任崩塌）
- `DistillViewModel.kt:118-141` `startCountdown`：固定 `COUNTDOWN_SECONDS=30` 每秒递减，**到 0 无条件**把文章从列表移除 + Toast "蒸馏完成"。
- 全程**不调用** `ArticleApi.getDistillStatus` 回查后端真实 `ready/failed`（`getDistillStatus` 接口已存在却未接进流程）。
- 后果：后端 25s 失败，UI 30s 仍报"完成"；用户以为有音频实际没有。
- 修复方向：倒计时结束改为**轮询 `getDistillStatus`**，仅当后端 `ready` 才移除并提示，失败则报错并保留重试入口。

### P0-3 · 登录身份伪造（user_id 写死 6892）
- `AuthRepository.kt:41` `mockWechatLogin()` 向 dev-only 端点发 **固定 `user_id="6892"`**；`AuthApi.wechatLogin`（微信 OAuth）注释明说当前 500，留 TODO 切回。
- 后果：所有登录用户都是同一测试账号，配额/收藏/文章归属全压在一个 ID 上；多用户、真实微信登录完全未接。
- 修复方向（MVP 内/外视范围而定）：至少接 `wechat-login` 真端点；MVP-alpha 可接受 mock，但**不应写死 6892**，应允许任意 user_id 透传以便联调多账号。

---

## 三、P1 半闭环项（体验/正确性缺口）

| 项 | 位置 | 缺口 |
|---|------|------|
| 订阅取消不可达 | `TagSubscriptionViewModel.kt:18,53` | 无"GET 我的订阅"接口，`subscribedIds` 永不加载 → `isSubscribed` 恒 false → unsubscribe 走不到，已订阅会被重复订阅 |
| 全屏播放恢复是坏的 | `FullScreenPlayerScreen.kt:216` | 暂停后点播放传**空 audioUrl** → 新建空 MediaItem，不恢复当前曲目 |
| 全屏标题写死 | `MainActivity.kt:251,332` | 两处路由传 `title="听匣 · 当前播放"`、author/cover 为 null，不读真实 metadata |
| 迷你播放条不常驻 | `ArticleDetailScreen.kt:180` | `AudioPlayerBar` 只在详情页 bottomBar，回 Home 后无播放控件 |
| 迷你条恢复是重启 | `AudioPlayerBar.kt:136` | `controller.play(it)` 重新 setMediaItem → 从头开始非 resume |
| D9 分享硬编码真机 IP | `D9Receiver.kt:36` | `BASE_URL="http://172.16.5.28:8100/"`，模拟器不可用；且 `handleD9Intent` 仅 `Log.i` 不导航不刷新（fire-and-forget） |
| Onboarding 后端调用被吞 | `OnboardingViewModel` | `runCatching` 软失败仍推进 UI，埋点等同 no-op |
| Favorites/LaterListens 显示原始 id | `FavoritesScreen.kt:243` / `LaterListensScreen.kt:143` | 列表项显示 `article_id` 原始串而非标题（缺文章标题补全） |
| 空脚手架模块 | `feature/home`、`feature/profile`、`feature/player` | 仅含 `*Marker.kt` 空标记文件，无任何业务代码且未被引用 |

---

## 四、P2 Mock 项（MVP 可接受范围，需标注）

- **付费墙升级**：`PaywallViewModel.mockUpgrade()` 纯前端假动作。MVP 不做商业化，可接受，但需明确"升级"为占位。
- **Auth mock 端点**：dev-only token 端点。内部 alpha 可用，外部用户前必须接真微信 OAuth（同 P0-3）。
- **OfflineDownloadManager 透明缓存**：ExoPlayer 首次播放经 CacheDataSource LRU 缓存是真实的；"主动下载按钮"未做属 P1 stub。

---

## 五、MVP 最小闭环定义（建议基线）

要算"能交付的 MVP"，至少需满足：
1. **真实收听**：一个 ExoPlayer + MediaSession，锁屏/通知/后台可控，能从详情页/迷你条/全屏一致控制同一曲目（修 P0-1 + P1 全屏/迷你条）。
2. **真实蒸馏闭环**：触发→轮询状态→ready 才提示完成，失败可重试（修 P0-2）。
3. **真实身份**（至少联调级）：可切换/透传 user_id，配额与内容归属正确（修 P0-3 或至少去写死）。
4. **内容闭环**：剪藏→蒸馏→收听→反馈→回听，5 项已 CLOSED-LOOP，无需重做。

> 已闭环的 7 项（剪藏/回听/收藏/反馈/通知/首页/配额）证明**后端 API 契约与 Repository 层是真实可用的**，问题集中在 ViewModel 伪造逻辑 + 播放架构断裂 + 登录身份伪造三处，修复面可控。

---

## 六、下一步建议（待确认）

进入 superpowers「Implementation Planning」阶段，按 P0 → P1 优先级拆分可验证任务（每个含 file:line、改动、验证步骤）。建议顺序：
1. **P0-1 播放架构合并**（影响最大，唯一真"听"通路）
2. **P0-2 蒸馏真轮询**
3. **P0-3 登录去写死 / 接 wechat-login**
4. **P1 订阅取消 + 全屏/迷你条恢复 + D9 + 标题补全**
5. **P2 付费墙/离线下载占位说明**

是否按此顺序产出详细 plan.md？或您先指定本次要收口的功能范围（例如"本轮只修播放 + 蒸馏"）。
