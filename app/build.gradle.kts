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
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
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
