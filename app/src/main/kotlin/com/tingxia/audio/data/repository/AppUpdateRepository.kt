package com.tingxia.audio.data.repository

import com.tingxia.audio.data.model.AppUpdateInfo
import com.tingxia.audio.data.model.toInfo
import com.tingxia.audio.data.remote.AppUpdateApi
import com.tingxia.audio.data.remote.parseEnvelope
import retrofit2.HttpException
import javax.inject.Inject

/**
 * 应用内更新的数据入口：读服务端当前发布的版本信息。
 *
 * ## 为什么把 HttpException 统一换成 ApiException
 *
 * 网关的 4xx/5xx 返回的是业务信封 `{code,message,data}`（网关自己的注释里
 * 明确写着"安卓 parseEnvelope 靠这个信封分流"）。Retrofit 只会抛裸 HttpException，
 * 把它换成 [com.tingxia.audio.data.remote.ApiException] 之后，
 * [com.tingxia.audio.ui.friendlyError] 才能按业务码分流，
 * 而不是一律显示成"请求失败（404）"。
 *
 * ⚠️ 404 要特别小心：网关的"接口不存在"用的是 code 40400，
 * 而 `isAudioNotReady` 判的也是 404 + 40400 —— 通用文案会变成
 * "音频还在蒸馏中，请稍候"。所以调用方（ViewModel）必须先看 HTTP 状态码，
 * 404 一律按"服务端没有这个接口"处理。
 */
open class AppUpdateRepository @Inject constructor(
    private val api: AppUpdateApi,
) {
    /** 取当前发布版本信息；失败时抛 [com.tingxia.audio.data.remote.ApiException]。 */
    open suspend fun latestVersion(): AppUpdateInfo = try {
        api.getLatestVersion().toInfo()
    } catch (e: HttpException) {
        throw parseEnvelope(e)
    }
}
