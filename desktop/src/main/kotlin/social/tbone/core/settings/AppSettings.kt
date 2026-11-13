package social.tbone.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import social.tbone.core.store.SettingsStore

enum class ThemeMode(val prefValue: String, val label: String) {
    LIGHT("light", "LIGHT"),
    DARK("dark", "DARK"),
    CREAM("cream", "CREAM"),
    ;

    val isDark: Boolean get() = this == DARK

    companion object {
        fun fromPref(v: String?): ThemeMode = entries.firstOrNull { it.prefValue == v } ?: DARK
    }
}

enum class ImageLoadMode(val prefValue: String, val label: String) {
    OFF("off", "OFF"),
    ON("on", "ON"),
    LOW("low", "LOW"),
    ;

    companion object {
        fun fromPref(v: String?): ImageLoadMode = entries.firstOrNull { it.prefValue == v } ?: ON
    }
}

enum class AvatarMode(val prefValue: String, val label: String) {
    INITIAL("initial", "INITIAL"),
    LOW("low", "LOW"),
    REGULAR("regular", "REGULAR"),
    ;

    companion object {
        fun fromPref(v: String?): AvatarMode = entries.firstOrNull { it.prefValue == v } ?: REGULAR
    }
}

/**
 * Typed app settings — desktop port of upstream's `AppSettings`.
 *
 * Upstream exposed each preference as a `StateFlow` over DataStore. The port
 * keeps that surface (so screen code reads the same way) but backs it with the
 * SQLite `prefs` table through [SettingsStore]. Every key name is upstream's.
 */
class AppSettings(private val store: SettingsStore) {

    // ── Appearance ────────────────────────────────────────────────────────────
    private val _themeMode = MutableStateFlow(ThemeMode.fromPref(store.getString(KEY_THEME_MODE)))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _accentColor = MutableStateFlow(store.getString(KEY_ACCENT_COLOR))
    val accentColor: StateFlow<String?> = _accentColor.asStateFlow()

    // ── Privacy / transport ───────────────────────────────────────────────────
    private val _torEnabled = MutableStateFlow(store.getBoolean(KEY_TOR, false))
    val torEnabled: StateFlow<Boolean> = _torEnabled.asStateFlow()

    private val _torExplicitlySet = MutableStateFlow(store.getBoolean(KEY_TOR_SET, false))
    val torExplicitlySet: StateFlow<Boolean> = _torExplicitlySet.asStateFlow()

    // ── Media ─────────────────────────────────────────────────────────────────
    private val _imageLoadMode = MutableStateFlow(ImageLoadMode.fromPref(store.getString(KEY_IMAGE_MODE)))
    val imageLoadMode: StateFlow<ImageLoadMode> = _imageLoadMode.asStateFlow()

    private val _avatarMode = MutableStateFlow(AvatarMode.fromPref(store.getString(KEY_AVATAR_MODE)))
    val avatarMode: StateFlow<AvatarMode> = _avatarMode.asStateFlow()

    // ── Content filtering ─────────────────────────────────────────────────────
    private val _blockedPubkeys = MutableStateFlow(store.getStringSet(KEY_BLOCKED))
    val blockedPubkeys: StateFlow<Set<String>> = _blockedPubkeys.asStateFlow()

    private val _hideNsfw = MutableStateFlow(store.getBoolean(KEY_HIDE_NSFW, false))
    val hideNsfw: StateFlow<Boolean> = _hideNsfw.asStateFlow()

    private val _bleepWords = MutableStateFlow(store.getStringSet(KEY_BLEEP))
    val bleepWords: StateFlow<Set<String>> = _bleepWords.asStateFlow()

    private val _hideWords = MutableStateFlow(store.getStringSet(KEY_HIDE_WORDS))
    val hideWords: StateFlow<Set<String>> = _hideWords.asStateFlow()

    // ── Notifications / app lock ──────────────────────────────────────────────
    private val _lastViewedNotificationsAt = MutableStateFlow(store.getLong(KEY_LAST_NOTIF, 0L))
    val lastViewedNotificationsAt: StateFlow<Long> = _lastViewedNotificationsAt.asStateFlow()

    private val _pinEnabled = MutableStateFlow(store.getBoolean(KEY_PIN_ENABLED, false))
    val pinEnabled: StateFlow<Boolean> = _pinEnabled.asStateFlow()

    val duressPinEnabled: Boolean get() = store.getBoolean(KEY_DURESS_ENABLED, false)

    private val _notificationEnabledTypes = MutableStateFlow(store.getStringSet(KEY_NOTIF_TYPES))
    val notificationEnabledTypes: StateFlow<Set<String>> = _notificationEnabledTypes.asStateFlow()

    // Setters (write-through, mirroring upstream's setters) ────────────────────

