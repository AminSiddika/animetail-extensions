package eu.kanade.tachiyomi.animeextension.en.hstream

import eu.kanade.tachiyomi.animeextension.proxy.MediaVideo

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder

class HStream : AnimeHttpSource() {

    override val name = "@JalebiBae - HStream"
    override val id: Long = 246851957385861584L
    override val baseUrl = "https://hstream.moe"
    override val lang = "en"
    override val supportsLatest = true

    @Volatile
    private var searchQuery: String = ""

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        searchQuery = query
        return GET("$baseUrl/", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val token = Regex("""name="_token" value="([^"]+)"""")
            .find(response.asJsoup().html())?.groupValues?.get(1)
            ?: return AnimesPage(emptyList(), false)
        val cookies = response.headers("Set-Cookie")
            .mapNotNull { it.substringBefore(";").takeIf { c -> c.contains("=") } }
            .joinToString("; ")
        val body = "search=${URLEncoder.encode(searchQuery, "UTF-8")}&_token=${URLEncoder.encode(token, "UTF-8")}"
        val request = Request.Builder()
            .url("$baseUrl/search")
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", "$baseUrl/")
            .header("Cookie", cookies)
            .build()
        val html = runCatching {
            client.newCall(request).execute().use { it.body?.string() }
        }.getOrNull() ?: return AnimesPage(emptyList(), false)
        return AnimesPage(parseCards(html), false)
    }

    private fun parseList(response: Response): AnimesPage {
        val items = parseCards(response.asJsoup().html())
        return AnimesPage(items, false)
    }

    private fun parseCards(html: String): List<SAnime> {
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.select("a[href^=https://hstream.moe/hentai/]")
            .toList()
            .filter { it.selectFirst("img") != null }
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!SERIES_URL.matches(href)) return@mapNotNull null
                val img = link.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = img?.attr("alt")?.trim().orEmpty()
                    thumbnail_url = img?.absUrl("src")
                }
            }
            .distinctBy { it.url }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href*='/genre/']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("meta[name=description]")?.attr("content")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            name = "Full Video"
            episode_number = 1f
        },
    )

    override fun videoListParse(response: Response): List<Video> {
        val html = response.asJsoup().html()
        val episodeId = Regex("""id="e_id"[^>]*value="([^"]+)"""")
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        val seed = runCatching {
            client.newCall(GET("$baseUrl/", headers)).execute().use { r ->
                val body = r.body?.string().orEmpty()
                val token = Regex("""name="_token" value="([^"]+)"""").find(body)
                    ?.groupValues?.get(1).orEmpty()
                val cookies = r.headers("Set-Cookie")
                    .mapNotNull { it.substringBefore(";").takeIf { c -> c.contains("=") } }
                    .joinToString("; ")
                token to cookies
            }
        }.getOrNull() ?: return emptyList()
        val token = seed.first
        val cookies = seed.second
        if (token.isBlank()) return emptyList()

        val payload = JSONObject().put("episode_id", episodeId).toString()
        val request = Request.Builder()
            .url("$baseUrl/player/api")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "application/json")
            .header("X-CSRF-TOKEN", token)
            .header("Cookie", cookies)
            .header("Referer", response.request.url.toString())
            .build()
        val body = runCatching {
            client.newCall(request).execute().use { it.body?.string() }
        }.getOrNull() ?: return emptyList()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val path = json.optString("stream_url")
        if (path.isBlank()) return emptyList()
        val domains = json.optJSONArray("stream_domains")
            ?: json.optJSONArray("asia_stream_domains")
            ?: return emptyList()
        if (domains.length() == 0) return emptyList()
        val domain = domains.optString(0).trimEnd('/')
        if (!domain.startsWith("http")) return emptyList()
        val url = "$domain/$path/x264.720p.mp4"
        return listOf(MediaVideo.create(url, "720p", url))
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val SERIES_URL = Regex("""^https://hstream\.moe/hentai/[^/?#]+$""")
    }
}
