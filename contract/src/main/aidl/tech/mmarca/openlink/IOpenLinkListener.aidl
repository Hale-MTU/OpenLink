package tech.mmarca.openlink;

import tech.mmarca.openlink.OpenLinkState;
import tech.mmarca.openlink.OpenLinkProfile;
import tech.mmarca.openlink.OpenLinkDevice;
import tech.mmarca.openlink.OpenLinkRecordingState;
import tech.mmarca.openlink.OpenLinkLiveBatch;

/**
 * What a host tells a bound client. Every method is oneway: the host never waits on a
 * client, and a slow client never holds the host's binder thread.
 */
oneway interface IOpenLinkListener {
    /** The caller's trust or granted capabilities changed. */
    void onStateChanged(in OpenLinkState state);
    /** The host's body profile changed (capability profile). */
    void onProfileChanged(in OpenLinkProfile profile);
    /** A device was added, removed or changed (capability devices). */
    void onDevicesChanged(in List<OpenLinkDevice> devices);
    /** A recording session the caller started changed state (capability recording). */
    void onRecordingStateChanged(in OpenLinkRecordingState state);
    /** Samples for a live subscription (capability live). */
    void onLiveSamples(in OpenLinkLiveBatch batch);
}
