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

## 🤖 与 WorkBuddy 协作

- 任务包路径：`.workbuddy/tasks/`
- 工作日志：`.workbuddy/memory/YYYY-MM-DD.md`
- 项目记忆：`.workbuddy/memory/MEMORY.md`
- 协作约定：`.workbuddy/memory/CONVENTIONS.md`

任务包命名：`cpX.Y.md`（对齐 stashbox 主仓 CP1-CP8 体系）。

---

## 📜 许可

暂未开源协议定稿——内部项目。