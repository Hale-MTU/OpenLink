package tech.mmarca.openlink.host

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkRecordingMode
import tech.mmarca.openlink.OpenLinkRecordingPhase
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openlink.OpenLinkWorkoutSummary

class OpenLinkSessionsTest {

    private var now = 1_000L
    private val sessions = OpenLinkSessions(clock = { now })

    private class MapStorage : OpenLinkTrustStorage {
        val map = mutableMapOf<String, String>()
        var writes = 0
        override fun read(): Map<String, String> = map.toMap()
        override fun write(key: String, value: String) { map[key] = value; writes++ }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun workout(id: String, at: Long = 0L, device: String? = "w1") =
        OpenLinkRecordingRequest(id, startedAtMillis = at, deviceId = device, mode = OpenLinkRecordingMode.RECORD)

    private fun stream(id: String) = OpenLinkRecordingRequest(id)

    private fun state(id: String, phase: OpenLinkRecordingPhase, at: Long = 0L) = OpenLinkRecordingState(id, "w1", phase, atMillis = at)

    private fun stopped(id: String, at: Long = 0L) =
        OpenLinkRecordingState(id, "w1", OpenLinkRecordingPhase.STOPPED, OpenLinkRecordingState.REASON_STOPPED_ON_DEVICE, at)

    // --- ownership

    @Test
    fun `a session belongs to the app that opened it`() {
        assertThat(sessions.claim(OpenLink.CAPABILITY_LIVE, "s1", "a")).isTrue()
        assertThat(sessions.claim(OpenLink.CAPABILITY_LIVE, "s1", "b")).isFalse()
        assertThat(sessions.release(OpenLink.CAPABILITY_LIVE, "s1", "b")).isFalse()
        assertThat(sessions.owner(OpenLink.CAPABILITY_LIVE, "s1")).isEqualTo("a")
    }

    @Test
    fun `losing a capability ends only that app's sessions under it`() {
        sessions.claim(OpenLink.CAPABILITY_LIVE, "s1", "a")
        sessions.startRecording(stream("s1"), "a")
        sessions.claim(OpenLink.CAPABILITY_LIVE, "s2", "b")

        assertThat(sessions.releaseAll("a", OpenLink.CAPABILITY_LIVE).map { it.sessionId }).containsExactly("s1")
        assertThat(sessions.owner(OpenLink.CAPABILITY_RECORDING, "s1")).isEqualTo("a")
        assertThat(sessions.owner(OpenLink.CAPABILITY_LIVE, "s2")).isEqualTo("b")
    }

    @Test
    fun `the sessions held are published as they come and go`() {
        sessions.startRecording(stream("s1"), "a")
        sessions.claim(OpenLink.CAPABILITY_LIVE, "s1", "a")
        assertThat(sessions.active.value).containsExactly(
            OpenLinkActiveSession("a", OpenLink.CAPABILITY_RECORDING, "s1"),
            OpenLinkActiveSession("a", OpenLink.CAPABILITY_LIVE, "s1"),
        )

        sessions.stopRecording("s1", "a")
        sessions.releaseAll("a", OpenLink.CAPABILITY_LIVE)

        assertThat(sessions.active.value).isEmpty()
    }

    @Test
    fun `a recorded workout survives its owner leaving, a stream session does not`() {
        sessions.startRecording(stream("plain"), "a")
        sessions.startRecording(workout("watch"), "a")

        assertThat(sessions.releaseAll("a", OpenLink.CAPABILITY_RECORDING, keepDetached = true).map { it.sessionId }).containsExactly("plain")
        assertThat(sessions.owner(OpenLink.CAPABILITY_RECORDING, "watch")).isEqualTo("a")
        // Losing the capability ends it too.
        assertThat(sessions.releaseAll("a", OpenLink.CAPABILITY_RECORDING).map { it.sessionId }).containsExactly("watch")
        assertThat(sessions.active.value).isEmpty()
    }

    // --- starts

