package com.tingxia.audio.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedAudioDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadedAudioEntity)

    @Query("SELECT * FROM downloaded_audio WHERE article_id = :articleId")
    suspend fun byArticle(articleId: String): DownloadedAudioEntity?

    @Query("SELECT * FROM downloaded_audio WHERE article_id = :articleId")
    fun observeByArticle(articleId: String): Flow<DownloadedAudioEntity?>

    /** 按音频 URL 查 —— 缓存对账用（Cache 的键是 URL，台账主键是 articleId）。 */
    @Query("SELECT * FROM downloaded_audio WHERE audio_url = :audioUrl")
    suspend fun byAudioUrl(audioUrl: String): DownloadedAudioEntity?

    @Query("SELECT * FROM downloaded_audio WHERE pinned = 1")
    suspend fun allPinned(): List<DownloadedAudioEntity>

    @Query("SELECT * FROM downloaded_audio")
    suspend fun all(): List<DownloadedAudioEntity>

    @Query("SELECT COALESCE(SUM(cached_bytes), 0) FROM downloaded_audio")
    suspend fun totalCachedBytes(): Long

    @Query("DELETE FROM downloaded_audio WHERE article_id = :articleId")
    suspend fun delete(articleId: String)

    /**
     * 清掉已经被 LRU 淘汰掉的文件对应的行。
     *
     * 调 [com.tingxia.audio.audio.OfflineDownloadManager.reconcileWithCache] 时用：
     * 记着"已下载"但文件早没了的行必须删，否则界面会骗用户。
     */
    @Query("DELETE FROM downloaded_audio WHERE article_id IN (:articleIds)")
    suspend fun deleteAll(articleIds: List<String>)

    /**
     * 清空整张表 —— 登出时用（上一个用户的下载台账不能留给下一个用户）。
     *
     * 刻意**不用** `RoomDatabase.clearAllTables()`：那个是**阻塞**调用且要求非主线程，
     * 而这里是 suspend 方法，Room 会自己切到查询线程，还能被协程取消。
     * 另一层理由是**可审计**：受影响的表被逐个写在这里，将来加新表时
     * 不会因为"整库清空"而静默把不该清的东西一起清掉。
     */
    @Query("DELETE FROM downloaded_audio")
    suspend fun clearAll()
}

@Dao
interface PlaybackProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackProgressEntity)

    @Query("SELECT * FROM playback_progress WHERE article_id = :articleId")
    suspend fun byArticle(articleId: String): PlaybackProgressEntity?

    @Query("SELECT * FROM playback_progress WHERE article_id = :articleId")
    fun observeByArticle(articleId: String): Flow<PlaybackProgressEntity?>

    /** 断网期间攒下的、还没同步到服务端的进度。 */
    @Query("SELECT * FROM playback_progress WHERE synced_to_server = 0")
    suspend fun unsynced(): List<PlaybackProgressEntity>

    @Query("UPDATE playback_progress SET synced_to_server = 1 WHERE article_id = :articleId")
    suspend fun markSynced(articleId: String)

    @Query("DELETE FROM playback_progress WHERE article_id = :articleId")
    suspend fun delete(articleId: String)

    /**
     * 清空整张表 —— 登出时用。
     *
     * 断网攒下的进度尤其不能留：它是上一个用户的播放位置，
     * 下一个用户登录后会从"他没听过的地方"接着播。
     */
    @Query("DELETE FROM playback_progress")
    suspend fun clearAll()
}
