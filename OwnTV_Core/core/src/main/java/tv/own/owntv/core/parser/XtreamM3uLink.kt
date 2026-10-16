package tv.own.owntv.core.parser

import java.net.URI
import java.net.URLDecoder

/**
 * An "M3U" link that is really an Xtream account:
 * `http://host:port/get.php?username=U&password=P&type=m3u_plus&output=ts`.
 *
 * Importing this shape as Xtream preserves the provider's real Live/VOD/Series catalogue instead of
 * flattening every entry into Live TV. The parser is deliberately strict and never logs credentials.
 */
data class XtreamM3uLink(
    val server: String,
    val username: String,
    val password: String,
    val preferHls: Boolean,
) {
    companion object {
        fun clean(url: String): String = url.trim().replace("&amp;", "&", ignoreCase = true)

        fun parse(url: String): XtreamM3uLink? {
            val uri = runCatching { URI(clean(url)) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val authority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return null
            val path = uri.rawPath.orEmpty()
            if (!path.endsWith("/get.php", ignoreCase = true)) return null
            val params = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=').takeIf { it.isNotBlank() } ?: return@mapNotNull null
                key.lowercase() to decode(pair.substringAfter('=', ""))
            }.toMap()
            val username = params["username"]?.takeIf { it.isNotBlank() } ?: return null
            val password = params["password"]?.takeIf { it.isNotBlank() } ?: return null
            val output = params["output"]?.lowercase()
            return XtreamM3uLink(
                server = "$scheme://$authority${path.dropLast("/get.php".length)}",
                username = username,
                password = password,
                preferHls = output == "m3u8" || output == "hls",
            )
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
