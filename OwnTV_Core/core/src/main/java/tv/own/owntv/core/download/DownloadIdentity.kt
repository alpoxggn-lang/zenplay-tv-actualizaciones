package tv.own.owntv.core.download

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity

/**
 * Stable, non-secret identity for an offline item.
 *
 * Room ids are deliberately absent: catalogue sync is allowed to replace those ids. Provider
 * remote ids survive that replacement; sources without one use a one-way hash of the stable stream
 * command instead. Components are URL-safe Base64 so provider ids containing `:` cannot make an
 * episode look like another key shape.
 */
object DownloadIdentity {
    private const val VERSION = "d1"
    private const val REMOTE = "r."
    private const val HASH = "h."

    fun movie(movie: MovieEntity): String = listOf(
        VERSION,
        movie.sourceId.toString(),
        "MOVIE",
        component(movie.remoteId, movie.streamUrl),
    ).joinToString(":")

    fun episode(series: SeriesEntity, episode: EpisodeEntity): String = listOf(
        VERSION,
        series.sourceId.toString(),
        "EPISODE",
        component(series.remoteId, "${series.name}|${series.year.orEmpty()}"),
        component(
            episode.remoteId,
            "${episode.seasonNumber}|${episode.episodeNumber}|${episode.streamUrl}",
        ),
    ).joinToString(":")

    /** Remote movie id carried by a current-format key, for re-linking after a catalogue refresh. */
    fun movieRemoteId(key: String): String? {
        val p = parts(key, "MOVIE", 4) ?: return null
        return decodeRemote(p[3])
    }

    /** Remote show + episode ids carried by a current-format episode key. */
    fun episodeRemoteIds(key: String): Pair<String, String>? {
        val p = parts(key, "EPISODE", 5) ?: return null
        val show = decodeRemote(p[3]) ?: return null
        val episode = decodeRemote(p[4]) ?: return null
        return show to episode
    }

    fun isLegacy(key: String): Boolean = !key.startsWith("$VERSION:")

    /** Directory isolation prevents equal provider filenames from crossing profiles or playlists. */
    fun scopedDirectory(profileId: Long, sourceId: Long, requested: String): String =
        listOf("Profiles", "p$profileId", "Sources", "s$sourceId", requested)
            .filter { it.isNotBlank() }
            .joinToString("/")

    /**
     * Keeps the human-readable name but gives it a stable identity suffix. This prevents two films
     * called, for example, "Arrival" from pointing at the same physical document.
     */
    fun scopedFileName(requested: String, contentKey: String): String {
        val dot = requested.lastIndexOf('.').takeIf { it > 0 } ?: requested.length
        val stem = requested.substring(0, dot).ifBlank { "media" }
        val extension = requested.substring(dot)
        return "$stem--${sha256(contentKey).take(16)}$extension"
    }

    private fun component(remoteId: String?, fallback: String): String =
        remoteId?.takeIf { it.isNotBlank() }
            ?.let { REMOTE + Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8)) }
            ?: HASH + sha256(fallback)

    private fun decodeRemote(value: String): String? {
        if (!value.startsWith(REMOTE)) return null
        return runCatching {
            String(Base64.getUrlDecoder().decode(value.removePrefix(REMOTE)), StandardCharsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun parts(key: String, type: String, size: Int): List<String>? {
        val p = key.split(':')
        if (p.size != size || p[0] != VERSION || p[2] != type || p[1].toLongOrNull() == null) return null
        return p
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun Int?.orEmpty(): String = this?.toString().orEmpty()
}
