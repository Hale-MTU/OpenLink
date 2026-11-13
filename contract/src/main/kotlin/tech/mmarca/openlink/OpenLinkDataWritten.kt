package tech.mmarca.openlink

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * The host wrote new data to Health Connect (version 2): which record types, over which time.
 * It carries no value, so a client knows when to read Health Connect again instead of polling.
 * [recordTypes] are Health Connect record class names without "Record", e.g. "HeartRate",
 * "Steps", "ExerciseSession".
 */
data class OpenLinkDataWritten(
    val recordTypes: Set<String>,
    val fromMillis: Long,
    val toMillis: Long,
    /** The device the data came from; null if several or none. */
    val deviceId: String? = null,
) : Parcelable {
    fun toBundle(): Bundle = Bundle().apply {
        putStringArrayList(KEY_TYPES, ArrayList(recordTypes))
        putLong(KEY_FROM, fromMillis)
        putLong(KEY_TO, toMillis)
        if (deviceId != null) putString(KEY_DEVICE, deviceId)
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeFields(toBundle())

    companion object {
        private const val KEY_TYPES = "recordTypes"
        private const val KEY_FROM = "fromMillis"
        private const val KEY_TO = "toMillis"
        private const val KEY_DEVICE = "deviceId"

        fun fromBundle(bundle: Bundle): OpenLinkDataWritten = OpenLinkDataWritten(
            recordTypes = bundle.getStringArrayList(KEY_TYPES).orEmpty().toSet(),
            fromMillis = bundle.getLong(KEY_FROM, 0L),
            toMillis = bundle.getLong(KEY_TO, 0L),
            deviceId = bundle.getString(KEY_DEVICE),
        )

        @JvmField
        val CREATOR: Parcelable.Creator<OpenLinkDataWritten> = parcelableCreator(::fromBundle) { arrayOfNulls(it) }
    }
}
