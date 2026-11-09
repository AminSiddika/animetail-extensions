package eu.kanade.tachiyomi.animeextension.en.hanime

import eu.kanade.tachiyomi.animeextension.proxy.MediaVideo

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class HAnime : AnimeHttpSource() {

    override val name = "@JalebiBae - H-Anime"
    override val id: Long = 3992978880970788141L
    override val baseUrl = "https://h-anime.net"
    init {
        MediaVideo.referer = "$baseUrl/"
    }
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request =
        GET(listUrl(page, "popular"), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(listUrl(page, "update"), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun listUrl(page: Int, order: String): String {
        val base = if (page <= 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/"
        return "$base?order=$order"
    }

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("article.bs .bsx > a[href]")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(href.trimEnd('/') + "/")
                    title = link.attr("title").ifBlank { link.selectFirst(".tt")?.text().orEmpty() }.trim()
                    thumbnail_url = link.selectFirst("img")?.absUrl("src")
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
            title = doc.selectFirst("h1.entry-title")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".thumbook .thumb img")?.absUrl("src")
            genre = doc.select(".genxed a[href*='/genres/']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst(".mindesc")?.text()?.trim()
                ?: doc.selectFirst(".desc")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        response.asJsoup()
            .select(".eplister li a[href]")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("-episode-")) return@mapNotNull null
                val number = link.selectFirst(".epl-num")?.text()?.trim()?.toIntOrNull()
                    ?: EPISODE.find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                SEpisode.create().apply {
                    setUrlWithoutDomain(href.trimEnd('/') + "/")
                    name = "Episode $number"
                    episode_number = number.toFloat()
                }
            }
            .distinctBy { it.url }
            .sortedBy { it.episode_number }

    override fun videoListParse(response: Response): List<Video> {
        val episodeUrl = response.request.url.toString()
        val doc = response.asJsoup()
        val embeds = doc.select("iframe[src]")
            .mapNotNull { it.absUrl("src").ifBlank { it.attr("src") } }
            .filter { it.contains("doodstream", true) || it.contains("doodst.", true) }
            .distinct()
        if (embeds.isEmpty()) return emptyList()
        return embeds.flatMap { resolveDood(it, episodeUrl) }.distinctBy { it.url }
    }

    private fun resolveDood(embedUrl: String, referer: String): List<Video> {
        val html = fetchText(embedUrl, referer) ?: return emptyList()
        val block = Regex("""sources:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""\{[^{}]*?"file"\s*:\s*"(.*?)"[^{}]*?}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(block)
            .mapNotNull { match ->
                val obj = match.value
                var url = match.groupValues[1].replace("\\/", "/")
                if (url.startsWith("//")) url = "https:$url"
                if (!url.startsWith("http")) return@mapNotNull null
                url = url.replace(" ", "%20")
                val label = Regex(""""label"\s*:\s*"([^"]*)"""")
                    .find(obj)?.groupValues?.get(1).orEmpty().ifBlank { "MP4" }
                val headers = Headers.Builder().add("Referer", embedUrl).build()
                MediaVideo.create(url, label, url, headers = headers)
            }
            .toList()
    }

    private fun fetchText(url: String, referer: String): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", referer)
                .build(),
        ).execute().use { it.body?.string() }
    }.getOrNull()

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val EPISODE = Regex("""-episode-(\d+)""")
    }
}
