package eu.kanade.tachiyomi.animeextension.en.hentaiocean

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
import java.net.URLDecoder
import java.net.URLEncoder

class HentaiOcean : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiOcean"
    override val id: Long = 8488013344619572852L
    override val baseUrl = "https://hentaiocean.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/", headers)

    override fun searchAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("a[href^=https://hentaiocean.com/watch/]")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/watch/")) return@mapNotNull null
                val img = link.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = img?.attr("alt")?.trim()
                        ?: link.attr("title").ifBlank { link.text() }.trim()
                    thumbnail_url = img?.absUrl("src")
                }
            }
            .distinctBy { it.url }
        return AnimesPage(items, false)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href*='/genre/']")
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
        val raw = Regex("""var jsondata = (\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val mirrors = json.optJSONArray("mirrors") ?: return emptyList()
        val urls = linkedSetOf<String>()
        for (i in 0 until mirrors.length()) {
            val mirror = mirrors.optJSONObject(i) ?: continue
            val play = mirror.optString("mirrorurl").replace("\\/", "/")
            if (!play.startsWith("http")) continue
            val vid = Regex("[?&]vid=([^&]+)").find(play)?.groupValues?.get(1)
                ?: continue
            val decoded = URLDecoder.decode(vid, "UTF-8")
            val encoded = URLEncoder.encode(decoded, "UTF-8").replace("+", "%20")
            urls.add("https://w2.hentaiocean.com/video/$encoded")
        }
        return urls.map { url -> Video(url, "MP4", url) }
    }
}
