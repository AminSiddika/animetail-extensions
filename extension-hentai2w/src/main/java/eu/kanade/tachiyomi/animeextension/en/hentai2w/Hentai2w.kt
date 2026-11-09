package eu.kanade.tachiyomi.animeextension.en.hentai2w

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
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

class Hentai2w : AnimeHttpSource() {

    override val name = "@JalebiBae - Hentai2W"
    override val id: Long = 1088020651482575489L
    override val baseUrl = "https://hentai2w.com"
    override val lang = "en"
    override val supportsLatest = true

    @Volatile
    private var ageCookie: String? = null

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/browse?sort=popular&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/browse?sort=new&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/browse?q=$encoded&sort=new&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun props(doc: org.jsoup.nodes.Document): JSONObject? {
        val raw = doc.selectFirst("script[data-page=app]")?.data() ?: return null
        return runCatching { JSONObject(raw).optJSONObject("props") }.getOrNull()
    }

    private fun parseList(response: Response): AnimesPage {
        val props = props(response.asJsoup()) ?: return AnimesPage(emptyList(), false)
        val series = props.optJSONObject("series") ?: return AnimesPage(emptyList(), false)
        val data = series.optJSONArray("data") ?: return AnimesPage(emptyList(), false)
        val items = buildList {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val slug = item.optString("slug")
                if (slug.isBlank()) continue
                add(
                    SAnime.create().apply {
                        url = "/series/$slug"
                        title = item.optString("title")
                        thumbnail_url = item.optString("cover_url").takeIf { it.startsWith("http") }
                    },
                )
            }
        }.distinctBy { it.url }
        val hasNext = !series.optString("next_page_url").isNullOrBlank()
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val props = props(response.asJsoup()) ?: return SAnime.create()
        val series = props.optJSONObject("series") ?: return SAnime.create()
        val tags = series.optJSONArray("tags")
        val genre = buildList {
            if (tags != null) {
                for (i in 0 until tags.length()) {
                    tags.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }.distinct().joinToString(", ")
        val description = series.optString("description").takeUnless {
            it.isBlank() || it.equals("Nothing yet!", true)
        }
        return SAnime.create().apply {
            title = series.optString("title")
            thumbnail_url = series.optString("cover_url").takeIf { it.startsWith("http") }
                ?: series.optString("cover").takeIf { it.startsWith("http") }
            this.genre = genre
            this.description = description
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val props = props(response.asJsoup()) ?: return emptyList()
        val episodes = props.optJSONArray("episodes") ?: return emptyList()
        return buildList {
            for (i in 0 until episodes.length()) {
                val ep = episodes.optJSONObject(i) ?: continue
                val id = ep.optInt("id", 0)
                if (id <= 0) continue
                val number = ep.optInt("number", i + 1).toFloat()
                add(
                    SEpisode.create().apply {
                        url = "/watch/$id"
                        name = ep.optString("title").ifBlank { "Episode ${number.toInt()}" }
                        episode_number = number
                    },
                )
            }
        }.sortedBy { it.episode_number }
    }

    override fun videoListParse(response: Response): List<Video> {
        val props = props(response.asJsoup()) ?: return emptyList()
        val playlist = props.optString("playlist")
        if (!playlist.startsWith("http")) return emptyList()

        val cookie = getAgeCookie() ?: return emptyList()
        val headers = Headers.Builder()
            .add("Cookie", "age_confirmed=$cookie")
            .add("Referer", "$baseUrl/")
            .build()
        return listOf(MediaVideo.create(playlist, "HLS", playlist, headers = headers))
    }

    private fun getAgeCookie(): String? {
        ageCookie?.let { return it }
        synchronized(this) {
            ageCookie?.let { return it }
            val home = runCatching {
                client.newCall(GET("$baseUrl/", headers)).execute().use { response ->
                    val cookies = response.headers("Set-Cookie")
                        .mapNotNull { it.substringBefore(";").takeIf { c -> c.contains("=") } }
                    val xsrf = cookies.firstOrNull { it.startsWith("XSRF-TOKEN=") }
                        ?.substringAfter("XSRF-TOKEN=")
                        ?.let { URLDecoder.decode(it, "UTF-8") }
                    xsrf to cookies.joinToString("; ")
                }
            }.getOrNull() ?: return null
            val token = home.first ?: return null
            val cookieHeader = home.second

            val body = FormBody.Builder().build()
            val request = Request.Builder()
                .url("$baseUrl/age-gate")
                .post(body)
                .header("User-Agent", DESKTOP_UA)
                .header("X-XSRF-TOKEN", token)
                .header("Cookie", cookieHeader)
                .header("Referer", "$baseUrl/")
                .build()
            val value = runCatching {
                client.newCall(request).execute().use { response ->
                    response.headers("Set-Cookie")
                        .firstOrNull { it.startsWith("age_confirmed=") }
                        ?.substringAfter("age_confirmed=")
                        ?.substringBefore(";")
                }
            }.getOrNull()
            ageCookie = value
            return value
        }
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
