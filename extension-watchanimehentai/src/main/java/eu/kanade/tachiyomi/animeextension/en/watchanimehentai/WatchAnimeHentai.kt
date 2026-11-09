package eu.kanade.tachiyomi.animeextension.en.watchanimehentai

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
import org.jsoup.Jsoup
import java.net.URLEncoder

class WatchAnimeHentai : AnimeHttpSource() {

    override val name = "@JalebiBae - WatchAnimeHentai"
    override val id: Long = 1476099918794882295L
    override val baseUrl = "https://www.watchanimehentai.com"
    init {
        MediaVideo.referer = "$baseUrl/"
    }
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/?s=$encoded", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun listUrl(page: Int): String =
        if (page <= 1) "$baseUrl/hentai/" else "$baseUrl/hentai/$page/"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("article.item")
            .mapNotNull { article ->
                val link = article.select("a[href^='/info/']")
                    .firstOrNull { it.text().isNotBlank() || it.attr("title").isNotBlank() }
                    ?: article.selectFirst("a[href^='/info/']")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/info/")) return@mapNotNull null
                val img = article.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = article.selectFirst("h3 a")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                        ?: img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                        ?: href.substringAfter("/info/").trimEnd('/')
                    thumbnail_url = img?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val current = Regex("/hentai/(\\d+)/").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("a[href]").any {
            it.attr("href").contains("/hentai/${current + 1}/")
        }
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href^='/genre/']")
                .map { it.text().trim() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("meta[name=description]")?.attr("content")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val episodes = doc.select("a[href^='/episodes/']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null
                val number = Regex("/episode-(\\d+)/").find(href)
                    ?.groupValues?.get(1)?.toFloatOrNull()
                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    name = link.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: link.text().trim().takeIf { it.isNotBlank() }
                        ?: "Episode ${number?.toInt() ?: 1}"
                    episode_number = number ?: 1f
                }
            }
            .distinctBy { it.url }
        if (episodes.isNotEmpty()) return episodes
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = "Full Video"
                episode_number = 1f
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val videos = mutableListOf<Video>()
        doc.select("iframe[src*='/players/p2/']").forEach { iframe ->
            val playerUrl = iframe.absUrl("src").ifBlank { iframe.attr("src") }
            val playerHtml = fetchText(playerUrl, response.request.url.toString()) ?: return@forEach
            val source = Jsoup.parse(playerHtml).selectFirst("source[src]")?.attr("src")
                ?: return@forEach
            val absolute = when {
                source.startsWith("http") -> source
                source.startsWith("//") -> "https:$source"
                else -> baseUrl + source
            }
            val finalUrl = resolveRedirect(absolute, response.request.url.toString())
                ?.replace(" ", "%20")
                ?: return@forEach
            videos.add(proxyVideo(finalUrl, "MP4"))
        }
        return videos.distinctBy { it.url }
    }

    private fun fetchText(url: String, referer: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", referer)
            .build()
        client.newCall(request).execute().use { it.body?.string() }
    }.getOrNull()

    private fun resolveRedirect(url: String, referer: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", referer)
            .build()
        noRedirectClient.newCall(request).execute().use { response ->
            response.header("Location")
                ?: url.takeIf {
                    response.header("Content-Type")?.startsWith("video/") == true
                }
        }
    }.getOrNull()

    private val noRedirectClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private fun proxyVideo(url: String, label: String): Video {
        val videoHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            )
            .build()
        return MediaVideo.create(url, label, url, videoHeaders)
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
