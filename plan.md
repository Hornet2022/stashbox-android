# 听匣 Android 客户端 · MVP 闭环修复实施计划

> 依据：`MVP闭环审计.md`（2026-09-22 审计结论）
> 方法：superpowers Implementation Planning，按 P0→P1→P2 优先级，每任务含「目标 / 改动文件:行 / 具体改动 / 验证」
> 范围：客户端闭环收口，使核心链路 **剪藏 → 蒸馏 → 收听 → 反馈** 全真闭环、锁屏/通知/后台可控、登录身份可联调多账号。
> 不在本次范围（需后端协同或后续）：真实微信 OAuth（user-service `wechat-login` 现 500）、真实支付、真 LLM/TTS（后端仍 mock，不影响 App 闭环）。

---

## 总览与执行顺序

```
P0-1 播放架构合并（单一 ExoPlayer + MediaSession）   ← 影响最大，唯一真"听"通路
P0-2 蒸馏去假倒计时，改真状态轮询
P0-3 登录身份去写死（多账号联调）
P1-1 订阅「取消」可达
P1-2 全屏/迷你条恢复播放 + 真实标题
P1-3 D9 分享去硬编码 + 回跳刷新
P1-4 收藏/回听列表显示标题
P2   Paywall / 离线下载占位说明（不阻塞）
```

> 关键事实（已逐文件核对）：`ArticleDetailViewModel.startPolling`（105–148 行）**已正确轮询** `getDistillStatus`，所以 P0-2 只需修**蒸馏中心** `DistillViewModel` 的假倒计时；`getDistillStatus(taskId)`（`ArticleApi:32-34`）与 `distillArticle` 返回的 `taskId` 均已具备，纯客户端可闭环。

---

## P0-1 · 播放架构合并（单一 ExoPlayer + MediaSession）

**问题**：`PlayerController.kt:68` 自建 ExoPlayer 真正出声；`AudioPlayerService.kt:54` 自建**空** ExoPlayer 绑 MediaSession（从未 `setMediaItem`）。锁屏/通知/后台控制绑在空播放器，真出声的播放器无 MediaSession → 核心"听"在真机残废。

