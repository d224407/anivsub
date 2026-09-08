package git.shin.animevsub.ui.screens.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import git.shin.animevsub.data.local.PreferencesManager
import git.shin.animevsub.data.remote.api.AnimeDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject

data class AboutUiState(
  val error: String? = null,
  val isDeveloperMode: Boolean = false,
  val hideDonationPopup: Boolean = false,
  val loginUrl: String = ""
)

@HiltViewModel
class AboutViewModel @Inject constructor(
  private val preferencesManager: PreferencesManager,
  private val animeDataSource: AnimeDataSource
) : ViewModel() {

  private val internalState = MutableStateFlow(AboutUiState())

  val uiState: StateFlow<AboutUiState> = combine(
    internalState,
    preferencesManager.developerMode,
    preferencesManager.hideDonationPopup
  ) { internal, devMode, hideDonation ->
    internal.copy(
      isDeveloperMode = devMode,
      hideDonationPopup = hideDonation,
      loginUrl = if (devMode) animeDataSource.loginUrl else ""
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = AboutUiState()
  )

  fun enableDeveloperMode(password: String): Boolean {
    val hash = MessageDigest.getInstance("SHA-256")
      .digest(password.toByteArray())
      .joinToString("") { "%02x".format(it) }

    return if (hash == git.shin.animevsub.BuildConfig.DEV_PWD_HASH) {
      viewModelScope.launch {
        preferencesManager.setDeveloperMode(true)
      }
      true
    } else {
      false
    }
  }

  fun setHideDonationPopup(hide: Boolean) {
    viewModelScope.launch {
      preferencesManager.setHideDonationPopup(hide)
    }
  }

}
