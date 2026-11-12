package tech.mmarca.openlink.host

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkRecordingPhase
import tech.mmarca.openlink.OpenLinkRecordingState

class OpenLinkSessionsTest {

    private val sessions = OpenLinkSessions()

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
        sessions.claim(OpenLink.CAPABILITY_RECORDING, "s1", "a")
        sessions.claim(OpenLink.CAPABILITY_LIVE, "s2", "b")

        assertThat(sessions.releaseAll("a", OpenLink.CAPABILITY_LIVE)).containsExactly("s1")
        assertThat(sessions.owner(OpenLink.CAPABILITY_RECORDING, "s1")).isEqualTo("a")
        assertThat(sessions.owner(OpenLink.CAPABILITY_LIVE, "s2")).isEqualTo("b")
    }

    @Test
    fun `the sessions held are published as they come and go`() {
        sessions.claim(OpenLink.CAPABILITY_RECORDING, "s1", "a")
        sessions.claim(OpenLink.CAPABILITY_LIVE, "s1", "a")
        assertThat(sessions.active.value).containsExactly(
            OpenLinkActiveSession("a", OpenLink.CAPABILITY_RECORDING, "s1"),
            OpenLinkActiveSession("a", OpenLink.CAPABILITY_LIVE, "s1"),
        )

        sessions.release(OpenLink.CAPABILITY_RECORDING, "s1", "a")
        sessions.releaseAll("a", OpenLink.CAPABILITY_LIVE)

        assertThat(sessions.active.value).isEmpty()
    }

    @Test
    fun `a detached session survives its owner leaving, a plain one does not`() {
        sessions.claim(OpenLink.CAPABILITY_RECORDING, "plain", "a")
        sessions.claim(OpenLink.CAPABILITY_RECORDING, "watch", "a", detached = true)

        assertThat(sessions.releaseAll("a", OpenLink.CAPABILITY_RECORDING, keepDetached = true)).containsExactly("plain")
        assertThat(sessions.owner(OpenLink.CAPABILITY_RECORDING, "watch")).isEqualTo("a")
        // Losing the capability ends it too.
        assertThat(sessions.releaseAll("a", OpenLink.CAPABILITY_RECORDING)).containsExactly("watch")
        assertThat(sessions.active.value).isEmpty()
    }

    @Test
    fun `a repeated claim keeps how the session was first claimed`() {
        sessions.claim(OpenLink.CAPABILITY_RECORDING, "watch", "a", detached = true)
        assertThat(sessions.claim(OpenLink.CAPABILITY_RECORDING, "watch", "a", detached = false)).isTrue()
        assertThat(sessions.claim(OpenLink.CAPABILITY_RECORDING, "watch", "b", detached = true)).isFalse()

        assertThat(sessions.releaseAll("a", OpenLink.CAPABILITY_RECORDING, keepDetached = true)).isEmpty()
    }

    private class MapStorage : OpenLinkTrustStorage {
        val map = mutableMapOf<String, String>()
        override fun read(): Map<String, String> = map.toMap()
        override fun write(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun stopped(id: String, at: Long = 0L) =
        OpenLinkRecordingState(id, "w1", OpenLinkRecordingPhase.STOPPED, OpenLinkRecordingState.REASON_STOPPED_ON_DEVICE, at)

    @Test
    fun `an ended recording answers its owner's replayed start, and no one else's`() {
        assertThat(sessions.startRecording("watch", "a", detached = true)).isEqualTo(OpenLinkStart.Begin)
        assertThat(sessions.startRecording("watch", "b", detached = true)).isEqualTo(OpenLinkStart.Refused)

        assertThat(sessions.reported(stopped("watch"))).isEqualTo("a")

        assertThat(sessions.active.value).isEmpty()
        assertThat(sessions.startRecording("watch", "a", detached = true)).isEqualTo(OpenLinkStart.Ended(stopped("watch")))
        // Another app's session under the same id is its own.
        assertThat(sessions.startRecording("watch", "b", detached = true)).isEqualTo(OpenLinkStart.Begin)
    }

    @Test
    fun `only the owner controls a recording`() {
        sessions.startRecording("watch", "a", detached = true)

        assertThat(sessions.controls("watch", "a")).isTrue()
        assertThat(sessions.controls("watch", "b")).isFalse()
        assertThat(sessions.controls("other", "a")).isFalse()
    }

    @Test
    fun `a report reaches the owner, and ends only a detached session`() {
        sessions.claim(OpenLink.CAPABILITY_RECORDING, "plain", "a")
        assertThat(sessions.reported(stopped("plain"))).isEqualTo("a")
        assertThat(sessions.owner(OpenLink.CAPABILITY_RECORDING, "plain")).isEqualTo("a")
        assertThat(sessions.reported(stopped("nobody"))).isNull()

        sessions.startRecording("watch", "a", detached = true)
        val paused = OpenLinkRecordingState("watch", "w1", OpenLinkRecordingPhase.PAUSED)
        assertThat(sessions.reported(paused)).isEqualTo("a")
        assertThat(sessions.controls("watch", "a")).isTrue()
    }

    @Test
    fun `old ended recordings are forgotten`() {
        repeat(OpenLinkSessions.MAX_FINISHED + 1) { i ->
            sessions.startRecording("w$i", "a", detached = true)
            sessions.reported(stopped("w$i", at = i.toLong()))
        }
        assertThat(sessions.startRecording("w0", "a", detached = true)).isEqualTo(OpenLinkStart.Begin)
        assertThat(sessions.startRecording("w${OpenLinkSessions.MAX_FINISHED}", "a", detached = true))
            .isInstanceOf(OpenLinkStart.Ended::class.java)
    }

    @Test
    fun `a host that restarts keeps running and ended recordings, not plain sessions`() {
        val storage = MapStorage()
        val before = OpenLinkSessions(storage)
        before.startRecording("running", "a", detached = true)
        before.startRecording("ended", "a", detached = true)
        before.reported(stopped("ended", at = 7))
        before.claim(OpenLink.CAPABILITY_RECORDING, "plain", "a")
        before.claim(OpenLink.CAPABILITY_LIVE, "running", "a")

        val after = OpenLinkSessions(storage)

        assertThat(after.active.value).containsExactly(OpenLinkActiveSession("a", OpenLink.CAPABILITY_RECORDING, "running"))
        assertThat(after.controls("running", "a")).isTrue()
        assertThat(after.startRecording("ended", "a", detached = true)).isEqualTo(OpenLinkStart.Ended(stopped("ended", at = 7)))
        // The owner stopping it removes it from the storage too.
        after.release(OpenLink.CAPABILITY_RECORDING, "running", "a")
        assertThat(OpenLinkSessions(storage).active.value).isEmpty()
    }

    @Test
    fun `the storage holds only its own keys, and drops what it cannot read`() {
        val storage = MapStorage().apply {
            map["client.x"] = "trust record"
            map["session.bad"] = "?"
            map["session.ok"] = "R\ta"
        }

        val sessions = OpenLinkSessions(storage)

        assertThat(sessions.controls("ok", "a")).isTrue()
        assertThat(storage.map.keys).containsExactly("client.x", "session.ok")
    }

    @Test
    fun `the storage keeps no more ended recordings than are remembered`() {
        val storage = MapStorage()
        val sessions = OpenLinkSessions(storage)
        repeat(OpenLinkSessions.MAX_FINISHED + 3) { i ->
            sessions.startRecording("w$i", "a", detached = true)
            sessions.reported(stopped("w$i", at = i.toLong()))
        }
        assertThat(storage.map).hasSize(OpenLinkSessions.MAX_FINISHED)
    }
}
