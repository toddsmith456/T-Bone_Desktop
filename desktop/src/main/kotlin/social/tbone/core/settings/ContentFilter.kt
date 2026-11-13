package social.tbone.core.settings

import social.tbone.core.nostr.Event

/**
 * Content filtering applied to every note before it reaches the UI — ported
 * unchanged from upstream:
 *  - BLOCK: notes from blocked pubkeys are removed entirely.
 *  - NSFW (NIP-36): notes carrying a "content-warning" tag are hidden.
 *  - HIDE WORDS: notes containing any hidden word are removed entirely.
 *  - BLEEP WORDS: notes stay visible but the words become asterisks.
 */
object ContentFilter {

    fun shouldHide(
        event: Event,
        blockedPubkeys: Set<String>,
        hideNsfw: Boolean,
        hideWords: Set<String>,
    ): Boolean {
        if (event.pubkey in blockedPubkeys) return true
        if (hideNsfw && hasContentWarning(event)) return true
        if (hideWords.isNotEmpty() && containsAny(event.content, hideWords)) return true
        return false
    }

    fun bleep(text: String, bleepWords: Set<String>): String {
        if (bleepWords.isEmpty()) return text
        val lower = text.lowercase()
        val builder = StringBuilder(text)
        bleepWords.forEach { word ->
            if (word.isBlank()) return@forEach
            val mask = "*".repeat(word.length)
            var from = 0
            while (true) {
                val idx = lower.indexOf(word, from)
                if (idx < 0) break
                builder.replace(idx, idx + word.length, mask)
                from = idx + word.length
            }
        }
        return builder.toString()
    }

    fun contentWarningReason(event: Event): String? =
        event.parsedTags.firstOrNull { it.name == "content-warning" }?.value()

    private fun hasContentWarning(event: Event): Boolean =
        event.parsedTags.any { it.name == "content-warning" }

    private fun containsAny(text: String, words: Set<String>): Boolean {
        val lower = text.lowercase()
        return words.any { it.isNotBlank() && lower.contains(it) }
    }
}