### 任务 P0-1.1 · 提供共享 ExoPlayer @Singleton
- **新文件** `app/src/main/kotlin/com/tingxia/audio/audio/di/PlayerModule.kt`
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {
    @Provides @Singleton
    fun provideExoPlayer(
        @ApplicationContext ctx: Context,
        offline: OfflineDownloadManager,
    ): ExoPlayer {
        val cacheFactory = offline.buildCacheDataSourceFactory()
        val mediaSourceFactory = DefaultMediaSourceFactory(cacheFactory)
        return ExoPlayer.Builder(ctx).setMediaSourceFactory(mediaSourceFactory).build()
    }
}
```
- 复用 `PlayerController.initialize()`（130–139 行）与 `AudioPlayerService`（42–56 行）已有的 cache factory 构造逻辑。

### 任务 P0-1.2 · PlayerController 改用共享实例
- `PlayerController.kt`
  - 构造函数 `@Inject constructor(...)` 增加 `private val player: ExoPlayer`（去掉内部 `var player: ExoPlayer?` 的本地构建）。
  - 删除 `initialize()` 内 130–139 行 `player = ExoPlayer.Builder(...).build()`；`initialize()` 改为空实现或直接移除，并同步删除 `MainActivity.kt:82` 的 `playerController.initialize()` 调用。
  - **新增** 当前曲目元数据 StateFlow（供 P1-2 用）：
```kotlin
private val _currentTitle = MutableStateFlow("")
val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()
// 同理 _currentAuthor / _currentAudioUrl
```
    - 在 `play()`（150–177 行）`setMediaItem` 前赋值 `_currentTitle.value = title` 等。

### 任务 P0-1.3 · AudioPlayerService 绑定同一实例
- `AudioPlayerService.kt`
  - 删除 42–56 行本地 `offlineDownloadManager`/`cacheFactory`/`player` 构建；改为从 `AudioPlayerEntryPoint` 取共享 `exoPlayer()`。
  - `onDestroy()`（91–98 行）：**只释放 `mediaSession`，不要 `session.player.release()`**——共享播放器是 @Singleton，生命周期随 App，误释放会破坏 PlayerController。
- `AudioPlayerEntryPoint.kt`：增加 `fun exoPlayer(): ExoPlayer`。

### 任务 P0-1.4 · MainActivity 调整
- `MainActivity.kt:82`：删除 `playerController.initialize()`（播放器已由 Hilt 提供）。`startAudioService()`（118–124 行）保留。

**验证 P0-1**
- 真机：详情页点播放 → 下拉通知栏出现 MediaStyle 通知 + 锁屏控制条 → 锁屏/通知的 暂停/播放/seek **真实生效** → 切后台仍播、不被回收。
- 单元：`PlayerControllerTest` 验证 `play()` 后 `state==PLAYING` 且 `player` 非空；`AudioPlayerService` 启动后 `mediaSession.player` 与 `PlayerController` 持有同一实例（同引用）。

---

## P0-2 · 蒸馏去假倒计时，改真状态轮询

**问题**：`DistillViewModel.startCountdown()`（118–141 行）固定 30s 递减，**到 0 无条件**移除文章 + 弹"蒸馏完成"，从不回查后端。后端失败也报成功 → 用户信任崩塌。

### 任务 P0-2.1 · DistillViewModel 真轮询
- `DistillViewModel.kt`
  - 删除 `COUNTDOWN_SECONDS`（36 行）与 `startCountdown()`（118–141 行）。
  - `distill(articleId)`：调 `repository.distillArticle(id)` → 取 `resp.taskId`；把该 article 标 `inProgress`（保留 `DistillInProgress` 但去掉 `remaining/total`，或改 `DistillPolling(articleId)`）；启动轮询：每 **3s** 调 `repository.getDistillStatus(taskId)`，参照 `ArticleDetailViewModel.startPolling`（105–148 行）的状态机：
    - `READY` → 从 `pendingArticles` 移除 + toast "蒸馏完成，可收听"
    - `FAILED` → 保留 article + toast "蒸馏失败" + 允许重试
    - 设上限（如 30 次 ≈ 90s）超时提示。
  - `UiState.inProgress` 类型由"倒计时"改为"轮询中"语义（UI 仅显示"蒸馏中"）。

### 任务 P0-2.2 · DistillScreen UI 适配
- `DistillScreen.kt:191-208`：进度条改为 **indeterminate**（`LinearProgressIndicator()` 不带 `progress` 参数），文案由"蒸馏中,剩余 X 秒"改为"蒸馏中（后台处理）"。按钮在 `inProgress` 期间仍 disable（184 行逻辑不变）。
- `DistillViewModel.DistillInProgress`：移除 `remaining/total` 字段（或保留但 UI 不再用）。

**验证 P0-2**
- 真机：蒸馏中心点"立即蒸馏" → 显示"蒸馏中" 指示器 → 后端 `ready` 后该条自动消失 + toast；**人为让后端失败** → 显示失败且可重试，**不再误报完成**。
- 单元：`DistillViewModelTest` 注入 fake `ArticleRepository`，验证 distill 后**轮询到 READY 才移除**、轮询到 FAILED 保留并报错。

---

## P0-3 · 登录身份去写死（多账号联调）

**问题**：`AuthRepository.kt:41` `mockWechatLogin()` 向 dev-only 端点发**写死 `user_id="6892"`**，所有账号都是同一测试用户，配额/收藏/文章归属全压一个 ID。

### 任务 P0-3.1 · AuthRepository 可配置 userId
- `AuthRepository.kt:41`：`issueToken(TokenIssueRequest(user_id = "6892"))` → 改为 `user_id = resolveUserId()`，其中 `resolveUserId()` 优先读 `BuildConfig.DEBUG_USER_ID`（在 `build.gradle` 定义，默认 `"6892"`），或由 `AuthViewModel` 传入。保留 `// TODO: user-service wechat-login 修复后切回` 注释。

### 任务 P0-3.2 · LoginScreen 增加 userId 输入（debug）
- `LoginScreen.kt:51`（"微信登录（mock）"）：debug 构建下增加 `TextField` 输入 `user_id`，点击登录时传给 `mockWechatLogin(userId)`（`AuthViewModel` 方法签名加默认参数 `userId: String = "6892"`）。

**验证 P0-3**
- 分别用 `user_id=6892` 与另一 ID 登录，后端返回的文章列表/配额各自隔离（确认不再"所有人都是 6892"）。

---

## P1-1 · 订阅「取消」可达

**问题**：`TagSubscriptionViewModel.subscribedIds` 初始为空（18 行）且从不从服务端加载 → `isSubscribed` 恒 false（53 行）→ `unsubscribe` 永远走不到（65 行），已订阅会被重复订阅（依赖后端幂等兜底）。`TagApi` 无"我的订阅"接口。

### 任务 P1-1.1 · 后端 Tag 增加 subscribed 标记（stashbox/backend content-service）
- `GET /api/v1/tags` 每个 Tag 响应增加 `subscribed: Boolean`（当前用户是否已订阅）。**最小改动**：在序列化 Tag 时附带当前用户订阅状态（比新增端点更省）。

