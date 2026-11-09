package eu.kanade.tachiyomi.animeextension.en.hentaistream

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
import java.net.URLEncoder

class HentaiStream : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiStream"
    override val id: Long = 439007758964585859L
    override val baseUrl = "https://tube.hentaistream.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(pageUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/?s=$encoded", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun pageUrl(page: Int): String =
        if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div.post")
            .mapNotNull { post ->
                val link = post.selectFirst("p.posttitle a[href]")
                    ?: post.selectFirst("div.postimg a[href]")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = link.attr("title").ifBlank { link.text() }
                    thumbnail_url = post.selectFirst("img")?.let {
                        it.absUrl("data-src").ifBlank { it.absUrl("src") }
                    }
                }
            }
            .distinctBy { it.url }

        return AnimesPage(items, hasNextPage(doc))
    }

    private fun hasNextPage(doc: org.jsoup.nodes.Document): Boolean {
        val info = doc.selectFirst("#wp_page_numbers .page_info")?.text() ?: return false
        val match = PAGE_INFO.find(info) ?: return false
        val current = match.groupValues[1].toIntOrNull() ?: return false
        val total = match.groupValues[2].toIntOrNull() ?: return false
        return current < total
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val rawTitle = doc.selectFirst(".videotitle")?.text()
            ?.removePrefix("¤")
            ?.trim()
            .orEmpty()
        return SAnime.create().apply {
            title = rawTitle.ifBlank {
                doc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.removePrefix("Watch ")
                    .orEmpty()
            }
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[rel=tag]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
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

        val frames = doc.select("iframe[src*='/frames/']")
        frames.forEachIndexed { index, frame ->
            val frameUrl = frame.absUrl("src").ifBlank { frame.attr("src") }
            if (frameUrl.isBlank()) return@forEachIndexed

            val frameDoc = runCatching {
                client.newCall(
                    GET(
                        frameUrl,
                        headers.newBuilder().set("Referer", "$baseUrl/").build(),
                    ),
                ).execute().use { it.asJsoup() }
            }.getOrNull() ?: return@forEachIndexed

            frameDoc.select("video source[src]").forEach { source ->
                val url = source.absUrl("src").ifBlank { source.attr("src") }
                if (url.isNotBlank() && videos.none { it.videoUrl == url }) {
                    videos += Video(url, "Source ${index + 1}", url)
                }
            }
            frameDoc.select("video[src]").forEach { video ->
                val url = video.absUrl("src").ifBlank { video.attr("src") }
                if (url.isNotBlank() && videos.none { it.videoUrl == url }) {
                    videos += Video(url, "Source ${index + 1}", url)
                }
            }
        }

        return videos
    }

    companion object {
        private val PAGE_INFO = Regex("""Page\s+(\d+)\s+of\s+(\d+)""")
    }
}
