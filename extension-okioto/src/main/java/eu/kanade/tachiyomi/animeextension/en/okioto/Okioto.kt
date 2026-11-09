package eu.kanade.tachiyomi.animeextension.en.okioto

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

class Okioto : AnimeHttpSource() {

    override val name = "@JalebiBae - Okioto"
    override val id: Long = 5182361200418176396L
    override val baseUrl = "https://okioto.net"
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
        val items = doc.select("article.loop-video")
            .mapNotNull { article ->
                val link = article.selectFirst("a[href][title]")
                    ?: article.selectFirst("a[href^='https://okioto.net/']")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("okioto.net/")) return@mapNotNull null
                val img = article.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = link.attr("title").trim().takeIf { it.isNotBlank() }
                        ?: img?.attr("alt")?.trim().orEmpty()
                    thumbnail_url = article.attr("data-main-thumb").takeIf { it.isNotBlank() }
                        ?: img?.absUrl("src")
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
            title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href*='/category/']")
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
        val embed = doc.selectFirst("div.video-player iframe[src*='xhamster.com/embed/']")
            ?.attr("src")
            ?: return emptyList()
        val id = Regex("/embed/([A-Za-z0-9]+)").find(embed)?.groupValues?.get(1)
            ?: return emptyList()

        val page = fetchText("https://xhamster.com/videos/$id", "$baseUrl/")
            ?: return emptyList()
        val hls = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""")
            .find(page)?.value
            ?.replace("&amp;", "&")
        if (hls != null) return listOf(MediaVideo.create(hls, "HLS", hls, headers))

        return Regex("""https?://[^"'\s<>]+\.mp4[^"'\s<>]*""")
            .findAll(page)
            .map { it.value.replace("&amp;", "&") }
            .filter { it.contains("xhcdn.com") }
            .distinct()
            .map { MediaVideo.create(it, "MP4", it, headers) }
            .toList()
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