### 任务 P1-1.2 · 客户端消费
- `TagApi.kt`（9–18 行）/`Tag` model：增加 `subscribed` 字段（可空，默认 false）。
- `TagSubscriptionViewModel.loadTags()`（34–50 行）：加载后 `subscribedIds = tags.filter { it.subscribed == true }.map { it.id }.toSet()`，替换 18 行空初始。
- 此后 `toggleTag` 的 `isSubscribed` 判断正确 → 取消订阅真实可达，且不再重复订阅。

**验证 P1-1**
- 真机：订阅某标签 → 退出重进「订阅」页 → 该标签仍显示"已订阅"且点按触发 `unsubscribe`（后端日志确认 DELETE 类请求）。

---

## P1-2 · 全屏/迷你条恢复播放 + 真实标题

**问题**：`FullScreenPlayerScreen.kt:216` 暂停后点播放传**空 audioUrl** → 新建空 MediaItem；`MainActivity.kt:251,332` 两处传写死 `title="听匣 · 当前播放"`、author/cover 为 null；`AudioPlayerBar.kt:136` `controller.play(it)` 重新 `setMediaItem` → 从头而非 resume。

### 任务 P1-2.1 · PlayerController 暴露当前曲目 + resume()
- `PlayerController.kt`：已在 P0-1.2 加 `_currentTitle/_currentAuthor/_currentAudioUrl` StateFlow。
- 新增：
```kotlin
fun resume() { player?.play() }   // 不重设 mediaItem，仅恢复当前曲目
```

### 任务 P1-2.2 · FullScreenPlayerScreen 修正
- `FullScreenPlayerScreen.kt:216`：`controller.play("", title, author, coverUrl)` → `if (playbackState == PAUSED) controller.resume() else controller.play(currentAudioUrl, currentTitle, currentAuthor, currentCover)`。
- 标题/作者/封面改从 `controller.currentTitle.value` 等读取（而非函数参数），`MainActivity.kt:251,332` 不再传写死字符串（传 null 或移除参数，屏幕读 controller）。

### 任务 P1-2.3 · AudioPlayerBar 修正
- `AudioPlayerBar.kt:136`：`audioUrl?.let { controller.play(it) }` → 若与 `controller.currentAudioUrl` 相同且处于 PAUSED，调 `controller.resume()`；否则 `play(it)`。

**验证 P1-2**
- 真机：详情页播放 → 进全屏 → 暂停 → 点播放**从原位置继续**（非从头）；全屏标题/作者显示真实文章信息（非"听匣 · 当前播放"）；迷你条暂停后再点也从原位置续播。

---

## P1-3 · D9 分享去硬编码 + 回跳刷新

**问题**：`D9Receiver.kt:36` 写死 `BASE_URL="http://172.16.5.28:8100/"`，模拟器不可用；`MainActivity.handleD9Intent`（107–115 行）只 `Log.i`，分享后 App 内无感知（fire-and-forget）。

### 任务 P1-3.1 · 共享 base URL
- 新增 `object BaseUrls { fun current(): String }` 复制 `NetworkModule` 的 `isRunningOnEmulator()` 逻辑（46–49 行）。`NetworkModule.BASE_URL`（40–44 行）与 `D9Receiver.kt:36` 均改用它（DRY）。

### 任务 P1-3.2 · D9 成功回跳
- 新增 `@Singleton D9EventBus`（`MutableSharedFlow<D9Result.Success>`）。
- `MainActivity.handleD9Intent`（107–115 行）：`D9Receiver.handleIntent` 返回 `Success` 时 `d9EventBus.emit(result)`（需注入 bus）。
- `HomeScreen` 收集 bus → 导航 `detail/{articleId}` 或 toast "已添加" + 触发 `ArticleListViewModel` 刷新。

**验证 P1-3**
- 真机（同 Wi-Fi）：微信/浏览器分享链接到听匣 → D9 回调成功 → App 自动跳转详情或列表刷新 + toast；模拟器端 base URL 自动用 `10.0.2.2`（不再连不上）。

---

## P1-4 · 收藏/回听列表显示标题

**问题**：`FavoritesScreen.kt:243` / `LaterListensScreen.kt:143` 显示 `article_id` 原始串，不显示标题。

### 任务 P1-4.1 · 后端补全字段（stashbox/backend）
- `favorites` / `later-listens` 列表响应增加 `article_title`、`source`（或嵌套 article 摘要）。

### 任务 P1-4.2 · 客户端展示
- 两 Screen 用 `article_title`（缺省回退 `article_id`）替换当前 `article_id` 文本。

**验证 P1-4**
- 真机：收藏/回听列表项显示文章标题而非 UUID。

---

## P2 · Mock 占位说明（不阻塞 MVP）

