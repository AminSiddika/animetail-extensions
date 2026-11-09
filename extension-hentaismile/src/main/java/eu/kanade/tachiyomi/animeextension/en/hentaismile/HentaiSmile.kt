package eu.kanade.tachiyomi.animeextension.en.hentaismile

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
import java.net.URLEncoder

class HentaiSmile : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiSmile"
    override val id: Long = 5599185677673317176L
    override val baseUrl = "https://www.hentaismile.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(pageUrl("most-popular", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl("latest-updates", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/?s=$encoded", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun pageUrl(section: String, page: Int): String =
        if (page <= 1) "$baseUrl/$section/" else "$baseUrl/$section/$page/"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("a[href^=https://www.hentaismile.com/videos/]")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/videos/")) return@mapNotNull null
                val img = link.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = img?.attr("alt")?.trim()
                        ?: link.attr("title").ifBlank { link.text() }.trim()
                    thumbnail_url = img?.absUrl("data-original")?.takeIf { it.isNotBlank() }
                        ?: img?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val current = Regex("/(?:latest-updates|most-popular)/(\\d+)/").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("a[href]").any { it.attr("href").contains("/${current + 1}/") }
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href*='/categories/'], a[href*='/tags/']")
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
        val candidates = listOf(
            VIDEO_URL to VIDEO_TEXT,
            VIDEO_ALT to ALT_TEXT,
            VIDEO_ALT2 to ALT2_TEXT,
        )
        return buildList {
            for ((urlRegex, textRegex) in candidates) {
                val url = urlRegex.find(html)?.groupValues?.get(1)
                    ?.replace("&amp;", "&")
                    ?.takeIf { it.startsWith("http") }
                    ?: continue
                val label = textRegex.find(html)?.groupValues?.get(1)
                    ?.takeIf { it.isNotBlank() } ?: "MP4"
                add(MediaVideo.create(url, label, url))
            }
        }.distinctBy { it.url }
    }

    companion object {
        private val VIDEO_URL = Regex("""video_url:\s*'([^']+)'""")
        private val VIDEO_TEXT = Regex("""video_url_text:\s*'([^']*)'""")
        private val VIDEO_ALT = Regex("""video_alt_url:\s*'([^']+)'""")
        private val ALT_TEXT = Regex("""video_alt_url_text:\s*'([^']*)'""")
        private val VIDEO_ALT2 = Regex("""video_alt_url2:\s*'([^']+)'""")
        private val ALT2_TEXT = Regex("""video_alt_url2_text:\s*'([^']*)'""")
    }
}
