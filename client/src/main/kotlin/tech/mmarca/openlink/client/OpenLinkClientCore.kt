package tech.mmarca.openlink.client

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.mmarca.openlink.IOpenLinkHost
import tech.mmarca.openlink.IOpenLinkListener
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkHello
import tech.mmarca.openlink.OpenLinkLiveBatch
import tech.mmarca.openlink.OpenLinkLiveSubscription
import tech.mmarca.openlink.OpenLinkProfile
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openlink.OpenLinkState

/** Where the client stands with its host. */
sealed interface OpenLinkStatus {
    /** The client is not started, or was stopped. */
    data object Stopped : OpenLinkStatus

    /** The host app is not installed, or exports no OpenLink service this client may bind. */
    data object HostMissing : OpenLinkStatus

    /** Binding, or the host went away and a rebind is pending. */
    data object Connecting : OpenLinkStatus

    /** Bound; [state] is what the host decided about this client. */
    data class Connected(val state: OpenLinkState) : OpenLinkStatus
}

/**
 * The client's state machine, with the binding left outside: [OpenLinkClient] binds and
 * hands over the host's interface; this keeps what the app asked for (the profile to push,
 * the recording sessions and live subscriptions it holds) and replays it on every
 * (re)connection and trust change. Every call into the host may throw; a throw means the
 * host is gone, and the binding side reconnects.
 *
 * Not thread-safe by itself: [OpenLinkClient] calls it from one serial dispatcher.
 */
