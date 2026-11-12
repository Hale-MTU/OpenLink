package tech.mmarca.openlink.host

import android.app.PendingIntent
import android.os.Binder
import android.os.RemoteCallbackList
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import tech.mmarca.openlink.IOpenLinkHost
import tech.mmarca.openlink.IOpenLinkListener
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkDataWritten
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkHello
import tech.mmarca.openlink.OpenLinkLiveBatch
import tech.mmarca.openlink.OpenLinkLiveSubscription
import tech.mmarca.openlink.OpenLinkProfile
import tech.mmarca.openlink.OpenLinkRecordingPhase
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openlink.OpenLinkState
import tech.mmarca.openlink.OpenLinkTrust

/**
 * The host's binder and its listeners. One per process: the app builds it once (a
 * singleton) and its OpenLinkHostService returns [binder]. The app publishes events here;
 * each goes only to the clients trusted with its capability, and a session's events only
 * to the client that owns the session.
 */
class OpenLinkHost(
    val core: OpenLinkHostCore,
    private val resolver: OpenLinkCallerResolver,
    private val capabilities: OpenLinkHostCapabilities,
    /** The host's consent screen for a pending client; null if the host has none. */
    private val consentIntent: (packageName: String) -> PendingIntent?,
    /**
     * Where recordings a wearable records itself are kept across a restart of the host's
     * process (it may be the trust store's own storage). Null: in memory only.
     */
    sessionStorage: OpenLinkTrustStorage? = null,
) {
    private val sessions = OpenLinkSessions(sessionStorage)

    private val _connected = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The packages bound now with a listener: "in use". A client leaves it when it clears
     * its listener, dies, or the last binding to the service goes ([onAllUnbound]).
     */
    val connected: StateFlow<Set<String>> = _connected.asStateFlow()

    /** The recording sessions and live subscriptions clients hold now. */
    val activeSessions: StateFlow<List<OpenLinkActiveSession>> = sessions.active

    private val listeners = object : RemoteCallbackList<IOpenLinkListener>() {
        override fun onCallbackDied(callback: IOpenLinkListener, cookie: Any?) {
            // A client that crashed leaves nothing running on its behalf.
            (cookie as? OpenLinkCaller)?.let { left(it.packageName) }
        }
    }

    init {
        core.isConnected = { it in _connected.value }
        core.onTrustChanged = { packageName, _, after ->
            val kept = if (after?.trust == OpenLinkTrust.TRUSTED) after.capabilities else emptySet()
            endSessions(packageName, OpenLinkSessions.SESSION_CAPABILITIES - kept, OpenLinkRecordingState.REASON_HOST_STOPPED)
            broadcastTo(packageName) { listener, caller -> listener.onStateChanged(stateFor(caller)) }
        }
    }

    val binder: IOpenLinkHost.Stub = object : IOpenLinkHost.Stub() {
        override fun hello(hello: OpenLinkHello?): OpenLinkState {
            val caller = caller() ?: return OpenLinkState(trust = OpenLinkTrust.BLOCKED)
            val decision = core.hello(caller, hello ?: OpenLinkHello())
            return state(decision, caller.packageName)
        }

        override fun setListener(listener: IOpenLinkListener?) {
            val caller = caller() ?: return
            // A listener is for a client the host knows: one that said hello and is not blocked.
            // Clearing one is always allowed.
            if (listener != null && !core.mayListen(caller)) {
                Log.i(TAG, "refused a listener")
                return
            }
            // One listener per client app: a new one replaces the old.
            synchronized(listeners) {
                val count = listeners.registeredCallbackCount
                val stale = (0 until count).mapNotNull { i ->
                    val cookie = listeners.getRegisteredCallbackCookie(i) as? OpenLinkCaller
                    if (cookie?.packageName == caller.packageName) listeners.getRegisteredCallbackItem(i) else null
                }
                stale.forEach(listeners::unregister)
                if (listener != null) listeners.register(listener, caller)
            }
            if (listener != null) {
                _connected.update { it + caller.packageName }
                // What the user decided between this client's hello and now reached no listener:
                // tell it where it stands.
                runCatching { listener.onStateChanged(stateFor(caller)) }
            } else {
                // A client that stops cleanly clears its listener first: it is gone.
                left(caller.packageName)
            }
        }

        override fun setProfile(profile: OpenLinkProfile?) = gated(OpenLink.CAPABILITY_PROFILE) { caller ->
            capabilities.setProfile(caller, profile ?: return@gated)
            core.onProfileReceived(caller.packageName)
        }

        override fun getProfile(): OpenLinkProfile =
            gatedValue(OpenLink.CAPABILITY_PROFILE, OpenLinkProfile.EMPTY) { capabilities.profile() }

        override fun getDevices(): List<OpenLinkDevice> =
            gatedValue(OpenLink.CAPABILITY_DEVICES, emptyList()) { capabilities.devices() }

        override fun requestSync(deviceId: String?) =
            gated(OpenLink.CAPABILITY_DEVICES) { capabilities.requestSync(it, deviceId ?: return@gated) }

        override fun startRecording(request: OpenLinkRecordingRequest?) = gated(OpenLink.CAPABILITY_RECORDING) { caller ->
            request ?: return@gated
            when (val start = sessions.startRecording(request, caller.packageName)) {
                OpenLinkStart.Begin -> capabilities.startRecording(caller, request)
                OpenLinkStart.Refused -> Unit
                // A replay after a reconnect: where the workout stands, if the host app said.
                is OpenLinkStart.Already -> start.last?.let { tellRecording(caller.packageName, it) }
                // A replay of a recording that ended while the client was away: start nothing.
                is OpenLinkStart.Ended -> tellRecording(caller.packageName, start.last)
                is OpenLinkStart.Failed -> tellRecording(
                    caller.packageName,
                    OpenLinkRecordingState(request.sessionId, request.deviceId.orEmpty(), OpenLinkRecordingPhase.FAILED, start.reason, System.currentTimeMillis()),
                )
            }
        }

        override fun pauseRecording(sessionId: String?, atMillis: Long) = gated(OpenLink.CAPABILITY_RECORDING) { caller ->
            if (sessionId != null && sessions.control(sessionId, caller.packageName, pause = true, atMillis = atMillis)) {
                capabilities.pauseRecording(caller, sessionId, atMillis)
            }
        }

        override fun resumeRecording(sessionId: String?, atMillis: Long) = gated(OpenLink.CAPABILITY_RECORDING) { caller ->
            if (sessionId != null && sessions.control(sessionId, caller.packageName, pause = false, atMillis = atMillis)) {
                capabilities.resumeRecording(caller, sessionId, atMillis)
            }
        }

        override fun stopRecording(sessionId: String?) = gated(OpenLink.CAPABILITY_RECORDING) { caller ->
            if (sessionId != null && sessions.stopRecording(sessionId, caller.packageName)) {
                capabilities.stopRecording(caller, sessionId)
            }
        }

        override fun subscribeLive(subscription: OpenLinkLiveSubscription?) = gated(OpenLink.CAPABILITY_LIVE) { caller ->
            val sessionId = subscription?.sessionId?.takeIf { it.isNotBlank() } ?: return@gated
            if (sessions.claim(OpenLink.CAPABILITY_LIVE, sessionId, caller.packageName)) {
                capabilities.subscribeLive(caller, subscription)
            }
        }

        override fun unsubscribeLive(sessionId: String?) = gated(OpenLink.CAPABILITY_LIVE) { caller ->
            if (sessionId != null && sessions.release(OpenLink.CAPABILITY_LIVE, sessionId, caller.packageName)) {
                capabilities.unsubscribeLive(caller, sessionId)
            }
        }
    }

    // --- events the host app publishes

    fun publishProfile(profile: OpenLinkProfile) =
        broadcast(OpenLink.CAPABILITY_PROFILE) { it.onProfileChanged(profile) }

    fun publishDevices(devices: List<OpenLinkDevice>) =
        broadcast(OpenLink.CAPABILITY_DEVICES) { it.onDevicesChanged(devices) }

    fun publishRecordingState(state: OpenLinkRecordingState) {
        // The session stays the client's until it stops it or loses the capability: a device
        // that dropped out may come back within the same recording.
        // A recorded workout's state is taken over, and its end remembered, in the same step.
        val owner = sessions.reported(state) ?: return
        tellRecording(owner, state)
    }

    /**
     * The host wrote new data to Health Connect (version 2). To every client trusted with
     * devices that speaks version 2; an older one has no such method.
     */
    fun publishDataWritten(written: OpenLinkDataWritten) {
        if (written.recordTypes.isEmpty()) return
        forEachListener { listener, caller ->
            if (core.versionOf(caller.packageName) >= 2 && core.allows(caller, OpenLink.CAPABILITY_DEVICES)) {
                listener.onDataWritten(written)
            }
        }
    }

    fun publishLive(batch: OpenLinkLiveBatch) {
        if (batch.samples.isEmpty()) return
        val owner = sessions.owner(OpenLink.CAPABILITY_LIVE, batch.sessionId) ?: return
        broadcastTo(owner, OpenLink.CAPABILITY_LIVE) { listener, _ -> listener.onLiveSamples(batch) }
    }

    private fun tellRecording(packageName: String, state: OpenLinkRecordingState) {
        val sent = state.forVersion(core.versionOf(packageName))
        broadcastTo(packageName, OpenLink.CAPABILITY_RECORDING) { listener, _ -> listener.onRecordingStateChanged(sent) }
    }

    /** Whether any client is bound with a listener. */
    fun hasListeners(): Boolean = synchronized(listeners) { listeners.registeredCallbackCount > 0 }

    /**
     * Every binding to the service is gone (OpenLinkHostService.onUnbind). A client that
     * unbound without clearing its listener is still alive, so no death notice comes for it:
     * this is where it leaves.
     */
    fun onAllUnbound() {
        val packages = synchronized(listeners) {
            val count = listeners.registeredCallbackCount
            val items = (0 until count).map { i -> listeners.getRegisteredCallbackItem(i) to listeners.getRegisteredCallbackCookie(i) }
            items.forEach { (item, _) -> listeners.unregister(item) }
            items.mapNotNull { (_, cookie) -> (cookie as? OpenLinkCaller)?.packageName }.toSet()
        }
        (packages + _connected.value).forEach(::left)
    }

    // --- internals

    /** [packageName] went away: nothing keeps running for it, and its "last connected" moves. */
    private fun left(packageName: String) {
        endSessions(packageName, OpenLinkSessions.SESSION_CAPABILITIES, OpenLinkRecordingState.REASON_HOST_STOPPED, keepDetached = true)
        if (packageName in _connected.getAndUpdate { it - packageName }) core.onDisconnected(packageName)
    }

    private fun caller(): OpenLinkCaller? = resolver.resolve(Binder.getCallingUid())

    private inline fun gated(capability: String, block: (OpenLinkCaller) -> Unit) {
        val caller = caller() ?: return
        if (!core.allows(caller, capability)) {
            Log.i(TAG, "refused a $capability call")
            return
        }
        // The capability methods run under the host's identity, not the caller's.
        val token = Binder.clearCallingIdentity()
        try {
            block(caller)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private inline fun <T> gatedValue(capability: String, empty: T, block: (OpenLinkCaller) -> T): T {
        val caller = caller() ?: return empty
        if (!core.allows(caller, capability)) return empty
        val token = Binder.clearCallingIdentity()
        return try {
            block(caller)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun stateFor(caller: OpenLinkCaller): OpenLinkState = state(core.decision(caller), caller.packageName)

    private fun state(decision: OpenLinkDecision, packageName: String): OpenLinkState = OpenLinkState(
        protocolVersion = decision.protocolVersion,
        trust = decision.trust,
        grantedCapabilities = decision.capabilities,
        consentIntent = if (decision.trust == OpenLinkTrust.PENDING) consentIntent(packageName) else null,
    )

    private fun endSessions(packageName: String, capabilitiesLost: Set<String>, reason: String, keepDetached: Boolean = false) {
        for (capability in capabilitiesLost) {
            for (sessionId in sessions.releaseAll(packageName, capability, keepDetached)) {
                val caller = OpenLinkCaller(uid = -1, packageName = packageName, signerSha256 = "")
                runCatching {
                    when (capability) {
                        OpenLink.CAPABILITY_RECORDING -> capabilities.stopRecording(caller, sessionId)
                        OpenLink.CAPABILITY_LIVE -> capabilities.unsubscribeLive(caller, sessionId)
                    }
                }.onFailure { Log.w(TAG, "ending a session failed", it) }
                if (capability == OpenLink.CAPABILITY_RECORDING) {
                    // To the owner whatever it may still do: it has just lost the capability, and
                    // must hear that its recording ended.
                    broadcastTo(packageName) { listener, _ ->
                        listener.onRecordingStateChanged(
                            OpenLinkRecordingState(sessionId, "", OpenLinkRecordingPhase.STOPPED, reason, System.currentTimeMillis()),
                        )
                    }
                }
            }
        }
    }

    private fun broadcast(capability: String, send: (IOpenLinkListener) -> Unit) {
        forEachListener { listener, caller ->
            if (core.allows(caller, capability)) send(listener)
        }
    }

    private fun broadcastTo(packageName: String, capability: String? = null, send: (IOpenLinkListener, OpenLinkCaller) -> Unit) {
        forEachListener { listener, caller ->
            if (caller.packageName == packageName && (capability == null || core.allows(caller, capability))) send(listener, caller)
        }
    }

    private fun forEachListener(action: (IOpenLinkListener, OpenLinkCaller) -> Unit) = synchronized(listeners) {
        val count = listeners.beginBroadcast()
        try {
            for (i in 0 until count) {
                val caller = listeners.getBroadcastCookie(i) as? OpenLinkCaller ?: continue
                // Oneway calls: a slow or dead client costs nothing here, and RemoteCallbackList
                // drops a dead one on its own.
                runCatching { action(listeners.getBroadcastItem(i), caller) }
            }
        } finally {
            listeners.finishBroadcast()
        }
    }

    private companion object {
        const val TAG = "OpenLinkHost"
    }
}
