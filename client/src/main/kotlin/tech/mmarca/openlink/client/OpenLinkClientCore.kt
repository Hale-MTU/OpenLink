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
import tech.mmarca.openlink.OpenLinkRecordingMode
import tech.mmarca.openlink.OpenLinkRecordingPhase
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openlink.OpenLinkSigners
import tech.mmarca.openlink.OpenLinkState

/**
 * Whether a client may bind a host signed by [signers]: under one of the keys the app ships
 * as its first-party host's ([known]), or under the key the user approved ([approved]). A key
 * rotated from either counts, since the platform proves the lineage.
 */
fun hostTrusted(signers: OpenLinkSigners, known: Set<String>, approved: String?): Boolean =
    signers.carriesAny(known) || (approved != null && signers.carries(approved))

/** Where the client stands with its host. */
sealed interface OpenLinkStatus {
    /** The client is not started, or was stopped. */
    data object Stopped : OpenLinkStatus

    /** The host app is not installed, or exports no OpenLink service. */
    data object HostMissing : OpenLinkStatus

    /**
     * The host is installed but Android refused the binding: an older host that guards its
     * service with a permission this app was not granted (installed before the host).
     */
    data object Unreachable : OpenLinkStatus

    /**
     * The host is installed, but under a key this app does not know and the user has not
     * approved: [signerSha256] is its current signing certificate. Nothing is bound and
     * nothing is sent. The app shows the user what it found and, on a yes, calls
     * `OpenLinkClient.trustHost(signerSha256)`. Empty if the key could not be read: there is
     * then nothing to approve, and the host stays unbound.
     */
    data class HostUntrusted(val signerSha256: String) : OpenLinkStatus

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
    private val clock: () -> Long = System::currentTimeMillis,
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

    private val _profileExchangedAtMillis = MutableStateFlow(0L)

    /** When a body profile last went to the host or came from it on this run; 0 if not yet. */
    val profileExchangedAtMillis: StateFlow<Long> = _profileExchangedAtMillis.asStateFlow()

    private var host: IOpenLinkHost? = null
    private var pendingProfile: OpenLinkProfile? = null
    private val recordings = linkedMapOf<String, OpenLinkRecordingRequest>()

    /** Sessions the app paused, with the time it asked, to pause them again after a reconnect. */
    private val paused = mutableMapOf<String, Long>()
    private val subscriptions = linkedMapOf<String, OpenLinkLiveSubscription>()

    /** What [listener] (the binder side's stub) forwards here. */
    val events: Events = Events()

    fun onStarting() {
        val now = _status.value
        if (now == OpenLinkStatus.Stopped || now == OpenLinkStatus.HostMissing || now == OpenLinkStatus.Unreachable || now is OpenLinkStatus.HostUntrusted) {
            _status.value = OpenLinkStatus.Connecting
        }
    }

    /** Binding failed: [installed] says whether the host app is there and refused, or absent. */
    fun onHostMissing(installed: Boolean = false) {
        host = null
        _status.value = if (installed) OpenLinkStatus.Unreachable else OpenLinkStatus.HostMissing
        _devices.value = emptyList()
    }

    /** The host package is there, but its key is neither known nor approved: stay away from it. */
    fun onHostUntrusted(signerSha256: String) {
        // A host bound until now (its approval was taken back) is told, as on a stop.
        runCatching { host?.setListener(null) }
        host = null
        _status.value = OpenLinkStatus.HostUntrusted(signerSha256)
        _devices.value = emptyList()
        _hostProfile.value = null
    }

    fun onStopped() {
        // Tell the host first, so it shows this app as gone now, not when the service unbinds.
        runCatching { host?.setListener(null) }
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
        val host = host
        if (host != null && granted(OpenLink.CAPABILITY_RECORDING)) begin(host, request)
    }

    fun stopRecording(sessionId: String) {
        paused.remove(sessionId)
        if (recordings.remove(sessionId) == null) return
        if (granted(OpenLink.CAPABILITY_RECORDING)) host?.stopRecording(sessionId)
    }

    /** Pauses a session this app started (version 2). A repeat asks again; the host ignores it. */
    fun pauseRecording(sessionId: String, atMillis: Long = clock()) {
        if (sessionId !in recordings) return
        paused[sessionId] = atMillis
        if (granted(OpenLink.CAPABILITY_RECORDING)) host?.pauseRecording(sessionId, atMillis)
    }

