package com.tingxia.audio.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tingxia.audio.data.model.CreateArticleResponse
import com.tingxia.audio.data.remote.ArticleApi
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/**
 * **文档剪藏**仓库（2026-10-23）。
 *
 * ## 为什么单独一个仓库，而不是塞进 [ArticleRepository]
 *
 * ① 它需要 `Context`（读 `content://` URI），而 [ArticleRepository] 只吃
 *    `ArticleApi` —— 给它加 Context 会改构造签名，而那个签名被 8+ 个测试
 *    直接 `ArticleRepository(api)` 构造着。改签名会让那些测试变成
 *    「构造函数的守门员」，而不是行为的守门员。
 *
 * ② 职责不同：[ArticleRepository] 是「文章数据」的读写，文档上传是
 *    「一次带文件的多部分请求」—— 混在一起会让两边都变胖。
 *
 * ## 为什么用 multipart 而不是 base64
 *
 * base64 让请求体**膨胀 33%**（10MB 文件 → 13MB），而且要先把整个文件读进
 * 内存再编码 —— 低端机上就是 OOM。multipart 让 OkHttp 直接流式写出。
 *
 * ## 大小上限
 *
 * 上限由**服务端**定（10MB，与 nginx 的 `client_max_body_size 10m` 同口径）。
 * 这里**也**先挡一道：让用户传 50MB 才撞网关的 413（那种报错没有信息量），
 * 不如在选完文件就告诉他「太大了」。
 */
class DocumentUploadRepository @Inject constructor(
    private val api: ArticleApi,
    @ApplicationContext private val context: Context,
) {

    companion object {
        /** 与服务端 `MAX_DOCUMENT_BYTES` 同口径（10MB）。 */
        const val MAX_DOCUMENT_BYTES = 10L * 1024 * 1024

        /** 服务端支持的扩展名（UI 用来过滤文件选择器）。 */
        val SUPPORTED_EXTENSIONS = listOf(
            "pdf", "md", "markdown", "txt", "text", "rst", "log", "csv", "json", "yaml", "yml",
            "html", "htm", "xhtml", "docx",
        )
    }

    /**
     * 读文件内容（字节）+ 文件名 + MIME。
     *
     * @throws IllegalArgumentException 文件太大 / 读不出来
     */
    fun readDocument(uri: Uri): DocumentPayload {
        val resolver = context.contentResolver
        val name = queryDisplayName(uri) ?: "文档"
        val mime = resolver.getType(uri) ?: guessMimeFromName(name)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("打不开所选文件")
        if (bytes.size > MAX_DOCUMENT_BYTES) {
            throw IllegalArgumentException(
                "文件太大了（${bytes.size / 1024 / 1024}MB），上限 ${MAX_DOCUMENT_BYTES / 1024 / 1024}MB"
            )
        }
        return DocumentPayload(bytes = bytes, fileName = name, mimeType = mime)
    }

    /** 上传文档 → 后端解析 → 建文章 → 派蒸馏。 */
    suspend fun upload(payload: DocumentPayload, title: String? = null): CreateArticleResponse {
        val body = payload.bytes.toRequestBody(payload.mimeType.toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("file", payload.fileName, body)
        val titlePart = title?.takeIf { it.isNotBlank() }
            ?.toRequestBody("text/plain".toMediaTypeOrNull())
        return api.createArticleFromDocument(part, titlePart)
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()

    private fun guessMimeFromName(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "html", "htm", "xhtml" -> "text/html"
            "json" -> "application/json"
            "csv" -> "text/csv"
            else -> "text/plain"
        }
}

/** 读出来的文档内容。 */
data class DocumentPayload(
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean =
        other is DocumentPayload &&
            fileName == other.fileName &&
            mimeType == other.mimeType &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        (bytes.contentHashCode() * 31 + fileName.hashCode()) * 31 + mimeType.hashCode()
}
