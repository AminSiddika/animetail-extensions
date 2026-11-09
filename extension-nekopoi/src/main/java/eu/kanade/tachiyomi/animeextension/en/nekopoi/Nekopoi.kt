package eu.kanade.tachiyomi.animeextension.en.nekopoi

import eu.kanade.tachiyomi.animeextension.proxy.MediaVideo

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLEncoder

class Nekopoi : AnimeHttpSource() {

    override val name = "@JalebiBae - NekoPoi"
    override val id: Long = 446061740949428455L
    override val baseUrl = "https://nekopoi.care"
    init {
        MediaVideo.referer = "$baseUrl/"
    }
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/hentai-list", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/hentai-list", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/?s=$encoded&post_type=hentai", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val azItems = doc.select("div.nk-az-item a.nk-series-link[href]")
        val links = if (azItems.isNotEmpty()) {
            azItems
        } else {
            doc.select("a[href^=https://nekopoi.care/hentai/]")
        }
        val items = links.mapNotNull { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            if (!SERIES_URL.matches(href)) return@mapNotNull null
            val tooltip = link.attr("original-title")
            val thumb = if (tooltip.isNotBlank()) {
                Jsoup.parse(tooltip).selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            } else {
                link.selectFirst("img")?.absUrl("src")
            }
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = link.text().trim()
                thumbnail_url = thumb
            }
        }.distinctBy { it.url }
        return AnimesPage(items, false)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val poster = Regex("""url\('([^']+)'\)""")
            .find(doc.selectFirst("div.nk-series-poster")?.attr("style").orEmpty())
            ?.groupValues?.get(1)
        return SAnime.create().apply {
            title = doc.selectFirst(".nk-series-synopsis b")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = poster
            genre = doc.select("a[href*='/genre/']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst(".nk-series-synopsis p")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        response.asJsoup()
            .select(".nk-episode-grid a.nk-episode-card[href]")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                val label = link.selectFirst(".nk-episode-badge")?.text()?.trim().orEmpty()
                val number = EPISODE.find(label)?.groupValues?.get(1)?.toIntOrNull()
                    ?: EPISODE.find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    name = if (label.isNotBlank()) label else "Episode $number"
                    episode_number = number.toFloat()
                }
            }
            .distinctBy { it.url }
            .sortedBy { it.episode_number }

    override fun videoListParse(response: Response): List<Video> {
        val episodeUrl = response.request.url.toString()
        val iframes = response.asJsoup()
            .select("iframe[src*='playmogo.com/e/']")
            .mapNotNull { it.absUrl("src").ifBlank { it.attr("src") } }
            .distinct()
        return iframes.flatMap { resolvePlaymogo(it, episodeUrl) }.distinctBy { it.url }
    }

    private fun resolvePlaymogo(embedUrl: String, referer: String): List<Video> {
        val html = fetchText(embedUrl, referer) ?: return emptyList()
        val pass = Regex("""pass_md5/([^'"]+)""").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val token = Regex("""token=([A-Za-z0-9]+)""").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val base = fetchText("https://playmogo.com/pass_md5/$pass", "https://playmogo.com/")
            ?: return emptyList()
        if (!base.startsWith("http")) return emptyList()
        val suffix = (1..10).map { CHARS.random() }.joinToString("")
        val url = base.trim() + suffix + "?token=$token&expiry=" + System.currentTimeMillis()
        val headers = Headers.Builder().add("Referer", "https://playmogo.com/").build()
        return listOf(MediaVideo.create(url, "MP4", url, headers = headers))
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
        private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        private val SERIES_URL = Regex("""^https://nekopoi\.care/hentai/[^/?#]+/?$""")
        private val EPISODE = Regex("""(?i)(?:episode|ep)[\s-]*(\d+)""")
    }
}