    fun resumeRecording(sessionId: String, atMillis: Long = clock()) {
        if (sessionId !in recordings) return
        paused.remove(sessionId)
        if (granted(OpenLink.CAPABILITY_RECORDING)) host?.resumeRecording(sessionId, atMillis)
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
        // The host repeats the state when the listener registers; nothing to catch up on then.
        if (state == before) return
        _status.value = OpenLinkStatus.Connected(state)
        val host = host ?: return
        if (state.has(OpenLink.CAPABILITY_DEVICES)) _devices.value = host.devices.orEmpty().real() else _devices.value = emptyList()
        if (state.has(OpenLink.CAPABILITY_PROFILE)) {
            _hostProfile.value = host.profile?.takeUnless { it.isEmpty }
            if (_hostProfile.value != null) _profileExchangedAtMillis.value = clock()
            flushProfile()
        } else {
            _hostProfile.value = null
        }
        // Replay what the app holds whenever the capability is newly there: after a reconnect
        // (before is null) or a grant. The host ignores a session it already has.
        if (state.has(OpenLink.CAPABILITY_RECORDING) && before?.has(OpenLink.CAPABILITY_RECORDING) != true) {
            // A copy: a request this host cannot take is dropped from the map as it is replayed.
            recordings.values.toList().forEach { begin(host, it) }
        }
        if (state.has(OpenLink.CAPABILITY_LIVE) && before?.has(OpenLink.CAPABILITY_LIVE) != true) {
            subscriptions.values.forEach(host::subscribeLive)
        }
    }

    /**
     * Sends a start, then the pause the app last asked for. A host that negotiated version 1
     * would stream instead of record, so a [OpenLinkRecordingMode.RECORD] request it cannot take
     * fails here, reported like any other failure, instead of being sent.
     */
    private fun begin(host: IOpenLinkHost, request: OpenLinkRecordingRequest) {
        val version = state()?.protocolVersion ?: 1
        if (request.mode == OpenLinkRecordingMode.RECORD && version < 2) {
            recordings.remove(request.sessionId)
            paused.remove(request.sessionId)
            _recordingStates.tryEmit(
                OpenLinkRecordingState(
                    request.sessionId,
                    request.deviceId.orEmpty(),
                    OpenLinkRecordingPhase.FAILED,
                    OpenLinkRecordingState.REASON_UNSUPPORTED,
                    clock(),
                ),
            )
            return
        }
        host.startRecording(request)
        paused[request.sessionId]?.let { host.pauseRecording(request.sessionId, it) }
    }

    private fun flushProfile() {
        val profile = pendingProfile ?: return
        if (!granted(OpenLink.CAPABILITY_PROFILE)) return
        host?.setProfile(profile) ?: return
        pendingProfile = null
        _profileExchangedAtMillis.value = clock()
    }

    private fun List<OpenLinkDevice>.real(): List<OpenLinkDevice> = filter { it.id.isNotEmpty() }

    /** The listener's callbacks, forwarded by the binder side's stub. */
    inner class Events {
        fun onStateChanged(state: OpenLinkState) {
            if (state() != null) applyState(state)
        }

        fun onProfileChanged(profile: OpenLinkProfile) {
            if (!granted(OpenLink.CAPABILITY_PROFILE)) return
            _hostProfile.value = profile.takeUnless { it.isEmpty }
            if (!profile.isEmpty) _profileExchangedAtMillis.value = clock()
        }

        fun onDevicesChanged(devices: List<OpenLinkDevice>) {
            if (granted(OpenLink.CAPABILITY_DEVICES)) _devices.value = devices.real()
        }

        fun onRecordingStateChanged(state: OpenLinkRecordingState) {
            if (state() == null) return
            val request = recordings[state.sessionId] ?: return
            // A workout the wearable recorded is over once the host says so: replaying its start
            // after a reconnect would begin a new one. A stream session stays until the app stops
            // it, since its device may come back.
            val over = state.phase == OpenLinkRecordingPhase.STOPPED || state.phase == OpenLinkRecordingPhase.FAILED
            if (over && request.mode == OpenLinkRecordingMode.RECORD) {
                recordings.remove(state.sessionId)
                paused.remove(state.sessionId)
            }
            _recordingStates.tryEmit(state)
        }

        fun onLiveSamples(batch: OpenLinkLiveBatch) {
            // Only from the host this client is connected to: one it left may still be calling.
            if (state() != null && batch.sessionId in subscriptions) _liveBatches.tryEmit(batch)
        }
    }
}
