package tech.mmarca.openlink;

import tech.mmarca.openlink.IOpenLinkListener;
import tech.mmarca.openlink.OpenLinkHello;
import tech.mmarca.openlink.OpenLinkState;
import tech.mmarca.openlink.OpenLinkProfile;
import tech.mmarca.openlink.OpenLinkDevice;
import tech.mmarca.openlink.OpenLinkRecordingRequest;
import tech.mmarca.openlink.OpenLinkLiveSubscription;

/**
 * The host's side of the channel. The caller's identity comes from the binder, never from
 * an argument. A call under a capability the caller lacks is a no-op; the synchronous ones
 * then return an empty value. Nothing here throws across the binder for a permission
 * question. See docs/spec.md.
 */
interface IOpenLinkHost {
    /** First call on every binding. Returns the caller's trust state and granted capabilities. */
    OpenLinkState hello(in OpenLinkHello hello);
    /** The one listener of this binding; null clears it. Events need a trusted caller. */
    void setListener(IOpenLinkListener listener);

    // profile
    oneway void setProfile(in OpenLinkProfile profile);
    OpenLinkProfile getProfile();

    // devices
    List<OpenLinkDevice> getDevices();
    oneway void requestSync(String deviceId);

    // recording
    oneway void startRecording(in OpenLinkRecordingRequest request);
    oneway void stopRecording(String sessionId);

    // live
    oneway void subscribeLive(in OpenLinkLiveSubscription subscription);
    oneway void unsubscribeLive(String sessionId);

    // recording, version 2. Appended last so the transaction codes of version 1 stay.
    /** Pauses the session's workout; [atMillis] is the client's clock. A repeat changes nothing. */
    oneway void pauseRecording(String sessionId, long atMillis);
    oneway void resumeRecording(String sessionId, long atMillis);
}
