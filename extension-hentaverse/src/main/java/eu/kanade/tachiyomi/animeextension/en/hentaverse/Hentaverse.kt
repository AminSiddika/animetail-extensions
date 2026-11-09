package eu.kanade.tachiyomi.animeextension.en.hentaverse

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

class Hentaverse : AnimeHttpSource() {

    override val name = "@JalebiBae - Hentaverse"
    override val id: Long = 269604309978318660L
    override val baseUrl = "https://hentaverse.com"
    override val lang = "en"
    override val supportsLatest = true

    @Volatile
    private var searchQuery: String = ""

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/hentai", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/hentai", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        searchQuery = query
        return GET("$baseUrl/hentai", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val all = parseList(response).animes
        val q = searchQuery.trim()
        if (q.isBlank()) return AnimesPage(emptyList(), false)
        return AnimesPage(all.filter { it.title.contains(q, true) }, false)
    }

    private fun parseList(response: Response): AnimesPage {
        val html = response.body?.string().orEmpty()
        val items = SERIES.findAll(html)
            .mapNotNull { match ->
                val name = unescape(match.groupValues[1])
                val slug = match.groupValues[2]
                val image = match.groupValues[3].replace("\\/", "/")
                if (slug.isBlank()) return@mapNotNull null
                SAnime.create().apply {
                    url = "/hentai/$slug"
                    title = name
                    thumbnail_url = if (image.startsWith("http")) image
                    else "https://cdn.hentaverse.com/" + image.trimStart('/')
                }
            }
            .distinctBy { it.url }
            .toList()
        return AnimesPage(items, false)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href^=/categories/]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("meta[name=description]")?.attr("content")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val scoped = doc.select("#episodes-section a[href^=/video/]")
        val links = if (scoped.isNotEmpty()) scoped else doc.select("a[href^=/video/]")
        return links
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/video/")) return@mapNotNull null
                val title = link.selectFirst(".video_card_title")?.text()?.trim()
                    ?: link.selectFirst("img")?.attr("alt")?.trim()
                    ?: link.text().trim()
                val number = EPISODE.find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: EPISODE.find(title)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0
                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    name = title.ifBlank { "Episode $number" }
                    episode_number = number.toFloat()
                }
            }
            .distinctBy { it.url }
            .sortedBy { it.episode_number }
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val raw = doc.selectFirst("meta[property=og:video]")?.attr("content")
            ?: Regex(""""contentUrl":"([^"]+)"""").find(doc.html())?.groupValues?.get(1)
            ?: return emptyList()
        val base = raw.replace("cdn.hentaverse.com//", "cdn.hentaverse.com/")
        val urls = listOf("1080p", "720p", "480p")
            .map { quality -> base.replace("1080p.mp4", "$quality.mp4") }
            .distinct()
        return urls.map { url ->
            val quality = Regex("""/(\d{3,4})p\.mp4""").find(url)?.groupValues?.get(1)
                ?.let { "${it}p" } ?: "MP4"
            MediaVideo.create(url, quality, url)
        }
    }

    private fun unescape(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    companion object {
        private val SERIES = Regex(
            """\\"name\\":\\"([^"\\]+)\\",\\"description\\":.*?\\"slug\\":\\"([^"\\]+)\\",\\"image\\":\\"([^"\\]+)\\"""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val EPISODE = Regex("""(?i)(?:episode|ep)[\s-]*(\d+)""")
    }
}
