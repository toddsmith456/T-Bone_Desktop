package social.tbone.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as PlatformFont
import androidx.compose.ui.unit.sp
import social.tbone.core.settings.ThemeMode
import kotlin.math.roundToInt

/**
 * The T-bone palette, ported from upstream's `BonyColors` / `BonyTheme`.
 *
 * Every value below is upstream's. What changed is only the delivery mechanism:
 * upstream mutated a global mutable state object from a ViewModel; here the
 * palette is computed from the current [ThemeMode] + accent and provided through
 * the Material scheme, so recomposition happens the normal Compose way.
 *
 * The three shipped modes are preserved exactly: LIGHT, DARK (pure black) and
 * CREAM (a dimmed old-book-page warm scheme).
 */
object Palette {
    // Default (DARK) values, also used as the fallback for any mode.
    val Bg = Color(0xFF0A0A0A)
    val Surface = Color(0xFF0F0F0E)
    val SurfaceAlt = Color(0xFF0D0D0C)
    val Rule = Color(0xFF2A2826)
    val RuleStrong = Color(0xFF3A3733)
    val Text = Color(0xFFE8E6DF)
    val TextDim = Color(0xFF9A968D)
    val TextMute = Color(0xFF6B6862)
    val Warn = Color(0xFFE89A4A)
    val Danger = Color(0xFFD96A5A)
    val Link = Color(0xFF82C8FF)
    val Accent = Color(0xFF7FDE6B)
    val DefaultAccent = Color(0xFF7FDE6B)
    val AccentBg = Color(0x0A7FDE6B)
    val AccentDim = Color(0xFF3F6F35)
}

data class ThemeColors(
    val bg: Color = Palette.Bg,
    val surface: Color = Palette.Surface,
    val surfaceAlt: Color = Palette.SurfaceAlt,
    val rule: Color = Palette.Rule,
    val ruleStrong: Color = Palette.RuleStrong,
    val text: Color = Palette.Text,
    val textDim: Color = Palette.TextDim,
    val textMute: Color = Palette.TextMute,
    val warn: Color = Palette.Warn,
    val danger: Color = Palette.Danger,
    val link: Color = Palette.Link,
    val accent: Color = Palette.Accent,
    val accentBg: Color = Color(0x0A7FDE6B),
    val accentDim: Color = Color(0xFF3F6F35),
    val isDark: Boolean = true,
)

