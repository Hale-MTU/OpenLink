package tech.mmarca.openlink.client

import android.os.IBinder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import tech.mmarca.openlink.IOpenLinkHost
import tech.mmarca.openlink.IOpenLinkListener
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkConnection
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkDeviceKind
import tech.mmarca.openlink.OpenLinkHello
import tech.mmarca.openlink.OpenLinkIntegration
import tech.mmarca.openlink.OpenLinkLiveBatch
import tech.mmarca.openlink.OpenLinkLiveSubscription
import tech.mmarca.openlink.OpenLinkProfile
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkSample
import tech.mmarca.openlink.OpenLinkSampleType
import tech.mmarca.openlink.OpenLinkState
import tech.mmarca.openlink.OpenLinkTrust

@OptIn(ExperimentalCoroutinesApi::class)
class OpenLinkClientCoreTest {

    private class FakeHost(var state: OpenLinkState) : IOpenLinkHost {
        val calls = mutableListOf<String>()
        var hostDevices = mutableListOf(
            OpenLinkDevice("w1", "Venu", OpenLinkDeviceKind.WATCH, OpenLinkIntegration.GARMIN, OpenLinkConnection.CONNECTED, batteryPercent = 80),
        )
        var hostProfile = OpenLinkProfile(birthYear = 1990, updatedAtMillis = 5)
        override fun hello(hello: OpenLinkHello?): OpenLinkState { calls += "hello:${hello?.requestedCapabilities?.sorted()}"; return state }
        override fun setListener(listener: IOpenLinkListener?) { calls += if (listener == null) "clear" else "listener" }
        override fun setProfile(profile: OpenLinkProfile?) { calls += "setProfile:${profile?.birthYear}" }
        override fun getProfile(): OpenLinkProfile = hostProfile
        override fun getDevices(): MutableList<OpenLinkDevice> = hostDevices
        override fun requestSync(deviceId: String?) { calls += "sync:$deviceId" }
        override fun startRecording(request: OpenLinkRecordingRequest?) { calls += "start:${request?.sessionId}" }
        override fun stopRecording(sessionId: String?) { calls += "stop:$sessionId" }
        override fun subscribeLive(subscription: OpenLinkLiveSubscription?) { calls += "live:${subscription?.sessionId}" }
        override fun unsubscribeLive(sessionId: String?) { calls += "unlive:$sessionId" }
        override fun asBinder(): IBinder? = null
    }

    private object NoListener : IOpenLinkListener {
        override fun onStateChanged(state: OpenLinkState?) = Unit
        override fun onProfileChanged(profile: OpenLinkProfile?) = Unit
        override fun onDevicesChanged(devices: MutableList<OpenLinkDevice>?) = Unit
        override fun onRecordingStateChanged(state: tech.mmarca.openlink.OpenLinkRecordingState?) = Unit
        override fun onLiveSamples(batch: OpenLinkLiveBatch?) = Unit
        override fun asBinder(): IBinder? = null
    }

    private val trusted = OpenLinkState(trust = OpenLinkTrust.TRUSTED, grantedCapabilities = OpenLink.ALL_CAPABILITIES)
    private val pending = OpenLinkState(trust = OpenLinkTrust.PENDING)
    private var now = 100L
    private val core = OpenLinkClientCore(OpenLink.ALL_CAPABILITIES, "2.17.0", clock = { now })

    @Test
    fun `a trusted connection catches up on devices and the host's profile`() {
        core.onConnected(FakeHost(trusted), NoListener)

        assertThat(core.status.value).isEqualTo(OpenLinkStatus.Connected(trusted))
        assertThat(core.devices.value.single().id).isEqualTo("w1")
        assertThat(core.hostProfile.value?.birthYear).isEqualTo(1990)
    }

    @Test
    fun `while pending nothing is sent, and a grant flushes what the app asked for`() {
        val host = FakeHost(pending)
        core.onConnected(host, NoListener)

        core.setProfile(OpenLinkProfile(birthYear = 1985))
        core.startRecording(OpenLinkRecordingRequest("r1"))
        core.subscribeLive(OpenLinkLiveSubscription("r1"))
        assertThat(host.calls).containsExactly("hello:${OpenLink.ALL_CAPABILITIES.sorted()}", "listener")
        assertThat(core.devices.value).isEmpty()

        core.events.onStateChanged(trusted)

        assertThat(host.calls).containsAtLeast("setProfile:1985", "start:r1", "live:r1")
    }

