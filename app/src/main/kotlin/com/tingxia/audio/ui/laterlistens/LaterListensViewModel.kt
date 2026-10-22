package com.tingxia.audio.ui.laterlistens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.remote.LaterListen
import com.tingxia.audio.data.repository.FavoritesRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LaterListensUiState(
    val laterListens: List<LaterListen> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LaterListensViewModel @Inject constructor(
    private val repository: FavoritesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LaterListensUiState())
    val uiState: StateFlow<LaterListensUiState> = _uiState.asStateFlow()

    init {
        loadLaterListens()
    }

    fun loadLaterListens() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val laterListens = repository.listLaterListens()
                _uiState.value = _uiState.value.copy(
                    laterListens = laterListens,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = friendlyError(e, fallback = "加载失败"),
                )
            }
        }
    }

    fun unsnooze(articleId: String) {
        viewModelScope.launch {
            try {
                val ok = repository.unsnooze(articleId)
                if (ok) {
                    _uiState.value = _uiState.value.copy(
                        laterListens = _uiState.value.laterListens.filter { it.article_id != articleId },
                    )
                } else {
                    // ⚠️ ok == false 时原来既不移除也不报错 → 条目不消失、无提示，
                    // 用户反复点多少次都一样，像 App 坏了。
                    _uiState.value = _uiState.value.copy(error = "取消失败，请稍后重试")
                }
            } catch (e: Exception) {
                // 屏幕本来就有 Toast 通路（LaterListensScreen 的 LaunchedEffect(error)），
                // 但这里从不给 error 赋值 → 那条通路永远不触发。补上。
                _uiState.value = _uiState.value.copy(
                    error = friendlyError(e, fallback = "取消失败，请稍后重试"),
                )
            }
        }
    }
}
