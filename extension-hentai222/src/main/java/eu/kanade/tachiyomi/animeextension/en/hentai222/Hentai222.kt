package eu.kanade.tachiyomi.animeextension.en.hentai222

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

class Hentai222 : AnimeHttpSource() {

    override val name = "@JalebiBae - Hentai222"
    override val id: Long = 5713325484251259760L
    override val baseUrl = "https://hentai222.com"
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
        if (page <= 1) "$baseUrl/videos" else "$baseUrl/videos?page=$page"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("a[href^='https://hentai222.com/video/']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!VIDEO_URL.matches(href)) return@mapNotNull null
                val img = link.selectFirst("img")
                val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                    ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: link.selectFirst("h3")?.text()?.trim()
                    ?: link.text().trim()
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                    thumbnail_url = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                        ?: img?.absUrl("src")
                        ?: link.attr("data-bg")?.takeIf { it.isNotBlank() }
                }
            }
            .distinctBy { it.url }

        val current = Regex("[?&]page=(\\d+)").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("a[href]").any {
            it.attr("href").contains("page=${current + 1}")
        } || items.size >= 20
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href*='/hashtag/'], a[href*='/tag/'], a[href*='/category/']")
                .map { it.text().trim() }
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
        val doc = response.asJsoup()
        val urls = mutableListOf<String>()
        doc.select("video source[src]").forEach { source ->
            val url = source.absUrl("src").ifBlank { source.attr("src") }
                .replace("&amp;", "&")
            if (url.startsWith("http")) urls.add(url)
        }
        if (urls.isEmpty()) {
            Regex("\"contentUrl\":\"([^\"]+\\.mp4)\"")
                .find(doc.html())
                ?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.takeIf { it.startsWith("http") }
                ?.let(urls::add)
        }
        val videoHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        return urls.distinct().map { url ->
            Video(url, "MP4", url, videoHeaders)
        }
    }

    companion object {
        private val VIDEO_URL = Regex("""^https://hentai222\.com/video/[^/?#]+/[^/?#]+/?$""")
    }
}
