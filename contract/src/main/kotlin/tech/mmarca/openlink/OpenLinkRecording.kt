package tech.mmarca.openlink

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * Asks the host to run a recording session on a wearable. In version 1 neither watch can
 * be told to start a workout of its own; a session means the host keeps the wearable's live
 * readings flowing for its duration and reports them under the same session id to a live
 * subscription. [exerciseType] is the Health Connect exercise type, for a future watch that
 * can mark the workout.
 */
data class OpenLinkRecordingRequest(
    /** Chosen by the client, unique per recording; the live subscription names it too. */
    val sessionId: String,
    val exerciseType: Int = 0,
    val startedAtMillis: Long = 0L,
    /** Null: every device that supports recording. */
    val deviceId: String? = null,
) : Parcelable {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_SESSION, sessionId)
        putInt(KEY_EXERCISE_TYPE, exerciseType)
        putLong(KEY_STARTED_AT, startedAtMillis)
        if (deviceId != null) putString(KEY_DEVICE, deviceId)
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeFields(toBundle())

    companion object {
        private const val KEY_SESSION = "sessionId"
        private const val KEY_EXERCISE_TYPE = "exerciseType"
        private const val KEY_STARTED_AT = "startedAtMillis"
        private const val KEY_DEVICE = "deviceId"

        fun fromBundle(bundle: Bundle): OpenLinkRecordingRequest = OpenLinkRecordingRequest(
            sessionId = bundle.getString(KEY_SESSION) ?: "",
            exerciseType = bundle.getInt(KEY_EXERCISE_TYPE, 0),
            startedAtMillis = bundle.getLong(KEY_STARTED_AT, 0L),
            deviceId = bundle.getString(KEY_DEVICE),
        )

        @JvmField
        val CREATOR: Parcelable.Creator<OpenLinkRecordingRequest> = parcelableCreator(::fromBundle) { arrayOfNulls(it) }
    }
}

/** Where a recording session stands on one device. [reason] is a code, never a message. */
data class OpenLinkRecordingState(
    val sessionId: String,
    val deviceId: String,
    val phase: OpenLinkRecordingPhase,
    val reason: String? = null,
    val atMillis: Long = 0L,
) : Parcelable {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_SESSION, sessionId)
        putString(KEY_DEVICE, deviceId)
        putString(KEY_PHASE, phase.name)
        if (reason != null) putString(KEY_REASON, reason)
        putLong(KEY_AT, atMillis)
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeFields(toBundle())

    companion object {
        private const val KEY_SESSION = "sessionId"
        private const val KEY_DEVICE = "deviceId"
        private const val KEY_PHASE = "phase"
        private const val KEY_REASON = "reason"
        private const val KEY_AT = "atMillis"

        /** The reason codes version 1 hosts send. */
        const val REASON_NO_DEVICE = "no_device"
        const val REASON_NOT_CONNECTED = "not_connected"
        const val REASON_UNSUPPORTED = "unsupported"
        const val REASON_STOPPED_BY_CLIENT = "stopped_by_client"
        const val REASON_HOST_STOPPED = "host_stopped"

        fun fromBundle(bundle: Bundle): OpenLinkRecordingState = OpenLinkRecordingState(
            sessionId = bundle.getString(KEY_SESSION) ?: "",
            deviceId = bundle.getString(KEY_DEVICE) ?: "",
            phase = bundle.getEnumOrNull<OpenLinkRecordingPhase>(KEY_PHASE) ?: OpenLinkRecordingPhase.FAILED,
            reason = bundle.getString(KEY_REASON),
            atMillis = bundle.getLong(KEY_AT, 0L),
        )

        @JvmField
        val CREATOR: Parcelable.Creator<OpenLinkRecordingState> = parcelableCreator(::fromBundle) { arrayOfNulls(it) }
    }
}
