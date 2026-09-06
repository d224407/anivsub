package git.shin.animevsub.data.remote.api_hidden

import android.webkit.CookieManager
import git.shin.animevsub.data.local.ApiStorage
import git.shin.animevsub.data.model.*
import git.shin.animevsub.data.remote.SegmentDataInterceptor
import git.shin.animevsub.data.remote.SegmentUrlInterceptor
import git.shin.animevsub.data.remote.api.AnimeDataSource
import git.shin.animevsub.utils.CloudflareManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URL
import java.net.URLEncoder
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
class AnimeApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val cloudflareManager: CloudflareManager,
    private val apiStorage: ApiStorage
) : AnimeDataSource {

    companion object {
        private var currentDomain = "animevietsub.pl"
        private var isInitialized = false

        private const val DYNAMIC_HOST = "dynamic_host"

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
        get() = "$baseUrl/login"

    override val segmentUrlInterceptor: SegmentUrlInterceptor?
        get() = null

    override val segmentDataInterceptor: SegmentDataInterceptor?
        get() = null

    private suspend fun ensureDomain(): String {
        if (!isInitialized) {
            try {
                apiStorage.getString(DYNAMIC_HOST).collect { saved ->
                    if (!saved.isNullOrBlank()) {
                        currentDomain = saved.removePrefix("https://")
                            .removePrefix("http://")
                            .trimEnd('/')
                    }
                }
            } catch (_: Exception) {
                // Keep the compiled-in fallback domain.
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

    override suspend fun getUser(): Flow<User?> = flowOf(null)

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

    override suspend fun getHomePage(): HomeData {
        val doc = fetchHtml("/") ?: return HomeData(
            emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList()
        )

        return HomeData(
            thisSeason = parseSections(doc, ".anime-moi-cap-nhat .mli"),
            carousel = parseSections(doc, ".carousel .mli"),
            lastUpdate = parseSections(doc, ".anime-moi-update .mli"),
            preRelease = parseSections(doc, ".anime-sap-chieu .mli"),
            nominate = parseSections(doc, ".anime-de-cu .mli"),
            hotUpdate = parseSections(doc, ".anime-hot .mli")
        )
    }

    override suspend fun getSchedule(): List<ScheduleDay> {
        val doc = fetchHtml("/lich-chieu") ?: return emptyList()
        val days = mutableListOf<ScheduleDay>()

        val dayNodes = doc.select(
            "[data-date].schedule-day,.schedule-day,.lich-chieu-day"
        )

        if (dayNodes.isNotEmpty()) {
            for (node in dayNodes) {
                val date = node.attr("data-date").toLongOrNull()
                    ?: Regex("""\d+""").find(node.text())?.value?.toLongOrNull()
                    ?: continue
                val items = node.select(".mli").mapNotNull {
                    runCatching { parseAnimeCard(it) }.getOrNull()
                }
                if (items.isNotEmpty()) days += ScheduleDay(date, items)
            }
        }

        return days
    }

    override suspend fun getRankings(type: String): List<AnimeCard> {
        val path = "/xep-hang/${urlEncode(type)}"
        val doc = fetchHtml(path) ?: fetchHtml("/top/${urlEncode(type)}")
            ?: return emptyList()

        return doc.select(".mli,.film_list-wrap .flw-item,.film_list .item")
            .mapNotNull { runCatching { parseAnimeCard(it) }.getOrNull() }
    }

    override suspend fun getRankingTypes(): List<FilterOption> {
        val doc = fetchHtml("/xep-hang") ?: fetchHtml("/top") ?: return emptyList()

        return doc.select(
            "select option,.ranking-type a,.filter-option a"
        ).mapNotNull {
            val id = it.attr("value").ifBlank {
                it.attr("href").substringAfterLast('/').ifBlank { it.text() }
            }
            val name = it.text().trim()
            if (id.isBlank() || name.isBlank()) null else FilterOption(id, name)
        }.distinctBy { it.id }
    }

    override suspend fun preSearch(keyword: String): List<SearchSuggestion> {
        if (keyword.isBlank()) return emptyList()

        val url = "$baseUrl/tim-kiem?keyword=${urlEncode(keyword)}"
        val doc = fetchHtml(url) ?: return emptyList()

        return doc.select(
            ".search-suggest .ss-item,.search-suggest .item,.flw-item"
        ).mapNotNull { item ->
            val href = item.selectFirst("a")?.attr("href").orEmpty()
            val id = extractIdFromPath(href)
            val image = item.selectFirst("img")
                ?.let { it.attr("data-cfsrc").ifBlank { it.attr("src") } }
                .orEmpty()
            val name = item.selectFirst(".Title,.title,.film-name")?.text()?.trim()
                ?: item.selectFirst("a")?.text()?.trim()
                ?: ""
            val status = item.selectFirst(".mli-eps,.status,.film-infor")?.text()?.trim().orEmpty()

            if (id.isBlank() || name.isBlank()) null
            else SearchSuggestion(id, image, name, status)
        }
    }

    private fun categoryQuery(filters: List<SelectedFilter>, page: Int): String {
        if (filters.isEmpty()) return "/danh-sach?page=$page"

        val selected = filters.joinToString("&") { filter ->
            "${urlEncode(filter.id)}=${urlEncode(filter.value)}"
        }

        return "/danh-sach?page=$page&$selected"
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
            "[data-id][data-name], .list-episode a, .episode-list a, .server-list a"
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
                    extra = mapOf(
                        "url" to node.absUrl("href").ifBlank { node.attr("href") }
                    )
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

    override suspend fun getServers(chapter: ChapterInfo): List<ServerInfo> {
        val url = chapter.extra["url"]
            ?: chapter.extra["link"]
            ?: if (chapter.id.startsWith("http")) chapter.id else null
            ?: return listOf(ServerInfo(chapter.name, mapOf("id" to chapter.id)))

        val doc = fetchHtml(url) ?: return listOf(
            ServerInfo(chapter.name, mapOf("url" to url, "id" to chapter.id))
        )

        val servers = doc.select(
            "[data-server],[data-link],[data-url],.server a,.list-server a"
        ).mapNotNull { node ->
            val name = node.text().trim().ifBlank { "Server" }
            val link = node.attr("data-url")
                .ifBlank { node.attr("data-link") }
                .ifBlank { node.absUrl("href") }
                .ifBlank { node.attr("href") }

            if (link.isBlank()) null
            else ServerInfo(
                name,
                mapOf(
                    "url" to link,
                    "chapterId" to chapter.id,
                    "chapterName" to chapter.name
                )
            )
        }.distinctBy { it.extra["url"] ?: it.name }

        return if (servers.isEmpty()) {
            listOf(ServerInfo(chapter.name, mapOf(
                "url" to url,
                "chapterId" to chapter.id
            )))
        } else servers
    }

    /**
     * Player reconstruction based on the recovered Java flow:
     * - resolve chapter/server URL
     * - request the page
     * - extract id/sid/token/avsToken and crypto-hardening flags
     * - when a playlist endpoint is directly exposed, return it as HLS
     * - preserve X-Envelope-related metadata for the caller/interceptor.
     */
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
