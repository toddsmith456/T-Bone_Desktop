package social.tbone.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.Nip19
import social.tbone.desktop.app.AppCore
import social.tbone.desktop.app.DesktopUx

/**
 * Note body renderer — port of upstream's `LinkAwareText` / `NoteBodyText`.
 *
 * Upstream built an AnnotatedString with clickable annotations for:
 *   - `nostr:npub1…` / `nostr:nprofile1…` → that profile
 *   - `nostr:note1…` / `nostr:nevent1…`   → that thread
 *   - `#hashtag`                          → hashtag search
 *   - bare `http(s)://` links             → the system browser
 *   - `@npub1…` mentions                  → that profile
 *
 * Same rules here. The only difference is the click targets' destinations: the
 * desktop app opens threads/profiles in-app and hands real web links to the OS
 * browser, since there is no Android intent filter to catch `nostr:` URIs.
 */
@Composable
fun RichText(
    text: String,
    app: AppCore,
    modifier: Modifier = Modifier,
    onOpenThread: ((Event) -> Unit)? = null,
    onOpenProfile: ((String) -> Unit)? = null,
    onOpenHashtag: ((String) -> Unit)? = null,
) {
    val linkColor = colors.link
    val annotated = remember(text, linkColor) { buildLinkedText(text, linkColor) }
    var layoutResult by remember(text) { mutableStateOf<TextLayoutResult?>(null) }

    BasicText(
        text = annotated,
        modifier = modifier.pointerInput(text) {
            detectTapGestures { pos ->
                val layout = layoutResult ?: return@detectTapGestures
                val offset = layout.getOffsetForPosition(pos)
                val hit = annotated.getStringAnnotations(TAG_TARGET, offset, offset).firstOrNull()
                val target = hit?.let { parseTarget(it.item) }
                when (target) {
                    is LinkTarget.Profile -> onOpenProfile?.invoke(target.pubkey)
                    is LinkTarget.NoteId -> {
                        val open = onOpenThread
                        if (open != null) app.scope.launch {
                            app.events.byId(target.eventId)?.let { open(it) }
                        }
                    }
                    is LinkTarget.Hashtag -> onOpenHashtag?.invoke(target.tag)
                    is LinkTarget.Web -> DesktopUx.openExternal(target.url)
                    null -> Unit
                }
            }
        },
        style = withColor(Type.body, colors.text),
        onTextLayout = { layoutResult = it },
    )
}

/** TextStyle with a colour applied (explicit construction; see Theme.kt). */
private fun withColor(base: androidx.compose.ui.text.TextStyle, c: Color) =
    androidx.compose.ui.text.TextStyle(
        fontFamily = base.fontFamily,
        fontWeight = base.fontWeight,
        fontSize = base.fontSize,
        lineHeight = base.lineHeight,
        letterSpacing = base.letterSpacing,
        color = c,
    )

/** Resolved click target of a span, resolved lazily at click time. */
sealed interface LinkTarget {
    data class Profile(val pubkey: String) : LinkTarget
    data class NoteId(val eventId: String) : LinkTarget
    data class Hashtag(val tag: String) : LinkTarget
    data class Web(val url: String) : LinkTarget
}

private val NOSTR_URI = Regex("""nostr:(npub1|nprofile1|note1|nevent1|naddr1)[a-z0-9]+""")
private val AT_NPUB = Regex("""@(npub1[a-z0-9]+)""")
private val WEB_URL = Regex("""https?://\S+""")
private val HASHTAG = Regex("""(?<![\w/&])#([A-Za-z0-9_]{1,64})""")

/**
 * Builds the AnnotatedString. Exposed (internal) so the parsing rules can be
 * unit-tested without a Composition.
 */
internal fun buildLinkedText(text: String, linkColor: Color = Palette.Link): AnnotatedString {
    data class Hit(val start: Int, val end: Int, val target: LinkTarget)
    val hits = mutableListOf<Hit>()

    NOSTR_URI.findAll(text).forEach { m ->
        val uri = m.value
        val pubkey = Nip19.nprofileToHex(uri) ?: Nip19.npubToHex(uri.substringAfter("nostr:"))
        val noteId = Nip19.nostrUriToEventId(uri)
        when {
            pubkey != null -> hits.add(Hit(m.range.first, m.range.last + 1, LinkTarget.Profile(pubkey)))
            noteId != null -> hits.add(Hit(m.range.first, m.range.last + 1, LinkTarget.NoteId(noteId)))
        }
    }
    AT_NPUB.findAll(text).forEach { m ->
        val pk = Nip19.npubToHex(m.groupValues[1])
        if (pk != null) hits.add(Hit(m.range.first, m.range.last + 1, LinkTarget.Profile(pk)))
    }
    WEB_URL.findAll(text).forEach { m ->
        // Skip URLs already claimed as part of a nostr: URI.
        if (hits.none { m.range.first >= it.start && m.range.first < it.end }) {
            hits.add(Hit(m.range.first, m.range.last + 1, LinkTarget.Web(m.value)))
        }
    }
    HASHTAG.findAll(text).forEach { m ->
        hits.add(Hit(m.range.first, m.range.last + 1, LinkTarget.Hashtag(m.groupValues[1].lowercase())))
    }

    // Non-overlapping wins: earliest match first, then longest.
    val ordered = hits.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))
    val accepted = mutableListOf<Hit>()
    ordered.forEach { h ->
        if (accepted.none { a -> h.start < a.end && a.start < h.end }) accepted.add(h)
    }

    val builder = buildAnnotatedString {
        var cursor = 0
        accepted.sortedBy { it.start }.forEach { hit ->
            if (hit.start > cursor) append(text.substring(cursor, hit.start))
            pushStringAnnotation(TAG_TARGET, hit.target.serialized())
            withStyle(
                SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                )
            ) { append(text.substring(hit.start, hit.end)) }
            pop()
            cursor = hit.end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
    return builder
}

private const val TAG_TARGET = "tbone:target"

/** Compact, stable encoding so the annotation list survives recomposition. */
private fun LinkTarget.serialized(): String = when (this) {
    is LinkTarget.Profile -> "p:$pubkey"
    is LinkTarget.NoteId -> "e:$eventId"
    is LinkTarget.Hashtag -> "t:$tag"
    is LinkTarget.Web -> "w:$url"
}

internal fun parseTarget(raw: String): LinkTarget? = when (raw.substringBefore(':')) {
    "p" -> LinkTarget.Profile(raw.removePrefix("p:"))
    "e" -> LinkTarget.NoteId(raw.removePrefix("e:"))
    "t" -> LinkTarget.Hashtag(raw.removePrefix("t:"))
    "w" -> LinkTarget.Web(raw.removePrefix("w:"))
    else -> null
}

