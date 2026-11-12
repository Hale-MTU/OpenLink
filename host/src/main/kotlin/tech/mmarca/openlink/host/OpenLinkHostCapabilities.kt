package tech.mmarca.openlink.host

import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkLiveSubscription
import tech.mmarca.openlink.OpenLinkProfile
import tech.mmarca.openlink.OpenLinkRecordingRequest

/**
 * What the host app implements. Every method is called on a binder thread, only for a
 * caller trusted with the capability the method belongs to. They must return at once:
 * read from memory, and hand anything slow (Bluetooth, Health Connect) to the app's own
 * scope. Events go back through [OpenLinkHost]'s publish methods.
 */
interface OpenLinkHostCapabilities {
    // profile
    fun profile(): OpenLinkProfile
    fun setProfile(caller: OpenLinkCaller, profile: OpenLinkProfile)

    // devices
    fun devices(): List<OpenLinkDevice>
    fun requestSync(caller: OpenLinkCaller, deviceId: String)

    // recording
    fun startRecording(caller: OpenLinkCaller, request: OpenLinkRecordingRequest)
    fun stopRecording(caller: OpenLinkCaller, sessionId: String)

    /**
     * Version 2. Only for the session's owner. [atMillis] is the client's clock: a request older
     * than the session's last change is the host app's to ignore, and so is a repeat.
     */
    fun pauseRecording(caller: OpenLinkCaller, sessionId: String, atMillis: Long)
    fun resumeRecording(caller: OpenLinkCaller, sessionId: String, atMillis: Long)

    // live
    fun subscribeLive(caller: OpenLinkCaller, subscription: OpenLinkLiveSubscription)
    fun unsubscribeLive(caller: OpenLinkCaller, sessionId: String)
}
