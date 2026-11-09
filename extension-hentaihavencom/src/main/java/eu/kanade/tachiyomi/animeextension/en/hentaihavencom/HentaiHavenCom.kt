package eu.kanade.tachiyomi.animeextension.en.hentaihavencom

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
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder

class HentaiHavenCom : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiHaven.com"
    override val id: Long = 9110705273108960989L
    override val baseUrl = "https://hentaihaven.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(listUrl(page), headers)

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

    private fun listUrl(page: Int): String =
        if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div.item")
            .mapNotNull { item ->
                val link = item.selectFirst("a[href*='/video/']") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!SERIES_URL.matches(href)) return@mapNotNull null

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

        val current = Regex("/page/(\\d+)/").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("a[href]").any { it.attr("href").contains("/page/${current + 1}/") }
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("div.single_data a[href*='/genre/']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val seriesPath = response.request.url.encodedPath.trimEnd('/')
        return response.asJsoup()
            .select("a[href]")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("$seriesPath/episode-")) return@mapNotNull null
                val number = EPISODE.find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                SEpisode.create().apply {
                    setUrlWithoutDomain(href.trimEnd('/') + "/")
                    name = link.selectFirst(".title")?.text()?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "Episode $number"
                    episode_number = number.toFloat()
                }
            }
            .distinctBy { it.url }
            .sortedBy { it.episode_number }
    }

    override fun videoListParse(response: Response): List<Video> {
        val episodeUrl = response.request.url.toString()
        val iframe = response.asJsoup()
            .selectFirst("iframe[src*='player-logic/player.php']")
            ?.absUrl("src")
            ?: return emptyList()

        val playerDoc = fetchDoc(iframe, episodeUrl) ?: return emptyList()
        val token = playerDoc.selectFirst("meta[name=x-secure-token]")?.attr("content")
            ?: return emptyList()
        val config = decodeToken(token) ?: return emptyList()

        val en = config.optString("en")
        val iv = config.optString("iv")
        if (en.isBlank() || iv.isBlank()) return emptyList()

        val body = FormBody.Builder()
            .add("action", "zarat_get_data_player_ajax")
            .add("a", en)
            .add("b", iv)
            .build()
        val request = Request.Builder()
            .url("$baseUrl/wp-content/plugins/player-logic/api.php")
            .post(body)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", iframe)
            .header("Origin", baseUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json")
            .build()

        val payload = runCatching {
            client.newCall(request).execute().use { it.body?.string() }
        }.getOrNull() ?: return emptyList()

        val data = runCatching { JSONObject(payload).optJSONObject("data") }.getOrNull()
            ?: return emptyList()
        val sources = data.optJSONArray("sources") ?: return emptyList()

        return buildList {
            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                val url = source.optString("src")
                if (!url.startsWith("http")) continue
                val label = source.optString("label").ifBlank { "Auto" }
                add(Video(url, label, url))
            }
        }.distinctBy { it.url }
    }

    private fun fetchDoc(url: String, referer: String) = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", referer)
                .build(),
        ).execute().use { it.asJsoup() }
    }.getOrNull()

    // The player token is: rot13 -> base64 -> rot13 -> base64 -> rot13 -> base64 -> JSON
    private fun decodeToken(raw: String): JSONObject? = runCatching {
        var value = raw.removePrefix("sha512-")
        repeat(3) {
            value = rot13(value)
            value = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
        }
        JSONObject(value)
    }.getOrNull()

    private fun rot13(value: String): String = buildString(value.length) {
        for (c in value) {
            append(
                when (c) {
                    in 'a'..'z' -> 'a' + ((c - 'a' + 13) % 26)
                    in 'A'..'Z' -> 'A' + ((c - 'A' + 13) % 26)
                    else -> c
                },
            )
        }
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val SERIES_URL = Regex("""^https://hentaihaven\.com/video/[^/?#]+/?$""")
        private val EPISODE = Regex("""/episode-(\d+)""")
    }
}
