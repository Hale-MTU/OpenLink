package tech.mmarca.openlink.host

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tech.mmarca.openlink.OpenLink

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
}