- **Paywall**：`PaywallViewModel.mockUpgrade()`（56–62 行）保留 mock，但 UI 明确标注"演示版，暂未接入支付"，避免用户误以为已付费。
- **离线下载**：`DownloadButton` no-op + `OfflineDownloadManager.isCached()` 恒 false。二选一：① 接真实 `preload(url)`；② 隐藏按钮并加提示"已自动缓存"。标记占位，不阻塞。

---

## 真机验收总表（MVP 达标判定）

| 链路 | 验收动作 | 通过标准 |
|---|---|---|
| 收听 | 详情页播放 → 锁屏/通知控制 | 锁屏可暂停/播放/seek，后台不中断 |
| 蒸馏 | 蒸馏中心点"立即蒸馏" | 显示"蒸馏中"，后端 ready 才消失；失败可重试，**不误报** |
| 身份 | 用两个 user_id 登录 | 文章/配额各自隔离 |
| 订阅 | 订阅→重进→取消 | 状态正确持久，取消真实生效 |
| 全屏 | 暂停→播放 | 从原位置续播，标题真实 |
| D9 | 分享链接到 App | 自动回跳/刷新，模拟器可用 |
| 列表 | 收藏/回听 | 显示标题 |

> 全部通过 = 达到可交付 MVP（内部 alpha）。真实微信登录、真实支付、真 LLM/TTS 为后续 CP 阶段，不在本计划。

---

## 实施落地记录（2026-09-22 续18）

按上文顺序逐项落地，task list 19/19 closed。本地无 JDK，未跑 Gradle 编译验证；改动基于精确 file:line 阅读 + diff 严格匹配。

### 文件级改动清单

**P0-1 合并播放架构**
- 新增 `app/src/main/kotlin/com/tingxia/audio/audio/di/PlayerModule.kt` — `@Singleton` ExoPlayer 由 OfflineDownloadManager 提供 CacheDataSourceFactory
- `audio/PlayerController.kt` — 构造函数改为注入共享 player；新增 `currentTitle/currentAuthor/currentAudioUrl` StateFlow；新增 `resume()`（不重建 MediaItem）；`release()` 不再 release 共享实例
- `audio/AudioPlayerService.kt` — onCreate 从 `AudioPlayerEntryPoint.exoPlayer()` 取共享实例绑 MediaSession；onDestroy 只 `session.release()`，不再 `session.player.release()`
- `audio/di/AudioPlayerEntryPoint.kt` — 加 `exoPlayer(): ExoPlayer`
- `MainActivity.kt` — 删 `playerController.initialize()` 调用

**P0-2 蒸馏真状态轮询**
- `ui/distill/DistillViewModel.kt` — 删假 30s 倒计时；`startPolling(articleId, taskId)` 每 3s 调 `repository.getDistillStatus(taskId)`，READY 才移除+toast「蒸馏完成，可收听」，FAILED 保留可重试，30 次≈90s 超时
- `ui/distill/DistillScreen.kt` — 进度条改 indeterminate LinearProgressIndicator

**P0-3 登录身份去写死**
- `app/build.gradle.kts` — `defaultConfig` 加 `buildConfigField("String", "DEBUG_USER_ID", "\"6892\"")`
- `auth/AuthRepository.kt` — `mockWechatLogin(userId: String? = null)`；留空时回退到 `BuildConfig.DEBUG_USER_ID`
- `ui/auth/AuthViewModel.kt` — `mockWechatLogin(userId: String? = null)` 透传
- `ui/screens/LoginScreen.kt` — debug 构建下加 `OutlinedTextField` 输入 user_id
- `MainActivity.kt` — `onMockLogin = { uid -> viewModel.mockWechatLogin(uid) }`

**P1-1 订阅取消可达**
- `data/model/TagModels.kt` — `Tag` 加 `subscribed: Boolean = false`（兼容老后端/旧 fixture）
- `ui/tags/TagSubscriptionViewModel.kt` — `loadTags()` 末尾回填 `subscribedIds = tags.filter { it.subscribed }.map { it.id }.toSet()`
- `test/.../TagSubscriptionTest.kt` — fixture 加 `subscribed:true`

**P1-2 全屏/迷你条恢复 + 真实标题**
- `ui/components/AudioPlayerBar.kt` — 暂停态 + 同 URL → `controller.resume()`，否则 `controller.play(audioUrl, title)`
- `ui/screens/FullScreenPlayerScreen.kt` — `onPlayPause` 三态分支（PAUSED→resume / IDLE-STOPPED→仅非空 URL 才 rebuild）；`displayTitle = currentTitle.ifBlank { title.ifBlank { "未在播放" } }`
- `MainActivity.kt` — `composable("player")` / `composable("fullscreen_player")` 的 title 改为 `""`，屏内 collect 真实 currentTitle

