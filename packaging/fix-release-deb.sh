#!/bin/bash
# Post-process a jpackage-built .deb so the desktop integration actually works.
# jpackage hard-wires a broken desktop file (`Categories=Unknown`), copies it
# into the menu only via postinst (`xdg-desktop-menu install`, which exits 3
# on systems without a writable menu dir and fails the dpkg configure step),
# ships no hicolor icon, declares a Maintainer string with duplicated angle
# brackets, and depends on `libpng16-16` — a name that does not exist on
# Ubuntu 24.04 / Debian 13 (it is libpng16-16t64).
#
# This script rewrites those pieces and leaves everything else untouched:
#   - valid desktop entry (Categories=Network;Chat, StartupNotify) shipped as a
#     dpkg-owned file under /usr/share/applications — no postinst copy needed
#   - icon installed into the hicolor theme
#   - embedded /opt desktop file replaced with the valid one too
#   - postinst/prerm/postrm become no-ops that may never fail dpkg configure
#   - Depends: t64-tolerant libpng alternative; adds libegl1 libopengl0 desktop-file-utils
#
# Usage: fix-release-deb.sh <package.deb>
set -euo pipefail

DEB="$1"
command -v dpkg-deb >/dev/null || { echo "dpkg-deb required"; exit 2; }
if command -v magick >/dev/null; then IM=magick;
elif command -v convert >/dev/null; then IM=convert;
else echo "ImageMagick (magick or convert) required"; exit 2; fi

WORK="$(mktemp -d /home/user/.fixdeb.XXXXXX 2>/dev/null || mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
dpkg-deb -R "$DEB" "$WORK"

# --- desktop entry + hicolor icon -----------------------------------------
mkdir -p "$WORK/usr/share/applications" "$WORK/usr/share/icons/hicolor/256x256/apps"
"$IM" "$WORK/opt/t-bone-desktop/lib/t-bone-desktop.png" -resize 256x256 \
  "$WORK/usr/share/icons/hicolor/256x256/apps/t-bone-desktop.png"

cat > "$WORK/usr/share/applications/t-bone-desktop.desktop" <<'DESKTOP'
[Desktop Entry]
Version=1.0
Type=Application
Name=T-Bone Desktop
Comment=Privacy-first Nostr client (desktop port of T-Bone)
Exec=/opt/t-bone-desktop/bin/t-bone-desktop
Icon=t-bone-desktop
Terminal=false
Categories=Network;Chat;
Keywords=nostr;privacy;
StartupNotify=true
DESKTOP

# keep the embedded jpackage-generated file valid too (it is what xdg-desktop-menu
# consumes, and what desktop-file-validate failures cascade from)
cat > "$WORK/opt/t-bone-desktop/lib/t-bone-desktop-t-bone-desktop.desktop" <<'DESKTOP'
[Desktop Entry]
Version=1.0
Type=Application
Name=T-Bone Desktop
Comment=T-Bone Desktop: privacy-first Nostr client
Exec=/opt/t-bone-desktop/bin/t-bone-desktop
Icon=/opt/t-bone-desktop/lib/t-bone-desktop.png
Terminal=false
Categories=Network;Chat;
StartupNotify=true
DESKTOP

# --- robust maintainer scripts ---------------------------------------------
cat > "$WORK/DEBIAN/postinst" <<'SH'
#!/bin/sh
set -e
# Menu entry + icon ship as dpkg-owned files; only refresh caches. Never fail configure.
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database -q /usr/share/applications 2>/dev/null || true
fi
exit 0
SH
cat > "$WORK/DEBIAN/prerm" <<'SH'
#!/bin/sh
set -e
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database -q /usr/share/applications 2>/dev/null || true
fi
exit 0
SH
cat > "$WORK/DEBIAN/postrm" <<'SH'
#!/bin/sh
set -e
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database -q /usr/share/applications 2>/dev/null || true
fi
exit 0
SH
chmod 755 "$WORK/DEBIAN/postinst" "$WORK/DEBIAN/prerm" "$WORK/DEBIAN/postrm"

# --- control file ----------------------------------------------------------
CTRL="$WORK/DEBIAN/control"
python3 - "$CTRL" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
s = s.replace(
    'Maintainer: T-Bone Desktop contributors <toddsmith456 <toddsmith456@users.noreply.github.com>>',
    'Maintainer: T-Bone Desktop contributors <toddsmith456@users.noreply.github.com>')
s = s.replace('libpng16-16,', 'libpng16-16t64 | libpng16-16, libegl1, libopengl0, desktop-file-utils,')
s = s.replace('libpng16-16 ', 'libpng16-16t64 | libpng16-16, libegl1, libopengl0, desktop-file-utils ')
open(p, 'w').write(s)
PY

SIZE=$(du -sk --exclude=DEBIAN "$WORK" | cut -f1)
sed -i "s/^Installed-Size:.*/Installed-Size: ${SIZE}/" "$CTRL"

OUT="$DEB.fixed.deb"
dpkg-deb --build --root-owner-group "$WORK" "$OUT" >/dev/null
mv -f "$OUT" "$DEB"
echo "fixed: $DEB (Installed-Size: ${SIZE}K)"
