package tech.mmarca.openlink

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * Asks the host to run a recording session on a wearable. In [OpenLinkRecordingMode.STREAM]
 * (version 1, the default) the host keeps the wearable's live readings flowing for the
 * session's duration and reports them under the same session id to a live subscription. In
 * [OpenLinkRecordingMode.RECORD] (version 2) the wearable records the workout itself and the
 * host saves it to Health Connect; [exerciseType] is the Health Connect exercise type it
 * records. A repeated request for the same session id changes nothing, so a client may send
 * it again to take a session back after it restarted.
 */
data class OpenLinkRecordingRequest(
    /** Chosen by the client, unique per recording; the live subscription names it too. */
    val sessionId: String,
    val exerciseType: Int = 0,
    val startedAtMillis: Long = 0L,
    /** Null: every device that supports recording. Required in [OpenLinkRecordingMode.RECORD]. */
    val deviceId: String? = null,
    val mode: OpenLinkRecordingMode = OpenLinkRecordingMode.STREAM,
) : Parcelable {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_SESSION, sessionId)
        putInt(KEY_EXERCISE_TYPE, exerciseType)
        putLong(KEY_STARTED_AT, startedAtMillis)
        if (deviceId != null) putString(KEY_DEVICE, deviceId)
        putString(KEY_MODE, mode.name)
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeFields(toBundle())

    companion object {
        private const val KEY_SESSION = "sessionId"
        private const val KEY_EXERCISE_TYPE = "exerciseType"
        private const val KEY_STARTED_AT = "startedAtMillis"
        private const val KEY_DEVICE = "deviceId"
        private const val KEY_MODE = "mode"

        fun fromBundle(bundle: Bundle): OpenLinkRecordingRequest = OpenLinkRecordingRequest(
            sessionId = bundle.getString(KEY_SESSION) ?: "",
            exerciseType = bundle.getInt(KEY_EXERCISE_TYPE, 0),
            startedAtMillis = bundle.getLong(KEY_STARTED_AT, 0L),
            deviceId = bundle.getString(KEY_DEVICE),
            mode = bundle.getEnumOrNull<OpenLinkRecordingMode>(KEY_MODE) ?: OpenLinkRecordingMode.STREAM,
        )

        @JvmField
        val CREATOR: Parcelable.Creator<OpenLinkRecordingRequest> = parcelableCreator(::fromBundle) { arrayOfNulls(it) }
    }
}

/**
 * What a recorded workout gives beyond its Health Connect session (version 2): only figures
 * Health Connect has no type for. Pauses go to Health Connect as pause segments, and splits as
 * laps, so they are not here. Every field is optional: a device fills what it measured.
 */
data class OpenLinkWorkoutSummary(
    /** Seconds spent in each heart-rate zone, zone 1 first. */
    val zoneSeconds: List<Int> = emptyList(),
    /** The lowest heart rate of each zone, zone 1 first, as the host counted them. */
    val zoneLowerBoundsBpm: List<Int> = emptyList(),
    /** Banister's training impulse over the workout. */
    val trimp: Double? = null,
    /** How far the heart rate fell in the first and second minute after the stop. */
    val heartRateRecovery1MinBpm: Int? = null,
    val heartRateRecovery2MinBpm: Int? = null,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putIntegerArrayList(KEY_ZONE_SECONDS, ArrayList(zoneSeconds))
        putIntegerArrayList(KEY_ZONE_BOUNDS, ArrayList(zoneLowerBoundsBpm))
        putNullableDouble(KEY_TRIMP, trimp)
        putNullableInt(KEY_HRR_1, heartRateRecovery1MinBpm)
        putNullableInt(KEY_HRR_2, heartRateRecovery2MinBpm)
    }

    companion object {
        private const val KEY_ZONE_SECONDS = "zoneSeconds"
        private const val KEY_ZONE_BOUNDS = "zoneLowerBoundsBpm"
        private const val KEY_TRIMP = "trimp"
        private const val KEY_HRR_1 = "heartRateRecovery1MinBpm"
        private const val KEY_HRR_2 = "heartRateRecovery2MinBpm"

        fun fromBundle(bundle: Bundle): OpenLinkWorkoutSummary = OpenLinkWorkoutSummary(
            zoneSeconds = bundle.getIntegerArrayList(KEY_ZONE_SECONDS).orEmpty(),
            zoneLowerBoundsBpm = bundle.getIntegerArrayList(KEY_ZONE_BOUNDS).orEmpty(),
            trimp = bundle.getNullableDouble(KEY_TRIMP),
            heartRateRecovery1MinBpm = bundle.getNullableInt(KEY_HRR_1),
            heartRateRecovery2MinBpm = bundle.getNullableInt(KEY_HRR_2),
        )
    }
}

