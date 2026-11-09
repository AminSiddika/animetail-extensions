package eu.kanade.tachiyomi.animeextension.en.hentai0

import eu.kanade.tachiyomi.animeextension.proxy.MediaVideo

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.net.URLEncoder

class Hentai0 : AnimeHttpSource() {

    override val name = "@JalebiBae - Hentai0"
    override val id: Long = 9068977780688523816L
    override val baseUrl = "https://hentai0.com"
    override val lang = "en"
    override val supportsLatest = true

    private data class Csrf(val token: String, val cookie: String)

    @Volatile
    private var csrf: Csrf? = null

    @Volatile
    private var reqPage: Int = 1

    @Volatile
    private var reqQuery: String = ""

    @Volatile
    private var reqMode: String = "latest"

    override fun popularAnimeRequest(page: Int): Request {
        reqMode = "popular"
        reqPage = page
        return GET("$baseUrl/popular", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = postList()

    override fun latestUpdatesRequest(page: Int): Request {
        reqMode = "latest"
        reqPage = page
        return GET("$baseUrl/new-movies", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = postList()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        reqMode = "search"
        reqPage = page
        reqQuery = query
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search?keyword=$encoded", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = postList()

    private fun postList(): AnimesPage {
        val current = csrf ?: bootstrapCsrf() ?: return AnimesPage(emptyList(), false)
        val isSearch = reqMode == "search"
        val url = if (isSearch) {
            "$baseUrl/search/vue?page=$reqPage"
        } else {
            "$baseUrl/fetchvideos?page=$reqPage"
        }
        val bodyObj = JSONObject()
        if (isSearch) {
            bodyObj.put("keyword", reqQuery)
            bodyObj.put("sortBy", "")
        } else {
            val (source, title) = if (reqMode == "popular") {
                "dedicated-popular" to "Popular Movies"
            } else {
                "dedicated-new" to "Newest Movies"
            }
            bodyObj.put("keyword", "")
            bodyObj.put("tag_id", "")
            bodyObj.put("title", title)
            bodyObj.put("grid", true)
            bodyObj.put("source", source)
            bodyObj.put("popular_order", "views")
            bodyObj.put("sortBy", "")
        }

        val json = postJson(url, bodyObj.toString(), current) ?: return AnimesPage(emptyList(), false)
        val wrapper = json.optJSONObject("data") ?: return AnimesPage(emptyList(), false)
        val arr = wrapper.optJSONArray("data") ?: return AnimesPage(emptyList(), false)
        val slugs = json.optJSONArray("slugs")
        val items = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optInt("id", 0)
                if (id <= 0) continue
                val slug = slugs?.optString(i)?.takeIf { it.isNotBlank() }
                    ?: item.optString("slug").takeIf { it.isNotBlank() }
                    ?: "video"
                val path = if (slug.startsWith("/movie/")) slug else "/movie/$slug-$id"
                val name = item.optString("name").ifBlank { "Video $id" }
                val episode = item.optInt("episode", 0)
                val fullName = item.optString("fullName").takeIf { it.isNotBlank() }
                    ?: if (episode > 0) "$name - %02d".format(episode) else name
                val poster = posterUrl(item, id)
                add(
                    SAnime.create().apply {
                        this.url = path
                        title = fullName
                        thumbnail_url = poster
                    },
                )
            }
        }.distinctBy { it.url }
        val hasNext = !wrapper.optString("next_page_url").isNullOrBlank()
        return AnimesPage(items, hasNext)
    }

    private fun posterUrl(item: JSONObject, id: Int): String? {
        val poster = item.opt("poster")
        val path = when (poster) {
            is String -> poster.takeIf { it.contains("/") }
            is Number -> {
                val frame = (poster.toInt() + 4) / 5
                "$frame.jpg"
            }
            else -> null
        } ?: return null
        return "$baseUrl/assets/screens/poster/$id/$path"
    }

    private fun postJson(url: String, body: String, auth: Csrf): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "application/json")
            .header("X-XSRF-TOKEN", auth.token)
            .header("Cookie", auth.cookie)
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/")
            .build()
        val text = runCatching {
            client.newCall(request).execute().use { it.body?.string() }
        }.getOrNull() ?: return null
        if (text.isBlank()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun bootstrapCsrf(): Csrf? = synchronized(this) {
        csrf?.let { return it }
        val result = runCatching {
            client.newCall(GET("$baseUrl/new-movies", headers)).execute().use { response ->
                val cookies = response.headers("Set-Cookie")
                    .mapNotNull { it.substringBefore(";").takeIf { c -> c.contains("=") } }
                val token = cookies.firstOrNull { it.startsWith("XSRF-TOKEN=") }
                    ?.substringAfter("XSRF-TOKEN=")
                    ?.let { URLDecoder.decode(it, "UTF-8") }
                    ?: return@use null
                Csrf(token, cookies.joinToString("; "))
            }
        }.getOrNull()
        csrf = result
        result
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val html = response.asJsoup().html()
        val video = vueJson(html, ":video=") ?: return SAnime.create()
        val id = video.optInt("id", 0)
        val name = video.optString("name")
        val fullName = video.optString("fullName").takeIf { it.isNotBlank() } ?: name
        val poster = video.optString("poster").takeIf { it.contains("/") }
        val tags = video.optJSONArray("tags")
        val genre = buildList {
            if (tags != null) {
                for (i in 0 until tags.length()) {
                    tags.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }.distinct().joinToString(", ")
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = fullName.ifBlank { name }
            thumbnail_url = poster?.let { "$baseUrl/assets/screens/poster/$it" }
            this.genre = genre
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
        val sources = vueJsonArray(html, ":video_source=") ?: return emptyList()
        return buildList {
            for (i in 0 until sources.length()) {
                val url = sources.optString(i)
                if (!url.startsWith("http")) continue
                val label = Regex("""/(\d{3,4})p\.mp4""").find(url)?.groupValues?.get(1)
                    ?.let { "${it}p" } ?: "MP4"
                add(MediaVideo.create(url, label, url))
            }
        }.distinctBy { it.url }
    }

    private fun vueJson(html: String, marker: String): JSONObject? {
        val raw = attrJson(html, marker) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun vueJsonArray(html: String, marker: String): JSONArray? {
        val raw = attrJson(html, marker) ?: return null
        return runCatching { JSONArray(raw) }.getOrNull()
    }

    private fun attrJson(html: String, marker: String): String? {
        val regex = Regex(Regex.escape(marker) + "\"([^\"]*)\"")
        val raw = regex.find(html)?.groupValues?.get(1) ?: return null
        return Parser.unescapeEntities(raw, false)
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
