package com.tingxia.audio.data.repository

import com.tingxia.audio.data.remote.DeviceApi
import com.tingxia.audio.data.remote.DeviceRegisterRequest
import com.tingxia.audio.data.remote.DeviceResponse
import com.tingxia.audio.data.remote.DeviceUnregisterResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推送设备注册的仓库层回归。
 *
 * 锁住三件容易出错的事：
 * 1. 注册体的 `token` 是**本机稳定 id**，`provider` 是 `"none"`
 *    （现在还没有厂商推送通道，不能拿假 token 冒充）；
 * 2. 重复注册用**同一个** token（后端对 (token, provider) 幂等，
 *    换 token 就等于换了一台设备）；
 * 3. 登出解绑只解绑**本机**那一台（按 token 匹配），
 *    不能把账号下别的设备也一起解掉。
 */
class DeviceRepositoryTest {

    /** 假的本机 id 存储：可控制"有没有登记过"。 */
    private class FakeDeviceIdStore(private var id: String? = null) : DeviceIdStore {
        var createCount = 0
            private set

        override suspend fun getOrCreate(): String {
            createCount++
            return id ?: "device-uuid-1".also { id = it }
        }

        override suspend fun peek(): String? = id
    }

    private class FakeDeviceApi : DeviceApi {
        val requests = mutableListOf<DeviceRegisterRequest>()
        var devices = mutableListOf<DeviceResponse>()
        val unregistered = mutableListOf<Int>()

        override suspend fun registerDevice(body: DeviceRegisterRequest): DeviceResponse {
            requests += body
            // 模拟后端幂等：同一个 (token, provider) 返回同一条记录
            val existing = devices.firstOrNull { it.token == body.token && it.provider == body.provider }
            if (existing != null) return existing
            val created = DeviceResponse(
                id = devices.size + 1,
                token = body.token,
                platform = body.platform,
                provider = body.provider,
                appVersion = body.appVersion,
                deviceModel = body.deviceModel,
                isActive = true,
            )
            devices += created
            return created
        }

        override suspend fun listDevices(): List<DeviceResponse> = devices

        override suspend fun unregisterDevice(deviceId: Int): DeviceUnregisterResponse {
            unregistered += deviceId
            return DeviceUnregisterResponse(deleted = true)
        }
    }

    @Test
    fun `注册时带上本机 id 与应用版本`() = runTest {
        val api = FakeDeviceApi()
        val store = FakeDeviceIdStore()
        val repo = DeviceRepository(api, store)

        val device = repo.registerCurrentDevice(appVersion = "0.1.0", deviceModel = "Pixel 8")

        assertEquals(1, api.requests.size)
        val request = api.requests.first()
        assertEquals("device-uuid-1", request.token)
        assertEquals("android", request.platform)
        // 还没接厂商推送通道 → provider 必须是 "none"（不拿假 token 冒充）
        assertEquals("none", request.provider)
        assertEquals("0.1.0", request.appVersion)
        assertEquals("Pixel 8", request.deviceModel)
        assertTrue(device.isActive)
    }

    @Test
    fun `重复注册用同一个 token`() = runTest {
        val api = FakeDeviceApi()
        val repo = DeviceRepository(api, FakeDeviceIdStore())

        repo.registerCurrentDevice()
        repo.registerCurrentDevice()

        assertEquals(2, api.requests.size)
        assertEquals(api.requests[0].token, api.requests[1].token)
        assertEquals(api.requests[0].provider, api.requests[1].provider)
    }

    @Test
    fun `登出解绑只解绑本机那一台`() = runTest {
        val api = FakeDeviceApi()
        api.devices = mutableListOf(
            DeviceResponse(id = 10, token = "别人的设备", platform = "android", provider = "none"),
            DeviceResponse(id = 11, token = "device-uuid-1", platform = "android", provider = "none"),
        )
        val repo = DeviceRepository(api, FakeDeviceIdStore(id = "device-uuid-1"))

        val ok = repo.unregisterCurrentDevice()

        assertTrue(ok)
        assertEquals(listOf(11), api.unregistered)
    }

    @Test
    fun `本机没登记过时解绑返回 false 而不是报错`() = runTest {
        val api = FakeDeviceApi()
        val store = FakeDeviceIdStore(id = null)
        val repo = DeviceRepository(api, store)

        val ok = repo.unregisterCurrentDevice()

        assertFalse(ok)
        assertTrue(api.unregistered.isEmpty())
        // 没登记过就不该为了"查一下"凭空造一个 id 落盘
        assertEquals(0, store.createCount)
    }
}
