# 音频应用基础能力：调研、缺口与补齐方案（2026-10-23）

## 一、调研：现状与平台要求

### 1.1 现状：已经有的

| 能力 | 位置 | 状态 |
|---|---|---|
| 前台服务 | `AndroidManifest` → `AudioPlayerService`，`foregroundServiceType="mediaPlayback"` | ✅ 合规（Android 14+ 要求） |
| MediaSession | `AudioPlayerService.onCreate` 里用共享 ExoPlayer 建 session | ✅ |
| 媒体通知 | `DefaultMediaNotificationProvider`（Media3 自带 MediaStyle） | ⚠️ 有通知，但**按钮不全、点了没反应**（见 2.2 / 2.3） |
| 后台播放 | 服务 + 共享 ExoPlayer 单例 | ⚠️ 能播，但**息屏可能断**（没 wake mode）、**没音频焦点** |
| 播放队列 | `PlayerController._queue`（App 层内存态） | ⚠️ 系统完全看不到（没进播放器） |
| 播放速度 | `PlayerController.setSpeed` → `player.setPlaybackSpeed` | ✅ 真落到播放器 |
| 离线下载 / 预加载 | `OfflineDownloadManager` | ✅ |
| 进度回传 | 每 10s 上报 `progress` | ✅ |

### 1.2 平台硬性要求（不能凭记忆，已查证）

| 要求 | 出处 | 对本项目的影响 |
|---|---|---|
| Android 13+（API 33）`POST_NOTIFICATIONS` **必须运行时申请** | 官方原文：「如果漏掉后面这一条,会出现"**声音在播但通知栏没有控制条**」 | 本项目**只在蒸馏通知里检查**这个权限，**播放通知没申请** → 用户听到声音但通知栏空白 |
| Android 14+（API 34）必须声明 `foregroundServiceType` + 对应权限 | 前台服务类型文档 | ✅ 已做 |
| **Android 15（API 35，= 本项目 targetSdk）音频焦点请求受限** | 「targeting SDK 35 and above: The app must be a top app or be running a foreground service to request audio focus. If these conditions are not met, `requestAudioFocus()` returns `AUDIOFOCUS_REQUEST_FAILED`」 | 一旦打开音频焦点处理，**服务必须是前台**才能拿到焦点 → 与「服务必须已启动」强耦合 |
| Android 15+ `mediaPlayback` 前台服务**不能从 BOOT_COMPLETED 启动** | 同上 | 本项目不从 boot 起，无影响 |
| Android 17：后台音频需要「可见 Activity 或非 shortService 的前台服务」 | Android 17 background audio hardening | ✅ 本项目是 `mediaPlayback` FGS + `MediaSessionService`，符合基线 |
| Media3 `MediaSessionService` 自动生成 MediaStyle 通知 | 官方 background-playback 文档 | ✅ 已用 |

### 1.3 Media3 1.4.1 的实际行为（读了 1.4.1 源码，不是新文档）

这一节很关键 —— **按新文档写会在 1.4.1 上编译不过或行为不对**。

- `MediaSession.Callback`（1.4.1）**没有** `onSkipToNext` / `onSkipToPrevious`，
  只有 `onCustomCommand` / `onAddMediaItems` / `onPlaybackResumption` 等。
- `DefaultMediaNotificationProvider.getMediaButtons()` 判断按钮的依据是
  **`player.getAvailableCommands()`**（不是 session 的命令集合）：
  - 含 `COMMAND_SEEK_TO_PREVIOUS(_MEDIA_ITEM)` → 显示「上一首」
  - 含 `COMMAND_PLAY_PAUSE` → 显示「播放/暂停」
  - 含 `COMMAND_SEEK_TO_NEXT(_MEDIA_ITEM)` → 显示「下一首」
  - **单曲播放器没有下一项 → 这两个命令不可用 → 通知栏没有上一首/下一首**
- 通知的点击意图 = `mediaSession.getSessionActivity()`，
  **没设的话 contentIntent 为 null → 点通知什么都不会发生**。
- 通知里**没有**快进/快退按钮（`getMediaButtons` 只管三个），
  要加只能走 `setCustomLayout` + 自定义命令。
- `Player`（1.4.1）**没有** `setMediaItem(int, MediaItem, long)`；
  替换队列内某一项只有 `replaceMediaItem(int, MediaItem)`（**不保留播放位置**，
  要自己 `seekTo(index, pos)` 补）。

---

## 二、缺口清单

### 2.1 🔴 平台行为缺失（不补就不像个播放器）

