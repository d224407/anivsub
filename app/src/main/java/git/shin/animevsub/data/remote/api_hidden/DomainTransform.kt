import kotlin.time.Duration.Companion.milliseconds
package git.shin.animevsub.data.remote.api_hidden

import kotlinx.serialization.Serializable

@Serializable
data class DomainTransform(
    val scheme: String,
    val host: String,
    val name: String
)