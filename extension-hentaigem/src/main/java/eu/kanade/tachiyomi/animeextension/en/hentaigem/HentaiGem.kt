package eu.kanade.tachiyomi.animeextension.en.hentaigem

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

class HentaiGem : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiGem"
    override val id: Long = 843337943156741000L
    override val baseUrl = "https://www.hentaigem.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request =
        GET(sectionUrl("most-popular", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(sectionUrl("latest-updates", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) {
            "$baseUrl/search/?q=$encoded"
        } else {
            "$baseUrl/search/?q=$encoded&from_videos=$page"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun sectionUrl(section: String, page: Int): String =
        if (page <= 1) "$baseUrl/$section/" else "$baseUrl/$section/$page/"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div.list-videos div.item")
            .mapNotNull { item ->
                val link = item.selectFirst("a[href*='/videos/']") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = link.attr("title").ifBlank { link.text() }.trim()
                    thumbnail_url = item.selectFirst("img[data-original]")?.let { img ->
                        img.absUrl("data-original").ifBlank { img.absUrl("data-webp") }
                    } ?: item.selectFirst("img")?.let { img ->
                        img.absUrl("data-webp").ifBlank { img.absUrl("src") }
                    }
                }
            }
            .distinctBy { it.url }

        val current = currentPage(response)
        val hasNext = doc.select(".pagination a").any { link ->
            val href = link.attr("href")
            href.contains("from_videos=${current + 1}") ||
                Regex("""/${current + 1}/?$""").containsMatchIn(href)
        } || items.size >= 60
        return AnimesPage(items, hasNext)
    }

    private fun currentPage(response: Response): Int {
        val url = response.request.url.toString()
        if (url.contains("from_videos=")) {
            return Regex("[?&]from_videos=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        }
        return Regex("/(?:latest-updates|most-popular|top-rated)/(\\d+)/").find(url)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val genres = doc.select("a[href*='/categories/']").map { it.text().trim() }
        val tags = doc.select("a[href*='/tags/']").map { it.text().trim() }
        return SAnime.create().apply {
            this.title = title
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = (genres + tags)
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = title
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
        val url = VIDEO_URL.find(html)?.groupValues?.get(1) ?: return emptyList()
        if (!url.startsWith("http")) return emptyList()
        return listOf(MediaVideo.create(url, "MP4", url))
    }

    companion object {
        private val VIDEO_URL = Regex("""video_url:\s*'([^']+)'""")
    }
}
