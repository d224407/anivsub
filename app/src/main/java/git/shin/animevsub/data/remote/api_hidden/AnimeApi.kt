package git.shin.animevsub.data.remote.api_hidden

import git.shin.animevsub.data.local.ApiStorage
import git.shin.animevsub.data.model.*
import git.shin.animevsub.data.remote.SegmentDataInterceptor
import git.shin.animevsub.data.remote.SegmentUrlInterceptor
import git.shin.animevsub.data.remote.api.AnimeDataSource
import git.shin.animevsub.data.remote.api.AnimeDataSource.Companion.getHeaders
import git.shin.animevsub.utils.CloudflareManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URI
import java.net.URLEncoder
import java.time.Instant

/**
 * AniDB-backed data source.
 *
 * The previous AnimeVsub HTML/API implementation was tightly coupled to a
 * server which is no longer reliable. This implementation follows the same
 * source used by ani-cli:
 *   - search: https://anidb.app/browse?q=...
 *   - detail: https://anidb.app/anime/{id}
 *   - episodes: https://anidb.app/api/frontend/anime/{numericId}/episodes
 *   - streams: https://anidb.app/api/frontend/episode/{episodeId}/languages
 *              -> embed_url -> master m3u8
 *
 * AniDB does not provide the AnimeVsub-specific home/schedule/follow/comment/
 * notification APIs, so those features intentionally return empty/unsupported
 * results for now. Keep these notes until an equivalent source is selected.
 */
class AnimeApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val apiStorage: ApiStorage,
    private val cloudflareManager: CloudflareManager
) : AnimeDataSource {

    companion object {
        private const val ANI_DB = "https://anidb.app"
        private const val SEARCH = "$ANI_DB/browse?q=%s"
        private const val EPISODES = "$ANI_DB/api/frontend/anime/%s/episodes"
        private const val LANGUAGES = "$ANI_DB/api/frontend/episode/%s/languages"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override val hostCurl: String get() = "anidb.app"
    override val baseUrl: String get() = ANI_DB
    override val loginUrl: String get() = "$ANI_DB/login"

    // AniDB is public for the data we use here and has no AnimeVsub-compatible
    // application login/session API. Keep the local app account state empty.
    override fun getUser(): Flow<User?> = flow { emit(null) }

    override suspend fun refreshUser(): User =
        throw UnsupportedOperationException("AniDB source does not expose the AnimeVsub user API")

    override suspend fun logout() = Unit

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
        .header("Referer", ANI_DB + "/")
        .build()

    private suspend fun getText(url: String): String {
        val response = client.newCall(request(url)).execute()
        response.use {
            if (!it.isSuccessful) throw IllegalStateException("AniDB HTTP ${it.code}: $url")
            return it.body?.string().orEmpty()
        }
    }

    private suspend fun getDocument(url: String): Document =
        Jsoup.parse(getText(url), url)

    private fun normalizeUrl(value: String, base: String = ANI_DB): String {
        if (value.isBlank()) return ""
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> base + value
            else -> "$base/${value.trimStart('/')}"
        }
    }

    private fun animeIdFromHref(href: String): String {
        val path = runCatching { URL(normalizeUrl(href)).path }.getOrDefault(href)
        return path.substringAfter("/anime/", "")
            .substringBefore('/')
            .ifBlank { path.substringAfterLast('/').substringBefore('?') }
    }

    private fun numericAnimeId(animeId: String): String =
        animeId.substringAfterLast('-').takeIf { it.all(Char::isDigit) }
            ?: animeId.filter(Char::isDigit)

    private fun cleanText(text: String?): String =
        text.orEmpty().replace(Regex("\\s+"), " ").trim()

    private fun imageFrom(element: Element?): String {
        if (element == null) return ""
        val img = element.selectFirst("img") ?: return ""
        return listOf("data-src", "data-cfsrc", "src").firstNotNullOfOrNull {
            img.attr(it).takeIf(String::isNotBlank)
        }?.let(::normalizeUrl).orEmpty()
    }

    private fun parseAnimeCardFromAniDb(element: Element, href: String? = null): AnimeCard? {
        val link = href ?: element.selectFirst("a[href*='/anime/']")?.attr("href") ?: return null
        val id = animeIdFromHref(link)
        if (id.isBlank()) return null

        val title = cleanText(
            element.selectFirst("[title]:not(img), .title, .name, h1, h2, h3")?.text()
                ?: element.selectFirst("a[href*='/anime/']")?.text()
        )
        if (title.isBlank()) return null

        val image = imageFrom(element)
        val year = Regex("\\b(19|20)\\d{2}\\b")
            .find(cleanText(element.text()))?.value?.toIntOrNull()

        return AnimeCard(
            animeId = id,
            image = image,
            name = title,
            lastEpisode = null,
            rate = Regex("\\b\\d+(?:\\.\\d+)?\\b")
                .find(cleanText(element.selectFirst(".rating,.score,.average")?.text()))
                ?.value?.toFloatOrNull() ?: 0f,
            year = year
        )
    }

    private fun parseSearchResults(doc: Document): List<AnimeCard> {
        val results = linkedMapOf<String, AnimeCard>()
        doc.select("a[href*='/anime/']").forEach { link ->
            val cardRoot = link.closest("li")
                ?: link.closest("article")
                ?: link.parent()
                ?: link
            parseAnimeCardFromAniDb(cardRoot, link.attr("href"))?.let { results.putIfAbsent(it.animeId, it) }
        }
        return results.values.toList()
    }

    // ==================== HOME / SCHEDULE / RANKING ====================
    // TODO(ANIDB-HOME): AniDB has no equivalent AnimeVsub home page sections.
    override suspend fun getHomePage(): HomeData = HomeData(
        thisSeason = emptyList(),
        carousel = emptyList(),
        lastUpdate = emptyList(),
        preRelease = emptyList(),
        nominate = emptyList(),
        hotUpdate = emptyList()
    )

    // TODO(ANIDB-SCHEDULE): AniDB source used by ani-cli does not expose the
    // AnimeVsub daily schedule model.
    override suspend fun getSchedule(): List<ScheduleDay> = emptyList()

    // TODO(ANIDB-RANKING): No direct replacement for AnimeVsub ranking tabs.
    override suspend fun getRankings(type: String): List<AnimeCard> = emptyList()

    override suspend fun getRankingTypes(): List<FilterOption> = emptyList()

    // ==================== SEARCH ====================

    override suspend fun preSearch(keyword: String): List<SearchSuggestion> {
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword.trim(), "UTF-8").replace("+", "%20")
        val doc = getDocument(String.format(SEARCH, encoded))
        return parseSearchResults(doc).map {
            SearchSuggestion(it.animeId, it.image, it.name, "")
        }
    }

    override suspend fun search(keyword: String, page: Int): CategoryPage {
        if (keyword.isBlank() || page != 1) {
            return CategoryPage(emptyList(), if (page == 1) 1 else 0, page, keyword, keyword)
        }
        val encoded = URLEncoder.encode(keyword.trim(), "UTF-8").replace("+", "%20")
        val doc = getDocument(String.format(SEARCH, encoded))
        val items = parseSearchResults(doc)
        return CategoryPage(items, 1, 1, keyword, keyword)
    }

    // TODO(ANIDB-CATEGORY): AniDB browse filters do not map cleanly to the
    // application's AnimeVsub SelectedFilter model. Search remains supported.
    override suspend fun getCategory(filters: List<SelectedFilter>, page: Int): CategoryPage =
        CategoryPage(emptyList(), 0, page, "", "")

    override suspend fun getFilters(filters: List<SelectedFilter>): List<FilterGroup> = emptyList()

    // ==================== DETAIL ====================

    override suspend fun getAnimeDetail(animeId: String): AnimeDetail {
        val doc = getDocument("$ANI_DB/anime/$animeId")

        val title = cleanText(
            doc.selectFirst("h1, .anime-title, .title")?.text()
                ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
        ).ifBlank { animeId }

        val image = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?.let(::normalizeUrl)
            ?: imageFrom(doc.selectFirst("main, #main, .g_content, body"))

        val description = cleanText(
            doc.selectFirst("meta[property='og:description']")?.attr("content")
                ?: doc.selectFirst(".description, .synopsis, #description")?.text()
        )

        val othername = doc.selectFirst(".alternative-title, .alt-title, .synonyms")?.text()
            ?.let(::cleanText)
            ?.ifBlank { null }

        val rating = Regex("\\b\\d+(?:\\.\\d+)?\\b")
            .find(cleanText(doc.selectFirst(".rating,.score,.average")?.text()))
            ?.value?.toDoubleOrNull() ?: 0.0

        val year = Regex("\\b(19|20)\\d{2}\\b")
            .find(doc.text())?.value
            ?.let { CategoryLink(it, listOf(SelectedFilter("year", it, it))) }

        val genres = doc.select("a[href*='/genre/'], a[href*='/tag/']")
            .map { CategoryLink(cleanText(it.text())) }
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name }

        val imageValue = image.ifBlank { null }

        return AnimeDetail(
            name = title,
            othername = othername,
            image = imageValue,
            poster = imageValue,
            description = description,
            rate = rating,
            countRate = 0,
            duration = null,
            yearOf = year,
            views = 0,
            season = emptyList(),
            genre = genres,
            quality = null,
            authors = emptyList(),
            countries = emptyList(),
            follows = 0,
            language = "Japanese",
            studio = null,
            seasonOf = null,
            trailer = doc.selectFirst("iframe[src*='youtube'], a[href*='youtube.com']")
                ?.let { it.attr("src").ifBlank { it.attr("href") } },
            related = emptyList(),
            extra = mapOf("source" to "anidb", "anidbId" to numericAnimeId(animeId)),
            externalPlatforms = emptyList()
        )
    }

    override suspend fun getChapters(animeId: String): ChapterData {
        val numericId = numericAnimeId(animeId)
        if (numericId.isBlank()) throw IllegalArgumentException("Invalid AniDB anime id: $animeId")

        val detail = runCatching { getAnimeDetail(animeId) }
        val image = detail.getOrNull()?.image.orEmpty()
        val poster = detail.getOrNull()?.poster.orEmpty()

        val raw = getText(String.format(EPISODES, numericId))
        val chapters = parseAniDbEpisodes(raw)

        return ChapterData(
            chaps = chapters,
            update = if (chapters.isNotEmpty()) Triple(chapters.lastIndex + 1, chapters.size, 0) else null,
            image = image,
            poster = poster
        )
    }

    private fun parseAniDbEpisodes(raw: String): List<ChapterInfo> {
        val result = mutableListOf<ChapterInfo>()
        val jsonLike = raw.replace("},{", "}\n{")
        val regex = Regex("\\\"id\\\"\\s*:\\s*(\\d+).*?\\\"number\\\"\\s*:\\s*(\\d+)")
        regex.findAll(jsonLike).forEach { match ->
            val id = match.groupValues[1]
            val number = match.groupValues[2]
            result += ChapterInfo(
                id = id,
                name = number,
                extra = mapOf("source" to "anidb", "number" to number)
            )
        }
        return result.distinctBy { it.id }
    }

    // ==================== PLAYER ====================

    override suspend fun getServers(chapter: ChapterInfo): List<ServerInfo> {
        return listOf(
            ServerInfo(
                name = "AniDB",
                extra = mapOf(
                    "episodeId" to chapter.id,
                    "number" to (chapter.extra["number"] ?: chapter.name)
                )
            )
        )
    }

    override suspend fun getPlayerLink(server: ServerInfo): PlayerData {
        val episodeId = server.extra["episodeId"]
            ?: throw IllegalArgumentException("AniDB episode id is missing")

        val languageJson = getText(String.format(LANGUAGES, episodeId))
        val langEntry = findLanguageEntry(languageJson, preferred = "jpn")
            ?: findLanguageEntry(languageJson, preferred = "eng")
            ?: throw IllegalStateException("AniDB returned no playable source for episode $episodeId")

        val embedUrl = normalizeUrl(langEntry)
        val embedHtml = getText(embedUrl)

        val master = Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""", RegexOption.IGNORE_CASE)
            .find(embedHtml)?.groupValues?.getOrNull(1)
            ?: Regex("""https?://[^'"\s]+\.m3u8[^'"\s]*""", RegexOption.IGNORE_CASE)
                .find(embedHtml)?.value
            ?: throw IllegalStateException("AniDB embed did not expose a master m3u8 URL")

        val masterUrl = runCatching {
            URI(embedUrl).resolve(master).toString()
        }.getOrElse { normalizeUrl(master, embedUrl.substringBeforeLast('/')) }

        return PlayerData(
            link = masterUrl,
            type = "hls",
            headers = getHeaders(embedUrl),
            isContent = false
        )
    }

    private fun findLanguageEntry(raw: String, preferred: String): String? {
        val block = raw.split("},{", "}, {")
            .firstOrNull { it.contains("\"$preferred\"") }
            ?: return null
        return Regex("\\\"embed_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(block)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
    }

    override val segmentUrlInterceptor: SegmentUrlInterceptor? = null
    override val segmentDataInterceptor: SegmentDataInterceptor? = null

    // AniDB does not expose AnimeVsub's skip-intro endpoint.
    override suspend fun getEpisodeSkip(
        animeId: String,
        detail: AnimeDetail,
        chapter: ChapterInfo
    ): InOutroEpisode? = null

    // ==================== FOLLOWS ====================
    // TODO(ANIDB-FOLLOW): requires a separate authenticated provider.
    override suspend fun getFollows(filters: List<SelectedFilter>, page: Int): CategoryPage =
        CategoryPage(emptyList(), 0, page)

    override suspend fun getFollowFilters(filters: List<SelectedFilter>): List<FilterGroup> = emptyList()

    override suspend fun checkFollow(animeId: String): Boolean = false

    override suspend fun toggleFollow(animeId: String, follow: Boolean) {
        throw UnsupportedOperationException("Follow is not implemented for AniDB source")
    }

    // ==================== NOTIFICATIONS ====================
    // TODO(ANIDB-NOTIFICATIONS): no equivalent source.
    override suspend fun getNotifications(): NotificationData =
        NotificationData(items = emptyList(), max = 0)

    override suspend fun onTrigger(trigger: Trigger) = Unit

    // ==================== COMMENTS ====================
    // TODO(ANIDB-COMMENTS): AniDB does not provide the AnimeVsub comment API.
    override suspend fun getComments(
        filmId: String,
        anime: AnimeDetail,
        sort: FilterOption?,
        offset: Int
    ): CommentResponse = CommentResponse(false, error = "Comments are not available on AniDB")

    override suspend fun getReplies(
        commentId: String,
        sort: FilterOption?,
        offset: Int
    ): ReplyResponse = ReplyResponse(false, error = "Replies are not available on AniDB")

    override suspend fun postComment(
        filmId: String,
        content: String,
        isSpoiler: Boolean,
        episodeId: String?,
        parentId: String,
        threadKey: String?
    ): PostCommentResponse = PostCommentResponse(false, error = "Comments are not available on AniDB")

    override suspend fun voteComment(commentId: String, voteType: VoteType): VoteResponse =
        VoteResponse(false, error = "Comment voting is not available on AniDB")

    override suspend fun editComment(
        commentId: String,
        content: String,
        isSpoiler: Boolean
    ): EditCommentResponse = EditCommentResponse(false, error = "Comments are not available on AniDB")

    override suspend fun getCommentSortOptions(): List<FilterOption> = emptyList()

    override fun encodeURI(url: String): String = URLEncoder.encode(url, "UTF-8")

    override fun decodeURI(url: String): String =
        runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
}
