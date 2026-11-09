package eu.kanade.tachiyomi.animeextension.en.underhentai

import eu.kanade.tachiyomi.animeextension.proxy.MediaVideo

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

class UnderHentai : AnimeHttpSource() {

    override val name = "@JalebiBae - UnderHentai"
    override val id: Long = 8720741465438590649L
    override val baseUrl = "https://www.underhentai.net"
    init {
        MediaVideo.referer = "$baseUrl/"
    }
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/releases/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/releases/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/?s=$encoded", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("article.post-card")
            .mapNotNull { card ->
                val link = card.selectFirst("a.flex-col[href]")
                    ?: card.selectFirst("a[href]")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("underhentai.net/")) return@mapNotNull null
                val img = card.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = card.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                        ?: img?.attr("alt")?.trim().orEmpty()
                    thumbnail_url = img?.absUrl("src")
                }
            }
            .distinctBy { it.url }
        return AnimesPage(items, false)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href^='/genre/']")
                .map { it.text().trim() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("meta[name=description]")?.attr("content")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val episodes = doc.select("a[href*='/watch/?id=']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null
                val ep = Regex("[?&]ep=(\\d+)").find(href)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    name = link.text().trim().takeIf { it.isNotBlank() }
                        ?: "Episode ${ep + 1}"
                    episode_number = (ep + 1).toFloat()
                }
            }
            .distinctBy { it.url }
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
        val videos = mutableListOf<Video>()
        doc.select("iframe[src*='luluvdo.com/e/']").forEach { iframe ->
            val embedUrl = iframe.absUrl("src").ifBlank { iframe.attr("src") }
            val page = fetchText(embedUrl, response.request.url.toString()) ?: return@forEach
            val unpacked = unpackLulu(page) ?: return@forEach
            val stream = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)""")
                .find(unpacked)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?: return@forEach
            if (stream.startsWith("http")) videos.add(MediaVideo.create(stream, "HLS", stream, headers))
        }
        return videos.distinctBy { it.url }
    }

    private fun unpackLulu(html: String): String? {
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
    }
}
