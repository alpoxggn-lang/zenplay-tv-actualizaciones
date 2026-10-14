package tv.own.owntv.mobile.ui.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import tv.own.owntv.mobile.R
import tv.own.owntv.core.theme.AccentColor
import tv.own.owntv.core.theme.AccentRoleValues
import tv.own.owntv.core.theme.accentRolesFromSeed
import tv.own.owntv.core.theme.parseAccentHex
import tv.own.owntv.core.theme.roles

/**
 * ZenPlay's palette as a Material 3 [ColorScheme] for touch.
 *
 * [ZenPlayPalette] keeps the mobile surfaces consistent with ZenPlay for TV. Accent preferences
 * retain core's contrast-aware roles and remain compatible with restored settings.
 */
/** The accent's name, from core's strings — the same label the TV app's settings screen shows. */
val AccentColor.labelRes: Int
    @StringRes get() = when (this) {
        AccentColor.TEAL -> R.string.settings_accent_teal
        AccentColor.BLUE -> R.string.settings_accent_blue
        AccentColor.VIOLET -> R.string.settings_accent_violet
        AccentColor.GREEN -> R.string.settings_accent_green
        AccentColor.AMBER -> R.string.settings_accent_amber
    }

/**
 * The accent as it must look over a picture.
 *
 * The player draws on video, never on a themed surface, so the light theme's accent — chosen to
 * carry against a near-white page — is the wrong colour there: it is dark, and a dark seek bar on a
 * dark scene is invisible. The dark theme's accent is derived to sit on black, which is exactly what
 * is behind the player in both themes, so the player asks for that one regardless of the theme.
 */
fun accentOnVideo(accent: AccentColor, customAccent: String = ""): Color {
    val roles = parseAccentHex(customAccent)
        ?.let { accentRolesFromSeed(it, isDark = true) }
        ?: accent.roles(isDark = true)
    return Color(roles.primary)
}

/**
 * The colour each of the four glass materials becomes with the Glass Effect off.
 *
 * Three of them are the shell's per-region fills rather than M3 container steps, because that is
 * what the TV app paints and the two apps are one product: the navigation takes the rail colour,
 * the page takes the content colour, a row takes the detail-pane colour. The elevation ladder is
 * greyer and flattens the regions into each other, which is the look those three exist to avoid.
 */
@Immutable
data class MobileSurfaceTones(
    /** Dialogs, sheets and toasts — the TV app's own dialog fill. */
    val floating: Color,
    /** Bars, rails and the mini player. */
    val chrome: Color,
    /** Page panels and the detail backdrop. */
    val container: Color,
    /** Cards and rows. */
    val inline: Color,
)

fun mobileSurfaceTones(isDark: Boolean): MobileSurfaceTones = if (isDark) {
    MobileSurfaceTones(
        floating = Color(ZenPlayPalette.DarkSurfaceContainerHigh),
        chrome = Color(ZenPlayPalette.DarkRailPanel),
        container = Color(ZenPlayPalette.DarkContentPanel),
        inline = Color(ZenPlayPalette.DarkPreviewPanel),
    )
} else {
    MobileSurfaceTones(
        floating = Color(ZenPlayPalette.LightSurfaceContainerHigh),
        chrome = Color(ZenPlayPalette.LightRailPanel),
        container = Color(ZenPlayPalette.LightContentPanel),
        inline = Color(ZenPlayPalette.LightPreviewPanel),
    )
}

