package social.tbone.desktop.ui

import kotlinx.coroutines.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import social.tbone.core.net.Transport
import social.tbone.core.settings.AvatarMode
import social.tbone.core.settings.ImageLoadMode
import social.tbone.core.settings.OrbotHelper
import social.tbone.core.settings.ThemeMode
import social.tbone.core.settings.ThemeMode.CREAM
import social.tbone.core.settings.ThemeMode.DARK
import social.tbone.core.settings.ThemeMode.LIGHT
import social.tbone.desktop.app.AppCore
import social.tbone.desktop.app.DesktopUx
import social.tbone.desktop.app.NotifKind
import social.tbone.desktop.app.launchAction

/**
 * Settings — port of upstream's `SettingsScreen`, restricted to the settings that
 * mean something on a desktop, with the Android-only ones called out instead of
 * silently missing.
 *
 * Sections kept: appearance (light/dark/cream + accent), media (inline images,
 * avatar style), privacy (Tor/proxy status, app lock PIN), content filters
 * (blocked pubkeys, NSFW, hidden and bleeped words), relays (add/remove, publish
 * NIP-65), notifications, account (signer type, key state), and data (wipe,
 * cache, log path, version/build info).
 */
@Composable
fun SettingsScreen(app: AppCore, onLock: () -> Unit) {
    val theme by app.settings.themeMode.collectAsState()
    val accent by app.settings.accentColor.collectAsState()
    val tor by app.settings.torEnabled.collectAsState()
    val imageMode by app.settings.imageLoadMode.collectAsState()
    val avatarMode by app.settings.avatarMode.collectAsState()
    val blocked by app.settings.blockedPubkeys.collectAsState()
    val hideNsfw by app.settings.hideNsfw.collectAsState()
    val hideWords by app.settings.hideWords.collectAsState()
    val bleepWords by app.settings.bleepWords.collectAsState()
    val account by app.accounts.activeAccount.collectAsState()
    val relayStatuses by app.relayStatuses.collectAsState()
    val transport by app.transportStatus.collectAsState()

    var newRelay by remember { mutableStateOf("") }
    var newBlock by remember { mutableStateOf("") }
    var newHide by remember { mutableStateOf("") }
    var newBleep by remember { mutableStateOf("") }
    var pinEntry by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SETTINGS", style = Type.title, color = colors.text)
            Spacer(Modifier.weight(1f))
            message?.let { Text(it, style = Type.metaDim, color = colors.textDim) }
        }
        Rule()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp)) {

            // ── Appearance ────────────────────────────────────────────────────
            SectionLabel("appearance")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(LIGHT, DARK, CREAM).forEach { m ->
                    BoneButton(m.label, primary = theme == m) { app.settings.setThemeMode(m) }
                }
                Spacer(Modifier.width(10.dp))
                SectionLabel("accent", Modifier.align(Alignment.CenterVertically))
                Spacer(Modifier.width(8.dp))
                listOf("#7FDE6B", "#82C8FF", "#E89A4A", "#D96A5A", "#C79BFF").forEach { hex ->
                    val c = parseHexColor(hex)!!
                    Box(
                        Modifier
                            .width(26.dp)
                            .height(26.dp)
                            .background(c)
                            .clickable { app.settings.setAccentColor(hex) }
                    )
                }
                BoneButton("reset") { app.settings.setAccentColor(null) }
            }
            Spacer(Modifier.height(16.dp))

            // ── Media ─────────────────────────────────────────────────────────
            SectionLabel("media")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("inline images", Modifier.width(120.dp))
                ImageLoadMode.entries.forEach { m ->
                    BoneButton(m.label, primary = imageMode == m) { app.settings.setImageLoadMode(m) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("avatars", Modifier.width(120.dp))
                AvatarMode.entries.forEach { m ->
                    BoneButton(m.label, primary = avatarMode == m) { app.settings.setAvatarMode(m) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Animated (GIF) avatars are not supported on desktop: the JVM image " +
                    "pipeline decodes still frames only, so animation is shown as a still.",
                style = Type.caption, color = colors.textMute,
            )
            Spacer(Modifier.height(16.dp))

            // ── Privacy ───────────────────────────────────────────────────────
            SectionLabel("privacy")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoneButton(if (tor) "tor: on" else "tor: off", primary = tor) {
                    app.settings.setTorEnabled(!tor)
                }
                Spacer(Modifier.width(10.dp))
                BoneButton("probe proxy") {
                    app.scope.launch {
                        val probe = Transport.detectProxy()
                        message = if (probe == null) "no local proxy on 8118/9050/9150"
                        else "found ${probe.kind} on 127.0.0.1:${probe.port}"
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(transport.ifBlank { "transport unknown" }, style = Type.metaDim, color = colors.textDim)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Tor is a *local proxy* setting here: T-Bone Desktop routes relay and media " +
                    "traffic through 127.0.0.1:8118 (HTTP CONNECT, no DNS leak) or 9050/9150 " +
                    "(SOCKS) when a proxy answers. If none answers, traffic stays on the clearnet " +
                    "and this row says so — it never claims to be anonymised when it is not.",
                style = Type.caption, color = colors.textMute,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("app lock pin", Modifier.width(120.dp))
                BoneTextField(pinEntry, { pinEntry = it }, Modifier.width(160.dp), placeholder = "4+ digits")
                Spacer(Modifier.width(8.dp))
                BoneButton("set") {
                    if (pinEntry.length >= 4) {
                        app.settings.setPin(pinEntry)
                        pinEntry = ""
                        message = "PIN set — the lock screen appears on next start and via 'lock'."
                    } else message = "PIN must be at least 4 characters"
                }
                Spacer(Modifier.width(6.dp))
                BoneButton("clear") { app.settings.setPin(null); message = "PIN cleared" }
                Spacer(Modifier.width(6.dp))
                if (app.settings.hasPin()) BoneButton("lock now") { onLock() }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "The PIN is stored as PBKDF2-HMAC-SHA256 (120k iterations, random salt). " +
                    "It gates the UI on this machine; it is not a disk-encryption substitute.",
                style = Type.caption, color = colors.textMute,
            )
            Spacer(Modifier.height(16.dp))

            // ── Content filters ───────────────────────────────────────────────
            SectionLabel("content filters")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoneButton(if (hideNsfw) "nsfw: hidden" else "nsfw: shown", primary = hideNsfw) {
                    app.settings.setHideNsfw(!hideNsfw)
                }
                Spacer(Modifier.width(10.dp))
                Text("hides notes carrying a NIP-36 content-warning tag", style = Type.caption, color = colors.textMute)
            }
            Spacer(Modifier.height(10.dp))
            WordListEditor(
                label = "blocked pubkeys",
                values = blocked,
                placeholder = "npub or hex",
                onAdd = { raw ->
                    val pk = social.tbone.desktop.app.normalisePubkeyInput(raw)
                    if (pk != null) app.settings.block(pk) else message = "not a valid pubkey"
                },
                onRemove = { app.settings.unblock(it) },
            )
            WordListEditor(
                label = "hidden words (note removed)",
                values = hideWords,
                placeholder = "word",
                onAdd = { app.settings.setHideWords(hideWords + it.lowercase()) },
                onRemove = { app.settings.setHideWords(hideWords - it) },
            )
            WordListEditor(
                label = "bleeped words (masked)",
                values = bleepWords,
                placeholder = "word",
                onAdd = { app.settings.setBleepWords(bleepWords + it.lowercase()) },
                onRemove = { app.settings.setBleepWords(bleepWords - it) },
            )
            Spacer(Modifier.height(16.dp))

            // ── Relays ────────────────────────────────────────────────────────
            SectionLabel("relays (nip-65)")
            Spacer(Modifier.height(8.dp))
            account?.relays?.forEach { url ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(6.dp)
                            .height(6.dp)
                            .background(
                                when (relayStatuses[url]) {
                                    social.tbone.core.nostr.relay.RelayStatus.CONNECTED -> colors.accent
                                    social.tbone.core.nostr.relay.RelayStatus.CONNECTING -> colors.warn
                                    social.tbone.core.nostr.relay.RelayStatus.DISCONNECTED -> colors.danger
                                    null -> colors.textMute
                                }
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(url, style = Type.metaDim, color = colors.textDim, modifier = Modifier.weight(1f))
                    BoneButton("remove") {
                        app.scope.launchAction { app.removeRelay(url); Result.success(Unit) }
                    }
                }
            }
            if (account?.relays.isNullOrEmpty()) {
                Text("Using the built-in default relays.", style = Type.caption, color = colors.textMute)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoneTextField(newRelay, { newRelay = it }, Modifier.weight(1f), placeholder = "wss://relay.example")
                Spacer(Modifier.width(8.dp))
                BoneButton("add relay") {
                    if (newRelay.startsWith("wss://") || newRelay.startsWith("ws://")) {
                        app.scope.launchAction { app.addRelay(newRelay.trim()); Result.success(Unit) }
                        newRelay = ""
                    } else message = "relay must start with wss:// or ws://"
                }
                Spacer(Modifier.width(8.dp))
                BoneButton("publish list") {
                    app.scope.launchAction {
                        app.publishRelayList().map { message = "published NIP-65 relay list" }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ── Notifications ─────────────────────────────────────────────────
            SectionLabel("notification types")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotifKind.entries.forEach { k ->
                    val enabled = app.settings.notificationEnabledTypes.value
                    val active = enabled.isEmpty() || k.label in enabled
                    BoneButton(k.label, primary = active) {
                        val next = if (enabled.isEmpty()) NotifKind.entries.map { it.label }.toSet() - k.label
                        else if (k.label in enabled) enabled - k.label else enabled + k.label
                        app.settings.setNotificationEnabledTypes(next)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ── Account / signer ──────────────────────────────────────────────
            SectionLabel("account")
            Spacer(Modifier.height(8.dp))
            if (account == null) {
                Text("No active account.", style = Type.metaDim, color = colors.textMute)
            } else {
                Text("pubkey  ${account!!.pubkey}", style = Type.metaDim, color = colors.textDim)
                Spacer(Modifier.height(4.dp))
                Text(
                    "signer  ${app.signerKindLabel.value ?: "unknown"}  ·  " +
                        if (app.accounts.hasPrivateKey(account!!.pubkey)) "key material present" else "no key material (read-only)",
                    style = Type.metaDim, color = colors.textDim,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    account!!.relays.let { }
                    BoneButton("remove account") {
                        app.scope.launchAction { app.removeAccount(account!!.pubkey); Result.success(Unit) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Amber (NIP-55) accounts cannot sign on desktop — there is no Android " +
                        "intent bridge. Add a local key or nsecBunker account instead.",
                    style = Type.caption, color = colors.textMute,
                )
            }
            Spacer(Modifier.height(16.dp))

            // ── Data ──────────────────────────────────────────────────────────
            SectionLabel("data")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BoneButton("clear image cache") {
                    app.media.clearCache()
                    message = "image cache cleared"
                }
                Spacer(Modifier.width(6.dp))
                Text("${app.media.cacheSizeBytes() / 1024} KB on disk", style = Type.caption, color = colors.textMute)
                Spacer(Modifier.width(12.dp))
                BoneButton("open data folder") { DesktopUx.openInFileManager(app.dataDir) }
                Spacer(Modifier.width(12.dp))
                BoneButton(if (confirmWipe) "click again to WIPE" else "wipe all data") {
                    if (confirmWipe) {
                        app.scope.launchAction { app.wipeAll(); Result.success(Unit) }
                        confirmWipe = false
                        message = "all local data wiped"
                    } else confirmWipe = true
                }
            }
            Spacer(Modifier.height(16.dp))

            // ── About ─────────────────────────────────────────────────────────
            SectionLabel("about")
            Spacer(Modifier.height(8.dp))
            Text(
                "T-Bone Desktop 0.3.36-desktop.1 — a Compose Multiplatform port of T-Bone v0.3.36 " +
                    "(MIT). Upstream lineage: daomah/bony → T-Bone (social.tbone) → this port.",
                style = Type.caption, color = colors.textMute,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Data directory: ${app.dataDir.absolutePath}",
                style = Type.caption, color = colors.textMute,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WordListEditor(
    label: String,
    values: Set<String>,
    placeholder: String,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var entry by remember { mutableStateOf("") }
    Column(Modifier.padding(vertical = 6.dp)) {
        SectionLabel(label)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BoneTextField(entry, { entry = it }, Modifier.width(280.dp), placeholder = placeholder)
            Spacer(Modifier.width(8.dp))
            BoneButton("add") {
                if (entry.isNotBlank()) {
                    onAdd(entry.trim())
                    entry = ""
                }
            }
            Spacer(Modifier.width(12.dp))
            Text("${values.size} entr${if (values.size == 1) "y" else "ies"}", style = Type.caption, color = colors.textMute)
        }
        if (values.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            values.take(12).forEach { v ->
                Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(v, style = Type.caption, color = colors.textDim)
                    Spacer(Modifier.width(8.dp))
                    Text("remove", style = Type.caption, color = colors.danger, modifier = Modifier.clickable { onRemove(v) })
                }
            }
        }
    }
}
