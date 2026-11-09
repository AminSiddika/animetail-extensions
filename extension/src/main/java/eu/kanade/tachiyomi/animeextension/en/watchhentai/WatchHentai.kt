package eu.kanade.tachiyomi.animeextension.en.watchhentai

import eu.kanade.tachiyomi.animeextension.proxy.MediaVideo

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import fi.iki.elonen.NanoHTTPD as Httpd
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class WatchHentai : AnimeHttpSource() {

    override val name = "@JalebiBae - Watch Hentai"
    override val id: Long = 135440030259600165L
    override val baseUrl = "https://watchhentai.net"
    init {
        MediaVideo.referer = "$baseUrl/"
    }
    override val lang = "en"
    override val supportsLatest = true

    // ---------------------------------------------------------------- browse

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/trending/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseSeriesPage(response)

    // ---------------------------------------------------------------- latest

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/series/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseSeriesPage(response)

    // ---------------------------------------------------------------- search

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selectedSlug
        return if (genre != null) {
            GET("$baseUrl/genre/$genre/page/$page/", headers)
        } else {
            val encoded = URLEncoder.encode(query, "UTF-8")
            GET("$baseUrl/?s=$encoded&type=series", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseSeriesPage(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(GenreFilter())

    // --------------------------------------------------------------- details

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("div.tv-single-hero h1")?.text().orEmpty()
            thumbnail_url = doc.selectFirst("div.tv-single-hero div.poster img")?.imageUrl()
            genre = doc.select("div.sgeneros a").joinToString(", ") { it.text() }
            author = doc.select("div.tv-about-field").firstOrNull { field ->
                field.selectFirst("span.tv-about-field__label")
                    ?.text()
                    ?.equals("Studio", ignoreCase = true) == true
            }?.selectFirst("span.tv-about-field__value")?.text()
            description = doc.selectFirst("div.tv-single-synopsis__body")
                ?.select("p")
                ?.joinToString("\n\n") { it.text() }
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    // -------------------------------------------------------------- episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        return doc.select("#episodes a.tv-ep-card-link")
            .mapNotNull { link ->
                val href = link.absUrl("href")
                if (href.isBlank()) return@mapNotNull null

                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    name = link.selectFirst("span.eptitle")?.text()
                        ?: link.attr("title").removeSuffix(" Watch Hentai")
                    date_upload = parseDate(link.selectFirst("span.date")?.text())
                    episode_number = EPISODE_NUMBER
                        .find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                }
            }
            .distinctBy { it.url }
            .reversed()
    }

    // ----------------------------------------------------------------- video

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val page = response.asJsoup()

        val playerUrl = page.selectFirst("[data-primary-player-url]")
            ?.attr("data-primary-player-url")
            ?.takeIf { it.startsWith("http") }
            ?: return emptyList()

        val playerDoc = runCatching {
            client.newCall(
                GET(
                    playerUrl,
                    headers.newBuilder().set("Referer", "$baseUrl/").build(),
                ),
            ).execute().use { it.asJsoup() }
        }.getOrNull() ?: return emptyList()

        val mediaUrl = jsonLdMediaUrl(playerDoc) ?: decodeMediaUrl(playerDoc.html())
        if (mediaUrl.isNullOrBlank()) return emptyList()

        // Serve the mp4 through the in-extension proxy so the Referer/UA are always sent,
        // then also expose the direct link as a fallback.
        val proxied = proxyUrl(mediaUrl)
        val videos = mutableListOf<Video>()
        if (proxied != mediaUrl) {
            videos += MediaVideo.create(proxied, "Proxy", proxied, headers = videoHeaders)
        }
        videos += MediaVideo.create(mediaUrl, "Direct", mediaUrl, headers = videoHeaders)
        return videos
    }

    // -------------------------------------------------------------- proxy

    /**
     * Starts (once) a local NanoHTTPD server and returns a URL whose path carries the
     * base64url-encoded remote mp4. Every request is replayed upstream with the headers
     * the host needs, including Range requests so seeking works.
     */
    @Synchronized
    private fun proxyUrl(target: String): String {
        val port = runCatching {
            if (proxy == null) {
                VideoProxy(client, "$baseUrl/").also {
                    it.start(5000, true)
                    proxy = it
                    proxyPort = it.listeningPort
                }
            }
            proxyPort
        }.getOrNull() ?: return target

        val encoded = Base64.encodeToString(
            target.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "http://127.0.0.1:$port/v/$encoded"
    }

    private class VideoProxy(
        private val client: OkHttpClient,
        private val referer: String,
    ) : Httpd(0) {

        override fun serve(session: Httpd.IHTTPSession): Httpd.Response {
            val encoded = session.uri.substringAfterLast('/')
            val target = runCatching {
                String(
                    Base64.decode(
                        encoded,
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                    ),
                    Charsets.UTF_8,
                )
            }.getOrNull()

            if (target.isNullOrBlank() || !target.startsWith("http")) {
                return newFixedLengthResponse(
                    Httpd.Response.Status.BAD_REQUEST,
                    MIME_TEXT,
                    "bad target",
                )
            }

            val request = Request.Builder()
                .url(target)
                .header("Referer", referer)
                .header("User-Agent", USER_AGENT)

            session.headers["range"]?.let { request.header("Range", it) }

            val upstream = runCatching {
                client.newCall(request.build()).execute()
            }.getOrNull() ?: return newFixedLengthResponse(
                Httpd.Response.Status.INTERNAL_ERROR,
                MIME_TEXT,
                "upstream error",
            )

            val body = upstream.body
            if (body == null || upstream.code !in 200..299) {
                val code = upstream.code
                val range = upstream.header("Content-Range")
                upstream.close()
                val response = newFixedLengthResponse(
                    if (code == 416) {
                        Httpd.Response.Status.RANGE_NOT_SATISFIABLE
                    } else {
                        Httpd.Response.Status.INTERNAL_ERROR
                    },
                    MIME_TEXT,
                    "",
                )
                range?.let { response.addHeader("Content-Range", it) }
                return response
            }

            val status = if (upstream.code == 206) {
                Httpd.Response.Status.PARTIAL_CONTENT
            } else {
                Httpd.Response.Status.OK
            }
            val mime = upstream.header("Content-Type") ?: "video/mp4"
            val length = body.contentLength()

            val response = if (length >= 0) {
                newFixedLengthResponse(
                    status,
                    mime,
                    ClosingStream(body.byteStream(), upstream),
                    length,
                )
            } else {
                newChunkedResponse(status, mime, ClosingStream(body.byteStream(), upstream))
            }

            upstream.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
            response.addHeader("Accept-Ranges", "bytes")
            return response
        }

        private class ClosingStream(
            input: InputStream,
            private val closeable: Closeable,
        ) : FilterInputStream(input) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    closeable.close()
                }
            }
        }

        private companion object {
            const val MIME_TEXT = "text/plain"
            const val USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        }
    }

    // ---------------------------------------------------------------- helper

    private fun parseSeriesPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("article.item.tvshows")
            .mapNotNull { article ->
                val href = article.selectFirst("div.data h3 a")?.absUrl("href")
                    ?: article.selectFirst("div.poster a[href*='/series/']")?.absUrl("href")
                if (href.isNullOrBlank()) return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = article.selectFirst("div.data h3 a")?.text().orEmpty()
                    thumbnail_url = article.selectFirst("div.poster img")?.imageUrl()
                }
            }
            .distinctBy { it.url }

        return AnimesPage(animes, hasNextPage(doc))
    }

    private fun hasNextPage(doc: Document): Boolean {
        val status = doc.selectFirst(".pagination__status")?.text() ?: return false
        val match = PAGE_STATUS.find(status) ?: return false
        val current = match.groupValues[1].toIntOrNull() ?: return false
        val total = match.groupValues[2].toIntOrNull() ?: return false
        return current < total
    }

    private fun Element.imageUrl(): String? {
        val raw = attr("data-src").ifBlank { attr("src") }
        return raw.takeIf { it.startsWith("http") }
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return runCatching { DATE_FORMAT.parse(text.trim())?.time ?: 0L }.getOrDefault(0L)
    }

    /** The player page embeds a schema.org VideoObject with the direct MP4 URL. */
    private fun jsonLdMediaUrl(doc: Document): String? {
        doc.select("script[type='application/ld+json']").forEach { script ->
            val json = runCatching { JSONObject(script.data()) }.getOrNull() ?: return@forEach
            val url = json.optString("contentUrl")
            if (url.startsWith("http")) return url
        }
        return null
    }

    /** Fallback: the player page also carries a base64/XOR-obfuscated JW Player source. */
    private fun decodeMediaUrl(html: String): String? {
        val encoded = OBFUSCATED_SOURCE.find(html)?.groupValues?.get(1) ?: return null
        return runCatching { deobfuscate(encoded) }.getOrNull()
    }

    private fun deobfuscate(input: String): String {
        var encoded = input.replace('-', '+').replace('_', '/')
        while (encoded.length % 4 != 0) encoded += "="

        val raw = Base64.decode(encoded, Base64.DEFAULT)
        val chars = CharArray(raw.size) { i ->
            ((raw[i].toInt() and 0xFF) xor ((13 + i % 17) and 0xFF)).toChar()
        }
        val reversed = chars.concatToString().reversed()
        val decoded = Base64.decode(reversed, Base64.DEFAULT).toString(Charsets.UTF_8)

        return ASCII_URL.find(decoded)?.value.orEmpty()
    }

    private val videoHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
    }

    private class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        arrayOf("All") + GENRES.map { it.first }.toTypedArray(),
    ) {
        val selectedSlug: String?
            get() = GENRES.getOrNull(state - 1)?.second
    }

    companion object {
        @Volatile
        private var proxy: VideoProxy? = null

        @Volatile
        private var proxyPort: Int = 0

        private val DATE_FORMAT = SimpleDateFormat("MMM. dd, yyyy", Locale.ENGLISH)
        private val EPISODE_NUMBER = Regex("""Episode\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val PAGE_STATUS = Regex("""Page\s+(\d+)\s+of\s+(\d+)""")
        private val OBFUSCATED_SOURCE = Regex("\"file\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"")
        private val ASCII_URL = Regex("""https?://[\x21-\x7E]+""")

        private val GENRES = listOf(
            "Ahegao" to "ahegao",
            "Anal" to "anal",
            "Blackmail" to "blackmail",
            "Blowjob" to "blowjob",
            "Bondage" to "bondage",
            "Creampie" to "creampie",
            "Cosplay" to "cosplay",
            "Dubbed" to "dubbed",
            "Dark Skin" to "dark-skin",
            "DeepThroat" to "deepthroat",
            "Femdom" to "femdom",
            "Harem" to "harem",
            "Horny Slut" to "horny-slut",
            "Incest" to "incest",
            "Large Breasts" to "large-breasts",
            "NTR" to "ntr",
            "Public Sex" to "public-sex",
            "Rape" to "rape",
            "School Girls" to "school-girls",
            "Tits Fuck" to "tits-fuck",
            "Uncensored" to "uncensored",
            "Vanilla" to "vanilla-id-1",
            "Virgins" to "virgins",
            "X-Ray" to "x-ray",
            "Yuri" to "yuri",
            "3D" to "3d",
            "Action" to "action",
            "Adventure" to "adventure",
            "Animal Ears" to "animal-ears",
            "Animation" to "animation",
            "BDSM" to "bdsm",
            "Beastiality" to "beastiality",
            "Big Boobs" to "big-boobs",
            "Brainwashed" to "brainwashed",
            "Bukakke" to "bukakke",
            "Cat Girl" to "cat-girl",
            "Censored" to "censored",
            "Comedy" to "comedy",
            "Demons" to "demons",
            "Doctor" to "doctor",
            "Double Penatration" to "double-penatration",
            "Drama" to "drama",
            "Ecchi" to "ecchi",
            "Elf" to "elf",
            "Eroge" to "eroge",
            "Facesitting" to "facesitting",
            "Facial" to "facial",
            "Family" to "family",
            "Fantasy" to "fantasy",
            "Female Doctor" to "female-doctor",
            "Female Teacher" to "female-teacher",
            "Footjob" to "footjob",
            "Futanari" to "futanari",
            "Gangbang" to "gangbang",
            "Gore" to "gore",
            "Gyaru" to "gyaru",
            "Historical" to "historical",
            "Housewife" to "housewife",
            "Humiliation" to "humiliation",
            "Inflation" to "inflation",
            "Internal Cumshot" to "internal-cumshot",
            "Lactation" to "lactation",
            "Magical Girls" to "magical-girls",
            "Maid" to "maid",
            "Martial Arts" to "martial-arts",
            "Megane" to "megane",
            "MILF" to "milf",
            "Mind Break" to "mind-break",
            "Molestation" to "molestation",
            "Nuns" to "nuns",
            "Nurses" to "nurses",
            "Office Ladies" to "office-ladies",
            "Police" to "police",
            "POV" to "pov",
            "Pregnant" to "pregnant",
            "Princess" to "princess",
            "Rim Job" to "rim-job",
            "Romance" to "romance",
            "Scat" to "scat",
            "Sci-Fi" to "sci-fi",
            "Shimapan" to "shimapan",
            "Short" to "short",
            "Slaves" to "slaves",
            "Sports" to "sports",
            "Squirting" to "squirting",
            "Stocking" to "stocking",
            "Strap-on" to "strap-on",
            "Strapped On" to "strapped-on",
            "Succubus" to "succubus",
            "Super Power" to "super-power",
            "Supernatural" to "supernatural",
            "Swimsuit" to "swimsuit",
            "Tentacles" to "tentacles",
            "Three Some" to "three-some",
            "Torture" to "torture",
            "Toys" to "toys",
            "Train Molestation" to "train-molestation",
            "Tsundere" to "tsundere",
            "Urination" to "urination",
            "Vampire" to "vampire",
            "Vanilla" to "vanilla",
            "Widow" to "widow",
            "Yaoi" to "yaoi",
            "Reality" to "reality",
            "Soap" to "soap",
            "Upcoming" to "upcoming",
        )
    }
}
