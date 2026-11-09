package eu.kanade.tachiyomi.animeextension.en.hentaicloud

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

class HentaiCloud : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiCloud"
    override val id: Long = 2020258780867681619L
    override val baseUrl = "https://www.hentaicloud.com"
    init {
        MediaVideo.referer = "$baseUrl/"
    }
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request =
        GET(listUrl("mv", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(listUrl("mr", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search?search_type=videos&search_query=$encoded", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response, paged = false)

    private fun listUrl(order: String, page: Int): String =
        if (page <= 1) "$baseUrl/videos?o=$order" else "$baseUrl/videos?o=$order&page=$page"

    private fun parseList(response: Response, paged: Boolean = true): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div.video-item")
            .mapNotNull { item ->
                val link = item.selectFirst("a[href^=/video/]")
                    ?: item.selectFirst("a[href*='/video/']")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/video/")) return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = item.selectFirst("span.name a")?.text()?.trim()
                        ?: link.attr("title").trim()
                    thumbnail_url = item.selectFirst("img")?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val current = Regex("[?&]page=(\\d+)").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val total = Regex("([\\d,]+)\\s*Episodes")
            .find(doc.selectFirst("span.p-total")?.text().orEmpty())
            ?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        val hasNext = paged && if (total != null) {
            current * 23 < total
        } else {
            items.size >= 23
        }
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("video#player_one[poster]")?.attr("poster")
        return SAnime.create().apply {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: doc.selectFirst("div.video-info h1")?.text()?.trim().orEmpty()
            thumbnail_url = poster?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            genre = doc.select("div.category li a")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("div.description article")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
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
        val raw = response.asJsoup().html().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val sources = Regex("""<source\s+src="(https://www\.hentaicloud\.com/media/videos/(hd|iphone)/\d+\.mp4)"[^>]*>""")
            .findAll(raw)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        return sources
            .sortedByDescending { it.second == "hd" }
            .distinctBy { it.first }
            .map { (url, kind) -> MediaVideo.create(url, if (kind == "hd") "720p" else "SD", url) }
    }
}
