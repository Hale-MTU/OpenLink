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

    /** An ended workout: its id is the owner's, and another app may use the same one. */
    private data class Done(val owner: String, val sessionId: String)

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
         * The host app reported it recording or paused once: a workout ran. A failure before that
         * is a refused start, which leaves the session id free for another try.
         */
        val ran: Boolean = false,
        /**
         * The last state the host app reported, for an owner that comes back. Not stored: after a
         * restart of the host it is unknown until the host app reports again.
         */
        val last: OpenLinkRecordingState? = null,
    )

    private val owners = linkedMapOf<Key, String>()

    /**
     * When each recording session was given to its owner, on this host's clock. The host app
     * names a session by its id only: a report older than this is about an ended workout that
     * had the same id before.
     */
    private val begunAt = mutableMapOf<String, Long>()

    /** The recorded workouts held now, by session id. */
    private val workouts = mutableMapOf<String, Workout>()

    /** The last recorded workouts the host ended, oldest first, with their final state. */
    private val finished = linkedMapOf<Done, OpenLinkRecordingState>()

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
        val sessionId = request.sessionId
        if (sessionId.isBlank() || sessionId.length > MAX_SESSION_ID_LENGTH) return OpenLinkStart.Refused
        val key = Key(OpenLink.CAPABILITY_RECORDING, sessionId)
        when (owners[key]) {
            // A stream session's repeat goes to the host app again: one that failed (its device
            // gone) is retried, as in version 1.
            packageName -> return workouts[sessionId]?.let { OpenLinkStart.Already(it.last) }
                // A recorded workout under the id of the caller's own stream session: not taken.
                ?: if (request.mode == OpenLinkRecordingMode.RECORD) OpenLinkStart.Failed(OpenLinkRecordingState.REASON_BUSY) else OpenLinkStart.Begin
            null -> Unit
            else -> return OpenLinkStart.Refused
        }
        finished[Done(packageName, sessionId)]?.let { return OpenLinkStart.Ended(it) }
        if (request.mode == OpenLinkRecordingMode.RECORD) {
            if (request.deviceId.isNullOrBlank()) return OpenLinkStart.Failed(OpenLinkRecordingState.REASON_NO_DEVICE)
            val held = workouts.keys.count { owners[Key(OpenLink.CAPABILITY_RECORDING, it)] == packageName }
            if (held >= MAX_WORKOUTS_PER_APP) return OpenLinkStart.Failed(OpenLinkRecordingState.REASON_BUSY)
            workouts[sessionId] = Workout(changedAtMillis = request.startedAtMillis)
        }
        owners[key] = packageName
        begunAt[sessionId] = clock()
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

    /**
     * A stop from [packageName] for [sessionId] that it does not hold: the end to tell it, so a
     * client that waits for its end hears one. Its remembered end if the workout ended (while it
     * was away), else `STOPPED` as asked (the host never began it: another app holds the id, or
     * the start was never taken). Null if it holds the session: the stop was a repeat.
     */
    @Synchronized
    fun unheldStop(sessionId: String, packageName: String): OpenLinkRecordingState? {
        if (owners[Key(OpenLink.CAPABILITY_RECORDING, sessionId)] == packageName) return null
        return finished[Done(packageName, sessionId)]
            ?: OpenLinkRecordingState(sessionId, "", OpenLinkRecordingPhase.STOPPED, OpenLinkRecordingState.REASON_STOPPED_BY_CLIENT, clock())
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
     * summary) replaces what is remembered and reaches its owner too, also once another app
     * took the same session id: a report older than that app's start is not about its session.
     */
    @Synchronized
    fun reported(state: OpenLinkRecordingState): String? {
        val sessionId = state.sessionId
        val key = Key(OpenLink.CAPABILITY_RECORDING, sessionId)
        val over = state.phase.isOver
        val current = owners[key]?.takeUnless {
            state.atMillis < (begunAt[sessionId] ?: Long.MIN_VALUE) && finished.keys.any { it.sessionId == sessionId }
        }
        val owner = current ?: run {
            if (!over) return null
            // The host app names the session only: the latest that ended under this id.
            val ended = finished.keys.lastOrNull { it.sessionId == sessionId }?.owner ?: return null
            remember(sessionId, ended, state)
            return ended
        }
        val workout = workouts[sessionId] ?: return owner
        if (over) {
            drop(key)
            // A start the host app refused (busy, not connected) ran no workout: nothing to keep.
            if (workout.ran || state.phase == OpenLinkRecordingPhase.STOPPED) remember(sessionId, owner, state)
            publish()
            return owner
        }
        val updated = when (state.phase) {
            OpenLinkRecordingPhase.PAUSED, OpenLinkRecordingPhase.RECORDING -> {
                val paused = state.phase == OpenLinkRecordingPhase.PAUSED
                // Only a newer report that flips it is a change: an older one (a late start
                // confirmation) does not undo the owner's pause, and a repeat (the device came
                // back) does not make the owner's earlier wish older than it.
                val changed = paused != workout.paused && state.atMillis >= workout.changedAtMillis
                workout.copy(
                    paused = if (changed) paused else workout.paused,
                    changedAtMillis = if (changed) state.atMillis else workout.changedAtMillis,
                    ran = true,
                    last = state,
                )
            }
            else -> workout.copy(last = state)
        }
        workouts[sessionId] = updated
        // `last` is not stored: write only what a restart keeps.
        if (updated.copy(last = null) != workout.copy(last = null)) save(sessionId)
        return owner
    }

    @Synchronized
    fun owner(capability: String, sessionId: String): String? = owners[Key(capability, sessionId)]

    /** A session [releaseAll] ended; [stopOwed] if the host app has not been handed its stop yet. */
    data class Released(val sessionId: String, val stopOwed: Boolean)

    /**
     * Removes and returns the sessions [packageName] owns under [capability]; with
     * [keepDetached], its recorded workouts stay. A recorded workout that ends here (its owner
     * lost the capability) is remembered as stopped by the host, so a start its owner replays
     * once it has the capability again does not begin a new workout. One the owner stopped
     * already owes the host app no second stop.
     */
    @Synchronized
    fun releaseAll(packageName: String, capability: String, keepDetached: Boolean = false): List<Released> {
        val keys = owners.filter { (key, owner) ->
            owner == packageName && key.capability == capability &&
                !(keepDetached && key.capability == OpenLink.CAPABILITY_RECORDING && key.sessionId in workouts)
        }.keys.toList()
        val released = keys.map { key ->
            val workout = if (key.capability == OpenLink.CAPABILITY_RECORDING) workouts[key.sessionId] else null
            drop(key)
            if (workout != null) remember(key.sessionId, packageName, hostStopped(key.sessionId))
            Released(key.sessionId, stopOwed = workout?.stopping != true)
        }
        if (keys.isNotEmpty()) publish()
        return released
    }

    /**
     * A workout the owner stopped whose end the host app has not reported for [STOP_TIMEOUT_MILLIS]
     * (the watch out of reach, the host app lost it) is taken as ended, so it does not hold one of
     * the owner's places for ever. Returns each such end with the owner to tell. The real end, if
     * it comes later, still reaches the owner. The host runs this at [nextStopDeadline] and before
     * a start.
     */
    @Synchronized
    fun expireStops(): List<Pair<String, OpenLinkRecordingState>> {
        val now = clock()
        val expired = workouts.filter { (_, w) -> w.stopping && now - w.stoppedAtMillis >= STOP_TIMEOUT_MILLIS }.keys.toList()
        val ends = expired.mapNotNull { sessionId ->
            val key = Key(OpenLink.CAPABILITY_RECORDING, sessionId)
            val owner = owners[key] ?: return@mapNotNull null
            drop(key)
            val end = hostStopped(sessionId)
            remember(sessionId, owner, end)
            owner to end
        }
        if (expired.isNotEmpty()) publish()
        return ends
    }

    /** When the next stopped workout's end is due on this host's clock, or null if none waits. */
    @Synchronized
    fun nextStopDeadline(): Long? =
        workouts.values.filter { it.stopping }.minOfOrNull { it.stoppedAtMillis + STOP_TIMEOUT_MILLIS }

    private fun hostStopped(sessionId: String) =
        OpenLinkRecordingState(sessionId, "", OpenLinkRecordingPhase.STOPPED, OpenLinkRecordingState.REASON_HOST_STOPPED, clock())

    private fun drop(key: Key) {
        owners.remove(key)
        if (key.capability != OpenLink.CAPABILITY_RECORDING) return
        begunAt.remove(key.sessionId)
        if (workouts.remove(key.sessionId) != null) storage?.remove(storageKey(key.sessionId))
    }

    /** Writes the recorded workout [sessionId], if it is one, to the storage. */
    private fun save(sessionId: String) {
        val workout = workouts[sessionId] ?: return
        val owner = owners[Key(OpenLink.CAPABILITY_RECORDING, sessionId)] ?: return
        storage?.write(
            storageKey(sessionId),
            listOf(
                ACTIVE, owner, flag(workout.paused), workout.changedAtMillis.toString(), flag(workout.stopping),
                workout.stoppedAtMillis.toString(), flag(workout.ran), begunAt[sessionId]?.toString().orEmpty(),
            )
                .joinToString("\t"),
        )
    }

    private fun remember(sessionId: String, owner: String, last: OpenLinkRecordingState) {
        val done = Done(owner, sessionId)
        finished.remove(done)
        finished[done] = last
        storage?.write(endedKey(done), encodeFinished(owner, last))
        trimFinished()
    }

    private fun trimFinished() {
        while (finished.size > MAX_FINISHED) {
            val eldest = finished.keys.first()
            finished.remove(eldest)
            storage?.remove(endedKey(eldest))
        }
    }

    private fun load() {
        val stored = storage?.read().orEmpty()
        val ended = mutableListOf<Pair<Done, OpenLinkRecordingState>>()
        for ((storedKey, value) in stored) {
            val kept = when {
                storedKey.startsWith(KEY_PREFIX) ->
                    value.startsWith(ACTIVE) && loadWorkout(storedKey.removePrefix(KEY_PREFIX), value.split('\t', limit = 8))
                storedKey.startsWith(ENDED_PREFIX) -> {
                    // The owner, a package name, holds no "/": the session id is all after the first.
                    val rest = storedKey.removePrefix(ENDED_PREFIX)
                    val sessionId = rest.substringAfter('/', "")
                    val fields = value.split('\t', limit = 6)
                    val last = if (fields[0] == FINISHED && sessionId.isNotEmpty()) decodeFinished(sessionId, fields) else null
                    if (last != null && rest == fields[1] + "/" + sessionId) ended += Done(fields[1], sessionId) to last
                    last != null
                }
                else -> continue
            }
            // Written by a newer or broken build: not ours to keep.
            if (!kept) storage?.remove(storedKey)
        }
        ended.sortedBy { it.second.atMillis }.forEach { (done, last) -> finished[done] = last }
        trimFinished()
    }

    private fun loadWorkout(sessionId: String, fields: List<String>): Boolean {
        // Seven fields: written before the start time was kept.
        if (fields.size !in 7..8 || fields[1].isEmpty()) return false
        val changedAt = fields[3].toLongOrNull() ?: return false
        val stoppedAt = fields[5].toLongOrNull() ?: return false
        owners[Key(OpenLink.CAPABILITY_RECORDING, sessionId)] = fields[1]
        fields.getOrNull(7)?.toLongOrNull()?.let { begunAt[sessionId] = it }
        workouts[sessionId] = Workout(
            paused = fields[2] == "1",
            changedAtMillis = changedAt,
            stopping = fields[4] == "1",
            stoppedAtMillis = stoppedAt,
            ran = fields[6] == "1",
        )
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

        /**
         * Keys in the storage of the workouts held now, by session id, and of ended ones, by owner
         * and session id; the trust store's start with "client.", so both may share one.
         */
        const val KEY_PREFIX = "session."
        const val ENDED_PREFIX = "ended."

        private const val ACTIVE = "R"
        private const val FINISHED = "F"

        private fun storageKey(sessionId: String) = KEY_PREFIX + sessionId

        private fun endedKey(done: Done) = ENDED_PREFIX + done.owner + "/" + done.sessionId

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
            return listOf(FINISHED, owner, last.phase.name, last.atMillis.toString(), last.reason.orEmpty().replace('\t', ' '), summary + "\t" + last.deviceId)
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
