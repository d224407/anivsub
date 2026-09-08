package git.shin.animevsub.data.remote.api_hidden

import android.webkit.CookieManager
import git.shin.animevsub.data.local.ApiStorage
import git.shin.animevsub.data.model.*
import git.shin.animevsub.data.remote.SegmentDataInterceptor
import git.shin.animevsub.data.remote.SegmentUrlInterceptor
import git.shin.animevsub.data.remote.api.AnimeDataSource
import git.shin.animevsub.utils.CloudflareManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

/**
 * Restored implementation of the hidden AnimeVsub API.
 *
 * This file deliberately keeps the public AnimeDataSource contract intact.
 * The HTML parsing, token extraction and AES-GCM routine are reconstructed
 * from the supplied Kotlin/Java sources.  Where JADX lost the original
 * coroutine body, this implementation uses the observable data contract and
 * resilient HTML/HTTP fallbacks instead of throwing "Not implemented yet".
 */
@Serializable
data class LoginResponse(
    val success: Boolean,
    val message: String? = null,
    val user: User? = null,
    val token: String? = null
)

class AnimeApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val apiStorage: ApiStorage,
    private val cloudflareManager: CloudflareManager
) : AnimeDataSource {

    companion object {
        private var currentDomain = "animevietsub.li"
        private var isInitialized = false

        private const val DYNAMIC_HOST = "dynamic_host"

        // TODO(API-DOMAIN): The APK proves that dynamic_host is read from
        // ApiStorage, but the exact code which refreshes that value from
        // https://bit.ly/animevietsubtv has not been recovered yet.
        // Do not hard-code a permanent domain beyond this safe fallback.

        private fun origin(url: String): String {
            return try {
                val normalized = if (url.contains("://")) url else "https://$url"
                val uri = URI(normalized)
                if (uri.port == -1) "${uri.scheme}://${uri.host}"
                else "${uri.scheme}://${uri.host}:${uri.port}"
            } catch (_: Exception) {
                url.substringBefore('/', url).let { "https://$it" }
            }
        }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

        private fun decodeBase64Url(value: String): ByteArray {
            val normalized = value
                .replace('-', '+')
                .replace('_', '/')
                .let { it + "=".repeat((4 - it.length % 4) % 4) }
            return Base64.getDecoder().decode(normalized)
        }

        private fun extractIdFromPath(href: String): String {
            val path = try {
                URL(href).path
            } catch (_: Exception) {
                href
            }
            return path.substringAfter("/phim/").substringBefore('/').ifBlank {
                path.substringAfterLast('/').ifBlank { href }
            }
        }

        fun replaceDomain(input: String): String =
            Regex("animevietsub\\.(\\w+)").replace(input, currentDomain)

        /**
         * Reconstructed exactly from the supplied Java decompile:
         *
         * key = Base64(keyBase64)
         * hmac = HMAC-SHA256(key, "$stag:$rtag:$iv" [+ ":0"])
         * AES key = hmac
         * GCM IV = first 12 bytes of decoded key
         */
        @Throws(Exception::class)
        fun decryptAesGcm(
            encrypted: String,
            keyBase64: String,
            iv: String,
            stag: String,
            rtag: String,
            shadow: Boolean
        ): String {
            val keyBytes = decodeBase64Url(keyBase64)

            require(keyBytes.size >= 12) {
                "AES-GCM key material must contain at least 12 bytes"
            }

            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))

            val material = if (shadow) {
                "$stag:$rtag:$iv:0"
            } else {
                "$stag:$rtag:$iv"
            }

            val aesKey = SecretKeySpec(
                mac.doFinal(material.toByteArray(StandardCharsets.UTF_8)),
                "AES"
            )

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                aesKey,
                GCMParameterSpec(128, keyBytes.copyOfRange(0, 12))
            )

            val encryptedBytes = decodeBase64Url(encrypted)

            return try {
                val plain = cipher.doFinal(encryptedBytes)
                val text = String(plain, StandardCharsets.UTF_8)
                if (shadow) {
                    val prefix = "$iv:$stag:$rtag:"
                    text.substringAfter(prefix, text)
                } else {
                    text
                }
            } catch (e: Exception) {
                if (!shadow) throw e

                // Same deterministic noise fallback shape as the supplied Java.
                val maxLen = max(0, encryptedBytes.size - 16)
                val seedText = "AES-GCM:$iv:$stag:$rtag:$maxLen:noise"
                var hash = -2128831035
                for (ch in seedText) {
                    hash = (hash xor (ch.code and 0xff)) * 16777619
                }
                if (hash == 0) hash = 1

                val result = ByteArray(maxLen)
                var state = hash
                for (i in result.indices) {
                    val part = i and 3
                    if (part == 0) {
                        state = (state shl 13) xor state
                        state = state xor (state ushr 17)
                        state = state xor (state shl 5)
                    }
                    result[i] = (state ushr (part * 8)).toByte()
                }
                String(result, StandardCharsets.UTF_8)
            }
        }

        fun getHeaders(url: String, ignoreUserAgent: Boolean = false): Map<String, String> {
            val headers = mutableMapOf<String, String>()
            if (!ignoreUserAgent) {
                headers["User-Agent"] = CloudflareManager.getCurrentUserAgent()
            }
            headers["Referer"] = origin(url)
            return headers
        }

        fun extractBackgroundImage(style: String): String =
            Regex("""background-image\s*:\s*url\(['"]?([^'")]+)['"]?\)""")
                .find(style)
                ?.groupValues
                ?.getOrNull(1)
                ?: ""
    }

    override val hostCurl: String
        get() = currentDomain

    override val baseUrl: String
        get() = "https://$currentDomain"

    override val loginUrl: String
        get() = "$baseUrl/account/login/"

    override val segmentUrlInterceptor: SegmentUrlInterceptor?
        get() = null

    override val segmentDataInterceptor: SegmentDataInterceptor?
        get() = null

    private suspend fun ensureDomain(): String {
        if (!isInitialized) {
            try {
                val saved = apiStorage.getString(DYNAMIC_HOST).firstOrNull()
                if (!saved.isNullOrBlank()) {
                    currentDomain = saved.removePrefix("https://")
                        .removePrefix("http://")
                        .trimEnd('/')
                }
            } catch (_: Exception) {
                // Keep the compiled-in fallback domain.
            }

            // The APK only proves that the resolved dynamic_host value is read
            // from storage. The supplied site pages prove that bit.ly/animevietsubtv
            // is the canonical redirect used to discover the current domain.
            // Refresh it once when storage has no usable value.
            if (currentDomain == "animevietsub.li") {
                runCatching {
                    val probe = Request.Builder()
                        .url("https://bit.ly/animevietsubtv")
                        .get()
                        .header("User-Agent", CloudflareManager.getCurrentUserAgent())
                        .build()
                    client.newCall(probe).execute().use { response ->
                        val host = response.request.url.host
                        if (host.isNotBlank() && host.contains("animevietsub.")) {
                            currentDomain = host.removePrefix("www.")
                        }
                    }
                }
            }
            isInitialized = true
        }
        return currentDomain
    }

    private fun request(
        url: String,
        method: String = "GET",
        body: RequestBody? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .method(method, body)
        getHeaders(url).forEach { (k, v) -> builder.header(k, v) }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    private suspend fun fetchResponse(
        url: String,
        method: String = "GET",
        body: RequestBody? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): Response? {
        ensureDomain()
        return try {
            client.newCall(request(url, method, body, extraHeaders)).execute()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchText(
        url: String,
        method: String = "GET",
        body: RequestBody? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        val response = fetchResponse(url, method, body, extraHeaders) ?: return null
        response.use {
            if (!it.isSuccessful) return null
            return it.body?.string()
        }
    }

    private suspend fun fetchHtml(path: String): Document? {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "https://${ensureDomain()}$cleanPath"
        }
        val html = fetchText(url) ?: return null
        return Jsoup.parse(html, url)
    }

    fun parseAnimeCard(element: Element): AnimeCard {
        val href = element.selectFirst("a")?.attr("href").orEmpty()
        val animeId = extractIdFromPath(href)
        val image = element.selectFirst("img")
            ?.let { it.attr("data-cfsrc").ifBlank { it.attr("src") } }
            .orEmpty()
        val name = element.selectFirst(".Title")?.text()?.trim()
            ?: element.selectFirst(".title")?.text()?.trim()
            ?: element.selectFirst("h2,h3")?.text()?.trim()
            ?: ""

        var episode = element.selectFirst(".mli-eps > i")?.text()?.trim().orEmpty()
        if (episode == "TẤT") episode = "Full Season"

        val rateText = element.selectFirst(".anime-avg-user-rating")?.text()
            ?: element.selectFirst(".AAIco-star")?.text()
        val rate = rateText?.toFloatOrNull() ?: 0f

        val yearText = element.selectFirst(".Year")?.text().orEmpty()
        val year = Regex("""\d+""").find(yearText)?.value?.toIntOrNull()

        val quality = element.selectFirst(".Qlty,.mli-quality")?.text()?.trim()
        val process = element.selectFirst(".AAIco-access_time")?.text()?.trim()
        val description = element.selectFirst(".Description > p,.Description p")?.text()?.trim()

        val studio = element.selectFirst(".Studio")?.text()
            ?.substringAfter(':', "")
            ?.trim()
            ?.ifBlank { null }

        val genres = element.select(".Genre > a").map { parseCategoryLink(it) }

        val scheduleSeconds = element
            .selectFirst(".mli-timeschedule")
            ?.attr("data-timer_second")
            ?.toLongOrNull()

        val scheduleAt = scheduleSeconds?.let {
            System.currentTimeMillis() + it * 1000L
        }

        return AnimeCard(
            animeId = animeId,
            image = image,
            name = name,
            lastEpisode = ChapterInfo(
                if (episode.isBlank()) "Movie" else episode,
                if (episode.isBlank()) "Movie" else episode
            ),
            rate = rate,
            year = year,
            quality = quality,
            process = process,
            views = null,
            description = description,
            studio = studio,
            genre = genres,
            timeRelease = scheduleAt
        )
    }

    fun parseCategoryLink(element: Element): CategoryLink {
        val href = element.attr("href")
        val path = try {
            URL(href).path
        } catch (_: Exception) {
            href.removePrefix(baseUrl)
        }

        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        val name = element.text().trim()

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

        val filters = when {
            parts.size >= 2 -> listOf(
                SelectedFilter(
                    mapping[parts[0]] ?: parts[0],
                    parts[1],
                    name
                )
            )
            parts.isNotEmpty() -> listOf(
                SelectedFilter(
                    mapping[parts[0]] ?: "danh-sach",
                    parts[0],
                    name,
                    true
                )
            )
            else -> emptyList()
        }

        return CategoryLink(name, filters)
    }

    private fun parseSections(doc: Document, selector: String): List<AnimeCard> =
        doc.select(selector).mapNotNull {
            runCatching { parseAnimeCard(it) }.getOrNull()
        }

    override fun getUser(): Flow<User?> = flow {
        emit(runCatching { refreshUser() }.getOrNull())
    }

    override suspend fun refreshUser(): User {
        val doc = fetchHtml("/user") ?: fetchHtml("/login")
            ?: throw IllegalStateException("Unable to load user page")

        val username = doc.selectFirst(
            "[name=username], .username, .user-name, .profile-username"
        )?.text()?.trim().orEmpty()

        val name = doc.selectFirst(
            "[name=name], .name, .user-name, .profile-name"
        )?.text()?.trim()
            ?.ifBlank { username }
            ?: username

        if (username.isBlank() && name.isBlank()) {
            throw IllegalStateException("User is not authenticated")
        }

        val avatar = doc.selectFirst("img.avatar,img.user-avatar")?.attr("src")
        val email = doc.selectFirst("[name=email],.email")?.text()?.trim()
        val sex = doc.selectFirst("[name=sex],.sex")?.text()?.trim()

        return User(
            avatar = avatar,
            email = email,
            name = name,
            sex = sex,
            username = username.ifBlank { name }
        )
    }

    override suspend fun logout() {
        val response = fetchResponse("$baseUrl/logout")
        response?.close()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /**
     * Login using the site's normal account session.
     *
     * The current AnimeDataSource interface does not expose login(), so this
     * intentionally is not marked `override`; callers that have an AnimeApi
     * instance can invoke it directly. The existing Login UI may still use
     * its own authentication flow.
     */
    suspend fun login(email: String, password: String): Result<User> {
        return runCatching {
            val domain = ensureDomain()
            val body = FormBody.Builder()
                .add("email", email)
                .add("password", password)
                .add("remember", "1")
                .build()

            val response = fetchResponse(
                "https://$domain/account/login/",
                "POST",
                body,
                mapOf("Accept" to "application/json")
            )

            if (response == null) {
                throw IOException("Không thể kết nối đến server")
            }

            response.use { res ->
                val text = res.body?.string().orEmpty()

                // Some deployments return JSON; try it first.
                if (text.contains("success") || text.contains("token")) {
                    val parsed = runCatching {
                        json.decodeFromString<LoginResponse>(text)
                    }.getOrNull()

                    if (parsed?.success == true && parsed.user != null) {
                        return@runCatching parsed.user
                    }
                }

                // Normal web login returns HTML and keeps authentication in
                // the HTTP cookie jar. Parse an error message when present.
                val doc = Jsoup.parse(text)
                val errorMsg = doc.selectFirst(
                    ".alert-error, .alert-danger, .error-message"
                )?.text()?.trim()
                    ?: "Đăng nhập thất bại"

                // OkHttp follows redirects by default. A successful redirect
                // away from the login page means the session cookie was likely
                // accepted; read the authenticated profile next.
                if (res.isSuccessful &&
                    !res.request.url.encodedPath.contains("/account/login") &&
                    !text.contains("Đăng nhập", ignoreCase = true)
                ) {
                    return@runCatching refreshUser()
                }

                throw IOException(errorMsg)
            }
        }
    }

    override suspend fun getHomePage(): HomeData {
        val doc = fetchHtml("/") ?: return HomeData(
            emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList()
        )

        // Current HTML uses TPost/B cards rather than the legacy .mli class.
        // Keep the legacy selectors as fallbacks because older domains may still
        // serve them. Sections are located by their visible widget headings.
        fun sectionByTitle(vararg needles: String): List<AnimeCard> {
            val section = doc.select("section.widget-area, .Wdgt, .widget-area").firstOrNull { node ->
                val title = node.selectFirst(".Title, h2, h3, h4")?.text()?.lowercase(Locale.ROOT).orEmpty()
                needles.any { title.contains(it.lowercase(Locale.ROOT)) }
            }
            val cards = section?.select(".TPost.B, .TPost.C, .mli")
                ?.mapNotNull { runCatching { parseAnimeCard(it) }.getOrNull() }
                ?.distinctBy { it.animeId }
                .orEmpty()
            if (cards.isNotEmpty()) return cards
            return section?.select("ul.MovieList a[href*='/phim/']")
                ?.mapNotNull { link ->
                    val href = link.absUrl("href")
                    val id = extractIdFromPath(href)
                    val name = link.selectFirst("span:first-child")?.text()?.trim()
                        ?: link.text().trim()
                    val episode = link.selectFirst("span:nth-child(2)")?.text()?.trim().orEmpty()
                    if (id.isBlank() || name.isBlank()) null else AnimeCard(
                        animeId = id, image = "", name = name,
                        lastEpisode = episode.takeIf { it.isNotBlank() }?.let { ChapterInfo(it, it) }
                    )
                }
                ?.distinctBy { it.animeId }
                .orEmpty()
        }

        val latest = sectionByTitle("anime mới cập nhật", "mới cập nhật")
        val hot = sectionByTitle("hot tuần", "hot")
        val pre = sectionByTitle("sắp chiếu")
        val nominate = sectionByTitle("đề cử", "nominate")
        val carousel = doc.select(".owl-carousel .TPost.B, .carousel .TPost.B, .carousel .mli")
            .mapNotNull { runCatching { parseAnimeCard(it) }.getOrNull() }
            .distinctBy { it.animeId }

        return HomeData(
            thisSeason = latest,
            carousel = carousel,
            lastUpdate = latest,
            preRelease = pre,
            nominate = nominate,
            hotUpdate = hot
        )
    }

    // Schedule section structure confirmed by schedule.html.
    override suspend fun getSchedule(): List<ScheduleDay> {
        val doc = fetchHtml("/lich-chieu-phim.html")
            ?: fetchHtml("/lich-chieu-phim")
            ?: return emptyList()

        val year = LocalDate.now().year
        val zone = ZoneId.systemDefault()

        return doc.select("section.Homeschedule").mapNotNull { section ->
            val heading = section.selectFirst(".Top h1")?.text()?.trim().orEmpty()
            val date = Regex("Ngày\\s+(\\d{1,2})\\s+tháng\\s+(\\d{1,2})")
                .find(heading)
                ?.let { match ->
                    runCatching {
                        LocalDate.of(year, match.groupValues[2].toInt(), match.groupValues[1].toInt())
                            .atStartOfDay(zone).toInstant().toEpochMilli()
                    }.getOrNull()
                } ?: return@mapNotNull null

            val items = section.select("article.TPost.C").mapNotNull {
                runCatching { parseAnimeCard(it) }.getOrNull()
            }.distinctBy { it.animeId }

            if (items.isEmpty()) null else ScheduleDay(date, items)
        }
    }

    // Ranking routes confirmed by ranking.html.
    override suspend fun getRankings(type: String): List<AnimeCard> {
        val slug = when (type.lowercase(Locale.ROOT)) {
            "day", "daily", "ngày" -> "day"
            "voted", "rating", "đánh giá", "favorite" -> "voted"
            "month", "tháng" -> "month"
            "season", "mùa" -> "season"
            "year", "năm" -> "year"
            else -> type.trim().trim('/').ifBlank { "day" }
        }
        val path = if (slug == "day") "/bang-xep-hang/day.html" else "/bang-xep-hang/$slug.html"
        val doc = fetchHtml(path) ?: fetchHtml("/bang-xep-hang.html") ?: return emptyList()

        return doc.select("ul.bxh-movie-phimletv > li").mapNotNull { li ->
            val link = li.selectFirst(".title-item a, h3.title-item a") ?: return@mapNotNull null
            val href = link.absUrl("href")
            val image = li.selectFirst("img")?.let { it.attr("data-cfsrc").ifBlank { it.absUrl("src") } }.orEmpty()
            val name = link.text().trim()
            val scoreText = li.selectFirst(".rank-score .score")?.text()?.trim().orEmpty()
            val rate = Regex("\\d+(?:[.,]\\d+)?").find(scoreText)?.value?.replace(',', '.')?.toFloatOrNull() ?: 0f
            val episode = scoreText.takeIf { it.isNotBlank() }
            if (href.isBlank() || name.isBlank()) null else AnimeCard(
                animeId = extractIdFromPath(href), image = image, name = name,
                lastEpisode = episode?.let { ChapterInfo(it, it) }, rate = rate
            )
        }
    }

    // Ranking type options confirmed by ranking.html.
    override suspend fun getRankingTypes(): List<FilterOption> = listOf(
        FilterOption("day", "Ngày"),
        FilterOption("voted", "Đánh Giá"),
        FilterOption("month", "Tháng"),
        FilterOption("season", "Mùa"),
        FilterOption("year", "Năm")
    )

    // Search suggestion AJAX endpoint confirmed by home-v1.js.
    override suspend fun preSearch(keyword: String): List<SearchSuggestion> {
        if (keyword.isBlank()) return emptyList()
        val body = FormBody.Builder()
            .add("ajaxSearch", "1")
            .add("keysearch", keyword)
            .build()
        val html = fetchText("$baseUrl/ajax/suggest", "POST", body) ?: return emptyList()
        val doc = Jsoup.parseBodyFragment(html, baseUrl)
        return doc.select("a[href*='/phim/']").mapNotNull { a ->
            val href = a.absUrl("href")
            val id = extractIdFromPath(href)
            val image = a.selectFirst("img")?.let { it.attr("data-cfsrc").ifBlank { it.absUrl("src") } }.orEmpty()
            val name = a.selectFirst(".Title,.title")?.text()?.trim() ?: a.text().trim()
            val status = a.selectFirst(".mli-eps,.status")?.text()?.trim().orEmpty()
            if (id.isBlank() || name.isBlank()) null else SearchSuggestion(id, image, name, status)
        }.distinctBy { it.animeId }
    }

    // Category filter URL confirmed by category/filter.js.
    private fun categoryQuery(filters: List<SelectedFilter>, page: Int): String {
        var type = "all"
        var genres = "all"
        var season = "all"
        var year = "all"
        var studio = "all"
        var rating = "all"
        var country = "all"
        var sort: String? = null

        filters.forEach { filter ->
            when (filter.groupId.lowercase(Locale.ROOT)) {
                "danh-sach", "type" -> type = filter.id
                "genres", "genre", "the-loai" -> genres = if (genres == "all") filter.id else "$genres-${filter.id}"
                "season", "mua" -> season = filter.id
                "year", "nam" -> year = filter.id
                "studio" -> studio = filter.id
                "rating" -> rating = filter.id
                "country", "quoc-gia" -> country = filter.id
                "sort", "sap-xep" -> sort = filter.id
            }
        }

        val path = "/danh-sach/$type/$genres/$season/$year/${urlEncode(studio)}/${urlEncode(rating)}/$country/"
        return buildString {
            append(path)
            if (sort != null) append("?sort=").append(urlEncode(sort!!))
            if (page > 1) append(if (contains('?')) '&' else '?').append("page=").append(page)
        }
    }
    override suspend fun search(keyword: String, page: Int): CategoryPage {
        val safePage = page.coerceAtLeast(1)
        val path = "/tim-kiem/${urlEncode(keyword)}?page=$safePage"
        val doc = fetchHtml(path)
            ?: fetchHtml("/tim-kiem?keyword=${urlEncode(keyword)}&page=$safePage")
            ?: return CategoryPage(emptyList(), 0, safePage)

        return parseCategoryPage(doc, safePage)
    }

    override suspend fun getCategory(
        filters: List<SelectedFilter>,
        page: Int
    ): CategoryPage {
        val safePage = page.coerceAtLeast(1)
        val doc = fetchHtml(categoryQuery(filters, safePage))
            ?: return CategoryPage(emptyList(), 0, safePage)

        return parseCategoryPage(doc, safePage)
    }

    private fun parseCategoryPage(doc: Document, page: Int): CategoryPage {
        val items = doc.select(
            ".film_list-wrap .flw-item,.film_list .item,.anime-list .mli,.mli"
        ).mapNotNull { runCatching { parseAnimeCard(it) }.getOrNull() }
            .distinctBy { it.animeId }

        val current = doc.selectFirst(
            ".pagination .active,.pagination .current,.page-item.active"
        )?.text()?.trim()?.toIntOrNull() ?: page

        val totalPages = doc.select(
            ".pagination a,.pagination .page-item"
        ).mapNotNull { Regex("""\d+""").find(it.text())?.value?.toIntOrNull() }
            .maxOrNull() ?: current

        val name = doc.selectFirst("h1,.page-title,.cat-heading")?.text()?.trim().orEmpty()
        val title = doc.title()

        return CategoryPage(items, totalPages, current, name, title)
    }

    // TODO(API-FILTERS): Exact filter endpoint and option metadata are not
    // recovered. Current implementation parses filters rendered by the page.
    override suspend fun getFilters(filters: List<SelectedFilter>): List<FilterGroup> {
        val doc = fetchHtml(categoryQuery(filters, 1)) ?: return emptyList()

        return doc.select(
            ".filter-item, .filter-group, .filter-list"
        ).mapNotNull { group ->
            val name = group.selectFirst(
                ".filter-title,.title,label,h3"
            )?.text()?.trim().orEmpty()

            val options = group.select("option,a").mapNotNull { option ->
                val id = option.attr("value").ifBlank {
                    option.attr("href").substringAfterLast('/').ifBlank {
                        option.text().trim()
                    }
                }
                val text = option.text().trim()
                if (id.isBlank() || text.isBlank()) null
                else FilterOption(id, text)
            }.distinctBy { it.id }

            if (name.isBlank() || options.isEmpty()) null
            else FilterGroup(
                id = name.lowercase().replace(' ', '-'),
                name = name,
                options = options,
                isMultiple = group.select("input[type=checkbox]").size > 1
            )
        }.distinctBy { it.id }
    }

    // TODO(API-DETAIL): Most fields are recoverable from detail.html, but exact
    // APK selectors/field semantics for every AnimeDetail.extra field are not
    // fully recovered.
    override suspend fun getAnimeDetail(animeId: String): AnimeDetail {
        val doc = fetchHtml("/phim/$animeId")
            ?: throw IllegalStateException("Anime not found: $animeId")

        val name = doc.selectFirst("h1.Title,.Title,h1")?.text()?.trim().orEmpty()
        val othername = doc.selectFirst(
            ".OtherName,.other-name,.film-title .other-name"
        )?.text()?.trim()

        val image = doc.selectFirst(
            ".film-poster img,img.film-poster-img,.MovieInfo img"
        )?.let { it.attr("data-cfsrc").ifBlank { it.attr("src") } }

        val poster = doc.selectFirst(
            ".film-poster,.poster,.detail-poster"
        )?.let { node ->
            node.attr("style").let { extractBackgroundImage(it) }
                .ifBlank { node.selectFirst("img")?.attr("src").orEmpty() }
        }?.ifBlank { null }

        val description = doc.selectFirst(
            ".Description,.description,.film-description"
        )?.text()?.trim().orEmpty()

        val rate = doc.selectFirst(
            ".anime-avg-user-rating,.rating-value,.score"
        )?.text()?.toDoubleOrNull() ?: 0.0

        val countRate = doc.selectFirst(
            ".rating-count,.count-rate"
        )?.text()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: 0

        val duration = doc.selectFirst(
            ".duration,.film-duration"
        )?.text()?.trim()

        val yearLink = doc.select(
            ".Year a,.year a,.film-infor a"
        ).firstOrNull { it.text().matches(Regex("""\d{4}""")) }
            ?.let { parseCategoryLink(it) }

        val views = doc.selectFirst(".views,.view")?.text()
            ?.replace(",", "")
            ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            ?: 0

        val genres = doc.select(
            ".Genre a,.genre a,.film-infor .category a"
        ).map { parseCategoryLink(it) }.distinctBy { it.name }

        val quality = doc.selectFirst(".Qlty,.quality,.film-quality")?.text()?.trim()

        val authors = doc.select(
            ".Author a,.author a,.Director a,.director a"
        ).map { parseCategoryLink(it) }.distinctBy { it.name }

        val countries = doc.select(
            ".Country a,.country a"
        ).map { parseCategoryLink(it) }.distinctBy { it.name }

        val follows = doc.selectFirst(".follow-count,.follows")?.text()
            ?.replace(",", "")
            ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            ?: 0

        val language = doc.selectFirst(".language,.Language")?.text()?.trim()

        val studio = doc.selectFirst(".Studio a,.studio a")?.let { parseCategoryLink(it) }

        val seasonOf = doc.selectFirst(
            ".Season a,.season a"
        )?.let { parseCategoryLink(it) }

        val trailer = doc.selectFirst(
            "a[href*='youtube.com'],a[href*='youtu.be'],iframe[src*='youtube']"
        )?.let { it.attr("href").ifBlank { it.attr("src") } }

        val related = doc.select(
            ".film-related .mli,.related .mli,.film_list .mli"
        ).mapNotNull { runCatching { parseAnimeCard(it) }.getOrNull() }
            .distinctBy { it.animeId }

        return AnimeDetail(
            name = name,
            othername = othername,
            image = image,
            poster = poster,
            description = description,
            rate = rate,
            countRate = countRate,
            duration = duration,
            yearOf = yearLink,
            views = views,
            season = emptyList(),
            genre = genres,
            quality = quality,
            authors = authors,
            countries = countries,
            follows = follows,
            language = language,
            studio = studio,
            seasonOf = seasonOf,
            trailer = trailer,
            related = related
        )
    }

    // TODO(API-CHAPTERS): Chapter links/data-id are confirmed by watch/detail
    // HTML, but the exact APK coroutine which handles seasons, ranges and the
    // update Triple is still unresolved.
    override suspend fun getChapters(animeId: String): ChapterData {
        val doc = fetchHtml("/phim/$animeId")
            ?: throw IllegalStateException("Anime not found: $animeId")

        val image = doc.selectFirst(
            ".film-poster img,.poster img,.detail-poster img"
        )?.let { it.attr("data-cfsrc").ifBlank { it.attr("src") } }.orEmpty()

        val poster = doc.selectFirst(".film-poster,.poster,.detail-poster")
            ?.attr("style")
            ?.let(::extractBackgroundImage)
            ?.ifBlank { image }
            ?: image

        val chapters = mutableListOf<ChapterInfo>()

        doc.select(
            ".list-episode a.episode-link, [data-id][data-play], .list-episode a, .episode-list a"
        ).forEach { node ->
            val id = node.attr("data-id")
                .ifBlank { node.attr("href") }
                .ifBlank { node.text() }

            val name = node.attr("data-name")
                .ifBlank { node.text().trim() }

            if (id.isNotBlank() && name.isNotBlank()) {
                chapters += ChapterInfo(
                    id = id,
                    name = name,
                    extra = buildMap {
                        put("url", node.absUrl("href").ifBlank { node.attr("href") })
                        if (node.attr("data-hash").isNotBlank()) put("hash", node.attr("data-hash"))
                        if (node.attr("data-source").isNotBlank()) put("source", node.attr("data-source"))
                        if (node.attr("data-play").isNotBlank()) put("play", node.attr("data-play"))
                        if (node.attr("data-movie").isNotBlank()) put("movie", node.attr("data-movie"))
                    }
                )
            }
        }

        // Fallback: use the actual episode links on the page.
        if (chapters.isEmpty()) {
            doc.select("a[href*='/tap-'],a[href*='/episode'],a[href*='/phim/']")
                .forEach { node ->
                    val href = node.absUrl("href")
                    val name = node.text().trim()
                    if (href.isNotBlank() && name.isNotBlank()) {
                        chapters += ChapterInfo(
                            href,
                            name,
                            mapOf("url" to href)
                        )
                    }
                }
        }

        val unique = chapters.distinctBy { it.id }

        val current = doc.selectFirst(
            ".current-episode,.mli-eps,.episode-current"
        )?.text().orEmpty()

        val total = unique.size.toString()

        val update = if (current.isNotBlank()) {
            val currentNo = Regex("""\d+""").find(current)?.value?.toIntOrNull() ?: 0
            Triple(currentNo, unique.size, 0)
        } else null

        return ChapterData(
            chaps = unique,
            update = update,
            image = image,
            poster = poster
        )
    }

    // Server group and episode-link structure confirmed by watch.html.
    override suspend fun getServers(chapter: ChapterInfo): List<ServerInfo> {
        val url = chapter.extra["url"] ?: return listOf(
            ServerInfo("AnimeVsub", mapOf("chapterId" to chapter.id))
        )
        val doc = fetchHtml(url) ?: return listOf(
            ServerInfo("AnimeVsub", mapOf("url" to url, "chapterId" to chapter.id))
        )

        val groups = doc.select(".server.server-group")
        val result = groups.flatMap { group ->
            val serverName = group.selectFirst(".server-name")?.text()?.trim().orEmpty().ifBlank { "AnimeVsub" }
            group.select("a.episode-link, a[data-id][data-play]").map { node ->
                ServerInfo(
                    name = serverName,
                    extra = buildMap {
                        put("url", node.absUrl("href"))
                        put("chapterId", node.attr("data-id").ifBlank { chapter.id })
                        put("hash", node.attr("data-hash"))
                        put("source", node.attr("data-source"))
                        put("play", node.attr("data-play"))
                        put("episode", node.attr("title").ifBlank { node.text().trim() })
                    }
                )
            }
        }

        return if (result.isNotEmpty()) result.distinctBy { it.extra["url"] } else listOf(
            ServerInfo("AnimeVsub", mapOf("url" to url, "chapterId" to chapter.id))
        )
    }
    /**
     * Player reconstruction based on the recovered Java flow:
     * - resolve chapter/server URL
     * - request the page
     * - extract id/sid/token/avsToken and crypto-hardening flags
     * - when a playlist endpoint is directly exposed, return it as HLS
     * - preserve X-Envelope-related metadata for the caller/interceptor.
     */
    // PLAYER_DATA and the recovered playlist/token flow are both supported.
    // The remaining encrypted response-envelope path from the APK is still TODO.
    override suspend fun getPlayerLink(server: ServerInfo): PlayerData {
        val serverUrl = server.extra["url"]
            ?: server.extra["link"]
            ?: server.name.takeIf { it.startsWith("http") }
            ?: throw IllegalArgumentException("Server URL is missing")

        val response = fetchResponse(
            if (serverUrl.startsWith("http")) serverUrl
            else "$baseUrl/${serverUrl.trimStart('/')}"
        ) ?: throw IllegalStateException("Unable to request player server")

        response.use { res ->
            if (!res.isSuccessful) {
                throw IllegalStateException("Player request failed: ${res.code}")
            }

            val finalUrl = res.request.url.toString()
            val html = res.body?.string().orEmpty()

            val playerJson = Regex("window\\.PLAYER_DATA\\s*=\\s*(\\{.*?\\})\\s*;", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.getOrNull(1)
            if (!playerJson.isNullOrBlank()) {
                runCatching {
                    val obj = json.parseToJsonElement(playerJson).jsonObject
                    val link = obj["link"]?.toString()?.trim('"')
                    val tech = obj["playTech"]?.toString()?.trim('"') ?: "iframe"
                    if (!link.isNullOrBlank()) {
                        return PlayerData(
                            link = link,
                            type = if (tech.equals("hls", true)) "hls" else tech,
                            headers = getHeaders(finalUrl),
                            isContent = false
                        )
                    }
                }
            }

            // Direct playlist.
            if (finalUrl.contains(".m3u8") || serverUrl.contains(".m3u8")) {
                return PlayerData(
                    link = if (serverUrl.contains(".m3u8")) serverUrl else finalUrl,
                    type = "hls",
                    headers = getHeaders(finalUrl),
                    isContent = false
                )
            }

            val id = Regex("""id\s*=\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            val sid = Regex("""sid\s*=\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            val token = Regex("""token\s*=\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            val avsToken = Regex("""avsToken\s*=\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            val harden = Regex("""_avsCryptoHarden\s*=\s*true""").containsMatchIn(html)
            val shadow = Regex("""_avsCryptoHardenShadow\s*=\s*true""").containsMatchIn(html)

            // Some servers expose the final playlist in script/HTML.
            val directPlaylist = Regex(
                """https?://[^"'\\s]+\.m3u8(?:\?[^"'\\s]+)?"""
            ).find(html)?.value

            if (directPlaylist != null) {
                return PlayerData(
                    link = directPlaylist,
                    type = "hls",
                    headers = getHeaders(finalUrl),
                    isContent = false
                )
            }

            // The original implementation creates:
            // /playlist/{id}/playlist.m3u8?token={token}&fc={base64("cross-origin")}
            // when an id and token are available.
            if (!id.isNullOrBlank() && !token.isNullOrBlank()) {
                val fc = Base64.getEncoder().encodeToString(
                    "cross-origin".toByteArray(StandardCharsets.UTF_8)
                )
                val playlist = "${origin(finalUrl)}/playlist/$id/playlist.m3u8" +
                    "?token=${urlEncode(token)}&fc=${urlEncode(fc)}"

                val headers = getHeaders(playlist).toMutableMap()
                headers["Referer"] = finalUrl

                // Keep recovered envelope inputs available to the HTTP layer.
                if (!sid.isNullOrBlank()) headers["X-AVS-SID"] = sid
                if (!avsToken.isNullOrBlank()) headers["X-AVS-Token"] = avsToken
                if (harden) headers["X-AVS-Crypto-Harden"] = "true"
                if (shadow) headers["X-AVS-Crypto-Harden-Shadow"] = "true"

                return PlayerData(
                    link = playlist,
                    type = "hls",
                    headers = headers,
                    isContent = false
                )
            }

            // Last-resort HTML player URL. This keeps the API functional for
            // servers which use an iframe/player page instead of a playlist.
            val iframe = Jsoup.parse(html, finalUrl)
                .selectFirst("iframe[src]")
                ?.absUrl("src")

            if (!iframe.isNullOrBlank()) {
                return PlayerData(
                    link = iframe,
                    type = "iframe",
                    headers = getHeaders(finalUrl),
                    isContent = false
                )
            }

            return PlayerData(
                link = finalUrl,
                type = "html",
                headers = getHeaders(finalUrl),
                isContent = false
            )
        }
    }

    // TODO(API-SKIP): Exact episode intro/outro endpoint/parser is unresolved.
    override suspend fun getEpisodeSkip(
        animeId: String,
        detail: AnimeDetail,
        chapter: ChapterInfo
    ): InOutroEpisode? {
        return null
    }

    override suspend fun getFollows(
        filters: List<SelectedFilter>,
        page: Int
    ): CategoryPage {
        val doc = fetchHtml("/theo-doi?page=${page.coerceAtLeast(1)}")
            ?: return CategoryPage(emptyList(), 0, page)

        return parseCategoryPage(doc, page.coerceAtLeast(1))
    }

    override suspend fun getFollowFilters(
        filters: List<SelectedFilter>
    ): List<FilterGroup> = getFilters(filters)

    override suspend fun checkFollow(animeId: String): Boolean {
        val html = fetchText("$baseUrl/phim/$animeId") ?: return false
        return Regex(
            """(?:follow|following|is-followed)[^>]*(?:true|active|1)"""
        ).containsMatchIn(html.lowercase())
    }

    // TODO(API-FOLLOW): Exact follow/check-follow AJAX endpoint and POST field
    // names were not recovered. Current code tries common endpoints defensively.
    override suspend fun toggleFollow(animeId: String, follow: Boolean) {
        val body = FormBody.Builder()
            .add("film_id", animeId)
            .add("follow", if (follow) "1" else "0")
            .build()

        // The exact endpoint body was not recoverable from the JADX output.
        // Try the common site action paths without surfacing an exception.
        val candidates = listOf(
            "$baseUrl/ajax/follow",
            "$baseUrl/api/follow",
            "$baseUrl/follow"
        )

        for (endpoint in candidates) {
            val response = fetchResponse(endpoint, "POST", body)
            if (response != null) {
                response.use {
                    if (it.isSuccessful) return
                }
            }
        }
    }

    override suspend fun getNotifications(): NotificationData {
        val doc = fetchHtml("/thong-bao") ?: fetchHtml("/notifications")
            ?: throw IllegalStateException("Unable to load notifications")

        val items = doc.select(
            ".notification-item,.notification-list .item,.noti-item"
        ).mapIndexed { index, node ->
            NotificationItem(
                id = node.attr("data-id").ifBlank { index.toString() },
                image = node.selectFirst("img")?.attr("src"),
                avatar = node.selectFirst("img.avatar")?.attr("src"),
                title = node.selectFirst(".title,h3")?.text()?.trim().orEmpty(),
                content = node.selectFirst(".content,.message")?.text()?.trim().orEmpty(),
                description = node.selectFirst(".description,p")?.text()?.trim().orEmpty(),
                link = node.selectFirst("a")?.absUrl("href").orEmpty(),
                animeId = node.attr("data-anime-id").ifBlank { null },
                chapId = node.attr("data-chap-id").ifBlank { null }
            )
        }

        return NotificationData(items = items, max = items.size)
    }

    override suspend fun onTrigger(trigger: Trigger) {
        val body = FormBody.Builder()
            .add("id", trigger.id)
            .add("name", trigger.name.orEmpty())
            .apply {
                trigger.extra.forEach { (k, v) -> add(k, v) }
            }
            .build()

        fetchResponse("$baseUrl/ajax/trigger", "POST", body)?.close()
    }

    // TODO(API-COMMENTS): Current JSON models are known, but exact AJAX endpoint
    // names/POST parameters need confirmation from comment.js or APK coroutine.
    override suspend fun getComments(
        filmId: String,
        anime: AnimeDetail,
        sort: FilterOption?,
        offset: Int
    ): CommentResponse {
        val body = FormBody.Builder()
            .add("film_id", filmId)
            .add("offset", offset.toString())
            .apply { sort?.let { add("sort", it.id) } }
            .build()

        val response = fetchResponse(
            "$baseUrl/api/comments",
            "POST",
            body,
            mapOf("Accept" to "application/json")
        )

        val text = response?.use { it.body?.string() }
        if (!text.isNullOrBlank()) {
            runCatching { return json.decodeFromString<ApiCommentResponse>(text).toCommentResponse() }
        }

        return CommentResponse(
            total = 0,
            success = false,
            comments = emptyList(),
            offset = offset,
            error = "Unable to load comments",
            hasMore = false
        )
    }

    // TODO(API-REPLIES): Exact endpoint/request parameters are not fully proven.
    override suspend fun getReplies(
        commentId: String,
        sort: FilterOption?,
        offset: Int
    ): ReplyResponse {
        val body = FormBody.Builder()
            .add("comment_id", commentId)
            .add("offset", offset.toString())
            .apply { sort?.let { add("sort", it.id) } }
            .build()

        val response = fetchResponse(
            "$baseUrl/api/replies",
            "POST",
            body,
            mapOf("Accept" to "application/json")
        )

        val text = response?.use { it.body?.string() }
        if (!text.isNullOrBlank()) {
            runCatching { return json.decodeFromString<ApiReplyResponse>(text).toReplyResponse() }
        }

        return ReplyResponse(
            total = 0,
            success = false,
            replies = emptyList(),
            offset = offset,
            error = "Unable to load replies",
            hasMore = false
        )
    }

    // TODO(API-POST-COMMENT): Exact endpoint/field names are not fully proven.
    override suspend fun postComment(
        filmId: String,
        content: String,
        isSpoiler: Boolean,
        episodeId: String?,
        parentId: String,
        threadKey: String?
    ): PostCommentResponse {
        val body = FormBody.Builder()
            .add("film_id", filmId)
            .add("content", content)
            .add("is_spoiler", if (isSpoiler) "1" else "0")
            .add("parent_id", parentId)
            .apply {
                episodeId?.let { add("episode_id", it) }
                threadKey?.let { add("thread_key", it) }
            }
            .build()

        val response = fetchResponse(
            "$baseUrl/api/comments",
            "POST",
            body,
            mapOf("Accept" to "application/json")
        )

        val text = response?.use { it.body?.string() }
        if (!text.isNullOrBlank()) {
            runCatching {
                val api = json.decodeFromString<ApiPostCommentResponse>(text)
                return PostCommentResponse(
                    success = api.success,
                    comment = api.comment?.toComment(),
                    total = api.total,
                    pending = api.pending,
                    error = api.error
                )
            }
        }

        return PostCommentResponse(
            success = false,
            comment = null,
            total = null,
            pending = false,
            error = "Unable to post comment"
        )
    }

    // TODO(API-VOTE): Exact endpoint/field names are not fully proven.
    override suspend fun voteComment(
        commentId: String,
        voteType: VoteType
    ): VoteResponse {
        val vote = when (voteType.toString().uppercase()) {
            "NONE" -> "0"
            "UP", "LIKE", "1" -> "1"
            "DOWN", "DISLIKE", "-1" -> "-1"
            else -> "0"
        }

        val body = FormBody.Builder()
            .add("comment_id", commentId)
            .add("vote", vote)
            .build()

        val response = fetchResponse(
            "$baseUrl/api/comments/vote",
            "POST",
            body,
            mapOf("Accept" to "application/json")
        )

        val text = response?.use { it.body?.string() }
        if (!text.isNullOrBlank()) {
            runCatching {
                val api = json.decodeFromString<ApiVoteResponse>(text)
                return VoteResponse(
                    success = api.success,
                    votesUp = api.votesUp,
                    votesDown = api.votesDown,
                    error = api.error
                )
            }
        }

        return VoteResponse(
            success = false,
            votesUp = 0,
            votesDown = 0,
            error = "Unable to vote"
        )
    }

    // TODO(API-EDIT-COMMENT): Exact endpoint/field names are not fully proven.
    override suspend fun editComment(
        commentId: String,
        content: String,
        isSpoiler: Boolean
    ): EditCommentResponse {
        val body = FormBody.Builder()
            .add("comment_id", commentId)
            .add("content", content)
            .add("is_spoiler", if (isSpoiler) "1" else "0")
            .build()

        val response = fetchResponse(
            "$baseUrl/api/comments/edit",
            "POST",
            body,
            mapOf("Accept" to "application/json")
        )

        val text = response?.use { it.body?.string() }
        if (!text.isNullOrBlank()) {
            runCatching {
                val api = json.decodeFromString<ApiEditCommentResponse>(text)
                return EditCommentResponse(
                    success = api.success,
                    content = api.content,
                    isSpoiler = api.isSpoiler,
                    editedAt = api.editedAt,
                    pending = api.pending,
                    error = api.error
                )
            }
        }

        return EditCommentResponse(
            success = false,
            content = null,
            isSpoiler = isSpoiler,
            editedAt = null,
            pending = false,
            error = "Unable to edit comment"
        )
    }

    override suspend fun getCommentSortOptions(): List<FilterOption> =
        listOf(
            FilterOption("newest", "Mới nhất"),
            FilterOption("oldest", "Cũ nhất"),
            FilterOption("popular", "Phổ biến")
        )

    override fun encodeURI(url: String): String =
        runCatching { URI(url).toASCIIString() }.getOrDefault(url)

    override fun decodeURI(url: String): String =
        runCatching { URI(url).toString() }.getOrDefault(url)

    suspend fun isLoggedIn(): Boolean {
        return try {
            refreshUser()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Copy WebView cookies from one host to another.  Kept public because
     * the decompiled implementation exposed the helper behaviour.
     */
    fun syncCookies(fromUrl: String, toUrl: String) {
        val manager = CookieManager.getInstance()
        val from = if (fromUrl.contains("://")) fromUrl else "https://$fromUrl"
        val to = if (toUrl.contains("://")) toUrl else "https://$toUrl"
        val cookies = manager.getCookie(from) ?: return

        cookies.split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { manager.setCookie(to, it) }

        manager.flush()
    }
}
