package eu.kanade.tachiyomi.animeextension.en.hanimetv

import android.util.Base64
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
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class HanimeTv : AnimeHttpSource() {

    override val name = "@JalebiBae - hanime.tv"
    override val id: Long = 5358250886824217988L
    override val baseUrl = "https://hanime.tv"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(trendingUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(trendingUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return if (isIndexFresh()) {
            GET("$baseUrl/?q=$encoded", headers)
        } else {
            GET("$SEARCH_URL?search=$encoded", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val query = Regex("[?&](?:search|q)=([^&]*)").find(response.request.url.toString())
            ?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }
            .orEmpty()

        val index = if (isIndexFresh()) {
            indexCache.orEmpty()
        } else {
            val parsed = runCatching {
                parseIndex(response.body?.string().orEmpty())
            }.getOrNull().orEmpty()
            if (parsed.isNotEmpty()) {
                indexCache = parsed
                indexCachedAt = System.currentTimeMillis()
            }
            parsed
        }

        val matches = if (query.isBlank()) {
            index
        } else {
            index.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.searchTitles.contains(query, ignoreCase = true)
            }
        }

        return AnimesPage(matches.take(60).map { it.toSAnime() }, false)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("a[href^='/browse/tags/']")
                .map { it.text().trim() }
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
        val slug = Regex("/videos/hentai/([a-z0-9-]+)")
            .find(response.request.url.toString())
            ?.groupValues?.get(1)
            ?: return emptyList()

        val time = System.currentTimeMillis() / 1000
        val signature = sha256Hex("$time,$SIGNATURE_SALT,$ORIGIN,$SIGNATURE_SUFFIX,$time")
        val token = encryptToken(
            """{"timestamp_unix":$time,"directive":"htv_player_handshake","slug":"$slug"}""",
        )
        val payload = JSONObject().put("token", token).toString()

        val request = Request.Builder()
            .url("$AUTH_BASE/api/v11/handshake")
            .header("User-Agent", DESKTOP_UA)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/videos/hentai/$slug")
            .header("x-signature-version", "web2")
            .header("x-signature", signature)
            .header("x-time", time.toString())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val encrypted = runCatching {
            client.newCall(request).execute().use { handshake ->
                if (handshake.code !in 200..299) return@use null
                handshake.header("x-token")
            }
        }.getOrNull() ?: return emptyList()

        val decrypted = decryptToken(encrypted) ?: return emptyList()
        val sources = runCatching {
            JSONObject(decrypted).optJSONArray("sources")
        }.getOrNull() ?: return emptyList()

        val videoHeaders = headers.newBuilder()
            .set("Referer", "$ORIGIN/")
            .set("Origin", ORIGIN)
            .build()

        val videos = mutableListOf<Video>()
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            if (source.optString("kind") == "promotion") continue
            var url = source.optString("src")
            if (url.isBlank()) continue
            if (url.startsWith("//")) url = "https:$url"
            if (url.startsWith("/")) url = ORIGIN + url
            if (!url.startsWith("http")) continue
            if (!url.contains(".m3u8")) url += ".m3u8"
            val label = source.optString("label").ifBlank { "HLS" }
            videos.add(Video(url, label, url, videoHeaders))
        }
        return videos.distinctBy { it.url }
    }

    private fun trendingUrl(page: Int): String =
        if (page <= 1) "$baseUrl/browse/trending" else "$baseUrl/browse/trending?page=$page"

    private fun parseList(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("a[href^='/videos/hentai/']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!VIDEO_URL.matches(href)) return@mapNotNull null
                val img = link.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                        ?: link.attr("title")
                            .removePrefix("Watch ")
                            .substringBefore(" hentai")
                            .trim()
                    thumbnail_url = img?.absUrl("src")
                }
            }
            .distinctBy { it.url }

        val current = Regex("[?&]page=(\\d+)").find(response.request.url.toString())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = doc.select("a[href]").any {
            it.attr("href").contains("page=${current + 1}")
        }
        return AnimesPage(items, hasNext)
    }

    private fun parseIndex(body: String): List<IndexEntry> {
        if (body.isBlank()) return emptyList()
        val array = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val entries = ArrayList<IndexEntry>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val slug = item.optString("slug").trim()
            if (slug.isBlank()) continue
            val tags = item.optJSONArray("tags")?.let { tagArray ->
                (0 until tagArray.length()).mapNotNull { index ->
                    tagArray.optString(index).takeIf { it.isNotBlank() }
                }
            }.orEmpty()
            entries.add(
                IndexEntry(
                    slug = slug,
                    name = item.optString("name").trim(),
                    searchTitles = item.optString("search_titles"),
                    cover = item.optString("cover_url"),
                    poster = item.optString("poster_url"),
                    description = item.optString("description"),
                    tags = tags,
                ),
            )
        }
        return entries
    }

    private fun isIndexFresh(): Boolean =
        indexCache != null && System.currentTimeMillis() - indexCachedAt < INDEX_TTL_MS

    private fun encryptToken(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val data = encrypted.copyOfRange(0, encrypted.size - 16)
        val tag = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)
        val outer = JSONObject()
            .put("v", 1)
            .put("alg", "AES-256-GCM")
            .put("iv", base64UrlEncode(iv))
            .put("tag", base64UrlEncode(tag))
            .put("data", base64UrlEncode(data))
        return base64UrlEncode(outer.toString().toByteArray(Charsets.UTF_8))
    }

    private fun decryptToken(token: String): String? = runCatching {
        val outer = JSONObject(String(base64UrlDecode(token), Charsets.UTF_8))
        val iv = base64UrlDecode(outer.optString("iv"))
        val tag = base64UrlDecode(outer.optString("tag"))
        val data = base64UrlDecode(outer.optString("data"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        String(cipher.doFinal(data + tag), Charsets.UTF_8)
    }.getOrNull()

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun base64UrlDecode(value: String): ByteArray {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            builder.append("%02x".format(byte.toInt() and 0xff))
        }
        return builder.toString()
    }

    private fun IndexEntry.toSAnime(): SAnime {
        val text = description.replace(Regex("<[^>]+>"), "").trim()
        return SAnime.create().apply {
            setUrlWithoutDomain("$ORIGIN/videos/hentai/$slug")
            title = name
            thumbnail_url = cover.ifBlank { poster }
            description = text
            genre = tags.joinToString(", ")
        }
    }

    private data class IndexEntry(
        val slug: String,
        val name: String,
        val searchTitles: String,
        val cover: String,
        val poster: String,
        val description: String,
        val tags: List<String>,
    )

    companion object {
        private const val AUTH_BASE = "https://auth.hanime.tv"
        private const val ORIGIN = "https://hanime.tv"
        private const val SEARCH_URL = "https://guest.freeanimehentai.net/api/v11/search_hvs"
        private const val SIGNATURE_SALT = "Xkdi29"
        private const val SIGNATURE_SUFFIX = "mn2"
        private const val INDEX_TTL_MS = 30 * 60 * 1000L
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val VIDEO_URL = Regex("""^https://hanime\.tv/videos/hentai/[a-z0-9-]+/?$""")
        private val KEY: ByteArray = MessageDigest.getInstance("SHA-256")
            .digest("htv-insecure-handshake-v1".toByteArray(Charsets.UTF_8))
        private val AAD = "htv-insecure-v1".toByteArray(Charsets.UTF_8)
    }

    @Volatile
    private var indexCache: List<IndexEntry>? = null

    @Volatile
    private var indexCachedAt: Long = 0L
}
