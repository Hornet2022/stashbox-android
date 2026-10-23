package com.tingxia.audio.data.repository

import com.tingxia.audio.data.local.PlaylistDao
import com.tingxia.audio.data.local.PlaylistItemEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放列表（本机）。
 *
 * ## 它是什么
 *
 * 用户**显式攒下来**的一串文章，按他加进去的顺序播。
 * 跟「文章列表」的区别：文章列表是**全部已剪藏**的文章（服务端数据），
 * 播放列表是「这几篇我想连着听」（本机数据）。
 *
 * ## 为什么只存 article_id
 *
 * 音频是**签名 URL，会过期**。存一份 URL 就是存一份必然过期的数据
 * （播放列表里那些 URL 迟早 403）。所以只存 id，播放时现取地址。
 *
 * ## 顺序怎么维护
 *
 * `position` 是**稠密**的 0..n-1（中间不留空洞）。所有会改顺序的操作
 * （加入、删除、上移、下移）都走 [rewritePositions]，把整串重排一遍。
 *
 * 为什么不用「稀疏 position + 查询时排序」：那样删除一条之后中间会留洞，
 * 「下一条」是 `position + 1` 就取不到了。稠密化多写几行，换来所有
 * 顺序操作都是「排好数组 → 整体重写」，没有第二种算法。
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
) {

    /** 观察播放列表（按顺序）。 */
    fun observe(): Flow<List<PlaylistItemEntity>> = dao.observeAll()

    suspend fun all(): List<PlaylistItemEntity> = dao.all()

    suspend fun contains(articleId: String): Boolean = dao.byArticle(articleId) != null

    suspend fun count(): Int = dao.count()

    /**
     * 加入播放列表（已在列表里则**保持原位置**不动）。
     *
     * 「已在列表里」不是错误：用户点两次「加入」应该没反应，
     * 而不是把这一篇挪到末尾（那会让人以为列表被重排了）。
     *
     * @param title 标题快照（展示用）
     * @param audioUrl 音频地址快照（播放用；当前生产多为永久直链）
     * @param durationSec 时长（秒），展示用
     */
    suspend fun add(
        articleId: String,
        title: String? = null,
        audioUrl: String? = null,
        durationSec: Int? = null,
    ) {
        if (contains(articleId)) return
        dao.upsert(
            PlaylistItemEntity(
                articleId = articleId,
                position = dao.maxPosition() + 1,
                title = title,
                audioUrl = audioUrl,
                durationSec = durationSec,
            ),
        )
    }

    /**
     * 刷新某一篇的音频地址快照。
     *
     * 音频地址接真签名后会过期，这时用 [com.tingxia.audio.data.repository.ArticleRepository.getAudioUrl]
     * 换一个新的回来。**权威永远是 article_id** —— 这一列只是快照。
     */
    suspend fun refreshUrl(articleId: String, audioUrl: String, title: String? = null, durationSec: Int? = null) {
        val item = dao.byArticle(articleId) ?: return
        dao.upsert(
            item.copy(
                audioUrl = audioUrl,
                title = title ?: item.title,
                durationSec = durationSec ?: item.durationSec,
            ),
        )
    }

    /** 移出播放列表。 */
    suspend fun remove(articleId: String) {
        val before = dao.all()
        if (before.none { it.articleId == articleId }) return
        dao.delete(articleId)
        rewritePositions(before.filter { it.articleId != articleId })
    }

    /**
     * 上移一位（已经在最前面则不动）。
     *
     * @return 是否真的移动了
     */
    suspend fun moveUp(articleId: String): Boolean {
        val items = dao.all()
        val index = items.indexOfFirst { it.articleId == articleId }
        if (index <= 0) return false
        val reordered = items.toMutableList().apply {
            val tmp = this[index - 1]
            this[index - 1] = this[index]
            this[index] = tmp
        }
        rewritePositions(reordered)
        return true
    }

    /**
     * 下移一位（已经在最后则不动）。
     *
     * @return 是否真的移动了
     */
    suspend fun moveDown(articleId: String): Boolean {
        val items = dao.all()
        val index = items.indexOfFirst { it.articleId == articleId }
        if (index < 0 || index >= items.lastIndex) return false
        val reordered = items.toMutableList().apply {
            val tmp = this[index + 1]
            this[index + 1] = this[index]
            this[index] = tmp
        }
        rewritePositions(reordered)
        return true
    }

    /** 清空播放列表。 */
    suspend fun clear() = dao.clearAll()

    /**
     * 把整串按给定顺序重写为稠密的 0..n-1。
     *
     * 逐条 upsert 而不是「先删后插」：后者在事务中途失败会留下空表，
     * 用户的播放列表**整个没了**。upsert 是幂等的，中途失败最多是顺序乱。
     */
    private suspend fun rewritePositions(ordered: List<PlaylistItemEntity>) {
        ordered.forEachIndexed { index, item ->
            if (item.position != index) {
                dao.updatePosition(item.articleId, index)
            }
        }
    }
}