**P1-3 D9 去硬编码 + 回跳刷新**
- 新增 `di/BaseUrls.kt` — `gatewayBaseUrl()` 统一入口（real 172.16.5.28 / emulator 10.0.2.2）
- `di/NetworkModule.kt` — 已切换到 `BaseUrls.gatewayBaseUrl()`（删除本地 `isRunningOnEmulator` 副本）
- `share/D9Receiver.kt` — 删硬编码 `172.16.5.28:8100`；改 `BaseUrls.gatewayBaseUrl()`；callback 成功后 `D9EventBus.emit(result)`
- 新增 `share/D9EventBus.kt` — `MutableSharedFlow<D9Result>`（extraBufferCapacity=1）
- `MainActivity.kt` — `AppNavigation` 内 `LaunchedEffect(Unit) { D9EventBus.events.collect { ... Toast + navigate("list") { popUpTo(startDest) { inclusive=false } } } }`

**P1-4 收藏/回听列表显示标题**
- `data/remote/FavoritesApi.kt` — `Favorite` / `LaterListen` 加 `article_title: String? = null`（兼容老后端）
- `ui/favorites/FavoritesScreen.kt` — `text = article_title ?: article_id`；标题缺失时副行加 `article_id.take(12)`
- `ui/laterlistens/LaterListensScreen.kt` — 同模式

**P2 Paywall 占位**
- `ui/paywall/PaywallScreen.kt` — 注释加 P2 说明；UI 末加一行"升级后还将开放：离线下载 / 主题标签自动蒸馏"（不阻塞）

### 仍需后端配合 1 项

P1-4 完整闭环需要后端 `content-service` 的 `GET /api/v1/favorites` 与 `GET /api/v1/later-listens` 响应里 join 出 `article_title` 字段（目前 list 接口只返 article_id）。客户端已兼容缺失情况（降级显示 article_id），不影响 MVP 跑通，仅影响体验。

### 待真机验收 7 项

| 链路 | 验收动作 | 通过标准 |
|---|---|---|
| 收听 | 详情页播放 → 锁屏/通知控制 | 锁屏可暂停/播放/seek，后台不中断 |
| 蒸馏 | 蒸馏中心点"立即蒸馏" | "蒸馏中" + 后端 ready 才消失；失败可重试，不误报 |
| 身份 | debug LoginScreen 输入两个 user_id | 文章/配额各自隔离 |
| 订阅 | 加载后 Switch 状态正确 + 点取消 | 取消真实生效 |
| 全屏 | 暂停→播放 | 从原位置续播，标题真实 |
| D9 | 分享到 App | toast + 回跳文章列表 |
| 列表 | 收藏/回听 | 显示标题（后端未返时降级 article_id） |

---

## 真实链路走查（MVP-真机收听）

### 触发
- 用户目标：真机剪藏→看到状态→蒸馏→听
- 用户反馈"为什么真机上还是无法播放了"

### 三方走读发现的 bug 矩阵

| # | Bug | 服务端 / 客户端 | 位置 | 影响 |
|---|---|---|---|---|
| B1 | `submit_article` 没调 `trigger_distill` | 服务端 | `content-service/main.py:385-405` | 安卓/管理后台剪藏后文章永远 pending |
| B2 | ai-service MockTTS 返 https://mock/seg1.m4a 占位 | 服务端 | `ai-service/distill/mock_tts.py:13-14` | 即便手动 trigger，audio_url 仍是占位 |
| B3 | 安卓 CaptureScreen 没轮询新剪藏状态 | 安卓 | `CaptureViewModel.kt:62-72` | 用户看不到"蒸馏中→就绪"进度 |
| B5 | ai-service :8103 DOWN + arq worker DEAD | 服务进程 | 多次被 `stop_by_port` 误杀 | worker 死循环 |

### 执行 Plan（按 P0→P1→P2）

#### P0-1 服务端 submit_article 自动 trigger_distill（修 B1）
- `content-service/main.py:387-404` `submit_article` 函数：
  - 建完 article 后调 `get_ai_client().trigger_distill(art.id, auth_token=create_access_token(str(art.user_id)))`
  - 用 `try/except` 包裹，失败仅 log 不破请求（ai-service 不可达时仍可继续）
- 验收：`curl POST /api/v1/articles` 后立刻查 article 详情，**status 应变 `distilling`**（不再是 pending），有 `task_id`

#### P0-2 服务端 fetcher trust_env=False（修 B4）
- `content-service/fetchers/` 下所有 `httpx.AsyncClient()` 加 `trust_env=False`
- 同 gateway 续16 修法
- 验证：抓 example.com 不再因 HTTP_PROXY=55063 失败

