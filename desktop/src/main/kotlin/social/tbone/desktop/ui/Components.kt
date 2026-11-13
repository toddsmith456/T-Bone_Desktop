package social.tbone.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.tbone.core.nostr.ProfileContent
import social.tbone.core.store.ProfileStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared chrome for the desktop port.
 *
 * The Android build leaned on Material components for the "phone" look; on a
 * desktop window the layout is a three-region shell (sidebar, timeline, composer)
 * but the *visual language* is kept: monospaced type, hairline rules, uppercase
 * micro-labels and the accent only where it carries meaning.
 */

/** A section label: uppercase, letter-spaced, muted — upstream's caption style. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = colors.textMute) {
    Text(
        text = text.uppercase(),
        style = Type.caption,
        color = color,
        modifier = modifier,
    )
}

/** A 1 px hairline in the current palette. */
@Composable
fun Rule(modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(if (strong) colors.ruleStrong else colors.rule)
    )
}

/** Bordered, uppercase button in the T-bone style. */
@Composable
fun BoneButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val border = when {
        !enabled -> colors.rule
        primary -> colors.accent
        else -> colors.ruleStrong
    }
    val textColor = when {
        !enabled -> colors.textMute
        primary -> colors.accent
        else -> colors.text
    }
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (primary && enabled) colors.accentBg else Color.Transparent)
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = Type.button, color = textColor)
    }
}

/** Small status pill (relay state, encryption state, signer state). */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color(color.red, color.green, color.blue, 0.12f))
            .border(1.dp, Color(color.red, color.green, color.blue, 0.45f), RoundedCornerShape(3.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text.uppercase(), style = Type.tag, color = color)
    }
}

/** Text field with the app's hairline + monospace styling. */
@Composable
fun BoneTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    isError: Boolean = false,
    mono: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        isError = isError,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = if (mono) Type.body else androidx.compose.ui.text.TextStyle(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
            fontSize = 15.sp, lineHeight = 22.sp,
        ),
        placeholder = {
            if (placeholder.isNotEmpty()) {
                Text(placeholder, style = Type.body, color = colors.textMute)
            }
        },
        shape = RoundedCornerShape(4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.rule,
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
            cursorColor = colors.accent,
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            disabledContainerColor = colors.surfaceAlt,
            disabledTextColor = colors.textMute,
            errorBorderColor = colors.danger,
        ),
    )
}

/** Deterministic letter square, upstream's INITIAL avatar style. */
@Composable
fun InitialAvatar(pubkey: String, name: String, size: Int = 40) {
    val initials = initialsFor(name, pubkey)
    val hue = (pubkey.take(6).hashCode() and 0x7fffffff) % 360
    val base = hsl(hue.toFloat(), 0.35f, if (colors.isDark) 0.28f else 0.72f)
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(base)
            .border(1.dp, colors.rule, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            style = Type.variant(Type.tag, size = (size / 3.2f).toInt()),
            color = if (colors.isDark) colors.text else Color(0xFF1C1B19),
            fontWeight = FontWeight.SemiBold,
        )
    }
}


fun initialsFor(name: String, pubkey: String): String {
    val src = name.trim().ifBlank { pubkey }
    val parts = src.split(' ', '_', '-', '.').filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        parts.size == 1 && parts[0].length >= 2 -> parts[0].take(2).uppercase()
        parts.size == 1 -> parts[0].uppercase()
        else -> "?"
    }
}

private fun hsl(h: Float, s: Float, l: Float): Color {
    val c = (1 - kotlin.math.abs(2 * l - 1)) * s
    val hp = h / 60f
    val x = c * (1 - kotlin.math.abs(hp % 2 - 1))
    val (r1, g1, b1) = when {
        hp < 1 -> Triple(c, x, 0f)
        hp < 2 -> Triple(x, c, 0f)
        hp < 3 -> Triple(0f, c, x)
        hp < 4 -> Triple(0f, x, c)
        hp < 5 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2
    return Color((r1 + m).coerceIn(0f, 1f), (g1 + m).coerceIn(0f, 1f), (b1 + m).coerceIn(0f, 1f))
}

/** Relative timestamp, upstream's `Xs / Xm / Xh / Xd` style. */
fun relativeTime(createdAt: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
    val d = (nowSeconds - createdAt).coerceAtLeast(0)
    return when {
        d < 60 -> "${d}s"
        d < 3600 -> "${d / 60}m"
        d < 86_400 -> "${d / 3600}h"
        d < 604_800 -> "${d / 86_400}d"
        else -> DATE_FMT.format(Instant.ofEpochSecond(createdAt).atZone(ZoneId.systemDefault()))
    }
}

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** Empty-state block used by feeds, notes, notifications and search. */
@Composable
fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title.uppercase(), style = Type.title, color = colors.textDim)
        Spacer(Modifier.height(8.dp))
        Text(detail, style = Type.bodyDim, color = colors.textMute)
    }
}

/** Row of micro-stats (followers/likes/etc.) with hairlines between. */
@Composable
fun StatRow(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, (label, value) ->
            if (i > 0) {
                Spacer(Modifier.width(14.dp))
                Box(Modifier.width(1.dp).height(22.dp).background(colors.rule))
                Spacer(Modifier.width(14.dp))
            }
            Column {
                Text(value, style = Type.body, color = colors.text)
                Text(label.uppercase(), style = Type.caption, color = colors.textMute)
            }
        }
    }
}

/** Name + shortened npub line used everywhere an author is shown. */
fun displayNameWithFallback(profiles: ProfileStore, pubkey: String): String =
    profiles.profile(pubkey)?.bestName ?: ProfileStore.shortHex(pubkey)

fun profileOf(profiles: ProfileStore, pubkey: String): ProfileContent? = profiles.profile(pubkey)

@Composable
fun AuthorLine(name: String, pubkey: String, createdAt: Long, modifier: Modifier = Modifier, suffix: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Type.variant(Type.body, weight = FontWeight.SemiBold), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(ProfileStore.shortHex(pubkey), style = Type.metaDim, color = colors.textMute, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text("·", style = Type.metaDim, color = colors.textMute)
        Spacer(Modifier.width(8.dp))
        Text(relativeTime(createdAt), style = Type.metaDim, color = colors.textMute)
        if (suffix != null) {
            Spacer(Modifier.width(8.dp))
            Text(suffix, style = Type.metaDim, color = colors.textDim)
        }
    }
}
