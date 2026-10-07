package io.github.ytdlpandroid

import android.widget.ImageView
import coil3.load
import coil3.request.error
import coil3.request.placeholder
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val MAX_THUMBNAIL_BYTES = 5L * 1024 * 1024
private const val THUMBNAIL_TIMEOUT_SECONDS = 4L

/**
 * The single Coil entry point for remote artwork (Requirements 6.5 and 6.7).
 *
 * The trust check runs before Coil sees the URL, so an untrusted host resolves to the
 * placeholder without a network request ever being made. Funneling every call site
 * through here is what keeps Requirement 6.7 enforceable in one place.
 */
internal fun ImageView.loadTrustedThumbnail(url: String?) {
    val trusted = trustedThumbnailUrl(url)
    if (trusted == null) {
        setImageResource(android.R.drawable.ic_media_play)
        return
    }
    load(trusted) {
        placeholder(android.R.drawable.ic_media_play)
        error(android.R.drawable.ic_media_play)
    }
}

/**
 * OkHttp call factory used for all thumbnail fetching. Redirects stay disabled to match
 * the behaviour of the hand-rolled loader this replaces (Requirement 17.12).
 */
internal fun thumbnailCallFactory(): Call.Factory = OkHttpClient.Builder()
    .connectTimeout(THUMBNAIL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(THUMBNAIL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .followRedirects(false)
    .addInterceptor(ThumbnailSizeLimitInterceptor())
    .build()

/**
 * Requirement 6.10: a response over 5 MB is aborted and the placeholder shown.
 *
 * `Content-Length` alone is not enough — YouTube image responses can be chunked — so the
 * body is also wrapped in a counting source that fails once the limit is crossed.
 */
private class ThumbnailSizeLimitInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val declared = response.header("Content-Length")?.toLongOrNull()
        if (declared != null && declared > MAX_THUMBNAIL_BYTES) {
            response.close()
            throw IOException("Thumbnail declared $declared bytes, over the $MAX_THUMBNAIL_BYTES limit")
        }
        val body = response.body ?: return response
        val limited = LimitedSource(body.source(), MAX_THUMBNAIL_BYTES).buffer()
        return response.newBuilder()
            .body(limited.asResponseBody(body.contentType(), body.contentLength()))
            .build()
    }
}

private class LimitedSource(
    private val delegate: Source,
    private val limit: Long,
) : Source {
    private var total = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
        val read = delegate.read(sink, byteCount)
        if (read > 0) {
            total += read
            if (total > limit) throw IOException("Thumbnail response exceeded $limit bytes")
        }
        return read
    }

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() = delegate.close()
}
