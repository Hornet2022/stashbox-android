package com.tingxia.audio.ui.feedback

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.tingxia.audio.data.remote.DeviceInfo
import com.tingxia.audio.data.remote.FeedbackCategory
import com.tingxia.audio.data.repository.FeedbackRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                (1..5).forEach { star ->
                    Text(
                        text = if (star <= rating) "⭐" else "☆",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.clickable { rating = star },
                    )
                }
                if (rating > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "$rating/5",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
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
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("提交反馈")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
