package com.tingxia.audio.core

/**
 * core 模块标记（CP4.2 骨架）。
 * 真实代码随 CP4.4（播放）/ CP4.6（网络·登录·JWT 持久化）落地：
 * - di/：Hilt 模块（NetworkModule / StorageModule / AppModule）
 * - network/：Retrofit + 拦截器（gateway 鉴权）
 * - storage/：DataStore（token / 播放进度）
 * - model/：跨模块共享数据类
 */
internal const val CORE_MODULE = "core"
