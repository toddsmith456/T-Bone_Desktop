#!/bin/sh
# Build T-Bone Desktop as an AppImage (x86_64).
# Prereq: jpackage app image exists at desktop/build/compose/binaries/main/app/t-bone-desktop
#         (./gradlew :desktop:createDistributable)
#         appimagetool on PATH (github.com/AppImage/appimagetool)
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/desktop/build/compose/binaries/main/app/t-bone-desktop"
WORK="${1:-$ROOT/packaging/appimage/work}"
APP="$WORK/t-bone-desktop.AppDir"
rm -rf "$APP"; mkdir -p "$APP/opt"
cp -a "$SRC" "$APP/opt/t-bone-desktop"
cp "$SRC/lib/t-bone-desktop.png" "$APP/t-bone-desktop.png"
cat > "$APP/AppRun" <<'RUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/opt/t-bone-desktop/bin/t-bone-desktop" "$@"
RUN
chmod +x "$APP/AppRun"
cat > "$APP/t-bone-desktop.desktop" <<'DESK'
[Desktop Entry]
Type=Application
Name=T-Bone Desktop
Comment=Privacy-first Nostr client (desktop port of T-Bone)
Exec=t-bone-desktop
Icon=t-bone-desktop
Terminal=false
Categories=Network;Chat;InstantMessaging;
Keywords=nostr;privacy;
DESK
appimagetool "$APP" "$WORK/t-bone-desktop-0.3.36-x86_64.AppImage"
