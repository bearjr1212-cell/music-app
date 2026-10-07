package io.github.ytdlpandroid

import android.net.Uri

private const val YOUTUBE_THUMBNAIL_HOST = "i.ytimg.com"

/**
 * Single implementation of the trusted-thumbnail control.
 *
 * Requirement 6.7 forbids passing any URL that is not an HTTPS YouTube image to the
 * image loader, and Requirement 17.12 requires the check's behaviour to survive the
 * refactor unchanged. It lives at package scope rather than as a `DownloadService`
 * member so that Coil-backed artwork loading and `DownloadService.fetchArtwork`
 * cannot drift into two different predicates.
 */
internal fun trustedThumbnailUrl(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return try {
        val uri = Uri.parse(value)
        value.takeIf {
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals(YOUTUBE_THUMBNAIL_HOST, ignoreCase = true) &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) &&
                uri.fragment == null
        }
    } catch (_: Exception) {
        null
    }
}
