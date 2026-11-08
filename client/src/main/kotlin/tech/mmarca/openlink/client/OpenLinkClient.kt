package tech.mmarca.openlink.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkLiveBatch
import tech.mmarca.openlink.OpenLinkLiveSubscription
import tech.mmarca.openlink.OpenLinkProfile
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openlink.OpenLinkState

/**
 * Binds to an OpenLink host and keeps the binding: a host that dies or is updated is rebound
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
) {
    private val appContext = context.applicationContext
    private val core = OpenLinkClientCore(requestedCapabilities, appVersion)
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

    private val listener = object : IOpenLinkListener.Stub() {
        override fun onStateChanged(state: OpenLinkState?) = post { core.events.onStateChanged(state ?: return@post) }
        override fun onProfileChanged(profile: OpenLinkProfile?) = post { core.events.onProfileChanged(profile ?: return@post) }
        override fun onDevicesChanged(devices: MutableList<OpenLinkDevice>?) = post { core.events.onDevicesChanged(devices.orEmpty()) }
        override fun onRecordingStateChanged(state: OpenLinkRecordingState?) = post { core.events.onRecordingStateChanged(state ?: return@post) }
        override fun onLiveSamples(batch: OpenLinkLiveBatch?) = post { core.events.onLiveSamples(batch ?: return@post) }
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
        unbind()
        core.onStopped()
    }

    /** Re-checks a missing host, for when the user may have just installed it. */
    fun refresh() = post {
        if (started && !bound) {
            retryJob?.cancel()
            retryDelayMillis = INITIAL_RETRY_MILLIS
            bind()
        }
    }

    fun setProfile(profile: OpenLinkProfile) = call { core.setProfile(profile) }
    fun requestSync(deviceId: String) = call { core.requestSync(deviceId) }
    fun startRecording(request: OpenLinkRecordingRequest) = call { core.startRecording(request) }
    fun stopRecording(sessionId: String) = call { core.stopRecording(sessionId) }
    fun subscribeLive(subscription: OpenLinkLiveSubscription) = call { core.subscribeLive(subscription) }
    fun unsubscribeLive(sessionId: String) = call { core.unsubscribeLive(sessionId) }

    // --- binding

    private fun hostIntent(): Intent = Intent(OpenLink.ACTION_HOST).setPackage(hostPackage)

    private fun bind() {
        if (bound) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = post {
                if (!started || service == null) return@post
                retryDelayMillis = INITIAL_RETRY_MILLIS
                runCatching { core.onConnected(IOpenLinkHost.Stub.asInterface(service), listener) }
                    .onFailure { Log.w(TAG, "hello failed", it); lost() }
            }

            override fun onServiceDisconnected(name: ComponentName?) = post { core.onDisconnected() }

            override fun onBindingDied(name: ComponentName?) = post { lost() }

            override fun onNullBinding(name: ComponentName?) = post {
                unbind()
                core.onHostMissing()
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
            core.onHostMissing()
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
        const val TAG = "OpenLinkClient"
        const val INITIAL_RETRY_MILLIS = 1_000L
        const val MAX_RETRY_MILLIS = 60_000L
        const val MISSING_RETRY_MILLIS = 5 * 60_000L
    }
}
