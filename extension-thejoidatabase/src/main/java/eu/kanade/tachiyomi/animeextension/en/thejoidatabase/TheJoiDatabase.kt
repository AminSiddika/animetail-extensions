package eu.kanade.tachiyomi.animeextension.en.thejoidatabase

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

class TheJoiDatabase : AnimeHttpSource() {

    override val name = "@JalebiBae - The JOI Database"
    override val id: Long = 3496703588188214876L
    override val baseUrl = "https://www.the-joi-database.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/videos?search=$encoded&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun listUrl(page: Int): String =
        if (page <= 1) "$baseUrl/videos" else "$baseUrl/videos?page=$page"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div.video-thumbnail-block")
            .mapNotNull { block ->
                val link = block.selectFirst("a[href*='/watch/']") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null

                val thumbnail = block.selectFirst("asis-video-thumbnail")
                    ?.attr("thumbnail")
                    ?.takeIf { it.isNotBlank() }
                    ?: block.selectFirst("img")?.let { img ->
                        img.absUrl("data-src").ifBlank { img.absUrl("src") }
                    }

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = block.selectFirst("h6")?.attr("title")
                        ?.takeIf { it.isNotBlank() }
                        ?: block.selectFirst("h6")?.text()?.trim().orEmpty()
                    thumbnail_url = thumbnail
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
        val title = doc.selectFirst("h4")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - The joi Database").orEmpty()
        return SAnime.create().apply {
            this.title = title
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href*='/videos?tags='], a[href*='/tags/']")
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
        val doc = response.asJsoup()
        val path = doc.selectFirst("video#player")?.attr("data-hls-source")
            ?.takeIf { it.isNotBlank() }
            ?: Regex("""data-hls-source="([^"]+)"""").find(doc.html())?.groupValues?.get(1)
            ?: return emptyList()
        val url = if (path.startsWith("http")) path else baseUrl + path
        return listOf(Video(url, "HLS", url))
    }
}
