package git.shin.animevsub.ui.screens.home
import kotlin.time.Duration.Companion.milliseconds
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import git.shin.animevsub.data.model.HomeData
import git.shin.animevsub.data.repository.AnimeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
sealed class HomeUiState {
    object Loading : HomeUiState()
    data class Success(val data: HomeData) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: AnimeRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    init {
        loadHomeData()
    }
    fun loadHomeData() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            val result = repository.getHomePage()
            result.onSuccess { data ->
                _uiState.value = HomeUiState.Success(data)
            }.onFailure { error ->
                _uiState.value = HomeUiState.Error(error.message ?: "Unknown error")
            }
        }
    }
}