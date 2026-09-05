package git.shin.animevsub.data.remote.api_hidden
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.Serializable
@Serializable
data class ApiEditCommentResponse(
    val success: Boolean,
    val content: String? = null,
    val isSpoiler: Boolean = false,
    val editedAt: Long? = null,
    val pending: Boolean = false,
    val error: String? = null
)