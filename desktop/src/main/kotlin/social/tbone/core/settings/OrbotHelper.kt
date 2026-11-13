package social.tbone.core.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import social.tbone.core.net.ProxyKind
import social.tbone.core.net.Transport

/**
 * Desktop port of upstream's `OrbotHelper`.
 *
 * Upstream checked whether the Orbot Android app was installed and then probed
 * its local proxy ports. A desktop install has no Orbot package, so "installed"
 * is replaced by "a local Tor proxy answers", and the status type keeps the same
 * three states the UI branches on.
 */
sealed class OrbotStatus {
    /** A proxy is reachable; [kind] is HTTP (preferred, no DNS leak) or SOCKS. */
    data class InstalledAndConnected(val kind: ProxyKind, val port: Int) : OrbotStatus()

    /** Nothing is listening — Tor is configured but not running. */
    object InstalledNotRunning : OrbotStatus()

    /** No local Tor proxy found at all. */
    object NotInstalled : OrbotStatus()
}

object OrbotHelper {

    suspend fun getStatus(): OrbotStatus = withContext(Dispatchers.IO) {
        val probe = Transport.detectProxy() ?: return@withContext OrbotStatus.NotInstalled
        OrbotStatus.InstalledAndConnected(probe.kind, probe.port)
    }

    suspend fun findProxy(): Pair<ProxyKind, Int>? = withContext(Dispatchers.IO) {
        Transport.detectProxy()?.let { it.kind to it.port }
    }

    /** User-facing explanation used by the Tor settings screen. */
    fun describe(status: OrbotStatus): String = when (status) {
        is OrbotStatus.InstalledAndConnected ->
            "Local Tor proxy found (${status.kind} on 127.0.0.1:${status.port}). Relays and media use it."
        OrbotStatus.InstalledNotRunning ->
            "A Tor proxy port is not answering. Start Tor (or Orbot) and try again."
        OrbotStatus.NotInstalled ->
            "No local Tor proxy answered on 8118 (HTTP) or 9050/9150 (SOCKS). " +
                "Start a Tor daemon with a SocksPort or HTTPTunnelPort to anonymise traffic."
    }
}
