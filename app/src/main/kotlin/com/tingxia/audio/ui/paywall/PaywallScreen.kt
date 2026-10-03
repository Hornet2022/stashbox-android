package com.tingxia.audio.ui.paywall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tingxia.audio.data.model.QuotaResponse
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

/**
 * CP11.0.4 P1.2 付费墙:
 * - 用户本月配额用尽时拦截提交 → 跳此页
 * - 显示当前配额 + mock 升级按钮(mock:点"升级"只弹 toast,后端无支付)
 * - 离线下载已于 2026-10-02 真做（Room 台账 + 真下载 + 通勤预加载），不再是付费点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(
    onBack: () -> Unit,
    viewModel: PaywallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("升级会员") },
                // 2026-10-03 真机发现：这一屏顶栏只有标题、没有返回入口，
                // 是全 App 唯一漏掉的一个（其余 14 屏都接了 ArrowBack）。
                // 付费墙是**被动**被推上来的 —— 用户在剪藏页点「立即剪藏」，
                // 配额耗尽才落到这里，他并没有主动选择来看价格。
                // 结果是：没注意到底部「稍后再说」的人，界面里没有任何
                // 回去的路，只剩系统返回键/手势 —— 在手势导航机型上那个热区
                // 常常和 Home 指示条重叠，单手够不着。
                // onBack 参数本来就在（被「稍后再说」用着），这里补的是图标。
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 2026-10-03：这一屏原来整列不可滚动。内容是
                // 120dp 星星 + 标题 + 副标题 + 配额行 + 三张特性卡 + 升级按钮，
                // 竖着叠完约 620dp；加上 TopAppBar 和 40dp 顶部留白，
                // 6.1 寸以外的机器和任何放大过的系统字号下，底部的
                // 「升级会员」按钮直接掉出屏幕 —— 这一屏存在的全部意义
                // 就是那一个按钮，用户却看不到、点不到。
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // 顶部渐变 + 大星星
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(60.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(60.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "本月配额已用完",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "升级会员,无限剪藏 + 蒸馏",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 配额卡片
            QuotaCard(state.quota)

            Spacer(modifier = Modifier.height(32.dp))

            // CP-QUOTA-HONEST：原来这里是一个写着「立即升级 ¥18/月」的大按钮，
            // onClick 走 viewModel.mockUpgrade() —— 只弹一个 toast。实测后端
            // **没有任何支付/下单端点**，orders 表连 migration 都没有，业务代码
            // 从不写它。一个标价却点不出任何东西的按钮，比没有更糟：用户会
            // 真的去付钱。
            //
            // 现在如实告知「自助升级未开放」+ 说明真正的获取途径（运营在管理
            // 后台调整配额）。等支付端点真的接上，再把 Button 换回来。
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "自助升级暂未开放",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "目前没有在线支付入口。如需更多配额，请在管理后台由运营调整" +
                            "你的月配额，调整记录会写入审计日志。配额将在下月 1 日自动重置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("稍后再说")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2026-10-02：离线下载已经真做了（Room 台账 + 真下载 + 通勤预加载），
            // 这里原本写「升级后还将开放：离线下载」已经过期 —— 而且它把一个
            // 已有的功能说成付费点，会让用户以为不付钱就用不了。
            // 剩下的真实待办是「主题标签自动蒸馏」。
            Text(
                text = "升级后还将开放：主题标签自动蒸馏",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // 错误信息
            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun QuotaCard(quota: QuotaResponse?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
        ) {
            Text(
                text = "当前配额",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // CP-QUOTA-FIELD：原来三个数字都写 `?: 0`。但"取不到"和"真的是 0"
                // 是两回事——字段名对不上时 usedQuota 恒 null，这里就会把一个
                // 取不到的数据伪装成"已用 0 次"，用户完全无从察觉。
                // 取不到就显示 "—"，让问题暴露出来。
                QuotaMetric(
                    label = "已用",
                    value = quota?.usedQuota?.toString() ?: "—",
                )
                QuotaMetric(
                    label = "剩余",
                    value = quota?.remaining?.toString() ?: "—",
                )
                QuotaMetric(
                    label = "月配额",
                    value = quota?.monthlyQuota?.toString() ?: "—",
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // CP-QUOTA-FIELD：原来这里显示 `quota?.plan ?: "free"`。
            // 后端从来没有 plan 字段（users 表只有一列 tier，既是等级也是管理员
            // 角色），所以不管什么用户看到的都是"套餐: free"——一个恒为常量的
            // 假信息。改成显示后端真实提供的重置时间。
            Text(
                text = quota?.resetAt?.let { "配额将于 $it 重置" } ?: "配额按月重置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuotaMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}