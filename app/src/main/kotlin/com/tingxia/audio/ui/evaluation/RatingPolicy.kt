package com.tingxia.audio.ui.evaluation

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §2.5 评分推送策略（客户端本地计数器实现，无专用后端端点 —— G3 待排期）。
 *
 * 规则：
 * - 新用户前 5 篇完听后**应当**弹评分引导；5 篇后频率降档
 * - 同一用户评分引导 24h 内最多 1 次
 *
 * 实现：DataStore 持久化两个计数器：
 * - `rated_count` — 累计评分次数
 * - `last_rating_prompt_ms` — 上次弹出评分引导的 epoch ms
 *
 * 调用方（[EvaluationViewModel] / [com.tingxia.audio.ui.articles.ArticleDetailViewModel]）
 * 在完听后问 [shouldPrompt]，false 则不弹。
 */
@Singleton
class RatingPolicy @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 触发频次：累计 ≤ 5 篇强制弹；之后按 [PROMPT_COOLDOWN_MS] 节流。 */
    suspend fun shouldPrompt(): Boolean {
        val prefs = context.dataStore.data.first()
        val rated = prefs[RATED_COUNT_KEY] ?: 0L
        val last = prefs[LAST_PROMPT_MS_KEY] ?: 0L
        val now = System.currentTimeMillis()
        return when {
            rated < COLD_START_LIMIT -> true
            (now - last) >= PROMPT_COOLDOWN_MS -> true
            else -> false
        }
    }

    /** 弹过一次评分引导就记一笔（不论用户是否提交了评分）。 */
    suspend fun recordPromptShown() {
        context.dataStore.edit {
            it[LAST_PROMPT_MS_KEY] = System.currentTimeMillis()
        }
    }

    /** 提交了一次评分后递增（用于 cold-start 计数）。 */
    suspend fun recordRated() {
        context.dataStore.edit {
            val current = it[RATED_COUNT_KEY] ?: 0L
            it[RATED_COUNT_KEY] = current + 1
        }
    }

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "rating_policy")

    companion object {
        /** 冷启动阶段（首 5 篇）每篇必弹 */
        const val COLD_START_LIMIT: Long = 5L

        /** 冷启动之后的弹窗冷却（24h） */
        const val PROMPT_COOLDOWN_MS: Long = 24L * 60L * 60L * 1000L

        private val RATED_COUNT_KEY = longPreferencesKey("rated_count")
        private val LAST_PROMPT_MS_KEY = longPreferencesKey("last_rating_prompt_ms")
    }
}