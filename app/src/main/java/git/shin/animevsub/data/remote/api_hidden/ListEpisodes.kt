import kotlin.time.Duration.Companion.milliseconds
package git.shin.animevsub.data.remote.api_hidden

import kotlinx.serialization.Serializable

@Serializable
data class ListEpisodes(
    val poster: String,
    val progress: EpisodeProgress,
    val name: String,
    val jName: String? = null,
    val id: String,
    val list: List<EpisodeItem>
)