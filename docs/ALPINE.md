# Running T-Bone Desktop on Alpine (musl) via OCI

Alpine Linux uses musl libc. Compose Desktop's rendering layer (Skiko) ships
prebuilt native libraries linked against glibc, and there is no musl build of the
native `.apk`/`.deb` application image. Building a native Alpine package would mean
maintaining a musl Skiko build, which is out of scope for this port.

So the supported way to run T-Bone Desktop on an Alpine host is to run the glibc
build inside an OCI container. This is a packaging strategy, not a limitation of the
app's protocol code — everything in `core/` and `ui/` is plain Kotlin/JVM.

## Why not a native Alpine package

| Component | glibc need | Notes |
|---|---|---|
| Skiko native lib (`libskiko-linux-x64.so`) | glibc | prebuilt; no musl variant published |
| SQLite JDBC | pure Java + bundled native | works on musl when the bundled `.so` matches |
| OkHttp / TLS | pure Java | fine |

The blocker is Skiko. Two ways to deal with it, in order of preference:

1. **Run the glibc build in a container** (recommended; documented below).
2. Run the app with a glibc compatibility layer (`gcompat`) — fragile: Skiko pulls a
   wide slice of glibc's loader/runtime, and `gcompat` does not cover all of it. Not
   documented as supported because it was not verified here.

## Option A: Debian-slim base image (recommended)

The `.deb` is built for Debian/Ubuntu-style glibc systems. A minimal image that runs
it without a desktop session is enough for a server/headless use, and an X server (or
Xwayland) is needed only for the GUI.

```dockerfile
# Dockerfile.tbone-deb
FROM debian:13-slim

# Runtime libraries the Compose/Skiko stack needs, plus the font and X libs.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libbrotli1 libbz2-1.0 libexpat1 libfontconfig1 libfreetype6 \
        libgl1 libglvnd0 libglx0 libpng16-16 \
        libx11-6 libxau6 libxcb1 libxdmcp6 libxext6 libxi6 libxrender1 libxtst6 \
        xdg-utils ca-certificates fonts-dejavu-core \
        menu desktop-file-utils \
    && rm -rf /var/lib/apt/lists/*

COPY t-bone-desktop_0.3.36-1_amd64.deb /tmp/
RUN dpkg -i /tmp/t-bone-desktop_0.3.36-1_amd64.deb && rm /tmp/*.deb

# Data directory: mount a volume here so keys/notes survive the container.
VOLUME ["/root/.config/t-bone-desktop"]

ENTRYPOINT ["/opt/t-bone-desktop/bin/t-bone-desktop"]
```

Build and run:

```bash
./gradlew :desktop:packageDeb
cp desktop/build/compose/binaries/main/deb/t-bone-desktop_0.3.36-1_amd64.deb .
docker build -f Dockerfile.tbone-deb -t tbone-desktop:0.3.36 .
```

For a GUI, hand it a display and mount the config directory:

```bash
docker run --rm -it \
  -e DISPLAY="$DISPLAY" -v /tmp/.X11-unix:/tmp/.X11-unix \
  -v "$HOME/.config/t-bone-desktop:/root/.config/t-bone-desktop" \
  tbone-desktop:0.3.36
```

Headless CI check (what this project actually ran): use `Xvfb` inside the container
and `-Dskiko.renderApi=SOFTWARE`, because there is no GPU:

```bash
docker run --rm -e DISPLAY=:99 tbone-desktop:0.3.36 \
  sh -c 'Xvfb :99 -screen 0 1400x950x24 & sleep 2; exec /opt/t-bone-desktop/bin/t-bone-desktop'
```

## Option B: Alpine host, glibc container

Run the same image on an Alpine host — the kernel is shared, the container provides
glibc. Nothing in the image needs Alpine's musl:

```bash
# on the Alpine host
apk add docker
docker run --rm ... tbone-desktop:0.3.36
```

If you specifically want *Alpine's* userspace for the rest of the tooling, keep the app
in its Debian container and mount the data volume; do not try to run the app binary
against musl directly.

## Honest status

- The `.deb` + Debian-slim container path was built and launched in this project's
  environment (Debian 13, Xvfb, software rendering) — onboarding reached, relays
  connected.
- A native Alpine `.apk` was **not** produced and is **not** claimed to work.
- `gcompat` was **not** verified and is **not** recommended.
- No GPU acceleration was tested in a container; software rendering is the verified
  configuration there.
