package eu.kanade.tachiyomi.animeextension.en.javguru

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class JavGuru : AnimeHttpSource() {

    override val name = "@JalebiBae - Jav.Guru"
    override val id: Long = 1707968472392837187L
    override val baseUrl = "https://jav.guru"
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
        val items = doc.select("a[href^='https://jav.guru/']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!VIDEO_URL.matches(href)) return@mapNotNull null
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
        } || items.size >= 20
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")
                ?: doc.selectFirst(".large-screenshot img, article img")?.absUrl("src")
            genre = doc.select("a[href*='/category/'], a[href*='/tag/'], a[href*='/actress/']")
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
        val videos = mutableListOf<Video>()
        val seen = mutableSetOf<String>()

        val encodedIframes = Regex("\"iframe_url\":\"([^\"]+)\"")
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()

        for (encoded in encodedIframes) {
            val firstUrl = decodeBase64(encoded) ?: continue
            val searchUrl = if (firstUrl.startsWith("http")) firstUrl else continue
            val firstPage = fetch(searchUrl, response.request.url.toString()) ?: continue
            val xd = Regex("[?&]xd=([^&]+)").find(searchUrl)?.groupValues?.get(1)
                ?: Regex("[?&]xd=([^&]+)").find(firstPage.first)?.groupValues?.get(1)
                ?: continue
            val xr = xd.reversed()
            val streamPage = fetch("$baseUrl/searcho/?xr=$xr", searchUrl) ?: continue
            val finalUrl = streamPage.second
            val body = streamPage.first
            val unpacked = unpackPacker(body) ?: body

            val hls2 = Regex("\"hls2\"\\s*:\\s*\"([^\"]+)\"").find(unpacked)?.groupValues?.get(1)
            val hls4 = Regex("\"hls4\"\\s*:\\s*\"([^\"]+)\"").find(unpacked)?.groupValues?.get(1)
            val hls3 = Regex("\"hls3\"\\s*:\\s*\"([^\"]+)\"").find(unpacked)?.groupValues?.get(1)
            val chosen = listOf(hls2, hls4, hls3)
                .filterNotNull()
                .map { it.replace("\\/", "/") }
                .map { resolve(it, finalUrl) }
                .firstOrNull { it != null }
                ?: continue
            if (!seen.add(chosen)) continue
            val videoHeaders = headers.newBuilder()
                .set("Referer", finalUrl)
                .set("User-Agent", DESKTOP_UA)
                .build()
            videos.add(Video(chosen, "HLS", chosen, videoHeaders))
        }
        return videos
    }

    private fun resolve(url: String, base: String): String? {
        if (url.startsWith("http")) return url
        return base.toHttpUrlOrNull()?.resolve(url)?.toString()
    }

    private fun decodeBase64(value: String): String? = runCatching {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    private fun unpackPacker(html: String): String? {
        val match = Regex(
            """eval\(function\(p,a,c,k,e,d\).*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(html) ?: return null
        val packed = match.groupValues[1]
        val radix = match.groupValues[2].toIntOrNull() ?: return null
        val words = match.groupValues[4].split('|')
        return Regex("""\b\w+\b""").replace(packed) { token ->
            val index = token.value.toIntOrNull(radix)
            if (index != null && index < words.size && words[index].isNotEmpty()) {
                words[index]
            } else {
                token.value
            }
        }
    }

    private fun fetch(url: String, referer: String): Pair<String, String>? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", referer)
            .build()
        client.newCall(request).execute().use {
            it.body?.string().orEmpty() to it.request.url.toString()
        }
    }.getOrNull()

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val VIDEO_URL = Regex("""^https://jav\.guru/\d+/[^/?#]+/?$""")
    }
}
