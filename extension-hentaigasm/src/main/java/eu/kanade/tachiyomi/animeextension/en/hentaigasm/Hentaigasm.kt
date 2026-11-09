package eu.kanade.tachiyomi.animeextension.en.hentaigasm

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

class Hentaigasm : AnimeHttpSource() {

    override val name = "@JalebiBae - Hentaigasm"
    override val id: Long = 4755056524044778684L
    override val baseUrl = "https://hentaigasm.com"
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
        if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div.item")
            .mapNotNull { item ->
                val link = item.selectFirst("a.clip-link[href]")
                    ?: item.selectFirst("h2.title a[href]")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!VIDEO_URL.matches(href)) return@mapNotNull null
                val img = item.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = item.selectFirst("h2.title a")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                        ?: img?.attr("alt")?.trim().orEmpty()
                    thumbnail_url = img?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val current = Regex("/page/(\\d+)/").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("a[href]").any {
            it.attr("href").contains("/page/${current + 1}/")
        }
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href^='https://hentaigasm.com/genre/']")
                .map { it.text().trim() }
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
        val doc = response.asJsoup()
        val html = doc.html()
        val urls = mutableListOf<String>()
        FILE.findAll(html).forEach { match ->
            val url = match.groupValues[1].replace("\\/", "/")
            if (url.startsWith("http")) urls.add(url)
        }
        doc.select("a[href*='hgasm3.com'][download]").forEach { link ->
            val url = link.absUrl("href").ifBlank { link.attr("href") }
            if (url.startsWith("http")) urls.add(url)
        }
        return urls.distinct()
            .map { raw ->
                val url = raw.replace(" ", "%20")
                proxyVideo(url, "MP4")
            }
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
        private val VIDEO_URL = Regex("""^https://hentaigasm\.com/[a-z0-9-]+/$""")
        private val FILE = Regex("\\bfile:\\s*\"([^\"]+\\.mp4[^\"]*)\"")
    }
}
