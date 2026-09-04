package git.shin.animevsub.data.remote.api_hidden

import git.shin.animevsub.data.model.CommentResponse
import kotlinx.serialization.Serializable

@Serializable
data class ApiCommentResponse(
    val success: Boolean,
    val comments: List<ApiComment> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null
) {
    fun toCommentResponse(): CommentResponse {
        return CommentResponse(
            total = total,
            success = success,
            comments = comments.map { it.toComment() },
            offset = offset,
            error = error,
            hasMore = hasMore
        )
    }
}