package eu.kanade.tachiyomi.animeextension.en.hentaihorizon

import eu.kanade.tachiyomi.animeextension.proxy.MediaVideo

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class HentaiHorizon : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiHorizon"
    override val id: Long = 3984430965603324359L
    override val baseUrl = "https://hentaihorizon.com"
    init {
        MediaVideo.referer = "$baseUrl/"
    }
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/?trending=15days", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val trending = doc.select("section").firstOrNull { section ->
            section.selectFirst("h2")?.text()?.contains("Trending", true) == true
        } ?: return AnimesPage(emptyList(), false)
        return AnimesPage(parseItems(trending), false)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/category/videos?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search?q=$encoded&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = parseItems(doc)
        val current = Regex("[?&]page=(\\d+)").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.selectFirst("a[rel=next]") != null ||
            doc.select("nav[aria-label*=Pagination] a[href]").any {
                it.attr("href").contains("page=${current + 1}")
            }
        return AnimesPage(items, hasNext)
    }

    private fun parseItems(root: org.jsoup.nodes.Element): List<SAnime> =
        root.select("div.text-white.p-2.group")
            .mapNotNull { card ->
                val link = card.selectFirst("a.block[href]") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = card.selectFirst("h3")?.text()?.trim()
                        ?: card.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
                    thumbnail_url = card.selectFirst("img")?.absUrl("src")
                }
            }
            .distinctBy { it.url }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val header = doc.selectFirst("article header") ?: doc
        val categories = header.select("a[href*='/category/']").map { it.text().trim() }
        val tags = header.select("span.inline-block").map { it.text().trim() }
        return SAnime.create().apply {
            title = header.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = header.selectFirst("img.post-thumbnail")?.absUrl("src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = (categories + tags)
                .filter { it.isNotEmpty() && !it.equals("Serie", true) }
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
        val detailUrl = response.request.url.toString()
        val doc = response.asJsoup()
        val iframe = doc.selectFirst("#video-container-0 iframe[src]")?.absUrl("src")
            ?: doc.selectFirst("iframe[src*='iframe.mediadelivery.net/embed/']")?.absUrl("src")
            ?: return emptyList()

        val embed = fetchText(iframe, "$baseUrl/") ?: return emptyList()
        val playlist = Regex(
            """<source[^>]+type="application/vnd\.apple\.mpegURL"[^>]+src="([^"]+playlist\.m3u8[^"]*)"""",
        ).find(embed)?.groupValues?.get(1)
            ?: Regex("""(https://[^"']+/playlist\.m3u8[^"']*)""").find(embed)?.groupValues?.get(1)
            ?: return emptyList()

        val headers = Headers.Builder().add("Referer", "$baseUrl/").build()
        return listOf(MediaVideo.create(playlist, "HLS", playlist, headers = headers))
    }

    private fun fetchText(url: String, referer: String?): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
        referer?.let { builder.header("Referer", it) }
        client.newCall(builder.build()).execute().use { it.body?.string() }
    }.getOrNull()

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
