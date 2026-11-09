package eu.kanade.tachiyomi.animeextension.proxy

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers

/**
 * Global media wrapper. Every playback URL is routed through the Cloudflare
 * Worker, which supplies a browser User-Agent/Referer, supports Range and
 * rewrites HLS playlists/segments.
 */
object MediaVideo {
    private const val WORKER = "https://animextension.mobiusdie.workers.dev"
    private const val TOKEN = "animetail-2026"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    fun create(
        url: String,
        quality: String,
        videoUrl: String? = url,
        headers: Headers? = null,
    ): Video {
        val target = videoUrl ?: url
        val referer = headers?.get("Referer") ?: originOf(target)
        val builder = headers?.newBuilder() ?: Headers.Builder()
        builder.set("User-Agent", UA)

        // DASH manifests are not rewritten by the Worker; leave them direct.
        if (target.contains(".mpd", ignoreCase = true)) {
            return Video(url, quality, videoUrl, builder.build())
        }

        val ext = when {
            target.contains(".m3u8", ignoreCase = true) -> "m3u8"
            target.contains("/hls/", ignoreCase = true) -> "m3u8"
            target.contains(".webm", ignoreCase = true) -> "webm"
            else -> "mp4"
        }
        val proxied =
            "$WORKER/api/proxy.$ext?token=$TOKEN&u=${b64(target)}&r=${b64(referer)}"
        return Video(proxied, quality, proxied, builder.build())
    }

    private fun b64(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun originOf(url: String): String {
        val scheme = url.substringBefore("://", "")
        if (scheme != "http" && scheme != "https") return ""
        val host = url.substringAfter("://").substringBefore("/")
        return "$scheme://$host/"
    }
}
