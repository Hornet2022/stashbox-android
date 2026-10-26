package com.tingxia.audio.ui.articles

import androidx.lifecycle.ViewModel
import com.tingxia.audio.ui.friendlyError
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.model.Article
import com.tingxia.audio.data.model.ShareResponse
import com.tingxia.audio.data.model.Tag
import com.tingxia.audio.data.repository.ArticleRepository
import com.tingxia.audio.data.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 列表页 ViewModel：持有文章列表 StateFlow，加载时拉取 [ArticleRepository.getArticles]。
 */
@HiltViewModel
class ArticleListViewModel @Inject constructor(
    private val repository: ArticleRepository,
    /** 标签仓库（0041 共享：标签选择器要拉可用标签）。 */
    private val tagRepository: TagRepository,
) : ViewModel() {

    private val _articles = MutableStateFlow<List<Article>>(emptyList())
    val articles: StateFlow<List<Article>> = _articles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // CP7.5: 加 error state,500/网络异常时降级空态不 crash。
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // CP-TAG-FILTER：当前过滤的 tag slug（null=全量）。UI 顶部 chip 观察此 state。
    private val _activeTag = MutableStateFlow<String?>(null)
    val activeTag: StateFlow<String?> = _activeTag.asStateFlow()

    fun loadArticles() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val tag = _activeTag.value
                _articles.value = if (tag != null) {
                    repository.getArticlesByTag(tag)
                } else {
                    repository.getArticles()
                }
            } catch (e: Exception) {
                // 软降级:不 throw,设 error 让 UI 显示空态/重试按钮。
                android.util.Log.w("ArticleListViewModel", "loadArticles failed (soft-fail): ${e.message}")
                _articles.value = emptyList()
                _error.value = e.message ?: "加载失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** CP-TAG-FILTER：切换 tag 过滤；传入 null 恢复全量 */
    fun setActiveTag(tag: String?) {
        if (_activeTag.value == tag) return
        _activeTag.value = tag
        loadArticles()
    }

    /**
     * 主动重载列表 —— 不显示 loading indicator（避免列表上闪一下）。
     * 列表进入时由 LaunchedEffect 调用，用于拉取最新文章。
     */
    fun reloadArticles() {
        viewModelScope.launch {
            try {
                val tag = _activeTag.value
                _articles.value = if (tag != null) {
                    repository.getArticlesByTag(tag)
                } else {
                    repository.getArticles()
                }
                _error.value = null
                android.util.Log.i("ArticleListViewModel", "reloadArticles ok (${_articles.value.size} items, tag=$tag)")
            } catch (e: Exception) {
                android.util.Log.w("ArticleListViewModel", "reloadArticles failed: ${e.message}")
                _error.value = e.message ?: "重载失败"
            }
        }
    }

    // CP-DELETE: 长按删除。成功 → 本地列表即时移除该行（不整表刷新，避免闪动）；
    // 失败 → _error 提示（UI 侧 observe error 弹 Toast）。
    private val _deletingId = MutableStateFlow<String?>(null)
    val deletingId: StateFlow<String?> = _deletingId.asStateFlow()

    /**
     * 提示已发出后清空 error。
     *
     * 为什么需要：`_error` 是 StateFlow，**同一条错误连续发生两次时值没变** →
     * `LaunchedEffect(error)` 不再触发 → 第二次删除失败**连提示都没有**，
     * 用户以为第二次成功了。发出即清空，才能让同样的错误再次响。
     */
    fun consumeError() {
        _error.value = null
    }

    fun deleteArticle(id: String) {
        if (_deletingId.value != null) return // 串行防重复点
        _deletingId.value = id
        viewModelScope.launch {
            try {
                repository.deleteArticle(id)
                _articles.value = _articles.value.filterNot { it.id == id }
            } catch (e: Exception) {
                android.util.Log.w("ArticleListViewModel", "deleteArticle failed: ${e.message}")
                // ⚠️ 原来直接用 e.message → 弹出 "HTTP 500" 这种原始异常；
                // 而且 _error 是 StateFlow，**同一条错误再发生时值没变** →
                // LaunchedEffect(error) 不再触发 → 第二次删除失败**连提示都没有**。
                _error.value = friendlyError(e, fallback = "删除失败，请稍后重试")
            } finally {
                _deletingId.value = null
            }
        }
    }

    // ─────────────── 0041 共享（2026-10-26 产品拍板，列表页独立一份） ───────────────
    //
    // 列表页没有 ArticleDetailViewModel，所以共享对话框的状态在这里单独维护一份。
    // 逻辑与 ArticleDetailViewModel 的共享实现保持一致（标签选择器 + share/unshare），
    // 但不改动详情页那份。共享成功后把响应写回列表里那篇文章，徽标立刻消失/出现。

    private val _shareState = MutableStateFlow<ShareState>(ShareState.Idle)
    val shareState: StateFlow<ShareState> = _shareState.asStateFlow()

    /** 共享标签选择器要展示的可用标签（懒加载，只拉一次）。 */
    private val _availableTags = MutableStateFlow<List<Tag>>(emptyList())
    val availableTags: StateFlow<List<Tag>> = _availableTags.asStateFlow()

    private var _availableTagsLoaded = false

    /**
     * 拉取可用标签供共享选择器使用（懒加载，只拉一次）。
     * 共享标签是**手动选**的公开标签，从 tag 列表取，不用文章自带的 LLM 分类。
     */
    fun loadAvailableTags(force: Boolean = false) {
        if (_availableTagsLoaded && !force) return
        viewModelScope.launch {
            runCatching { tagRepository.listTags() }
                .onSuccess { tags ->
                    _availableTags.value = tags
                    _availableTagsLoaded = true
                }
                .onFailure {
                    android.util.Log.w("ArticleListViewModel", "load available tags failed: ${it.message}")
                    // 拉取失败不阻断：选择器给空列表。
                    _availableTagsLoaded = true
                }
        }
    }

    /** 共享标签选择器当前已勾选的标签 **id** 集合（UI 勾选状态）。 */
    private val _selectedTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedTags: StateFlow<Set<String>> = _selectedTags.asStateFlow()

    /** 勾选 / 取消勾选一个标签（对话框内）。 */
    fun toggleTag(tagId: String) {
        _selectedTags.update { current ->
            if (tagId in current) current - tagId else current + tagId
        }
    }

    /** 打开共享对话框时：预勾选当前已共享的标签。 */
    fun presetSelectedTags(tagIds: Set<String>) {
        _selectedTags.value = tagIds
    }

    /** 清空勾选（关对话框时调用）。 */
    fun clearSelectedTags() {
        _selectedTags.value = emptySet()
    }

    /** 关掉错误提示（对话框 Error 分支点关闭后调用）。 */
    fun dismissShareError() {
        if (_shareState.value is ShareState.Error) {
            _shareState.value = ShareState.Idle
        }
    }

    /**
     * 共享某篇文章：把 [sharedTags]（标签**中文名**）作为公开标签推给订阅者。
     * 空列表等价「取消共享」。成功后把响应写回列表里那篇，徽标立刻反映。
     */
    fun shareArticle(articleId: String, sharedTags: List<String>) {
        if (_shareState.value is ShareState.Loading) return
        _shareState.value = ShareState.Loading
        viewModelScope.launch {
            runCatching { repository.shareArticle(articleId, sharedTags) }
                .onSuccess { resp ->
                    applyShareResponse(resp)
                    _shareState.value = ShareState.Success(shared = resp.shared)
                }
                .onFailure {
                    _shareState.value = ShareState.Error(friendlyError(it, fallback = "共享失败"))
                }
        }
    }

    /** 取消共享当前文章。 */
    fun unshareArticle(articleId: String) {
        if (_shareState.value is ShareState.Loading) return
        _shareState.value = ShareState.Loading
        viewModelScope.launch {
            runCatching { repository.unshareArticle(articleId) }
                .onSuccess { resp ->
                    applyShareResponse(resp)
                    _shareState.value = ShareState.Success(shared = resp.shared)
                }
                .onFailure {
                    _shareState.value = ShareState.Error(friendlyError(it, fallback = "取消共享失败"))
                }
        }
    }

    /** 把共享响应写回列表里那篇文章，列表徽标立刻反映（不用等重新拉取）。 */
    private fun applyShareResponse(resp: ShareResponse) {
        val id = resp.id ?: return
        _articles.update { list ->
            list.map {
                if (it.id == id) it.copy(shared = resp.shared, sharedTags = resp.sharedTags) else it
            }
        }
    }
}
