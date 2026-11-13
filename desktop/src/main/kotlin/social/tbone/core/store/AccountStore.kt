package social.tbone.core.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import social.tbone.core.Log
import social.tbone.core.account.Account
import kotlinx.serialization.encodeToString
import social.tbone.core.nostr.NostrJson
import java.util.Base64

/**
 * Account persistence — port of upstream's DataStore-backed AccountRepository.
 *
 * Accounts are stored as a JSON array under one key, the active pubkey under
 * another, and encrypted private-key blobs in a separate map, exactly as
 * upstream did. The blobs are already AES-GCM-encrypted by [LocalCrypto] before
 * they reach the settings table; base64 is only a transport encoding.
 */
class AccountStore(private val settings: SettingsStore) {

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _active = MutableStateFlow<Account?>(null)
    val activeAccount: StateFlow<Account?> = _active.asStateFlow()

    fun load() {
        _accounts.value = readAccounts()
        val pubkey = settings.getString(KEY_ACTIVE_PUBKEY)
        _active.value = _accounts.value.firstOrNull { it.pubkey == pubkey }
    }

    private fun readAccounts(): List<Account> =
        settings.getString(KEY_ACCOUNTS)?.let {
            runCatching { NostrJson.decodeFromString<List<Account>>(it) }
                .onFailure { e -> Log.w("AccountStore", "accounts JSON unreadable: ${e.message}") }
                .getOrDefault(emptyList())
        } ?: emptyList()

    private fun writeAccounts(list: List<Account>) {
        settings.putString(KEY_ACCOUNTS, NostrJson.encodeToString(list))
        _accounts.value = list
        _active.value = list.firstOrNull { it.pubkey == settings.getString(KEY_ACTIVE_PUBKEY) }
    }

    suspend fun addAccount(account: Account, encryptedPrivkey: ByteArray? = null) = withContext(Dispatchers.IO) {
        val current = readAccounts().toMutableList()
        if (current.none { it.pubkey == account.pubkey }) current.add(account)
        writeAccounts(current)
        if (encryptedPrivkey != null) {
            val keys = readKeys().toMutableMap()
            keys[account.pubkey] = Base64.getEncoder().encodeToString(encryptedPrivkey)
            settings.putString(KEY_ENCRYPTED_KEYS, NostrJson.encodeToString(keys))
        }
        setActiveAccount(account.pubkey)
    }

    suspend fun removeAccount(pubkey: String) = withContext(Dispatchers.IO) {
        writeAccounts(readAccounts().filter { it.pubkey != pubkey })
        if (settings.getString(KEY_ACTIVE_PUBKEY) == pubkey) {
            settings.putString(KEY_ACTIVE_PUBKEY, _accounts.value.firstOrNull()?.pubkey ?: "")
        }
        val keys = readKeys().toMutableMap()
        keys.remove(pubkey)
        settings.putString(KEY_ENCRYPTED_KEYS, NostrJson.encodeToString(keys))
        _active.value = _accounts.value.firstOrNull { it.pubkey == settings.getString(KEY_ACTIVE_PUBKEY) }
    }

    suspend fun setActiveAccount(pubkey: String) = withContext(Dispatchers.IO) {
        settings.putString(KEY_ACTIVE_PUBKEY, pubkey)
        _active.value = _accounts.value.firstOrNull { it.pubkey == pubkey }
    }

    /** Atomically transforms one stored account. */
    suspend fun updateAccount(pubkey: String, transform: (Account) -> Account) = withContext(Dispatchers.IO) {
        writeAccounts(readAccounts().map { if (it.pubkey == pubkey) transform(it) else it })
    }

    fun encryptedPrivkey(pubkey: String): ByteArray? =
        readKeys()[pubkey]?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    fun hasPrivateKey(pubkey: String): Boolean = readKeys().containsKey(pubkey)

    private fun readKeys(): Map<String, String> =
        settings.getString(KEY_ENCRYPTED_KEYS)?.let {
            runCatching { NostrJson.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap())
        } ?: emptyMap()

    /** Wipes every account + key blob (used by the "wipe all data" action). */
    suspend fun wipeAll() = withContext(Dispatchers.IO) {
        settings.remove(KEY_ACCOUNTS)
        settings.remove(KEY_ACTIVE_PUBKEY)
        settings.remove(KEY_ENCRYPTED_KEYS)
        _accounts.value = emptyList()
        _active.value = null
        Log.i("AccountStore", "all accounts wiped")
    }

    private companion object {
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_ACTIVE_PUBKEY = "active_pubkey"
        const val KEY_ENCRYPTED_KEYS = "encrypted_keys"
    }
}
