package eu.kanade.tachiyomi.animeextension.en.hentaihd

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder

class HentaiHD : AnimeHttpSource() {

    override val name = "@JalebiBae - HentaiHD"
    override val id: Long = 6913463672953127187L
    override val baseUrl = "https://hentaihd.net"
    init {
        MediaVideo.referer = "$baseUrl/"
    }
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request =
        GET(animeUrl(page, sortPopular = true), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(animeUrl(page, sortPopular = false), headers)

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

    private fun animeUrl(page: Int, sortPopular: Boolean): String {
        val suffix = if (sortPopular) "?sort=popular" else ""
        return if (page <= 1) "$baseUrl/anime/$suffix" else "$baseUrl/anime/page/$page/$suffix"
    }

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("article.anime-card")
            .mapNotNull { card ->
                val link = card.selectFirst("a.anime-card__link[href]") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/anime/")) return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(href.trimEnd('/') + "/")
                    title = card.selectFirst("h3.anime-card__title")?.text()?.trim()
                        ?: link.attr("title").substringBefore("|").trim()
                    thumbnail_url = card.selectFirst("img.anime-card__image")?.absUrl("src")
                        ?: card.selectFirst("img")?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val hasNext = doc.selectFirst("nav.animehub-pagination li.page-next a") != null
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1.anime-title")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("div.anime-poster img")?.absUrl("src")
            genre = doc.select("div.anime-hero__genres a.genre-tag, div.meta-tags-list a.meta-tag")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("div.synopsis-text")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        response.asJsoup()
            .select("div.episodes-list div.episode-row")
            .flatMap { row ->
                val links = mutableListOf<Pair<String, String>>()
                row.selectFirst("a.episode-number[href]")?.let { link ->
                    val href = link.absUrl("href").ifBlank { link.attr("href") }
                    if (href.isNotBlank()) {
                        val label = row.selectFirst("span.episode-number__label")?.text()?.trim().orEmpty()
                        links.add(href to label.ifBlank { "Episode" })
                    }
                }
                row.select("div.episode-variants a.variant-badge[href]").forEach { link ->
                    val href = link.absUrl("href").ifBlank { link.attr("href") }
                    if (href.isNotBlank()) {
                        links.add(href to link.attr("title").ifBlank { link.text().trim() })
                    }
                }
                links.map { (href, label) ->
                    val number = EPISODE.find(label)?.groupValues?.get(1)?.toFloatOrNull()
                        ?: EPISODE.find(href)?.groupValues?.get(1)?.toFloatOrNull()
                        ?: 0f
                    SEpisode.create().apply {
                        setUrlWithoutDomain(href.trimEnd('/') + "/")
                        name = label.ifBlank { "Episode ${number.toInt()}" }
                        episode_number = number
                    }
                }
            }
            .distinctBy { it.url }
            .sortedBy { it.episode_number }

    override fun videoListParse(response: Response): List<Video> {
        val html = response.asJsoup().html()
        val ulid = ULID.find(html)?.groupValues?.get(1) ?: return emptyList()
        val nonce = runCatching {
            val body = client.newCall(
                Request.Builder()
                    .url("$baseUrl/wp-json/animehub/v1/player-nonce")
                    .header("User-Agent", DESKTOP_UA)
                    .build(),
            ).execute().use { it.body?.string() }
            JSONObject(body.orEmpty()).optString("nonce")
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: REST_NONCE.find(html)?.groupValues?.get(1)
            ?: return emptyList()

        val payload = JSONObject()
            .put("id", ulid)
            .put("type", "esv")
            .put("nonce", nonce)
            .toString()
        val request = Request.Builder()
            .url("$baseUrl/wp-json/animehub/v1/video-url")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "application/json")
            .build()

        val body = runCatching {
            client.newCall(request).execute().use { it.body?.string() }
        }.getOrNull() ?: return emptyList()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val url = json.optString("url")
        val type = json.optString("type")
        if (!url.startsWith("http") || (type != "hls" && type != "mp4")) return emptyList()
        val label = if (type == "hls") "HLS" else "MP4"
        return listOf(
            MediaVideo.create(
                url,
                label,
                url,
                headers = videoHeaders(),
            ),
        )
    }

    private fun videoHeaders(): Headers = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", DESKTOP_UA)
        .build()

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val ULID = Regex("""data-esv-ulid="([0-9A-Za-z_-]{10,40})"""")
        private val REST_NONCE = Regex(""""restNonce":"([A-Za-z0-9]+)"""")
        private val EPISODE = Regex("""(?i)(?:episode|ep)[\s-]*(\d+(?:\.\d+)?)""")
    }
}
