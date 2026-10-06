plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// 构建期后端开关：`-PapiBaseUrl=http://192.168.3.100:18100/`
//
// 2026-10-03 加这个的原因：有些状态**只在真机上验得了，但造它们要写库**
// —— 配额耗尽（付费墙 / QuotaBanner）、蒸馏中（状态徽章文案）。
// 而 base URL 原本是 [BaseUrls] 里的硬编码常量，App 连哪套后端在编译期就
// 钉死了，想换只能改源码重编译。
//
// 有了这个开关就能让 App 指向 admin-web 那套 e2e 后端（:18100，独立的
// stashbox_e2e 库 + redis db 14），测试数据全落在隔离库里，生产库零写入。
//
// 默认空 = 走 [BaseUrls] 原逻辑，不传这个参数时行为完全不变。
//
// 2026-10-06 追加一条硬约束：**release 变体必须显式给值**（见文件末尾的守卫）。
// 原因：release 里 `di/BaseUrls.kt` 的局域网兜底（Mac-mini.local:8100 /
// 192.168.3.100）已全部禁用 —— 那两个值只在作者自己家的局域网里可达，
// 留着就等于发一个「装得上、但任何外部用户的第一个请求必失败」的包。
// 不给值直接构建失败，比发出去再召回强。
// debug / minifyDebug 不受此限制：emulator 走 10.0.2.2、真机走 Mac-mini.local，
// 那本来就是开发用的便利。
//
// 环境变量 `TINGXIA_API_BASE_URL` 是 `-PapiBaseUrl` 的等价写法，给 CI 用
// （CI 的 secret 不适合拼成命令行参数）。
val apiBaseUrlOverride: String = (
    (project.findProperty("apiBaseUrl") as String?)?.trim()
        ?: System.getenv("TINGXIA_API_BASE_URL")?.trim()
    )
    ?.takeIf { it.isNotEmpty() }
    ?.let { if (it.endsWith("/")) it else "$it/" }
    ?: ""

// ─────────────────────────────────────────────────────────────────────────────
// release 签名：不生成密钥、不把密钥写进仓库，从 Gradle property / 环境变量读
// ─────────────────────────────────────────────────────────────────────────────
//
// 2026-10-06 修 BUG#1：release 变体原本**没有 signingConfig**，
// `assembleRelease` 打出来的是未签名 APK —— 装不上任何真机、进不了任何商店，
// 是个废产物。而 CI（`.github/workflows/ci.yml`）只跑 lint / test / assembleDebug，
// 从不构建 release，所以这个洞一直没暴露。
//
// 为什么不退化成「用 debug key 签 release」：
// debug key 签出来的包一旦装到用户手机上，之后换正式 key 就**装不上去覆盖**
// （Android 要求签名一致），只能卸载重装 → 本地库、登录态、下载进度全丢。
// 那种事故比「发不了版」严重得多，而且不可挽回。所以这里选：缺配置就构建失败。
//
// 需要下面 4 项（Gradle property 优先，其次同名环境变量）：
//
//     store 文件路径    -PreleaseStoreFile      或 TINGXIA_RELEASE_STORE_FILE
//     store 密码        -PreleaseStorePassword  或 TINGXIA_RELEASE_STORE_PASSWORD
//     key 别名          -PreleaseKeyAlias       或 TINGXIA_RELEASE_KEY_ALIAS
//     key 密码          -PreleaseKeyPassword    或 TINGXIA_RELEASE_KEY_PASSWORD
//     密钥库类型(可选)  -PreleaseKeyStoreType   或 TINGXIA_RELEASE_KEY_STORE_TYPE
//
// store 文件支持绝对路径，或相对 **app/ 模块目录** 的相对路径。
// 密钥与密码只放本机 `~/.gradle/gradle.properties` 或 CI secret；
// **不要**写进仓库里的 gradle.properties —— 那个文件是要提交上去的。
//
// debug / minifyDebug 完全不需要这些：debug 走默认 debug key，
// minifyDebug 在下面显式指定 debug key（见 buildTypes 里的注释）。

/** (说明, Gradle property 名, 环境变量名)。 */
val releaseSigningSpecs = listOf(
    Triple("store 文件路径（keystore）", "releaseStoreFile", "TINGXIA_RELEASE_STORE_FILE"),
    Triple("store 密码", "releaseStorePassword", "TINGXIA_RELEASE_STORE_PASSWORD"),
    Triple("key 别名", "releaseKeyAlias", "TINGXIA_RELEASE_KEY_ALIAS"),
    Triple("key 密码", "releaseKeyPassword", "TINGXIA_RELEASE_KEY_PASSWORD"),
)

/** Gradle property 优先，其次同名环境变量；都没有 = 空串（视为缺失）。 */
fun signingInput(propertyName: String, envName: String): String =
    ((project.findProperty(propertyName) as String?)?.trim()
        ?: System.getenv(envName)?.trim()).orEmpty()

