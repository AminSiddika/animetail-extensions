package eu.kanade.tachiyomi.animeextension.en.naughtyhentai

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

class NaughtyHentai : AnimeHttpSource() {

    override val name = "@JalebiBae - NaughtyHentai"
    override val id: Long = 4310227187973947283L
    override val baseUrl = "https://www.naughtyhentai.com"
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
        val items = doc.select("div.videopost")
            .mapNotNull { post ->
                val link = post.selectFirst("div.video_pic a[href]")
                    ?: post.selectFirst("div.video_meta a[href]")
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
        val current = doc.selectFirst(".pagination .page-numbers.current")
            ?.text()?.toIntOrNull() ?: 1
        return doc.select(".pagination a.page-numbers[href]").any { link ->
            PAGE_NUMBER.find(link.attr("href"))
                ?.groupValues?.get(1)?.toIntOrNull()
                ?.let { it > current } == true
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[rel=tag]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
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
        doc.select("video[src]").forEach { video ->
            val url = video.absUrl("src").ifBlank { video.attr("src") }
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
        private val PAGE_NUMBER = Regex("""/page/(\d+)""")
    }
}
