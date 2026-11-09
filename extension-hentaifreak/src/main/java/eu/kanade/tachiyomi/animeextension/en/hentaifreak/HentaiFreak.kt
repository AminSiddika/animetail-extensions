package eu.kanade.tachiyomi.animeextension.en.hentaifreak

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

class HentaiFreak : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiFreak"
    override val id: Long = 4113319840366758407L
    override val baseUrl = "https://hentaifreak.org"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request =
        GET(pageUrl(page, "orderby=post_views"), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(pageUrl(page, "orderby=date"), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET(pageUrl(page, "s=$encoded"), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun pageUrl(page: Int, query: String): String =
        if (page <= 1) "$baseUrl/?$query" else "$baseUrl/page/$page/?$query"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div#content-masonry article.hentry")
            .mapNotNull { card ->
                val link = card.selectFirst("h2.entry-title > a") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = link.text().trim()
                    thumbnail_url = card.selectFirst("img.post-featured-image")?.let { img ->
                        img.absUrl("data-src").ifBlank { img.absUrl("src") }
                    }
                }
            }
            .distinctBy { it.url }

        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val html = doc.html()
        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()
        val description = Regex(
            """(?s)<strong>\s*Synopsis:\s*</strong>(.*?)(?=<br\s*/?>\s*<strong>\s*Alternate Names:|</p>)""",
        ).find(html)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace("&nbsp;", " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        return SAnime.create().apply {
            title = rawTitle.replace(Regex("""\s*-\s*Hentai Stream and Download\s*$"""), "").trim()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("div.hentaitags a[rel=tag]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            this.description = description?.takeIf { it.isNotBlank() }
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
        val doc = response.asJsoup()
        val sources = doc.select("div.tab-content video source[src], video#my_video_1 source[src]")
            .mapNotNull { it.attr("src").takeIf { url -> url.isNotBlank() } }
            .distinct()
        return sources.mapIndexed { index, raw ->
            val url = raw.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D")
            val label = if (url.contains(".m3u8")) "HLS" else "MP4 ${index + 1}"
            MediaVideo.create(url, label, url)
        }
    }
}
