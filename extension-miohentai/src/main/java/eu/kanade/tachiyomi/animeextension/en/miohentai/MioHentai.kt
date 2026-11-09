package eu.kanade.tachiyomi.animeextension.en.miohentai

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
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

class MioHentai : AnimeHttpSource() {

    override val name = "@JalebiBae - MioHentai"
    override val id: Long = 351232038236878912L
    override val baseUrl = "https://miohentai.com"
    init {
        MediaVideo.referer = "$baseUrl/"
    }
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(pageUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET(
            "$baseUrl/wp-json/wp/v2/posts?search=$encoded&per_page=20&page=$page&_embed=1",
            headers,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body?.string().orEmpty()
        val results = runCatching { JSONArray(body) }.getOrNull() ?: return AnimesPage(emptyList(), false)
        val items = buildList {
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                val link = obj.optString("link")
                if (link.isBlank()) continue
                val title = Jsoup.parse(obj.optJSONObject("title")?.optString("rendered").orEmpty()).text()
                val thumb = obj.optJSONObject("_embedded")
                    ?.optJSONArray("wp:featuredmedia")
                    ?.optJSONObject(0)
                    ?.optString("source_url")
                    ?.takeIf { it.startsWith("http") }
                add(
                    SAnime.create().apply {
                        setUrlWithoutDomain(link)
                        this.title = title
                        thumbnail_url = thumb
                    },
                )
            }
        }
        val current = Regex("[?&]page=(\\d+)").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val total = response.header("X-WP-TotalPages")?.toIntOrNull()
        return AnimesPage(items.distinctBy { it.url }, total != null && current < total)
    }

    private fun pageUrl(page: Int): String =
        if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("article.blog-view")
            .mapNotNull { article ->
                val link = article.selectFirst("a.post-thumbnail[href]") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank() || !ROOT_POST.matches(href)) return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = article.selectFirst("h3")?.text()?.trim()
                        ?: article.selectFirst("img")?.attr("alt")?.removeSuffix(" Thumbnail")?.trim().orEmpty()
                    thumbnail_url = article.selectFirst("img")?.let { img ->
                        img.absUrl("data-src").ifBlank { img.absUrl("src") }
                    }
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
            genre = doc.select("a[href*='/tag/']")
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
        val pageUrl = response.request.url.toString()
        val html = response.asJsoup().html()
        val config = parseBootConfig(html) ?: return emptyList()

        val resolver = config.optJSONObject("videoResolver")
        val ajaxUrl = resolver?.optString("ajaxUrl").orEmpty()
        val postId = config.optInt("postId", 0)
        if (ajaxUrl.startsWith("http") && postId > 0) {
            val body = FormBody.Builder()
                .add("action", resolver?.optString("action").orEmpty().ifBlank { "get_video_url" })
                .add("post_id", postId.toString())
                .add("nonce", resolver?.optString("nonce").orEmpty())
                .build()
            val request = Request.Builder()
                .url(ajaxUrl)
                .post(body)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", pageUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            val fresh = runCatching {
                client.newCall(request).execute().use { it.body?.string() }
            }.getOrNull()?.trim()
            if (!fresh.isNullOrBlank() && fresh.startsWith("http")) {
                return listOf(MediaVideo.create(fresh, "MP4", fresh))
            }
        }

        val fallback = config.optString("videoSrc")
        if (fallback.startsWith("http")) return listOf(MediaVideo.create(fallback, "MP4", fallback))
        return emptyList()
    }

    private fun parseBootConfig(html: String): JSONObject? {
        val match = Regex(
            """window\.videoPlayerBootConfig\s*=\s*(\{.*?\})\s*;\s*(?:\n|</script>)""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(html) ?: return null
        return runCatching { JSONObject(match.groupValues[1]) }.getOrNull()
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val ROOT_POST = Regex("""^https://miohentai\.com/[^/?#]+/$""")
    }
}
