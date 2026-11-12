package tech.mmarca.openlink.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkRecordingMode
import tech.mmarca.openlink.OpenLinkRecordingPhase
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openlink.OpenLinkWorkoutSummary

/** A recording session or live subscription a client holds on the host. */
data class OpenLinkActiveSession(
    val packageName: String,
    /** [OpenLink.CAPABILITY_RECORDING] or [OpenLink.CAPABILITY_LIVE]. */
    val capability: String,
    val sessionId: String,
)

/** What a start from a client comes to. */
sealed interface OpenLinkStart {
    /** The session is the caller's now: hand it to the host app. */
    data object Begin : OpenLinkStart

    /** The caller holds it already: nothing to hand on; tell the caller [last] if it is known. */
    data class Already(val last: OpenLinkRecordingState?) : OpenLinkStart

    /** Another client owns the session id, or the id is not one the host takes: nothing happens. */
    data object Refused : OpenLinkStart

    /** The caller's recording ended while it was away: tell it [last], start nothing. */
    data class Ended(val last: OpenLinkRecordingState) : OpenLinkStart

    /** The request cannot be taken: tell the caller `FAILED` with [reason]. */
    data class Failed(val reason: String) : OpenLinkStart
}

/**
 * Which client owns which recording session and live subscription, and the rules of a
 * recorded workout that need no device. Events for a session go to its owner only, and what a
 * client loses (its capability, its binding) ends what it owned. Plain Kotlin, tested on the JVM.
 *
 * A recorded workout (a session in [OpenLinkRecordingMode.RECORD]) outlives its owner's
 * binding. This class holds its state (paused or not, since when, being stopped) and passes on
 * only what changes it: a repeated or older pause or resume, a repeated start or stop, is
 * dropped here, so the host app sees each real change once. A stop by the owner keeps the
 * session until the host app reports its end, which then reaches the owner with its summary.
 *
 * With a [storage], recorded workouts and the last [MAX_FINISHED] ended ones survive a restart
 * of the host's process.
 */
