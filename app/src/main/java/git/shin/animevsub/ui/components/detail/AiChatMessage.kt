package git.shin.animevsub.ui.components.detail
import kotlin.time.Duration.Companion.milliseconds
data class AiChatMessage(
  val id: String = System.currentTimeMillis().toString(),
  val content: String,
  val isFromUser: Boolean,
  val isLoading: Boolean = false
)
