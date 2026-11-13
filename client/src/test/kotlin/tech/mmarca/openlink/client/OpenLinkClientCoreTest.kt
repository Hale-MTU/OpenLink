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
import tech.mmarca.openlink.OpenLinkRecordingMode
import tech.mmarca.openlink.OpenLinkRecordingPhase
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openlink.OpenLinkSample
import tech.mmarca.openlink.OpenLinkSampleType
import tech.mmarca.openlink.OpenLinkSigners
import tech.mmarca.openlink.OpenLinkSetting
import tech.mmarca.openlink.OpenLinkSettingKeys
import tech.mmarca.openlink.OpenLinkSettings
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
        var dies = false
        override fun stopRecording(sessionId: String?) {
            if (dies) throw android.os.DeadObjectException()
            calls += "stop:$sessionId"
        }
        override fun pauseRecording(sessionId: String?, atMillis: Long) { calls += "pause:$sessionId@$atMillis" }
        override fun resumeRecording(sessionId: String?, atMillis: Long) { calls += "resume:$sessionId@$atMillis" }
        override fun subscribeLive(subscription: OpenLinkLiveSubscription?) { calls += "live:${subscription?.sessionId}" }
        override fun unsubscribeLive(sessionId: String?) { calls += "unlive:$sessionId" }
        var hostSettings = OpenLinkSettings.EMPTY
        var sentSettings: OpenLinkSettings? = null
        override fun setSettings(settings: OpenLinkSettings?) { sentSettings = settings; calls += "setSettings:${settings?.entries?.keys?.sorted()}" }
        override fun getSettings(): OpenLinkSettings = hostSettings
        override fun asBinder(): IBinder? = null
    }

    private object NoListener : IOpenLinkListener {
        override fun onStateChanged(state: OpenLinkState?) = Unit
        override fun onProfileChanged(profile: OpenLinkProfile?) = Unit
        override fun onDevicesChanged(devices: MutableList<OpenLinkDevice>?) = Unit
        override fun onRecordingStateChanged(state: tech.mmarca.openlink.OpenLinkRecordingState?) = Unit
        override fun onLiveSamples(batch: OpenLinkLiveBatch?) = Unit
        override fun onDataWritten(written: tech.mmarca.openlink.OpenLinkDataWritten?) = Unit
        override fun onSettingsChanged(settings: OpenLinkSettings?) = Unit
        override fun asBinder(): IBinder? = null
    }

    private val trusted = OpenLinkState(trust = OpenLinkTrust.TRUSTED, grantedCapabilities = OpenLink.ALL_CAPABILITIES)
    private val pending = OpenLinkState(trust = OpenLinkTrust.PENDING)
    private var now = 100L
    private val core = OpenLinkClientCore(OpenLink.ALL_CAPABILITIES, "2.17.0", clock = { now })

    @Test
    fun `the app's later change of a setting wins, also at the same time`() {
        core.onStarting()
        core.setSettings(OpenLinkSettings().with(OpenLinkSettingKeys.GOAL_STEPS, listOf(8_000.0), 50))
        core.setSettings(OpenLinkSettings().with(OpenLinkSettingKeys.GOAL_STEPS, listOf(9_000.0), 50))
        val host = FakeHost(trusted)

        core.onConnected(host, NoListener)

        assertThat(host.sentSettings?.number(OpenLinkSettingKeys.GOAL_STEPS)).isEqualTo(9_000.0)
    }

    @Test
    fun `settings go to a version 3 host once, only the keys OpenLink knows`() {
        core.onStarting()
        core.setSettings(
            OpenLinkSettings(
                mapOf(
                    OpenLinkSettingKeys.GOAL_STEPS.name to OpenLinkSetting(9_000.0, 50),
                    "note.free_text" to OpenLinkSetting(1.0, 50),
                ),
            ),
        )
        val host = FakeHost(trusted)

        core.onConnected(host, NoListener)
        core.onDisconnected()
        val again = FakeHost(trusted)
        core.onConnected(again, NoListener)

        assertThat(host.calls).contains("setSettings:[goal.steps]")
        assertThat(again.calls.none { it.startsWith("setSettings") }).isTrue()
    }

    @Test
    fun `a version 2 host is sent no settings and shows none, until it speaks version 3`() {
        val v2 = trusted.copy(protocolVersion = 2)
        val host = FakeHost(v2).apply { hostSettings = OpenLinkSettings().with(OpenLinkSettingKeys.GOAL_STEPS, listOf(8_000.0), 50) }
        core.onConnected(host, NoListener)

        core.setSettings(OpenLinkSettings().with(OpenLinkSettingKeys.GOAL_FLOORS, listOf(12.0), 60))
        core.events.onSettingsChanged(host.hostSettings)

        assertThat(host.calls.none { it.startsWith("setSettings") }).isTrue()
        assertThat(core.hostSettings.value.isEmpty).isTrue()
    }

    @Test
    fun `the host's settings come in checked, and not without the capability`() {
        val host = FakeHost(trusted).apply {
            hostSettings = OpenLinkSettings(
                mapOf(
                    OpenLinkSettingKeys.GOAL_STEPS.name to OpenLinkSetting(8_000.0, 50),
                    // Out of range: no step goal of a million.
                    OpenLinkSettingKeys.GOAL_FLOORS.name to OpenLinkSetting(1_000_000.0, 50),
                ),
            )
        }
        core.onConnected(host, NoListener)
        assertThat(core.hostSettings.value.number(OpenLinkSettingKeys.GOAL_STEPS)).isEqualTo(8_000.0)
        assertThat(core.hostSettings.value.number(OpenLinkSettingKeys.GOAL_FLOORS)).isNull()

        core.events.onStateChanged(trusted.copy(grantedCapabilities = OpenLink.ALL_CAPABILITIES - OpenLink.CAPABILITY_SETTINGS))
        core.events.onSettingsChanged(host.hostSettings)

        assertThat(core.hostSettings.value.isEmpty).isTrue()
    }

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

    @Test
    fun `a host is bound only under a known key or the key the user approved`() {
        val known = setOf("AA:BB")
        val official = OpenLinkSigners("aabb")
        val other = OpenLinkSigners("1234")

        assertThat(hostTrusted(official, known, approved = null)).isTrue()
        assertThat(hostTrusted(other, known, approved = null)).isFalse()
        assertThat(hostTrusted(other, known, approved = "1234")).isTrue()
        // The approval was for that key: the same package under another one asks again.
        assertThat(hostTrusted(OpenLinkSigners("9999"), known, approved = "1234")).isFalse()
        // A key rotated from a known or an approved one carries its trust.
        assertThat(hostTrusted(OpenLinkSigners("eeff", past = setOf("aabb")), known, approved = null)).isTrue()
        assertThat(hostTrusted(OpenLinkSigners("eeff", past = setOf("1234")), emptySet(), approved = "1234")).isTrue()
    }

    @Test
    fun `an untrusted host is reported with its key and holds nothing`() {
        val core = OpenLinkClientCore(setOf("devices"), "1.0")
        core.onStarting()

        core.onHostUntrusted("1234")

        assertThat(core.status.value).isEqualTo(OpenLinkStatus.HostUntrusted("1234"))
        assertThat(core.devices.value).isEmpty()
        core.onStarting()
        assertThat(core.status.value).isEqualTo(OpenLinkStatus.Connecting)
    }

    @Test
    fun `the state the host repeats when the listener registers is not caught up on twice`() {
        val host = FakeHost(trusted)
        core.onConnected(host, NoListener)
        host.hostDevices.clear()

        core.events.onStateChanged(trusted)
        assertThat(core.devices.value).hasSize(1)

        // A real change is applied.
        core.events.onStateChanged(trusted.copy(grantedCapabilities = setOf(OpenLink.CAPABILITY_PROFILE)))
        assertThat(core.devices.value).isEmpty()
    }

    @Test
    fun `a host that lost its approval is told, and what it still sends is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val host = FakeHost(trusted)
        core.onConnected(host, NoListener)
        core.subscribeLive(OpenLinkLiveSubscription("r1", types = setOf(OpenLinkSampleType.HEART_RATE)))
        val received = mutableListOf<OpenLinkLiveBatch>()
        val job = launch { core.liveBatches.collect { received += it } }

        core.onHostUntrusted("1234")
        core.events.onLiveSamples(OpenLinkLiveBatch("r1", "w1", listOf(OpenLinkSample(OpenLinkSampleType.HEART_RATE, 1L, 60.0))))
        core.events.onStateChanged(trusted)

        assertThat(host.calls).contains("clear")
        assertThat(received).isEmpty()
        assertThat(core.status.value).isEqualTo(OpenLinkStatus.HostUntrusted("1234"))
        job.cancel()
    }

    @Test
    fun `a pause is sent, replayed after a reconnect and cleared by a resume`() {
        val first = FakeHost(trusted)
        core.onConnected(first, NoListener)
        core.startRecording(OpenLinkRecordingRequest("r1", deviceId = "w1", mode = OpenLinkRecordingMode.RECORD))
        core.pauseRecording("r1", atMillis = 200)
        core.pauseRecording("nope", atMillis = 201)
        assertThat(first.calls).containsAtLeast("start:r1", "pause:r1@200")
        assertThat(first.calls.filter { it.endsWith("@201") }).isEmpty()

        core.onDisconnected()
        val again = FakeHost(trusted)
        core.onConnected(again, NoListener)
        assertThat(again.calls.filter { it == "start:r1" || it.startsWith("pause:") }).containsExactly("start:r1", "pause:r1@200").inOrder()

        core.resumeRecording("r1", atMillis = 300)
        core.onDisconnected()
        val third = FakeHost(trusted)
        core.onConnected(third, NoListener)
        assertThat(third.calls.filter { it.startsWith("pause:") }).isEmpty()
    }

    @Test
    fun `a record session is not sent to a host that speaks version 1`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<OpenLinkRecordingState>()
        val job = launch { core.recordingStates.collect { seen += it } }
        val host = FakeHost(trusted.copy(protocolVersion = 1))
        core.onConnected(host, NoListener)

        core.startRecording(OpenLinkRecordingRequest("r1", deviceId = "w1", mode = OpenLinkRecordingMode.RECORD))

        assertThat(host.calls).doesNotContain("start:r1")
        assertThat(core.activeRecordings()).isEmpty()
        assertThat(seen.single().phase).isEqualTo(OpenLinkRecordingPhase.FAILED)
        assertThat(seen.single().reason).isEqualTo(OpenLinkRecordingState.REASON_UNSUPPORTED)
        job.cancel()
    }

    @Test
    fun `a stream session still works against a host that speaks version 1`() {
        val host = FakeHost(trusted.copy(protocolVersion = 1))
        core.onConnected(host, NoListener)

        core.startRecording(OpenLinkRecordingRequest("r1"))

        assertThat(host.calls).contains("start:r1")
    }

    @Test
    fun `a pause reads as a recording to a version 1 peer`() {
        val paused = OpenLinkRecordingState("r1", "w1", OpenLinkRecordingPhase.PAUSED)

        assertThat(paused.forVersion(1).phase).isEqualTo(OpenLinkRecordingPhase.RECORDING)
        assertThat(paused.forVersion(2).phase).isEqualTo(OpenLinkRecordingPhase.PAUSED)
    }

    @Test
    fun `a record session the host ends is not replayed, a stream session is`() {
        core.onConnected(FakeHost(trusted), NoListener)
        core.startRecording(OpenLinkRecordingRequest("rec", deviceId = "w1", mode = OpenLinkRecordingMode.RECORD))
        core.pauseRecording("rec", atMillis = 200)
        core.startRecording(OpenLinkRecordingRequest("str"))

        core.events.onRecordingStateChanged(OpenLinkRecordingState("rec", "w1", OpenLinkRecordingPhase.STOPPED, OpenLinkRecordingState.REASON_STOPPED_ON_DEVICE))
        core.events.onRecordingStateChanged(OpenLinkRecordingState("str", "w1", OpenLinkRecordingPhase.FAILED, OpenLinkRecordingState.REASON_NOT_CONNECTED))

        assertThat(core.activeRecordings()).containsExactly("str")
        core.onDisconnected()
        val again = FakeHost(trusted)
        core.onConnected(again, NoListener)
        assertThat(again.calls).contains("start:str")
        assertThat(again.calls.filter { it.endsWith(":rec") || it.contains(":rec@") }).isEmpty()
    }

    private val record = OpenLinkRecordingRequest("rec", deviceId = "w1", mode = OpenLinkRecordingMode.RECORD)

    @Test
    fun `a stop of a recorded workout while disconnected is sent on the reconnect`() {
        core.onConnected(FakeHost(trusted), NoListener)
        core.startRecording(record)
        core.onDisconnected()

        core.stopRecording("rec")

        val again = FakeHost(trusted)
        core.onConnected(again, NoListener)
        assertThat(again.calls).contains("stop:rec")
        assertThat(again.calls).doesNotContain("start:rec")
        // Sent once.
        core.onDisconnected()
        val third = FakeHost(trusted)
        core.onConnected(third, NoListener)
        assertThat(third.calls).doesNotContain("stop:rec")
    }

    @Test
    fun `a stop of a recorded workout no host was sent ends it here, and sends nothing`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<OpenLinkRecordingState>()
        val job = launch { core.recordingStates.collect { seen += it } }
        val host = FakeHost(pending)
        core.onConnected(host, NoListener)
        core.startRecording(record)

        core.stopRecording("rec")
        core.events.onStateChanged(trusted)

        assertThat(seen).containsExactly(
            OpenLinkRecordingState("rec", "w1", OpenLinkRecordingPhase.STOPPED, OpenLinkRecordingState.REASON_STOPPED_BY_CLIENT, now),
        )
        assertThat(host.calls.filter { it.endsWith(":rec") }).isEmpty()
        job.cancel()
    }

    @Test
    fun `a stop of a stream session while disconnected is not owed, the unbind ended it`() {
        core.onConnected(FakeHost(trusted), NoListener)
        core.startRecording(OpenLinkRecordingRequest("str"))
        core.onDisconnected()
        core.stopRecording("str")

        val again = FakeHost(trusted)
        core.onConnected(again, NoListener)
        assertThat(again.calls.filter { it.endsWith(":str") }).isEmpty()
    }

    @Test
    fun `a resume while disconnected is sent on the reconnect, with its time`() {
        core.onConnected(FakeHost(trusted), NoListener)
        core.startRecording(record)
        core.pauseRecording("rec", atMillis = 200)
        core.onDisconnected()

        core.resumeRecording("rec", atMillis = 300)

        val again = FakeHost(trusted)
        core.onConnected(again, NoListener)
        assertThat(again.calls.filter { it == "start:rec" || it.startsWith("pause:") || it.startsWith("resume:") })
            .containsExactly("start:rec", "resume:rec@300").inOrder()
    }

    @Test
    fun `losing the recording capability drops the stops owed`() {
        core.onConnected(FakeHost(trusted), NoListener)
        core.startRecording(record)
        core.onDisconnected()
        core.stopRecording("rec")

        val noRecording = trusted.copy(grantedCapabilities = OpenLink.ALL_CAPABILITIES - OpenLink.CAPABILITY_RECORDING)
        core.onConnected(FakeHost(noRecording), NoListener)
        core.onDisconnected()
        val again = FakeHost(trusted)
        core.onConnected(again, NoListener)
        assertThat(again.calls).doesNotContain("stop:rec")
    }

    @Test
    fun `a fuller end of an ended workout still reaches the app, other sessions do not`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<OpenLinkRecordingState>()
        val job = launch { core.recordingStates.collect { seen += it } }
        core.onConnected(FakeHost(trusted), NoListener)
        core.startRecording(record)
        val first = OpenLinkRecordingState("rec", "w1", OpenLinkRecordingPhase.STOPPED, OpenLinkRecordingState.REASON_STOPPED_BY_CLIENT, 10)
        val fuller = first.copy(summary = tech.mmarca.openlink.OpenLinkWorkoutSummary(heartRateRecovery1MinBpm = 31), atMillis = 130)

        core.events.onRecordingStateChanged(first)
        core.events.onRecordingStateChanged(fuller)
        core.events.onRecordingStateChanged(OpenLinkRecordingState("rec", "w1", OpenLinkRecordingPhase.RECORDING))
        core.events.onRecordingStateChanged(OpenLinkRecordingState("other", "w1", OpenLinkRecordingPhase.STOPPED))

        assertThat(seen).containsExactly(first, fuller).inOrder()
        job.cancel()
    }

    @Test
    fun `data written reaches the app only under devices`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<tech.mmarca.openlink.OpenLinkDataWritten>()
        val job = launch { core.dataWritten.collect { seen += it } }
        val written = tech.mmarca.openlink.OpenLinkDataWritten(setOf("HeartRate"), 1, 2)
        core.onConnected(FakeHost(trusted.copy(grantedCapabilities = setOf(OpenLink.CAPABILITY_PROFILE))), NoListener)
        core.events.onDataWritten(written)
        assertThat(seen).isEmpty()

        core.events.onStateChanged(trusted)
        core.events.onDataWritten(written)
        assertThat(seen).containsExactly(written)
        job.cancel()
    }

    @Test
    fun `the end of a workout the app stopped still reaches it, with its summary`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<OpenLinkRecordingState>()
        val job = launch { core.recordingStates.collect { seen += it } }
        core.onConnected(FakeHost(trusted), NoListener)
        core.startRecording(record)

        core.stopRecording("rec")
        val end = OpenLinkRecordingState("rec", "w1", OpenLinkRecordingPhase.STOPPED, OpenLinkRecordingState.REASON_STOPPED_BY_CLIENT, 10,
            tech.mmarca.openlink.OpenLinkWorkoutSummary(trimp = 12.0))
        core.events.onRecordingStateChanged(end)

        assertThat(seen).containsExactly(end)
        job.cancel()
    }

    @Test
    fun `a newer state from the host replaces the app's wish, so an old pause is not sent again`() {
        core.onConnected(FakeHost(trusted), NoListener)
        core.startRecording(record)
        core.pauseRecording("rec", atMillis = 1)
        // Resumed on the watch at 2.
        core.events.onRecordingStateChanged(OpenLinkRecordingState("rec", "w1", OpenLinkRecordingPhase.RECORDING, atMillis = 2))
        core.onDisconnected()

        val again = FakeHost(trusted)
        core.onConnected(again, NoListener)
        assertThat(again.calls.filter { it.startsWith("pause:") || it.startsWith("resume:") }).isEmpty()
    }

    @Test
    fun `a wish newer than the host's last state is still sent again`() {
        core.onConnected(FakeHost(trusted), NoListener)
        core.startRecording(record)
        core.events.onRecordingStateChanged(OpenLinkRecordingState("rec", "w1", OpenLinkRecordingPhase.RECORDING, atMillis = 2))
        core.onDisconnected()
        core.pauseRecording("rec", atMillis = 3)

        val again = FakeHost(trusted)
        core.onConnected(again, NoListener)
        assertThat(again.calls).contains("pause:rec@3")
    }

    @Test
    fun `a stream session has no pause`() {
        val host = FakeHost(trusted)
        core.onConnected(host, NoListener)
        core.startRecording(OpenLinkRecordingRequest("str"))

        core.pauseRecording("str", atMillis = 1)
        core.resumeRecording("str", atMillis = 2)

        assertThat(host.calls.filter { it.startsWith("pause:") || it.startsWith("resume:") }).isEmpty()
    }

    @Test
    fun `a stop the dying host did not take is sent to the next one`() {
        val dying = FakeHost(trusted)
        core.onConnected(dying, NoListener)
        core.startRecording(record)
        dying.dies = true

        runCatching { core.stopRecording("rec") }
        core.onDisconnected()

        val again = FakeHost(trusted)
        core.onConnected(again, NoListener)
        assertThat(again.calls).contains("stop:rec")
    }

    @Test
    fun `a recorded workout ends with the capability, and a later grant does not begin it again`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<OpenLinkRecordingState>()
        val job = launch { core.recordingStates.collect { seen += it } }
        core.onConnected(FakeHost(trusted), NoListener)
        core.startRecording(record)
        core.onDisconnected()

        // Revoked while the app was away: it hears it in the next hello.
        val noRecording = trusted.copy(grantedCapabilities = OpenLink.ALL_CAPABILITIES - OpenLink.CAPABILITY_RECORDING)
        core.onConnected(FakeHost(noRecording), NoListener)

        assertThat(core.activeRecordings()).isEmpty()
        assertThat(seen.single().phase).isEqualTo(OpenLinkRecordingPhase.STOPPED)
        assertThat(seen.single().reason).isEqualTo(OpenLinkRecordingState.REASON_HOST_STOPPED)
        val regranted = FakeHost(trusted)
        core.onDisconnected()
        core.onConnected(regranted, NoListener)
        assertThat(regranted.calls).doesNotContain("start:rec")
        job.cancel()
    }

    @Test
    fun `a recorded workout asked for while pending is sent once it is granted`() {
        val host = FakeHost(pending)
        core.onConnected(host, NoListener)
        core.startRecording(record)

        core.events.onStateChanged(trusted)

        assertThat(host.calls).contains("start:rec")
    }

    @Test
    fun `a workout the host refused can be tried again with the same id`() {
        val host = FakeHost(trusted)
        core.onConnected(host, NoListener)
        core.startRecording(record)
        core.events.onRecordingStateChanged(OpenLinkRecordingState("rec", "w1", OpenLinkRecordingPhase.FAILED, OpenLinkRecordingState.REASON_BUSY))

        core.startRecording(record)

        assertThat(host.calls.filter { it == "start:rec" }).hasSize(2)
    }

    @Test
    fun `a stopped workout whose end the app missed ends with the capability, once`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<OpenLinkRecordingState>()
        val job = launch { core.recordingStates.collect { seen += it } }
        core.onConnected(FakeHost(trusted), NoListener)
        core.startRecording(record)
        core.stopRecording("rec")
        core.onDisconnected()

        val noRecording = trusted.copy(grantedCapabilities = OpenLink.ALL_CAPABILITIES - OpenLink.CAPABILITY_RECORDING)
        core.onConnected(FakeHost(noRecording), NoListener)
        core.onDisconnected()
        core.onConnected(FakeHost(noRecording), NoListener)

        assertThat(seen.map { it.sessionId to it.reason }).containsExactly("rec" to OpenLinkRecordingState.REASON_HOST_STOPPED)
        job.cancel()
    }

    @Test
    fun `a stopped workout whose end arrived is not ended again by a lost capability`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<OpenLinkRecordingState>()
        val job = launch { core.recordingStates.collect { seen += it } }
        core.onConnected(FakeHost(trusted), NoListener)
        core.startRecording(record)
        core.stopRecording("rec")
        val end = OpenLinkRecordingState("rec", "w1", OpenLinkRecordingPhase.STOPPED, OpenLinkRecordingState.REASON_STOPPED_BY_CLIENT, 10)
        core.events.onRecordingStateChanged(end)

        core.events.onStateChanged(trusted.copy(grantedCapabilities = OpenLink.ALL_CAPABILITIES - OpenLink.CAPABILITY_RECORDING))

        assertThat(seen).containsExactly(end)
        job.cancel()
    }
}