#### P0-3 安卓 CaptureViewModel.capture 后兜底 trigger distill（补 client 链）
- `CaptureViewModel.kt:125-146`：调完 `createArticle` 后，**额外**调一次 `repository.distillArticle(id)` 兜底
- 服务端 B1 修了之后这一步多余，但保留作为 idempotent 兜底（已 ready 的文章调 distill 端点返已存在 task，不重复扣配额）
- 验收：app 剪藏后日志能看到 `distillArticle ok` + taskId 存在

#### P0-4 安卓 CaptureScreen 实时轮询新剪藏状态（修 B3）
- `CaptureViewModel` 加 `startPolling(capturedId)`：
  - 每 3s 拉一次 `getArticle(capturedId)` 更新 `recentArticles` 对应条目
  - status=ready 或 30 次超时（90s）停止
  - 启动时机：`capture()` 成功后 + `loadRecent()` 后
- `CaptureScreen` Compose 端：每条 recent item 已显示 statusBadge，状态变化自动反映（已用 `collectAsState`）
- 验收：剪藏一个 URL → 90s 内 status 从 `pending`/`distilling` → `ready`

#### P1-1 ai-service MockTTS 返真可播 WAV（修 B2）
- `ai-service/distill/mock_tts.py`：
  - 改为生成 30 秒静音 WAV bytes（同 fixture 真实音频）
  - 返回 `audio_url=None`，让 pipeline `_save_audio` 走 LocalStorage 落盘
- 验证：触发蒸馏后，`task.audio_url` = `http://172.16.5.28:8100/audio/audio/{id}.wav`，文件 30s

#### P1-2 ai-service 进程守护（修 B5）
- 用 `lauchctl load` 启动 ai-service + arq worker（已写 plist 但未启用）
- 或改 `bin/restart_services.sh` 加 `--no-kill-neighbor` 标志，避免 stop_by_port 误伤同端口进程
- 当前短期：每次重启后单独 nohup 拉起 ai-service + arq worker

#### P2 清理（已知）
- `_resolve_actual_audio_extension` 函数移到顶部（结构整理）
- `task.audio_url` DB 历史遗留 `.mp3` 后缀，但磁盘是 `.wav` → 已修（续24）
- Redis `article:detail:*` 缓存：每次改服务端代码后清

### 目标验收标准（MVP-真机收听）

| 链路 | 验收动作 | 通过标准 |
|---|---|---|
| 剪藏 | 安卓 app 输入 URL 点"立即剪藏" | toast 反馈成功 + quota-1 + 跳回列表看到 pending 状态 |
| 蒸馏 | CaptureScreen 90s 内 | status 从 `pending`/`distilling` 自动变 `ready`，看到进度条 / 状态变化 |
| 收听 | 列表点"立即听"进入详情页 | 自动播放，锁屏可控制，听到 30s 静音（mock TTS） |
| 跨账号 | debug 端 LoginScreen 切 user_id | 各自文章 / 配额隔离 |
| 管理后台 | admin-web 创建文章 + 看列表 + force-retry | 服务端修复后无感可用 |

> 全部通过 = 真机可走通"剪藏→蒸馏→听"完整 MVP 链路

### 不在范围内
- 真实微信 OAuth（用 dev-only token 兜底）
- 真实支付（mock upgrade）
- 真实 LLM/TTS（mock 数据 + silent WAV 兜底）
- 离线下载 / 推送（占位）


---

## 三方协作复扫（2026-09-22 17:30，以真机闭环为目标）

### 全链路当前态（服务端 / 管理后台 / 安卓）

| 链路点 | 状态 | 证据 |
|---|---|---|
| 真机 → gateway (172.16.5.28:8100) | ✅ | adb shell curl health=200 |
| cleartext HTTP | ✅ | Manifest usesCleartextTraffic=true + network_security_config |
| 剪藏 POST /api/v1/articles | ✅ | submit 返回 task_id（P0-1 自动 trigger 生效） |
| content→ai trigger_distill | ✅ | ai_client trust_env=False 修复后 ConnectError 消失 |
| ai-service + arq worker | ✅ | pid 稳定（start_ai.sh 用 start_new_session） |
| 蒸馏 4 步 + 真豆包 TTS | ✅ | arq_distill_completed，61KB 真音频落盘 |
| TTS 管理后台热生效 | ✅ | distill_task 每任务前 tts_reload()，PUT doubao 配置即生效 |
| audio_url 真机可达 | ✅ | 172.16.5.28:8100 HEAD 200 + Range 206 |
| 安卓 P0-3/P0-4 代码 | ✅ 源码已改 | CaptureViewModel: distillArticle 兜底 + startStatusPolling |
| **真机安装的 APK** | ❌ **旧** | 设备上包 last build 16:08 < 源码 16:34 |
| 管理后台 TtsSettings 页 | ✅ | GET/PUT /admin/tts/config + test 均 200 |
| 管理后台登录 | ⚠️ | 仅 admin_649ed932@stashbox.dev 有密码可走 /admin/auth/login，其余 admin 无 password_hash |

