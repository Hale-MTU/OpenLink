package tech.mmarca.openlink.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkRecordingPhase
import tech.mmarca.openlink.OpenLinkRecordingState

/** A recording session or live subscription a client holds on the host. */
data class OpenLinkActiveSession(
    val packageName: String,
    /** [OpenLink.CAPABILITY_RECORDING] or [OpenLink.CAPABILITY_LIVE]. */
    val capability: String,
    val sessionId: String,
)

/** What a start from a client comes to. */
sealed interface OpenLinkStart {
    /** The session is the caller's now (or already was): hand it to the host app. */
    data object Begin : OpenLinkStart

    /** Another client owns the session id: nothing happens. */
    data object Refused : OpenLinkStart

    /** The caller's recording ended while it was away: tell it [last], start nothing. */
    data class Ended(val last: OpenLinkRecordingState) : OpenLinkStart
}

/**
 * Which client owns which recording session and live subscription. Events for a session go
 * to its owner only, and what a client loses (its capability, its binding) ends what it
 * owned. Plain Kotlin, tested on the JVM.
 *
 * A detached session (a workout the wearable records itself) outlives its owner's binding,
 * and, with a [storage], the host's process: it and the last [MAX_FINISHED] such sessions the
 * host ended are written there, so a host that restarts mid-workout still knows whose it is,
 * and a start replayed after the end still meets that end.
 */
class OpenLinkSessions(private val storage: OpenLinkTrustStorage? = null) {
    private data class Key(val capability: String, val sessionId: String)

    private val owners = linkedMapOf<Key, String>()

    /** Sessions that outlive their owner's binding: a wearable that records on its own. */
    private val detached = mutableSetOf<Key>()

    /** The last detached recordings the host ended, oldest first, with their owner and final state. */
    private val finished = linkedMapOf<String, Pair<String, OpenLinkRecordingState>>()

    private val _active = MutableStateFlow<List<OpenLinkActiveSession>>(emptyList())

    /** Every session held now, for the host app's UI (who is recording, a notification). */
    val active: StateFlow<List<OpenLinkActiveSession>> = _active.asStateFlow()

    init {
        load()
        publish()
    }

    /**
     * Gives [sessionId] to [packageName], or confirms it already has it; a repeat keeps how the
     * session was first claimed. A [detached] session keeps running when its owner unbinds or
     * dies ([releaseAll] with `keepDetached`); only the owner stopping it, the host ending it
     * ([reported]) or the owner losing the capability ends it.
     */
    @Synchronized
    fun claim(capability: String, sessionId: String, packageName: String, detached: Boolean = false): Boolean {
        val key = Key(capability, sessionId)
        val owner = owners[key]
        if (owner != null && owner != packageName) return false
        if (owner == null) {
            owners[key] = packageName
            if (detached) {
                this.detached += key
                storage?.write(storageKey(sessionId), "$ACTIVE\t$packageName")
            }
            publish()
        }
        return true
    }

    /** A recording start: its end if the caller's recording already ended, else a [claim]. */
    @Synchronized
    fun startRecording(sessionId: String, packageName: String, detached: Boolean): OpenLinkStart {
        val key = Key(OpenLink.CAPABILITY_RECORDING, sessionId)
        if (key !in owners) {
            finished[sessionId]?.takeIf { it.first == packageName }?.let { return OpenLinkStart.Ended(it.second) }
        }
        return if (claim(OpenLink.CAPABILITY_RECORDING, sessionId, packageName, detached)) OpenLinkStart.Begin else OpenLinkStart.Refused
    }

    /** Whether [packageName] may pause, resume or stop the recording [sessionId]: it owns it. */
    @Synchronized
    fun controls(sessionId: String, packageName: String): Boolean =
        owners[Key(OpenLink.CAPABILITY_RECORDING, sessionId)] == packageName

    @Synchronized
    fun release(capability: String, sessionId: String, packageName: String): Boolean {
        val key = Key(capability, sessionId)
        if (owners[key] != packageName) return false
        drop(key)
        publish()
        return true
    }

