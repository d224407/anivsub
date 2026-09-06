package git.shin.animevsub.data.remote.api_hidden
import kotlinx.serialization.Serializable
@Serializable
data class ApiPostCommentResponse(
  val success: Boolean,
  val comment: ApiComment? = null,
  val total: Int? = null,
  val pending: Boolean = false,
  val error: String? = null
)