| # | 缺口 | 后果 |
|---|---|---|
| A1 | **完全没有音频焦点**（`ExoPlayer.Builder` 没设 `AudioAttributes`，`handleAudioFocus` 默认 false） | 不遵守其他 App 的播放：音乐 App 在放时听匣也放；来电/导航提示不打断、不 duck |
| A2 | **没有耳机拔出处理**（`setHandleAudioBecomingNoisy` 未开） | 拔耳机后还在外放 |
| A3 | **息屏会中断**（`WAKE_LOCK` 权限声明了但 `setWakeMode` 没调） | 锁屏后 CPU 休眠，播放断掉 |
| A4 | **播放通知没申请 POST_NOTIFICATIONS** | Android 13+：**声音在播但通知栏没有控制条** |

### 2.2 🔴 通知栏控制不完整

| # | 缺口 | 后果 |
|---|---|---|
| B1 | **没设 `setSessionActivity`** | **点通知没反应**（contentIntent 为 null） |
| B2 | 队列没进 ExoPlayer → `COMMAND_SEEK_TO_NEXT/PREVIOUS` 不可用 | 通知栏/锁屏**没有上一首/下一首** |
| B3 | 没暴露 `COMMAND_SEEK_BACKWARD/FORWARD` | 通知栏没有快退/快进（耳机双击、切歌键也没有） |
| B4 | `onConnect` 只暴露 4 个命令 | 系统媒体面板取不到时间轴/元数据/速度 |

### 2.3 🟡 健壮性与体验

| # | 缺口 | 后果 |
|---|---|---|
| C1 | **全项目零 `Player.Listener`** | 播放失败（网络断/404/解码错）→ 界面毫无反应（状态是乐观设成 PLAYING 的） |
| C2 | 没有睡眠定时器 | 音频 App 标配（听完自动停） |
| C3 | 没有「继续播放」 | 进程被杀后，系统媒体面板点「继续」没反应（1.4.1 有 `onPlaybackResumption` 可用） |

### 2.4 ⚪ 本轮明确不做（并说明为什么）

| 项 | 原因 |
|---|---|
| Android Auto / Automotive | 需要**单独一个 APK**（官方要求 automotive module 独立），是独立工程量 |
| Wear OS | 需要独立 APK |
| Cast / 投屏 | 需要 Google Play Services 依赖，与「不新增第三方依赖」冲突 |
| 字幕 / 歌词 | 服务端不产出字幕，属于内容侧 |
| 均衡器 | Android 12+ 系统自带 EQ 应用可覆盖 |

---

## 三、方案：三层契约，不重复定义

「音频播放应用该有的能力」散在三个层，每层各管一段，**同一件事只在一处定义**：

```
第 1 层  PlayerModule（播放器实例）     ← 播放器的「平台行为」唯一定义处
         音频焦点 / 息屏唤醒 / 耳机拔出
         理由：这三件事都是 ExoPlayer 的配置，绑在实例上，不绑在谁在播

第 2 层  AudioPlayerService + Callback  ← 「系统怎么操控这个 App」唯一定义处
         session activity / 可用命令集合 / 自定义命令 / 播放恢复

第 3 层  PlayerController（应用层）      ← 「App 自己怎么播」唯一定义处
         队列 / 睡眠定时器 / 错误反馈 / 进度与完听
```

### 分批落地（每批独立可验证、独立提交）

| 批 | 内容 | 风险 |
|---|---|---|
| **1** | 音频焦点 + 耳机拔出 + 息屏唤醒 + 播放错误反馈 | 低（配置 + 一个 Listener） |
| **2** | `setSessionActivity` + 完整命令集合 + 通知快进/快退/上下一首（自定义命令） | 低（不改播放核心） |
| **3** | 队列进 ExoPlayer（`mediaId` 承载 articleId）→ 通知/锁屏/系统面板的上下一首真正可用 | 中（动播放核心，需真机回归进度与完听） |
| **4** | 通知权限运行时申请 + 睡眠定时器 + 继续播放（resumption） | 中 |

**为什么队列要进播放器（批 3）**：Media3 1.4.1 的通知按钮看的是
`player.getAvailableCommands()`。只要 ExoPlayer 是单曲，`COMMAND_SEEK_TO_NEXT`
就不可用 → 通知栏永远没有「下一首」。App 层再怎么画按钮也绕不过这一层。
`MediaItem.mediaId` 正好承载 `articleId`，进度/完听判定改为读
`player.currentMediaItem?.mediaId` 即可，不额外造状态。

---

## 三点五、真机实测结果（2026-10-23，0.3.2）

