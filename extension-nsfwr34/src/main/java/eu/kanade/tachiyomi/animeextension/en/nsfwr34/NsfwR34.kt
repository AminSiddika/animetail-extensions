package eu.kanade.tachiyomi.animeextension.en.nsfwr34

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
import org.json.JSONObject
import java.net.URLEncoder

class NsfwR34 : AnimeHttpSource() {

    override val name = "@JalebiBae - NSFWR34"
    override val id: Long = 2124152996770265631L
    override val baseUrl = "https://nsfwr34.com"
    init {
        MediaVideo.referer = "$baseUrl/"
    }
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(listUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(listUrl(page), headers)

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

    private fun listUrl(page: Int): String =
        if (page <= 1) "$baseUrl/video/" else "$baseUrl/video/page/$page/"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div.post-item article")
            .mapNotNull { article ->
                val link = article.selectFirst("a.post-permalink") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!href.contains("/video/")) return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = link.attr("title").ifBlank { link.text() }.trim()
                    thumbnail_url = article.selectFirst("img")?.let { img ->
                        img.absUrl("src").ifBlank { img.absUrl("data-src") }
                    }
                }
            }
            .distinctBy { it.url }

        val current = pageFromUrl(response.request.url.toString())
        val hasNext = doc.select("a[href]").any { a ->
            val href = a.attr("href")
            href.contains("/page/${current + 1}/")
        }
        return AnimesPage(items, hasNext)
    }

    private fun pageFromUrl(url: String): Int =
        Regex("/page/(\\d+)/").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href*='/categories/']")
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
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = "Full Video"
                episode_number = 1f
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val settings = doc.selectFirst("video-js[data-settings]")?.attr("data-settings")
            ?: return emptyList()
        val sources = runCatching { JSONObject(settings).optJSONArray("sources") }
            .getOrNull() ?: return emptyList()

        return buildList {
            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                val url = source.optString("src")
                if (!url.startsWith("http")) continue
                val type = source.optString("type")
                val label = when {
                    type.contains("mpegurl", true) -> "HLS"
                    type.contains("mp4", true) -> "MP4"
                    else -> "Video"
                }
                add(MediaVideo.create(url, label, url))
            }
        }.distinctBy { it.url }
    }
}
