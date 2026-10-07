package io.github.ytdlpandroid

import android.net.Uri

/** Extracts YouTube video ids so search results can be matched against downloaded files. */
object VideoIds {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** yt-dlp names files `Title [<id>] [job-N].ext`; take the bracketed 11-character id. */
    private val FILE_ID = Regex("""\[([A-Za-z0-9_-]{11})]""")

    fun fromUrl(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val candidate = when {
            host == "youtu.be" -> uri.pathSegments.firstOrNull()
            host.endsWith("youtube.com") -> when (uri.pathSegments.firstOrNull()) {
                "watch" -> uri.getQueryParameter("v")
                "shorts", "embed", "live" -> uri.pathSegments.getOrNull(1)
                else -> null
            }
            else -> null
        }
        return candidate?.takeIf { ID.matches(it) }
    }

    fun fromFileName(name: String): String? = FILE_ID.findAll(name)
        .map { it.groupValues[1] }
        .filterNot { it.startsWith("job-") }
        .lastOrNull()
}
