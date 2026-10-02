# ─────────────────────────────────────────────────────────────────────────────
# R8 / ProGuard 规则
#
# 2026-10-03 之前本文件全是注释（release 也 isMinifyEnabled = false），
# 所以这些规则从未真正生效过。开启 R8 后实测 release 从 15MB → 4MB（-73%）。
#
# ⚠️ 下面这些是**必需**的，不是可选的「按需补充」：
#   kotlinx.serialization 的 serializer 是编译期生成 + 运行时反射查找的，
#   R8 看不到这层引用就会把 serializer 裁掉，反序列化在运行期才崩
#   （编译期无警告，真机一调接口就炸）。Hilt / Room / media3 自带
#   consumer rules，这里只兜底本项目自己的东西。
# ─────────────────────────────────────────────────────────────────────────────

# 保留行号，崩溃栈才定位得到
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*, InnerClasses
# Retrofit 依赖泛型签名解析接口方法
-keepattributes Signature

# ── kotlinx.serialization ───────────────────────────────────────────────────
# 官方推荐规则（kotlinx.serialization 文档「R8 / ProGuard」节）
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 本项目的 @Serializable 数据类：保留生成器与 Companion
-keep,includedescriptorclasses class com.tingxia.audio.**$$serializer { *; }
-keepclassmembers class com.tingxia.audio.** {
    *** Companion;
}
-keepclasseswithmembers class com.tingxia.audio.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Retrofit ────────────────────────────────────────────────────────────────
# 接口本身是动态代理实现，必须保留签名
-keep,allowobfuscation interface com.tingxia.audio.data.remote.*
# Retrofit 的内部泛型解析
-keep class retrofit2.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# ── Room ────────────────────────────────────────────────────────────────────
# DAO 实现由注解处理器生成
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }

# ── Hilt / Dagger ───────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}

# ── Media3 ──────────────────────────────────────────────────────────────────
# MediaSession 的回调走 Binder 跨进程，类名会被反射
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.exoplayer.** { *; }

# ── Kotlin 协程 ─────────────────────────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
