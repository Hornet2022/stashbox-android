package com.tingxia.audio.ui.feedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.remote.FeedbackCategory
import com.tingxia.audio.data.remote.FeedbackItem
import com.tingxia.audio.data.repository.FeedbackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedbackHistoryUiState(
    val feedbacks: List<FeedbackItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class FeedbackHistoryViewModel @Inject constructor(
    private val repository: FeedbackRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedbackHistoryUiState())
    val uiState: StateFlow<FeedbackHistoryUiState> = _uiState.asStateFlow()

    init {
        loadFeedbacks()
    }

    fun loadFeedbacks(category: FeedbackCategory? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val feedbacks = repository.list(category)
                _uiState.value = _uiState.value.copy(
                    feedbacks = feedbacks,
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
}
