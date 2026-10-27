package com.tingxia.audio.ui.evaluation

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.model.EvaluationResponse
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * §2.6 4 维听感评分弹窗（CP3.7.0 评分 UI）。
 *
 * 触发时机：
 * - 完听后自动弹（[com.tingxia.audio.ui.articles.ArticleDetailViewModel] 监听 PLAYBACK_COMPLETE + 进度 ≥ 90%）
 * - 列表项长按可补评
 * - 「稍后再说」关闭弹窗 → **不调用评分接口**，仅记一次"已弹"（[RatingPolicy.recordPromptShown]）
 *
 * UI：
 * - 4 维评分（hook / section / outro / rhythm），每维 1-5 星或「跳过」
 * - 总评（必填 1-5）
 * - 跳过原因 selector（可选）
 * - 评论（≤500 字符）
 *
 * @param taskId 蒸馏任务 id（来自 §1.3 状态响应），不可空
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EvaluationDialog(
    taskId: String,
    onDismiss: () -> Unit,
    onSubmitted: (EvaluationResponse) -> Unit = {},
    viewModel: EvaluationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val submitState by viewModel.submitState.collectAsStateWithLifecycle()
    val myRating by viewModel.myRating.collectAsStateWithLifecycle()
    // 从 collect 出来的 uiState 推导 —— 这才是 Compose 认的依赖。
    val canSubmit = uiState.overallScore in 1..5 &&
        (uiState.hookScore == null || uiState.hookScore in 1..5) &&
        (uiState.sectionScore == null || uiState.sectionScore in 1..5) &&
        (uiState.outroScore == null || uiState.outroScore in 1..5) &&
        (uiState.rhythmScore == null || uiState.rhythmScore in 1..5) &&
        uiState.skipReason.let { it == null || it.length <= EvaluationViewModel.MAX_SKIP_REASON_LEN }

    LaunchedEffect(taskId) {
        viewModel.initWithTask(taskId)
    }

    LaunchedEffect(submitState) {
        if (submitState is EvaluationViewModel.SubmitState.Success) {
            val resp = (submitState as EvaluationViewModel.SubmitState.Success).response
            onSubmitted(resp)
            viewModel.reset()
            onDismiss()
        }
    }

    var commentExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (submitState !is EvaluationViewModel.SubmitState.Loading) {
                viewModel.markPromptShown()
                viewModel.reset()
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = submitState !is EvaluationViewModel.SubmitState.Loading,
            dismissOnClickOutside = submitState !is EvaluationViewModel.SubmitState.Loading,
        ),
        title = {
            Text(
                "听感评分",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "用 1-5 星为这篇打分（也可点「跳过」）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 评分闭环读侧：已评过就明说，并提示可以改分
                myRating?.takeIf { it.isRated }?.let { rated ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            buildString {
                                append("你已评过这篇：")
                                rated.overallScore?.let { append("总评 $it 星") }
                                append("，可以修改")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                // 4 维
                ScoreRow(
                    label = "开场吸引力",
                    value = uiState.hookScore,
                    onSet = viewModel::setHookScore,
                    onClear = { viewModel.setHookScore(null) },
                )
                ScoreRow(
                    label = "章节节奏",
                    value = uiState.sectionScore,
                    onSet = viewModel::setSectionScore,
                    onClear = { viewModel.setSectionScore(null) },
                )
                ScoreRow(
                    label = "结尾收束",
                    value = uiState.outroScore,
                    onSet = viewModel::setOutroScore,
                    onClear = { viewModel.setOutroScore(null) },
                )
                ScoreRow(
                    label = "语速节拍",
                    value = uiState.rhythmScore,
                    onSet = viewModel::setRhythmScore,
                    onClear = { viewModel.setRhythmScore(null) },
                )

                // 总评（必填）
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "总评（必填）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        ScoreRow(
                            label = "",
                            value = uiState.overallScore.takeIf { it in 1..5 },
                            onSet = viewModel::setOverallScore,
                            showSkip = false,
                        )
                    }
                }

                // 跳过原因
                //
                // 2026-10-03 真机发现：原来写「≤32 字符，可选」。
                // 但这一栏下面是 SKIP_REASONS 的 FilterChip 选择器，**根本没有输入框** ——
                // 「≤32 字符」是后端 skipReason 字段的校验上限（见 Evaluation.kt /
                // DistillationApi 的注释），属于接口契约细节，不是用户需要知道的东西。
                // 摆在这里的效果是让人以为能打字，点了没反应还要以为是自己理解错了。
                Text(
                    "跳过这篇的（可选）",
                    style = MaterialTheme.typography.titleSmall,
                )
                // 2026-10-27：原来用 chunked(3) 手工竖排成 3 列 ——
                // 既不是横排也不是 flow：中文标签长短不一时三列会参差不齐，
                // 横向空间也被白白浪费（每列硬塞 3 个）。
                // 改用 FlowRow（同 ShareArticleDialog.kt:75 的写法）：
                // chip 宽度按内容自适应，放不下就自然折行。
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    // 行间距不再额外加：chip 自己上下各留了 8dp 外边距（见下），
                    // 再加 8dp 的话折行后上下两行之间会空出 24dp。
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    EvaluationViewModel.SKIP_REASONS.forEach { (key, label) ->
                        FilterChip(
                            selected = uiState.skipReason == key,
                            onClick = {
                                viewModel.setSkipReason(
                                    if (uiState.skipReason == key) null else key,
                                )
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            // 2026-10-27 触控区：M3 的 FilterChip 本体只有 32dp 高，
                            // 低于 48dp 触控目标（R6.5）。
                            // 上下各留 8dp 外边距后，含外边距的可点高度正好 48dp ——
                            // 用外边距把可点区域撑大，而不是把图形缩小（R6.6）。
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }

                // 评论（可折叠）
                if (!commentExpanded) {
                    TextButton(onClick = { commentExpanded = true }) {
                        Text("+ 添加评论（可选）")
                    }
                } else {
                    OutlinedTextField(
                        value = uiState.comment,
                        onValueChange = viewModel::setComment,
                        label = { Text("评论") },
                        placeholder = { Text("说说你的真实感受…") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        supportingText = {
                            Text(
                                "${uiState.comment.length} / ${EvaluationViewModel.MAX_COMMENT_LEN}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }

                // 错误提示
                if (submitState is EvaluationViewModel.SubmitState.Error) {
                    Text(
                        (submitState as EvaluationViewModel.SubmitState.Error).message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = viewModel::submit,
                // enabled 必须从**已 collect 的 uiState** 推导，不能调 viewModel.isValid()。
                //
                // isValid() 读的是 _uiState.value —— 那是 StateFlow 的普通字段读，
                // **不是 Compose 快照读**，Compose 无从知道这个 Button 依赖它。
                // 星级 ScoreRow 因为读了 collectAsState 的 uiState 会重绘（星是亮的），
                // 但 confirmButton 这个 lambda 在 Material3 AlertDialog 的
                // confirmButton 槽位里不一定跟着重跑 → enabled 永远停在进弹窗时的
                // false。实测（华为 JEF-AN20，2026-10-02）：四维和总评都点满星、
                // 状态确认已写入（★★★★☆），提交按钮仍是 enabled=false，
                // 网关只收到 10 次 GET、0 次 POST，distillation_evaluations 至今 0 行。
                //
                // 也就是说：**这个评分弹窗从来没有、也不可能被用户提交成功**。
                // 闭环 1「听感质量」收不到数据的真正原因在这里 ——
                // 不是「没有入口」，是「有入口但点不动」。
                enabled = submitState !is EvaluationViewModel.SubmitState.Loading && canSubmit,
            ) {
                if (submitState is EvaluationViewModel.SubmitState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("提交评分")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    viewModel.markPromptShown()
                    viewModel.reset()
                    onDismiss()
                },
                enabled = submitState !is EvaluationViewModel.SubmitState.Loading,
            ) { Text("稍后再说") }
        },
    )
}

/**
 * 单维度评分行：5 颗星 + 「跳过」按钮。
 *
 * @param value 当前分数（null = 跳过该维；1-5 表示打分）
 * @param onSet 设置分数；onClear 设为 null 表示跳过
 * @param showSkip 是否显示「跳过」按钮（总评行不显示，因为必填）
 * @param a11yLabel 星形的无障碍描述里的维度名。总评那一行 label 是空的
 *   （标题写在上面的 Surface 里），所以单独给一个可读名，不能让读屏念出空维度。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScoreRow(
    label: String,
    value: Int?,
    onSet: (Int) -> Unit,
    onClear: (() -> Unit)? = null,
    showSkip: Boolean = true,
    a11yLabel: String = label.ifEmpty { "总评" },
) {
    // 2026-10-27 布局踩坑：原来这一行是 Row(标签 88dp + 5×32dp 星 + 「跳过」)，
    // 合计 ≈332dp，而紧凑弹窗正文只有 ~264dp —— 200% 字号下必然裁切。
    // 现在拆成两段：上段标签(weight 1f) + 「跳过」右对齐；下段 5 颗星走 FlowRow。
    // 星的触控容器保持 48dp（R6.5），字形从原来那个 32dp 的固定盒子里放出来 ——
    // 那个盒子既撑宽了整行、又和 48dp 触控容器打架；
    // 字形本身按字号自适应，200% 字号下也不会被裁切。
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotEmpty() || (showSkip && onClear != null)) {
            // 标签 + 「跳过」这一段留在 Row 里：Row 才有 weight(1f) 和垂直居中。
            // 标签用 weight 而不是写死 88.dp —— 88.dp 在 200% 字号下不够放
            // 「开场吸引力」六个字，写死的宽度会把后面的星挤出弹窗。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (label.isNotEmpty()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (showSkip && onClear != null) {
                    TextButton(
                        onClick = onClear,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            if (value == null) "已跳过" else "跳过",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            (1..5).forEach { star ->
                val filled = value != null && star <= value
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        // 2026-10-27：原来这层是 clickableNoRipple（indication = null），
                        // 点了没有任何水波纹反馈（R7.5 要求可点元素都要有涟漪），
                        // 而且可点区只有 32×32dp < 48dp（R6.5），星点不准。
                        // 现在：48dp 触控容器 + 默认涟漪。
                        .clickable { onSet(star) }
                        // 2026-10-27 语义：星形是纯图形，没有语义时读屏完全读不到
                        // 「这一行是给哪个维度打分」（R6.1）。
                        // 描述说清是哪一维、几星，不说外观（R6.2）。
                        .semantics(mergeDescendants = true) {
                            contentDescription = "$a11yLabel $star 星，共 5 星"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (filled) "★" else "☆",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        // clearAndSetSemantics：字形本身不再单独播报，
                        // 免得读屏把「★」当文本念出来、和上面的评分描述重复。
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }
    }
}