/**
 * Where a recording session stands on one device. [reason] is a code, never a message.
 * [summary] comes with `STOPPED` of a recorded workout (version 2); the host may report
 * `STOPPED` again with a fuller one, as the heart-rate recovery is known two minutes later.
 */
data class OpenLinkRecordingState(
    val sessionId: String,
    val deviceId: String,
    val phase: OpenLinkRecordingPhase,
    val reason: String? = null,
    val atMillis: Long = 0L,
    val summary: OpenLinkWorkoutSummary? = null,
) : Parcelable {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_SESSION, sessionId)
        putString(KEY_DEVICE, deviceId)
        putString(KEY_PHASE, phase.name)
        if (reason != null) putString(KEY_REASON, reason)
        putLong(KEY_AT, atMillis)
        if (summary != null) putBundle(KEY_SUMMARY, summary.toBundle())
    }

    /**
     * This state as a peer that negotiated [version] may read it: a version 1 client reads a
     * phase it does not know as a failure, so to it a pause is still a recording.
     */
    fun forVersion(version: Int): OpenLinkRecordingState =
        if (phase == OpenLinkRecordingPhase.PAUSED && version < 2) copy(phase = OpenLinkRecordingPhase.RECORDING) else this

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeFields(toBundle())

    companion object {
        private const val KEY_SESSION = "sessionId"
        private const val KEY_DEVICE = "deviceId"
        private const val KEY_PHASE = "phase"
        private const val KEY_REASON = "reason"
        private const val KEY_AT = "atMillis"
        private const val KEY_SUMMARY = "summary"

        /** The reason codes hosts send; the last three are version 2. */
        const val REASON_NO_DEVICE = "no_device"
        const val REASON_NOT_CONNECTED = "not_connected"
        const val REASON_UNSUPPORTED = "unsupported"
        const val REASON_STOPPED_BY_CLIENT = "stopped_by_client"
        const val REASON_HOST_STOPPED = "host_stopped"
        const val REASON_STOPPED_ON_DEVICE = "stopped_on_device"
        const val REASON_BUSY = "busy"
        const val REASON_NO_ASSOCIATION = "no_association"
        const val REASON_NO_GPS = "no_gps"

        fun fromBundle(bundle: Bundle): OpenLinkRecordingState = OpenLinkRecordingState(
            sessionId = bundle.getString(KEY_SESSION) ?: "",
            deviceId = bundle.getString(KEY_DEVICE) ?: "",
            phase = bundle.getEnumOrNull<OpenLinkRecordingPhase>(KEY_PHASE) ?: OpenLinkRecordingPhase.FAILED,
            reason = bundle.getString(KEY_REASON),
            atMillis = bundle.getLong(KEY_AT, 0L),
            summary = bundle.getBundle(KEY_SUMMARY)?.let(OpenLinkWorkoutSummary::fromBundle),
        )

        @JvmField
        val CREATOR: Parcelable.Creator<OpenLinkRecordingState> = parcelableCreator(::fromBundle) { arrayOfNulls(it) }
    }
}
