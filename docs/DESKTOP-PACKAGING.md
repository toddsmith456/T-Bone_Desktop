# Debian package desktop-entry defects (release v0.3.36-desktop.1)

Found because the arm64 .deb installed on a real machine but the app never
became launchable. Both release debs share the defects.

## Symptoms

- Double-clicking the .deb installs nothing / package ends up
  *not configured* — `dpkg -l t-bone-desktop` shows `iU`/`iH`/`iF` instead of `ii`
- No launcher entry appears; nothing happens when the user tries to open the app
- Or the entry appears and the app dies silently (desktop launches give no stderr)

## Root causes (verified by inspection of the release packages)

1. **postinst runs `xdg-desktop-menu install …`** which exits with
   status **3** ("No writable system menu directory found.") on minimal or
   non-standard systems — dpkg then refuses to configure the package.
   The menu entry is created only via this postinst side effect; the
   package ships **no** file under `/usr/share/applications` itself.
2. **The shipped `.desktop` file is invalid**: `Categories=Unknown`
   (jpackage default). `desktop-file-validate` reports
   `error: value "Unknown" for key "Categories"` (rc=1) and
   `desktop-file-install` rc=1 ("Failed to validate the created desktop
   file"). `xdg-desktop-menu` itself does not validate, but any
   validation-enforcing tooling does.
3. **Malformed `Maintainer:`** (`T-Bone Desktop contributors <toddsmith456
   <toddsmith456@users.noreply.github.com>>` — duplicated angle brackets).
4. **`Depends: libpng16-16`** — that package name does not exist on
   Ubuntu 24.04 / Debian 13 (it is `libpng16-16t64`); on those systems
   `apt install ./…deb` cannot satisfy the dependency.
5. **No icon installed into the hicolor theme** and no `libpng/EGL GLVND`
   dispatch libs declared (`libegl1`, `libopengl0`), which newer Mesa
   stacks need for Skiko.

## Verified not to be the problem

- ELF interpreter & shared deps of the launcher and `libjvm.so`
  resolve under qemu-user-static with plain `libc6:arm64` (launcher is
  `ELF … ARM aarch64`, deps: libdl, libc; libjvm: libm, libc only).
- `Depends:` already includes Mesa GL (`libgl1`, `libglvnd0`, `libglx0`),
  the X libs (`libx11-6`, `libxext6`, `libxi6`, `libxrender1`, `libxtst6`),
  `libasound2`, fontconfig and freetype — Skiko and AWT run with them
  (arm64 rendering of the onboarding screen was verified under qemu with
  those libs installed).

## Fixes shipped

`t-bone-desktop_0.3.36-1_arm64_fixed.deb` and
`t-bone-desktop_0.3.36-1_amd64-fixed.deb` (see release notes /
`packaging/README.md`):

- valid `Categories=Network;Chat;` desktop entries shipped **as dpkg-owned
  files** in `/usr/share/applications` (no postinst-side copy)
- icon installed into `/usr/share/icons/hicolor/256x256/apps/`
- postinst/prerm are no-ops that may never fail (`update-desktop-database || true`)
- `libpng16-16t64 | libpng16-16` alternative dependency
- adds `libegl1`, `libopengl0`, `desktop-file-utils`
- maintainer string fixed

CI should apply the same surgery to jpackage output before publishing;
otherwise every release re-creates this bug.

## On-machine triage (for the user)

    dpkg -l t-bone-desktop | tail -1     # want: ii  (iU/iH/iF = not configured)
    dpkg --audit
    sudo dpkg --configure -a
    sudo apt --fix-broken install         # if apt reports broken deps
    /opt/t-bone-desktop/bin/t-bone-desktop   # see the real error if it exits

If the app starts from a terminal but never from the menu entry, the
problem is the desktop entry, not the app.
