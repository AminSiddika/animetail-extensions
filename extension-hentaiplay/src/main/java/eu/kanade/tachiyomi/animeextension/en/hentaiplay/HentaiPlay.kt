package eu.kanade.tachiyomi.animeextension.en.hentaiplay

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

class HentaiPlay : AnimeHttpSource() {

    override val name = "HentaiPlay"
    override val baseUrl = "https://hentaiplay.net"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(categoryUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(categoryUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/?s=${query.replace(" ", "+")}", headers)

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun categoryUrl(page: Int): String =
        if (page <= 1) {
            "$baseUrl/hentai/episodes/"
        } else {
            "$baseUrl/hentai/episodes/page/$page/"
        }

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div.item.item-post")
            .mapNotNull { item ->
                val link = item.selectFirst("a.clip-link[href]")
                    ?: item.selectFirst("h2.entry-title a[href]")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = link.attr("title").ifBlank { link.text() }
                    thumbnail_url = item.selectFirst("img")?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        return AnimesPage(items, hasNextPage(doc))
    }

    private fun hasNextPage(doc: Document): Boolean {
        val status = doc.selectFirst(".wp-pagenavi .pages")?.text() ?: return false
        val match = PAGE_STATUS.find(status) ?: return false
        val current = match.groupValues[1].toIntOrNull() ?: return false
        val total = match.groupValues[2].toIntOrNull() ?: return false
        return current < total
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1.entry-title")?.text().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")
            author = doc.selectFirst("a[href*='/author/']")?.text()
            genre = (doc.select("a[rel=tag]") + doc.select("a[rel='category tag']"))
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[name=twitter:description]")?.attr("content")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(url)
                name = "Full Video"
                episode_number = 1f
                date_upload = 0L
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val videos = mutableListOf<Video>()

        doc.select("video source[src]").forEach { source ->
            val url = source.absUrl("src").ifBlank { source.attr("src") }
            if (url.isNotBlank() && videos.none { it.videoUrl == url }) {
                videos += Video(url, "MP4", url)
            }
        }

        val meta = doc.selectFirst("meta[property=og:video:url]")?.attr("content")
        if (!meta.isNullOrBlank() && videos.none { it.videoUrl == meta }) {
            videos += Video(meta, "MP4", meta)
        }

        return videos
    }

    companion object {
        private val PAGE_STATUS = Regex("""Page\s+(\d+)\s+of\s+(\d+)""")
    }
}
