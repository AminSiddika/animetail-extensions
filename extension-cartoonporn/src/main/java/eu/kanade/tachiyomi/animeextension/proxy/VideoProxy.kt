package eu.kanade.tachiyomi.animeextension.proxy

import android.util.Base64
import fi.iki.elonen.NanoHTTPD as Httpd
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Local NanoHTTPD proxy used by sources whose media hosts reject the app's
 * User-Agent or require a Referer for every request (including Range requests).
 */
object ProxySupport {
    @Volatile
    private var proxy: VideoProxy? = null

    @Volatile
    private var proxyPort: Int = 0

    @Synchronized
    fun url(client: OkHttpClient, target: String, referer: String): String {
        val port = runCatching {
            if (proxy == null) {
                VideoProxy(client, referer).also {
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
                return newFixedLengthResponse(Httpd.Response.Status.BAD_REQUEST, MIME_TEXT, "bad target")
            }

            val request = Request.Builder()
                .url(target)
                .header("Referer", referer)
                .header("User-Agent", USER_AGENT)
            session.headers["range"]?.let { request.header("Range", it) }

            val upstream = runCatching {
                client.newCall(request.build()).execute()
            }.getOrNull() ?: return newFixedLengthResponse(
                Httpd.Response.Status.INTERNAL_ERROR, MIME_TEXT, "upstream error",
            )

            val body = upstream.body
            if (body == null || upstream.code !in 200..299) {
                val code = upstream.code
                val range = upstream.header("Content-Range")
                upstream.close()
                val response = newFixedLengthResponse(
                    if (code == 416) Httpd.Response.Status.RANGE_NOT_SATISFIABLE
                    else Httpd.Response.Status.INTERNAL_ERROR,
                    MIME_TEXT,
                    "",
                )
                range?.let { response.addHeader("Content-Range", it) }
                return response
            }

            val status = if (upstream.code == 206) Httpd.Response.Status.PARTIAL_CONTENT
            else Httpd.Response.Status.OK
            val mime = upstream.header("Content-Type") ?: "video/mp4"
            val length = body.contentLength()
            val response = if (length >= 0) {
                newFixedLengthResponse(
                    status, mime, ClosingStream(body.byteStream(), upstream), length,
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
                try { super.close() } finally { closeable.close() }
            }
        }

        private companion object {
            const val MIME_TEXT = "text/plain"
            const val USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        }
    }
}
