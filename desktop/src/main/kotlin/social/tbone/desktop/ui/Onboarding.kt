package social.tbone.desktop.ui

import kotlinx.coroutines.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import social.tbone.core.nostr.Nip19
import social.tbone.core.nostr.toHex
import social.tbone.desktop.app.AppCore
import social.tbone.desktop.app.copyToClipboard
import social.tbone.desktop.app.launchAction

/**
 * First-run / identity screen — port of upstream's `OnboardingScreen`.
 *
 * Upstream offered three ways in: generate a local key, connect Amber (NIP-55),
 * or connect an nsecBunker (NIP-46). On desktop Amber is impossible, so it is
 * replaced by a fourth: **import an nsec**, which is how desktop Nostr clients
 * usually start. Generating a key shows the nsec exactly once with a copy action
 * and a warning — the same "you will not see this again" contract, made explicit
 * because on desktop there is no Keystore to lean on.
 */
@Composable
fun OnboardingScreen(app: AppCore) {
    var mode by remember { mutableStateOf<Mode>(Mode.CHOOSE) }
    var bunkerUrl by remember { mutableStateOf("") }
    var nsecInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var generatedNsec by remember { mutableStateOf<String?>(null) }
    var generatedPubkey by remember { mutableStateOf<String?>(null) }

    // Deliberately a full-window layer rather than a Dialog: a Dialog on the JVM
    // renders in its own undecorated window, and a modal window with no close
    // affordance is worse UX than an in-window step. It is also what makes the
    // first-run flow play nicely with a tiling window manager.
    Box(
        Modifier.fillMaxSize().background(colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(640.dp)
                .background(colors.bg)
                .border(1.dp, colors.ruleStrong, RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState())
                .padding(28.dp)
        ) {
            Text("T-BONE", style = Type.wordmark, color = colors.text)
            Text("DESKTOP · PRIVACY-FIRST NOSTR", style = Type.caption, color = colors.textMute)
            Spacer(Modifier.height(6.dp))
            Text(
                "No Google services, no tracking, no accounts on our servers. Your keys stay " +
                    "on this machine, encrypted at rest.",
                style = Type.metaDim, color = colors.textDim,
            )
            Spacer(Modifier.height(18.dp))
            Rule()

            when (mode) {
                Mode.CHOOSE -> {
                    Spacer(Modifier.height(16.dp))
                    Text("choose how to sign", style = Type.title, color = colors.text)
                    Spacer(Modifier.height(12.dp))
                    ChoiceCard(
                        title = "CREATE A LOCAL KEY",
                        body = "Generates a secp256k1 key in this app. Stored AES-256-GCM " +
                            "encrypted with a key file in the app data directory (0600). " +
                            "You will see the nsec once — write it down.",
                        primary = true,
                    ) {
                        busy = true
                        app.scope.launch {
                            try {
                                val (account, signer) = app.createLocalAccount()
                                // Recover the nsec for the one-time display: the signer holds
                                // the encrypted blob, so decrypt via the same vault path.
                                generatedPubkey = account.pubkey
                                generatedNsec = app.exportNsecOnce(account.pubkey)
                                mode = Mode.SHOW_KEY
                            } catch (t: Throwable) {
                                error = t.message
                            } finally {
                                busy = false
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    ChoiceCard(
                        title = "IMPORT AN NSEC",
                        body = "Paste an existing nsec1… private key. It is encrypted at rest " +
                            "immediately and never written to disk in the clear.",
                    ) { mode = Mode.IMPORT }
                    Spacer(Modifier.height(10.dp))
                    ChoiceCard(
                        title = "CONNECT AN NSECBUNKER (NIP-46)",
                        body = "Keep the private key in a remote signer. This app asks the " +
                            "bunker to sign over an encrypted relay channel; the key never comes here.",
                    ) { mode = Mode.BUNKER }
                    Spacer(Modifier.height(10.dp))
                    ChoiceCard(
                        title = "AMBER (NIP-55) — NOT AVAILABLE",
                        body = "Amber is an Android app-to-app signer. Desktop has no intent " +
                            "bridge, so this option is deliberately absent rather than half-working.",
                        enabled = false,
                    ) { }
                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = Type.metaDim, color = colors.danger)
                    }
                }

                Mode.SHOW_KEY -> {
                    Spacer(Modifier.height(16.dp))
                    Text("your private key (shown once)", style = Type.title, color = colors.text)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Anyone with this nsec controls your identity. Store it in a password " +
                            "manager now — it will not be displayed again.",
                        style = Type.metaDim, color = colors.warn,
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .border(1.dp, colors.rule, RoundedCornerShape(4.dp))
                            .padding(12.dp)
                    ) {
                        Text(generatedNsec ?: "(unavailable — re-import the key to recover it)", style = Type.metaDim, color = colors.text)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "npub: ${generatedPubkey?.let { Nip19.hexToNpub(it) } ?: ""}",
                        style = Type.caption, color = colors.textDim,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BoneButton("copy nsec") { generatedNsec?.let { copyToClipboard(it) } }
                        BoneButton("i saved it — continue", primary = true) {
                            generatedNsec = null
                            mode = Mode.DONE
                        }
                    }
                }

                Mode.IMPORT -> {
                    Spacer(Modifier.height(16.dp))
                    Text("import an nsec", style = Type.title, color = colors.text)
                    Spacer(Modifier.height(10.dp))
                    BoneTextField(
                        nsecInput, { nsecInput = it },
                        Modifier.fillMaxWidth(),
                        placeholder = "nsec1…",
                    )
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = Type.metaDim, color = colors.danger)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BoneButton("import", primary = true, enabled = !busy) {
                            busy = true
                            app.scope.launch {
                                app.importNsec(nsecInput.trim())
                                    .onSuccess { mode = Mode.DONE }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        }
                        BoneButton("back") { error = null; mode = Mode.CHOOSE }
                    }
                }

                Mode.BUNKER -> {
                    Spacer(Modifier.height(16.dp))
                    Text("connect an nsecbunker", style = Type.title, color = colors.text)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Paste the bunker:// connection string from your signer " +
                            "(it contains the remote pubkey, relay and optional secret).",
                        style = Type.metaDim, color = colors.textDim,
                    )
                    Spacer(Modifier.height(10.dp))
                    BoneTextField(
                        bunkerUrl, { bunkerUrl = it },
                        Modifier.fillMaxWidth(),
                        placeholder = "bunker://<remote-pubkey>?relay=wss://…&secret=…",
                    )
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = Type.metaDim, color = colors.danger)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BoneButton(if (busy) "connecting…" else "connect", primary = true, enabled = !busy) {
                            busy = true
                            app.scope.launch {
                                app.addBunkerAccount(bunkerUrl.trim())
                                    .onSuccess { mode = Mode.DONE }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        }
                        BoneButton("back") { error = null; mode = Mode.CHOOSE }
                    }
                }

                Mode.DONE -> {
                    Spacer(Modifier.height(16.dp))
                    Text("you're in", style = Type.title, color = colors.accent)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Relays are connecting. The feed fills as your follow list and notes arrive.",
                        style = Type.metaDim, color = colors.textDim,
                    )
                    Spacer(Modifier.height(14.dp))
                    BoneButton("continue", primary = true) { app.dismissOnboarding() }
                }
            }
            Spacer(Modifier.height(18.dp))
            Rule()
            Spacer(Modifier.height(10.dp))
            Text(
                "MIT licensed · upstream T-Bone v0.3.36 · no telemetry of any kind",
                style = Type.caption, color = colors.textMute,
            )
        }
    }
}

private enum class Mode { CHOOSE, SHOW_KEY, IMPORT, BUNKER, DONE }

@Composable
private fun ChoiceCard(
    title: String,
    body: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (primary && enabled) colors.accentBg else colors.surface)
            .border(
                1.dp,
                when {
                    !enabled -> colors.rule
                    primary -> colors.accent
                    else -> colors.ruleStrong
                },
                RoundedCornerShape(4.dp),
            )
            .then(
                if (enabled) Modifier.clickableNoIndication(onClick) else Modifier
            )
            .padding(14.dp)
    ) {
        Text(
            title,
            style = Type.button,
            color = when {
                !enabled -> colors.textMute
                primary -> colors.accent
                else -> colors.text
            },
        )
        Spacer(Modifier.height(5.dp))
        Text(body, style = Type.metaDim, color = colors.textMute)
    }
}

private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

