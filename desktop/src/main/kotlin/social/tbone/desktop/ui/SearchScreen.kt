package social.tbone.desktop.ui

import kotlinx.coroutines.launch

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import social.tbone.core.Log
import social.tbone.core.Tunables
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.EventKind
import social.tbone.core.nostr.Filter
import social.tbone.core.nostr.relay.RelayMessage
import social.tbone.desktop.app.AppCore
import social.tbone.desktop.app.normalisePubkeyInput
import social.tbone.core.store.KindSets
import social.tbone.desktop.app.eventsSnapshot

/**
 * Search — port of upstream's `SearchScreen`.
 *
 * Three modes, the same ones the phone app offered from one field:
 *   1. a pubkey (npub / nprofile / hex) → open that profile;
 *   2. a `nostr:` note/nevent URI → open that thread;
 *   3. anything else → NIP-50 full-text search on the search relay, with the
 *      author/kind filters upstream used, plus a local-cache pass so results are
 *      instant for notes already seen.
 */
@Composable
fun SearchScreen(
    app: AppCore,
    onOpenThread: (Event) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Event>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lookupProfile by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(18.dp)) {
            Text("SEARCH", style = Type.title, color = colors.text)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoneTextField(
                    query, { query = it },
                    Modifier.weight(1f),
                    placeholder = "text, npub1…, nostr:note1…",
                )
                Spacer(Modifier.width(10.dp))
                BoneButton(if (busy) "searching…" else "search", primary = true, enabled = !busy) {
                    busy = true
                    status = null
                    lookupProfile = null
                    app.scope.launch {
                        try {
                            when {
                                // 1. Pubkey input
                                normalisePubkeyInput(query) != null -> {
                                    lookupProfile = normalisePubkeyInput(query)
                                    results = emptyList()
                                }
                                // 2. nostr: URI
                                query.trim().startsWith("nostr:") -> {
                                    val id = social.tbone.core.nostr.Nip19.nostrUriToEventId(query.trim())
                                    val ev = id?.let { app.eventsSnapshot(it) }
                                    if (ev != null) {
                                        onOpenThread(ev)
                                    } else {
                                        status = "Note not found in the local cache; open the link's thread from a relay-backed view."
                                    }
                                }
                                // 3. NIP-50 text search + local pass
                                else -> {
                                    val local = app.searchLocal(query)
                                    if (local.isNotEmpty()) results = local
                                    val remote = app.searchRelay(query)
                                    val merged = (local + remote).distinctBy { it.id }.sortedByDescending { it.createdAt }
                                    results = merged
                                    status = if (merged.isEmpty()) {
                                        "No results. NIP-50 search depends on the relay supporting it."
                                    } else null
                                }
                            }
                        } catch (t: Throwable) {
                            status = t.message
                            Log.w("Search", "search failed: ${t.message}")
                        } finally {
                            busy = false
                        }
                    }
                }
            }
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = Type.metaDim, color = colors.textMute)
            }
            lookupProfile?.let { pk ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("profile found", colors.accent)
                    Spacer(Modifier.width(10.dp))
                    Text(pk, style = Type.metaDim, color = colors.textDim)
                    Spacer(Modifier.width(10.dp))
                    BoneButton("open profile") { onOpenProfile(pk) }
                }
            }
        }
        Rule()
        if (results.isEmpty()) {
            EmptyState(
                "search the network",
                "Full-text search uses NIP-50 and only returns what the search relay indexes. " +
                    "Paste an npub to jump to a profile, or a nostr: link to open a thread.",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.id }) { e ->
                    NoteCard(app, e, Modifier.fillMaxWidth(), onOpenThread, onOpenProfile)
                    Rule()
                }
            }
        }
    }
}

/** Local pass over the cached event table (fast, works offline). */
private suspend fun AppCore.searchLocal(q: String): List<Event> =
    runCatching {
        events.recent(
            accountPubkey = accounts.activeAccount.value?.pubkey ?: return emptyList(),
            kinds = KindSets.FEED + EventKind.METADATA,
            limit = 500,
        ).filter { it.content.contains(q, ignoreCase = true) }
    }.getOrDefault(emptyList())

/** NIP-50 search against the search relay, bounded by the EOSE timeout. */
private suspend fun AppCore.searchRelay(q: String): List<Event> {
    val pool = pool
    val searched = LinkedHashMap<String, Event>()
    val subId = pool.subscribe(
        listOf(
            Filter(kinds = listOf(EventKind.TEXT_NOTE), search = q, limit = 50),
        ),
        label = "search",
    )
    val listener = scope.launch {
        pool.messages.collect { msg ->
            val m = msg.message as? RelayMessage.EventMessage ?: return@collect
            if (m.subscriptionId != subId) return@collect
            if (m.event.verify()) synchronized(searched) { searched[m.event.id] = m.event }
        }
    }
    delay(Tunables.SEARCH_EOSE_TIMEOUT_MS)
    listener.cancel()
    pool.unsubscribe(subId)
    return synchronized(searched) { searched.values.toList() }
}