    @Test
    fun `a repeated start is not handed on again, and tells where the workout stands`() {
        assertThat(sessions.startRecording(workout("watch"), "a")).isEqualTo(OpenLinkStart.Begin)
        assertThat(sessions.startRecording(workout("watch"), "a")).isEqualTo(OpenLinkStart.Already(null))
        assertThat(sessions.startRecording(workout("watch"), "b")).isEqualTo(OpenLinkStart.Refused)

        val paused = state("watch", OpenLinkRecordingPhase.PAUSED, at = 5)
        sessions.reported(paused)
        assertThat(sessions.startRecording(workout("watch"), "a")).isEqualTo(OpenLinkStart.Already(paused))
        // A repeat with another mode keeps the first: it is still a recorded workout.
        assertThat(sessions.startRecording(stream("watch"), "a")).isEqualTo(OpenLinkStart.Already(paused))
        assertThat(sessions.releaseAll("a", OpenLink.CAPABILITY_RECORDING, keepDetached = true)).isEmpty()
    }

    @Test
    fun `a recorded workout needs a device, a short id and room`() {
        assertThat(sessions.startRecording(workout("x", device = null), "a"))
            .isEqualTo(OpenLinkStart.Failed(OpenLinkRecordingState.REASON_NO_DEVICE))
        assertThat(sessions.startRecording(workout("x", device = " "), "a"))
            .isEqualTo(OpenLinkStart.Failed(OpenLinkRecordingState.REASON_NO_DEVICE))
        assertThat(sessions.startRecording(workout("x".repeat(OpenLinkSessions.MAX_SESSION_ID_LENGTH + 1)), "a"))
            .isEqualTo(OpenLinkStart.Refused)
        assertThat(sessions.startRecording(workout(" "), "a")).isEqualTo(OpenLinkStart.Refused)

        repeat(OpenLinkSessions.MAX_WORKOUTS_PER_APP) { assertThat(sessions.startRecording(workout("w$it"), "a")).isEqualTo(OpenLinkStart.Begin) }
        assertThat(sessions.startRecording(workout("more"), "a")).isEqualTo(OpenLinkStart.Failed(OpenLinkRecordingState.REASON_BUSY))
        // Another app has its own room, and a stream session needs none.
        assertThat(sessions.startRecording(workout("more"), "b")).isEqualTo(OpenLinkStart.Begin)
        assertThat(sessions.startRecording(stream("plain"), "a")).isEqualTo(OpenLinkStart.Begin)
    }

    // --- pause and resume

    @Test
    fun `only a change from the owner is handed on, never a repeat or an older one`() {
        sessions.startRecording(workout("watch", at = 10), "a")

        assertThat(sessions.control("watch", "b", pause = true, atMillis = 20)).isFalse()
        assertThat(sessions.control("watch", "a", pause = false, atMillis = 20)).isFalse() // running already
        assertThat(sessions.control("watch", "a", pause = true, atMillis = 5)).isFalse() // older than the start
        assertThat(sessions.control("watch", "a", pause = true, atMillis = 20)).isTrue()
        assertThat(sessions.control("watch", "a", pause = true, atMillis = 30)).isFalse() // paused already
        assertThat(sessions.control("watch", "a", pause = false, atMillis = 15)).isFalse() // older than the pause
        assertThat(sessions.control("watch", "a", pause = false, atMillis = 40)).isTrue()
        assertThat(sessions.control("nope", "a", pause = true, atMillis = 50)).isFalse()
    }

    @Test
    fun `a stream session has no pause`() {
        sessions.startRecording(stream("plain"), "a")

        assertThat(sessions.control("plain", "a", pause = true, atMillis = 1)).isFalse()
    }

    @Test
    fun `a press on the watch counts like one from the owner, so an older wish is dropped`() {
        sessions.startRecording(workout("watch", at = 0), "a")
        assertThat(sessions.control("watch", "a", pause = true, atMillis = 1)).isTrue()
        // Resumed on the watch at 2.
        assertThat(sessions.reported(state("watch", OpenLinkRecordingPhase.RECORDING, at = 2))).isEqualTo("a")

        // The phone's pause from 1, replayed after a reconnect, is older than the watch's resume.
        assertThat(sessions.control("watch", "a", pause = true, atMillis = 1)).isFalse()
        assertThat(sessions.control("watch", "a", pause = true, atMillis = 3)).isTrue()
    }

    // --- stops and ends

