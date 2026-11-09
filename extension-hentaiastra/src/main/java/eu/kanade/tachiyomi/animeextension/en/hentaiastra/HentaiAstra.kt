package eu.kanade.tachiyomi.animeextension.en.hentaiastra

import eu.kanade.tachiyomi.animeextension.proxy.MediaVideo

import android.util.Base64
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

class HentaiAstra : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiAstra"
    override val id: Long = 4569267103063950831L
    override val baseUrl = "https://hentaiastra.com"
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
        val items = doc.select("div.MovieItem")
            .mapNotNull { item ->
                val link = item.selectFirst("a[href]") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("hentaiastra.com/")) return@mapNotNull null
                val style = item.selectFirst("div.poster")?.attr("data-style").orEmpty()
                val poster = Regex("""url\(([^)]+)\)""").find(style)
                    ?.groupValues?.get(1)?.trim('\'', '"')
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = item.selectFirst("div.title h4")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: item.selectFirst("div.title p")?.text()?.trim()
                        ?: link.attr("title").trim()
                    thumbnail_url = poster
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
        val detail = response.request.url.toString().trimEnd('/')
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain("$detail/watch/")
                name = "Full Video"
                episode_number = 1f
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val videos = mutableListOf<Video>()
        doc.select("li[data-watch]").forEach { item ->
            val watchUrl = item.attr("data-watch")
                .replace("&amp;", "&")
            if (!watchUrl.contains("hentaized.com/e/")) return@forEach
            val page = fetchText(watchUrl, response.request.url.toString()) ?: return@forEach
            val blob = Regex("""atob\("([^"]+)"\)""").find(page)
                ?.groupValues?.get(1)
                ?: return@forEach
            val js = runCatching {
                String(Base64.decode(blob, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: return@forEach
            val url = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")
                .find(js)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?: return@forEach
            if (url.startsWith("http")) videos.add(MediaVideo.create(url, "HLS", url, headers))
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

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
