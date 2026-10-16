package tv.own.owntv.player

import androidx.media3.common.C
import tv.own.owntv.core.player.TrackLanguages
import java.util.Locale

/** Semantic role metadata supplied by Media3/mpv; shells localize these words at the UI boundary. */
enum class TrackSemanticRole {
    COMMENTARY,
    AUDIO_DESCRIPTION,
    CLOSED_CAPTIONS,
    SDH,
    DUBBED,
    SIGN_LANGUAGE,
    FORCED,
}

/**
 * Presentation shared by TV and mobile for an engine-owned audio/subtitle track.
 *
 * Provider labels are often layout names (`Stereo`) repeated for every language. They are useful
 * metadata, but not a useful *name*. Language therefore wins when it exists; a meaningful label and
 * accessibility roles follow it, and codec/layout/index are brought in only when the list would
 * otherwise contain indistinguishable rows. The engine identity in [TrackOption.label] is never
 * changed — external subtitle selection and timing continue to use that exact raw value.
 */
fun TrackOption.localizedDisplayLabel(
    locale: Locale,
    fallback: String,
    separator: String = " · ",
    roleLabel: (TrackSemanticRole) -> String = { it.name },
    channelCountLabel: (Int) -> String = Int::toString,
): String {
    val raw = label.trim().takeIf(String::isNotEmpty)
    // Labels minted for downloaded/local subtitles carry source + release identity. They were made
    // unique deliberately; rewriting them to a bare language would make two releases look identical.
    if (raw?.isExternalSubtitleIdentity() == true) return raw

    val language = displayLanguage(locale)
    val usefulRaw = raw?.usefulTrackLabel(lang, locale, codec)
    val primary = language ?: usefulRaw ?: fallback
    val qualifiers = ArrayList<String>(5)

    if (language != null && usefulRaw != null && !usefulRaw.equals(language, ignoreCase = true)) {
        qualifiers += usefulRaw
    }

    roleTags(raw).forEach { role ->
        val shown = roleLabel(role)
        if (qualifiers.none { it.contains(shown, ignoreCase = true) } &&
            primary.contains(shown, ignoreCase = true).not()
        ) {
            qualifiers += shown
        }
    }

    val metadataMissing = language == null && usefulRaw == null
    if (metadataMissing || showTechnicalDetails) {
        audioLayout(raw, channelCountLabel)?.let { if (it !in qualifiers) qualifiers += it }
        normalizedCodec()?.let { if (it !in qualifiers && !primary.equals(it, ignoreCase = true)) qualifiers += it }
    }

    if (showOrdinal) qualifiers += "#${displayNumber()}"
    return (listOf(primary) + qualifiers.distinct()).joinToString(separator)
}

/**
 * Mark only genuinely ambiguous siblings for technical detail/index suffixes. This is intentionally
 * a batch operation: a single track cannot know whether another Spanish AAC 2.0 track follows it.
 */
internal fun List<TrackOption>.withTrackDisambiguation(): List<TrackOption> {
    if (size < 2) return this
    val semanticGroups = indices.groupBy { this[it].semanticIdentity() }
    val detail = BooleanArray(size)
    val ordinal = BooleanArray(size)
    semanticGroups.values.filter { it.size > 1 }.forEach { group ->
        group.forEach { detail[it] = true }
        group.groupBy { this[it].technicalIdentity() }.values
            .filter { it.size > 1 }
            .forEach { duplicates -> duplicates.forEach { ordinal[it] = true } }
    }
    return mapIndexed { index, track ->
        track.copy(showTechnicalDetails = detail[index], showOrdinal = ordinal[index])
    }
}

private fun TrackOption.displayLanguage(locale: Locale): String? {
    val code = lang?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) } ?: return null
    val shown = TrackLanguages.displayName(code, locale).trim()
    return shown.takeIf {
        it.isNotEmpty() &&
            !it.equals("und", ignoreCase = true) &&
            !it.equals("unknown", ignoreCase = true)
    }
}

private fun TrackOption.semanticIdentity(): String {
    val locale = Locale.ENGLISH
    val raw = label.trim().takeIf(String::isNotEmpty)
    if (raw?.isExternalSubtitleIdentity() == true) return "external:${raw.lowercase(Locale.ROOT)}"
    val language = lang?.takeUnless { it.isBlank() || it.equals("und", true) }
        ?.let(TrackLanguages::canonicalTag)
        .orEmpty()
    val usefulRaw = raw?.usefulTrackLabel(lang, locale, codec)?.lowercase(Locale.ROOT).orEmpty()
    // A nameless/untagged track already uses a numbered localized fallback, so it cannot collide.
    if (language.isBlank() && usefulRaw.isBlank()) return "fallback:${labelKind.name}:${displayNumber()}"
    return "${labelKind.name}:$language:$usefulRaw:${roleTags(raw).joinToString(",")}"
}

private fun TrackOption.technicalIdentity(): String =
    "${audioLayout(label, Int::toString)}:${normalizedCodec().orEmpty()}:${roleFlags}:${selectionFlags}"

