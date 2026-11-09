package eu.kanade.tachiyomi.animeextension.en.hentaisea

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
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

class HentaiSea : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiSea"
    override val id: Long = 6673811530837040275L
    override val baseUrl = "https://hentaisea.com"
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
        if (page <= 1) "$baseUrl/videos/" else "$baseUrl/videos/page/$page/"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("article.item")
            .mapNotNull { article ->
                val link = article.selectFirst("a[href^=https://hentaisea.com/videos/]")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                val img = article.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = article.selectFirst("h3")?.text()?.trim()
                        ?: img?.attr("alt")?.trim().orEmpty()
                    thumbnail_url = img?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val current = Regex("/videos/page/(\\d+)/").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("a[href]").any {
            it.attr("href").contains("/videos/page/${current + 1}/")
        }
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select(".dt_mainmeta nav.genres a")
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
        val option = doc.selectFirst("li.dooplay_player_option") ?: return emptyList()
        val postId = option.attr("data-post").takeIf { it.isNotBlank() } ?: return emptyList()
        val nume = option.attr("data-nume").ifBlank { "1" }
        val type = option.attr("data-type").ifBlank { "movie" }

        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", postId)
            .add("nume", nume)
            .add("type", type)
            .build()
        val request = Request.Builder()
            .url("$baseUrl/wp-admin/admin-ajax.php")
            .header("User-Agent", DESKTOP_UA)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", response.request.url.toString())
            .post(body)
            .build()
        val html = runCatching {
            client.newCall(request).execute().use { it.body?.string().orEmpty() }
        }.getOrNull() ?: return emptyList()

        val iframe = Jsoup.parse(html).selectFirst("iframe[src]")?.attr("src")
            ?.replace("&amp;", "&")
            ?: return emptyList()
        val source = Regex("[?&]source=([^&]+)").find(iframe)?.groupValues?.get(1)
            ?: return emptyList()
        val url = URLDecoder.decode(source, "UTF-8")
        if (!url.startsWith("http")) return emptyList()
        return listOf(Video(url, "MP4", url, headers))
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
