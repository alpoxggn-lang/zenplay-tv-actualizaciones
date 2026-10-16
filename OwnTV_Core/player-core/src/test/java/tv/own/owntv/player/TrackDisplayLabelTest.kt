package tv.own.owntv.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TrackDisplayLabelTest {

    private val spanish = Locale.forLanguageTag("es")

    @Test
    fun `language wins over repeated stereo provider label`() {
        val track = audio(label = "Stereo", lang = "spa", codec = "audio/aac", channels = 2)
        assertEquals("Español", track.localizedDisplayLabel(spanish, "Audio 1"))
    }

    @Test
    fun `different languages no longer produce a list of Stereo rows`() {
        val tracks = listOf(
            audio(label = "Stereo", lang = "spa", id = 0),
            audio(label = "Stereo", lang = "eng", id = 1),
            audio(label = "Stereo", lang = "fra", id = 2),
        ).withTrackDisambiguation()

        assertEquals(
            listOf("Español", "Inglés", "Francés"),
            tracks.mapIndexed { index, track -> track.localizedDisplayLabel(spanish, "Audio ${index + 1}") },
        )
    }

    @Test
    fun `subtitle rows use each available language instead of a generic provider label`() {
        val subtitles = listOf(
            subtitle(label = "Subtitle", lang = "spa", id = 0),
            subtitle(label = "Subtitle", lang = "eng", id = 1),
            subtitle(label = "Subtitle", lang = "jpn", id = 2),
        ).withTrackDisambiguation()

        assertEquals(
            listOf("Español", "Inglés", "Japonés"),
            subtitles.mapIndexed { index, track ->
                track.localizedDisplayLabel(spanish, "Subtítulo ${index + 1}")
            },
        )
    }

    @Test
    fun `same language tracks add layout and codec only when needed`() {
        val tracks = listOf(
            audio(label = "Stereo", lang = "spa", codec = "audio/aac", channels = 2, id = 0),
            audio(label = "Stereo", lang = "spa", codec = "audio/eac3", channels = 6, id = 1),
        ).withTrackDisambiguation()

        assertTrue(tracks.all { it.showTechnicalDetails })
        assertTrue(tracks.none { it.showOrdinal })
        assertEquals("Español · 2.0 · AAC", tracks[0].localizedDisplayLabel(spanish, "Audio 1"))
        assertEquals("Español · 5.1 · Dolby Digital Plus", tracks[1].localizedDisplayLabel(spanish, "Audio 2"))
    }

    @Test
    fun `truly identical sibling tracks receive stable ordinals`() {
        val tracks = listOf(
            audio(label = "Stereo", lang = "spa", codec = "aac", channels = 2, id = 0),
            audio(label = "Stereo", lang = "spa", codec = "aac", channels = 2, id = 1),
        ).withTrackDisambiguation()

        assertTrue(tracks.all { it.showOrdinal })
        assertEquals("Español · 2.0 · AAC · #1", tracks[0].localizedDisplayLabel(spanish, "Audio 1"))
        assertEquals("Español · 2.0 · AAC · #2", tracks[1].localizedDisplayLabel(spanish, "Audio 2"))
    }

    @Test
    fun `missing language uses useful numbered fallback with technical metadata`() {
        val track = audio(label = "Stereo", lang = null, codec = "audio/ac3", channels = 2)
        assertEquals("Audio 1 · 2.0 · Dolby Digital", track.localizedDisplayLabel(spanish, "Audio 1"))
    }

    @Test
    fun `bibliographic language codes display as real localized names`() {
        val track = audio(label = "Stereo", lang = "ger")
        assertEquals("Alemán", track.localizedDisplayLabel(spanish, "Audio 1"))
    }

    @Test
    fun `language words are removed from labels instead of repeated`() {
        val track = audio(label = "English Stereo", lang = "eng")
        assertEquals("Inglés", track.localizedDisplayLabel(spanish, "Audio 1"))
    }

    @Test
    fun `accessibility and forced roles remain visible`() {
        val commentary = audio(label = "", lang = "eng", roleFlags = C.ROLE_FLAG_COMMENTARY)
        val forced = TrackOption(
            label = "",
            mpvId = 0,
            selected = false,
            lang = "spa",
            selectionFlags = C.SELECTION_FLAG_FORCED,
            typeIndex = 0,
            labelKind = TrackLabelKind.SUBTITLE,
        )

        assertEquals("Inglés · COMMENTARY", commentary.localizedDisplayLabel(spanish, "Audio 1"))
        assertEquals("Español · FORCED", forced.localizedDisplayLabel(spanish, "Subtítulo 1"))
    }

    @Test
    fun `external subtitle identity and release stay untouched`() {
        val track = TrackOption(
            label = "OS_Spanish · WEB-DL.NF",
            mpvId = 4,
            selected = false,
            lang = "spa",
            typeIndex = 0,
            labelKind = TrackLabelKind.SUBTITLE,
        )

        assertEquals("OS_Spanish · WEB-DL.NF", track.localizedDisplayLabel(spanish, "Subtítulo 1"))
        assertFalse(track.showOrdinal)
    }

    private fun audio(
        label: String,
        lang: String?,
        codec: String? = null,
        channels: Int? = null,
        id: Int = 0,
        roleFlags: Int = 0,
    ) = TrackOption(
        label = label,
        mpvId = id,
        selected = id == 0,
        codec = codec,
        lang = lang,
        channelCount = channels,
        roleFlags = roleFlags,
        typeIndex = id,
        labelKind = TrackLabelKind.AUDIO,
    )

    private fun subtitle(label: String, lang: String?, id: Int) = TrackOption(
        label = label,
        mpvId = id,
        selected = id == 0,
        lang = lang,
        typeIndex = id,
        labelKind = TrackLabelKind.SUBTITLE,
    )
}
