package eu.kanade.tachiyomi.animeextension.en.xanimeporn

import eu.kanade.tachiyomi.animeextension.proxy.ProxySupport
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

class XAnimePorn : AnimeHttpSource() {

    override val name = "@JalebiBae - XAnimePorn"
    override val id: Long = 4408282602072346120L
    override val baseUrl = "https://xanimeporn.com"
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
        val items = doc.select("ul.listing-tube li")
            .mapNotNull { item ->
                val link = item.selectFirst("a[href][title]")
                    ?: item.selectFirst("a[href]")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("xanimeporn.com/")) return@mapNotNull null
                val img = item.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = link.attr("title").trim().takeIf { it.isNotBlank() }
                        ?: link.text().trim().takeIf { it.isNotBlank() }
                        ?: img?.attr("alt")?.trim().orEmpty()
                    thumbnail_url = img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
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
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href^='https://xanimeporn.com/genre/']")
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
        val links = doc.select("a[href*='download.php']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                    .replace("&amp;", "&")
                if (!href.startsWith("http")) return@mapNotNull null
                val quality = Regex("[?&]quality=([^&]+)").find(href)
                    ?.groupValues?.get(1)?.uppercase() ?: "MP4"
                quality to href
            }
            .distinctBy { it.second }

        return links.mapNotNull { (label, downloadUrl) ->
            val url = resolveRedirect(downloadUrl) ?: downloadUrl
            proxyVideo(url, label)
        }
    }

    private fun resolveRedirect(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", "$baseUrl/")
            .build()
        noRedirectClient.newCall(request).execute().use { response ->
            response.header("Location")
        }
    }.getOrNull()?.replace("[", "%5B")
        ?.replace("]", "%5D")
        ?.replace(" ", "%20")

    private val noRedirectClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
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
