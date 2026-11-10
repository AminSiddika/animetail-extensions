package eu.kanade.tachiyomi.animeextension.en.hanimehentai

import eu.kanade.tachiyomi.animeextension.proxy.MediaVideo

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder

class HanimeHentai : AnimeHttpSource() {

    override val name = "@JalebiBae - HanimeHentai"
    override val id: Long = 637031594592778223L
    override val baseUrl = "https://hanimehentai.tv"
    init {
        MediaVideo.referer = "$baseUrl/"
    }
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val trending = doc.select("div.block").firstOrNull { block ->
            block.selectFirst("h3.htitle")?.text()?.contains("Trending", true) == true
        }
        val items = parseItems(trending ?: doc)
        return AnimesPage(items, false)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page <= 1) "$baseUrl/video/" else "$baseUrl/video/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) {
            "$baseUrl/?s=$encoded"
        } else {
            "$baseUrl/page/$page/?s=$encoded"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = parseItems(doc)
        val hasNext = doc.selectFirst("a.nextpostslink[rel=next]") != null ||
            doc.select("a.page.larger").any {
                (it.text().trim().toIntOrNull() ?: 0) > currentPage(response)
            }
        return AnimesPage(items, hasNext)
    }

    private fun parseItems(root: org.jsoup.nodes.Element): List<SAnime> =
        root.select("div.item")
            .mapNotNull { item ->
                val link = item.selectFirst("a[href*='/video/']") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = item.selectFirst(".title")?.text()?.trim()
                        ?: link.attr("title").ifBlank { link.text() }.trim()
                    thumbnail_url = item.selectFirst("img")?.let { img ->
                        img.absUrl("data-src").ifBlank { img.absUrl("src") }
                    }
                }
            }
            .distinctBy { it.url }

    private fun currentPage(response: Response): Int =
        Regex("/page/(\\d+)/").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1.htitle")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("div.hentai_cover img")?.absUrl("src")
            genre = doc.select("div.single_data a[rel=tag]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("div.vraven_text.single")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        response.asJsoup()
            .select("li.hentai__chapter a[href]")
            .mapIndexedNotNull { index, link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/episode-")) return@mapIndexedNotNull null
                val raw = EPISODE.find(href)?.groupValues?.get(1)
                val number = raw?.toFloatOrNull() ?: (index + 1).toFloat()
                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    name = link.selectFirst("span.title")?.text()?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "Episode ${raw ?: index + 1}"
                    episode_number = number
                }
            }
            .distinctBy { it.url }
            .sortedBy { it.episode_number }

    override fun videoListParse(response: Response): List<Video> {
        val episodeUrl = response.request.url.toString()
        val iframe = response.asJsoup()
            .selectFirst("iframe[src*='player-logic/player.php']")
            ?.absUrl("src")
            ?: return emptyList()
        val data = Regex("""player\.php\?data=([^&"]+)""").find(iframe)
            ?.groupValues?.get(1)
            ?: return emptyList()

        val decoded = runCatching {
            String(Base64.decode(data, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return emptyList()
        val parts = decoded.split(":|::|:")
        if (parts.size < 2) return emptyList()
        val en = parts[0]
        val iv = parts[1]
        val b = Base64.encodeToString(iv.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val body = FormBody.Builder()
            .add("action", "zarat_get_data_player_ajax")
            .add("a", en)
            .add("b", b)
            .build()
        val request = Request.Builder()
            .url("$baseUrl/wp-content/plugins/player-logic/api.php")
            .post(body)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", episodeUrl)
            .header("Origin", baseUrl)
            .build()

        val payload = runCatching {
            client.newCall(request).execute().use { it.body?.string() }
        }.getOrNull() ?: return emptyList()
        val sources = runCatching {
            JSONObject(payload).optJSONObject("data")?.optJSONArray("sources")
        }.getOrNull() ?: return emptyList()

        val videoHeaders = Headers.Builder()
            .set("Referer", episodeUrl)
            .set("Origin", baseUrl)
            .build()
        return buildList {
            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                val url = source.optString("src")
                if (!url.startsWith("http")) continue
                val label = source.optString("label").ifBlank { "Auto" }
                add(MediaVideo.create(url, label, url, videoHeaders))
            }
        }.distinctBy { it.url }
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val EPISODE = Regex("""/episode-([^/?#]+)""")
    }
}
