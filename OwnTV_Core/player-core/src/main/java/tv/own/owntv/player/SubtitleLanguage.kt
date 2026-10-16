package tv.own.owntv.player

import java.util.Locale

/** Matches ISO-639-1/2 and regional tags (for example eng, en, and en-US). */
internal fun subtitleLanguageMatches(preferred: String, actual: String?): Boolean {
    if (preferred.isBlank() || actual.isNullOrBlank()) return false
    return languageIdentity(preferred) == languageIdentity(actual)
}

private fun languageIdentity(code: String): String {
    val raw = code.trim().replace('_', '-').lowercase(Locale.ROOT)
    val normalized = tv.own.owntv.core.player.TrackLanguages.canonicalTag(raw)
    val locale = Locale.forLanguageTag(normalized)
    return runCatching { locale.isO3Language.lowercase(Locale.ROOT) }
        .getOrNull()
        .takeUnless { it.isNullOrBlank() }
        ?: normalized.substringBefore('-')
}

/**
 * An mpv `alang` / `slang` value for one preferred language: every spelling a track may carry, since
 * mpv compares the text. "deu" → "deu,ger,de". Blank stays blank (no preference).
 */
internal fun mpvLanguageList(code: String): String {
    val t = code.trim().lowercase(Locale.ROOT)
    if (t.isEmpty()) return ""
    // mpv compares raw text, so include the common bibliographic alias alongside canonical and
    // two-letter forms. The display/matching normalisation itself lives in TrackLanguages.
    val b = tv.own.owntv.core.player.TrackLanguages.bibliographicAlias(t)
    return listOfNotNull(t, b, tv.own.owntv.core.player.TrackLanguages.twoLetter(t)).distinct().joinToString(",")
}
