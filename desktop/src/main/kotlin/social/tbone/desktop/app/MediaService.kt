package social.tbone.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import social.tbone.core.Log
import social.tbone.core.settings.ImageLoadMode
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Image loading/saving — desktop replacement for upstream's Coil pipeline.
 *
 * Upstream used Coil (an Android library) with an LruCache-backed memory cache and
 * a Tor-aware OkHttp fetcher, then decoded with BitmapFactory. Desktop Compose has
 * no Coil, so:
 *
 *   - the same transport rule applies: when Tor is enabled, images are fetched
 *     through the same proxy the relays use ([social.tbone.core.net.Transport]);
 *   - bytes are cached on disk (content-addressed by URL hash) and decoded to an
 *     ImageBitmap lazily on the IO dispatcher;
 *   - [ImageLoadMode] OFF keeps notes text-only (URL rendered, no fetch), ON
 *     fetches inline, LOW fetches and downscales to a 512 px box — the same three
 *     behaviours the phone app offered.
 */
class MediaService(private val core: AppCore) {

    private val memory = object : LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(m: MutableMap.MutableEntry<String, androidx.compose.ui.graphics.ImageBitmap>?): Boolean =
            size > 48
    }

    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    private val _updates = MutableStateFlow(0L)
    /** Bumped after each successful decode so composables re-read the cache. */
    val updates: StateFlow<Long> = _updates.asStateFlow()

    fun cached(url: String): androidx.compose.ui.graphics.ImageBitmap? = synchronized(memory) { memory[url] }

    fun cachedAvatar(pubkey: String): androidx.compose.ui.graphics.ImageBitmap? {
        val url = core.profiles.picture(pubkey) ?: return null
        return cached(url)
    }

    fun request(url: String) {
        if (url.isBlank()) return
        if (cached(url) != null) return
        if (inFlight.containsKey(url)) return
        val mode = core.settings.imageLoadMode.value
        if (mode == ImageLoadMode.OFF) return
        val job = core.scope.launch(Dispatchers.IO) {
            try {
                val bmp = load(url, mode == ImageLoadMode.LOW)
                if (bmp != null) {
                    synchronized(memory) { memory[url] = bmp }
                    _updates.value = _updates.value + 1
                }
            } finally {
                inFlight.remove(url)
            }
        }
        inFlight[url] = job
    }

    private suspend fun load(url: String, lowQuality: Boolean): androidx.compose.ui.graphics.ImageBitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = diskCache(url) ?: fetch(url)
                decode(bytes, if (lowQuality) 512 else 1600)
            }.onFailure { Log.w("MediaService", "image load failed for $url: ${it.message}") }.getOrNull()
        }

    private fun diskCache(url: String): ByteArray? = runCatching {
        val f = cacheFile(url)
        if (f.isFile && f.length() > 0) f.readBytes() else null
    }.getOrNull()

    private fun fetch(url: String): ByteArray {
        val bytes = social.tbone.core.net.Transport
            .fetchBytes(core.settings.torEnabled.value, core.proxyProbe, url)
            .getOrThrow()
        runCatching {
            val f = cacheFile(url)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        }
        return bytes
    }

    private fun cacheFile(url: String): File {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(core.dataDir, "media"), hash)
    }

    private fun decode(bytes: ByteArray, maxDim: Int): androidx.compose.ui.graphics.ImageBitmap? =
        ImageBitmaps.decodeDownscaled(bytes, maxDim)
            ?: ImageBitmaps.decode(bytes)

    /** Save-as flow: fetch, then ask the user where to put the file. */
    fun saveImageInteractive(url: String) {
        core.scope.launch(Dispatchers.IO) {
            val suggested = url.substringAfterLast('/').substringBefore('?').ifBlank { "image" }
            val dest = DesktopUx.chooseSaveFile("Save image", suggested)
            if (dest == null) {
                Log.d("MediaService", "save cancelled for $url")
                return@launch
            }
            saveTo(url, dest)
                .onSuccess { Log.i("MediaService", "saved image to ${it.absolutePath}") }
                .onFailure { Log.w("MediaService", "save failed: ${it.message}") }
        }
    }

    /** Saves an image to the user's chosen directory ("save image" action). */
    suspend fun saveTo(url: String, destFile: File): Result<File> = withContext(Dispatchers.IO) {
        social.tbone.core.net.Transport.download(
            core.settings.torEnabled.value,
            core.proxyProbe,
            url,
            destFile,
        )
    }

    fun clearCache() {
        synchronized(memory) { memory.clear() }
        runCatching { File(core.dataDir, "media").deleteRecursively() }
        _updates.value = _updates.value + 1
    }

    fun cacheSizeBytes(): Long = runCatching {
        File(core.dataDir, "media").walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)
}
