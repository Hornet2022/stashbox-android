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
}
