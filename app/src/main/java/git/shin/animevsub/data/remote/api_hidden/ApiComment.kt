import kotlin.time.Duration.Companion.milliseconds
package git.shin.animevsub.data.remote.api_hidden

import git.shin.animevsub.data.model.Comment
import git.shin.animevsub.data.model.CommentBadge
import git.shin.animevsub.data.model.VoteType
import kotlinx.serialization.Serializable

@Serializable
data class ApiComment(
    val id: Int,
    val userId: Int,
    val userName: String,
    val userAvatar: String,
    val content: String,
    val isSpoiler: Boolean = false,
    val isPending: Boolean = false,
    val isPinned: Boolean = false,
    val isGlobalPinned: Boolean = false,
    val createdAt: Long,
    val editedAt: Long? = null,
    val votesUp: Int = 0,
    val votesDown: Int = 0,
    val repliesCount: Int = 0,
    val userVote: Int = -1,
    val badges: List<CommentBadge> = emptyList(),
    val threadKey: String? = null,
    val parentId: String? = null,
    val isHidden: Boolean = false,
    val hideReason: String? = null
) {
    fun toComment(): Comment {
        return Comment(
            id = id.toString(),
            userId = userId,
            userName = userName,
            userAvatar = userAvatar,
            content = content,
            isSpoiler = isSpoiler,
            isPending = isPending,
            isPinned = isPinned,
            isGlobalPinned = isGlobalPinned,
            createdAt = createdAt,
            editedAt = editedAt,
            votesUp = votesUp,
            votesDown = votesDown,
            repliesCount = repliesCount,
            userVote = when (userVote) {
                0 -> VoteType.NONE
                1 -> VoteType.UP
                else -> VoteType.DOWN
            },
            badges = badges,
            threadKey = threadKey,
            parentId = parentId,
            isHidden = isHidden,
            hideReason = hideReason
        )
    }
}