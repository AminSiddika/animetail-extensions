package eu.kanade.tachiyomi.animeextension.en.rule34paheal

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
import java.net.URLEncoder

class Rule34Paheal : AnimeHttpSource() {

    override val name = "@JalebiBae - Rule34 Paheal"
    override val id: Long = 1691139563712467096L
    override val baseUrl = "https://rule34.paheal.net"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val tags = query.trim().replace(" ", "_").replace("+", "_")
        val encoded = URLEncoder.encode(tags, "UTF-8").replace("+", "_")
        val url = if (page <= 1) {
            "$baseUrl/post/list/$encoded/1"
        } else {
            "$baseUrl/post/list/$encoded/$page"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseList(response)

    private fun listUrl(page: Int): String =
        "$baseUrl/post/list/animated/$page"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div.shm-thumb")
            .mapNotNull { thumb ->
                if (!thumb.attr("data-mime").startsWith("video/")) return@mapNotNull null
                val link = thumb.selectFirst("a.shm-thumb-link[href]")
                    ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!VIDEO_URL.matches(href)) return@mapNotNull null
                val img = thumb.selectFirst("img")
                val tags = thumb.attr("data-tags").replace('_', ' ').trim()
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = tags.ifBlank {
                        img?.attr("alt")?.substringBefore(" //")?.trim().orEmpty()
                    }
                    thumbnail_url = img?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val current = Regex("/post/list/[^/]+/(\\d+)").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("a[href]").any {
            it.attr("href").contains("/${current + 1}")
        }
        return AnimesPage(items, hasNext)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val title = doc.title()
            .removePrefix("Post ")
            .substringAfter(": ", "")
            .trim()
        return SAnime.create().apply {
            this.title = title.ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
            thumbnail_url = doc.selectFirst("video[poster]")?.attr("poster")
            genre = doc.select("a[href^='/post/list/']")
                .map { it.text().replace('_', ' ').trim() }
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
        val urls = mutableListOf<String>()
        doc.select("video source[src]").forEach { source ->
            val url = source.absUrl("src").ifBlank { source.attr("src") }
            if (url.startsWith("http")) urls.add(url)
        }
        doc.select("video[src]").forEach { video ->
            val url = video.absUrl("src").ifBlank { video.attr("src") }
            if (url.startsWith("http")) urls.add(url)
        }
        val videoHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        return urls.distinct().map { url ->
            proxyMediaVideo.create(url, if (url.contains(".webm")) "WEBM" else "MP4")
        }
    }

    private fun proxyMediaVideo.create(url: String, label: String): Video {
        val videoHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            )
            .build()
        return MediaVideo.create(url, label, url, videoHeaders)
    }

    companion object {
        private val VIDEO_URL = Regex("""^https://rule34\.paheal\.net/post/view/\d+/?$""")
    }
}
