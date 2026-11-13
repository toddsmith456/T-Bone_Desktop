# Parity and deviations: T-Bone Desktop vs. T-Bone v0.3.36 (upstream `9c350e3c`)

This file exists because "parity" is a claim that has to be checkable. Below is what
was ported directly, what was re-implemented because the Android mechanism does not
exist on a Linux desktop, and what is **not** implemented. Anything in the second and
third groups is also surfaced in the app itself, in user-visible text, rather than
left for the user to discover.

Baseline: `9c350e3cc0b68818f62cb2360176d4f9957df981` (T-Bone v0.3.36). The upstream
tree was not modified; it is used read-only as the reference implementation.

## Where the logic lives

Upstream's domain code was adapted rather than called across a boundary. Each desktop
source file names the upstream file it came from, and the *reason* for any structural
change is in a comment at the change site.

| Area | Upstream (Android) | Desktop | Nature of the port |
|---|---|---|---|
| Event model / NIP-01 serialisation | `nostr/Event.kt` | `core/nostr/Event.kt` | Direct port; added a regression fix in the verified-event cache (below) |
| secp256k1 / BIP-340 | BouncyCastle + `nostr/Crypto.kt` | `core/nostr/Crypto.kt` | Direct port, same library |
| NIP-19 bech32 + TLV | `nostr/Nip19.kt` | `core/nostr/Nip19.kt` | Direct port; TLV parsing bug fixed (below) |
| NIP-44 v2 | `nostr/Nip44.kt` | `core/nostr/Nip44.kt` | Direct port |
| Relay pool / NIP-42 | `nostr/relay/*` | `core/nostr/relay/*` | Direct port; OkHttp instead of OkHttp-on-Android |
| Local signer | `signer/LocalKeySigner` (Keystore) | `core/signer/LocalKeySigner` (file key) | **Mechanism replaced** — see "Key storage" |
| NsecBunker / NIP-46 | `signer/NsecBunkerSigner` | `core/signer/NsecBunkerSigner` | Direct port (needs network, not exercised by unit tests) |
| Notes (encrypted) | Room + `NotesCrypto` | `core/store/NotesStore` + SQLite JDBC | **Mechanism replaced**, same crypto shape |
| Settings | DataStore<Preferences> | `core/settings/AppSettings` over a `prefs` table | Mechanism replaced, same keys/semantics |
| Theme / palette | Compose `BonyColors` global mutable | `ui/Theme.kt` `ThemeColors` via CompositionLocal | Mechanism replaced; values identical |

## Key storage (the biggest honest difference)

| | Upstream (Android) | Desktop |
|---|---|---|
| Private key at rest | Android Keystore, hardware-backed where available | AES-256-GCM blob in `storage.key`, mode `0600`, in the app data directory |
| Key material exposure to a local attacker with your user account | Keystore-gated | readable if the process runs as you and can read your files |
| Resistance to disk theft / backup exfiltration | Keystore is not in the app sandbox | `0600` file, so a stolen disk without your login is not enough, but it is not hardware-backed |

This is a real reduction in protection and it is stated in the app, on the Settings
screen and in the onboarding step where the key is created, in those words. There is
no Linux equivalent of the Android Keystore available to a JVM desktop application
without a TPM integration that would have to be written, tested, and shipped per
distribution — so the port does not pretend to have one.

What is preserved: the plaintext private key is never written to disk or to logs, is
only decrypted into a short-lived array that is zeroed after use, and the signer
refuses to sign after `wipe()`. Tests cover all of that
(`KeyHandlingTest`, `LocalCrypto` self-test).

## Features that cannot exist on the desktop target

| Feature | Status | What the user sees |
|---|---|---|
| **Amber (NIP-55)** external signer | Not possible — it is an Android intent/IPC contract with no desktop peer | Onboarding card present but disabled, labelled `NOT AVAILABLE`; Settings repeats it |
| **Push notifications** (FCM | UnifiedPush) | Not implemented | In-app notification bell only; the Notifications screen says activity is "in-app only" and that nothing is sent to a notification service |
| **`Orbot` integration** | Not portable — Orbot is an Android app exposing a local SOCKS port | Settings has a real `PROBE PROXY` action that tests `127.0.0.1:8118` / `9050` / `9150` and reports the truth, including "no local proxy" when none answers. Traffic is **not** silently routed or silently dropped |
| Animated (GIF) avatars | JVM image pipeline decodes a single frame | Settings states animation degrades to a still |
| Followers count | Upstream composes it from local state + relay queries | Shown as `?` when unknown rather than inventing a number |

