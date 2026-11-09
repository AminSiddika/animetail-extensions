package eu.kanade.tachiyomi.animeextension.en.watchhentai

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
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class WatchHentai : AnimeHttpSource() {

    override val name = "Watch Hentai"
    override val baseUrl = "https://watchhentai.net"
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

        return listOf(Video(mediaUrl, "MP4", mediaUrl, headers = videoHeaders))
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
