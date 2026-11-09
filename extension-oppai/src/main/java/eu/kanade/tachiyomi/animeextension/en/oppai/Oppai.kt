package eu.kanade.tachiyomi.animeextension.en.oppai

import eu.kanade.tachiyomi.animeextension.proxy.ProxySupport
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class Oppai : AnimeHttpSource() {

    override val name = "@JalebiBae - Oppai.Stream"
    override val id: Long = 6464131142362638028L
    override val baseUrl = "https://oppai.stream"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = searchRequest("", page, "popular")

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = searchRequest("", page, "recent")

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        searchRequest(query, page, "recent")

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun searchRequest(text: String, page: Int, order: String): Request {
        val body = FormBody.Builder()
            .add("text", text)
            .add("tags", "")
            .add("blacklist", "")
            .add("studios", "")
            .add("order", order)
            .add("limit", "24")
            .add("page", page.toString())
            .add("classes", "smaller-episode-gap")
            .add("character_filters", "{}")
            .build()
        return Request.Builder()
            .url("$baseUrl/api/search.php")
            .post(body)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", "$baseUrl/search")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("a.episode[href^=https://oppai.stream/watch/]")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/watch/")) return@mapNotNull null
                val img = link.selectFirst("img.cover")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = img?.attr("alt")?.removeSuffix(" thumbnail")?.trim()
                        ?: link.attr("title").trim()
                    thumbnail_url = img?.absUrl("data-src")?.takeIf { it.isNotBlank() }
                        ?: img?.absUrl("src")
                }
            }
            .distinctBy { it.url }
        return AnimesPage(items, items.size >= 15)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href*='/category/']")
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
        val html = response.asJsoup().html()
        val folder = Regex("""var folder = "([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val storage = Regex("""var storageBase = "([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?: "https://oppai.stream/videos"
        val res = Regex("""var res = "([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
        val ext = if (res.split(",").any { it.startsWith("4k-1") }) "mp4" else "webm"
        val videoUrl = storage.trimEnd('/') + "/" + folder + "/ep." + ext
        val dashUrl = storage.trimEnd('/') + "/" + folder + "/dash/manifest.mpd"
        return buildList {
            add(proxyVideo(videoUrl, ext.uppercase()))
            add(Video(dashUrl, "4K DASH", dashUrl))
        }.distinctBy { it.url }
    }

    private fun proxyVideo(url: String, label: String): Video {
        val proxied = ProxySupport.url(client, url, "$baseUrl/")
        return Video(proxied, label, proxied, headers)
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
