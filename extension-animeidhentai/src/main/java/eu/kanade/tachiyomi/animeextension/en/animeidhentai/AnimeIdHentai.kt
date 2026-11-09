package eu.kanade.tachiyomi.animeextension.en.animeidhentai

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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.security.MessageDigest

class AnimeIdHentai : AnimeHttpSource() {

    override val name = "@JalebiBae - AnimeIDHentai"
    override val id: Long = 3638446808547185958L
    override val baseUrl = "https://animeidhentai.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/trending", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseEpisodes(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseEpisodes(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search?q=$encoded", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseEpisodes(response)

    private fun parseEpisodes(response: Response): AnimesPage {
        val html = response.body?.string().orEmpty()
        val items = EPISODE.findAll(html)
            .map { match ->
                val title = match.groupValues[1]
                val slug = match.groupValues[2]
                val episode = match.groupValues[3]
                val cover = match.groupValues[4]

                SAnime.create().apply {
                    url = "/series/$slug/episode-$episode"
                    this.title = "$title Episode $episode"
                    thumbnail_url = if (cover.startsWith("http")) cover else "$baseUrl$cover"
                }
            }
            .distinctBy { it.url }
            .toList()

        return AnimesPage(items, false)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" | ")
                ?: doc.title().substringBefore(" | ")
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href*='/genre/']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("meta[name=description]")?.attr("content")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(url)
                name = "Full Video"
                episode_number = 1f
                date_upload = 0L
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> =
        resolveNhPlayer(response.request.url.toString())

    // ------------------------------------------------------------------
    // nhplayer.com resolver: PoW + DOM challenge parts + browser fingerprint
    // ------------------------------------------------------------------

    private fun resolveNhPlayer(episodeUrl: String): List<Video> {
        val episodeDoc = fetchDoc(episodeUrl, "$baseUrl/") ?: return emptyList()
        val embed = EMBED.find(episodeDoc.html())?.value ?: return emptyList()
        val embedUrl = embed.trimEnd('/') + "/"

        val embedDoc = fetchDoc(embedUrl, episodeUrl) ?: return emptyList()
        val dataId = embedDoc.selectFirst("li[data-id]")?.attr("data-id") ?: return emptyList()
        val playerUrl = embedUrl.toHttpUrlOrNull()?.resolve(dataId)?.toString() ?: return emptyList()

        val playerDoc = fetchDoc(playerUrl, embedUrl) ?: return emptyList()
        val html = playerDoc.html()
        val pv = PV.findAll(html).associate { it.groupValues[1] to it.groupValues[2] }
        val vid = pv["vid"] ?: return emptyList()
        val ct = pv["ct"].orEmpty()
        val pid = pv["pid"].orEmpty()
        val st = pv["st"].orEmpty()

        val coreSrc = playerDoc.selectFirst("script[src*='player-core-v2.php']")?.absUrl("src")
            ?: return emptyList()
        val core = fetchText(coreSrc, playerUrl) ?: return emptyList()

        val sc = SC.find(core)?.groupValues?.get(1) ?: return emptyList()
        val rid = RID.find(core)?.groupValues?.get(1) ?: return emptyList()
        val ids = IDS.findAll(core).map { it.groupValues[1] }.toList()
        val attrs = ATTRS.findAll(core).map { it.groupValues[1] }.toList()
        if (ids.size < 5 || attrs.size < 3) return emptyList()

        val e1 = tagById(html, ids[0])
        val e2 = tagById(html, ids[1])
        val e3 = tagById(html, ids[2])
        val e5 = tagById(html, ids[4])

        val p1 = attrOf(e1, attrs[0])
        val p2 = attrOf(e2, "value")
        val p3 = attrOf(e3, attrs[1])
        val ts = attrOf(e5, attrs[2])

        val templateStart = html.indexOf("id=\"${ids[3]}\"")
        val p4 = if (templateStart >= 0) {
            Regex("<p>([^<]+)</p>")
                .find(html.substring(templateStart, minOf(html.length, templateStart + 400)))
                ?.groupValues?.get(1).orEmpty()
        } else {
            ""
        }
        if (p1.isEmpty() || p2.isEmpty() || p3.isEmpty() || p4.isEmpty() || ts.isEmpty()) {
            return emptyList()
        }

        val challenge = p1 + p2 + p3 + p4 + ts
        val digest = MessageDigest.getInstance("SHA-256")
        var nonce = 0L
        while (nonce < 10_000_000L) {
            val hash = digest.digest((challenge + nonce.toString(16)).toByteArray(Charsets.UTF_8))
            if (hash[0].toInt() == 0) break
            nonce++
        }

        val query = listOf(
            "vid" to vid,
            "c" to ct,
            "p1" to p1,
            "p2" to p2,
            "p3" to p3,
            "p4" to p4,
            "t" to ts,
            "sc" to sc,
            "rid" to rid,
            "fp" to fingerprint(),
            "df" to "",
            "pow" to nonce.toString(16),
            "pid" to pid,
            "st" to st,
        ).joinToString("&") { (key, value) ->
            "$key=" + URLEncoder.encode(value, "UTF-8")
        }

        val body = fetchText(
            "https://nhplayer.com/get-video-url-v2.php?$query",
            playerUrl,
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to "https://nhplayer.com",
                "Accept" to "*/*",
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty",
            ),
        ) ?: return emptyList()

        val url = runCatching { JSONObject(body).optString("url") }.getOrDefault("")
        if (!url.startsWith("http")) return emptyList()
        return listOf(Video(url, "HD", url))
    }

