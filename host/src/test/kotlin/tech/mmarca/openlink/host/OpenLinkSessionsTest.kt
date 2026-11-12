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

    private val sessions = OpenLinkSessions()

    private class MapStorage : OpenLinkTrustStorage {
        val map = mutableMapOf<String, String>()
        override fun read(): Map<String, String> = map.toMap()
        override fun write(key: String, value: String) { map[key] = value }
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

        assertThat(sessions.releaseAll("a", OpenLink.CAPABILITY_LIVE)).containsExactly("s1")
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

        assertThat(sessions.releaseAll("a", OpenLink.CAPABILITY_RECORDING, keepDetached = true)).containsExactly("plain")
        assertThat(sessions.owner(OpenLink.CAPABILITY_RECORDING, "watch")).isEqualTo("a")
        // Losing the capability ends it too.
        assertThat(sessions.releaseAll("a", OpenLink.CAPABILITY_RECORDING)).containsExactly("watch")
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
            map["session.ok"] = "R\ta\t0\t3\t0"
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
}
