package social.tbone.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.EventKind
import social.tbone.core.nostr.Filter
import social.tbone.core.nostr.replyEventId
import social.tbone.core.nostr.rootEventId
import social.tbone.desktop.app.AppCore
import social.tbone.desktop.app.eventsSnapshot
import social.tbone.desktop.app.loadThread
import kotlinx.coroutines.launch
import social.tbone.desktop.app.launchAction

/**
 * Thread view — port of upstream's `ThreadScreen`/`ThreadViewModel`.
 *
 * Upstream walked the NIP-10 chain: the tapped note's root (if any) at the top,
 * then direct replies underneath, fetched by `#e` tag and re-verified. The same
 * traversal is used here, with the parent always shown above the focused note
 * (the phone app had a specific fix for that).
 */
@Composable
fun ThreadScreen(
    app: AppCore,
    eventId: String,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenThread: (Event) -> Unit,
) {
    var focus by remember(eventId) { mutableStateOf<Event?>(app.eventsSnapshot(eventId)) }
    var ancestors by remember(eventId) { mutableStateOf<List<Event>>(emptyList()) }
    var replies by remember(eventId) { mutableStateOf<List<Event>>(emptyList()) }
    var loading by remember(eventId) { mutableStateOf(true) }

    androidx.compose.runtime.LaunchedEffect(eventId) {
        loading = true
        app.loadThread(eventId)
            .onSuccess { result ->
                focus = result.focus
                ancestors = result.ancestors
                replies = result.replies
            }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoneButton("← back", onClick = onBack)
            Spacer(Modifier.width(14.dp))
            SectionLabel("thread")
            Spacer(Modifier.weight(1f))
            Text("${replies.size} REPLIES", style = Type.caption, color = colors.textMute)
        }
        Rule()

        LazyColumn(Modifier.fillMaxSize()) {
            items(ancestors, key = { "a-${it.id}" }) { e ->
                NoteCard(app, e, Modifier.fillMaxWidth(), { onOpenThread(it) }, onOpenProfile, compact = true)
                Rule()
            }
            focus?.let { f ->
                item(key = "focus") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                    ) {
                        NoteCard(app, f, Modifier.fillMaxWidth(), { onOpenThread(it) }, onOpenProfile)
                    }
                    Rule(strong = true)
                }
                item(key = "composer") {
                    Composer(app, replyTo = f)
                    Rule()
                }
            }
            if (loading) {
                item { Text("loading thread…", style = Type.metaDim, color = colors.textMute, modifier = Modifier.padding(18.dp)) }
            }
            if (!loading && replies.isEmpty()) {
                item { Text("no replies yet", style = Type.metaDim, color = colors.textMute, modifier = Modifier.padding(18.dp)) }
            }
            items(replies, key = { "r-${it.id}" }) { e ->
                NoteCard(app, e, Modifier.fillMaxWidth(), { onOpenThread(it) }, onOpenProfile)
                Rule()
            }
        }
    }
}

/** Result of a thread load, kept explicit so the UI can show partial data. */
data class ThreadData(
    val focus: Event?,
    val ancestors: List<Event>,
    val replies: List<Event>,
)
