package com.tingxia.audio.audio

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.tingxia.audio.di.PrimaryEndpoint
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/**
 * 音频流的兜底 DataSource —— **Media3 不走 OkHttp**，所以 [com.tingxia.audio.di.FallbackBaseUrlInterceptor]
 * 管不到它，必须在这里自己实现一份。
 *
 * ## 为什么音频这条线特别重要
 * 实测（2026-10-22）：某网络会 RST 掉到域名的连接。音频是**几分钟的连续
 * 大文件传输**（3MB+），被打断的概率远高于 API 的小请求 —— 实测音频下到
 * 57% 就断（`position=172s, state=ERROR, Source error`）。
 * 而 API 请求有兜底能秒切，音频没有 → 用户看到的就是「文章能看、音频播不了」。
 *
 * ## 行为
 *   · **open**：熔断中就直接用备用地址；否则先试主地址，失败则
 *     标记主地址失败（[PrimaryEndpoint.markPrimaryFailed]）并**用备用地址再试一次**。
 *   · **read**：读到一半失败（连接被重置）时标记主地址失败 ——
 *     这一步是关键：**中途断流不会重新 open**，但标记之后，
 *     播放器下一次 prepare/open 就会直接走备用地址，从缓存断点续上。
 *
 * ## 为什么不自己写 HTTP
 * 只做两件事（改 host、失败重试），HTTP 的其余部分仍然交给
 * [DefaultHttpDataSource]（重定向、超时、UA、Range 请求都归它）——
 * 自己写一遍只会引入新的边界 bug。
 */
@UnstableApi
class FallbackHttpDataSource(
    private val fallbackBaseUrl: String,
    private val delegateFactory: DefaultHttpDataSource.Factory,
) : HttpDataSource {

    private var delegate: DefaultHttpDataSource? = null

    /** 当前这一段在用哪个地址（open 失败时决定要不要换）。 */
    private var openedUrl: HttpUrl? = null

    /** 本次 open 是否已经试过备用地址（避免无限来回切）。 */
    private var triedFallback = false

    override fun open(dataSpec: DataSpec): Long {
        close()
        val primary = dataSpec.uri.toString().toHttpUrlOrNull()
        // 熔断中 → 直接走备用；否则先走主地址（决策逻辑见 FallbackUrlPolicy）
        val startUrl: Uri = if (primary != null) {
            FallbackUrlPolicy.resolve(primary, fallbackBaseUrl).toAndroidUri()
        } else {
            dataSpec.uri
        }
        triedFallback = startUrl != dataSpec.uri
        openedUrl = startUrl.toString().toHttpUrlOrNull()
        return openWith(dataSpec, startUrl)
    }

    /** 用指定地址打开；失败且还没试过备用地址时，换备用地址再试一次。 */
    private fun openWith(dataSpec: DataSpec, url: Uri): Long {
        val source = delegateFactory.createDataSource()
        try {
            val remaining = source.open(dataSpec.withUri(url))
            delegate = source
            return remaining
        } catch (e: HttpDataSource.HttpDataSourceException) {
            source.close()
            // 主地址失败 → 标记（让所有网络线都知道），再试备用地址
            PrimaryEndpoint.markPrimaryFailed()
            val fallback = openedUrl?.let { PrimaryEndpoint.toFallbackUrl(it, fallbackBaseUrl) }
            val canTryFallback = !triedFallback && fallback != null && fallback.toAndroidUri() != url
            if (!canTryFallback) throw e
            triedFallback = true
            val retry = delegateFactory.createDataSource()
            try {
                val remaining = retry.open(dataSpec.withUri(fallback.toAndroidUri()))
                delegate = retry
                return remaining
            } catch (e2: HttpDataSource.HttpDataSourceException) {
                retry.close()
                // ⚠️ 主地址的失败**不能丢**（2026-11-09）。
                //
                // 实测：主地址返回 404（音频被重新生成、旧文件已删）⇒ 换备用地址
                // ⇒ 备用地址连接超时 ⇒ 最终抛出的异常是「超时」
                // ⇒ 分类器只看到超时 ⇒ 判成「瞬时网络类」
                // ⇒ 界面显示「网络不稳，正在重连…」，并对一个**永远不存在的文件**
                //    反复自动重连（用户的原话「手机网络正常」是对的 —— 网络确实没问题，
                //    是服务端说"这个文件没了"，而客户端把它翻译成了"网络不稳"）。
                //
                // ⇒ 把主地址的失败挂到最终异常上（suppressed），
                //    让「服务端明确说了 404」这个事实不会在换地址时被抹掉。
                e2.addSuppressed(e)
                throw e2
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = delegate ?: throw IOException("数据源未打开")
        return try {
            source.read(buffer, offset, length)
        } catch (e: HttpDataSource.HttpDataSourceException) {
            // 中途断流：标记主地址失败。下一次 open（播放器重试）就会走备用地址，
            // 而缓存里已经下好的部分仍在 → 从断点续播。
            if (FallbackUrlPolicy.shouldMarkPrimaryFailed(e.cause)) {
                PrimaryEndpoint.markPrimaryFailed()
            }
            throw e
        }
    }

    override fun getUri(): Uri = delegate?.uri ?: Uri.EMPTY

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun getResponseCode(): Int = delegate?.responseCode ?: 0

    override fun setRequestProperty(name: String, value: String) {
        delegate?.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        delegate?.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        delegate?.clearAllRequestProperties()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        delegate?.addTransferListener(transferListener)
    }

    override fun close() {
        delegate?.close()
        delegate = null
        openedUrl = null
        triedFallback = false
    }

    /** 工厂：给 CacheDataSource 的上游用。 */
    class Factory(
        private val fallbackBaseUrl: String,
        private val delegateFactory: DefaultHttpDataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            FallbackHttpDataSource(fallbackBaseUrl, delegateFactory)
    }
}

/** okhttp 的 HttpUrl → Media3 用的 android.net.Uri。 */
private fun HttpUrl.toAndroidUri(): Uri = Uri.parse(this.toString())
