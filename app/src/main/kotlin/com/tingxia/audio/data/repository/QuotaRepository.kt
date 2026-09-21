package com.tingxia.audio.data.repository

import com.tingxia.audio.data.model.QuotaResponse
import com.tingxia.audio.data.model.QuotaStatus
import com.tingxia.audio.data.model.status
import com.tingxia.audio.data.remote.QuotaApi

/**
 * CP11.0.4 P1.2 付费墙:配额仓库。
 *
 * 不做缓存(后端 quota 极可能实时刷新),每次显式 query。
 * 失败返回 Unknown — UI 不得据此拦截用户提交(放行以免误伤)。
 */
class QuotaRepository(private val api: QuotaApi) {

    suspend fun getQuota(): QuotaResponse = api.getMyQuota()

    suspend fun getStatus(): QuotaStatus = try {
        getQuota().status()
    } catch (e: Exception) {
        android.util.Log.w("QuotaRepository", "getStatus error: ${e.message}")
        QuotaStatus.Unknown
    }
}