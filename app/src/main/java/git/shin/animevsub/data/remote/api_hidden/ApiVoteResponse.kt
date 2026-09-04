package git.shin.animevsub.data.remote.api_hidden

import kotlinx.serialization.Serializable

@Serializable
data class ApiVoteResponse(
    val success: Boolean,
    val votesUp: Int = 0,
    val votesDown: Int = 0,
    val error: String? = null
)