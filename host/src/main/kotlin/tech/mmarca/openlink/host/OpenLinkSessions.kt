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
 * owned. A client may open a new recording session at most once per [START_INTERVAL_MILLIS],
 * and a session ends after [MAX_SESSION_MILLIS]. Plain Kotlin, tested on the JVM.
 */
class OpenLinkSessions(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Key(val capability: String, val sessionId: String)

    private val owners = linkedMapOf<Key, String>()
    private val openedAt = mutableMapOf<Key, Long>()
    private val lastRecordingStart = mutableMapOf<String, Long>()

    private val _active = MutableStateFlow<List<OpenLinkActiveSession>>(emptyList())

    /** Every session held now, for the host app's UI (who is recording, a notification). */
    val active: StateFlow<List<OpenLinkActiveSession>> = _active.asStateFlow()

    @Synchronized
    fun claim(capability: String, sessionId: String, packageName: String): Boolean {
        val key = Key(capability, sessionId)
        val owner = owners[key]
        if (owner != null && owner != packageName) return false
        if (owner == null) openedAt[key] = clock()
        owners[key] = packageName
        publish()
        return true
    }

    /**
     * Whether [packageName] may open the recording session [sessionId] now. A session it holds
     * already is always allowed (a repeated start changes nothing); a new one only when the
     * client opened none in the last [START_INTERVAL_MILLIS]. An allowed new one counts.
     */
    @Synchronized
    fun mayStartRecording(sessionId: String, packageName: String): Boolean {
        if (owners[Key(OpenLink.CAPABILITY_RECORDING, sessionId)] == packageName) return true
        val now = clock()
        val last = lastRecordingStart[packageName]
        if (last != null && now - last < START_INTERVAL_MILLIS) return false
        lastRecordingStart[packageName] = now
        return true
    }

    /** Removes and returns the sessions opened more than [MAX_SESSION_MILLIS] ago. */
    @Synchronized
    fun releaseExpired(): List<OpenLinkActiveSession> {
        val now = clock()
        val expired = owners.filterKeys { key -> now - (openedAt[key] ?: now) > MAX_SESSION_MILLIS }
        if (expired.isEmpty()) return emptyList()
        expired.keys.forEach { owners.remove(it); openedAt.remove(it) }
        publish()
        return expired.map { (key, owner) -> OpenLinkActiveSession(owner, key.capability, key.sessionId) }
    }

    @Synchronized
    fun release(capability: String, sessionId: String, packageName: String): Boolean {
        val key = Key(capability, sessionId)
        if (owners[key] != packageName) return false
        owners.remove(key)
        openedAt.remove(key)
        publish()
        return true
    }

    @Synchronized
    fun owner(capability: String, sessionId: String): String? = owners[Key(capability, sessionId)]

    /** Removes and returns the session ids [packageName] owns under [capability]. */
    @Synchronized
    fun releaseAll(packageName: String, capability: String): List<String> {
        val keys = owners.filter { (key, owner) -> owner == packageName && key.capability == capability }.keys.toList()
        keys.forEach { owners.remove(it); openedAt.remove(it) }
        if (keys.isNotEmpty()) publish()
        return keys.map { it.sessionId }
    }

    private fun publish() {
        _active.value = owners.map { (key, owner) -> OpenLinkActiveSession(owner, key.capability, key.sessionId) }
    }

    companion object {
        /** A client opens at most one new recording session in this time. */
        const val START_INTERVAL_MILLIS = 10_000L

        /** A session that was never stopped ends after six hours. */
        const val MAX_SESSION_MILLIS = 6L * 60 * 60 * 1000

        val SESSION_CAPABILITIES: Set<String> = setOf(OpenLink.CAPABILITY_RECORDING, OpenLink.CAPABILITY_LIVE)
    }
}
