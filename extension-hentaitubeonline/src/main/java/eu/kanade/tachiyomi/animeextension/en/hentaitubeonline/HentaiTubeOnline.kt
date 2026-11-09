package eu.kanade.tachiyomi.animeextension.en.hentaitubeonline

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

class HentaiTubeOnline : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiTube.online"
    override val id: Long = 8165307191022465040L
    override val baseUrl = "https://www.hentaitube.online"
    override val lang = "en"
    override val supportsLatest = true

    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    override fun popularAnimeRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search/$encoded/", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response, paged = false)

    private fun listUrl(page: Int): String =
        if (page <= 1) "$baseUrl/hentai-list/" else "$baseUrl/hentai-list/$page/"

    private fun parseList(response: Response, paged: Boolean = true): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("#archive-content article.item.tvshows")
            .mapNotNull { item ->
                val link = item.selectFirst(".data h3 a[href*='/tvshows/']")
                    ?: item.selectFirst(".poster a[href*='/tvshows/']")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/tvshows/")) return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(href.trimEnd('/') + "/")
                    title = link.text().trim().ifBlank {
                        item.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
                    }
                    thumbnail_url = item.selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() }
                }
            }
            .distinctBy { it.url }

        val hasNext = paged && doc.select("#paginacao a").any { it.text().contains("»") }
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val description = doc.select("#info1 .wp-content p")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        return SAnime.create().apply {
            title = doc.selectFirst("#single .sheader .data h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("#single .sheader .poster img")?.absUrl("src")
            genre = doc.select(".sheader .data > .sgeneros a")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            this.description = description.ifBlank {
                doc.selectFirst("meta[name=description]")?.attr("content")
            }
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        response.asJsoup()
            .select("#episodes article.item.se.episodes")
            .mapNotNull { item ->
                val link = item.selectFirst(".season_m a[href*='/episodes/']")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                val label = item.selectFirst(".season_m a .c")?.text()?.trim()
                    ?: item.selectFirst(".data h3")?.text()?.trim()
                val number = EPISODE_NUMBER.find(label.orEmpty())
                    ?.groupValues?.get(1)?.toFloatOrNull()
                    ?: EPISODE_NUMBER.find(href)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: 0f
                SEpisode.create().apply {
                    setUrlWithoutDomain(href.trimEnd('/') + "/")
                    name = label?.takeIf { it.isNotEmpty() } ?: "Episode ${number.toInt()}"
                    episode_number = number
                }
            }
            .distinctBy { it.url }
            .sortedBy { it.episode_number }

    override fun videoListParse(response: Response): List<Video> {
        val episodeUrl = response.request.url.toString()
        val doc = response.asJsoup()
        val iframe = doc.selectFirst("#playex iframe[src*='player.php?padrao=']")
            ?.attr("src")
            ?: doc.selectFirst("iframe[src*='player.php?padrao=']")?.attr("src")
            ?: return emptyList()

        val token = PADRAO.find(iframe)?.groupValues?.get(1) ?: return emptyList()
        val encodedToken = URLEncoder.encode(token, "UTF-8").replace("+", "%20")
        val playerUrl = "$baseUrl/player.php?padrao=$encodedToken"

        val playerPage = runCatching {
            client.newCall(
                Request.Builder()
                    .url(playerUrl)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", episodeUrl)
                    .build(),
            ).execute().use { it.body?.string() }
        }.getOrNull() ?: return emptyList()

        val p2Path = P2_SOURCE.find(playerPage)?.groupValues?.get(1)
            ?: return emptyList()
        val p2Url = if (p2Path.startsWith("http")) p2Path else baseUrl + p2Path

        val location = runCatching {
            noRedirectClient.newCall(
                Request.Builder()
                    .url(p2Url)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", "$baseUrl/")
                    .build(),
            ).execute().use { it.header("Location") }
        }.getOrNull() ?: return emptyList()

        val encoded = location.replace(" ", "%20")
        if (!encoded.startsWith("http")) return emptyList()
        return listOf(
            Video(
                encoded,
                "MP4",
                encoded,
                headers = videoHeaders(),
            ),
        )
    }

    private fun videoHeaders(): Headers = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", DESKTOP_UA)
        .build()

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val PADRAO = Regex("""player\.php\?padrao=([A-Za-z0-9+/=%]+)""")
        private val P2_SOURCE = Regex("""<source\s+src="(/p2\.php\?id=[^"]+)"\s+type="video/mp4"""")
        private val EPISODE_NUMBER = Regex("""(?i)(?:episode|ep)[\s-]*(\d+)""")
    }
}
