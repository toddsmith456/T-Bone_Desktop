package social.tbone.core.net

import okhttp3.OkHttpClient
import social.tbone.core.Log
import social.tbone.core.Tunables
import social.tbone.core.nostr.relay.RelayConnection
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

enum class ProxyKind { NONE, HTTP, SOCKS }

data class ProxyProbe(val kind: ProxyKind, val port: Int)

/**
 * Tor/proxy support for the desktop port.
 *
 * Upstream's `OrbotHelper` needed an Android package check plus a port probe
 * against Orbot's local HTTP (8118) / SOCKS (9050, 9150) listeners. On desktop
 * there is no Orbot app, but any local Tor SOCKS/HTTP proxy can be used, so the
 * port keeps the same probe order and the same "HTTP CONNECT first — it resolves
 * DNS inside Tor" preference, and exposes the detected endpoint in Settings.
 *
 * The app never silently pretends to be anonymised: if Tor is enabled and no
 * proxy answers, the UI says so and the connection is not attempted over the
 * clearnet.
 */
object Transport {

    private const val CONNECT_TIMEOUT_MS = 1_500

    fun isPortReachable(port: Int, host: String = "127.0.0.1"): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    /** Probes the conventional local Tor endpoints in upstream's preference order. */
    fun detectProxy(): ProxyProbe? {
        if (isPortReachable(Tunables.TOR_HTTP_PROXY_PORT)) {
            return ProxyProbe(ProxyKind.HTTP, Tunables.TOR_HTTP_PROXY_PORT)
        }
        Tunables.TOR_SOCKS_PORTS.firstOrNull { isPortReachable(it) }?.let {
            return ProxyProbe(ProxyKind.SOCKS, it)
        }
        return null
    }

    fun buildClient(useTor: Boolean, probe: ProxyProbe? = null): OkHttpClient =
        OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // WebSocket: no read timeout
            .apply {
                if (useTor && probe != null) {
                    val type = if (probe.kind == ProxyKind.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
                    proxy(Proxy(type, InetSocketAddress("127.0.0.1", probe.port)))
                    Log.i("Transport", "routing relays through ${probe.kind} proxy on 127.0.0.1:${probe.port}")
                }
            }
            .build()

    /** Builds the pool's connection factory for the current transport setting. */
    fun factory(useTor: Boolean, probe: ProxyProbe?): (String) -> RelayConnection {
        val client = buildClient(useTor, probe)
        return { url -> RelayConnection(url, client) }
    }

    /**
     * Fetches an image over the same transport the relays use, so the Tor setting
     * applies to media as it did upstream (inline images respected the toggle).
     */
    fun fetchBytes(useTor: Boolean, probe: ProxyProbe?, url: String, maxBytes: Long = 12L * 1024 * 1024): Result<ByteArray> =
        runCatching {
            val client = buildClient(useTor, probe).newBuilder()
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val req = okhttp3.Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                require(resp.isSuccessful) { "HTTP ${resp.code}" }
                val body = resp.body ?: error("empty body")
                val bytes = body.bytes()
                require(bytes.size <= maxBytes) { "image too large (${bytes.size} bytes)" }
                bytes
            }
        }

    /** Downloads [url] to [dest] (used by the "save image" action). */
    fun download(useTor: Boolean, probe: ProxyProbe?, url: String, dest: File): Result<File> =
        fetchBytes(useTor, probe, url).map { bytes ->
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            dest
        }
}
