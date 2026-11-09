package eu.kanade.tachiyomi.animeextension.en.rule34video

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

class Rule34Video : AnimeHttpSource() {

    override val name = "@JalebiBae - Rule34Video"
    override val id: Long = 1031421854898827454L
    override val baseUrl = "https://rule34video.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) {
            "$baseUrl/search/$encoded/"
        } else {
            "$baseUrl/search/$encoded/?page=$page"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun listUrl(page: Int): String =
        if (page <= 1) "$baseUrl/latest-updates/" else "$baseUrl/latest-updates/$page/"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("a[href^=https://rule34video.com/video/]")
            .toList()
            .filter { it.selectFirst("img") != null }
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/video/")) return@mapNotNull null
                val img = link.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = link.attr("title").ifBlank { img?.attr("alt").orEmpty() }.trim()
                    thumbnail_url = img?.absUrl("src")
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
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href*='/tags/']")
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
            VIDEO_ALT3 to ALT3_TEXT,
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
        private val VIDEO_ALT3 = Regex("""video_alt_url3:\s*'([^']+)'""")
        private val ALT3_TEXT = Regex("""video_alt_url3_text:\s*'([^']*)'""")
    }
}
