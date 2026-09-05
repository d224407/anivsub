package git.shin.animevsub.data.remote
import kotlin.time.Duration.Companion.milliseconds
import git.shin.animevsub.data.model.PlayerData
import git.shin.animevsub.data.model.ServerInfo
fun interface SegmentUrlInterceptor {
  fun intercept(server: ServerInfo, playerData: PlayerData, requestUrl: String): String
}
