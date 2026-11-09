package eu.kanade.tachiyomi.animeextension.en.hentaimama

import eu.kanade.tachiyomi.animeextension.proxy.MediaVideo

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
import org.json.JSONArray
import org.jsoup.Jsoup
import java.net.URLEncoder

class HentaiMama : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiMama"
    override val id: Long = 2332318789350898624L
    override val baseUrl = "https://hentaimama.io"
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
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return GET("$baseUrl/search/$encoded/", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun listUrl(page: Int): String =
        if (page <= 1) "$baseUrl/hentai-series/" else "$baseUrl/hentai-series/page/$page/"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("article.series-card")
            .mapNotNull { article ->
                val link = article.selectFirst("a.sc-poster[href]")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                val img = article.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = article.selectFirst("h3.sc-title a")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: article.selectFirst("h3.sc-title")?.text()?.trim()
                        ?: img?.attr("alt")?.trim().orEmpty()
                    thumbnail_url = img?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val current = Regex("/hentai-series/page/(\\d+)/").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("a[href]").any {
            it.attr("href").contains("/hentai-series/page/${current + 1}/")
        }
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select(".dt_mainmeta nav.genres a, .sgeneros a")
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
        val episodes = doc.select("a[href^='https://hentaimama.io/episodes/']")
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
        val postId = doc.selectFirst("[data-post]")?.attr("data-post")
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val options = doc.select("a[href^='#option-']")
            .mapNotNull { link ->
                Regex("#option-(\\d+)").find(link.attr("href"))
                    ?.groupValues?.get(1)?.toIntOrNull()
            }
            .distinct()
            .ifEmpty { listOf(1, 3, 5) }

        val videos = mutableListOf<Video>()
        for (option in options) {
            val body = FormBody.Builder()
                .add("action", "get_player_contents")
                .add("a", postId)
                .add("i", option.toString())
                .build()
            val request = Request.Builder()
                .url("$baseUrl/wp-admin/admin-ajax.php")
                .header("User-Agent", DESKTOP_UA)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", response.request.url.toString())
                .post(body)
                .build()
            val text = runCatching {
                client.newCall(request).execute().use { it.body?.string().orEmpty() }
            }.getOrNull() ?: continue
            val array = runCatching { JSONArray(text) }.getOrNull() ?: continue
            for (i in 0 until array.length()) {
                val fragment = array.optString(i)
                if (fragment.isBlank()) continue
                val iframeSrc = Jsoup.parse(fragment).selectFirst("iframe[src]")
                    ?.attr("src")
                    ?.replace("&amp;", "&")
                    ?.replace("&#038;", "&")
                    ?.replace("&#38;", "&")
                    ?.takeIf { it.contains("dt_embed=") }
                    ?: continue
                val embed = fetchText(iframeSrc, response.request.url.toString()) ?: continue
                SOURCE_OBJECT.findAll(embed).forEach { match ->
                    val url = match.groupValues[1].replace("\\/", "/")
                    val label = match.groupValues[2]
                    if (url.startsWith("http")) videos.add(proxyVideo(url, label))
                }
                if (videos.isEmpty()) {
                    SOURCE_FILE.findAll(embed).forEach { match ->
                        val url = match.groupValues[1].replace("\\/", "/")
                        if (url.startsWith("http")) videos.add(proxyVideo(url, "MP4"))
                    }
                }
            }
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
        private val SOURCE_OBJECT = Regex(
            """\{[^{}]*?"file"\s*:\s*"([^"]+)"[^{}]*?"label"\s*:\s*"([^"]+)"[^{}]*?\}""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val SOURCE_FILE = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")
    }
}
