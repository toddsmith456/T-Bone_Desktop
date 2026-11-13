package social.tbone.desktop.app

import kotlinx.coroutines.launch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.NostrJson
import social.tbone.core.settings.ContentFilter

/**
 * Note content parsing — port of upstream's `ui/feed/NoteContent.kt`.
 *
 * Rules preserved: URLs ending in an image extension become inline images,
 * video extensions become video links, and everything else stays in the text.
 * Nostr URIs are deliberately *left in* the body so the rich renderer can make
 * them tappable, exactly as upstream did.
 */

sealed class MediaItem {
    data class Image(val url: String) : MediaItem()
    data class Video(val url: String) : MediaItem()
}

data class ParsedNote(
    val text: String,
    val media: List<MediaItem>,
)

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")
private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mov", "m4v", "mkv")
private val URL_REGEX = Regex("""https?://\S+""")

fun parseNote(content: String, bleepWords: Set<String> = emptySet()): ParsedNote {
    val media = mutableListOf<MediaItem>()
    val text = URL_REGEX.replace(content) { match ->
        val url = match.value
        val ext = url.substringAfterLast('.').lowercase()
            .substringBefore('?').substringBefore('#')
        when {
            ext in IMAGE_EXTENSIONS -> { media.add(MediaItem.Image(url)); "" }
            ext in VIDEO_EXTENSIONS -> { media.add(MediaItem.Video(url)); "" }
            else -> match.value
        }
    }
        .replace(Regex(" {2,}"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
    return ParsedNote(ContentFilter.bleep(text, bleepWords), media)
}

/** NIP-88 poll options, or an empty list when the payload is not a poll. */
data class PollOption(val id: String, val label: String)
data class ParsedPoll(val question: String, val options: List<PollOption>)

fun parsePoll(event: Event): ParsedPoll {
    return runCatching {
        val root = NostrJson.parseToJsonElement(event.content)
        val obj = root as kotlinx.serialization.json.JsonObject
        val q = obj["content"]?.jsonPrimitive?.content ?: ""
        val opts = (obj["options"] as? JsonArray)?.mapIndexedNotNull { i, el ->
            runCatching {
                val o = el as kotlinx.serialization.json.JsonObject
                PollOption(
                    id = o["id"]?.jsonPrimitive?.content ?: "$i",
                    label = o["label"]?.jsonPrimitive?.content ?: "",
                )
            }.getOrNull()
        } ?: emptyList()
        ParsedPoll(question = q.ifBlank { "Poll" }, options = opts)
    }.getOrElse { ParsedPoll("Poll", emptyList()) }
}

/** Copies text to the system clipboard (AWT, works on every desktop platform). */
fun copyToClipboard(text: String) {
    runCatching {
        val sel = java.awt.datatransfer.StringSelection(text)
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
    }
}

/** Fire-and-forget action on the app scope, reporting the Result to the log. */
fun kotlinx.coroutines.CoroutineScope.launchAction(block: suspend () -> Result<*>) {
    this.launch {
        block()
            .onFailure { social.tbone.core.Log.w("Action", "failed: ${it.message}") }
    }
}
