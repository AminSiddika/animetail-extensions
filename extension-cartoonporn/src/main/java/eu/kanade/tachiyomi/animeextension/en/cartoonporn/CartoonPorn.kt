package eu.kanade.tachiyomi.animeextension.en.cartoonporn

import eu.kanade.tachiyomi.animeextension.proxy.ProxySupport
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
import java.net.URLEncoder

class CartoonPorn : AnimeHttpSource() {

    override val name = "@JalebiBae - CartoonPorn"
    override val id: Long = 5865626856198430666L
    override val baseUrl = "https://cartoonporn.pro"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/?s=$encoded", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun listUrl(page: Int): String =
        if (page <= 1) "$baseUrl/latest-updates/" else "$baseUrl/latest-updates/$page/"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("a[href^='https://cartoonporn.pro/vids/']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!VIDEO_URL.matches(href)) return@mapNotNull null
                val img = link.selectFirst("img")
                val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                    ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: link.selectFirst(".title, h3")?.text()?.trim()
                    ?: link.text().trim()
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                    thumbnail_url = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                        ?: img?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val current = Regex("/latest-updates/(\\d+)/").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("a[href]").any {
            it.attr("href").contains("/latest-updates/${current + 1}/")
        } || items.size >= 20
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href^='https://cartoonporn.pro/tags/']")
                .map { it.text().replace('-', ' ').trim() }
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
        val videos = mutableListOf<Video>()
        val main = VIDEO_URL_FLASH.find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        if (main != null && main.startsWith("http")) {
            videos.add(proxyVideo(main, "720p"))
        }
        val fallback = EVENT_URL.find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        if (fallback != null && fallback.startsWith("http") && videos.none { it.url == fallback }) {
            videos.add(proxyVideo(fallback, "480p"))
        }
        return videos
    }

    private fun proxyVideo(url: String, label: String): Video {
        val proxied = ProxySupport.url(client, url, "$baseUrl/")
        return Video(proxied, label, proxied, headers)
    }

    companion object {
        private val VIDEO_URL = Regex("""^https://cartoonporn\.pro/vids/[^/?#]+/[^/?#]+/?$""")
        private val VIDEO_URL_FLASH = Regex("""video_url:\s*'([^']+)'""")
        private val EVENT_URL = Regex("""event_reporting2:\s*'([^']+)'""")
    }
}
