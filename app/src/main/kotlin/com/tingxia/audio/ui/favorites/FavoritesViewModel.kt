package com.tingxia.audio.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingxia.audio.data.remote.Favorite
import com.tingxia.audio.data.remote.FolderCount
import com.tingxia.audio.data.repository.FavoritesRepository
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
                    error = e.message ?: "加载失败",
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
                }
            } catch (_: Exception) {
                // silent failure
            }
        }
    }
}
