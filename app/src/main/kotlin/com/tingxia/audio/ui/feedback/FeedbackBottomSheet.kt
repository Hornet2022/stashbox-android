package com.tingxia.audio.ui.feedback

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tingxia.audio.data.remote.DeviceInfo
import com.tingxia.audio.data.remote.FeedbackCategory
import com.tingxia.audio.data.repository.FeedbackRepository
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.imePadding

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeedbackBottomSheet(
    articleId: String? = null,
    feedbackRepository: FeedbackRepository,
    appVersion: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current

    var selectedCategory by remember { mutableStateOf<FeedbackCategory?>(null) }
    var rating by remember { mutableIntStateOf(0) }
    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    val deviceInfo = remember(appVersion) {
        DeviceInfo(
            app_version = appVersion,
            os = "Android ${Build.VERSION.SDK_INT}",
            device_model = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 2026-10-03：补 imePadding —— 全 App 之前一处都没有。
                // 这个 sheet 里的反馈描述是个多行输入框，键盘弹起来约
                // 300dp 高的面板，ModalBottomSheet 不会自动躲开：内容
                // 被顶上去、「提交」按钮被压在键盘下面看不见，
                // 表现是「点了没反应」。imePadding 会把内容推到键盘上方。
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("反馈与建议", style = MaterialTheme.typography.titleLarge)

            // Category selection
            Text("问题类型", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FeedbackCategory.entries.take(3).forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category.displayName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FeedbackCategory.entries.drop(3).forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category.displayName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            // Star rating
            Text("评分（可选）", style = MaterialTheme.typography.titleSmall)
            // 2026-10-27 可点区：原来 clickable 直接挂在 Text("●") 上，
            // 可点区只有一个字形（≈20dp），低于 48dp 触控目标（R6.5/R6.6）——
            // 手指头点不准，评分经常点不中。
            // 现在触控容器提到 48dp（Box 负责，字形原样放中间）。
            // 这一行同时改成 FlowRow：48dp × 5 + 间距 + 「N / 5」已经接近
            // 360dp 机型扣掉 padding 后的正文宽，窄屏或 200% 字号下必须允许折行，
            // 否则右边的评分数字会被切掉。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                (1..5).forEach { star ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { rating = star }
                            // 2026-10-27 语义：星形是纯图形，不给语义就等于
                            // 读屏用户完全不知道这里能打分（R6.1）。
                            // 描述说「打几分」这个动作，不说「这是个圆点」这种外观（R6.2）。
                            .semantics(mergeDescendants = true) {
                                contentDescription = "评分 $star 星，满分 5 星"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (star <= rating) "●" else "○",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (star <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            // clearAndSetSemantics：字形本身不再单独播报，
                            // 免得读屏把「●」当文本念出来、和上面的评分描述重复。
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                }
                if (rating > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    // 2026-10-27：Compose foundation 1.7.5 的 FlowRow 同一行内
                    // 子项默认顶部对齐（没有 itemVerticalAlignment 参数），
                    // 「N / 5」不管就会贴在 48dp 星形行的顶端。
                    // fillMaxRowHeight 把这一项撑到本行最高，再居中。
                    Box(
                        modifier = Modifier.fillMaxRowHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$rating / 5",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // Content input
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("反馈内容（必填）") },
                placeholder = { Text("请详细描述您的问题或建议，至少10个字") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5,
            )

            // Contact input
            OutlinedTextField(
                value = contact,
                onValueChange = { contact = it },
                label = { Text("联系方式（可选）") },
                placeholder = { Text("方便我们联系您") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // Submit button
            Button(
                onClick = {
                    if (selectedCategory == null) {
                        Toast.makeText(context, "请选择问题类型", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (content.length < 10) {
                        Toast.makeText(context, "反馈内容至少10个字", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isSubmitting = true
                    scope.launch {
                        try {
                            feedbackRepository.submit(
                                category = selectedCategory!!,
                                content = content,
                                rating = if (rating > 0) rating else null,
                                articleId = articleId,
                                contact = contact.ifBlank { null },
                                deviceInfo = deviceInfo,
                            )
                            Toast.makeText(context, "反馈已提交", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (e: Exception) {
                            Toast.makeText(context, "提交失败：${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            isSubmitting = false
                        }
                    }
                },
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSubmitting) {
                    // 2026-10-27：原来没写 color，CircularProgressIndicator 默认取
                    // colorScheme.primary，而它就放在同为 primary 底的 Button 里 ——
                    // 同色 1:1，转圈时按钮看上去"毫无动静"，
                    // 用户以为没点上，于是连点好几遍、重复提交。
                    // 改成 onPrimary（同 CaptureScreen.kt:227 的写法）。
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("提交反馈")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
