package eu.kanade.tachiyomi.animeextension.en.hentaijp

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
import org.json.JSONArray
import java.net.URLEncoder

class HentaiJp : AnimeHttpSource() {

    override val name = "@JalebiBae - Hentai-JP"
    override val id: Long = 2221151590253777463L
    override val baseUrl = "https://hentai-jp.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/popular?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/latest?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search?q=$encoded&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div.grid > a.card")
            .mapNotNull { card ->
                val href = card.absUrl("href").ifBlank { card.attr("href") }
                if (!href.contains("/video/")) return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = card.attr("title").ifBlank { card.selectFirst("h3")?.text().orEmpty() }.trim()
                    thumbnail_url = card.selectFirst(".thumb img")?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val current = Regex("[?&]page=(\\d+)").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val maxPage = doc.select("nav.pager a[href]")
            .mapNotNull { Regex("[?&]page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: current
        return AnimesPage(items, maxPage > current)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val category = doc.selectFirst(".vi-cat a")?.text()?.trim().orEmpty()
        val tags = doc.select(".tags a").map { it.text().trim().removePrefix("#") }
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("#player")?.attr("data-poster")
            genre = (listOf(category) + tags)
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            description = listOf(category, tags.joinToString(", "))
                .filter { it.isNotEmpty() }
                .joinToString(" • ")
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
        val episodeUrl = response.request.url.toString()
        val doc = response.asJsoup()
        val raw = doc.selectFirst("script#serverData")?.data()
            ?: Regex("""<script[^>]*id="serverData"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(doc.html())?.groupValues?.get(1)
            ?: return emptyList()
        val servers = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()

        return buildList {
            for (i in 0 until servers.length()) {
                val server = servers.optJSONObject(i) ?: continue
                if (!server.optString("t").equals("iframe", true)) continue
                val url = server.optString("u")
                if (!url.startsWith("http")) continue
                addAll(resolveEmbed(url, episodeUrl))
            }
        }.distinctBy { it.url }
    }

    private fun resolveEmbed(embedUrl: String, episodeUrl: String): List<Video> {
        val html = fetchText(embedUrl, episodeUrl) ?: return emptyList()
        return when {
            embedUrl.contains("doodstream", true) || embedUrl.contains("doodst.", true) -> {
                resolveDood(html, embedUrl)
            }
            embedUrl.contains("playmogo", true) -> {
                resolvePlaymogo(html, embedUrl)
            }
            else -> emptyList()
        }
    }

    private fun resolveDood(html: String, referer: String): List<Video> {
        val block = Regex("""sources:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""\{[^{}]*?"file"\s*:\s*"(.*?)"[^{}]*?}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(block)
            .mapNotNull { match ->
                val obj = match.value
                val raw = match.groupValues[1]
                val label = Regex(""""label"\s*:\s*"([^"]*)"""")
                    .find(obj)?.groupValues?.get(1).orEmpty().ifBlank { "MP4" }
                val url = doodUrl(raw) ?: return@mapNotNull null
                val headers = Headers.Builder().add("Referer", referer).build()
                MediaVideo.create(url, label, url, headers = headers)
            }
            .toList()
    }

    private fun doodUrl(raw: String): String? {
        var url = raw.replace("\\/", "/")
        if (url.startsWith("//")) url = "https:$url"
        if (!url.startsWith("http")) return null
        return url.replace(" ", "%20")
    }

    private fun resolvePlaymogo(html: String, referer: String): List<Video> {
        val pass = Regex("""pass_md5/([^'"]+)""").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val token = Regex("""token=([A-Za-z0-9]+)""").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val base = fetchText(
            "https://playmogo.com/pass_md5/$pass",
            "https://playmogo.com/",
        ) ?: return emptyList()
        if (!base.startsWith("http")) return emptyList()
        val suffix = (1..10).map { CHARS.random() }.joinToString("")
        val url = base.trim() + suffix + "?token=$token&expiry=" + System.currentTimeMillis()
        val headers = Headers.Builder().add("Referer", "https://playmogo.com/").build()
        return listOf(MediaVideo.create(url, "MP4", url, headers = headers))
    }

    private fun fetchText(
        url: String,
        referer: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
        referer?.let { builder.header("Referer", it) }
        extraHeaders.forEach { (key, value) -> builder.header(key, value) }
        client.newCall(builder.build()).execute().use { it.body?.string() }
    }.getOrNull()

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
