package eu.kanade.tachiyomi.animeextension.en.hentaiyoga

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

class HentaiYoga : AnimeHttpSource() {

    override val name = "@JalebiBae - Hentai.yoga"
    override val id: Long = 4400951629817373367L
    override val baseUrl = "https://hentai.yoga"
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
        val items = doc.select("a[href*='/series/'][href*='/episode/']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/episode/")) return@mapNotNull null
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
            title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href*='/tag/'], a[href*='/genre/']")
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
        val doc = response.asJsoup()
        val iframe = doc.selectFirst("iframe[src*='iframe.mediadelivery.net/embed/']")?.absUrl("src")
            ?: return emptyList()
        val embed = fetchText(iframe) ?: return emptyList()
        val playlist = Regex("""<source[^>]+src="([^"]+playlist\.m3u8[^"]*)"""")
            .find(embed)?.groupValues?.get(1)
            ?: return emptyList()
        return listOf(MediaVideo.create(playlist, "HLS", playlist))
    }

    private fun fetchText(url: String): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "$baseUrl/")
                .build(),
        ).execute().use { it.body?.string() }
    }.getOrNull()

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
