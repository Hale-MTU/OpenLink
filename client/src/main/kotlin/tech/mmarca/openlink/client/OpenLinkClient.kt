package tech.mmarca.openlink.client

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tech.mmarca.openlink.IOpenLinkHost
import tech.mmarca.openlink.IOpenLinkListener
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkDataWritten
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkLiveBatch
import tech.mmarca.openlink.OpenLinkLiveSubscription
import tech.mmarca.openlink.OpenLinkProfile
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openlink.OpenLinkSettings
import tech.mmarca.openlink.OpenLinkSigners
import tech.mmarca.openlink.OpenLinkState

/**
 * Binds to an OpenLink host and keeps the binding, once it knows who the host is: the package
 * must be signed by one of [knownHostSigners], or by a key the user approved through
 * [trustHost]. A package name alone proves nothing, and the host receives the body profile
 * and feeds the app readings. Then: a host that dies or is updated is rebound
 * with a backoff, and everything the app asked for is replayed. All host calls run on one
 * serial dispatcher off the main thread; the flows are safe to collect anywhere.
 *
 * Start it while the app needs the channel (the whole process, or while a screen or a
 * recording runs), stop it when it does not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenLinkClient(
    context: Context,
    /** The host app's package, for example `tech.mmarca.openwatch`. */
    private val hostPackage: String,
    requestedCapabilities: Set<String>,
    appVersion: String,
    /**
     * Hex SHA-256 of the certificates this app's first-party host is signed with; such a host
     * is bound without asking. Empty: every host needs the user's approval ([trustHost]).
     */
    private val knownHostSigners: Set<String> = emptySet(),
) {
    private val appContext = context.applicationContext
    private val core = OpenLinkClientCore(requestedCapabilities, appVersion)
    private val approvals = appContext.getSharedPreferences(APPROVALS_PREFS, Context.MODE_PRIVATE)
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)

    private var started = false
    private var bound = false
    private var connection: ServiceConnection? = null
    private var retryJob: Job? = null
    private var retryDelayMillis = INITIAL_RETRY_MILLIS

    val status: StateFlow<OpenLinkStatus> = core.status
    val devices: StateFlow<List<OpenLinkDevice>> = core.devices
    val hostProfile: StateFlow<OpenLinkProfile?> = core.hostProfile
    val recordingStates: SharedFlow<OpenLinkRecordingState> = core.recordingStates
    val liveBatches: SharedFlow<OpenLinkLiveBatch> = core.liveBatches
    val dataWritten: SharedFlow<OpenLinkDataWritten> = core.dataWritten
    val profileExchangedAtMillis: StateFlow<Long> = core.profileExchangedAtMillis
    val hostSettings: StateFlow<OpenLinkSettings> = core.hostSettings

    private val listener = object : IOpenLinkListener.Stub() {
        override fun onStateChanged(state: OpenLinkState?) = post { core.events.onStateChanged(state ?: return@post) }
        override fun onProfileChanged(profile: OpenLinkProfile?) = post { core.events.onProfileChanged(profile ?: return@post) }
        override fun onDevicesChanged(devices: MutableList<OpenLinkDevice>?) = post { core.events.onDevicesChanged(devices.orEmpty()) }
        override fun onRecordingStateChanged(state: OpenLinkRecordingState?) = post { core.events.onRecordingStateChanged(state ?: return@post) }
        override fun onLiveSamples(batch: OpenLinkLiveBatch?) = post { core.events.onLiveSamples(batch ?: return@post) }
        override fun onDataWritten(written: OpenLinkDataWritten?) = post { core.events.onDataWritten(written ?: return@post) }
        override fun onSettingsChanged(settings: OpenLinkSettings?) = post { core.events.onSettingsChanged(settings ?: return@post) }
    }

    /** Whether the host app is installed and exports a service this client may bind. */
    fun isHostInstalled(): Boolean =
        appContext.packageManager.queryIntentServices(hostIntent(), 0).isNotEmpty()

    fun start() = post {
        if (started) return@post
        started = true
        core.onStarting()
        bind()
    }

    fun stop() = post {
        started = false
        retryJob?.cancel()
        core.onStopped()
        unbind()
    }

    /** Re-checks a missing host, for when the user may have just installed it. */
    fun refresh() = post { refreshNow() }

    private fun refreshNow() {
        if (started && !bound) {
            retryJob?.cancel()
            retryDelayMillis = INITIAL_RETRY_MILLIS
            bind()
        }
    }

    /**
     * The host's current key if it is installed but neither known nor approved; null if it
     * may be bound, or is not there at all (the bind then reports it missing). Empty for a
     * host that is there but whose signers could not be read: that proves nothing, so it is
     * not bound either.
     */
    private fun hostSignerIfUntrusted(): String? {
        val signers = OpenLinkSigners.of(appContext.packageManager, hostPackage)
            ?: return if (isHostInstalled()) "" else null
        return signers.current.takeUnless { hostTrusted(signers, knownHostSigners, approvals.getString(hostPackage, null)) }
    }

    /** Stays away from an untrusted host and looks again later. True if it was one. */
    private fun refuseUntrustedHost(): Boolean {
        val signer = hostSignerIfUntrusted() ?: return false
        // The core first: it clears the listener on a host that was bound until now.
        core.onHostUntrusted(signer)
        unbind()
        scheduleRetry(MISSING_RETRY_MILLIS)
        return true
    }

    /**
     * The user approved the host signed by [signerSha256], as [OpenLinkStatus.HostUntrusted]
     * reported it. The approval is for that key: the same package under another key asks again.
     */
    fun trustHost(signerSha256: String) = post {
        if (signerSha256.isBlank()) return@post
        approvals.edit().putString(hostPackage, OpenLinkSigners.normalize(signerSha256)).apply()
        // Approved: the question is answered, whatever the bind takes from here.
        if (started) core.onStarting()
        refreshNow()
    }

    /** Takes the user's approval back; a host that needed it is unbound and asks again. */
    fun forgetHost() = post {
        approvals.edit().remove(hostPackage).apply()
        if (started) refuseUntrustedHost()
    }

    /**
     * Opens the host's consent screen while it waits for the user's answer about this app.
     * Call it from a tap in a visible activity: from Android 14 a PendingIntent the host made
     * starts an activity only if the sender, this app, lends its foreground to it. False if
     * there is no consent screen to open now.
     */
    fun openConsent(context: Context): Boolean {
        val pending = (status.value as? OpenLinkStatus.Connected)?.state?.consentIntent ?: return false
        return runCatching {
            pending.send(context, 0, null, null, null, null, foregroundLaunchOptions())
            true
        }.getOrElse { error ->
            Log.w(TAG, "consent screen did not open", error)
            false
        }
    }

    fun setProfile(profile: OpenLinkProfile) = call { core.setProfile(profile) }
    fun setSettings(settings: OpenLinkSettings) = call { core.setSettings(settings) }
    fun requestSync(deviceId: String) = call { core.requestSync(deviceId) }
    fun startRecording(request: OpenLinkRecordingRequest) = call { core.startRecording(request) }
    fun stopRecording(sessionId: String) = call { core.stopRecording(sessionId) }
    fun pauseRecording(sessionId: String) = call { core.pauseRecording(sessionId) }
    fun resumeRecording(sessionId: String) = call { core.resumeRecording(sessionId) }
    fun subscribeLive(subscription: OpenLinkLiveSubscription) = call { core.subscribeLive(subscription) }
    fun unsubscribeLive(sessionId: String) = call { core.unsubscribeLive(sessionId) }

    // --- binding

    private fun hostIntent(): Intent = Intent(OpenLink.ACTION_HOST).setPackage(hostPackage)

    private fun bind() {
        if (bound) return
        if (refuseUntrustedHost()) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = post {
                if (!started || service == null) return@post
                // Checked again here: the package may have been replaced since the bind was asked for.
                if (refuseUntrustedHost()) return@post
                retryDelayMillis = INITIAL_RETRY_MILLIS
                runCatching { core.onConnected(IOpenLinkHost.Stub.asInterface(service), listener) }
                    .onFailure { Log.w(TAG, "hello failed", it); lost() }
            }

            override fun onServiceDisconnected(name: ComponentName?) = post { core.onDisconnected() }

            override fun onBindingDied(name: ComponentName?) = post { lost() }

            override fun onNullBinding(name: ComponentName?) = post {
                unbind()
                core.onHostMissing()
                scheduleRetry(MISSING_RETRY_MILLIS)
            }
        }
        val ok = runCatching { appContext.bindService(hostIntent(), connection, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(TAG, "bind refused", it) }
            .getOrDefault(false)
        if (ok) {
            this.connection = connection
            bound = true
        } else {
            // Not installed, or the permission is missing. Look again later; installing the
            // host does not tell us.
            runCatching { appContext.unbindService(connection) }
            core.onHostMissing(installed = isHostInstalled())
            scheduleRetry(MISSING_RETRY_MILLIS)
        }
    }

    private fun unbind() {
        val connection = connection ?: return
        runCatching { appContext.unbindService(connection) }
        this.connection = null
        bound = false
    }

    private fun lost() {
        unbind()
        core.onDisconnected()
        scheduleRetry(retryDelayMillis)
        retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
    }

    private fun scheduleRetry(delayMillis: Long) {
        if (!started) return
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMillis)
            if (started && !bound) bind()
        }
    }

    private fun post(block: () -> Unit) {
        scope.launch { block() }
    }

    /** A call into the host; a throw means the host is gone, so rebind. */
    private fun call(block: () -> Unit) = post {
        runCatching(block).onFailure {
            Log.w(TAG, "host call failed", it)
            if (bound) lost()
        }
    }

    private companion object {
        @Suppress("DEPRECATION")
        fun foregroundLaunchOptions(): android.os.Bundle? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else {
                null
            }

        const val TAG = "OpenLinkClient"
        const val APPROVALS_PREFS = "openlink_host_approvals"
        const val INITIAL_RETRY_MILLIS = 1_000L
        const val MAX_RETRY_MILLIS = 60_000L
        const val MISSING_RETRY_MILLIS = 5 * 60_000L
    }
}
