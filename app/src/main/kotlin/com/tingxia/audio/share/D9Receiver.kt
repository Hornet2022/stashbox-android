package com.tingxia.audio.share

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tingxia.audio.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 听匣 D9 接收器 (CP10.9)
 *
 * 微信/抖音/浏览器 → 分享 → "更多打开方式" → 听匣 → D9 callback endpoint
 *
 * 设计：
 * 1. 走裸 OkHttpClient（不走 Retrofit/AuthInterceptor）—— D9 不需要登录态
 * 2. device_id 从 SharedPreferences 取（首次启动 UUID 生成，永久不变）
 * 3. API gateway base URL 与 NetworkModule 同源（10.0.2.2:8100 真机用局域网）
 *
 * v2 §1.3 / §2.2 D9 入口承诺：微信"…→更多打开方式"= 2 步可达听匣。
 */
object D9Receiver {

    private const val TAG = "D9Receiver"
    private const val PREFS_NAME = "user_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val D9_PATH = "/api/v1/callback/d9-add-article"
    // 与 NetworkModule 同源:emulator 10.0.2.2:8100,真机 172.16.5.28:8100
    // dev 默认走真机 IP(支持 emulator + 真机一致),prod 走 BuildConfig (TODO)
    private const val BASE_URL = "http://172.16.5.28:8100/"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 从 intent.data 解析分享 URL 并调 D9 callback。
     * 在 IO 协程调用，不阻塞 UI 线程。
     *
     * @return Result<D9Result> success(articleId) / failure(reason)
     */
    suspend fun handleIntent(context: Context, intent: Intent): D9Result? = withContext(Dispatchers.IO) {
        val url = extractSharedUrl(intent) ?: return@withContext null
        val source = inferSource(intent)
        val deviceId = ensureDeviceId(context)
        Log.i(TAG, "D9 received url=$url source=$source device=$deviceId")
        runCatching { postD9Callback(url, source, deviceId) }
            .onFailure { Log.w(TAG, "D9 callback failed: ${it.message}") }
            .getOrNull()
    }

    private fun extractSharedUrl(intent: Intent): String? {
        // 微信/抖音/浏览器分享：通常 ACTION_SEND + EXTRA_TEXT 包含 URL
        // 也可以是 ACTION_VIEW 直接打开 App（如 https://stashbox.app/?url=...）
        android.util.Log.i("D9Receiver", "extractSharedUrl action=${intent.action} extras=${intent.extras?.keySet()}")
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra("text")
                    ?: return null
                android.util.Log.i("D9Receiver", "SEND text=$text")
                return extractUrlFromText(text)
            }
            Intent.ACTION_VIEW -> {
                val raw = intent.data?.toString() ?: return null
                android.util.Log.i("D9Receiver", "VIEW raw=$raw")
                return extractUrlFromText(raw)
            }
        }
        return null
    }

    private fun extractUrlFromText(text: String): String? {
        // 微信分享的 text 常带前缀（"分享xxx: https://..."），用正则取第一个 http(s)
        val regex = Regex("""https?://[^\s]+""")
        return regex.find(text)?.value?.trimEnd('.', ',', '!', '?', ')', ']', '}')
    }

    private fun inferSource(intent: Intent): String = when {
        intent.`package`?.contains("wechat") == true -> "wechat"
        intent.`package`?.contains("douyin") == true -> "douyin"
        intent.action == Intent.ACTION_VIEW -> "browser"
        else -> "share"
    }

    private fun ensureDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        // 首次：UUID，永久不变（卸载重装会变 — 一期可接受，二期换 Settings.Secure.ANDROID_ID）
        val newId = "android-" + java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    private fun postD9Callback(url: String, source: String, deviceId: String): D9Result {
        val payload = JSONObject().apply {
            put("url", url)
            put("source", source)
        }
        val request = Request.Builder()
            .url(BASE_URL.trimEnd('/') + D9_PATH)
            .addHeader("X-Device-Id", deviceId)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "D9 callback HTTP ${response.code}: $body")
                return D9Result.Error("HTTP ${response.code}: $body")
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
            val articleId = json?.optString("article_id").takeUnless { it.isNullOrBlank() }
            val status = json?.optString("status") ?: "pending"
            if (articleId == null) {
                Log.w(TAG, "D9 callback no article_id in body: $body")
                return D9Result.Error("no article_id in response")
            }
            Log.i(TAG, "D9 callback ok: article=$articleId status=$status")
            return D9Result.Success(articleId, status)
        }
    }
}

sealed class D9Result {
    data class Success(val articleId: String, val status: String) : D9Result()
    data class Error(val reason: String) : D9Result()
}