/** Builds the palette for a mode + optional custom accent ("#RRGGBB"). */
fun themeColors(mode: ThemeMode, accentHex: String?): ThemeColors {
    val accent = parseHexColor(accentHex) ?: Palette.DefaultAccent
    val accentBg = Color(accent.red, accent.green, accent.blue, 10f / 255f)
    val accentDim = lerpColor(accent, Color.Black, 0.5f)
    return when (mode) {
        ThemeMode.DARK -> ThemeColors(accent = accent, accentBg = accentBg, accentDim = accentDim, isDark = true)
        ThemeMode.LIGHT -> ThemeColors(
            bg = Color(0xFFF7F6F3),
            surface = Color(0xFFFFFFFF),
            surfaceAlt = Color(0xFFF0EFEB),
            rule = Color(0xFFE2E0DA),
            ruleStrong = Color(0xFFC9C6BF),
            text = Color(0xFF1C1B19),
            textDim = Color(0xFF55534D),
            textMute = Color(0xFF8A8780),
            warn = Color(0xFFB26A1F),
            danger = Color(0xFFC74B3D),
            link = Color(0xFF1E6FB8),
            accent = accent,
            accentBg = accentBg,
            accentDim = accentDim,
            isDark = false,
        )
        ThemeMode.CREAM -> ThemeColors(
            bg = Color(0xFFD9CEB0),
            surface = Color(0xFFE1D7BB),
            surfaceAlt = Color(0xFFD0C3A1),
            rule = Color(0xFFB8A987),
            ruleStrong = Color(0xFF9E8D68),
            text = Color(0xFF262015),
            textDim = Color(0xFF4B422D),
            textMute = Color(0xFF6A5E44),
            warn = Color(0xFFA8641B),
            danger = Color(0xFFB23A2C),
            link = Color(0xFF1E63A5),
            accent = accent,
            accentBg = accentBg,
            accentDim = accentDim,
            isDark = false,
        )
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

/**
 * JetBrains Mono NL (No Ligatures), OFL — bundled in resources/, the same faces
 * the Android build shipped as res/font/jetbrains_mono_*.ttf.
 *
 * Loading is resolved once, defensively. If the TTFs are missing from the runtime
 * image (a packaging mistake, or a classpath that omitted `resources/main`), Skiko
 * raises from inside the first measure pass and the failure surfaces as a raw AWT
 * error dialog with no window to speak of — a crash the user cannot act on. So the
 * faces are probed up front: present -> bundled mono, absent -> [FontFamily.Monospace]
 * (which is also a mono face, just the platform's). `fontBundled` is false in that
 * case and Main prints a warning, so a degraded start is never silent.
 */
val fontBundled: Boolean = listOf(
    "/jetbrains_mono_regular.ttf",
    "/jetbrains_mono_semibold.ttf",
    "/jetbrains_mono_bold.ttf",
).all { ThemeFontProbe.available(it) }

private object ThemeFontProbe {
    fun available(path: String): Boolean =
        runCatching { object {}.javaClass.getResourceAsStream(path)?.use { it.read() } != null }
            .getOrDefault(false)
}

val JetBrainsMono: FontFamily = if (fontBundled) {
    FontFamily(
        PlatformFont("/jetbrains_mono_regular.ttf", FontWeight.Normal),
        PlatformFont("/jetbrains_mono_semibold.ttf", FontWeight.SemiBold),
        PlatformFont("/jetbrains_mono_bold.ttf", FontWeight.Bold),
    )
} else {
    FontFamily.Monospace
}

/**
 * Upstream's type scale, unchanged in size/weight/tracking.
 *
 * Each style is constructed explicitly rather than derived with `TextStyle.copy()`:
 * the multiplatform `copy` for ui-text is not resolvable from this build's
 * metadata, and explicit construction is what those copies would produce anyway.
 */
object Type {
    private fun ts(
        size: Int,
        weight: FontWeight = FontWeight.Normal,
        tracking: Float = 0f,
        lineHeight: Int = (size * 1.45f).toInt(),
    ) = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = tracking.sp,
    )

    val wordmark = ts(30, FontWeight.Bold, -1.0f, 30)
    val title = ts(20, FontWeight.Bold, -0.3f, 26)
    val body = ts(15, FontWeight.Normal, 0f, 22)
    val bodyDim = ts(14, FontWeight.Normal, 0f, 20)
    val meta = ts(13, FontWeight.Normal, 0.4f, 18)
    val metaDim = ts(12, FontWeight.Normal, 0.4f, 16)
    val caption = ts(11, FontWeight.Normal, 1.5f, 14)
    val tag = ts(11, FontWeight.SemiBold, 1.0f, 14)
    val button = ts(13, FontWeight.SemiBold, 1.0f, 16)

    /** Variant used where a style needs a specific size/weight. */
    fun variant(
        base: TextStyle,
        size: Int? = null,
        weight: FontWeight? = null,
        tracking: Float? = null,
        lineHeight: Int? = null,
    ): TextStyle = TextStyle(
        fontFamily = base.fontFamily,
        fontWeight = weight ?: base.fontWeight,
        fontSize = size?.sp ?: base.fontSize,
        lineHeight = lineHeight?.sp ?: base.lineHeight,
        letterSpacing = tracking?.sp ?: base.letterSpacing,
        textDecoration = base.textDecoration,
        textAlign = base.textAlign,
        fontStyle = base.fontStyle,
    )
}

/** Provides the palette to the tree; mirrors upstream's `BonyTheme` signature. */
@Composable
fun BonyTheme(
    mode: ThemeMode = ThemeMode.DARK,
    accent: String? = null,
    content: @Composable () -> Unit,
) {
    val c = themeColors(mode, accent)
    val scheme = if (c.isDark) {
        darkColorScheme(
            background = c.bg, surface = c.surface, surfaceVariant = c.surfaceAlt,
            onBackground = c.text, onSurface = c.text, onSurfaceVariant = c.textDim,
            outline = c.rule, outlineVariant = c.rule,
            primary = c.accent, onPrimary = c.bg,
            error = c.danger, onError = c.bg,
        )
    } else {
        lightColorScheme(
            background = c.bg, surface = c.surface, surfaceVariant = c.surfaceAlt,
            onBackground = c.text, onSurface = c.text, onSurfaceVariant = c.textDim,
            outline = c.rule, outlineVariant = c.rule,
            primary = c.accent, onPrimary = c.bg,
            error = c.danger, onError = c.bg,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

// NOTE: deliberately `compositionLocalOf`, not `staticCompositionLocalOf`.
// A static local never re-reads its provider, so switching LIGHT/DARK/CREAM left
// panes painted in the previous palette (measured: sidebar text stayed dark-mode
// grey on the cream background). The dynamic local propagates the change.
val LocalColors = androidx.compose.runtime.compositionLocalOf { ThemeColors() }

/** Convenience accessor used throughout the desktop UI. */
val colors: ThemeColors
    @Composable get() = LocalColors.current

/** Parses "#RRGGBB" (or "#AARRGGBB"); null on garbage. Upstream behaviour. */
fun parseHexColor(hex: String?): Color? {
    if (hex == null) return null
    val cleaned = hex.removePrefix("#")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    val value = cleaned.toLongOrNull(16) ?: return null
    return if (cleaned.length == 6) {
        Color(
            red = ((value ushr 16) and 0xFF) / 255f,
            green = ((value ushr 8) and 0xFF) / 255f,
            blue = (value and 0xFF) / 255f,
        )
    } else {
        Color(
            red = ((value ushr 16) and 0xFF) / 255f,
            green = ((value ushr 8) and 0xFF) / 255f,
            blue = (value and 0xFF) / 255f,
            alpha = ((value ushr 24) and 0xFF) / 255f,
        )
    }
}

fun colorToHex(color: Color): String {
    val r = (color.red * 255).roundToInt().coerceIn(0, 255)
    val g = (color.green * 255).roundToInt().coerceIn(0, 255)
    val b = (color.blue * 255).roundToInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(r, g, b)
}
