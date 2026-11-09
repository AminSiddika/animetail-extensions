package eu.kanade.tachiyomi.animeextension.en.akih

import eu.kanade.tachiyomi.animeextension.proxy.MediaVideo

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.net.URLEncoder

class AkiH : AnimeHttpSource() {

    override val name = "@JalebiBae - Aki-H"
    override val id: Long = 3876317610373167042L
    override val baseUrl = "https://aki-h.com"
    init {
        MediaVideo.referer = "$baseUrl/"
    }
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(pageUrl("popular", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl("latest", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search/?q=$encoded", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response, paged = false)

    private fun pageUrl(section: String, page: Int): String =
        if (page <= 1) "$baseUrl/$section/" else "$baseUrl/$section/?page=$page"

    private fun parseList(response: Response, paged: Boolean = true): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div.ak-card")
            .mapNotNull { card ->
                val link = card.selectFirst("a.ak-card__poster[href]") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = link.attr("title").ifBlank {
                        card.selectFirst(".ak-card__title")?.text().orEmpty()
                    }.trim()
                    thumbnail_url = card.selectFirst("img")?.let { img ->
                        img.absUrl("data-src").ifBlank { img.absUrl("src") }
                    }
                }
            }
            .distinctBy { it.url }

        val hasNext = paged && items.size >= 20
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".ak-spot__cover img[data-src], .ak-hero img[data-src]")
                ?.absUrl("data-src")
            ?: doc.selectFirst("img[data-src*='i.aki-h.com/images/']")?.absUrl("data-src")
        return SAnime.create().apply {
            title = doc.selectFirst("h1 .dynamic-name")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = cover
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

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = response.asJsoup()
            .select("div.ak-ep a.ak-ep__thumb[href]")
            .mapIndexedNotNull { index, link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/watch/") && !href.contains("/videos/")) return@mapIndexedNotNull null
                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    name = link.attr("title").ifBlank { "Episode ${index + 1}" }.trim()
                    episode_number = (index + 1).toFloat()
                }
            }
            .distinctBy { it.url }
        return episodes
    }

    override fun videoListParse(response: Response): List<Video> {
        val watchUrl = response.request.url.toString()
        val id = WATCH_ID.find(watchUrl)?.groupValues?.get(1) ?: return emptyList()
        val serversJson = fetchText("https://v.aki-h.com/api/servers/$id", watchUrl)
            ?: return emptyList()
        val servers = runCatching {
            org.json.JSONObject(serversJson).optJSONArray("servers")
        }.getOrNull() ?: return emptyList()

        return buildList {
            for (i in 0 until servers.length()) {
                val server = servers.optJSONObject(i) ?: continue
                val serverUrl = server.optString("url")
                val name = server.optString("name").ifBlank { "Server ${i + 1}" }
                if (!serverUrl.startsWith("http")) continue
                val page = fetchText(serverUrl, watchUrl) ?: continue
                val token = STREAM_TOKEN.find(page)?.groupValues?.get(1) ?: continue
                val url = "https://aki-h.stream/file/$token/"
                add(MediaVideo.create(url, "$name (HLS)", url))
            }
        }.distinctBy { it.url }
    }

    private fun fetchText(url: String, referer: String?): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
        referer?.let { builder.header("Referer", it) }
        client.newCall(builder.build()).execute().use { it.body?.string() }
    }.getOrNull()

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val WATCH_ID = Regex("""/(?:watch|videos)/([A-Za-z0-9]+)/""")
        private val STREAM_TOKEN = Regex("""aki-h\.stream/v/([A-Za-z0-9]+)""")
    }
}
