package social.tbone.desktop.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.tbone.core.Log
import social.tbone.core.account.Account
import social.tbone.core.signer.NostrSignerType
import social.tbone.core.account.SigningIndicator
import social.tbone.core.net.Transport
import social.tbone.core.nostr.NostrJson
import social.tbone.core.nostr.isReply
import social.tbone.core.nostr.pubkeys
import social.tbone.core.nostr.rootEventId
import social.tbone.core.nostr.UnsignedEvent
import social.tbone.core.nostr.relay.RelayConnection
import social.tbone.core.nostr.relay.RelayPool
import social.tbone.core.nostr.relay.RelayStatus
import social.tbone.core.nostr.relay.ClientMessage
import social.tbone.core.nostr.identity.Phrase
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.EventKind
import social.tbone.core.settings.AppSettings
import social.tbone.core.settings.OrbotHelper
import social.tbone.core.settings.OrbotStatus
import social.tbone.core.signer.LocalKeySigner
import social.tbone.core.signer.NostrSigner
import social.tbone.core.signer.NsecBunkerSigner
import social.tbone.core.store.AccountStore
import social.tbone.core.store.DesktopDatabase
import social.tbone.core.store.EventStore
import social.tbone.core.store.LocalCrypto
import social.tbone.core.store.NotesStore
import social.tbone.core.store.ProfileStore
import social.tbone.core.store.KindSets
import social.tbone.core.store.SettingsStore
import social.tbone.core.Tunables
import java.io.File

/**
 * Application core: the desktop stand-in for upstream's Hilt object graph.
 *
 * Upstream wired RelayPool, AccountRepository, ProfileRepository, EventRepository,
 * NotesRepository and the signer factory through Dagger/Hilt. There is no Hilt on
 * the JVM, so this class owns them explicitly, in the same dependency order, and
 * is the single place the UI talks to.
 */
