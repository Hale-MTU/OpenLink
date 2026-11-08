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

    // live
    fun subscribeLive(caller: OpenLinkCaller, subscription: OpenLinkLiveSubscription)
    fun unsubscribeLive(caller: OpenLinkCaller, sessionId: String)
}
