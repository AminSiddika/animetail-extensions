package eu.kanade.tachiyomi.animeextension.en.hentaistreamtv

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
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder

class HentaistreamTv : AnimeHttpSource() {

    override val name = "@JalebiBae - Hentaistream.tv"
    override val id: Long = 4399319176870368526L
    override val baseUrl = "https://hentaistream.tv"
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
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/?s=$encoded", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun listUrl(page: Int): String =
        if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("a[href*='/watch/']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!SERIES_URL.matches(href)) return@mapNotNull null
                val img = link.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                        ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                        ?: link.text().trim()
                    thumbnail_url = img?.absUrl("src")
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
            genre = doc.select("a[href*='/genre/'], a[href*='/tag/']")
                .map { it.text().trim() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("meta[name=description]")?.attr("content")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val seriesUrl = response.request.url.toString().trimEnd('/')
        val doc = response.asJsoup()
        val episodes = doc.select("a[href]")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.startsWith("$seriesUrl/episode-")) return@mapNotNull null
                val number = Regex("/episode-(\\d+)/?").find(href)
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    name = "Episode $number"
                    episode_number = number.toFloat()
                }
            }
            .distinctBy { it.url }
            .sortedBy { it.episode_number }
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
        val iframe = doc.selectFirst("iframe[src*='player.php?data=']")
            ?: return emptyList()
        val playerUrl = iframe.absUrl("src").ifBlank { iframe.attr("src") }
            .replace("&amp;", "&")
        val playerPage = fetchText(playerUrl, response.request.url.toString())
            ?: return emptyList()
        val token = Regex("<meta name=\"x-secure-token\" content=\"([^\"]+)\"")
            .find(playerPage)?.groupValues?.get(1)
            ?: return emptyList()
        val config = decryptConfig(token) ?: return emptyList()

        val en = config.optString("en")
        val iv = config.optString("iv")
        var uri = config.optString("uri")
        if (uri.isBlank() || en.isBlank() || iv.isBlank()) return emptyList()
        if (uri.startsWith("//")) uri = "https:$uri"
        if (!uri.endsWith("/")) uri += "/"
        val apiUrl = uri + "api.php"

        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("action", "zarat_get_data_player_ajax")
            .addFormDataPart("a", en)
            .addFormDataPart("b", iv)
            .build()
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", playerUrl)
            .header("Origin", baseUrl)
            .header("Accept", "application/json, text/plain, */*")
            .post(form)
            .build()
        val body = runCatching {
            client.newCall(request).execute().use { it.body?.string().orEmpty() }
        }.getOrNull() ?: return emptyList()

        val sources = runCatching {
            JSONObject(body).optJSONObject("data")?.optJSONArray("sources")
        }.getOrNull() ?: return emptyList()

        val videoHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        val videos = mutableListOf<Video>()
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            var src = source.optString("src").replace("\\/", "/")
            if (src.isBlank()) continue
            if (src.startsWith("//")) src = "https:$src"
            if (src.startsWith("/")) src = baseUrl + src
            if (!src.startsWith("http")) continue
            val label = source.optString("label").ifBlank {
                if (src.contains(".m3u8")) "HLS" else "MP4"
            }
            videos.add(MediaVideo.create(src, label, src, videoHeaders))
        }
        return videos.distinctBy { it.url }
    }

    private fun decryptConfig(token: String): JSONObject? {
        var value = token.removePrefix("sha512-")
        repeat(3) {
            value = rot13(value)
            value = runCatching {
                String(
                    Base64.decode(padBase64(value), Base64.DEFAULT),
                    Charsets.UTF_8,
                )
            }.getOrNull() ?: return null
        }
        return runCatching { JSONObject(value) }.getOrNull()
    }

    private fun rot13(value: String): String = buildString(value.length) {
        for (char in value) {
            append(
                when (char) {
                    in 'A'..'Z' -> 'A' + ((char - 'A' + 13) % 26)
                    in 'a'..'z' -> 'a' + ((char - 'a' + 13) % 26)
                    else -> char
                },
            )
        }
    }

    private fun padBase64(value: String): String =
        value + "=".repeat((4 - value.length % 4) % 4)

    private fun fetchText(url: String, referer: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", referer)
            .build()
        client.newCall(request).execute().use { it.body?.string() }
    }.getOrNull()

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val SERIES_URL = Regex("""^https://hentaistream\.tv/watch/[a-z0-9-]+/?$""")
    }
}
