package com.tingxia.audio.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tingxia.audio.data.remote.DeviceApi
import com.tingxia.audio.data.remote.DeviceRegisterRequest
import com.tingxia.audio.data.remote.DeviceResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

/**
 * 本机设备标识的持久化。
 *
 * 抽成接口是为了让 [DeviceRepository] 能在**纯 JVM 单测**里被替换掉
 * （真实现依赖 DataStore / Context，单测里不该牵扯 Android 组件）。
 */
interface DeviceIdStore {
    /** 取本机标识；没有就生成一个并落盘。 */
    suspend fun getOrCreate(): String

    /** 只读不写：没有就返回 null（登出时不该为了"查一下"凭空造一个 id）。 */
    suspend fun peek(): String?
}

/**
 * 基于 DataStore 的设备标识存储。
 *
 * 为什么必须**持久化**而不是每次启动现生成：
 * 后端对 (token, provider) 幂等 —— 只要标识稳定，重装前后的同一个"设备"
 * 就能被后端认出来、归属也能正确转移；每次启动都换新 id 会让后端
 * 攒出一堆僵尸设备记录。
 */
class ContextDeviceIdStore(
    @ApplicationContext private val context: Context,
) : DeviceIdStore {

    override suspend fun getOrCreate(): String {
        var id: String? = null
        // edit 是 DataStore 的原子写：并发调用不会各自生成一个 id。
        context.devicePrefs.edit { prefs ->
            val existing = prefs[DEVICE_ID_KEY]?.takeIf { it.isNotBlank() }
            val resolved = existing ?: UUID.randomUUID().toString()
            id = resolved
            prefs[DEVICE_ID_KEY] = resolved
        }
        return id!!
    }

    override suspend fun peek(): String? =
        context.devicePrefs.data.map { it[DEVICE_ID_KEY] }.first()?.takeIf { it.isNotBlank() }

    private companion object {
        val DEVICE_ID_KEY = stringPreferencesKey("device_id")
    }
}

private val Context.devicePrefs: DataStore<Preferences> by preferencesDataStore(name = "device_registration")

/**
 * 推送设备注册仓库。
 *
 * ## token 字段里放的是什么（重要）
 *
 * 后端 `token` 字段的语义是**厂商推送 token**（APNs/FCM/HMS 那串）。
 * 但本仓库**还没有接入任何厂商推送 SDK**，后端也**还没有配置推送通道**
 * —— 硬造一个假的厂商 token 充数，只会让后端以为有投递目标，
 * 等真接上厂商 SDK 时再全部推倒重来。
 *
 * 所以这里放的是一个**本地生成的、稳定不变的安装级 UUID**，
 * 并把 `provider` 标成 `"none"`（后端约定的"没有通道"取值），
 * 让这一行记录在数据上就是**诚实的**：它是一台设备的占位登记，不是可投递的推送目标。
 *
 * ## 以后接上厂商通道时怎么升级
 *
 * 后端对 **(token, provider)** 幂等，且 provider 参与唯一性判定：
 * - 用**同一个 token** + 新的 provider（例如 `"fcm"`）再注册一次 → 后端会新建一行；
 * - 老的 `provider="none"` 那行要用 DELETE 解绑，否则会留下两条"这台设备"的记录。
 *
 * 也就是说 **API 形状不用变**，只换 `provider` 和 token 的来源即可。
 */
open class DeviceRepository @Inject constructor(
    private val api: DeviceApi,
    private val deviceIdStore: DeviceIdStore,
) {

    /** 本机稳定标识（首次调用时生成并持久化）。 */
    open suspend fun currentDeviceId(): String = deviceIdStore.getOrCreate()

    /**
     * 把本机登记到当前账号名下。
     *
     * 幂等：后端对 (token, provider) 幂等，重复调用只是把同一台设备
     * 重新指向当前账号并激活 —— 换账号登录时正好需要这个行为。
     */
    open suspend fun registerCurrentDevice(
        appVersion: String? = null,
        deviceModel: String? = null,
    ): DeviceResponse {
        val token = currentDeviceId()
        return api.registerDevice(
            DeviceRegisterRequest(
                token = token,
                platform = PLATFORM_ANDROID,
                provider = PROVIDER_NONE,
                appVersion = appVersion,
                deviceModel = deviceModel,
            )
        )
    }

    /** 当前账号名下的所有设备（已解绑的不在里面）。 */
    open suspend fun listDevices(): List<DeviceResponse> = api.listDevices()

    /**
     * 登出前解绑本机。
     *
     * 为什么必须做：共用一台手机的场景下，不解绑的话**上一个账号**的推送
     * 记录还留在后端，厂商通道一旦接上就可能把上一个账号的消息推给这台手机。
     *
     * 返回值：true = 确实解绑了一台；false = 本机没有登记过（已经解绑过了）。
     * 解绑失败不抛 —— 登出不能因为推送接口挂了就登不出去。
     */
    open suspend fun unregisterCurrentDevice(): Boolean {
        val token = deviceIdStore.peek() ?: return false
        // 按 (token, provider) 匹配 —— 与注册时**完全对称**。
        // 只按 token 匹配是不够的：以后接上厂商通道后，同一个 token 可能同时
        // 存在 provider="none" 和 provider="fcm" 两条记录，只按 token 匹配
        // 会解绑到错误的那一条。
        val target = api.listDevices()
            .firstOrNull { it.token == token && it.provider == PROVIDER_NONE }
            ?: return false
        return api.unregisterDevice(target.id).deleted
    }

    companion object {
        const val PLATFORM_ANDROID = "android"

        /**
         * "没有接任何厂商推送通道"的标记值。
         *
         * 后端 `DeviceRegisterRequest.provider` 的默认值就是 `"none"`，
         * 这里显式写出来，是为了让"这行记录不可投递"这件事在代码里看得见。
         */
        const val PROVIDER_NONE = "none"
    }
}
