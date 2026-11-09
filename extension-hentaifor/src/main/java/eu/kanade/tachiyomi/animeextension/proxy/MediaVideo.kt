package eu.kanade.tachiyomi.animeextension.proxy

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers

/**
 * Single playback wrapper used by every extension.
 *
 * Playback goes through the app's own HTTP client, so the site's session
 * cookies and Cloudflare clearance are kept (an external proxy cannot do
 * that: the media endpoints of several hosts answer 403 without them).
 *
 * Every video is given a browser User-Agent plus the Referer/Origin of the
 * source site, which is what hotlink-protected media hosts check.
 *
 * [useProxy] is an opt-in escape hatch for a source whose media host blocks
 * the device IP but works from the Cloudflare Worker.
 */
object MediaVideo {
    private const val WORKER = "https://animextension.mobiusdie.workers.dev"
    private const val TOKEN = "animetail-2026"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** Referer used when the caller supplies none. Set from each source's baseUrl. */
    @Volatile
    var referer: String = ""

    /** Route media through the Cloudflare Worker instead of direct playback. */
    @Volatile
    var useProxy: Boolean = false

    fun create(
        url: String,
        quality: String,
        videoUrl: String? = url,
        headers: Headers? = null,
    ): Video {
        val target = videoUrl ?: url
        val ref = headers?.get("Referer")
            ?.takeIf { it.isNotBlank() }
            ?: referer.takeIf { it.isNotBlank() }
            ?: ""

        val builder = headers?.newBuilder() ?: Headers.Builder()
        builder.set("User-Agent", UA)
        if (ref.isNotBlank()) {
            builder.set("Referer", ref)
            originOf(ref)?.let { builder.set("Origin", it) }
        }

        if (!useProxy || target.contains(".mpd", ignoreCase = true)) {
            return Video(url, quality, videoUrl, builder.build())
        }

        val proxyReferer = ref.ifBlank { originOf(target).orEmpty() }
        val proxied =
            "$WORKER/api/proxy?token=$TOKEN&u=${b64(target)}&r=${b64(proxyReferer)}"
        return Video(proxied, quality, proxied, builder.build())
    }

    private fun b64(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun originOf(url: String): String? {
        val scheme = url.substringBefore("://", "")
        if (scheme != "http" && scheme != "https") return null
        val host = url.substringAfter("://").substringBefore("/")
        if (host.isBlank()) return null
        return "$scheme://$host"
    }
}
