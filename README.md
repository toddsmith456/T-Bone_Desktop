# T-Bone Desktop

A desktop port of **T-Bone** — a privacy-first Nostr client — built with Compose
Multiplatform for Linux (`.deb`), from the Android source at
[v0.3.36 (`9c350e3c`)](https://github.com/5ToddSmith5/T-Bone-).

No telemetry, no analytics, no accounts on anyone's servers. Keys are generated and
kept on your machine.

## What this is

This repository is a **port**, not a mock-up: the Nostr protocol layer, signing, key
handling, encrypted storage and relay pool are real implementations that talk to real
relays, and there are tests for each of those layers. It is also **not finished**, and
the places where it differs from the Android app — or does not do something at all —
are documented rather than papered over. Start with
[`docs/PARITY.md`](docs/PARITY.md).

## Current state (honest)

| Area | State |
|---|---|
| Compiles | `./gradlew :desktop:compileKotlin` — clean |
| Tests | **55 tests, 0 failures** across protocol, signing, key handling, persistence, relay framing, PIN hashing |
| Package | `.deb` builds, installs (`dpkg -i` exits 0) and launches; verified end-to-end |
| Live network | Connects to public relays, reads the global feed, publishes signed notes, reports relay `OK`/rejection |
| Onboarding | Works: create local key / import nsec / nsecBunker; Amber card disabled with reason |
| Notes | Encrypted at rest (AES-256-GCM); verified that note plaintext does not appear in the DB or WAL on disk |
| Themes | LIGHT / DARK / CREAM + accent colour, all repainting correctly |

Known gaps are listed at the end of `docs/PARITY.md` — including DM conversation UI,
relay-list editing, zap sending, unverified NIP-46 against a live bunker, and the
absence of a bundled Tor.

## Build, install, run

```bash
./gradlew :desktop:test
./gradlew :desktop:packageDeb
sudo dpkg -i desktop/build/compose/binaries/main/deb/t-bone-desktop_0.3.36-1_amd64.deb
t-bone-desktop
```

Full prerequisites, the data-directory layout, and the packaging pitfalls (a missing
`java.sql` module in the runtime image, a non-ASCII metadata string, a host without a
desktop menu tree) are in [`docs/BUILD.md`](docs/BUILD.md).

Alpine/musl cannot run the native package — the renderer's native library is glibc-only
— so the supported path there is an OCI container. See
[`docs/ALPINE.md`](docs/ALPINE.md).

## Repository layout

```
desktop/src/main/kotlin/social/tbone/
  core/                    # ported domain layer (no UI, no Compose)
    nostr/                 # NIP-01 events, BIP-340 Schnorr, NIP-19, NIP-44, filters
      relay/               # relay pool, wire protocol, NIP-42 auth
    signer/                # local key signer, nsecBunker (NIP-46)
    store/                 # SQLite + encrypted at-rest storage, notes, events, accounts
    settings/              # settings, content filters, proxy probing
    net/                   # transports (clearnet / local proxy)
  desktop/
    app/                   # AppCore object graph, feed/thread/profile/media services
    ui/                    # Compose screens, theme, components
  Main.kt                  # entry point, window, PIN lock gate
desktop/src/test/kotlin/…  # JUnit 4 suites
docs/                      # PARITY.md, BUILD.md, ALPINE.md
```

Each ported file names the upstream file it came from, and any structural change
explains itself at the change site.

## Attribution and licence

This is a derivative work.

- **Upstream: T-Bone** — MIT, Copyright (c) 2026 daomah.
  Source: `https://github.com/5ToddSmith5/T-Bone-`, ported from tag/commit
  `9c350e3cc0b68818f62cb2360176d4f9957df981` (v0.3.36).
  Lineage per upstream's own `FORK.md`: `daomah/bony` → `T-Bone` → this port.
- **This port**: also MIT. See [`LICENSE`](LICENSE), which is upstream's licence text,
  unchanged and retained.
- **Bundled fonts**: JetBrains Mono NL, SIL Open Font License 1.1, as shipped by
  upstream (`desktop/src/main/resources/jetbrains_mono_*.ttf`).
- **Libraries**: BouncyCastle (MIT-style), OkHttp (Apache-2.0), kotlinx-serialization
  and kotlinx-coroutines (Apache-2.0), SQLite JDBC (Apache-2.0), Compose Multiplatform
  and Skiko (Apache-2.0).

The upstream source was used read-only as a reference; it was not modified. Nothing in
this repository claims to be the upstream project, and the app identifies itself as
`T-Bone Desktop 0.3.36`, distinct from the Android app.

## Security notes

- The private key is stored AES-256-GCM-encrypted in a `0600` file in the app data
  directory. **This is weaker than the Android build's hardware Keystore** and the app
  says so where it matters. It is stated in the onboarding step and on the Settings
  screen.
- The PIN lock is a UI gate (PBKDF2-HMAC-SHA256, 120k iterations, random salt), not a
  disk-encryption substitute. The duress PIN wipes local data instead of unlocking.
- "Tor" is a setting that routes through a proxy **you** run, with a probe that reports
  honestly when no proxy is listening. Traffic is never silently downgraded and
  anonymity is never claimed when it is not in effect.

## Reporting

Bugs and gaps are best reported against this repository. The app writes UTF-8 logs to
`<data dir>/logs/`, which is the file to attach.