真机：小米 Mi 11 / MIUI / Android 11，网络 172.16.6.0/20（该网络会 RST 掉到域名的
TLS 连接 → 整条链路自动走**兜底切 IP**，是最严苛的环境）。

| 能力 | 怎么验的 | 结果 |
|---|---|---|
| 播放通知 | `dumpsys notification` 查记录 | ✅ `pkg=com.tingxia.audio id=1001 importance=2 category=transport actions=4` |
| 通知控件 | 截图 + 逐个点 | ✅ 快进30 / 上一首 / 播放暂停 / 快退10 四个都在，MediaStyle 带进度条 |
| 点通知回播放器 | 退后台 → 拉下通知 → 点标题 | ✅ 直接落到**全屏播放器**（不是首页） |
| 通知控件真的在控播 | 点通知里的播放键 | ✅ `state=PLAYING position=2859` |
| 后台播放 | 退到后台 25 秒 | ✅ position 2859 → 29927，声音不断 |
| 息屏不中断 | `KEYCODE_SLEEP` 息屏 25 秒 | ✅ position 44963 → 72027（`setWakeMode` 生效） |
| 音频焦点 | `dumpsys audio` 的 focus stack | ✅ 本 App 持有焦点，`flags: PAUSES_ON_DUCKABLE_LOSS`（导航提示会先让它降音量） |
| 前台服务 | `dumpsys activity services` | ✅ `isForeground=true types=00000002`（mediaPlayback） |
| 通知权限时机 | 首次播放 | ✅ 弹窗出现在**开始播放的那一刻**，不是一进 App |
| 媒体面板 | `dumpsys media_session` | ✅ AVRCP 拿到标题（`Avrcp_ext: updateCurrentMediaState`） |

### 三个只在真机上暴露、单测全盲的坑

**这三个是本轮真正的价值所在** —— 它们在代码里都「看着对」，
单测也全绿，只有真机才炸：

1. **`MediaSessionService` 不接管 `onGetSession` 返回的会话**（Media3 ≥ 1.4.0）。
   必须自己 `addSession(session)`。漏了它 → 服务不知道自己有会话 →
   **不发通知、不进前台**。症状极具迷惑性：声音照放、进度照回传、
   `dumpsys media_session` 里也看得到会话，只有通知不见 ——
   于是极易被误判成「Android 13+ 通知权限」，去申请权限、加渠道、
   改 IMPORTANCE，**全都不是这里**。
   判据：`dumpsys activity services` 里 `startForegroundCount=0` 就是它。

2. **`CommandButton.Builder(int)` 收的是「图标常量」不是 drawable 资源 id。**
   它内部会 `getIconResIdForIconConstant()` 做映射。传 `R.drawable.xxx`
   → 映射成 0 → Media3 建 CustomAction 时抛
   `IllegalArgumentException: You must specify an icon resource id to build a CustomAction`
   → **服务在 `onCreate` 里就崩** → App 一起身就退。
   正确写法：`CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)`。

3. **通知的 provider 必须在 `addSession` 之前设。**
   服务一旦接管会话就会发第一条通知，那时 provider 还是默认的 →
   通知落在 Media3 的 `default_channel_id` 渠道上，而不是我们自己的
   `stashbox_audio_playback`。

另外补一条**测试本身的坑**：Media3 把「同步给旧版会话（锁屏/车机）」的任务
**投递到主线程** —— 不 idle 主线程，那一跳根本不执行，
于是「图标常量传错」这类错误在单测里**完全看不见**。
`AudioPlayerServiceTest` 原来只有一条断言 provider 非空的用例，
对这三个问题**全盲**；现在补了「idle 主线程跑一遍不崩」
「会话登记给了服务」「通知有落地页」三条。

## 六、验证方式（计划）

| 能力 | 怎么验 |
|---|---|
| 音频焦点 | 真机：先放音乐 App 的歌 → 启动听匣播放 → 音乐应停/降音量（`dumpsys media.audio_flinger` 看焦点持有者） |
| 息屏不中断 | 真机：播放 → 息屏 60s → 回来看 position 是否还在涨（服务端 `listening_statuses` 也在涨） |
| 耳机拔出 | 真机：插耳机播放 → 拔耳机 → 应自动暂停 |
| 通知控制 | 真机：下拉通知栏看控制条；点通知应回到播放器页；上一首/下一首/快进快退逐个点 |
| 后台播放 | 真机：播放 → 切到别的 App / 锁屏 → 声音不断 |
| 播放错误 | 单测（构造 `PlaybackException`）+ 真机（断网播放） |
| 睡眠定时器 | 单测（定时到点停）+ 真机 |
