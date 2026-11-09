package tech.mmarca.openlink.host

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tech.mmarca.openlink.OpenLink

class OpenLinkSessionsTest {

    private var now = 1_000L
    private val sessions = OpenLinkSessions(clock = { now })

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
    fun `a client opens at most one new recording session per interval`() {
        assertThat(sessions.mayStartRecording("s1", "a")).isTrue()
        sessions.claim(OpenLink.CAPABILITY_RECORDING, "s1", "a")

        // A repeated start of the session it holds is always allowed.
        assertThat(sessions.mayStartRecording("s1", "a")).isTrue()
        assertThat(sessions.mayStartRecording("s2", "a")).isFalse()
        // Another client has its own interval.
        assertThat(sessions.mayStartRecording("s3", "b")).isTrue()

        now += OpenLinkSessions.START_INTERVAL_MILLIS
        assertThat(sessions.mayStartRecording("s2", "a")).isTrue()
    }

    @Test
    fun `a session ends after the longest time a host keeps one`() {
        sessions.claim(OpenLink.CAPABILITY_RECORDING, "s1", "a")
        now += 1_000L
        sessions.claim(OpenLink.CAPABILITY_LIVE, "s2", "a")

        now += OpenLinkSessions.MAX_SESSION_MILLIS
        assertThat(sessions.releaseExpired()).containsExactly(
            OpenLinkActiveSession("a", OpenLink.CAPABILITY_RECORDING, "s1"),
        )
        assertThat(sessions.owner(OpenLink.CAPABILITY_LIVE, "s2")).isEqualTo("a")

        now += 1_000L
        assertThat(sessions.releaseExpired()).hasSize(1)
        assertThat(sessions.active.value).isEmpty()
    }
}
