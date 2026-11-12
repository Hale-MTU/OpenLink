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
     * Version 2, for a recorded workout only. The library passes on only what changes the
     * workout: from its owner, not repeated, not older ([atMillis], the phone's clock) than the
     * last change, which a pause or resume the host app reported counts as. A start for a
     * recorded workout comes once, with a device id; a stop comes once, and the session stays
     * the owner's until the host app reports `STOPPED` or `FAILED`: the host app must report one
     * for every stop it is handed, also when the device is gone (`FAILED`, `not_connected`).
     * Without it the library takes the workout as stopped by the host after 30 minutes; an end
     * reported later still reaches the owner. One workout per device
     * (`busy`) is the host app's to check: only it knows its devices.
     */
    fun pauseRecording(caller: OpenLinkCaller, sessionId: String, atMillis: Long)
    fun resumeRecording(caller: OpenLinkCaller, sessionId: String, atMillis: Long)

    // live
    fun subscribeLive(caller: OpenLinkCaller, subscription: OpenLinkLiveSubscription)
    fun unsubscribeLive(caller: OpenLinkCaller, sessionId: String)
}
