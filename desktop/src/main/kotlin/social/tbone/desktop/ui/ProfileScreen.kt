package social.tbone.desktop.ui

import kotlinx.coroutines.launch

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.Nip19
import social.tbone.core.store.ProfileStore
import social.tbone.desktop.app.AppCore
import social.tbone.desktop.app.ProfileData
import social.tbone.desktop.app.copyToClipboard
import social.tbone.desktop.app.launchAction
import social.tbone.desktop.app.loadProfile
import social.tbone.desktop.app.toggleFollow

/**
 * Profile screen — port of upstream's `ProfileScreen`.
 *
 * Shows the same information the phone app's profile tab did: avatar, display
 * name, NIP-05, about, npub with a copy action, follow/unfollow, block, relay
 * hints and the author's notes. Identity verification (upstream's
 * VerifyIdentityScreen, which compares a 6-word BIP-39 fingerprint) is included
 * because it is a privacy-hygiene feature, not an Android one.
 */
@Composable
fun ProfileScreen(
    app: AppCore,
    pubkey: String,
    onBack: () -> Unit,
    onOpenThread: (Event) -> Unit,
) {
    var data by remember(pubkey) { mutableStateOf<ProfileData?>(null) }
    var error by remember(pubkey) { mutableStateOf<String?>(null) }
    var editing by remember(pubkey) { mutableStateOf(false) }
    val activeAccount by app.accounts.activeAccount.collectAsState()
    val isSelf = activeAccount?.pubkey == pubkey

    LaunchedEffect(pubkey) {
        app.loadProfile(pubkey)
            .onSuccess { data = it }
            .onFailure { error = it.message }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BoneButton("← back", onClick = onBack)
            Spacer(Modifier.weight(1f))
            if (data != null) {
                if (!data!!.isSelf) {
                    BoneButton(if (data!!.isFollowing) "unfollow" else "follow", primary = !data!!.isFollowing) {
                        app.scope.launchAction {
                            app.toggleFollow(pubkey, !data!!.isFollowing).map { data = data!!.copy(isFollowing = !data!!.isFollowing) }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                BoneButton(if (data!!.isBlocked) "unblock" else "block") {
                    if (data!!.isBlocked) app.settings.unblock(pubkey) else app.settings.block(pubkey)
                    data = data!!.copy(isBlocked = !data!!.isBlocked)
                }
                if (isSelf) {
                    Spacer(Modifier.width(8.dp))
                    BoneButton("edit profile", primary = true) { editing = !editing }
                }
            }
        }
        Rule()

        if (error != null) {
            EmptyState("profile unavailable", error!!)
            return@Column
        }
        val d = data ?: run {
            Text("loading profile…", style = Type.metaDim, color = colors.textMute, modifier = Modifier.padding(18.dp))
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pic = d.profile?.picture
                        val bmp = pic?.let { app.media.cached(it) }
                        LaunchedEffect(pic) { pic?.let { app.media.request(it) } }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            InitialAvatar(pubkey, d.profile?.bestName ?: pubkey, 64)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(d.profile?.bestName ?: ProfileStore.shortHex(pubkey), style = Type.title, color = colors.text)
                            if (!d.profile?.nip05.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Pill("nip-05", colors.accent)
                                    Spacer(Modifier.width(6.dp))
                                    Text(d.profile!!.nip05!!, style = Type.metaDim, color = colors.textDim)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                Nip19.hexToNpub(pubkey).let { "${it.take(16)}…${it.takeLast(6)}" },
                                style = Type.metaDim,
                                color = colors.textMute,
                                modifier = Modifier.clickable { copyToClipboard(Nip19.hexToNpub(pubkey)) },
                            )
                        }
                    }
                    if (d.isBlocked) {
                        Spacer(Modifier.height(10.dp))
                        Pill("blocked — their notes are hidden", colors.danger)
                    }
                }
            }

            if (editing && isSelf) {
                item { ProfileEditor(app) }
            }

            item {
                Column(Modifier.padding(horizontal = 18.dp)) {
                    if (!d.profile?.about.isNullOrBlank()) {
                        Text(d.profile!!.about!!, style = Type.bodyDim, color = colors.textDim)
                        Spacer(Modifier.height(14.dp))
                    }
                    StatRow(
                        listOf(
                            "notes" to d.notes.size.toString(),
                            "following" to if (d.following.isEmpty()) "?" else d.following.size.toString(),
                            "relays" to d.relayHints.size.toString(),
                        )
                    )
                    Spacer(Modifier.height(14.dp))
                    if (d.relayHints.isNotEmpty()) {
                        SectionLabel("relay hints")
                        Spacer(Modifier.height(4.dp))
                        Text(d.relayHints.joinToString("  ·  "), style = Type.caption, color = colors.textMute)
                        Spacer(Modifier.height(14.dp))
                    }
                    IdentityFingerprint(pubkey)
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("notes")
                }
                Rule()
            }

            if (d.notes.isEmpty()) {
                item {
                    Text(
                        "No notes from this account on the connected relays.",
                        style = Type.metaDim, color = colors.textMute,
                        modifier = Modifier.padding(18.dp),
                    )
                }
            }
            items(d.notes, key = { it.id }) { e ->
                NoteCard(app, e, Modifier.fillMaxWidth(), onOpenThread, onOpenProfile = null)
                Rule()
            }
        }
    }
}

