package eu.kanade.tachiyomi.animeextension.en.zhentube

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
import org.json.JSONObject
import java.net.URLEncoder

class ZhenTube : AnimeHttpSource() {

    override val name = "@JalebiBae - ZhenTube"
    override val id: Long = 7201517584102320390L
    override val baseUrl = "https://zhentube.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request =
        GET(pageUrl(page, "filter=most-viewed"), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(pageUrl(page, "filter=latest"), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET(pageUrl(page, "s=$encoded"), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun pageUrl(page: Int, query: String): String =
        if (page <= 1) "$baseUrl/?$query" else "$baseUrl/page/$page/?$query"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("main#main article.thumb-block")
            .mapNotNull { card ->
                val link = card.selectFirst("a[href]") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!POST_URL.matches(href)) return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = link.attr("title").ifBlank {
                        card.selectFirst("header.entry-header span")?.text().orEmpty()
                    }.trim()
                    thumbnail_url = card.attr("data-main-thumb").takeIf { it.startsWith("http") }
                        ?: card.selectFirst("img[data-src]")?.absUrl("data-src")
                }
            }
            .distinctBy { it.url }

        val hasNext = doc.select("div.pagination a[href]").any { link ->
            val text = link.text().trim()
            text.equals("Next", true) || text.equals("Last", true) ||
                link.attr("href").contains("/page/")
        }
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1.entry-title")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select(".tags .tags-list a.label")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst(".video-description .desc")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
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
        val detailUrl = response.request.url.toString()
        val doc = response.asJsoup()
        val embed = doc.selectFirst("meta[itemprop=embedUrl]")?.attr("content")
            ?: doc.selectFirst("div.video-player iframe[data-src]")?.attr("data-src")
            ?: return emptyList()
        val hash = HASH.find(embed)?.groupValues?.get(1) ?: return emptyList()

        val body = FormBody.Builder()
            .add("hash", hash)
            .add("r", detailUrl)
            .build()
        val request = Request.Builder()
            .url("https://javbest.cc/player/index.php?data=$hash&do=getVideo")
            .post(body)
            .header("User-Agent", DESKTOP_UA)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", detailUrl)
            .build()
        val text = runCatching {
            client.newCall(request).execute().use { it.body?.string() }
        }.getOrNull() ?: return emptyList()
        if (!text.trimStart().startsWith("{")) return emptyList()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()

        val secured = json.optString("securedLink")
        val master = json.optString("videoSource")
        val url = secured.takeIf { it.startsWith("http") }
            ?: master.takeIf { it.startsWith("http") }
            ?: return emptyList()
        return listOf(MediaVideo.create(url, "HLS", url))
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val POST_URL = Regex("""^https://zhentube\.com/[^/?#]+/$""")
        private val HASH = Regex("""javbest\.cc/video/([0-9a-f]{32})""")
    }
}
