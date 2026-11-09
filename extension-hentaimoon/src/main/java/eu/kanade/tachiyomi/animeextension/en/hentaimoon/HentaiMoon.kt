package eu.kanade.tachiyomi.animeextension.en.hentaimoon

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

class HentaiMoon : AnimeHttpSource() {

    override val name = "@JalebiBae - Hentai-Moon"
    override val id: Long = 1752074658394183681L
    override val baseUrl = "https://hentai-moon.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(pageUrl("top-rated", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl("latest-updates", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search/$encoded/", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response, allowPaging = false)

    private fun pageUrl(section: String, page: Int): String =
        if (page <= 1) "$baseUrl/$section/" else "$baseUrl/$section/$page/"

    private fun parseList(response: Response, allowPaging: Boolean = true): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div.item")
            .mapNotNull { item ->
                val link = item.selectFirst("a[href*='/videos/']") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = link.attr("title").ifBlank { link.text() }.trim()
                    thumbnail_url = item.selectFirst("img.thumb-main-img")?.let { img ->
                        img.absUrl("src").ifBlank {
                            img.attr("srcset").substringBefore(' ').trim()
                        }
                    } ?: item.selectFirst("img[data-original]")?.absUrl("data-original")
                }
            }
            .distinctBy { it.url }

        val current = pageFromUrl(response.request.url.toString())
        val hasNext = allowPaging && doc.select(".pagination li.page a")
            .mapNotNull { it.text().trim().toIntOrNull() }
            .any { it > current }
        return AnimesPage(items, hasNext)
    }

    private fun pageFromUrl(url: String): Int =
        Regex("/(?:latest-updates|top-rated)/(\\d+)/").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val html = doc.html()
        val tags = TAGS.find(html)?.groupValues?.get(1).orEmpty()
        val categories = CATEGORIES.find(html)?.groupValues?.get(1).orEmpty()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = (categories.split(",") + tags.split(","))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("meta[name=description]")?.attr("content")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = "Full Video"
                episode_number = 1f
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val html = response.asJsoup().html()
        val main = VIDEO_URL.find(html)?.groupValues?.get(1)
        val alt = VIDEO_ALT_URL.find(html)?.groupValues?.get(1)
        val mainLabel = VIDEO_URL_TEXT.find(html)?.groupValues?.get(1).orEmpty()
        val altLabel = VIDEO_ALT_TEXT.find(html)?.groupValues?.get(1).orEmpty()

        return buildList {
            if (isPlayable(main)) {
                add(Video(main!!, mainLabel.ifBlank { "480p" }, main))
            }
            if (isPlayable(alt) && alt != main) {
                add(Video(alt!!, altLabel.ifBlank { "720p" }, alt))
            }
        }
    }

    private fun isPlayable(url: String?): Boolean =
        !url.isNullOrBlank() && url.startsWith("http") && url.contains("/get_file/")

    companion object {
        private val VIDEO_URL = Regex("""video_url:\s*'([^']+)'""")
        private val VIDEO_ALT_URL = Regex("""video_alt_url:\s*'([^']+)'""")
        private val VIDEO_URL_TEXT = Regex("""video_url_text:\s*'([^']*)'""")
        private val VIDEO_ALT_TEXT = Regex("""video_alt_url_text:\s*'([^']*)'""")
        private val TAGS = Regex("""video_tags:\s*'([^']*)'""")
        private val CATEGORIES = Regex("""video_categories:\s*'([^']*)'""")
    }
}