/**
 * BIP-39 fingerprint — port of upstream's VerifyIdentity screen.
 *
 * The same deterministic derivation is used: SHA-256 of the pubkey, 11 bits per
 * word into the 2048-word list, so two devices show the same six words for the
 * same identity. The 4×4 dot grid uses bits 66–81 of the same hash.
 */
@Composable
private fun IdentityFingerprint(pubkey: String) {
    val words = remember(pubkey) { runCatching { social.tbone.core.nostr.identity.Phrase.wordsFor(pubkey, 6) }.getOrDefault(emptyList()) }
    val grid = remember(pubkey) { runCatching { social.tbone.core.nostr.identity.Phrase.avatarGrid(pubkey) }.getOrDefault(emptyList()) }
    Column {
        SectionLabel("identity fingerprint")
        Spacer(Modifier.height(6.dp))
        Text(
            words.joinToString(" ") { it.uppercase() },
            style = Type.variant(Type.meta, tracking = 1.2f),
            color = colors.textDim,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            (0 until 4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    (0 until 4).forEach { col ->
                        val on = grid.getOrNull(row * 4 + col) == true
                        Box(
                            Modifier
                                .size(9.dp)
                                .background(if (on) colors.accent else colors.surfaceAlt)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Compare these six words and the dot grid with the account owner on another device.",
            style = Type.caption, color = colors.textMute,
        )
    }
}

@Composable
private fun ProfileEditor(app: AppCore) {
    var name by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var picture by remember { mutableStateOf("") }
    var nip05 by remember { mutableStateOf("") }
    var lud16 by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(18.dp)
    ) {
        SectionLabel("edit profile (kind 0)")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BoneTextField(name, { name = it }, Modifier.weight(1f), placeholder = "name")
            BoneTextField(display, { display = it }, Modifier.weight(1f), placeholder = "display name")
        }
        Spacer(Modifier.height(8.dp))
        BoneTextField(about, { about = it }, Modifier.fillMaxWidth(), placeholder = "about", singleLine = false, minLines = 2)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BoneTextField(picture, { picture = it }, Modifier.weight(1f), placeholder = "picture url")
            BoneTextField(nip05, { nip05 = it }, Modifier.weight(1f), placeholder = "nip05")
            BoneTextField(lud16, { lud16 = it }, Modifier.weight(1f), placeholder = "lud16")
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BoneButton("publish", primary = true) {
                app.scope.launchAction {
                    app.publishProfile(name, display, about, picture, nip05, lud16)
                        .map { status = "published" }
                        .onFailure { status = it.message }
                }
            }
            Spacer(Modifier.width(10.dp))
            status?.let { Text(it, style = Type.metaDim, color = colors.textDim) }
        }
        Spacer(Modifier.height(4.dp))
    }
    Rule()
}
