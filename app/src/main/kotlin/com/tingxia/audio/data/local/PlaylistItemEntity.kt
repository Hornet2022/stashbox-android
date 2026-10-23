package com.tingxia.audio.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 播放列表（2026-10-23 新增）。
 *
 * ## 之前没有播放列表，只有「隐式队列」
 *
 * 原来的队列是「文章列表页**当前可见**的 ready 文章」——
 * 由列表页每次加载时灌进播放器（`ArticleListScreen` → `controller.setQueue`）。
 * 这带来三个问题：
 *
 * 1. **用户看不见也管不了**：列表一刷新、翻一页、换个筛选，队列就变了；
 * 2. **通知栏/耳机键的「上一首/下一首」经常是灰的** —— 队列里只有当前这一篇时，
 *    Media3 的 `COMMAND_SEEK_TO_NEXT` 不可用，按钮不亮（这符合 Media3 的语义，
 *    但对用户来说就是「我的播放器连着播不了下一集」）；
 * 3. **隐式队列不是持久状态** —— 退出重进就没了。
 *
 * 这张表把「想连着听的一串」变成**用户显式维护、可持久化、可见可编辑**的对象。
 *
 * ## 为什么只存 article_id，不存音频 URL
 *
 * 音频是**签名 URL，会过期**（服务端 `articles.audio_url` 带 `expires_at`，
 * 客户端有「过期前刷新」逻辑）。存一份 URL 就等于存一份**必然过期**的数据：
 * 播放列表里存着的 URL 迟早 403。
 *
 * 所以这里只存 **article_id + 顺序**，播放时的 URL 从服务端现取。
 *
 * ## 顺序为什么显式存
 *
 * 「加入播放列表」的顺序**是语义**（先加的先播），不是插入顺序的副作用。
 * 用一个显式的 `position` 字段存，排序/移动就是改一个数字，
 * 不用在内存里维护数组再整体重写。
 */
@Entity(
    tableName = "playlist_items",
    indices = [Index(value = ["position"])],
)
data class PlaylistItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "article_id")
    val articleId: String,

    /** 播放顺序，从 0 开始，**连续**（0..n-1，中间不留空洞）。 */
    @ColumnInfo(name = "position")
    val position: Int,

    /** 标题快照（文章被删后仍能显示个名字）。 */
    @ColumnInfo(name = "title")
    val title: String? = null,

    /** 音频地址快照（当前生产多为永久直链；见类注释）。 */
    @ColumnInfo(name = "audio_url")
    val audioUrl: String? = null,

    /** 音频时长（秒），列表展示用。 */
    @ColumnInfo(name = "duration_sec")
    val durationSec: Int? = null,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),
)
