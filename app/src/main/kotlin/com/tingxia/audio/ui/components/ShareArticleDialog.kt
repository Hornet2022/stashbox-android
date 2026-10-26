package com.tingxia.audio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tingxia.audio.data.model.Tag

/**
 * 0041 共享：文章共享对话框（详情页与列表页共用）。
 *
 * 属主把自己剪藏的内容按标签共享给订阅了这些标签的其他用户：
 * - 勾选标签 → 保存 → 共享；一个都不勾 → 保存 = 取消共享。
 * - 已共享时显示「已共享给 N 个标签」，并把当前 sharedTags 预勾选进选择器。
 * - 保存期间 Loading → 关闭按钮与保存按钮都禁用、保存按钮转圈。
 * - Error → 对话框内红字显示错误（不打断用户已选的内容）。
 *
 * 选择状态（[selectedTagIds]）、勾选回调（[onToggleTag]）由调用方（ViewModel）
 * 持有；保存时把选中的标签**中文名**交给 [onSave]（后端 shared_tags 存的是名字，
 * 不是 id —— 见 Article.kt / ShareRequest 的注释）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareArticleDialog(
    articleTitle: String?,
    alreadyShared: Boolean,
    sharedTagsCount: Int,
    availableTags: List<Tag>,
    selectedTagIds: Set<String>,
    isLoading: Boolean,
    errorMessage: String?,
    onToggleTag: (String) -> Unit,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("共享这篇内容") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = buildString {
                        append("共享后，订阅了这些标签的用户就能在订阅流里看到并收听这篇。")
                        if (alreadyShared) {
                            append("\n\n当前已共享给 $sharedTagsCount 个标签。")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 标签多选：可用标签来自 availableTags（懒加载，可能为空 → 提示稍候）
                if (availableTags.isEmpty()) {
                    Text(
                        text = "正在加载标签…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        availableTags.forEach { tag ->
                            val selected = tag.id in selectedTagIds
                            FilterChip(
                                selected = selected,
                                onClick = { onToggleTag(tag.id) },
                                label = {
                                    Text(
                                        text = tag.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(selectedTagIds.toList()) },
                enabled = !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存中…")
                } else {
                    Text("保存")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading,
            ) { Text("取消") }
        },
    )
}