    fun setThemeMode(mode: ThemeMode) {
        store.putString(KEY_THEME_MODE, mode.prefValue); _themeMode.value = mode
    }

    fun setAccentColor(hex: String?) {
        store.putString(KEY_ACCENT_COLOR, hex); _accentColor.value = hex
    }

    fun setTorEnabled(enabled: Boolean) {
        store.putBoolean(KEY_TOR, enabled)
        store.putBoolean(KEY_TOR_SET, true)
        _torEnabled.value = enabled
        _torExplicitlySet.value = true
    }

    fun setImageLoadMode(mode: ImageLoadMode) {
        store.putString(KEY_IMAGE_MODE, mode.prefValue); _imageLoadMode.value = mode
    }

    fun setAvatarMode(mode: AvatarMode) {
        store.putString(KEY_AVATAR_MODE, mode.prefValue); _avatarMode.value = mode
    }

    fun setBlocked(pubkeys: Set<String>) {
        store.putStringSet(KEY_BLOCKED, pubkeys); _blockedPubkeys.value = pubkeys
    }

    fun block(pubkey: String) = setBlocked(_blockedPubkeys.value + pubkey)
    fun unblock(pubkey: String) = setBlocked(_blockedPubkeys.value - pubkey)
    fun isBlocked(pubkey: String) = pubkey in _blockedPubkeys.value

    fun setHideNsfw(hide: Boolean) {
        store.putBoolean(KEY_HIDE_NSFW, hide); _hideNsfw.value = hide
    }

    fun setBleepWords(words: Set<String>) {
        store.putStringSet(KEY_BLEEP, words); _bleepWords.value = words
    }

    fun setHideWords(words: Set<String>) {
        store.putStringSet(KEY_HIDE_WORDS, words); _hideWords.value = words
    }

    fun setLastViewedNotificationsAt(ts: Long) {
        store.putLong(KEY_LAST_NOTIF, ts); _lastViewedNotificationsAt.value = ts
    }

    fun setNotificationEnabledTypes(types: Set<String>) {
        store.putStringSet(KEY_NOTIF_TYPES, types); _notificationEnabledTypes.value = types
    }

    /** Sets or clears the lock-screen PIN. Only a salted hash is stored, as upstream. */
    fun setPin(pin: String?) {
        if (pin.isNullOrBlank()) {
            store.remove(KEY_PIN_HASH); store.putBoolean(KEY_PIN_ENABLED, false)
            _pinEnabled.value = false
        } else {
            store.putString(KEY_PIN_HASH, PinHash.hash(pin))
            store.putBoolean(KEY_PIN_ENABLED, true)
            _pinEnabled.value = true
        }
    }

    fun verifyPin(pin: String): Boolean {
        val stored = store.getString(KEY_PIN_HASH) ?: return false
        return PinHash.verify(pin, stored)
    }

    fun hasPin(): Boolean = store.getString(KEY_PIN_HASH) != null

    /** True when the entered PIN matches the duress PIN (wipe instead of unlock). */
    fun isDuressPin(pin: String): Boolean {
        val stored = store.getString(KEY_DURESS_HASH) ?: return false
        return PinHash.verify(pin, stored)
    }

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_ACCENT_COLOR = "accent_color"
        const val KEY_TOR = "tor_enabled"
        const val KEY_TOR_SET = "tor_explicitly_set"
        const val KEY_IMAGE_MODE = "image_load_mode"
        const val KEY_AVATAR_MODE = "avatar_mode"
        const val KEY_BLOCKED = "blocked_pubkeys"
        const val KEY_HIDE_NSFW = "hide_nsfw"
        const val KEY_BLEEP = "bleep_words"
        const val KEY_HIDE_WORDS = "hide_words"
        const val KEY_LAST_NOTIF = "last_viewed_notifications_at"
        const val KEY_NOTIF_TYPES = "notification_enabled_types"
        const val KEY_PIN_ENABLED = "pin_enabled"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_DURESS_ENABLED = "duress_pin_enabled"
        const val KEY_DURESS_HASH = "duress_pin_hash"
    }
}

/**
 * PIN hashing for the desktop app lock.
 *
 * Upstream stored a hash produced on-device (the Android build used a salted
 * SHA-256 of the PIN). The desktop port uses PBKDF2-HMAC-SHA256 with a random
 * salt and 120k iterations, stored as `pbkdf2$<iterations>$<saltB64>$<hashB64>`.
 */
object PinHash {
    private const val ITERATIONS = 120_000
    private const val KEY_LEN = 32

    fun hash(pin: String): String {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val dk = pbkdf2(pin, salt, ITERATIONS)
        return "pbkdf2$$ITERATIONS$${b64(salt)}$${b64(dk)}"
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { java.util.Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { java.util.Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        val actual = pbkdf2(pin, salt, iterations)
        return java.security.MessageDigest.isEqual(actual, expected)
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LEN * 8)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
}