## Deliberate behavioural additions (not in upstream)

- **PIN lock**: upstream's lock screen is Android-lifecycle driven (lock on
  background). A desktop window has no equivalent lifecycle event, so the port locks
  on launch and via an explicit sidebar control. PIN storage was upgraded from a
  salted SHA-256 to PBKDF2-HMAC-SHA256 (120k iterations, random salt); the reasoning
  and the honest scope ("gates the UI on this machine, not a disk-encryption
  substitute") are on the Settings screen, not just in this file.
- **Duress PIN**: entering it wipes local data instead of unlocking. This mirrors
  upstream's intent while making the desktop trigger path explicit.

## Bugs found and fixed during the port (documented because they change behaviour)

1. **Verified-event cache could launder a forged signature.** The cache was keyed by
   event id and consulted *before* checking the signature, so any event reusing an
   id that had been verified once was reported valid with any signature at all. It is
   now keyed by `(id, sig)` and only short-circuits verification when the presented
   signature is the one that was actually checked. Covered by
   `CryptoEventTest.forgedSignatureIsRejectedEvenWhenTheIdIsAlreadyVerified`.
2. **NIP-19 `nevent` / `nprofile` never resolved.** The TLV walker advanced past the
   type byte before reading the length, so the type-0 entry was never matched and
   every `nevent1…` / `nprofile1…` input returned null. Fixed and covered by
   `Nip19Test` (including relay-hint and author entries, and a truncated payload).
3. **Theme switching left stale colours.** The palette was provided through
   `staticCompositionLocalOf`, which does not propagate updates, so switching
   DARK → CREAM repainted the shell but left earlier panes in the old palette. Now
   `compositionLocalOf`.
4. **Schema version was written under a second key name** (`schemaVersion` vs the
   queried `schema_version`), so version detection always came back empty.
5. **Publish results were discarded.** `["OK", <id>, <accepted>, <message>]` frames were
   parsed and multiplexed, but no code consumed them, so a note a relay *rejected*
   (blocked, auth-required, rate-limited) looked identical to one it accepted. Relay
   acknowledgements are now tracked per event id and relay URL, logged, and shown in
   the composer as "accepted by N relays" or the rejection reason in full.
6. **The composer advertised `ctrl+enter` but nothing was bound to it.** The label was
   present, the shortcut was not. Now wired to the same submit path as the POST button.
7. **Log files were written in the platform charset.** `FileWriter` defaulted to ASCII
   under the container locale, so every non-ASCII character in a log line (an ellipsis
   in a truncated id, a note's Japanese content) became `?` in the file users are asked
   to attach to bug reports. Now explicit UTF-8.
8. **A missing font bundle crashed the app with a raw AWT dialog.** If `resources/main`
   was absent from the classpath, Skiko threw during the first measure pass and the user
   got an unactionable error box. The faces are now probed first; the app falls back to
   the platform monospace family and logs a warning instead of dying.
9. **`jpackage` shipped a runtime image without `java.sql`**, so the *installed* app
   died at startup (`NoClassDefFoundError: java/sql/DriverManager`) while the dev
   build-tree run worked fine. Fixed by pinning the JDK module set — this one is worth
   flagging because it only ever appears in the packaged artifact.

## Known gaps (not fixed, listed so they are not mistaken for working)

- **Encrypted DM history** is stored but the desktop UI has no conversation list yet;
  only the thread/notification surfaces that reference DMs.
- **Relay list management** is read from the settings store; there is no editor UI for
  adding/removing relays beyond the settings surface's preset list.
- **Zaps**: NIP-57 receipts are recognised and displayed as counts; there is no zap
  sending flow (no wallet integration), which matches upstream's read-only treatment
  but should be stated plainly.
- **NIP-46 (NsecBunker)** signer compiles and implements the protocol, but was not
  exercised against a live bunker in this environment (no test bunker available).
  Treat it as unverified rather than working.
- **Tor**: no bundled Tor process and no onion-only mode. The proxy setting only
  *uses* a proxy you already run.
- **Packaging** was validated for `.deb` on Debian-13-derived hosts; RPM/AppImage/MSI
  are not built.
