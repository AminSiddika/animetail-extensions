package eu.kanade.tachiyomi.animeextension.en.mundohentai

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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class MundoHentai : AnimeHttpSource() {

    override val name = "@JalebiBae - MundoHentai"
    override val id: Long = 805069891989744991L
    override val baseUrl = "https://mundohentai.net"
    override val lang = "en"
    override val supportsLatest = true

    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/hentai/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/hentai/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/buscar/?q=$encoded", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("a[href^=/info/]")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/info/")) return@mapNotNull null
                val img = link.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = img?.attr("alt")?.trim()
                        ?: link.attr("title").trim()
                        ?: link.text().trim()
                    thumbnail_url = img?.absUrl("data-src")?.takeIf { it.isNotBlank() }
                        ?: img?.absUrl("src")
                }
            }
            .distinctBy { it.url }
        return AnimesPage(items, false)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href*='/genero/']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("div.wp-content p")?.text()?.trim()
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        response.asJsoup()
            .select("article.item.se.episodes a[href^=/episodios/]")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                val label = link.selectFirst(".season_m .c")?.text()?.trim().orEmpty()
                val number = EPISODE.find(label)?.groupValues?.get(1)?.toIntOrNull()
                    ?: EPISODE.find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    name = if (label.isNotBlank()) label else "Episódio $number"
                    episode_number = number.toFloat()
                }
            }
            .distinctBy { it.url }
            .sortedBy { it.episode_number }

    override fun videoListParse(response: Response): List<Video> {
        val episodeUrl = response.request.url.toString()
        val doc = response.asJsoup()
        val iframe = doc.selectFirst("iframe[src*='/players/p2/']")?.absUrl("src")
            ?: return emptyList()
        val player = fetchText(iframe, episodeUrl) ?: return emptyList()
        val p2 = Regex("""<source src="([^"]+)"""").find(player)?.groupValues?.get(1)
            ?: return emptyList()

        val request = Request.Builder()
            .url(p2)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", "https://www.muitohentai.com/")
            .header("Range", "bytes=0-1023")
            .build()
        val location = runCatching {
            noRedirectClient.newCall(request).execute().use { it.header("Location") }
        }.getOrNull() ?: return emptyList()
        val url = location.replace(" ", "%20")
        if (!url.startsWith("http")) return emptyList()
        val headers = Headers.Builder()
            .add("Referer", "https://www.muitohentai.com/")
            .add("User-Agent", DESKTOP_UA)
            .build()
        return listOf(MediaVideo.create(url, "MP4", url, headers = headers))
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
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val EPISODE = Regex("""(?i)epis[oó]dio[\s-]*(\d+)""")
    }
}
