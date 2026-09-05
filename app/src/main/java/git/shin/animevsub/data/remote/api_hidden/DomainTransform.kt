package git.shin.animevsub.data.remote.api_hidden
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.Serializable
@Serializable
data class DomainTransform(
    val scheme: String,
    val host: String,
    val name: String
)