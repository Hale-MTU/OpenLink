package tech.mmarca.openlink.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.mmarca.openlink.OpenLink

/** A recording session or live subscription a client holds on the host. */
data class OpenLinkActiveSession(
    val packageName: String,
    /** [OpenLink.CAPABILITY_RECORDING] or [OpenLink.CAPABILITY_LIVE]. */
    val capability: String,
    val sessionId: String,
)

/**
 * Which client owns which recording session and live subscription. Events for a session go
 * to its owner only, and what a client loses (its capability, its binding) ends what it
 * owned. Plain Kotlin, tested on the JVM.
 */
class OpenLinkSessions {
    private data class Key(val capability: String, val sessionId: String)

    private val owners = linkedMapOf<Key, String>()

    private val _active = MutableStateFlow<List<OpenLinkActiveSession>>(emptyList())

    /** Every session held now, for the host app's UI (who is recording, a notification). */
    val active: StateFlow<List<OpenLinkActiveSession>> = _active.asStateFlow()

    @Synchronized
    fun claim(capability: String, sessionId: String, packageName: String): Boolean {
        val key = Key(capability, sessionId)
        val owner = owners[key]
        if (owner != null && owner != packageName) return false
        owners[key] = packageName
        publish()
        return true
    }

    @Synchronized
    fun release(capability: String, sessionId: String, packageName: String): Boolean {
        val key = Key(capability, sessionId)
        if (owners[key] != packageName) return false
        owners.remove(key)
        publish()
        return true
    }

    @Synchronized
    fun owner(capability: String, sessionId: String): String? = owners[Key(capability, sessionId)]

    /** Removes and returns the session ids [packageName] owns under [capability]. */
    @Synchronized
    fun releaseAll(packageName: String, capability: String): List<String> {
        val keys = owners.filter { (key, owner) -> owner == packageName && key.capability == capability }.keys.toList()
        keys.forEach(owners::remove)
        if (keys.isNotEmpty()) publish()
        return keys.map { it.sessionId }
    }

    private fun publish() {
        _active.value = owners.map { (key, owner) -> OpenLinkActiveSession(owner, key.capability, key.sessionId) }
    }

    companion object {
        val SESSION_CAPABILITIES: Set<String> = setOf(OpenLink.CAPABILITY_RECORDING, OpenLink.CAPABILITY_LIVE)
    }
}
