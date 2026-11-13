# Building and installing T-Bone Desktop

Verified commands, in the order a clean machine would run them. Every result quoted
below was produced in this repository, not projected.

## Prerequisites

- **JDK 17** (tested: Eclipse Temurin 17.0.21+9). The build compiles with
  `jvmToolchain(17)`.
- **Gradle 8.7**, or the bundled wrapper (`./gradlew`).
- For packaging a `.deb` only: `fakeroot` and `dpkg-deb` (Compose Multiplatform's
  packaging task shells out to them).
- To install the resulting `.deb` on a minimal container: `menu` and
  `desktop-file-utils` (and a `/usr/share/desktop-directories` directory). See
  "Known install-time snag" below.

Nothing else is required to build; the app has no native dependencies beyond what
Skiko ships.

## Build

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew :desktop:compileKotlin        # compile
./gradlew :desktop:test                 # unit tests
./gradlew :desktop:packageDeb           # build the .deb
```

The `.deb` lands in:

```
desktop/build/compose/binaries/main/deb/t-bone-desktop_0.3.36-1_amd64.deb
```

Measured on Debian 13 / x86-64: 108 MB, of which ~193 MB is the unpacked
application image with its bundled JDK runtime. `Installed-Size: 193511` (KiB).

### Running from the build tree (no packaging)

Useful when iterating, because it skips `jpackage`:

```bash
./gradlew :desktop:printRuntimeClasspath
java -Dskiko.renderApi=SOFTWARE \
     -cp "$(cat desktop/build/runtime-classpath.txt)" \
     social.tbone.desktop.MainKt
```

`-Dskiko.renderApi=SOFTWARE` is only needed where there is no GPU (containers, CI,
Xvfb). On a normal desktop, omit it and Skiko will pick a hardware backend.

## Install

```bash
sudo dpkg -i t-bone-desktop_0.3.36-1_amd64.deb
t-bone-desktop          # or launch from the application menu
```

Verified on a clean Debian 13 container: `dpkg -i` exits **0**, the runtime image
contains `java.sql`, and the app reaches its onboarding screen with the bundled
JetBrains Mono faces loaded.

### Where the app puts your data

Resolution order:

1. `-Dtbone.dataDir=/some/dir`
2. `$XDG_CONFIG_HOME/t-bone-desktop` (else `~/.config/t-bone-desktop`)

Contents: `tbone.db` (SQLite + WAL), `storage.key` (AES-256-GCM key, mode `0600`),
and `logs/`. To move or back up an account, copy the whole directory — the key file
is what makes the encrypted columns readable.

### Known install-time snag (not a package defect)

If `dpkg -i` reports:

```
xdg-desktop-menu: No writable system menu directory found.
dpkg: error processing package t-bone-desktop (--install):
 installed t-bone-desktop package post-installation script subprocess returned error exit status 3
```

…the package files are already installed; only menu registration failed, because the
host has no desktop menu tree. `xdg-desktop-menu` expects:

- `/etc/xdg/menus/` with a menu file (Debian's `menu` package provides
  `/var/lib/menu-xdg/menus/debian-menu.menu`), and
- `/usr/share/desktop-directories/`

On a normal desktop distribution both exist, and the install exits 0 — which is what
this build was finally verified against after installing `menu` +
`desktop-file-utils`. The launcher itself is installed to
`/usr/share/applications/` and `/opt/t-bone-desktop/bin/t-bone-desktop` regardless, so
the app is usable even on a headless host that trips this check.

## Prebuilt packages

Tagging `v*` runs `.github/workflows/release.yml`, which builds and publishes:

| Asset | Architecture |
|---|---|
| `t-bone-desktop_<ver>-1_amd64.deb` | x86_64 Linux |
| `t-bone-desktop_<ver>-1_arm64.deb` | ARM64 Linux |
| `t-bone-desktop-<ver>-1.x86_64.rpm` | x86_64 Linux |
| `t-bone-desktop_<ver>-linux-x86_64.tar.gz` | x86_64, no install needed |

ARM64 has to be built on an arm64 runner — `jpackage` cannot cross-compile, so
there is no flag that produces an arm64 package from an x86_64 host.

Windows and macOS are **not** built. `jpackage` can only target its own OS, and
neither has been verified for this project.

## Packaging notes that cost real time (kept so they are not rediscovered)

- **`modules(...)` must be pinned.** `jpackage` inferred a runtime image containing
  only `java.desktop`, so the installed app died at startup with
  `NoClassDefFoundError: java/sql/DriverManager` — the SQLite JDBC driver is loaded
  reflectively, so inference cannot see it. `desktop/build.gradle.kts` now lists the
  modules explicitly. If you add a dependency with reflective module use, extend that
  list.
- **Keep package metadata ASCII.** An em dash in `description` made `jpackage` fail
  with `Input length = 1` under the `POSIX` locale it runs in.
- **Build with `LANG=C.UTF-8`** for the same reason.
- **Small machines**: `gradle.properties` pins `-Xmx1200m` for Gradle and `-Xmx700m`
  for the Kotlin daemon. On a 2 GB container, running the app *and* Gradle at the same
  time gets the daemon OOM-killed mid-task (which shows up as the unhelpful
  "Gradle build daemon disappeared unexpectedly"). Close the app before packaging.