/** 缺失的签名项（空列表 = 齐全）。debug / minifyDebug 不看这个值。 */
val missingReleaseSigning = releaseSigningSpecs.filter { (_, propertyName, envName) ->
    signingInput(propertyName, envName).isEmpty()
}

val releaseStoreFilePath = signingInput("releaseStoreFile", "TINGXIA_RELEASE_STORE_FILE")
val releaseStorePassword = signingInput("releaseStorePassword", "TINGXIA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingInput("releaseKeyAlias", "TINGXIA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingInput("releaseKeyPassword", "TINGXIA_RELEASE_KEY_PASSWORD")
val releaseKeyStoreType = signingInput("releaseKeyStoreType", "TINGXIA_RELEASE_KEY_STORE_TYPE")

/** release 构建没有显式后端地址 —— 局域网兜底在 release 里已禁用。 */
val missingApiBaseUrlForRelease = apiBaseUrlOverride.isEmpty()

android {
    namespace = "com.tingxia.audio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tingxia.audio"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 调试用默认联调账号（P0-3：避免写死 6892，允许 LoginScreen 在 debug 包覆盖）
        buildConfigField("String", "DEBUG_USER_ID", "\"6892\"")

        // 见文件头 apiBaseUrlOverride 的说明。空串 = 走 BaseUrls 原逻辑。
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrlOverride\"")
    }

    signingConfigs {
        // 只在 4 项齐全时才创建。缺项时**不创建** —— 这样下面 release 变体里的
        // `signingConfigs.getByName("release")` 也不会抛异常；否则「只想构建
        // debug」的人会被签名配置拦住（配置期对所有变体求值，与本次构建哪个
        // 变体无关）。缺项这件事由文件末尾的守卫报错，且早于任何任务执行。
        if (missingReleaseSigning.isEmpty()) {
            create("release") {
                storeFile = file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // 不给就沿用 JDK 默认类型（JDK 9+ 默认 pkcs12），别硬写成 JKS。
                if (releaseKeyStoreType.isNotEmpty()) {
                    storeType = releaseKeyStoreType
                }
            }
        }
    }

    buildTypes {
        release {
            // 2026-10-03 开 R8：release 一直没混淆/裁剪，实测 15MB。
            // 开启后能裁掉未被引用的 Compose / media3 / Guava（media3-session 的
            // 传递依赖，约 4400 个类引用，release 里实际只用其中很小一部分）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // BUG#1（2026-10-06）：这里原来**一个签名配置都没有**，
            // assembleRelease 产出的是未签名 APK，而 CI 只跑 assembleDebug，
            // 于是没人会发现。现在签名来自文件头的 releaseSigningSpecs 那 4 项。
            //
            // ⚠️ 必须条件挂载：缺项时保持 null，交给文件末尾的守卫报错。
            // 在这里无条件 getByName("release") 会抛 IllegalArgumentException，
            // 那会把「只构建 debug」也一起拦下。
            if (missingReleaseSigning.isEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
        /**
         * minifyDebug：专用于**在真机上验证 R8**（2026-10-03）。
         *
         * 为什么需要单独一个变体：直接在 debug 上开 isMinifyEnabled 是**无效的** ——
         * AGP 会警告 "BuildType 'debug' is both debuggable and has 'isMinifyEnabled'
         * set to true. All code optimizations and obfuscation are disabled for
         * debuggable builds."，等于根本没跑 R8，验证了个寂寞。
         *
         * 所以这里复制一份 release 的完整配置（混淆 + 裁剪 + 同一套 proguard
         * 规则），只把签名换成 debug key（能装）、加上 .minifydebug 后缀
         * （和正式数据、常规 debug 包都隔离）。这才是「release 的混淆效果」的
         * 真机验证。
         *
         * 2026-10-03 已用它完成一次真机验证（华为 JEF-AN20 / Android 12）：
         * 启动 / Hilt 注入 / Compose 渲染 / 图标资源 / Retrofit 请求 /
         * kotlinx-serialization 反序列化 / 导航 / media3 实际播放全部通过，
         * 0 次 FATAL。以后改了 proguard 规则可以再用它复验。
         */
        create("minifyDebug") {
            initWith(getByName("release"))
            applicationIdSuffix = ".minifydebug"
            // ⚠️ isDebuggable 必须 false：AGP 见到 debuggable + minify 组合会
            // 直接「All code optimizations and obfuscation are disabled」，
            // 那样虽然过了构建，但**根本没跑 R8**，验证毫无意义。
            // 代价是崩溃栈没有行号/原类名，所以用 mapping.txt 还原。
            isDebuggable = false
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Media3 ExoPlayer / MediaSession 部分 API 仍标 @UnstableApi，全局 opt-in 避免逐个标注
        freeCompilerArgs += listOf("-opt-in=androidx.media3.common.util.UnstableApi")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// release 前提守卫（2026-10-06，BUG#1 未签名 + BUG#2 局域网地址）
// ─────────────────────────────────────────────────────────────────────────────
//
// 两项前提都只在**构建 release 产物**时要求，debug / minifyDebug 一行配置都不用加：
//
//   1. release 签名 4 项齐全（否则产出未签名 APK —— 装不上、进不了商店）
//   2. 显式后端地址（否则 release 只能去连作者家局域网里那一台机器）
//
// 为什么不把检查写成「无条件在配置期抛」：Kotlin DSL 的配置期对**所有**变体求值，
// 无条件抛就等于 debug 也构建不了。判据必须是「这次请求的任务里有没有 release 产物」。
//
// 为什么不靠 AGP 自己报错：AGP 对「release 没签名配置」是完全接受的（默认行为就是
// 不签名），不配 `signingConfig` 时它一声不响地产出未签名 APK —— 这正是 BUG#1
// 能在仓库里活这么久的原因。

/**
 * 任务名是不是「会产出或消费可安装、可分发产物」的 release 任务。
 *
 * 覆盖 AGP 8.x 实际生成的：`assembleRelease` / `assemble<Flavor>Release` /
 * `bundleRelease` / `packageRelease` / `installRelease` / `signReleaseBundle` /
 * `validateSigningRelease`。
 *
 * 判定必须**两头都卡**（前缀 + 以 Release 结尾），不能只看前缀：
 * `bundleReleaseClassesToRuntimeJar` / `bundleReleaseClassesToCompileJar` 是
 * Kotlin 给 compile classpath 用的 classes jar，名字也以 bundle 开头，
 * 而 `./gradlew :app:test` 会把所有变体（含 release）的单测都拉进来 ——
 * 只看前缀的话，CI 的 test job 会被这个守卫直接拦挂。
 *
 * 同理刻意**不**拦 `compileReleaseKotlin` / `lintRelease` / `testReleaseUnitTest` /
 * `packageReleaseSources`：那几个不碰签名也不打包产物，拦下来只会逼着人在
 * 只想编译或跑测试时也去配密钥。
 */
fun targetsReleaseArtifact(taskName: String): Boolean {
    val t = taskName.substringAfterLast(':')
    return when {
        // assemble / bundle / install / package：AGP 的产物任务一律以变体名结尾
        t.startsWith("assemble") || t.startsWith("bundle") ||
            t.startsWith("install") || t.startsWith("package") -> t.endsWith("Release")
        t.startsWith("sign") -> t.contains("Release")        // signReleaseBundle
        t.startsWith("validateSigning") -> t.contains("Release")
        else -> false
    }
}

/**
 * 守卫的报错文案。逐条列出**缺了什么**、**用哪个 property / 环境变量补** ——
 * 只写「请配置签名」等于让操作者自己猜，猜错了还会回来问第二遍。
 */
fun releasePrerequisiteError(triggeredBy: List<String>): String = buildString {
    appendLine()
    appendLine("✗ release 构建前提不满足，已中止（触发任务：${triggeredBy.joinToString()}）")
    appendLine()
    appendLine("拦在这里的原因：缺了下面任何一项，打出来的包都是「看起来正常、实际发不出去」——")
    appendLine("  • 没有签名的 APK：装不上任何设备，任何商店都不收，等于废产物；")
    appendLine("  • 没有后端地址的 APK：release 里局域网兜底（Mac-mini.local:8100 / 192.168.3.100）")
    appendLine("    已禁用，不显式给地址就只能连一台「只有作者家里通」的机器：装得上、登不进、")
    appendLine("    第一个请求就失败 —— 而这种问题只有真机 + 外网才能暴露。")
    appendLine("  与其发出去再召回，不如现在就失败。")
    if (missingReleaseSigning.isNotEmpty()) {
        appendLine()
        appendLine("① 缺少 release 签名配置（Gradle property 优先，其次同名环境变量）：")
        missingReleaseSigning.forEach { (label, propertyName, envName) ->
            appendLine("     ✗ $label  →  -P$propertyName  或  $envName")
        }
        appendLine("   完整说明见 app/build.gradle.kts 顶部「release 签名」注释。")
    }
    if (missingApiBaseUrlForRelease) {
        appendLine()
        appendLine("② 缺少后端地址（release 必须显式指定）：")
        appendLine("     ✗ 后端 base URL  →  -PapiBaseUrl=https://<你的后端>/  或  TINGXIA_API_BASE_URL=https://<你的后端>/")
        appendLine("   debug / minifyDebug 不受此限制：emulator → 10.0.2.2:8100，真机 → Mac-mini.local:8100。")
    }
    appendLine()
    appendLine("示例（值请自行替换；密码只放本机 ~/.gradle/gradle.properties 或 CI secret，")
    appendLine("不要写进仓库里那个要提交的 gradle.properties）：")
    appendLine("  ./gradlew :app:assembleRelease \\")
    appendLine("      -PapiBaseUrl=https://example.internal/ \\")
    appendLine("      -PreleaseStoreFile=/abs/path/tingxia.jks \\")
    appendLine("      -PreleaseStorePassword=… -PreleaseKeyAlias=… -PreleaseKeyPassword=…")
    appendLine()
}

// 前提齐全就什么都别做 —— release 守卫只负责「缺东西时报错」，
// 不负责在配置合法时反过来拦人（否则 -P 参数给对了却照样失败，比不设守卫还糟）。
if (missingReleaseSigning.isNotEmpty() || missingApiBaseUrlForRelease) {

    // 拦截点 1：命令行直接点名了 release 产物任务。这是最常见的入口，也是报错最直接的时机；
    // `assembleRelease --dry-run` 也会命中（不需要真去构建就能验证守卫）。
    val releaseTasksNamedOnCli = gradle.startParameter.taskNames.filter(::targetsReleaseArtifact)
    if (releaseTasksNamedOnCli.isNotEmpty()) {
        throw GradleException(releasePrerequisiteError(releaseTasksNamedOnCli))
    }

    // 拦截点 2：任务图就绪后再查一次，覆盖 `./gradlew build` / `:app:build` 这类
    // 「名字里没有 Release、但图里含 assembleRelease」的入口 —— 否则从那里照样能
    // 打出一个未签名的 release APK，也就是 BUG#1 原来的样子。
    //
    // 注：本工程未开启 configuration cache（gradle.properties 里没有
    // org.gradle.configuration-cache），taskGraph 在配置期可用。将来若开启配置缓存，
    // 拦截点 2 需要改成 AGP variant API 的回调（拦截点 1 不受影响，仍然有效）。
    // 用 listener 写而不是 `taskGraph.whenReady`：Kotlin DSL 里 whenReady 只有 Groovy 的
    // Closure 重载能被解析，Action 重载在脚本编译期过不了类型推断。
    gradle.taskGraph.addTaskExecutionGraphListener(TaskExecutionGraphListener { graph ->
        val hit = graph.allTasks.map { it.name }.filter(::targetsReleaseArtifact)
        if (hit.isNotEmpty()) {
            throw GradleException(releasePrerequisiteError(hit.distinct()))
        }
    })
}

// Room 的 schema 导出：迁移时要用（没有它 Room 只能靠 recreate 丢数据）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // CP8.5 — FullScreenPlayer 用到 SkipNext / Forward30 / Bookmark / Tag 等扩展图标
    //
    // 2026-10-03 实测：全项目只用 24 个图标，且命名空间全是 Filled/Outlined、
    // 零个 Icons.Extended，看起来「只用 core 就够」——实测**不行**，删掉这行后
    // 编译报 12+ 处 Unresolved reference，core 里缺：
    //   Pause / GraphicEq / CloudDownload / ErrorOutline / Link / …
    // 所以 extended 是必需的，别再当冗余依赖删。
    // 真要减包体只能换掉这几个图标（用 Material Symbols 或自绘），不是删依赖。
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Lifecycle / Activity / Navigation
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // collectAsStateWithLifecycle 全项目 20+ 处直接用，此前只靠 viewmodel-compose
    // 的传递依赖解析到；显式声明，见 libs.versions.toml 里的说明
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Audio (Media3 / ExoPlayer) — 留位，CP4.4 才接播放逻辑
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    // Network — 留位，CP4.6 才接 user-service
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    // DataStore — CP4.6 JWT 持久化（tokenManager 用 preferencesDataStore）
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 本地库 — 2026-10-02 真正启用。
    // 之前版本目录里声明了 room 但 app 没引，依赖是"看起来有、实际没有"，
    // 于是播放进度只能走网络，断网即丢（表现为地铁里重开文章从头播）。
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 后台预加载 — 通勤时段把下一集拉下来
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    // CP4.7-A2 E2E 测试用 MockWebServer 模拟 gateway
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // 真机端到端：Compose TestRule 直接 dispatch 语义动作（performClick），
    // 不经过 InputManager 注入 → 不受 MIUI「USB 调试(安全设置)」限制。
    // 注意：UiAutomator 的 injectInputEvent 在 MIUI 上即使 instrumentation
    // 身份也会被拒（实测 SecurityException），故不用它驱动点击。
    // Compose BOM 需显式作用于 androidTest classpath，否则 ui-test-junit4 版本解析为空
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// Robolectric 本地 Compose UI 测试需要 Android 资源
android.testOptions {
    unitTests.isIncludeAndroidResources = true
}