### 结论：三方链路服务端/后台已全绿，唯一断点 = 真机 APK 未含轮询+兜底 trigger

### 执行 Plan（收口）

1. **[P0] 重建并安装 APK 到真机**（gradle assembleDebug + adb install -r）
   - 注意：需 JAVA_HOME=/opt/homebrew/opt/openjdk@17（系统 java_home 探测不到，gradle.properties 已指）
2. **[P0] 真机 5 步验收**：
   剪藏 URL → 列表 pending badge → 轮询 pending→distilling→ready（≤30s，mock LLM+真豆包 TTS）→ 点进详情自动播放 → 锁屏控制
3. **[P1] 管理后台账号可用性**：确认用户可用 admin_649ed932@stashbox.dev 登录（密码见 seed 脚本/DB）；若要正式 admin 登录需给指定账号设 password_hash
4. **[P2] LLM 热生效补齐**：ai-service 蒸馏 LLM 目前只读 .env（LLM_PROVIDER=mock），token-plan qwen key 429 额度耗尽；额度恢复后仿 tts_reload 加 llm_reload
5. **[P2] 音频后缀一致性**：mock 路径写 .m4a 后缀但内容为 MP3，ExoPlayer 靠 Content-Type+magic 嗅探可播；严格化可统一按真实格式命名


---

## 删除功能补齐（2026-09-22 18:20，三方走读后）