private fun TrackOption.roleTags(rawLabel: String?): List<TrackSemanticRole> = buildList {
    fun addUnlessMentioned(role: TrackSemanticRole, vararg words: String) {
        if (rawLabel == null || words.none { rawLabel.contains(it, ignoreCase = true) }) add(role)
    }
    if (roleFlags and C.ROLE_FLAG_COMMENTARY != 0) addUnlessMentioned(TrackSemanticRole.COMMENTARY, "commentary", "comentario")
    if (roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) addUnlessMentioned(TrackSemanticRole.AUDIO_DESCRIPTION, "audio description", "descriptive", "descripción")
    if (roleFlags and C.ROLE_FLAG_TRANSCRIBES_DIALOG != 0) addUnlessMentioned(TrackSemanticRole.CLOSED_CAPTIONS, "closed caption", "caption")
    if (roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) addUnlessMentioned(TrackSemanticRole.SDH, "sdh")
    if (roleFlags and C.ROLE_FLAG_DUB != 0) addUnlessMentioned(TrackSemanticRole.DUBBED, "dub", "dobl")
    if (roleFlags and C.ROLE_FLAG_SIGN != 0) addUnlessMentioned(TrackSemanticRole.SIGN_LANGUAGE, "sign")
    if (selectionFlags and C.SELECTION_FLAG_FORCED != 0) addUnlessMentioned(TrackSemanticRole.FORCED, "forced", "forzad")
}

private fun TrackOption.audioLayout(
    rawLabel: String?,
    channelCountLabel: (Int) -> String,
): String? {
    if (labelKind != TrackLabelKind.AUDIO) return null
    val count = channelCount?.takeIf { it > 0 } ?: when {
        rawLabel?.contains("stereo", ignoreCase = true) == true -> 2
        rawLabel?.contains("mono", ignoreCase = true) == true -> 1
        else -> null
    }
    return when (count) {
        1 -> "1.0"
        2 -> "2.0"
        6 -> "5.1"
        8 -> "7.1"
        null -> null
        else -> channelCountLabel(count)
    }
}

private fun TrackOption.normalizedCodec(): String? {
    val token = codec?.trim()?.lowercase(Locale.ROOT)?.substringAfterLast('/')?.takeIf(String::isNotEmpty)
        ?: return null
    return when (token.replace("_", "-").removePrefix("codec:")) {
        "aac", "mp4a-latm", "mp4a.40.2", "mp4a.40.5", "mp4a.40.29" -> "AAC"
        "ac3", "ac-3" -> "Dolby Digital"
        "eac3", "e-ac-3" -> "Dolby Digital Plus"
        "eac3-joc", "e-ac-3-joc" -> "Dolby Atmos"
        "truehd", "mlp-fba" -> "Dolby TrueHD"
        "dts", "dca" -> "DTS"
        "dts-hd", "dtshd" -> "DTS-HD"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "flac" -> "FLAC"
        "mp3", "mpeg-l3" -> "MP3"
        "mp2", "mpeg-l2" -> "MP2"
        "subrip", "srt" -> "SRT"
        "ssa", "ass" -> "ASS"
        "webvtt", "vtt" -> "WebVTT"
        "pgs", "pgssub", "hdmv-pgs-subtitle" -> "PGS"
        "dvd-subtitle", "dvdsub", "vobsub" -> "VobSub"
        "dvb-subtitle", "dvbsub", "dvbsubs" -> "DVB"
        else -> token.uppercase(Locale.ROOT).takeIf { it.length <= 18 }
    }
}

private fun String.usefulTrackLabel(languageCode: String?, locale: Locale, codec: String?): String? {
    var candidate = trim()
    languageCode?.takeIf { it.isNotBlank() && !it.equals("und", true) }?.let { code ->
        val canonical = TrackLanguages.canonicalTag(code)
        val ownLocale = Locale.forLanguageTag(TrackLanguages.twoLetter(canonical) ?: canonical)
        val variants = listOf(
            code,
            canonical,
            TrackLanguages.twoLetter(canonical),
            TrackLanguages.displayName(canonical, locale),
            TrackLanguages.displayName(canonical, Locale.ENGLISH),
            TrackLanguages.displayName(canonical, ownLocale),
        ).filterNotNull().filter { it.length > 1 }.distinctBy { it.lowercase(Locale.ROOT) }
        variants.sortedByDescending(String::length).forEach { word ->
            candidate = candidate.replace(Regex("(?i)(?<![\\p{L}])${Regex.escape(word)}(?![\\p{L}])"), " ")
        }
        candidate = candidate.trim(' ', '-', '_', '·', '(', ')', '[', ']', '/', '\\')
    }
    if (candidate.isBlank()) return null
    val simple = candidate.lowercase(Locale.ROOT)
        .replace(Regex("[\\s._-]+"), " ")
        .trim()
    val codecToken = codec?.substringAfterLast('/')?.lowercase(Locale.ROOT)?.replace('_', '-')
    if (simple in GENERIC_LABELS || simple == codecToken) return null
    return candidate
}

private fun String.isExternalSubtitleIdentity(): Boolean = startsWith("OS_") || startsWith("LOCAL_")

private fun TrackOption.displayNumber(): Int = (typeIndex.takeIf { it >= 0 } ?: mpvId.coerceAtLeast(0)) + 1

private val GENERIC_LABELS = setOf(
    "audio", "audio track", "pista de audio", "subtitle", "subtitles", "subtitle track",
    "subtítulo", "subtítulos", "track", "stream", "default", "main", "unknown", "und",
    "stereo", "stereo audio", "estéreo", "audio estéreo", "mono", "mono audio",
    "1.0", "2.0", "5.1", "7.1",
    "aac", "ac3", "ac 3", "eac3", "e ac 3", "dts", "opus", "vorbis", "flac", "mp2", "mp3",
)
