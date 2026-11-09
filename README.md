# stashbox-android

> **听匣（stashbox.cn）Android 端** — Kotlin + Jetpack Compose + Hilt + Media3
>
> **iOS 端**：暂未启动，参考 [stashbox 主仓](https://github.com/Hornet2022/stashbox) §11.4 CP4
>
> **设计文档**：[stashbox 主仓 docs/](https://github.com/Hornet2022/stashbox/tree/main/docs)（v1 §11.4 CP4 / v1 §6 TTS）

---

## 🎯 项目状态

| 版本 | 状态 | Checkpoint |
|---|---|---|
| v0.1.0 | 待开发 | CP4.2（工程初始化） |
| v0.2.0 | — | CP4.4（MediaSession 后台播放） |
| v0.3.0 | — | CP4.5（锁屏控制） |
| v0.4.0 | — | CP4.6（JWT 接入 user-service） |
| v0.5.0 | — | CP4.7（端到端联调） |

---

## 🚀 启动（开发者）

**前置环境**：
- JDK 17+（`brew install openjdk@17`）
- Android SDK platforms 35 + build-tools 35.0.0（`brew install --cask android-commandlinetools` + `sdkmanager`）
- **不需要**系统 gradle——用项目内 `./gradlew`（自动下 gradle 8.10.2）

**环境变量**（写到 `~/.zshrc`）：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

**构建**：

```bash
./gradlew assembleDebug          # 构建 debug APK
./gradlew testDebugUnitTest      # 跑单测
./gradlew lint                   # lint 检查
```

**APK 路径**：`app/build/outputs/apk/debug/app-debug.apk`

---

## 📦 内测发版

> ⚠️ **发版只有这一条路**：本仓打 release 包 → **后端仓**的 `release_apk.sh` 一次做完
> 上传 + 软链 + 服务端版本 env + 重启 + 校验。
>
> ```bash
> # ① 本仓打 release 包（签名凭据在 ~/.stashbox/，仓库外，不提交）
> bash scripts/build_release.sh --api-base-url https://api.stashbox.cn/
>
> # ② 后端仓发版（一条命令做完全部，含校验）
> cd ../stashbox/backend
> ./deploy/release_apk.sh \
>   ../stashbox-android/app/build/outputs/apk/release/app-release.apk \
>   0.5.3 26 "0.5.3 修复：……（用户点「立即更新」时在弹窗里读到的文字）"
> ```
>
> 详见 [后端仓 deploy/README.md §6.5](https://github.com/Hornet2022/stashbox/blob/main/backend/deploy/README.md)。
>
> **为什么不能只做第 ① 步**：2026-11-09 实测踩坑 —— 只把包传上服务器、没同步
> 服务端的版本 env ⇒ 客户端查版本拿到旧号 ⇒ 判定"已是最新版本" ⇒
> **根本没有下载入口**，内测用户反馈"无法下载新版本"。

### 发完版顺手巡检一下（只读，随时可跑）

```bash
cd ../stashbox/backend
bash deploy/verify_release_consistency.sh
```

它对拍**三处真相源**（服务端 env / `latest.apk` 软链 / 磁盘上的包）与版本接口，
再查外网下载入口拿到的**是真 APK**（状态码 200 不够 —— 路由漏掉时返回的是
`index.html`，状态码照样是 200）。

为什么要有这个**独立于发版流程**的巡检：发版脚本只能校验"自己刚写的东西"，
看不见"有人绕过脚本手工发版"或"发完又被别的东西动了状态"。
详见 [后端仓 deploy/README.md §6.6](https://github.com/Hornet2022/stashbox/blob/main/backend/deploy/README.md)。

### 改版本号时的硬要求

在 `app/build.gradle.kts`：

| 字段 | 要求 | 为什么 |
|---|---|---|
| `versionCode` | **必须比线上大** | Android 只允许 versionCode 更大的包覆盖安装；停在旧号 ⇒ 已装用户收不到更新提示 |
| `versionName` | 递增 | 用户可见 |
| 签名 | **必须与历史包一致** | 不一致 ⇒ 只能卸载重装 ⇒ 本地库/登录态/下载进度全丢 |

打完包脚本会打印证书 SHA-256，与上一版比对即可确认签名一致。

### 产物归档

`releases/app-release-<版本>-versionCode<code>.apk`（本地留档；`.gitignore` 排除了 `*.apk`，不入库）。

---

## 🤖 与 WorkBuddy 协作

- 任务包路径：`.workbuddy/tasks/`
- 工作日志：`.workbuddy/memory/YYYY-MM-DD.md`
- 项目记忆：`.workbuddy/memory/MEMORY.md`
- 协作约定：`.workbuddy/memory/CONVENTIONS.md`

任务包命名：`cpX.Y.md`（对齐 stashbox 主仓 CP1-CP8 体系）。

---

## 📜 许可

暂未开源协议定稿——内部项目。