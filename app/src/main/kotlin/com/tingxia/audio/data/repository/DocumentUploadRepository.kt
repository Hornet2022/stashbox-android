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

        /** 服务端支持的扩展名（文件选择器的 accept 属性 + 客户端预检）。 */
        val SUPPORTED_EXTENSIONS = listOf(
            "pdf",
            "md",
            "markdown",
            "txt",
            "text",
            "rst",
            "log",
            "csv",
            "json",
            "yaml",
            "yml",
            "html",
            "htm",
            "xhtml",
            "docx",
        )

        /**
         * 传给 `ActivityResultContracts.OpenDocument` 的 **MIME 类型**。
         *
         * ## ⚠️ 这里传的是 MIME，**不是扩展名**（2026-10-24 真机踩到的阻断 bug）
         *
         * 契约的入参是 `String[]`，会被塞进 `Intent.EXTRA_MIME_TYPES`
         * （AndroidX 1.9.3 字节码实证：`putExtra("android.intent.extra.MIME_TYPES", input)`）。
         * 而 DocumentsUI 是拿这个列表去**比对文件的 MIME 类型** ——
         * 传进去 `"pdf"`、`"md"` 这种扩展名，没有一个文件的 MIME 恰好等于它们，
         * 于是**所有文件都被灰掉、点不动**。
         *
         * 真机症状：点「选择文档」→ 文件列表里每个文件都是灰的、点不动
         * （uiautomator dump 里文件卡片的 `enabled="false"`，只有文件夹是 true）。
         * 整个文档剪藏功能在真机上**完全不可用**，而单测与 API 层全是绿的。
         *
         * ## 为什么用「全匹配通配符」而不是一份 MIME 白名单
         *
         * 白名单会**再造一次同样的坑**：文件系统的 MIME 报告并不统一 ——
         * `.md` 可能是 `text/markdown` 也可能是 `text/plain`，
         * 未知后缀常是 `application/octet-stream`。一旦白名单漏了，
         * 用户的文件又变成灰的。
         *
         * 所以**选择器一律放开**（一个全匹配通配符），把「支不支持」交给客户端预检
         * （[isSupportedDocument]）与服务端解析器 —— 后者本来就按扩展名
         * **或** content-type 分派，能读就读，读不了给一句人话。
         * 过滤放在选择器里，坏掉的只是「选不中」；放在预检里，坏掉的
         * 顶多是「传上去被拒」，而且有明确提示。
         */
        val PICKER_MIME_TYPES = arrayOf("*/*")

        /** 非文本类、但我们支持的 MIME（与客户端预检、服务端分派保持一致）。 */
        val SUPPORTED_APPLICATION_MIME_TYPES = setOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "application/json",
            "application/xhtml+xml",
        )

        /**
         * 这个文件我们能不能读？（客户端预检，**在上传之前**）
         *
         * 判据与服务端 `fetchers/document.py::parse_document` 的分派规则对齐：
         * **扩展名命中** 或 **MIME 命中**（text 开头的与几个 application 开头的），
         * 外加「没有扩展名」的兜底（服务端也接受，由它按内容判定）。
         *
         * 放在客户端是为了**不浪费一次上传**（配额、流量、以及服务端那句报错），
         * 但服务端仍然是最终权威 —— 客户端判「可以」时它可能仍然读不出来
         * （扫描件 PDF / 加密文件 / 正文过短），那时回服务端的话术。
         *
         * 放在 companion 里（纯函数、不碰 Context）→ 单测可以直接静态调用，
         * 不用造一个带 Context 的仓库实例。
         */
        fun isSupportedDocument(fileName: String, mimeType: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext in SUPPORTED_EXTENSIONS) return true
            val mime = mimeType.substringBefore(';').trim().lowercase()
            if (mime.startsWith("text/")) return true
            if (mime in SUPPORTED_APPLICATION_MIME_TYPES) return true
            // 没有扩展名 → 交给服务端按内容判定（它也接受这种情况）
            return ext.isEmpty()
        }
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
        // 客户端预检：省掉一次白跑的上传（配额 + 流量 + 服务端那句报错）。
        // 判据与服务端分派规则对齐，见 isSupportedDocument 的注释。
        if (!isSupportedDocument(name, mime)) {
            throw IllegalArgumentException(
                "这个文件类型暂不支持（支持 PDF / Markdown / Word / 文本），可以直接粘贴文本"
            )
        }
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