fun mobileColorScheme(
    isDark: Boolean,
    accent: AccentColor,
    customAccent: String = "",
): ColorScheme {
    // A valid custom hex wins over the preset: the seed renders exactly, and its supporting
    // contrast roles are derived from it (core's derivation, the same one the TV app uses).
    val roles: AccentRoleValues = parseAccentHex(customAccent)
        ?.let { accentRolesFromSeed(it, isDark) }
        ?: accent.roles(isDark)

    return if (isDark) {
        darkColorScheme(
            primary = Color(roles.primary),
            onPrimary = Color(roles.onPrimary),
            primaryContainer = Color(roles.primaryContainer),
            onPrimaryContainer = Color(roles.onPrimaryContainer),
            secondary = Color(ZenPlayPalette.DarkSecondary),
            onSecondary = Color(ZenPlayPalette.DarkOnSecondary),
            secondaryContainer = Color(ZenPlayPalette.DarkSecondaryContainer),
            onSecondaryContainer = Color(ZenPlayPalette.DarkOnSecondaryContainer),
            tertiary = Color(ZenPlayPalette.DarkTertiary),
            onTertiary = Color(ZenPlayPalette.DarkOnTertiary),
            tertiaryContainer = Color(ZenPlayPalette.DarkTertiaryContainer),
            onTertiaryContainer = Color(ZenPlayPalette.DarkOnTertiaryContainer),
            background = Color(ZenPlayPalette.DarkBackground),
            onBackground = Color(ZenPlayPalette.DarkOnSurface),
            surface = Color(ZenPlayPalette.DarkSurface),
            onSurface = Color(ZenPlayPalette.DarkOnSurface),
            surfaceVariant = Color(ZenPlayPalette.DarkSurfaceContainerHigh),
            onSurfaceVariant = Color(ZenPlayPalette.DarkOnSurfaceVariant),
            surfaceContainerLowest = Color(ZenPlayPalette.DarkSurfaceContainerLowest),
            surfaceContainerLow = Color(ZenPlayPalette.DarkSurfaceContainerLow),
            surfaceContainer = Color(ZenPlayPalette.DarkSurfaceContainer),
            surfaceContainerHigh = Color(ZenPlayPalette.DarkSurfaceContainerHigh),
            surfaceContainerHighest = Color(ZenPlayPalette.DarkSurfaceContainerHighest),
            outline = Color(ZenPlayPalette.DarkOutline),
            outlineVariant = Color(ZenPlayPalette.DarkOutlineVariant),
            error = Color(ZenPlayPalette.DarkError),
        )
    } else {
        lightColorScheme(
            primary = Color(roles.primary),
            onPrimary = Color(roles.onPrimary),
            primaryContainer = Color(roles.primaryContainer),
            onPrimaryContainer = Color(roles.onPrimaryContainer),
            secondary = Color(ZenPlayPalette.LightSecondary),
            onSecondary = Color(ZenPlayPalette.LightOnSecondary),
            secondaryContainer = Color(ZenPlayPalette.LightSecondaryContainer),
            onSecondaryContainer = Color(ZenPlayPalette.LightOnSecondaryContainer),
            tertiary = Color(ZenPlayPalette.LightTertiary),
            onTertiary = Color(ZenPlayPalette.LightOnTertiary),
            tertiaryContainer = Color(ZenPlayPalette.LightTertiaryContainer),
            onTertiaryContainer = Color(ZenPlayPalette.LightOnTertiaryContainer),
            background = Color(ZenPlayPalette.LightBackground),
            onBackground = Color(ZenPlayPalette.LightOnSurface),
            surface = Color(ZenPlayPalette.LightSurface),
            onSurface = Color(ZenPlayPalette.LightOnSurface),
            surfaceVariant = Color(ZenPlayPalette.LightSurfaceContainerHigh),
            onSurfaceVariant = Color(ZenPlayPalette.LightOnSurfaceVariant),
            surfaceContainerLowest = Color(ZenPlayPalette.LightSurfaceContainerLowest),
            surfaceContainerLow = Color(ZenPlayPalette.LightSurfaceContainerLow),
            surfaceContainer = Color(ZenPlayPalette.LightSurfaceContainer),
            surfaceContainerHigh = Color(ZenPlayPalette.LightSurfaceContainerHigh),
            surfaceContainerHighest = Color(ZenPlayPalette.LightSurfaceContainerHighest),
            outline = Color(ZenPlayPalette.LightOutline),
            outlineVariant = Color(ZenPlayPalette.LightOutlineVariant),
            error = Color(ZenPlayPalette.LightError),
        )
    }
}