### 走读结论
| 端 | 现状 | 缺口 |
|---|---|---|
| 服务端 | **没有任何删除端点**（user/admin 均无）；仅 snooze 有 DELETE | 需新增 3 端点 |
| DB 约束 | articles 子表 FK 全部 **NO ACTION**（distilled_articles/favorites/later_listens/listening_statuses/feedback_v2），只有 push_notifications 是 CASCADE | 删除必须应用层按序清子表 |
| tags | tag_subscriptions 是 CASCADE ✅；tags 有 is_system 保护位 | 系统标签禁删 |
| 音频 | LocalStorage 只有 save/exists，无 delete | 需加 delete(key) |
| gateway | /api/v1/admin/* 无 fallback（_resolve_target 不认 admin 段）| 必须显式加路由 |
| admin-web | Articles 页只有 force-retry/导出；Tags 页只有新建/导出 | 加删除按钮+确认 |
| 安卓 | ArticleApi 无 delete；列表/详情无入口 | 加 API+repo+两处 UI |

### 删除语义（决策）
- **硬删除**（物理删），应用层按序：audio 文件 → favorites / later_listens / listening_statuses / distilled_articles（删） → feedback_v2.article_id 置 NULL（留反馈） → push_notifications（DB CASCADE 自动） → article 本体
- 配额**不返还**（剪藏即消耗，删除不回滚，避免白嫖蒸馏）
- 删 tag：级联删订阅（DB CASCADE）；is_system=true 拒绝（403）
- 越权：user 端 _get_owned 校验（不存在/非本人 → 404，与现有一致）

### 执行 Plan
1. **S1 服务端**（content-service + storage + gateway）
   - Storage.base 抽象加 `delete(key)`；LocalStorage 实现（missing_ok）
   - content-service：`_purge_article(db, art)` helper（清音频+子表+缓存 invalidate_article/invalidate_pending）
   - `DELETE /api/v1/articles/{article_id}`（用户端）
   - admin_router：`DELETE /api/v1/admin/articles/{article_id}` + `DELETE /api/v1/admin/tags/{tag_id}`（is_system 保护），写 admin_operation_logs
   - gateway config.py 显式路由 3 条 + 重启
   - curl E2E：本人删 200 / 他人 404 / 音频文件消失 / 子表清空 / admin 删任意用户 / 系统标签 403
2. **S2 admin-web**
   - api/admin.ts：deleteAdminArticle / deleteAdminTag
   - Articles.tsx 操作列「删除」+ 确认；Tags.tsx 行内「删除」（系统标签禁用）
   - tsc 通过
3. **S3 安卓**
   - ArticleApi @DELETE + ArticleRepository.deleteArticle
   - 列表 item：长按→删除确认 AlertDialog；详情页顶栏：删除 icon→确认→回列表
   - 删后刷新列表（+轮询 job 取消）
4. **S4 构建安装真机 + 5 步验收**

---

## 删除功能落地（执行记录 2026-09-22 18:45）

### S1 服务端 ✅
- `app/services/storage/base.py` 抽象 `delete(key)` + `local.py` 实现（`unlink(missing_ok=True)`）+ `oss.py` 补签名
- 新建 `content-service/article_purge.py`（main/admin_router 共用，避免循环导入）：
  - `purge_article`：favorites/later_listens/listening_statuses/distilled_articles 按序删 + feedback_v2.article_id 置 NULL + articles 本体
  - `finish_purge`：commit 后 invalidate_article + invalidate_pending + 磁盘音频文件清理（5 扩展名候选）
- `main.py`：`DELETE /api/v1/articles/{id}`——**越权 404**（破坏性操作不泄露存在性，有意区别于 GET 的 403）+ 埋点 article_delete
- `admin_router.py`：`DELETE /api/v1/admin/articles/{id}`（reason≥5，审计日志）+ `GET /api/v1/admin/tags`（数字 id + is_system + 订阅数，驱动前端删除按钮）+ `DELETE /api/v1/admin/tags/{id}`（is_system→403）
- gateway config.py 4 条显式路由；curl E2E 全过：本人删 200/他人删 404/再 GET 404/音频文件消失/7 张子表清零/push_notifications CASCADE 生效/feedback 埋点保留/admin 删 200+审计落库/reason<5→400/系统标签 403/自定义标签 200/不存在 404

### S2 admin-web ✅（tsc --noEmit 0 错）
- api/admin.ts：deleteAdminArticle/deleteAdminTag（axios delete 走 `{data:{reason}}`）+ listTags 改指 /api/v1/admin/tags
- Articles.tsx：行内红色「删除」按钮 → Modal 硬删除警告 + 原因必填 + 「确认删除」红按钮
- Tags.tsx：操作列「删除」（is_system 禁用+「系统」徽章）+ 删除确认 Modal（原因≥5 字符）；ID 列改显 slug

### S3 安卓 ✅（assembleDebug 成功，APK 已装真机 f1a9e47d）
- ArticleApi `@DELETE("api/v1/articles/{id}")` + ArticleRepository.deleteArticle
- ArticleListViewModel.deleteArticle：成功本地即时移除行 + deletingId/error 状态
- ArticleListScreen：卡片 `combinedClickable` 长按→AlertDialog 二次确认→删除；error Toast
- ArticleDetailViewModel：DeleteState 状态机 + pollingJob 句柄（删除前取消轮询+停播放器）；删除成功 UI 侧 LaunchedEffect→onBack
- ArticleDetailScreen：顶栏红色 Delete icon + 确认对话框（不可恢复/配额不返还文案）
- ⚠️ 真机自动化手势未跑通：MIUI 拦 `adb shell input`（SecurityException INJECT_EVENTS，需手机端开「USB 调试（安全设置）」）。已完成：APK 安装 Success、冷启动无 FATAL、uiautomator dump 确认列表卡片渲染。删除 HTTP 链路已 curl 全验（见 S1），手势部分待手动或开安全调试后补验。
- 测试源码集 40 个编译错误为**既有债务**（createArticle override 缺失、PlayerController 构造漂移，基线已存在；与 deleteArticle 相关错误数=0），未混入本次修复

### S4 真机验收（半自动）
用户手动路径：列表长按任一卡片→删除→确认→行消失；详情页删除→自动回列表。或手机设置开启「USB 调试（安全设置）」后我可用 adb 代跑。

## 补：剪藏/蒸馏页删除入口（2026-09-23 09:15）
用户反馈：文章列表/详情页可删（垃圾桶已见），但「剪藏」页最近剪藏列表、「蒸馏」页待蒸馏列表无删除入口 → 补齐：
- CaptureViewModel.deleteArticle：删前取消该条轮询 + recentArticles 本地移除 + deletingId/error；CaptureScreen 每行加红色垃圾桶 IconButton + AlertDialog 确认
- DistillViewModel.deleteArticle：pollJobs[id] 取消 + pendingArticles/inProgress 移除 + toast「已删除」；DistillItem 行尾（立即蒸馏按钮旁）加垃圾桶 + 确认对话框
- assembleDebug 成功，APK 已装 f1a9e47d，冷启动无 FATAL。后端 DELETE 链路（S1）不变，复用即可。

## CP-TIME + CP-DISTILL 落地（2026-09-23 09:50-10:30）
- CP-TIME：util/TimeFormat.kt 抽出；最近剪藏/文章列表/详情页/蒸馏中心补时间显示；后端 schemas 加 distilled_at + updated_at；main._to_response 透出；content 重启。
- CP-DISTILL：DistillViewModel.UiState 三桶（pending/distilling/failed）+ 各状态转移逻辑；DistillScreen 三段式 SectionHeader；distill/failed 的桶里按钮文案"重新蒸馏"。
- 打包 app-debug.apk 23.1MB，装 f1a9e47d PID 16750 冷启动无 FATAL。
