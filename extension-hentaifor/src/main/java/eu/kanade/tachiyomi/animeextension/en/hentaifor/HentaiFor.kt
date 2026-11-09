package eu.kanade.tachiyomi.animeextension.en.hentaifor

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
import org.json.JSONObject
import java.net.URLEncoder

class HentaiFor : AnimeHttpSource() {

    override val name = "@JalebiBae - Hentai-For"
    override val id: Long = 5928761132455073243L
    override val baseUrl = "https://hentai-for.net"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val from = (page - 1) * 10 + 1
        return GET(
            "$baseUrl/index.php?do=search&subaction=search&story=$encoded" +
                "&search_start=$page&result_from=$from&full_search=0",
            headers,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun listUrl(page: Int): String =
        if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div#dle-content div.kino-item")
            .mapNotNull { item ->
                val link = item.selectFirst("a.kino-poster-link[href]")
                    ?: item.selectFirst("a.kino-h[href]")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains(".html")) return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = link.attr("title").ifBlank {
                        item.selectFirst(".kino-title a")?.text().orEmpty()
                    }.trim()
                    thumbnail_url = item.selectFirst(".kino-img img")?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val hasNext = doc.selectFirst("span.pnext a[href]") != null
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("#kino-page h1.kino-h")?.text()?.trim()
                ?: doc.selectFirst("h1.kino-h")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("div.story-poster img")?.absUrl("src")
            genre = doc.select("div.xf-chips[data-xf=janre]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst("div.full-story-content")?.text()?.trim()
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val detailUrl = response.request.url.toString().substringBefore("#")
        val mirrors = parseMirrors(response.asJsoup().html()) ?: return emptyList()
        val items = episodeItems(mirrors) ?: return emptyList()
        return buildList {
            for ((index, ep) in items.withIndex()) {
                val name = ep.optString("sname").ifBlank { "Episode ${index + 1}" }
                val number = EPISODE.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: (index + 1).toFloat()
                add(
                    SEpisode.create().apply {
                        url = detailUrl + "?ep=$index"
                        this.name = name
                        episode_number = number
                    },
                )
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val mirrors = parseMirrors(doc.html()) ?: return emptyList()
        val items = episodeItems(mirrors) ?: return emptyList()
        val index = Regex("[?&]ep=(\\d+)").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val ep = items.getOrNull(index) ?: items.firstOrNull() ?: return emptyList()
        val scode = ep.optString("scode")
        val embed = Regex("""src="([^"]+)"""").find(scode)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.takeIf { it.contains("hentaimo.com/embed") }
            ?: return emptyList()

        val embedHtml = fetchText(embed, "$baseUrl/") ?: return emptyList()
        val marker = embedHtml.indexOf("var config")
        val start = if (marker >= 0) embedHtml.indexOf('{', marker) else -1
        val configRaw = if (start >= 0) extractObject(embedHtml, start) else null
        val config = configRaw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val sources = config.optJSONArray("sources") ?: return emptyList()
        return buildList {
            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                var url = source.optString("src")
                if (url.startsWith("//")) url = "https:$url"
                if (!url.startsWith("http")) continue
                val label = source.optString("label").ifBlank { "HLS" }
                add(MediaVideo.create(url, label, url))
            }
        }.distinctBy { it.url }
    }

    private fun parseMirrors(html: String): JSONObject? {
        val marker = html.indexOf("RalodePlayer.init(")
        if (marker < 0) return null
        val start = html.indexOf('{', marker)
        if (start < 0) return null
        val json = extractObject(html, start) ?: return null
        return runCatching { JSONObject(json) }.getOrNull()
    }

    private fun episodeItems(mirrors: JSONObject): List<JSONObject>? {
        val keys = mirrors.keys().asSequence().toList()
        val gdp = keys.mapNotNull { mirrors.optJSONObject(it) }
            .firstOrNull { it.optString("name").equals("GDP3", true) }
        val mirror = gdp ?: keys.mapNotNull { mirrors.optJSONObject(it) }
            .filter { it.optJSONObject("items") != null }
            .minByOrNull { it.optString("sort").toIntOrNull() ?: Int.MAX_VALUE }
            ?: return null
        val items = mirror.optJSONObject("items") ?: return null
        return items.keys().asSequence()
            .mapNotNull { items.optJSONObject(it) }
            .sortedBy { it.optString("lssort").toIntOrNull() ?: Int.MAX_VALUE }
            .toList()
    }

    private fun extractObject(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun fetchText(url: String, referer: String?): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
        referer?.let { builder.header("Referer", it) }
        client.newCall(builder.build()).execute().use { it.body?.string() }
    }.getOrNull()

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val EPISODE = Regex("""Episode\s+(\d+)""")
    }
}
