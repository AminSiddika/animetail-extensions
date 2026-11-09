package eu.kanade.tachiyomi.animeextension.en.hentaivideos

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

class HentaiVideos : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiVideos"
    override val id: Long = 3510030743580306429L
    override val baseUrl = "https://hentaivideos.net"
    override val lang = "en"
    override val supportsLatest = true

    @Volatile
    private var popularMode: Boolean = false

    override fun popularAnimeRequest(page: Int): Request {
        popularMode = true
        return GET(pageUrl(page), headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response, popular = true)

    override fun latestUpdatesRequest(page: Int): Request {
        popularMode = false
        return GET(pageUrl(page), headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response, popular = false)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response, popular = false)

    private fun pageUrl(page: Int): String =
        if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page"

    private fun parseList(response: Response, popular: Boolean): AnimesPage {
        val doc = response.asJsoup()
        val blocks = doc.select("ul.video-block")
        val block = if (popular) {
            blocks.firstOrNull { !it.hasClass("active") } ?: blocks.firstOrNull()
        } else {
            blocks.firstOrNull { it.hasClass("active") } ?: blocks.firstOrNull()
        }
        val items = block?.select("li div.video-thumb > a.thumb[href]")
            ?.mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!EPISODE_URL.matches(href)) return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = link.selectFirst(".thumb-descr")?.text()?.trim()
                        ?: link.attr("title").removePrefix("Watch ").trim()
                    thumbnail_url = link.selectFirst("img.lazyload[data-src]")?.absUrl("data-src")
                        ?.takeIf { it.isNotBlank() }
                        ?: link.selectFirst("img[data-src]")?.absUrl("data-src")
                }
            }
            ?.distinctBy { it.url }
            .orEmpty()

        val hasNext = doc.selectFirst(".paginator li.next a[href]") != null
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1.page__name")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href*='/genre/']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("div.video-descr.video-descr3 p")?.text()?.trim()
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
        val downloads = doc.select("#downLink a[href]")
            .mapNotNull { it.absUrl("href").takeIf { url -> url.isNotBlank() } }
            .distinct()
        if (downloads.isNotEmpty()) {
            return downloads.mapIndexed { index, url ->
                val label = if (downloads.size > 1) "Part ${index + 1}" else "MP4"
                Video(url, label, url)
            }
        }

        val frames = doc.select("iframe[src*='/frames/']")
            .mapNotNull { it.absUrl("src").takeIf { url -> url.isNotBlank() } }
            .distinct()
        return frames.mapIndexedNotNull { index, frame ->
            val html = fetchText(frame) ?: return@mapIndexedNotNull null
            val url = Regex(""""file"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: return@mapIndexedNotNull null
            val label = if (frames.size > 1) "Part ${index + 1}" else "MP4"
            Video(url, label, url)
        }
    }

    private fun fetchText(url: String): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "$baseUrl/")
                .build(),
        ).execute().use { it.body?.string() }
    }.getOrNull()

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val EPISODE_URL =
            Regex("""^https://hentaivideos\.net/[a-z0-9][a-z0-9_\-]*-episode-\d+$""")
    }
}
