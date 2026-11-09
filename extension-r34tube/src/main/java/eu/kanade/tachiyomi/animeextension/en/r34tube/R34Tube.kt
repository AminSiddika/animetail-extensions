package eu.kanade.tachiyomi.animeextension.en.r34tube

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
import org.json.JSONObject
import java.net.URLEncoder

class R34Tube : AnimeHttpSource() {

    override val name = "@JalebiBae - R34Tube"
    override val id: Long = 5710395400702408638L
    override val baseUrl = "https://r34tube.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/?q=$encoded", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun listUrl(page: Int): String =
        if (page <= 1) "$baseUrl/browse" else "$baseUrl/browse?page=$page"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("a[href^='/v/']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!VIDEO_URL.matches(href)) return@mapNotNull null
                val img = link.selectFirst("img")
                val slug = href.substringAfterLast('/')
                    .replace('-', ' ')
                    .replaceFirstChar { it.uppercase() }
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() } ?: slug
                    thumbnail_url = img?.let { it.attr("src").ifBlank { it.absUrl("src") } }
                }
            }
            .distinctBy { it.url }

        val current = Regex("[?&]page=(\\d+)").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("a[href]").any {
            it.attr("href").contains("page=${current + 1}")
        }
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
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
        val pageUrl = response.request.url.toString()
        val id = Regex("/v/([a-z0-9]+)").find(pageUrl)?.groupValues?.get(1)
        if (id != null) {
            val json = runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/v/$id/stream-urls")
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", pageUrl)
                    .build()
                client.newCall(request).execute().use { it.body?.string().orEmpty() }
            }.getOrNull()
            if (!json.isNullOrBlank()) {
                val sources = runCatching {
                    JSONObject(json).optJSONObject("sources")
                }.getOrNull()
                if (sources != null) {
                    val videos = mutableListOf<Video>()
                    val keys = sources.keys()
                    while (keys.hasNext()) {
                        val label = keys.next()
                        val url = sources.optString(label)
                        if (url.startsWith("http")) videos.add(proxyVideo(url, label))
                    }
                    if (videos.isNotEmpty()) return videos
                }
            }
        }

        val html = response.asJsoup().html()
        return MEDIA_URL.findAll(html)
            .map { it.value }
            .distinct()
            .map { proxyVideo(it, "MP4") }
            .toList()
    }

    private fun proxyVideo(url: String, label: String): Video {
        val videoHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            )
            .build()
        return MediaVideo.create(url, label, url, videoHeaders)
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val VIDEO_URL = Regex("""^https://r34tube\.com/v/[a-z0-9]+/[^/?#]+/?$""")
        private val MEDIA_URL = Regex("""https?://videos\.r34tube\.com/crawl/[^"'\s<>]+\.mp4""")
    }
}
