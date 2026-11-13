package social.tbone.desktop.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import social.tbone.core.nostr.Event
import social.tbone.core.settings.ImageLoadMode
import social.tbone.desktop.app.AppCore
import social.tbone.desktop.app.FeedTab
import social.tbone.desktop.app.launchAction
import kotlinx.coroutines.launch

/**
 * Timeline screen.
 *
 * Holds the two feeds upstream had — HOME (your follow graph) and GLOBAL (an
 * unfiltered window) — with a composer docked at the top, which is the desktop
 * equivalent of the phone's compose FAB + separate editor screen.
 */
@Composable
fun FeedScreen(
    app: AppCore,
    tab: FeedTab,
    onTabChange: (FeedTab) -> Unit,
    onOpenThread: (Event) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenSearch: () -> Unit,
) {
    val ui by app.feed.ui.collectAsState()
    val listState = rememberLazyListState()
    val account by app.accounts.activeAccount.collectAsState()
    val signerProblem by app.signerProblem.collectAsState()

    // Upstream scrolled to top when a settled feed was flushed; same here.
    LaunchedEffect(ui.events.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        // Header: feed tabs + refresh
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabLabel("HOME", tab == FeedTab.HOME) { onTabChange(FeedTab.HOME) }
            Spacer(Modifier.width(16.dp))
            TabLabel("GLOBAL", tab == FeedTab.GLOBAL) { onTabChange(FeedTab.GLOBAL) }
            Spacer(Modifier.weight(1f))
            if (ui.isLoading) {
                CircularProgressIndicator(
                    color = colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.width(16.dp).height(16.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text("${ui.events.size} NOTES", style = Type.caption, color = colors.textMute)
            Spacer(Modifier.width(14.dp))
            BoneButton("refresh") { app.feed.refresh() }
        }
        Rule()

        if (account != null && signerProblem == null) {
            Composer(app)
            Rule()
        }

        if (ui.error != null) {
            Row(Modifier.fillMaxWidth().padding(18.dp)) {
                Pill("error", colors.danger)
                Spacer(Modifier.width(8.dp))
                Text(ui.error!!, style = Type.metaDim, color = colors.danger)
            }
        }

        when {
            ui.isLoading && ui.events.isEmpty() -> LoadingFeed()
            ui.events.isEmpty() -> EmptyState(
                title = "nothing here yet",
                detail = "Relays may still be connecting, or nobody you follow has posted. " +
                    "Use GLOBAL to look at the whole network.",
            )
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(ui.events, key = { it.id }) { event ->
                    NoteCard(
                        app = app,
                        event = event,
                        onOpenThread = onOpenThread,
                        onOpenProfile = onOpenProfile,
                    )
                    Rule()
                }
            }
        }
    }
}

@Composable
private fun TabLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(vertical = 2.dp)) {
        Text(
            label,
            style = Type.variant(Type.button, weight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal),
            color = if (selected) colors.text else colors.textMute,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(28.dp)
                .height(2.dp)
                .background(if (selected) colors.accent else androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}

@Composable
private fun LoadingFeed() {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
        Spacer(Modifier.height(16.dp))
        Text("connecting to relays…", style = Type.metaDim, color = colors.textMute)
    }
}

/**
 * Composer: 1024-character note with a reply/quote context line when opened from
 * a thread. Ctrl+Enter posts, matching the desktop convention the app uses
 * elsewhere for "submit".
 */
@Composable
fun Composer(
    app: AppCore,
    replyTo: Event? = null,
    quote: Event? = null,
    onPosted: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Relay acknowledgements for the last note published from this composer.
    var lastEventId by remember { mutableStateOf<String?>(null) }
    val publishResults by app.pool.publishResults.collectAsState()
    val maxLen = 1024

    // One submit path, used by both the POST button and the ctrl+enter shortcut the
    // UI advertises. (The shortcut previously existed only as label text.)
    val submit: () -> Unit = submit@{
        if (busy || text.isBlank()) return@submit
        busy = true
        error = null
        app.scope.launch {
            app.publishNote(text.trim(), replyTo = replyTo, quote = quote)
                .onSuccess {
                    text = ""
                    lastEventId = it.id
                    onPosted?.invoke()
                }
                .onFailure { error = it.message ?: "publish failed" }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        if (replyTo != null) {
            Text(
                "REPLYING TO ${displayNameWithFallback(app.profiles, replyTo.pubkey)}",
                style = Type.caption,
                color = colors.accent,
            )
            Spacer(Modifier.height(6.dp))
        }
        if (quote != null) {
            Text(
                "QUOTING ${displayNameWithFallback(app.profiles, quote.pubkey)}",
                style = Type.caption,
                color = colors.accent,
            )
            Spacer(Modifier.height(6.dp))
        }
        BasicTextField(
            value = text,
            onValueChange = { if (it.length <= maxLen) text = it },
            textStyle = Type.body.copyWithColor(colors.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .onPreviewKeyEvent { event ->
                    if (
                        event.type == KeyEventType.KeyDown &&
                        event.key == Key.Enter &&
                        (event.isCtrlPressed || event.isMetaPressed)
                    ) {
                        submit()
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize()) {
                    if (text.isEmpty()) {
                        Text(
                            when {
                                replyTo != null -> "write your reply…"
                                quote != null -> "add a comment…"
                                else -> "what's happening?"
                            },
                            style = Type.body,
                            color = colors.textMute,
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${text.length}/$maxLen", style = Type.caption, color = colors.textMute)
            if (error != null) {
                Spacer(Modifier.width(12.dp))
                Text(error!!, style = Type.caption, color = colors.danger)
            } else if (lastEventId != null) {
                // NIP-01 acceptance is asynchronous: say "sent" until a relay answers,
                // then report what the relays actually said. A rejection is shown in
                // full, because that is the case a user most needs to see — a silently
                // rejected note used to look identical to a successful one.
                val acks = publishResults[lastEventId].orEmpty()
                val rejected = acks.filterValues { !it.accepted }
                val acceptedCount = acks.count { it.value.accepted }
                Spacer(Modifier.width(12.dp))
                when {
                    rejected.isNotEmpty() -> Text(
                        "rejected by " + rejected.keys.joinToString(", ") { it.removePrefix("wss://") } +
                            ": " + rejected.values.first().message.ifBlank { "no reason given" },
                        style = Type.caption,
                        color = colors.danger,
                    )
                    acceptedCount > 0 -> Text(
                        "accepted by $acceptedCount relay" + (if (acceptedCount == 1) "" else "s"),
                        style = Type.caption,
                        color = colors.accent,
                    )
                    else -> Text(
                        "signed and sent — awaiting relay OK",
                        style = Type.caption,
                        color = colors.textMute,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text("ctrl+enter to post", style = Type.caption, color = colors.textMute)
            Spacer(Modifier.width(12.dp))
            BoneButton(
                label = if (busy) "signing…" else "post",
                primary = true,
                enabled = !busy && text.isNotBlank(),
            ) { submit() }
        }
    }
}

/** TextStyle helper shared with RichText (explicit construction, see Theme.kt). */
internal fun androidx.compose.ui.text.TextStyle.copyWithColor(c: androidx.compose.ui.graphics.Color) =
    androidx.compose.ui.text.TextStyle(
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        fontSize = fontSize,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing,
        color = c,
    )
