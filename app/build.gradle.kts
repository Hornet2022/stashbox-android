plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

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
