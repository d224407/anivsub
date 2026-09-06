package git.shin.animevsub.data.remote.api_hidden
import kotlinx.serialization.Serializable
@Serializable
data class EpisodeItem(
  val id: String,
  val order: String,
  val name: String,
  val title: String? = null
)
