package tech.mmarca.openlink

/** The constants both sides of the channel agree on. See docs/spec.md. */
object OpenLink {
    /** The protocol this library speaks. Both sides send theirs in hello and use the lower. */
    const val PROTOCOL_VERSION: Int = 2

    /** The intent action a host's service answers to; the client also names the host's package. */
    const val ACTION_HOST: String = "tech.mmarca.openlink.action.HOST"

    /** The permission a host declares is `<its package>` + this suffix. */
    const val PERMISSION_SUFFIX: String = ".permission.OPENLINK"

    /** The capabilities a client can ask for. Unknown names are ignored by an older host. */
    const val CAPABILITY_PROFILE: String = "profile"
    const val CAPABILITY_DEVICES: String = "devices"
    const val CAPABILITY_RECORDING: String = "recording"
    const val CAPABILITY_LIVE: String = "live"

    val ALL_CAPABILITIES: Set<String> =
        setOf(CAPABILITY_PROFILE, CAPABILITY_DEVICES, CAPABILITY_RECORDING, CAPABILITY_LIVE)

    fun permissionFor(hostPackage: String): String = hostPackage + PERMISSION_SUFFIX
}

/** The trust a host has in a caller. */
enum class OpenLinkTrust { PENDING, TRUSTED, BLOCKED }

/** Biological sex as the body profile carries it. Null in the profile means not declared. */
enum class OpenLinkSex { FEMALE, MALE }

enum class OpenLinkDeviceKind { WATCH, BIKE_COMPUTER, SCALE }

enum class OpenLinkIntegration { GARMIN, WEAROS, XIAOMI }

enum class OpenLinkConnection { CONNECTED, DISCONNECTED, UNKNOWN }

/**
 * The states a recording session on a wearable goes through. [PAUSED] exists from version 2;
 * a host never reports it to a client that negotiated version 1, which would read it as a failure.
 */
enum class OpenLinkRecordingPhase {
    STARTING, RECORDING, PAUSED, STOPPED, FAILED;

    /** The session is over: nothing follows but, for a recorded workout, a fuller end. */
    val isOver: Boolean get() = this == STOPPED || this == FAILED
}

/**
 * What a recording session does on the host (version 2). [STREAM] keeps the wearable's live
 * readings flowing while the client records; [RECORD] has the wearable record the workout and
 * the host save it to Health Connect, with the client only controlling it.
 */
enum class OpenLinkRecordingMode { STREAM, RECORD }

/** What a device can do when it records a workout of its own (version 2). */
enum class OpenLinkRecordingFeature { GPS, HEART_RATE, PAUSE, AUTO_PAUSE }

/** What a device measures and its host writes to Health Connect (version 2). */
enum class OpenLinkMeasurement { HEART_RATE, HRV, SPO2, SKIN_TEMPERATURE }

/**
 * How a device's last sync ended (version 2). [lastSyncedAtMillis][OpenLinkDevice.lastSyncedAtMillis]
 * stays the last one that succeeded.
 */
enum class OpenLinkSyncResult { SUCCEEDED, INTERRUPTED, PERMISSION_MISSING, DISCONNECTED, FAILED }

/**
 * The sample types a live subscription can ask for. A host answers the ones its devices
 * produce and leaves the others out; version 1 hosts produce heart rate and steps.
 */
enum class OpenLinkSampleType { HEART_RATE, STEPS, CADENCE, POWER }
