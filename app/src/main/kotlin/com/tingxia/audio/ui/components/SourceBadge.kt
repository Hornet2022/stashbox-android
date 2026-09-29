package com.tingxia.audio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tingxia.audio.R
import com.tingxia.audio.ui.theme.SourceDouyin
import com.tingxia.audio.ui.theme.SourceWechat

/**
 * source 原始码 → 文案资源 id 的**唯一**映射出口。
 *
 * 为什么不能直接把 source 打到 UI 上
 * --------------------------------
 * `articles.source` 存的是机器可读的英文码，真实分布（2026-09 抽样）：
 * d9 509 / wechat 91 / wechat_mp 46 / douyin 26 / pdf 13 / unknown 1。
 * 原先 [SourceBadge] 只认 wechat 与 douyin，其余全部落到 else，
 * 而首页副标题、蒸馏页、抓藏页更是直接 `text = article.source`，
 * 真机上就露出 "unknown"、"d9" 这种机器码。
 *
 * 为什么返回 resId 而不是 String
 * ----------------------------
 * 有调用方在 @Composable 之外（[com.tingxia.audio.ui.articles.ArticleDetailViewModel]
 * 把 source 当"作者"塞给播放器的 MediaSession），那里拿不到 stringResource。
 * 返回资源 id，Composable 用 stringResource(res)、ViewModel 用
 * context.getString(res) 即可，中文仍留在 strings.xml 里不硬编码。
 */
fun sourceLabelRes(source: String?): Int = when (source?.lowercase()) {
    "wechat" -> R.string.source_wechat
    "wechat_mp" -> R.string.source_wechat_mp
    "douyin" -> R.string.source_douyin
    "d9" -> R.string.source_d9
    "browser" -> R.string.source_browser
    "share" -> R.string.source_share
    "pdf" -> R.string.source_pdf
    // null / unknown / 未来新增的码 —— 宁可显示"通用"，也不要露出机器码
    else -> R.string.source_generic
}

/**
 * 来源徽章：根据 source 标识展示「公众号 / 抖音 / 通用」。
 */
@Composable
fun SourceBadge(source: String, modifier: Modifier = Modifier) {
    val (label, color) = when (source.lowercase()) {
        "wechat", "wechat_mp" -> stringResource(R.string.source_wechat) to SourceWechat
        "douyin" -> stringResource(R.string.source_douyin) to SourceDouyin
        else -> stringResource(R.string.source_generic) to MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