    @Test
    fun `a stop by the owner waits for the end, which reaches it with the summary`() {
        sessions.startRecording(workout("watch"), "a")

        assertThat(sessions.stopRecording("watch", "b")).isFalse()
        assertThat(sessions.stopRecording("watch", "a")).isTrue()
        assertThat(sessions.stopRecording("watch", "a")).isFalse() // a repeat
        assertThat(sessions.control("watch", "a", pause = true, atMillis = 9)).isFalse() // stopping
        assertThat(sessions.owner(OpenLink.CAPABILITY_RECORDING, "watch")).isEqualTo("a")

        val end = stopped("watch", at = 10).copy(summary = OpenLinkWorkoutSummary(trimp = 50.0))
        assertThat(sessions.reported(end)).isEqualTo("a")
        assertThat(sessions.owner(OpenLink.CAPABILITY_RECORDING, "watch")).isNull()
        assertThat(sessions.startRecording(workout("watch"), "a")).isEqualTo(OpenLinkStart.Ended(end))
    }

    @Test
    fun `a stream session is released by its stop at once`() {
        sessions.startRecording(stream("plain"), "a")

        assertThat(sessions.stopRecording("plain", "a")).isTrue()
        assertThat(sessions.owner(OpenLink.CAPABILITY_RECORDING, "plain")).isNull()
    }

    @Test
    fun `an ended recording answers its owner's replayed start, and no one else's`() {
        sessions.startRecording(workout("watch"), "a")
        assertThat(sessions.reported(stopped("watch"))).isEqualTo("a")

        assertThat(sessions.active.value).isEmpty()
        assertThat(sessions.startRecording(workout("watch"), "a")).isEqualTo(OpenLinkStart.Ended(stopped("watch")))
        // Another app's session under the same id is its own.
        assertThat(sessions.startRecording(workout("watch"), "b")).isEqualTo(OpenLinkStart.Begin)
    }

    @Test
    fun `a report reaches the owner, and ends only a recorded workout`() {
        sessions.startRecording(stream("plain"), "a")
        assertThat(sessions.reported(stopped("plain"))).isEqualTo("a")
        assertThat(sessions.owner(OpenLink.CAPABILITY_RECORDING, "plain")).isEqualTo("a")
        assertThat(sessions.reported(stopped("nobody"))).isNull()

        sessions.startRecording(workout("watch"), "a")
        assertThat(sessions.reported(state("watch", OpenLinkRecordingPhase.PAUSED))).isEqualTo("a")
        assertThat(sessions.owner(OpenLink.CAPABILITY_RECORDING, "watch")).isEqualTo("a")
    }

    @Test
    fun `a fuller end of an ended workout reaches its owner and replaces the remembered one`() {
        sessions.startRecording(workout("watch"), "a")
        sessions.reported(stopped("watch", at = 1))
        val fuller = stopped("watch", at = 2).copy(summary = OpenLinkWorkoutSummary(heartRateRecovery2MinBpm = 40))

        assertThat(sessions.reported(fuller)).isEqualTo("a")
        assertThat(sessions.startRecording(workout("watch"), "a")).isEqualTo(OpenLinkStart.Ended(fuller))
        // A non-final state for an ended workout goes nowhere.
        assertThat(sessions.reported(state("watch", OpenLinkRecordingPhase.RECORDING))).isNull()
    }

    @Test
    fun `old ended recordings are forgotten`() {
        repeat(OpenLinkSessions.MAX_FINISHED + 1) { i ->
            sessions.startRecording(workout("w$i"), "a")
            sessions.reported(stopped("w$i", at = i.toLong()))
        }
        assertThat(sessions.startRecording(workout("w0"), "a")).isEqualTo(OpenLinkStart.Begin)
        assertThat(sessions.startRecording(workout("w${OpenLinkSessions.MAX_FINISHED}"), "a"))
            .isInstanceOf(OpenLinkStart.Ended::class.java)
    }

    // --- storage

