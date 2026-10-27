package com.tingxia.audio.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingxia.audio.audio.PlayerController

/**
 * 全局迷你播放条（2026-10-27 新增）。
 *
 * ## 为什么要有它
 *
 * 0.4.7 的播放器控件**只在内容详情页**（`ArticleDetailScreen` 的 bottomBar）。
 * 用户从列表/订阅/收藏点开一篇听，一旦离开详情页，就再也没有任何可见的
 * 播放控件 —— 只能靠通知栏去暂停/切歌。需求是：**只要有音频在播，控件就
 * 始终可见**。
 *
 * ## 它读什么
 *
 * 它读的是 `PlayerController` 单例的**真实当前曲目**（`currentTitle` /
 * `currentAudioUrl` / `currentArticleId`），不是某个页面的局部状态 ——
 * 所以它在任何页面都显示「正在播的那一篇」，与全屏播放器同一份来源。
 *
 * ## 什么时候显示
 *
 * - 有已加载的曲目（`currentAudioUrl` 非空）就显示。这覆盖 PLAYING / PAUSED /
 *   RECOVERING / ERROR —— 「暂停中」和「失败待重试」同样需要控件，不能只在
 *   播放时才给。
 * - 全屏播放页（route == "player"）**不显示**：它本身就是展开的控件，再叠
 *   一条迷你条是重复的（用户已确认此取舍）。
 *
 * ## ⚠️ 这个 Box 必须 fillMaxSize（2026-10-27，0.4.8 事故）
 *
 * 0.4.8 首发时这个 Box 写的是 `fillMaxWidth()` + `contentAlignment = BottomCenter`，
 * 结果播放条出现在了**屏幕顶部**。原因是布局对齐的层级：
 *
 * - 它是 `MainActivity` 里 `Box(Modifier.fillMaxSize())` 的子项，而那个父 Box
 *   的 `contentAlignment` 是**默认的 TopStart**；
 * - 自己的 Box 只有 `fillMaxWidth()`（高度 = 内容高度），所以父 Box 把它摆到**顶部**；
 * - 里面的 `BottomCenter` 只在这个「只有内容高度」的小 Box 内生效 ——
 *   上下沿重合，等于没写。
 *
 * ⇒ 结论：**`BottomCenter` 只有在父容器撑满时才有效**。这里必须 `fillMaxSize()`。
 * `GlobalPlayerBarTest` 里有一条断言直接钉住这条（用节点坐标比对，不看代码）。
 *
 * ## 它要给内容区让出空间
 *
 * 它是**浮层**（盖在 NavHost 之上），所以内容区必须自己给它让出高度，
 * 否则列表的最后一项会被它盖住、滚到底也看不全。
 * 让出多少由 [onContentHeightChange] 报给调用方（见该参数的说明）。
 *
 * @param playerController 注入的播放器单例
 * @param currentRoute 当前导航路由；全屏播放页时隐藏本条
 * @param onClick 整栏点击 → 打开全屏播放器
 * @param onContentHeightChange 报出「本条**自身内容**的高度」（不含系统导航栏
 *   inset），供调用方给内容区预留空间。隐藏时报 0。
 */
@Composable
fun GlobalPlayerBar(
    playerController: PlayerController,
    currentRoute: String?,
    onClick: () -> Unit,
    onContentHeightChange: (Dp) -> Unit = {},
) {
    val currentTitle by playerController.currentTitle.collectAsStateWithLifecycle()
    val currentAudioUrl by playerController.currentAudioUrl.collectAsStateWithLifecycle()
    val currentArticleId by playerController.currentArticleId.collectAsStateWithLifecycle()

    // 全屏播放页不叠迷你条（见类注释）。
    val visible = currentRoute != "player" && currentAudioUrl.isNotBlank()

    // 系统导航栏 inset。**只用来从实测高度里扣掉** ——
    // 内容区（各页自己的 Scaffold）已经按 systemBars 内缩过一次，
    // 这里再计一遍就会多出一截空白（导航栏高度 × 1）。
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // LocalDensity 是 @Composable 读取，不能在 onSizeChanged 的 lambda 里取
    // （那个 lambda 不是 composable）—— 先在外面取好。
    val density = LocalDensity.current

    // 实测播放条**整体**高度（含它自己的 navigationBarsPadding），再扣掉导航栏 inset
    // ＝ 条子自身内容的高度。
    //
    // 为什么实测而不是写死 88dp：写死的话，哪天有人改了播放条内部的留白
    // （比如加个副标题），这里的预留就会**静默错位** —— 内容被盖住，
    // 或多出一截空白。这种错位在代码里看不出来，只有真机滚到底才暴露。
    var barTotalHeight by remember { mutableStateOf(Dp.Unspecified) }
    val contentHeight = if (barTotalHeight == Dp.Unspecified) {
        Dp.Unspecified
    } else {
        (barTotalHeight - navBarInset).coerceAtLeast(0.dp)
    }

    // 回调会随重组换新 lambda，用 rememberUpdatedState 兜住，
    // 否则 LaunchedEffect 的 key 不变时会一直调着旧的那个。
    val reportHeight by rememberUpdatedState(onContentHeightChange)
    LaunchedEffect(visible, contentHeight) {
        reportHeight(if (visible) contentHeight else 0.dp)
    }

    // 没有已加载的曲目就不显示 —— App 刚启动、还没播过任何东西时，
    // 底部不该凭空出现一条空的播放条。
    if (!visible) return

    Box(
        // ⚠️ fillMaxSize 是必须的，不是偷懒 —— 见类注释「0.4.8 事故」。
        // 少了它，contentAlignment = BottomCenter 完全不起作用，条子会跑到顶部。
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AudioPlayerBar(
            title = currentTitle,
            audioUrl = currentAudioUrl,
            articleId = currentArticleId,
            playerController = playerController,
            onClick = onClick,
            modifier = Modifier.onSizeChanged {
                // onSizeChanged 的 lambda 不在 Density scope 里，px → dp 要显式换。
                barTotalHeight = with(density) { it.height.toDp() }
            },
        )
    }
}
