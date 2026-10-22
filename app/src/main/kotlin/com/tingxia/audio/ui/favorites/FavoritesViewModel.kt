package com.tingxia.audio.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.remote.Favorite
import com.tingxia.audio.data.remote.FolderCount
import com.tingxia.audio.data.repository.FavoritesRepository
import com.tingxia.audio.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoritesUiState(
    val favorites: List<Favorite> = emptyList(),
    val folders: List<FolderCount> = emptyList(),
    val selectedFolder: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val repository: FavoritesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init {
        loadFolders()
        loadFavorites()
    }

    fun loadFolders() {
        viewModelScope.launch {
            try {
                val folders = repository.listFolders()
                _uiState.value = _uiState.value.copy(folders = folders)
            } catch (_: Exception) {
                // folders load failure is non-critical
            }
        }
    }

    fun loadFavorites(folder: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, selectedFolder = folder)
            try {
                val favorites = repository.listFavorites(folder)
                _uiState.value = _uiState.value.copy(
                    favorites = favorites,
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

    fun selectFolder(folder: String?) {
        loadFavorites(folder)
    }

    fun deleteFavorite(favoriteId: Int) {
        viewModelScope.launch {
            try {
                val ok = repository.deleteFavorite(favoriteId)
                if (ok) {
                    _uiState.value = _uiState.value.copy(
                        favorites = _uiState.value.favorites.filter { it.id != favoriteId },
                    )
                } else {
                    // ⚠️ ok == false 时原来既不移除也不报错 → 弹窗关了、条目还在、
                    // 屏幕零变化，用户以为删掉了（其实还在服务端）。
                    _uiState.value = _uiState.value.copy(error = "删除失败，请稍后重试")
                }
            } catch (e: Exception) {
                // 屏幕本来就有 Toast 通路（FavoritesScreen 的 LaunchedEffect(error)），
                // 但这里从不给 error 赋值 → 那条通路永远不触发。补上。
                _uiState.value = _uiState.value.copy(
                    error = friendlyError(e, fallback = "删除失败，请稍后重试"),
                )
            }
        }
    }
}
