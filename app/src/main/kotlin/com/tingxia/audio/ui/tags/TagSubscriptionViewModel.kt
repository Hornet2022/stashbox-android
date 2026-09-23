package com.tingxia.audio.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.model.Tag
import com.tingxia.audio.data.repository.TagRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TagSubscriptionUiState(
    val isLoading: Boolean = false,
    val tags: List<Tag> = emptyList(),
    val subscribedIds: Set<String> = emptySet(),
    val error: String? = null,
)

@HiltViewModel
class TagSubscriptionViewModel @Inject constructor(
    private val repository: TagRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TagSubscriptionUiState())
    val uiState: StateFlow<TagSubscriptionUiState> = _uiState.asStateFlow()

    init {
        loadTags()
    }

    fun loadTags() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val tags = repository.listTags()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    tags = tags,
                    // P1-1：从服务端响应回填订阅状态，保证 Switch 立即正确显示并可"取消订阅"。
                    subscribedIds = tags.filter { it.subscribed }.map { it.id }.toSet(),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = friendlyError(e, fallback = "加载失败"),
                )
            }
        }
    }

    fun toggleTag(tagIdOrSlug: String) {
        val isSubscribed = _uiState.value.subscribedIds.contains(tagIdOrSlug)
        // Optimistic update
        val newSubscribedIds = if (isSubscribed) {
            _uiState.value.subscribedIds - tagIdOrSlug
        } else {
            _uiState.value.subscribedIds + tagIdOrSlug
        }
        _uiState.value = _uiState.value.copy(subscribedIds = newSubscribedIds)

        viewModelScope.launch {
            try {
                if (isSubscribed) {
                    repository.unsubscribe(tagIdOrSlug)
                } else {
                    repository.subscribe(tagIdOrSlug)
                }
            } catch (e: Exception) {
                // Rollback on failure
                _uiState.value = _uiState.value.copy(
                    subscribedIds = if (isSubscribed) {
                        _uiState.value.subscribedIds + tagIdOrSlug
                    } else {
                        _uiState.value.subscribedIds - tagIdOrSlug
                    },
                    error = friendlyError(e, fallback = "操作失败，请稍后再试"),
                )
            }
        }
    }
}
