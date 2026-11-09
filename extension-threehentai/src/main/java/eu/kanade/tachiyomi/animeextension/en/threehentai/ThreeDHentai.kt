package eu.kanade.tachiyomi.animeextension.en.threehentai

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
import org.json.JSONObject
import java.net.URLEncoder

class ThreeDHentai : AnimeHttpSource() {

    override val name = "@JalebiBae - 3DHentai"
    override val id: Long = 8355957899450964082L
    override val baseUrl = "https://3dhentai.it.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) {
            "$baseUrl/videos?search=$encoded"
        } else {
            "$baseUrl/videos?search=$encoded&page=$page"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun listUrl(page: Int): String =
        if (page <= 1) "$baseUrl/videos" else "$baseUrl/videos?page=$page"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("a[href^=https://3dhentai.it.com/video/]")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/video/")) return@mapNotNull null
                val img = link.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = img?.attr("alt")?.trim()
                        ?: link.attr("title").ifBlank { link.text() }.trim()
                    thumbnail_url = img?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val current = Regex("[?&]page=(\\d+)").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("a[href]").any {
            it.attr("href").contains("page=${current + 1}")
        } || items.size >= 24
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href*='/categories/'], a[href*='/tag/']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
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
        val urls = linkedSetOf<String>()
        Regex(""""contentUrl"\s*:\s*"([^"]+)"""").findAll(html).forEach {
            it.groupValues[1].replace("\\/", "/").takeIf { u -> u.startsWith("http") }?.let(urls::add)
        }
        doc.select("video source[src]").forEach { source ->
            source.absUrl("src").takeIf { it.startsWith("http") }?.let(urls::add)
        }
        return urls.map { url -> MediaVideo.create(url, if (url.contains(".m3u8")) "HLS" else "MP4", url) }
    }
}
