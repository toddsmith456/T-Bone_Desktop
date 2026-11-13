# Optional packaging: AppImage & Flatpak

For users who don't want `.deb`. Same app image, same version (0.3.36), same verification bar:
**both must launch and render the onboarding screen before they are called done.**

## AppImage (x86_64)

    ./gradlew :desktop:createDistributable          # needs JAVA_HOME=JDK 17
    packaging/appimage/build-appimage.sh            # needs appimagetool on PATH

Produces `packaging/appimage/work/t-bone-desktop-0.3.36-x86_64.AppImage`.
Test: `chmod +x *.AppImage && ./t-bone-desktop-0.3.36-x86_64.AppImage`
(requires FUSE; on hosts without it use `--appimage-extract-and-run`).

Runtime requirements for end users: glibc ≥ 2.28, X11 or Wayland, Mesa GL.
AppImages cannot bundle the host's GL driver by definition; the AppImage works
with the host's libGL, exactly like a `.tar.gz` install but with desktop integration.

## Flatpak (x86_64, local bundle)

    # prereqs: flatpak, flatpak-builder; one-time:
    flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
    flatpak install --user flathub org.freedesktop.Platform//24.08 org.freedesktop.Sdk//24.08

    flatpak-builder --user --repo=repo --force-clean --delete-build-dirs \
      build packaging/flatpak/io.github.toddsmith456.tbone-desktop.yaml
    flatpak build-bundle repo io.github.toddsmith456.tbone-desktop.flatpak \
      io.github.toddsmith456.tbone-desktop

Test: `flatpak install --user ./io.github.toddsmith456.tbone-desktop.flatpak && flatpak run io.github.toddsmith456.tbone-desktop`

Permissions (in the manifest, `finish-args`): network (Nostr relays), Wayland /
fallback X11, `dri` for Skiko/GL, read access to `xdg-download` for nsec export.
Data lives in the sandbox home: `~/.var/app/io.github.toddsmith456.tbone-desktop/config/t-bone-desktop`.

### Honest limitations

- **Not on Flathub.** Publishing there requires their review process (build from
  source, runtime-version ≥ currently-supported, screenshots/metainfo), and it
  has not been submitted. This builds a *local* single-file bundle.
- **Freedesktop 24.08 is EOL** as of 2026; a Flathub submission must move to a
  supported runtime once one ships. Works fine locally today.
- **ARM64 not covered here** — AppImage/Flatpak for arm64 would need an arm64
  host (same constraint as jpackage).
- The Flatpak ships the same prebuilt jpackage app image; Flathub wants an
  in-sandbox build from source, which the current Gradle build (needs ~1.5 GB
  RAM for jlink) may or may not survive under Flathub's builders.