class AppCore(val dataDir: File) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: DesktopDatabase = DesktopDatabase.open(dataDir)
    val settingsStore = SettingsStore(db)
    val settings = AppSettings(settingsStore)
    val accounts = AccountStore(settingsStore)
    val events = EventStore(db)
    val profiles = ProfileStore(db)
    val notes = NotesStore(db, LocalCrypto.forDatabase(db.file))

    val signingIndicator = SigningIndicator()

    val signerKindLabel = MutableStateFlow<String?>(null)

    /** Relay pool; the transport factory is swapped when the Tor setting changes. */
    @Volatile var proxyProbe = Transport.detectProxy()
        private set

    val pool = RelayPool(
        scope = scope,
        connectionFactory = { url -> RelayConnection(url, Transport.buildClient(settings.torEnabled.value, proxyProbe)) },
    )

    val relayStatuses: StateFlow<Map<String, RelayStatus>> get() = pool.relayStatuses

    /** Human-readable relay/transport state shown in the status bar. */
    private val _transportStatus = MutableStateFlow("")
    val transportStatus: StateFlow<String> = _transportStatus.asStateFlow()

    private val _activeSigner = MutableStateFlow<NostrSigner?>(null)
    val activeSigner: StateFlow<NostrSigner?> = _activeSigner.asStateFlow()

    /** Reason the active account cannot sign on desktop (e.g. Amber), if any. */
    private val _signerProblem = MutableStateFlow<String?>(null)
    val signerProblem: StateFlow<String?> = _signerProblem.asStateFlow()

    val feed = FeedService(this)
    val media = MediaService(this)
    val notifications = NotificationsService(this)

    /** Set once the user finishes the first-run flow (or immediately if an account exists). */
    private val _onboardingDone = MutableStateFlow(false)
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    fun dismissOnboarding() { _onboardingDone.value = true }

    fun start() {
        runCatching { Phrase.init() }
            .onFailure { Log.w("AppCore", "BIP-39 phrase list unavailable: ${it.message}") }
        Log.initFileLogging(File(dataDir, "logs"))
        Log.i("AppCore", "starting; data dir = ${dataDir.absolutePath}")

        accounts.load()
        scope.launch { notes.load() }
        scope.launch { profiles.loadAll() }

        // NIP-65 / fallback relays: upstream added the account's list, falling back
        // to Tunables.DEFAULT_RELAYS when the account had none.
        accounts.activeAccount.value?.let { acc ->
            (acc.relays.ifEmpty { Tunables.DEFAULT_RELAYS }).forEach { pool.addRelay(it) }
        }
        Tunables.DEFAULT_RELAYS.forEach { pool.addRelay(it) }

        scope.launch { rebuildSigner() }

        // React to account switches and Tor toggles exactly like upstream's
        // NetworkModule did: probe the proxy, swap the transport, reconnect.
        scope.launch {
            settings.torEnabled.collect { useTor ->
                proxyProbe = if (useTor) Transport.detectProxy() else null
                val status = if (!useTor) {
                    "CLEARNET"
                } else {
                    val probe = proxyProbe
                    if (probe == null) "TOR REQUESTED — NO PROXY ON 8118/9050/9150" else "TOR ${probe.kind} :${probe.port}"
                }
                _transportStatus.value = status
                pool.updateTransport(Transport.buildClient(useTor, proxyProbe))
                Log.i("AppCore", "transport = $status")
            }
        }

        scope.launch {
            accounts.activeAccount.collect { acc ->
                rebuildSigner()
                feed.onAccountChanged(acc)
                notifications.start(acc)
            }
        }

        feed.start()
    }

    /** Builds the signer for the active account, or records why it cannot. */
    suspend fun rebuildSigner() {
        val account = accounts.activeAccount.value
        if (account == null) {
            _activeSigner.value = null
            _signerProblem.value = null
            signerKindLabel.value = null
            return
        }
        val crypto = LocalCrypto.forDatabase(db.file)
        when (account.signerType) {
            NostrSignerType.LOCAL_KEY -> {
                val blob = accounts.encryptedPrivkey(account.pubkey)
                if (blob == null) {
                    _activeSigner.value = null
                    _signerProblem.value = "This account has no local key in the vault."
                } else {
                    _activeSigner.value = LocalKeySigner(account.pubkey, blob, crypto)
                    _signerProblem.value = null
                }
                signerKindLabel.value = "LOCAL KEY"
            }

            NostrSignerType.NSEC_BUNKER -> {
                val cfg = account.nsecBunkerConfig
                val sessionBlob = accounts.encryptedPrivkey(account.pubkey)
                if (cfg == null || sessionBlob == null) {
                    _activeSigner.value = null
                    _signerProblem.value = "nsecBunker session key is missing for this account."
                } else {
                    val session = LocalKeySigner(cfg.sessionPubkey, sessionBlob, crypto)
                    val signer = NsecBunkerSigner(
                        pubkey = account.pubkey,
                        config = cfg,
                        pool = pool,
                        sessionSigner = session,
                        scope = scope,
                        signingIndicator = signingIndicator,
                    )
                    // Handshake verifies the bunker is reachable; failure is reported,
                    // not hidden — the account stays visible with an explicit problem.
                    scope.launch {
                        signer.handshake()
                            .onSuccess { Log.i("AppCore", "nsecBunker handshake ok (${it.take(8)}…)") }
                            .onFailure {
                                _signerProblem.value = "nsecBunker handshake failed: ${it.message}"
                                Log.w("AppCore", "nsecBunker handshake failed: ${it.message}")
                            }
                    }
                    _activeSigner.value = signer
                    _signerProblem.value = null
                }
                signerKindLabel.value = "NSECBUNKER"
            }

            NostrSignerType.AMBER -> {
                _activeSigner.value = null
                _signerProblem.value =
                    "This account uses Amber (NIP-55), an Android app-to-app signer. " +
                        "There is no NIP-55 intent bridge on desktop, so this account is " +
                        "read-only here. Add a local key or nsecBunker account to post."
                signerKindLabel.value = "AMBER (unsupported)"
            }
        }
    }

    // ── Account operations ────────────────────────────────────────────────────

    /** Creates a fresh local-key account. Returns the new account. */
    suspend fun createLocalAccount(): Pair<Account, LocalKeySigner> {
        val crypto = LocalCrypto.forDatabase(db.file)
        val (signer, blob) = LocalKeySigner.generate(crypto)
        val account = Account(pubkey = signer.pubkey, signerType = NostrSignerType.LOCAL_KEY)
        accounts.addAccount(account, blob)
        rebuildSigner()
        // Publish the account's relay list so other clients can find it (NIP-65),
        // matching what upstream did on first login.
        scope.launch { publishRelayList() }
        return account to signer
    }

    /**
     * Imports an `nsec1…` key into a new local account.
     * NIP-19 bech32 nsec decoding is implemented here (upstream only had npub/note).
     */
    suspend fun importNsec(nsec: String): Result<Account> = runCatching {
        val priv = Nip19Desktop.decodeNsec(nsec)
            ?: error("That is not a valid nsec1… private key.")
        val crypto = LocalCrypto.forDatabase(db.file)
        // Encrypt once, keep the blob for persistence, then wipe the plaintext.
        val encrypted = crypto.encrypt(priv)
        priv.fill(0)
        val pubkey = social.tbone.core.nostr.Crypto.deriveXOnlyPubkey(
            crypto.decrypt(encrypted)
        ).joinToString("") { "%02x".format(it) }
        val account = Account(pubkey = pubkey, signerType = NostrSignerType.LOCAL_KEY)
        accounts.addAccount(account, encrypted)
        rebuildSigner()
        account
    }

    /** Connects a NIP-46 bunker:// URL as a read/write account. */
    suspend fun addBunkerAccount(bunkerUrl: String): Result<Account> = runCatching {
        val crypto = LocalCrypto.forDatabase(db.file)
        val (sessionSigner, sessionBlob) = LocalKeySigner.generate(crypto)
        val cfg = NsecBunkerSigner.parseBunkerUrl(bunkerUrl, sessionSigner.pubkey).getOrThrow()
        val fake = Account(pubkey = sessionSigner.pubkey, signerType = NostrSignerType.NSEC_BUNKER, nsecBunkerConfig = cfg)
        // Handshake must succeed before we persist anything — otherwise the user
        // would have a dead account in the switcher.
        val temporary = NsecBunkerSigner(
            pubkey = sessionSigner.pubkey,
            config = cfg,
            pool = pool,
            sessionSigner = sessionSigner,
            scope = scope,
            signingIndicator = signingIndicator,
        )
        pool.addRelay(cfg.relayUrl)
        val userPubkey = temporary.handshake().getOrThrow()
        temporary.close()
        val account = fake.copy(pubkey = userPubkey)
        accounts.addAccount(account, sessionBlob)
        rebuildSigner()
        account
    }

    /**
     * Returns the `nsec1…` form of a local key exactly once, for the onboarding
     * "write this down" screen. The key is decrypted from the vault only for the
     * duration of this call and the plaintext buffer is wiped afterwards.
     */
    fun exportNsecOnce(pubkey: String): String? = runCatching {
        val blob = accounts.encryptedPrivkey(pubkey) ?: return null
        val crypto = LocalCrypto.forDatabase(db.file)
        val raw = crypto.decrypt(blob)
        try {
            social.tbone.core.nostr.Nip19.bech32Encode("nsec", raw)
        } finally {
            raw.fill(0)
        }
    }.getOrNull()

    suspend fun switchAccount(pubkey: String) {
        accounts.setActiveAccount(pubkey)
        rebuildSigner()
    }

    suspend fun removeAccount(pubkey: String) {
        accounts.removeAccount(pubkey)
        rebuildSigner()
    }

    suspend fun sign(unsigned: UnsignedEvent): Result<Event> =
        activeSigner.value?.signEvent(unsigned)
            ?: Result.failure(IllegalStateException(signerProblem.value ?: "No signing account"))

    /** Publishes a kind-1 note. */
    suspend fun publishNote(text: String, replyTo: Event? = null, quote: Event? = null): Result<Event> {
        val signer = activeSigner.value ?: return Result.failure(IllegalStateException("No signing account"))
        val tags = buildList {
            if (replyTo != null) {
                // NIP-10 marked tags: root + reply.
                val rootId = replyTo.parsedTags.rootEventId
                if (rootId != null) {
                    add(buildTag("e", rootId, "", "root"))
                } else {
                    add(buildTag("e", replyTo.id, "", "root"))
                }
                add(buildTag("e", replyTo.id, "", "reply"))
                add(buildTag("p", replyTo.pubkey))
                replyTo.parsedTags.pubkeys.filter { it != replyTo.pubkey }.forEach { add(buildTag("p", it)) }
            }
            if (quote != null) {
                add(buildTag("q", quote.id))
                add(buildTag("p", quote.pubkey))
            }
        }
        val event = sign(
            UnsignedEvent(
                pubkey = signer.pubkey,
                kind = EventKind.TEXT_NOTE,
                tags = tags,
                content = text,
            )
        ).getOrElse { return Result.failure(it) }

        pool.publish(event)
        accounts.activeAccount.value?.let { acc ->
            events.save(event, acc.pubkey)
            acc.pubkey.let { feed.addOwnEvent(event) }
        }
        Log.i(
            "AppCore",
            "Published ${event.id.take(8)}… to ${pool.relayStatuses.value.count { it.value == RelayStatus.CONNECTED }} " +
                "connected relay(s); awaiting OK",
        )
        return Result.success(event)
    }

    /** NIP-25 reaction. */
    suspend fun react(event: Event, content: String = "+"): Result<Event> {
        val signer = activeSigner.value ?: return Result.failure(IllegalStateException("No signing account"))
        val unsigned = UnsignedEvent(
            pubkey = signer.pubkey,
            kind = EventKind.REACTION,
            tags = listOf(buildTag("e", event.id), buildTag("p", event.pubkey)),
            content = content,
        )
        return sign(unsigned).onSuccess {
            pool.publish(it)
            feed.noteReacted(event.id, signer.pubkey)
        }
    }

    /** NIP-18 repost. */
    suspend fun repost(event: Event): Result<Event> {
        val signer = activeSigner.value ?: return Result.failure(IllegalStateException("No signing account"))
        val unsigned = UnsignedEvent(
            pubkey = signer.pubkey,
            kind = EventKind.REPOST,
            tags = listOf(buildTag("e", event.id), buildTag("p", event.pubkey)),
            content = NostrJson.encodeToString(Event.serializer(), event),
        )
        return sign(unsigned).onSuccess {
            pool.publish(it)
            feed.noteReposted(event.id, signer.pubkey)
        }
    }

    /**
     * NIP-88 poll vote (kind 1018). Upstream's PollsRepository sent one
     * response event carrying a `response` tag per selected option.
     */
    suspend fun votePoll(poll: Event, optionIds: List<String>): Result<Event> {
        val signer = activeSigner.value ?: return Result.failure(IllegalStateException("No signing account"))
        val pollOwner = poll.parsedTags.firstOrNull { it.name == "p" }?.value() ?: poll.pubkey
        val tags = buildList {
            add(buildTag("e", poll.id))
            add(buildTag("p", pollOwner))
            optionIds.forEach { add(buildTag("response", it)) }
        }
        return sign(
            UnsignedEvent(pubkey = signer.pubkey, kind = EventKind.POLL_RESPONSE, tags = tags, content = "")
        ).onSuccess { pool.publish(it) }
    }

    /** NIP-09 deletion request for one of your own events. */
    suspend fun deleteEvent(event: Event): Result<Event> {
        val signer = activeSigner.value ?: return Result.failure(IllegalStateException("No signing account"))
        val unsigned = UnsignedEvent(
            pubkey = signer.pubkey,
            kind = EventKind.DELETE,
            tags = listOf(buildTag("e", event.id)),
            content = "deleted from T-Bone Desktop",
        )
        return sign(unsigned).onSuccess {
            pool.publish(it)
            events.remove(event.id)
            feed.removeEvent(event.id)
        }
    }

    /** Follow / unfollow via a fresh kind-3 contact list (NIP-02). */
    suspend fun setFollowing(target: String, follow: Boolean): Result<Unit> = runCatching {
        val account = accounts.activeAccount.value ?: error("No active account")
        val signer = activeSigner.value ?: error(signerProblem.value ?: "No signing account")
        val current = account.followEntries
            .filter { it.pubkey != target }
            .let { if (follow) it + social.tbone.core.account.FollowEntry(target) else it }
        val tags = current.map { buildTag("p", it.pubkey, it.relay, it.petname) }
        val signed = signer.signEvent(
            UnsignedEvent(pubkey = signer.pubkey, kind = EventKind.FOLLOW_LIST, tags = tags, content = "")
        ).getOrThrow()
        pool.publish(signed)
        accounts.updateAccount(account.pubkey) {
            it.copy(follows = current.map { f -> f.pubkey }, followEntries = current)
        }
        feed.invalidateFollowing()
    }

    /** NIP-65: publishes the account's current relay list. */
    suspend fun publishRelayList(): Result<Unit> = runCatching {
        val account = accounts.activeAccount.value ?: error("No active account")
        val signer = activeSigner.value ?: error(signerProblem.value ?: "No signing account")
        val relays = account.relays.ifEmpty { Tunables.DEFAULT_RELAYS }
        val tags = relays.map { buildTag("r", it) } // no marker = read+write
        val signed = signer.signEvent(
            UnsignedEvent(pubkey = signer.pubkey, kind = EventKind.RELAY_LIST, tags = tags, content = "")
        ).getOrThrow()
        pool.publish(signed)
        events.save(signed, account.pubkey)
    }

    /** kind-0 profile publish. */
    suspend fun publishProfile(
        name: String,
        displayName: String,
        about: String,
        picture: String,
        nip05: String,
        lud16: String,
    ): Result<Unit> = runCatching {
        val signer = activeSigner.value ?: error(signerProblem.value ?: "No signing account")
        val json = buildString {
            append("{")
            append("\"name\":").append(quoteJson(name)).append(",")
            append("\"display_name\":").append(quoteJson(displayName)).append(",")
            append("\"about\":").append(quoteJson(about)).append(",")
            append("\"picture\":").append(quoteJson(picture)).append(",")
            append("\"nip05\":").append(quoteJson(nip05)).append(",")
            append("\"lud16\":").append(quoteJson(lud16))
            append("}")
        }
        val signed = signer.signEvent(
            UnsignedEvent(pubkey = signer.pubkey, kind = EventKind.METADATA, content = json)
        ).getOrThrow()
        pool.publish(signed)
        profiles.put(signer.pubkey, json)
        accounts.updateAccount(signer.pubkey) {
            it.copy(displayName = displayName.ifBlank { name }, pictureUrl = picture)
        }
    }

    suspend fun addRelay(url: String) {
        val normalized = RelayPool.normalize(url)
        pool.addRelay(normalized)
        accounts.activeAccount.value?.let { acc ->
            accounts.updateAccount(acc.pubkey) { it.copy(relays = (it.relays + normalized).distinct()) }
        }
    }

    suspend fun removeRelay(url: String) {
        pool.removeRelay(url)
        accounts.activeAccount.value?.let { acc ->
            accounts.updateAccount(acc.pubkey) { it.copy(relays = it.relays - url) }
        }
    }

    /** Wipes all local data (accounts, events, notes, settings) — the duress path. */
    suspend fun wipeAll() {
        accounts.wipeAll()
        events.clear()
        notes.load()
        settingsStore.clearAll()
        pool.relayUrls().forEach { pool.removeRelay(it) }
        _activeSigner.value = null
        Log.w("AppCore", "all local data wiped")
    }

    fun stop() {
        runCatching { db.close() }
    }

    private fun quoteJson(s: String): String =
        social.tbone.core.nostr.NostrJson.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(s))

    private fun buildTag(vararg parts: String) = kotlinx.serialization.json.buildJsonArray {
        parts.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    }

    fun orbotStatus(): OrbotStatus = if (proxyProbe == null) OrbotStatus.NotInstalled
    else OrbotStatus.InstalledAndConnected(proxyProbe!!.kind, proxyProbe!!.port)

    /** Sends a raw NIP-01 frame to every connected relay (relay console). */
    fun send(raw: String): Boolean = pool.sendRaw(raw)
}

/** Small NIP-19 helpers the desktop port needs beyond upstream's npub/note support. */
object Nip19Desktop {

    /**
     * Decodes `nsec1…` into the raw 32-byte private key.
     * Reuses the bech32 core by mapping the nsec payload through the same
     * convert-bits routine upstream used for npub.
     */
    fun decodeNsec(nsec: String): ByteArray? = runCatching {
        val cleaned = nsec.trim().removePrefix("nostr:")
        val lowered = cleaned.lowercase()
        if (!lowered.startsWith("nsec1")) return null
        val decoded = social.tbone.core.nostr.Nip19.bech32Decode(lowered) ?: return null
        if (decoded.first != "nsec" || decoded.second.size != 32) return null
        decoded.second
    }.getOrNull()
}
