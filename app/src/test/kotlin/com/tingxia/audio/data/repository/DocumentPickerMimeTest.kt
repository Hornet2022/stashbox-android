package com.tingxia.audio.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文档剪藏的**文件选择器参数**与**客户端预检**（2026-10-24）。
 *
 * ## 这个用例在守什么（一个真机必现的阻断 bug）
 *
 * 原实现：`documentPicker.launch(SUPPORTED_EXTENSIONS.toTypedArray())`
 * —— 传的是**扩展名**（`["pdf","md","txt",…]`）。
 *
 * 而 `ActivityResultContracts.OpenDocument` 的入参会进
 * `Intent.EXTRA_MIME_TYPES`（AndroidX 1.9.3 字节码实证：
 * `putExtra("android.intent.extra.MIME_TYPES", input)`），
 * DocumentsUI 拿它**比对文件的 MIME 类型** —— 没有一个文件的 MIME 恰好等于
 * `"pdf"` / `"md"`，于是**所有文件都被灰掉、点不动**。
 *
 * 真机症状：点「选择文档」→ 文件列表里每个文件都灰着，tap 无反应
 * （uiautomator dump：文件卡片 `enabled="false"`，只有文件夹 `enabled="true"`）。
 * **整个文档剪藏在真机上完全不可用**，而单测与 API 层全是绿的。
 *
 * ⇒ 护栏必须守住两件事：
 * 1. 传给选择器的每一项**都是 MIME 类型**（含 `/`）；
 * 2. 我们支持的每个扩展名，其**真实 MIME**都能通过客户端预检 ——
 *    否则用户照样会选不中（换个入口造同一个坑）。
 */
class DocumentPickerMimeTest {

    @Test
    fun `传给选择器的每一项都是 MIME 类型而不是扩展名`() {
        DocumentUploadRepository.PICKER_MIME_TYPES.forEach { type ->
            assertTrue(
                "「$type」不是 MIME 类型 —— 选择器要拿它比对文件的 MIME，" +
                    "传扩展名会让所有文件被灰掉、点不动",
                type.contains("/"),
            )
        }
    }

    @Test
    fun `选择器不按扩展名过滤_避免因各设备 MIME 报告不一致而再造同一个坑`() {
        // 白名单式过滤的风险：.md 可能是 text/markdown 也可能是 text/plain，
        // 未知后缀常是 application/octet-stream。白名单漏一项 = 用户的文件又灰了。
        assertTrue(
            "选择器应当放开（*/*），把「支不支持」交给客户端预检与服务端",
            DocumentUploadRepository.PICKER_MIME_TYPES.contains("*/*"),
        )
    }

    @Test
    fun `每个支持的扩展名都能通过客户端预检`() {
        val mimeOfExtension = mapOf(
            "pdf" to "application/pdf",
            "md" to "text/markdown",
            "markdown" to "text/markdown",
            "txt" to "text/plain",
            "text" to "text/plain",
            "rst" to "text/plain",
            "log" to "text/plain",
            "csv" to "text/csv",
            "json" to "application/json",
            "yaml" to "text/yaml",
            "yml" to "text/yaml",
            "html" to "text/html",
            "htm" to "text/html",
            "xhtml" to "application/xhtml+xml",
            "docx" to
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
        // 扩展名清单与 MIME 映射必须同步，否则新加一种格式就会漏掉预检
        assertTrue(
            "SUPPORTED_EXTENSIONS 与 MIME 映射不一致：${
                DocumentUploadRepository.SUPPORTED_EXTENSIONS.filterNot { it in mimeOfExtension }
            }",
            DocumentUploadRepository.SUPPORTED_EXTENSIONS.all { it in mimeOfExtension },
        )
        mimeOfExtension.forEach { (ext, mime) ->
            assertTrue(
                "扩展名 .$ext（真实 MIME $mime）过不了预检 —— 用户会选不中",
                DocumentUploadRepository.isSupportedDocument("文件.$ext", mime),
            )
        }
    }

    @Test
    fun `md 在各设备 MIME 报告不一致时都能过预检`() {
        // .md 在不同设备上可能是 text/markdown，也可能只是 text/plain
        assertTrue(DocumentUploadRepository.isSupportedDocument("笔记.md", "text/markdown"))
        assertTrue(DocumentUploadRepository.isSupportedDocument("笔记.md", "text/plain"))
    }

    @Test
    fun `明显不支持的类型被拦在上传之前`() {
        assertFalse(DocumentUploadRepository.isSupportedDocument("安装包.apk", "application/vnd.android.package-archive"))
        assertFalse(DocumentUploadRepository.isSupportedDocument("照片.jpg", "image/jpeg"))
        assertFalse(DocumentUploadRepository.isSupportedDocument("视频.mp4", "video/mp4"))
    }

    @Test
    fun `没有扩展名的文件交给服务端判定而不是直接拒`() {
        // 服务端 parse_document 对「无扩展名」是放行的（按内容判定），
        // 客户端若在这里拒掉，就比服务端更严 —— 会把服务端能读的文件挡掉。
        assertTrue(DocumentUploadRepository.isSupportedDocument("README", "application/octet-stream"))
    }
}
