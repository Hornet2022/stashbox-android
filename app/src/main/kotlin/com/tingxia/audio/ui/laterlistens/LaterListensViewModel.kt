package com.tingxia.audio.ui.laterlistens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.remote.LaterListen
import com.tingxia.audio.data.repository.FavoritesRepository
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
                    error = e.message ?: "加载失败",
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
                }
            } catch (_: Exception) {
                // silent failure
            }
        }
    }
}