    /**
     * The host app reported [state]: returns the owner to tell, or null if no one holds the
     * session. A detached session the state ends is over for good: it is remembered as ended.
     */
    @Synchronized
    fun reported(state: OpenLinkRecordingState): String? {
        val key = Key(OpenLink.CAPABILITY_RECORDING, state.sessionId)
        val owner = owners[key] ?: return null
        val over = state.phase == OpenLinkRecordingPhase.STOPPED || state.phase == OpenLinkRecordingPhase.FAILED
        if (over && key in detached) {
            drop(key)
            remember(state.sessionId, owner, state)
            publish()
        }
        return owner
    }

    @Synchronized
    fun owner(capability: String, sessionId: String): String? = owners[Key(capability, sessionId)]

    /**
     * Removes and returns the session ids [packageName] owns under [capability]; with
     * [keepDetached], the detached ones stay.
     */
    @Synchronized
    fun releaseAll(packageName: String, capability: String, keepDetached: Boolean = false): List<String> {
        val keys = owners.filter { (key, owner) ->
            owner == packageName && key.capability == capability && !(keepDetached && key in detached)
        }.keys.toList()
        keys.forEach(::drop)
        if (keys.isNotEmpty()) publish()
        return keys.map { it.sessionId }
    }

    private fun drop(key: Key) {
        owners.remove(key)
        if (detached.remove(key)) storage?.remove(storageKey(key.sessionId))
    }

    private fun remember(sessionId: String, owner: String, last: OpenLinkRecordingState) {
        finished.remove(sessionId)
        finished[sessionId] = owner to last
        storage?.write(storageKey(sessionId), encodeFinished(owner, last))
        while (finished.size > MAX_FINISHED) {
            val eldest = finished.keys.first()
            finished.remove(eldest)
            storage?.remove(storageKey(eldest))
        }
    }

    private fun load() {
        val stored = storage?.read().orEmpty().filterKeys { it.startsWith(KEY_PREFIX) }
        val ended = mutableListOf<Triple<String, String, OpenLinkRecordingState>>()
        for ((storedKey, value) in stored) {
            val sessionId = storedKey.removePrefix(KEY_PREFIX)
            val fields = value.split('\t', limit = 6)
            when {
                fields.size == 2 && fields[0] == ACTIVE && fields[1].isNotEmpty() -> {
                    val key = Key(OpenLink.CAPABILITY_RECORDING, sessionId)
                    owners[key] = fields[1]
                    detached += key
                }
                fields.size == 6 && fields[0] == FINISHED -> {
                    val phase = runCatching { OpenLinkRecordingPhase.valueOf(fields[2]) }.getOrNull()
                    val at = fields[3].toLongOrNull()
                    if (phase != null && at != null) {
                        val last = OpenLinkRecordingState(sessionId, fields[5], phase, fields[4].ifEmpty { null }, at)
                        ended += Triple(sessionId, fields[1], last)
                    } else {
                        storage?.remove(storedKey)
                    }
                }
                // Written by a newer or broken build: not ours to keep.
                else -> storage?.remove(storedKey)
            }
        }
        ended.sortedBy { it.third.atMillis }.forEach { (sessionId, owner, last) -> finished[sessionId] = owner to last }
        while (finished.size > MAX_FINISHED) {
            val eldest = finished.keys.first()
            finished.remove(eldest)
            storage?.remove(storageKey(eldest))
        }
    }

    private fun publish() {
        _active.value = owners.map { (key, owner) -> OpenLinkActiveSession(owner, key.capability, key.sessionId) }
    }

    companion object {
        val SESSION_CAPABILITIES: Set<String> = setOf(OpenLink.CAPABILITY_RECORDING, OpenLink.CAPABILITY_LIVE)

        /** How many ended recordings are remembered. */
        const val MAX_FINISHED = 16

        /** Keys in the storage; the trust store's start with "client.", so both may share one. */
        const val KEY_PREFIX = "session."

        private const val ACTIVE = "R"
        private const val FINISHED = "F"

        private fun storageKey(sessionId: String) = KEY_PREFIX + sessionId

        private fun encodeFinished(owner: String, last: OpenLinkRecordingState): String =
            listOf(FINISHED, owner, last.phase.name, last.atMillis.toString(), last.reason.orEmpty(), last.deviceId).joinToString("\t")
    }
}
