pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "stashbox-android"

// CP4.2→CP4.7 模块拆分：从单模块 :app 拆出 core / feature 模块。
// 当前仅为骨架（源码随 CP4.4 播放 / CP4.6 网络·登录 落地）；各模块 build.gradle.kts 已就位。
include(":app")
// include(":core")  // CP9.x 暂未填充源码，build 失败；正式多模块阶段再启用
// include(":feature:home")
// include(":feature:player")
// include(":feature:profile")
