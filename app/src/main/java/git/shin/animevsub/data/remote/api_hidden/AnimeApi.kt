package git.shin.animevsub.data.remote.api_hidden
import android.webkit.CookieManager
import git.shin.animevsub.data.model.*
import git.shin.animevsub.data.remote.api.AnimeDataSource
import git.shin.animevsub.utils.CloudflareManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element
import java.net.URL
import java.util.*
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
class AnimeApi(
  private val client: OkHttpClient,
  private val json: Json,
  private val cloudflareManager: CloudflareManager
) : AnimeDataSource {
  companion object {
    private var currentDomain = "animevietsub.pl"
    private var isInitialized = false
    private val retryCodes = setOf(
      203, 204, 301, 302, 401, 402, 403, 404, 405, 406, 407, 408, 409, 429,
      451, 500, 501, 502, 503, 504, 505, 506, 507, 508, 509, 510, 511,
      520, 521, 522, 523, 524, 525, 526, 527
    )
  }
  override val hostCurl: String get() = currentDomain
  override val baseUrl: String get() = "https://$currentDomain"
  override val loginUrl: String get() = "$baseUrl/login"
  private suspend fun initDomain() {
    if (isInitialized) return
    // TODO: Implement domain initialization from remote config
    isInitialized = true
  }
  fun getFullUrl(path: String): String = baseUrl + path
  fun parseAnimeCard(element: Element): AnimeCard {
    val linkElement = element.selectFirst("a")
    val href = linkElement?.attr("href") ?: ""
    val path = extractPath(href)
    val animeId = path.substringAfterLast("/phim/").substringBefore("/")
    val imgElement = element.selectFirst("img")
    val image = imgElement?.attr("data-cfsrc") ?: imgElement?.attr("src") ?: ""
    val titleElement = element.selectFirst(".Title")
    val name = titleElement?.text() ?: ""
    val episodeElement = element.selectFirst(".mli-eps > i")
    var episode = episodeElement?.text() ?: ""
    if (episode == "TẤT") episode = "Full Season"
    val lastEpisode = ChapterInfo(episode, episode)
    val ratingElement = element.selectFirst(".anime-avg-user-rating")
    var rating = ratingElement?.text()?.toFloatOrNull() ?: 0f
    if (rating == 0f) {
      val starElement = element.selectFirst(".AAIco-star")
      rating = starElement?.text()?.toFloatOrNull() ?: 0f
    }
    val yearElement = element.selectFirst(".Year")
    val year = yearElement?.text()?.let {
      Regex("\\d+").find(it)?.value?.toIntOrNull()
    }
    val qualityElement = element.selectFirst(".Qlty") ?: element.selectFirst(".mli-quality")
    val quality = qualityElement?.text()
    val timeElement = element.selectFirst(".AAIco-access_time")
    val process = timeElement?.text()
    val descElement = element.selectFirst(".Description > p")
    val description = descElement?.text()
    val studioElement = element.selectFirst(".Studio")
    val studio = studioElement?.text()?.split(":")?.lastOrNull()?.trim()
    val genre = element.select(".Genre > a").map { parseCategoryLink(it) }
    return AnimeCard(
      animeId = animeId,
      image = image,
      name = name,
      lastEpisode = lastEpisode,
      rate = rating,
      year = year,
      quality = quality,
      process = process,
      description = description,
      studio = studio,
      genre = genre
    )
  }
  fun parseCategoryLink(element: Element): CategoryLink {
    val href = element.attr("href")
    val path = extractPath(href)
    val name = element.text()
    val segments = path.substringAfter("/").split("/").filter { it.isNotEmpty() }
    val mapping = mapOf(
      "the-loai" to "genres",
      "quoc-gia" to "country",
      "nam-phat-hanh" to "year",
      "studio" to "studio",
      "phim-le" to "danh-sach",
      "phim-bo" to "danh-sach",
      "anime-sap-chieu" to "danh-sach",
      "anime-moi-vsub" to "danh-sach",
      "anime-tron-bo" to "danh-sach",
      "anime-le" to "danh-sach",
      "anime-bo" to "danh-sach",
      "anime-dang-chieu" to "danh-sach"
    )
    val filters = if (segments.size >= 2) {
      val key = mapping[segments[0]] ?: segments[0]
      listOf(SelectedFilter(key, segments[1], name))
    } else if (segments.isNotEmpty() && segments[0].isNotEmpty()) {
      val key = mapping[segments[0]] ?: "danh-sach"
      listOf(SelectedFilter(key, segments[0], name, true))
    } else {
      emptyList()
    }
    return CategoryLink(name, filters)
  }
  private fun extractPath(url: String): String = try {
    URL(url).path
  } catch (_: Exception) {
    var path = url.removePrefix(baseUrl)
    if (!path.startsWith("/")) {
      path = "/$path"
    }
    path
  }
  private fun syncCookies(fromUrl: String, toUrl: String) {
    val cookieManager = CookieManager.getInstance()
    val fromFull = "https://$fromUrl"
    val toFull = "https://$toUrl"
    val cookies = cookieManager.getCookie(fromFull) ?: return
    cookies.split(";").forEach { cookie ->
      val trimmed = cookie.trim()
      if (trimmed.isNotEmpty()) {
        cookieManager.setCookie(toFull, trimmed)
      }
    }
    cookieManager.flush()
  }

  @Throws(Exception::class)
  private fun decryptAesGcm(
    encrypted: String,
    keyBase64: String,
    iv: String,
    stag: String,
    rtag: String,
    shadow: Boolean
  ): String {
    val keyBytes = decodeBase64(keyBase64)
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
    val data = if (shadow) {
      "$stag:$rtag:$iv:0"
    } else {
      "$stag:$rtag:$iv"
    }
    val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
    val secretKey = SecretKeySpec(hash, "AES")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val gcmSpec = GCMParameterSpec(128, keyBytes)
    cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
    val encryptedBytes = decodeBase64(encrypted.replace("-", "+").replace("_", "/"))
    try {
      val decrypted = cipher.doFinal(encryptedBytes)
      return if (shadow) {
        val prefix = "$iv:$stag:$rtag:"
        val result = String(decrypted, Charsets.UTF_8)
        result.substringAfter(prefix)
      } else {
        String(decrypted, Charsets.UTF_8)
      }
    } catch (e: Exception) {
      if (!shadow) throw e
      val maxLen = max(0, encryptedBytes.size - 16)
      val noise = "$iv:$stag:$rtag:$maxLen:noise"
      var hash2 = -2128831035
      for (ch in noise) {
        hash2 = (hash2 xor ch.code) * 16777619
      }
      if (hash2 == 0) hash2 = 1
      val result = ByteArray(maxLen)
      var seed = hash2
      for (i in 0 until maxLen) {
        val bit = i and 3
        if (bit == 0) {
          seed = (seed shl 13) xor seed
          seed = seed xor (seed ushr 17)
          seed = seed xor (seed shl 5)
        }
        result[i] = (seed ushr (bit * 8)).toByte()
      }
      return String(result, Charsets.UTF_8)
    }
  }
  private fun decodeBase64(input: String): ByteArray {
    val padded = input + "=".repeat((4 - input.length % 4) % 4)
    return Base64.getDecoder().decode(padded)
  }
  fun replaceDomain(input: String): String {
    val pattern = Regex("animevietsub\\.(\\w+)")
    return pattern.replace(input, currentDomain)
  }

  // ==================== AnimeDataSource Methods ====================
  override suspend fun getUser(): Flow<User?> = flowOf(null)
  override suspend fun refreshUser(): User = throw UnsupportedOperationException("Not implemented yet")
  override suspend fun logout() {
    // TODO: Implement actual logic
  }
  override suspend fun getHomePage(): HomeData = throw UnsupportedOperationException("Not implemented yet")
  override suspend fun getSchedule(): List<ScheduleDay> = emptyList()
  override suspend fun getRankings(type: String): List<AnimeCard> = emptyList()
  override suspend fun getRankingTypes(): List<FilterOption> = emptyList()
  override suspend fun preSearch(keyword: String): List<SearchSuggestion> = emptyList()
  override suspend fun search(keyword: String, page: Int): CategoryPage = CategoryPage(emptyList(), 0, 0)
  override suspend fun getCategory(filters: List<SelectedFilter>, page: Int): CategoryPage = CategoryPage(emptyList(), 0, 0)
  override suspend fun getFilters(filters: List<SelectedFilter>): List<FilterGroup> = emptyList()
  override suspend fun getAnimeDetail(animeId: String): AnimeDetail = throw UnsupportedOperationException("Not implemented yet")
  override suspend fun getChapters(animeId: String): ChapterData = throw UnsupportedOperationException("Not implemented yet")
  override suspend fun getServers(chapter: ChapterInfo): List<ServerInfo> = emptyList()
  override suspend fun getPlayerLink(server: ServerInfo): PlayerData = throw UnsupportedOperationException("Not implemented yet")
  override suspend fun getEpisodeSkip(
    animeId: String,
    detail: AnimeDetail,
    chapter: ChapterInfo
  ): InOutroEpisode? = null
  override suspend fun getFollows(filters: List<SelectedFilter>, page: Int): CategoryPage = CategoryPage(emptyList(), 0, 0)
  override suspend fun getFollowFilters(filters: List<SelectedFilter>): List<FilterGroup> = emptyList()
  override suspend fun checkFollow(animeId: String): Boolean = false
  override suspend fun toggleFollow(animeId: String, follow: Boolean) {
    // TODO: Implement actual logic
  }
  override suspend fun getNotifications(): NotificationData = throw UnsupportedOperationException("Not implemented yet")
  override suspend fun onTrigger(trigger: Trigger) {
    // TODO: Implement actual logic
  }
  override suspend fun getComments(
    filmId: String,
    anime: AnimeDetail,
    sort: FilterOption?,
    offset: Int
  ): CommentResponse = throw UnsupportedOperationException("Not implemented yet")
  override suspend fun getReplies(
    commentId: String,
    sort: FilterOption?,
    offset: Int
  ): ReplyResponse = throw UnsupportedOperationException("Not implemented yet")
  override suspend fun postComment(
    filmId: String,
    content: String,
    isSpoiler: Boolean,
    episodeId: String?,
    parentId: String,
    threadKey: String?
  ): PostCommentResponse = throw UnsupportedOperationException("Not implemented yet")
  override suspend fun voteComment(commentId: String, voteType: VoteType): VoteResponse = throw UnsupportedOperationException("Not implemented yet")
  override suspend fun editComment(
    commentId: String,
    content: String,
    isSpoiler: Boolean
  ): EditCommentResponse = throw UnsupportedOperationException("Not implemented yet")
  override suspend fun getCommentSortOptions(): List<FilterOption> = emptyList()
  override fun encodeURI(url: String): String = URL(url).toString()
  override fun decodeURI(url: String): String = URL(url).toString()
}