    @Test
    fun `a host that restarts keeps recorded workouts with their state, and ended ones`() {
        val storage = MapStorage()
        OpenLinkSessions(storage).apply {
            startRecording(workout("paused", at = 1), "a")
            control("paused", "a", pause = true, atMillis = 5)
            startRecording(workout("stopping"), "a")
            stopRecording("stopping", "a")
            startRecording(workout("ended"), "a")
            reported(stopped("ended", at = 7))
            startRecording(stream("plain"), "a")
            claim(OpenLink.CAPABILITY_LIVE, "paused", "a")
        }

        val after = OpenLinkSessions(storage)

        assertThat(after.active.value).containsExactly(
            OpenLinkActiveSession("a", OpenLink.CAPABILITY_RECORDING, "paused"),
            OpenLinkActiveSession("a", OpenLink.CAPABILITY_RECORDING, "stopping"),
        )
        assertThat(after.control("paused", "a", pause = true, atMillis = 6)).isFalse() // still paused
        assertThat(after.control("paused", "a", pause = false, atMillis = 4)).isFalse() // older than the pause
        assertThat(after.control("stopping", "a", pause = true, atMillis = 9)).isFalse()
        assertThat(after.stopRecording("stopping", "a")).isFalse()
        assertThat(after.startRecording(workout("ended"), "a")).isEqualTo(OpenLinkStart.Ended(stopped("ended", at = 7)))
        // The end of a stopping one removes it from the storage too.
        after.reported(stopped("stopping", at = 8))
        assertThat(OpenLinkSessions(storage).active.value).containsExactly(OpenLinkActiveSession("a", OpenLink.CAPABILITY_RECORDING, "paused"))
    }

    @Test
    fun `an ended workout's summary survives a restart`() {
        val storage = MapStorage()
        val summary = OpenLinkWorkoutSummary(
            zoneSeconds = listOf(60, 0, 300),
            zoneLowerBoundsBpm = listOf(100, 120, 140),
            trimp = 42.5,
            heartRateRecovery1MinBpm = 25,
        )
        val last = stopped("watch", at = 9).copy(deviceId = "dev\twith;tab", summary = summary)
        OpenLinkSessions(storage).apply {
            startRecording(workout("watch"), "a")
            reported(last)
        }

        assertThat(OpenLinkSessions(storage).startRecording(workout("watch"), "a")).isEqualTo(OpenLinkStart.Ended(last))
    }

    @Test
    fun `the storage holds only its own keys, and drops what it cannot read`() {
        val storage = MapStorage().apply {
            map["client.x"] = "trust record"
            map["session.bad"] = "?"
            map["session.short"] = "R\ta"
            map["session.ok"] = "R\ta\t0\t3\t0\t0\t1"
        }

        val sessions = OpenLinkSessions(storage)

        assertThat(sessions.owner(OpenLink.CAPABILITY_RECORDING, "ok")).isEqualTo("a")
        assertThat(storage.map.keys).containsExactly("client.x", "session.ok")
    }

    @Test
    fun `the storage keeps no more ended recordings than are remembered`() {
        val storage = MapStorage()
        val sessions = OpenLinkSessions(storage)
        repeat(OpenLinkSessions.MAX_FINISHED + 3) { i ->
            sessions.startRecording(workout("w$i"), "a")
            sessions.reported(stopped("w$i", at = i.toLong()))
        }
        assertThat(storage.map).hasSize(OpenLinkSessions.MAX_FINISHED)
    }

    @Test
    fun `a workout ended by a lost capability is not begun again by a replayed start`() {
        sessions.startRecording(workout("watch"), "a")

        assertThat(sessions.releaseAll("a", OpenLink.CAPABILITY_RECORDING).map { it.sessionId }).containsExactly("watch")

        val start = sessions.startRecording(workout("watch"), "a")
        assertThat(start).isInstanceOf(OpenLinkStart.Ended::class.java)
        assertThat((start as OpenLinkStart.Ended).last.reason).isEqualTo(OpenLinkRecordingState.REASON_HOST_STOPPED)
    }

