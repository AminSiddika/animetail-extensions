package eu.kanade.tachiyomi.animeextension.en.hentaibros

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
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.net.URLEncoder

class Hentaibros : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiBros"
    override val id: Long = 7289593570327561969L
    override val baseUrl = "https://hentaibros.net"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request =
        GET(pageUrl(page, "filter=popular"), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(pageUrl(page, "filter=latest"), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val url = if (page <= 1) {
            "$baseUrl/search/$encoded/"
        } else {
            "$baseUrl/search/$encoded/page/$page/"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun pageUrl(page: Int, query: String): String =
        if (page <= 1) "$baseUrl/?$query" else "$baseUrl/page/$page/?$query"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        if (doc.selectFirst("h1")?.text()?.contains("Nothing found", true) == true) {
            return AnimesPage(emptyList(), false)
        }
        val items = doc.select("#main article.thumb-block.format-video")
            .mapNotNull { card ->
                val link = card.selectFirst("a[href]") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = card.selectFirst("header.entry-header span")?.text()?.trim()
                        ?: link.attr("title").trim()
                    thumbnail_url = card.selectFirst("img.video-main-thumb")?.absUrl("src")
                        ?: card.attr("data-main-thumb").takeIf { it.startsWith("http") }
                }
            }
            .distinctBy { it.url }

        val current = Regex("/page/(\\d+)/").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("div.pagination a[href]").any {
            it.attr("href").contains("/page/${current + 1}/")
        }
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("div.tags-list a[href*='/genres/']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("div.video-description div.desc")?.text()?.trim()
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
        val html = response.asJsoup().html()
        val raw = Regex("""data-item="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val decoded = Parser.unescapeEntities(raw, false)
        val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return emptyList()
        val sources = json.optJSONArray("sources") ?: return emptyList()
        return buildList {
            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                val url = source.optString("src")
                if (!url.startsWith("http")) continue
                val type = source.optString("type")
                val label = if (type.contains("mpegurl", true)) "HLS" else "MP4"
                add(Video(url, label, url))
            }
        }.distinctBy { it.url }
    }
}
