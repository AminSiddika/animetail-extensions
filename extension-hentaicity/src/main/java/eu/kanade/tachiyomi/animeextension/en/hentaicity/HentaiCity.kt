package eu.kanade.tachiyomi.animeextension.en.hentaicity

import eu.kanade.tachiyomi.animeextension.proxy.MediaVideo

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

class HentaiCity : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiCity"
    override val id: Long = 6807862366543739110L
    override val baseUrl = "https://www.hentaicity.com"
    init {
        MediaVideo.referer = "$baseUrl/"
    }
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request =
        GET(listUrl("all-popular", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(listUrl("all-recent", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search/video/$encoded", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response, allowPaging = false)

    private fun listUrl(kind: String, page: Int): String =
        if (page <= 1) {
            "$baseUrl/videos/straight/$kind.html"
        } else {
            "$baseUrl/videos/straight/$kind-$page.html"
        }

    private fun parseList(response: Response, allowPaging: Boolean = true): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div.item")
            .mapNotNull { item ->
                val link = item.selectFirst("a.video-title")
                    ?: item.selectFirst("a.clip-link")
                    ?: item.selectFirst("a[href*='/video/']")
                    ?: return@mapNotNull null

                val href = link.absUrl("href").ifBlank { link.attr("href") }
                    .replace(CLICK_PREFIX, "")
                if (href.isBlank()) return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = link.attr("title").ifBlank { link.text() }
                    thumbnail_url = item.selectFirst("img")?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val hasNext = allowPaging && doc.selectFirst(".pagination a.next") != null
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            author = doc.selectFirst("#taglink a[href*='/profile/']")?.text()
            genre = doc.select("#taglink a")
                .filterNot { it.absUrl("href").contains("/profile/") }
                .joinToString(", ") { it.text() }
            description = doc.selectFirst("meta[name=description]")?.attr("content")
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

        val mp4 = doc.selectFirst("meta[property=og:video:url]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:video:secure_url]")?.attr("content")
        if (!mp4.isNullOrBlank()) {
            videos += MediaVideo.create(mp4, "MP4", mp4)
        }

        doc.select("video source[src]").forEach { source ->
            val url = source.absUrl("src").ifBlank { source.attr("src") }
            if (url.isNotBlank() && videos.none { it.videoUrl == url }) {
                videos += MediaVideo.create(url, if (url.contains(".m3u8")) "HLS" else "Source", url)
            }
        }

        return videos
    }

    companion object {
        private val CLICK_PREFIX = Regex("""/click/\d+-\d+""")
    }
}