    @Test
    fun `a reconnect replays the sessions the app still holds, not the stopped ones`() {
        core.onConnected(FakeHost(trusted), NoListener)
        core.startRecording(OpenLinkRecordingRequest("r1"))
        core.startRecording(OpenLinkRecordingRequest("r2"))
        core.stopRecording("r1")
        core.onDisconnected()
        assertThat(core.status.value).isEqualTo(OpenLinkStatus.Connecting)

        val again = FakeHost(trusted)
        core.onConnected(again, NoListener)

        assertThat(again.calls).contains("start:r2")
        assertThat(again.calls).doesNotContain("start:r1")
    }

    @Test
    fun `a profile pushed while disconnected goes out on the next connection, once`() {
        core.onStarting()
        core.setProfile(OpenLinkProfile(birthYear = 1985))
        val host = FakeHost(trusted)

        core.onConnected(host, NoListener)
        core.onDisconnected()
        val again = FakeHost(trusted)
        core.onConnected(again, NoListener)

        assertThat(host.calls.count { it.startsWith("setProfile") }).isEqualTo(1)
        assertThat(again.calls.none { it.startsWith("setProfile") }).isTrue()
    }

    @Test
    fun `live samples reach the app only for a session it holds`() = runTest(UnconfinedTestDispatcher()) {
        core.onConnected(FakeHost(trusted), NoListener)
        val received = mutableListOf<OpenLinkLiveBatch>()
        backgroundScope.launch { core.liveBatches.collect(received::add) }
        core.subscribeLive(OpenLinkLiveSubscription("r1"))
        val batch = OpenLinkLiveBatch("r1", "w1", listOf(OpenLinkSample(OpenLinkSampleType.HEART_RATE, 1, 120.0)))

        core.events.onLiveSamples(batch.copy(sessionId = "other"))
        core.events.onLiveSamples(batch)
        core.unsubscribeLive("r1")
        core.events.onLiveSamples(batch)

        assertThat(received).containsExactly(batch)
    }

    @Test
    fun `losing trust clears the devices`() {
        core.onConnected(FakeHost(trusted), NoListener)
        core.events.onStateChanged(OpenLinkState(trust = OpenLinkTrust.BLOCKED))

        assertThat(core.devices.value).isEmpty()
        assertThat(core.hostProfile.value).isNull()
    }

    @Test
    fun `stopping clears the listener first, so the host sees the app go`() {
        val host = FakeHost(trusted)
        core.onConnected(host, NoListener)

        core.onStopped()

        assertThat(host.calls.last()).isEqualTo("clear")
        assertThat(core.status.value).isEqualTo(OpenLinkStatus.Stopped)
    }

    @Test
    fun `a host that is installed but refuses the binding is unreachable, not missing`() {
        core.onHostMissing(installed = true)
        assertThat(core.status.value).isEqualTo(OpenLinkStatus.Unreachable)

        core.onStarting()
        assertThat(core.status.value).isEqualTo(OpenLinkStatus.Connecting)

        core.onHostMissing(installed = false)
        assertThat(core.status.value).isEqualTo(OpenLinkStatus.HostMissing)
    }

    @Test
    fun `the profile exchange time moves when a profile goes out or comes in`() {
        val host = FakeHost(pending).apply { hostProfile = OpenLinkProfile.EMPTY }
        core.onConnected(host, NoListener)
        core.setProfile(OpenLinkProfile(birthYear = 1985))
        assertThat(core.profileExchangedAtMillis.value).isEqualTo(0L)

        now = 200L
        core.events.onStateChanged(trusted)
        assertThat(core.profileExchangedAtMillis.value).isEqualTo(200L)

        now = 300L
        core.events.onProfileChanged(OpenLinkProfile(birthYear = 1990, updatedAtMillis = 9))
        assertThat(core.profileExchangedAtMillis.value).isEqualTo(300L)
    }
}
