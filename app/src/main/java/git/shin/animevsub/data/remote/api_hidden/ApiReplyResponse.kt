package git.shin.animevsub.data.remote.api_hidden

import git.shin.animevsub.data.model.ReplyResponse
import kotlinx.serialization.Serializable

@Serializable
data class ApiReplyResponse(
    val success: Boolean,
    val replies: List<ApiComment> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null
) {
    fun toReplyResponse(): ReplyResponse {
        return ReplyResponse(
            total = total,
            success = success,
            replies = replies.map { it.toComment() },
            offset = offset,
            error = error,
            hasMore = hasMore
        )
    }
}