    @Test
    fun `a stop whose end never comes frees its place after the timeout, and a late end still counts`() {
        repeat(OpenLinkSessions.MAX_WORKOUTS_PER_APP) {
            sessions.startRecording(workout("w$it"), "a")
            sessions.stopRecording("w$it", "a")
        }
        assertThat(sessions.nextStopDeadline()).isEqualTo(now + OpenLinkSessions.STOP_TIMEOUT_MILLIS)
        now += OpenLinkSessions.STOP_TIMEOUT_MILLIS - 1
        assertThat(sessions.expireStops()).isEmpty()
        assertThat(sessions.startRecording(workout("more"), "a")).isEqualTo(OpenLinkStart.Failed(OpenLinkRecordingState.REASON_BUSY))

        now += 1
        // Each owner is told its workout ended.
        val ends = sessions.expireStops()
        assertThat(ends.map { it.first to it.second.sessionId })
            .containsExactlyElementsIn((0 until OpenLinkSessions.MAX_WORKOUTS_PER_APP).map { "a" to "w$it" })
        assertThat(ends.map { it.second.reason }.toSet()).containsExactly(OpenLinkRecordingState.REASON_HOST_STOPPED)
        assertThat(sessions.nextStopDeadline()).isNull()
        assertThat(sessions.startRecording(workout("more"), "a")).isEqualTo(OpenLinkStart.Begin)
        assertThat(sessions.owner(OpenLink.CAPABILITY_RECORDING, "w0")).isNull()

        val late = stopped("w0", at = now)
        assertThat(sessions.reported(late)).isEqualTo("a")
        assertThat(sessions.startRecording(workout("w0"), "a")).isEqualTo(OpenLinkStart.Ended(late))
    }

    @Test
    fun `a stopping workout keeps its timeout through a restart`() {
        val storage = MapStorage()
        OpenLinkSessions(storage, clock = { now }).apply {
            startRecording(workout("watch"), "a")
            stopRecording("watch", "a")
        }
        now += OpenLinkSessions.STOP_TIMEOUT_MILLIS

        val after = OpenLinkSessions(storage, clock = { now })
        assertThat(after.nextStopDeadline()).isEqualTo(now)
        assertThat(after.expireStops().map { it.first }).containsExactly("a")
        assertThat(after.owner(OpenLink.CAPABILITY_RECORDING, "watch")).isNull()
    }

    @Test
    fun `a refused start does not use up its session id`() {
        assertThat(sessions.startRecording(workout("watch", device = null), "a"))
            .isEqualTo(OpenLinkStart.Failed(OpenLinkRecordingState.REASON_NO_DEVICE))

        assertThat(sessions.startRecording(workout("watch"), "a")).isEqualTo(OpenLinkStart.Begin)
    }

    private fun failed(id: String, reason: String = OpenLinkRecordingState.REASON_BUSY) =
        OpenLinkRecordingState(id, "w1", OpenLinkRecordingPhase.FAILED, reason, now)

    @Test
    fun `a start the host app refused leaves its session id free`() {
        assertThat(sessions.startRecording(workout("watch"), "a")).isEqualTo(OpenLinkStart.Begin)
        assertThat(sessions.reported(failed("watch"))).isEqualTo("a")

        assertThat(sessions.owner(OpenLink.CAPABILITY_RECORDING, "watch")).isNull()
        assertThat(sessions.startRecording(workout("watch"), "a")).isEqualTo(OpenLinkStart.Begin)
    }

    @Test
    fun `a workout that ran and then failed is remembered, also across a restart`() {
        val storage = MapStorage()
        OpenLinkSessions(storage, clock = { now }).apply {
            startRecording(workout("watch"), "a")
            reported(state("watch", OpenLinkRecordingPhase.RECORDING, at = 1))
        }
        val after = OpenLinkSessions(storage, clock = { now })

        after.reported(failed("watch", OpenLinkRecordingState.REASON_NOT_CONNECTED))

        assertThat(after.startRecording(workout("watch"), "a")).isInstanceOf(OpenLinkStart.Ended::class.java)
    }

    @Test
    fun `a report older than the last change does not undo it`() {
        sessions.startRecording(workout("watch", at = 0), "a")
        assertThat(sessions.control("watch", "a", pause = true, atMillis = 100)).isTrue()
        // The host app's late start confirmation, from before the pause.
        sessions.reported(state("watch", OpenLinkRecordingPhase.RECORDING, at = 90))

        assertThat(sessions.control("watch", "a", pause = false, atMillis = 110)).isTrue()
    }

    @Test
    fun `a report that repeats the state is no change, so an older wish still counts`() {
        sessions.startRecording(workout("watch", at = 0), "a")
        sessions.reported(state("watch", OpenLinkRecordingPhase.RECORDING, at = 10))
        // Reported again (the device came back) after the phone's pause at 100, sent late.
        sessions.reported(state("watch", OpenLinkRecordingPhase.RECORDING, at = 120))

        assertThat(sessions.control("watch", "a", pause = true, atMillis = 100)).isTrue()
    }