    private fun fingerprint(): String {
        val moves = JSONArray()
        for (i in 0 until 20) {
            moves.put(JSONArray().put(100 + i * 40).put(200 + i * 10).put(300 + i * 40))
        }
        val browser = JSONObject()
            .put("sw", 1920).put("sh", 1080).put("aw", 1920).put("ah", 1040)
            .put("cd", 24).put("pd", 24).put("tz", 0).put("hc", 8).put("dm", 8)
            .put("pl", "Win32").put("lang", "en-US").put("langs", "en-US,en")
            .put("dpr", 1).put("ww", 1920).put("wh", 950)
            .put("touch", false).put("pdf", true).put("fonts", 30)
        val data = JSONObject()
            .put("t", 2400)
            .put("mm", moves)
            .put("tm", JSONArray())
            .put("cl", JSONArray().put(JSONArray().put(900).put(400).put(1800)))
            .put("kp", JSONArray())
            .put("sc", JSONArray().put(JSONArray().put(0).put(0).put(1000)))
            .put("i", 1).put("mc", 20).put("tc", 0).put("cc", 1).put("kc", 0)
            .put("b", browser)
        return Base64.encodeToString(data.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun fetchDoc(url: String, referer: String?): Document? = runCatching {
        client.newCall(request(url, referer)).execute().use { it.asJsoup() }
    }.getOrNull()

    private fun fetchText(
        url: String,
        referer: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String? = runCatching {
        client.newCall(request(url, referer, extraHeaders)).execute().use { it.body?.string() }
    }.getOrNull()

    private fun request(
        url: String,
        referer: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
        referer?.let { builder.header("Referer", it) }
        extraHeaders.forEach { (key, value) -> builder.header(key, value) }
        return builder.build()
    }

    private fun tagById(html: String, id: String): String =
        Regex("id=\"$id\"[^>]*").find(html)?.value.orEmpty()

    private fun attrOf(tag: String, name: String): String =
        Regex("$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1).orEmpty()

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val EMBED = Regex("""https://nhplayer\.com/v/[A-Za-z0-9]+""")
        private val PV = Regex("""\b(vid|ct|pid|st):"([^"]*)"""")
        private val SC = Regex("""var\s+\w+='([0-9a-f]{8}\.[0-9a-f]{16})'""")
        private val RID = Regex("""var\s+\w+='([0-9a-f]{16})'""")
        private val IDS = Regex("""getElementById\('(\w+)'\)""")
        private val ATTRS = Regex("""getAttribute\('(data-\w+)'\)""")
        private val EPISODE = Regex(
            """\"title\":\"([^\"]+)\",\"titleSlug\":\"([^\"]+)\",\"titleId\":\"[^\"]+\",\"ep\":(\d+)[^}]*?\"backdrop\":\"([^\"]+)\"""",
        )
    }
}
