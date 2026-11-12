package tech.mmarca.openlink

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/** One device the host has paired, as much of its state as a tile needs. */
data class OpenLinkDevice(
    val id: String,
    val displayName: String,
    val kind: OpenLinkDeviceKind,
    val integration: OpenLinkIntegration,
    val connection: OpenLinkConnection = OpenLinkConnection.UNKNOWN,
    val batteryPercent: Int? = null,
    val batteryUpdatedAtMillis: Long? = null,
    val lastSyncedAtMillis: Long? = null,
    val syncing: Boolean = false,
    /** Whether [IOpenLinkHost.requestSync] does anything for this device. */
    val supportsSync: Boolean = false,
    /** Whether a recording session can run on this device. */
    val supportsRecording: Boolean = false,
    /** The live sample types this device can produce. */
    val liveSampleTypes: Set<OpenLinkSampleType> = emptySet(),
    /** What the device can do when it records a workout of its own (version 2); empty: it cannot. */
    val recordingFeatures: Set<OpenLinkRecordingFeature> = emptySet(),
    /** The Health Connect exercise types the device can record itself (version 2). */
    val exerciseTypes: Set<Int> = emptySet(),
) : Parcelable {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_ID, id)
        putString(KEY_NAME, displayName)
        putString(KEY_KIND, kind.name)
        putString(KEY_INTEGRATION, integration.name)
        putString(KEY_CONNECTION, connection.name)
        putNullableInt(KEY_BATTERY, batteryPercent)
        putNullableLong(KEY_BATTERY_AT, batteryUpdatedAtMillis)
        putNullableLong(KEY_LAST_SYNC, lastSyncedAtMillis)
        putBoolean(KEY_SYNCING, syncing)
        putBoolean(KEY_SUPPORTS_SYNC, supportsSync)
        putBoolean(KEY_SUPPORTS_RECORDING, supportsRecording)
        putStringArrayList(KEY_LIVE_TYPES, ArrayList(liveSampleTypes.map { it.name }))
        putStringArrayList(KEY_RECORDING_FEATURES, ArrayList(recordingFeatures.map { it.name }))
        putIntegerArrayList(KEY_EXERCISE_TYPES, ArrayList(exerciseTypes))
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeFields(toBundle())

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NAME = "displayName"
        private const val KEY_KIND = "kind"
        private const val KEY_INTEGRATION = "integration"
        private const val KEY_CONNECTION = "connection"
        private const val KEY_BATTERY = "batteryPercent"
        private const val KEY_BATTERY_AT = "batteryUpdatedAtMillis"
        private const val KEY_LAST_SYNC = "lastSyncedAtMillis"
        private const val KEY_SYNCING = "syncing"
        private const val KEY_SUPPORTS_SYNC = "supportsSync"
        private const val KEY_SUPPORTS_RECORDING = "supportsRecording"
        private const val KEY_LIVE_TYPES = "liveSampleTypes"
        private const val KEY_RECORDING_FEATURES = "recordingFeatures"
        private const val KEY_EXERCISE_TYPES = "exerciseTypes"

        /** Null when the bundle lacks what every device must have: an id, a name, a kind and an integration. */
        fun fromBundleOrNull(bundle: Bundle): OpenLinkDevice? {
            val id = bundle.getString(KEY_ID) ?: return null
            val name = bundle.getString(KEY_NAME) ?: return null
            val kind = bundle.getEnumOrNull<OpenLinkDeviceKind>(KEY_KIND) ?: return null
            val integration = bundle.getEnumOrNull<OpenLinkIntegration>(KEY_INTEGRATION) ?: return null
            return OpenLinkDevice(
                id = id,
                displayName = name,
                kind = kind,
                integration = integration,
                connection = bundle.getEnumOrNull<OpenLinkConnection>(KEY_CONNECTION) ?: OpenLinkConnection.UNKNOWN,
                batteryPercent = bundle.getNullableInt(KEY_BATTERY),
                batteryUpdatedAtMillis = bundle.getNullableLong(KEY_BATTERY_AT),
                lastSyncedAtMillis = bundle.getNullableLong(KEY_LAST_SYNC),
                syncing = bundle.getBoolean(KEY_SYNCING, false),
                supportsSync = bundle.getBoolean(KEY_SUPPORTS_SYNC, false),
                supportsRecording = bundle.getBoolean(KEY_SUPPORTS_RECORDING, false),
                liveSampleTypes = bundle.getEnumSet(KEY_LIVE_TYPES),
                recordingFeatures = bundle.getEnumSet(KEY_RECORDING_FEATURES),
                exerciseTypes = bundle.getIntegerArrayList(KEY_EXERCISE_TYPES).orEmpty().toSet(),
            )
        }

        @JvmField
        val CREATOR: Parcelable.Creator<OpenLinkDevice> = object : Parcelable.Creator<OpenLinkDevice> {
            // A device an older or newer peer wrote without the required fields reads as a
            // placeholder, never as a crash; the receiver drops placeholders.
            override fun createFromParcel(source: Parcel): OpenLinkDevice =
                fromBundleOrNull(source.readFields()) ?: PLACEHOLDER

            override fun newArray(size: Int): Array<OpenLinkDevice?> = arrayOfNulls(size)
        }

        val PLACEHOLDER = OpenLinkDevice(
            id = "",
            displayName = "",
            kind = OpenLinkDeviceKind.WATCH,
            integration = OpenLinkIntegration.GARMIN,
        )
    }
}