    @Test
    fun `a repeated start of a stream session is handed on again`() {
        assertThat(sessions.startRecording(stream("plain"), "a")).isEqualTo(OpenLinkStart.Begin)
        sessions.reported(failed("plain", OpenLinkRecordingState.REASON_NOT_CONNECTED))

        assertThat(sessions.startRecording(stream("plain"), "a")).isEqualTo(OpenLinkStart.Begin)
        assertThat(sessions.startRecording(stream("plain"), "b")).isEqualTo(OpenLinkStart.Refused)
    }

    @Test
    fun `a stopping workout ended by a lost capability is not stopped again`() {
        sessions.startRecording(workout("stopping"), "a")
        sessions.stopRecording("stopping", "a")
        sessions.startRecording(workout("running"), "a")
        sessions.startRecording(stream("plain"), "a")

        assertThat(sessions.releaseAll("a", OpenLink.CAPABILITY_RECORDING)).containsExactly(
            OpenLinkSessions.Released("stopping", stopOwed = false),
            OpenLinkSessions.Released("running", stopOwed = true),
            OpenLinkSessions.Released("plain", stopOwed = true),
        )
    }

    @Test
    fun `a report that changes nothing kept is not written`() {
        val storage = MapStorage()
        val sessions = OpenLinkSessions(storage)
        sessions.startRecording(workout("watch"), "a")
        sessions.reported(state("watch", OpenLinkRecordingPhase.RECORDING, at = 1))
        val writes = storage.writes

        sessions.reported(state("watch", OpenLinkRecordingPhase.STARTING, at = 2))
        sessions.reported(state("watch", OpenLinkRecordingPhase.RECORDING, at = 3))

        assertThat(storage.writes).isEqualTo(writes)
    }

    @Test
    fun `a pause the device did not take can be asked again once the host app says so`() {
        sessions.startRecording(workout("watch", at = 0), "a")
        assertThat(sessions.control("watch", "a", pause = true, atMillis = 100)).isTrue()
        assertThat(sessions.control("watch", "a", pause = true, atMillis = 105)).isFalse() // taken as done

        // The watch was out of reach: the host app reports it still recording.
        sessions.reported(state("watch", OpenLinkRecordingPhase.RECORDING, at = 108))

        assertThat(sessions.control("watch", "a", pause = true, atMillis = 110)).isTrue()
    }

    @Test
    fun `another app's workout under an ended one's id keeps its own place in the storage`() {
        val storage = MapStorage()
        val sessions = OpenLinkSessions(storage, clock = { now })
        sessions.startRecording(workout("w"), "a")
        sessions.reported(stopped("w", at = 1))
        assertThat(sessions.startRecording(workout("w"), "b")).isEqualTo(OpenLinkStart.Begin)

        // a's ended workout is forgotten as newer ones end.
        repeat(OpenLinkSessions.MAX_FINISHED) { i ->
            sessions.startRecording(workout("x$i"), "a")
            sessions.reported(stopped("x$i", at = 2L + i))
        }

        val after = OpenLinkSessions(storage, clock = { now })
        assertThat(after.owner(OpenLink.CAPABILITY_RECORDING, "w")).isEqualTo("b")
        assertThat(after.startRecording(workout("w"), "a")).isEqualTo(OpenLinkStart.Refused)
    }

    @Test
    fun `an ended workout is its owner's, so another app's end under the same id does not replace it`() {
        val storage = MapStorage()
        OpenLinkSessions(storage).apply {
            startRecording(workout("w"), "a")
            reported(stopped("w", at = 1))
            startRecording(workout("w"), "b")
            reported(stopped("w", at = 2))
        }

        val after = OpenLinkSessions(storage)
        assertThat(after.startRecording(workout("w"), "a")).isEqualTo(OpenLinkStart.Ended(stopped("w", at = 1)))
        assertThat(after.startRecording(workout("w"), "b")).isEqualTo(OpenLinkStart.Ended(stopped("w", at = 2)))
    }
}
