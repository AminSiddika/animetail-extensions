package eu.kanade.tachiyomi.animeextension.en.erotichentai

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
import org.json.JSONArray
import java.net.URLEncoder

class EroticHentai : AnimeHttpSource() {

    override val name = "@JalebiBae - EroticHentai"
    override val id: Long = 2582277769739202595L
    override val baseUrl = "https://erotic-hentai.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(pageUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun pageUrl(page: Int): String =
        if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("article.loop-video.thumb-block")
            .mapNotNull { card ->
                val link = card.selectFirst("a[href]") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = card.selectFirst("header.entry-header span")?.text()?.trim()
                        ?: link.attr("title").trim()
                    thumbnail_url = card.selectFirst("img.video-main-thumb")?.absUrl("src")
                        ?: card.attr("data-main-thumb").takeIf { it.startsWith("http") }
                }
            }
            .distinctBy { it.url }

        val current = Regex("/page/(\\d+)/").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("div.pagination a[href]").any {
            it.attr("href").contains("/page/${current + 1}/")
        }
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("div.tags-list a[href*='/category/'], div.tags-list a[href*='/tag/']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("div.video-description div.desc")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
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
        val embed = doc.select("iframe[src]")
            .mapNotNull { it.absUrl("src").ifBlank { it.attr("src") } }
            .firstOrNull { EMBED_ID.containsMatchIn(it) && !it.contains("adtng", true) }
            ?: return emptyList()
        val id = EMBED_ID.find(embed)?.groupValues?.get(1) ?: return emptyList()

        val api = "https://aniporn.com/api/videofile.php?video_id=$id&lifetime=864000"
        val body = runCatching {
            client.newCall(
                Request.Builder()
                    .url(api)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", "https://aniporn.com/embed/$id/")
                    .build(),
            ).execute().use { it.body?.string() }
        }.getOrNull() ?: return emptyList()

        val files = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until files.length()) {
                val item = files.optJSONObject(i) ?: continue
                val raw = item.optString("video_url")
                if (raw.isBlank()) continue
                val path = base164(raw)
                if (path.isBlank()) continue
                val url = if (path.startsWith("http")) path else "https://aniporn.com$path"
                val label = if (i == 0) "MP4" else "MP4 ${i + 1}"
                add(MediaVideo.create(url, label, url))
            }
        }.distinctBy { it.url }
    }

    // aniporn.com's base164 alphabet mixes Cyrillic А В С Е М with Latin letters.
    private fun base164(encoded: String): String {
        val filtered = encoded.filter { ALPHABET.indexOf(it) >= 0 }
        val out = StringBuilder()
        var r = 0
        while (r < filtered.length) {
            val idx = IntArray(4) {
                val c = if (r < filtered.length) filtered[r++] else '~'
                ALPHABET.indexOf(c)
            }
            val a = (idx[0] shl 2) or (idx[1] shr 4)
            val b = ((idx[1] and 15) shl 4) or (idx[2] shr 2)
            val c = ((idx[2] and 3) shl 6) or idx[3]
            out.append((a and 255).toChar())
            if (idx[2] != 64) out.append((b and 255).toChar())
            if (idx[3] != 64) out.append((c and 255).toChar())
        }
        return unescapePercent(out.toString())
    }

    private fun unescapePercent(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.append(hex.toChar())
                    i += 3
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val ALPHABET =
            "\u0410\u0412\u0421D\u0415FGHIJKL\u041cNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,~"

        private val EMBED_ID = Regex("""/embed/(\d+)""")
    }
}