class OpenLinkSessions(
    private val storage: OpenLinkTrustStorage? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Key(val capability: String, val sessionId: String)

    /** A recorded workout's state as the clients and the host app have set it. */
    private data class Workout(
        val paused: Boolean = false,
        /** The time of the last change, on the phone's clock: an older request is dropped. */
        val changedAtMillis: Long = 0L,
        /** The owner stopped it; it waits for the host app to report the end. */
        val stopping: Boolean = false,
        /** When the owner stopped it, on this host's clock. */
        val stoppedAtMillis: Long = 0L,
        /**
         * The last state the host app reported, for an owner that comes back. Not stored: after a
         * restart of the host it is unknown until the host app reports again.
         */
        val last: OpenLinkRecordingState? = null,
    )

    private val owners = linkedMapOf<Key, String>()

    /** The recorded workouts held now, by session id. */
    private val workouts = mutableMapOf<String, Workout>()

    /** The last recorded workouts the host ended, oldest first, with their owner and final state. */
    private val finished = linkedMapOf<String, Pair<String, OpenLinkRecordingState>>()

    private val _active = MutableStateFlow<List<OpenLinkActiveSession>>(emptyList())

    /** Every session held now, for the host app's UI (who is recording, a notification). */
    val active: StateFlow<List<OpenLinkActiveSession>> = _active.asStateFlow()

    init {
        load()
        publish()
    }

    /** Gives [sessionId] to [packageName], or confirms it already has it (a live subscription). */
    @Synchronized
    fun claim(capability: String, sessionId: String, packageName: String): Boolean {
        val key = Key(capability, sessionId)
        val owner = owners[key]
        if (owner != null && owner != packageName) return false
        if (owner == null) {
            owners[key] = packageName
            publish()
        }
        return true
    }

    /** A recording start from [packageName]. See [OpenLinkStart]. */
    @Synchronized
    fun startRecording(request: OpenLinkRecordingRequest, packageName: String): OpenLinkStart {
        expireStops()
        val sessionId = request.sessionId
        if (sessionId.isBlank() || sessionId.length > MAX_SESSION_ID_LENGTH) return OpenLinkStart.Refused
        val key = Key(OpenLink.CAPABILITY_RECORDING, sessionId)
        when (owners[key]) {
            packageName -> return OpenLinkStart.Already(workouts[sessionId]?.last)
            null -> Unit
            else -> return OpenLinkStart.Refused
        }
        finished[sessionId]?.takeIf { it.first == packageName }?.let { return OpenLinkStart.Ended(it.second) }
        if (request.mode == OpenLinkRecordingMode.RECORD) {
            if (request.deviceId.isNullOrBlank()) return OpenLinkStart.Failed(OpenLinkRecordingState.REASON_NO_DEVICE)
            val held = workouts.keys.count { owners[Key(OpenLink.CAPABILITY_RECORDING, it)] == packageName }
            if (held >= MAX_WORKOUTS_PER_APP) return OpenLinkStart.Failed(OpenLinkRecordingState.REASON_BUSY)
            workouts[sessionId] = Workout(changedAtMillis = request.startedAtMillis)
        }
        owners[key] = packageName
        save(sessionId)
        publish()
        return OpenLinkStart.Begin
    }

    /**
     * The owner asks to pause ([pause]) or resume its recorded workout at [atMillis]. True if it
     * changes the workout: the host app is to act on it. A stream session has no pause.
     */
    @Synchronized
    fun control(sessionId: String, packageName: String, pause: Boolean, atMillis: Long): Boolean {
        if (owners[Key(OpenLink.CAPABILITY_RECORDING, sessionId)] != packageName) return false
        val workout = workouts[sessionId] ?: return false
        if (workout.stopping || workout.paused == pause || atMillis < workout.changedAtMillis) return false
        workouts[sessionId] = workout.copy(paused = pause, changedAtMillis = atMillis)
        save(sessionId)
        return true
    }

    /**
     * The owner stops [sessionId]. True if the host app is to stop it. A stream session is
     * released at once; a recorded workout stays the owner's until the host app reports its end.
     */
    @Synchronized
    fun stopRecording(sessionId: String, packageName: String): Boolean {
        val key = Key(OpenLink.CAPABILITY_RECORDING, sessionId)
        if (owners[key] != packageName) return false
        val workout = workouts[sessionId]
        if (workout == null) {
            drop(key)
            publish()
            return true
        }
        if (workout.stopping) return false
        workouts[sessionId] = workout.copy(stopping = true, stoppedAtMillis = clock())
        save(sessionId)
        return true
    }

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
     * session. A pause or resume on the watch changes the workout as one from the owner would;
     * an end makes it over for good, remembered as ended. A later end for an ended one (a fuller
     * summary) replaces what is remembered and reaches its owner too.
     */
    @Synchronized
    fun reported(state: OpenLinkRecordingState): String? {
        val sessionId = state.sessionId
        val key = Key(OpenLink.CAPABILITY_RECORDING, sessionId)
        val over = state.phase == OpenLinkRecordingPhase.STOPPED || state.phase == OpenLinkRecordingPhase.FAILED
        val owner = owners[key] ?: run {
            val ended = finished[sessionId]?.first ?: return null
            if (!over) return null
            remember(sessionId, ended, state)
            return ended
        }
        val workout = workouts[sessionId] ?: return owner
        if (over) {
            drop(key)
            remember(sessionId, owner, state)
            publish()
            return owner
        }
        workouts[sessionId] = when (state.phase) {
            OpenLinkRecordingPhase.PAUSED, OpenLinkRecordingPhase.RECORDING -> workout.copy(
                paused = state.phase == OpenLinkRecordingPhase.PAUSED,
                changedAtMillis = maxOf(workout.changedAtMillis, state.atMillis),
                last = state,
            )
            else -> workout.copy(last = state)
        }
        save(sessionId)
        return owner
    }

    @Synchronized
    fun owner(capability: String, sessionId: String): String? = owners[Key(capability, sessionId)]

    /**
     * Removes and returns the session ids [packageName] owns under [capability]; with
     * [keepDetached], its recorded workouts stay. A recorded workout that ends here (its owner
     * lost the capability) is remembered as stopped by the host, so a start its owner replays
     * once it has the capability again does not begin a new workout.
     */
    @Synchronized
    fun releaseAll(packageName: String, capability: String, keepDetached: Boolean = false): List<String> {
        val keys = owners.filter { (key, owner) ->
            owner == packageName && key.capability == capability &&
                !(keepDetached && key.capability == OpenLink.CAPABILITY_RECORDING && key.sessionId in workouts)
        }.keys.toList()
        for (key in keys) {
            val recorded = key.capability == OpenLink.CAPABILITY_RECORDING && key.sessionId in workouts
            drop(key)
            if (recorded) remember(key.sessionId, packageName, hostStopped(key.sessionId))
        }
        if (keys.isNotEmpty()) publish()
        return keys.map { it.sessionId }
    }

    /**
     * A workout the owner stopped whose end the host app has not reported for [STOP_TIMEOUT_MILLIS]
     * (the watch out of reach, the host app lost it) is taken as ended, so it does not hold one of
     * the owner's places for ever. The real end, if it comes later, still reaches the owner.
     */
    private fun expireStops() {
        val now = clock()
        val expired = workouts.filter { (_, w) -> w.stopping && now - w.stoppedAtMillis >= STOP_TIMEOUT_MILLIS }.keys.toList()
        for (sessionId in expired) {
            val key = Key(OpenLink.CAPABILITY_RECORDING, sessionId)
            val owner = owners[key] ?: continue
            drop(key)
            remember(sessionId, owner, hostStopped(sessionId))
        }
        if (expired.isNotEmpty()) publish()
    }

    private fun hostStopped(sessionId: String) =
        OpenLinkRecordingState(sessionId, "", OpenLinkRecordingPhase.STOPPED, OpenLinkRecordingState.REASON_HOST_STOPPED, clock())

    private fun drop(key: Key) {
        owners.remove(key)
        if (key.capability == OpenLink.CAPABILITY_RECORDING && workouts.remove(key.sessionId) != null) {
            storage?.remove(storageKey(key.sessionId))
        }
    }

    /** Writes the recorded workout [sessionId], if it is one, to the storage. */
    private fun save(sessionId: String) {
        val workout = workouts[sessionId] ?: return
        val owner = owners[Key(OpenLink.CAPABILITY_RECORDING, sessionId)] ?: return
        storage?.write(
            storageKey(sessionId),
            listOf(ACTIVE, owner, flag(workout.paused), workout.changedAtMillis.toString(), flag(workout.stopping), workout.stoppedAtMillis.toString())
                .joinToString("\t"),
        )
    }

    private fun remember(sessionId: String, owner: String, last: OpenLinkRecordingState) {
        finished.remove(sessionId)
        finished[sessionId] = owner to last
        storage?.write(storageKey(sessionId), encodeFinished(owner, last))
        trimFinished()
    }

    private fun trimFinished() {
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
            val kept = when (fields[0]) {
                ACTIVE -> loadWorkout(sessionId, fields)
                FINISHED -> decodeFinished(sessionId, fields)?.let { ended += Triple(sessionId, fields[1], it) } != null
                else -> false
            }
            // Written by a newer or broken build: not ours to keep.
            if (!kept) storage?.remove(storedKey)
        }
        ended.sortedBy { it.third.atMillis }.forEach { (sessionId, owner, last) -> finished[sessionId] = owner to last }
        trimFinished()
    }

    private fun loadWorkout(sessionId: String, fields: List<String>): Boolean {
        if (fields.size != 6 || fields[1].isEmpty()) return false
        val changedAt = fields[3].toLongOrNull() ?: return false
        val stoppedAt = fields[5].toLongOrNull() ?: return false
        owners[Key(OpenLink.CAPABILITY_RECORDING, sessionId)] = fields[1]
        workouts[sessionId] = Workout(paused = fields[2] == "1", changedAtMillis = changedAt, stopping = fields[4] == "1", stoppedAtMillis = stoppedAt)
        return true
    }

    private fun publish() {
        _active.value = owners.map { (key, owner) -> OpenLinkActiveSession(owner, key.capability, key.sessionId) }
    }

    companion object {
        val SESSION_CAPABILITIES: Set<String> = setOf(OpenLink.CAPABILITY_RECORDING, OpenLink.CAPABILITY_LIVE)

        /** How many ended recordings are remembered. */
        const val MAX_FINISHED = 16

        /** How many recorded workouts one app may hold at once; one per device is the usual. */
        const val MAX_WORKOUTS_PER_APP = 4

        /** How long a stopped workout waits for its end before it is taken as ended. */
        const val STOP_TIMEOUT_MILLIS = 30L * 60 * 1000

        /** The longest session id the host takes: the client chooses it, and it is stored. */
        const val MAX_SESSION_ID_LENGTH = 128

        /** Keys in the storage; the trust store's start with "client.", so both may share one. */
        const val KEY_PREFIX = "session."

        private const val ACTIVE = "R"
        private const val FINISHED = "F"

        private fun storageKey(sessionId: String) = KEY_PREFIX + sessionId

        private fun flag(value: Boolean) = if (value) "1" else "0"

        // The summary goes before the device id, which is last and may hold anything: the summary
        // is five ";"-separated fields of numbers and ","-separated lists, then a tab.
        private fun encodeFinished(owner: String, last: OpenLinkRecordingState): String {
            val summary = last.summary?.let {
                listOf(
                    it.zoneSeconds.joinToString(","),
                    it.zoneLowerBoundsBpm.joinToString(","),
                    it.trimp?.toString().orEmpty(),
                    it.heartRateRecovery1MinBpm?.toString().orEmpty(),
                    it.heartRateRecovery2MinBpm?.toString().orEmpty(),
                ).joinToString(";")
            }.orEmpty()
            return listOf(FINISHED, owner, last.phase.name, last.atMillis.toString(), last.reason.orEmpty(), summary + "\t" + last.deviceId)
                .joinToString("\t")
        }

        private fun decodeFinished(sessionId: String, fields: List<String>): OpenLinkRecordingState? {
            if (fields.size != 6 || fields[1].isEmpty()) return null
            val phase = runCatching { OpenLinkRecordingPhase.valueOf(fields[2]) }.getOrNull() ?: return null
            val at = fields[3].toLongOrNull() ?: return null
            val rest = fields[5]
            val tab = rest.indexOf('\t')
            if (tab < 0) return null
            return OpenLinkRecordingState(sessionId, rest.substring(tab + 1), phase, fields[4].ifEmpty { null }, at, decodeSummary(rest.substring(0, tab)))
        }

        private fun decodeSummary(text: String): OpenLinkWorkoutSummary? {
            val parts = text.split(';')
            if (parts.size != 5) return null
            fun ints(list: String) = if (list.isEmpty()) emptyList() else list.split(',').mapNotNull(String::toIntOrNull)
            return OpenLinkWorkoutSummary(
                zoneSeconds = ints(parts[0]),
                zoneLowerBoundsBpm = ints(parts[1]),
                trimp = parts[2].toDoubleOrNull(),
                heartRateRecovery1MinBpm = parts[3].toIntOrNull(),
                heartRateRecovery2MinBpm = parts[4].toIntOrNull(),
            )
        }
    }
}