class OpenLinkClientCore(
    private val requestedCapabilities: Set<String>,
    private val appVersion: String,
) {
    private val _status = MutableStateFlow<OpenLinkStatus>(OpenLinkStatus.Stopped)
    val status: StateFlow<OpenLinkStatus> = _status.asStateFlow()

    private val _devices = MutableStateFlow<List<OpenLinkDevice>>(emptyList())
    val devices: StateFlow<List<OpenLinkDevice>> = _devices.asStateFlow()

    private val _hostProfile = MutableStateFlow<OpenLinkProfile?>(null)
    val hostProfile: StateFlow<OpenLinkProfile?> = _hostProfile.asStateFlow()

    private val _recordingStates = MutableSharedFlow<OpenLinkRecordingState>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val recordingStates: SharedFlow<OpenLinkRecordingState> = _recordingStates.asSharedFlow()

    private val _liveBatches = MutableSharedFlow<OpenLinkLiveBatch>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val liveBatches: SharedFlow<OpenLinkLiveBatch> = _liveBatches.asSharedFlow()

    private var host: IOpenLinkHost? = null
    private var pendingProfile: OpenLinkProfile? = null
    private val recordings = linkedMapOf<String, OpenLinkRecordingRequest>()
    private val subscriptions = linkedMapOf<String, OpenLinkLiveSubscription>()

    /** What [listener] (the binder side's stub) forwards here. */
    val events: Events = Events()

    fun onStarting() {
        if (_status.value == OpenLinkStatus.Stopped || _status.value == OpenLinkStatus.HostMissing) {
            _status.value = OpenLinkStatus.Connecting
        }
    }

    fun onHostMissing() {
        host = null
        _status.value = OpenLinkStatus.HostMissing
        _devices.value = emptyList()
    }

    fun onStopped() {
        host = null
        _status.value = OpenLinkStatus.Stopped
        _devices.value = emptyList()
    }

    /** The binding broke; keep the wishes for the next connection. */
    fun onDisconnected() {
        host = null
        if (_status.value != OpenLinkStatus.Stopped) _status.value = OpenLinkStatus.Connecting
    }

    /** Bound: say hello, register [listener], and catch up. Throws if the host died meanwhile. */
    fun onConnected(host: IOpenLinkHost, listener: IOpenLinkListener) {
        this.host = host
        val state = host.hello(OpenLinkHello(OpenLink.PROTOCOL_VERSION, appVersion, requestedCapabilities))
        host.setListener(listener)
        applyState(state)
    }

    // --- what the app asks for

    /** Pushes [profile] now if allowed, else when the host next allows it. The latest wins. */
    fun setProfile(profile: OpenLinkProfile) {
        pendingProfile = profile
        flushProfile()
    }

    fun requestSync(deviceId: String) {
        if (granted(OpenLink.CAPABILITY_DEVICES)) host?.requestSync(deviceId)
    }

    fun startRecording(request: OpenLinkRecordingRequest) {
        recordings[request.sessionId] = request
        if (granted(OpenLink.CAPABILITY_RECORDING)) host?.startRecording(request)
    }

    fun stopRecording(sessionId: String) {
        if (recordings.remove(sessionId) == null) return
        if (granted(OpenLink.CAPABILITY_RECORDING)) host?.stopRecording(sessionId)
    }

    fun subscribeLive(subscription: OpenLinkLiveSubscription) {
        subscriptions[subscription.sessionId] = subscription
        if (granted(OpenLink.CAPABILITY_LIVE)) host?.subscribeLive(subscription)
    }

    fun unsubscribeLive(sessionId: String) {
        if (subscriptions.remove(sessionId) == null) return
        if (granted(OpenLink.CAPABILITY_LIVE)) host?.unsubscribeLive(sessionId)
    }

    /** The sessions and subscriptions the app holds, for its own bookkeeping and tests. */
    fun activeRecordings(): Set<String> = recordings.keys.toSet()

    fun activeSubscriptions(): Set<String> = subscriptions.keys.toSet()

    // --- internals

    private fun state(): OpenLinkState? = (_status.value as? OpenLinkStatus.Connected)?.state

    private fun granted(capability: String): Boolean = state()?.has(capability) == true

    private fun applyState(state: OpenLinkState) {
        val before = state()
        _status.value = OpenLinkStatus.Connected(state)
        val host = host ?: return
        if (state.has(OpenLink.CAPABILITY_DEVICES)) _devices.value = host.devices.orEmpty().real() else _devices.value = emptyList()
        if (state.has(OpenLink.CAPABILITY_PROFILE)) {
            _hostProfile.value = host.profile?.takeUnless { it.isEmpty }
            flushProfile()
        } else {
            _hostProfile.value = null
        }
        // Replay what the app holds whenever the capability is newly there: after a reconnect
        // (before is null) or a grant. The host ignores a session it already has.
        if (state.has(OpenLink.CAPABILITY_RECORDING) && before?.has(OpenLink.CAPABILITY_RECORDING) != true) {
            recordings.values.forEach(host::startRecording)
        }
        if (state.has(OpenLink.CAPABILITY_LIVE) && before?.has(OpenLink.CAPABILITY_LIVE) != true) {
            subscriptions.values.forEach(host::subscribeLive)
        }
    }

    private fun flushProfile() {
        val profile = pendingProfile ?: return
        if (!granted(OpenLink.CAPABILITY_PROFILE)) return
        host?.setProfile(profile) ?: return
        pendingProfile = null
    }

    private fun List<OpenLinkDevice>.real(): List<OpenLinkDevice> = filter { it.id.isNotEmpty() }

    /** The listener's callbacks, forwarded by the binder side's stub. */
    inner class Events {
        fun onStateChanged(state: OpenLinkState) = applyState(state)

        fun onProfileChanged(profile: OpenLinkProfile) {
            if (granted(OpenLink.CAPABILITY_PROFILE)) _hostProfile.value = profile.takeUnless { it.isEmpty }
        }

        fun onDevicesChanged(devices: List<OpenLinkDevice>) {
            if (granted(OpenLink.CAPABILITY_DEVICES)) _devices.value = devices.real()
        }

        fun onRecordingStateChanged(state: OpenLinkRecordingState) {
            if (state.sessionId in recordings) _recordingStates.tryEmit(state)
        }

        fun onLiveSamples(batch: OpenLinkLiveBatch) {
            if (batch.sessionId in subscriptions) _liveBatches.tryEmit(batch)
        }
    }
}
