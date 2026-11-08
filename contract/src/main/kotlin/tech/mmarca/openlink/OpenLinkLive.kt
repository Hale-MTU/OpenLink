package tech.mmarca.openlink

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/** Asks for live samples under a session id, from one device or from every device that has them. */
data class OpenLinkLiveSubscription(
    val sessionId: String,
    val types: Set<OpenLinkSampleType> = setOf(OpenLinkSampleType.HEART_RATE),
    val deviceId: String? = null,
) : Parcelable {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_SESSION, sessionId)
        putStringArrayList(KEY_TYPES, ArrayList(types.map { it.name }))
        if (deviceId != null) putString(KEY_DEVICE, deviceId)
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeFields(toBundle())

    companion object {
        private const val KEY_SESSION = "sessionId"
        private const val KEY_TYPES = "types"
        private const val KEY_DEVICE = "deviceId"

        fun fromBundle(bundle: Bundle): OpenLinkLiveSubscription = OpenLinkLiveSubscription(
            sessionId = bundle.getString(KEY_SESSION) ?: "",
            types = bundle.getEnumSet(KEY_TYPES),
            deviceId = bundle.getString(KEY_DEVICE),
        )

        @JvmField
        val CREATOR: Parcelable.Creator<OpenLinkLiveSubscription> = parcelableCreator(::fromBundle) { arrayOfNulls(it) }
    }
}

/** One reading. The host stamps it; the client takes [atMillis] as the sample's time. */
data class OpenLinkSample(
    val type: OpenLinkSampleType,
    val atMillis: Long,
    val value: Double,
)

/**
 * Samples for one session from one device, batched by the host at most once a second.
 * Samples travel as three parallel arrays, the cheapest shape a parcel has for them.
 */
data class OpenLinkLiveBatch(
    val sessionId: String,
    val deviceId: String,
    val samples: List<OpenLinkSample> = emptyList(),
) : Parcelable {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_SESSION, sessionId)
        putString(KEY_DEVICE, deviceId)
        putStringArrayList(KEY_TYPES, ArrayList(samples.map { it.type.name }))
        putLongArray(KEY_TIMES, LongArray(samples.size) { samples[it].atMillis })
        putDoubleArray(KEY_VALUES, DoubleArray(samples.size) { samples[it].value })
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeFields(toBundle())

    companion object {
        private const val KEY_SESSION = "sessionId"
        private const val KEY_DEVICE = "deviceId"
        private const val KEY_TYPES = "types"
        private const val KEY_TIMES = "atMillis"
        private const val KEY_VALUES = "values"

        fun fromBundle(bundle: Bundle): OpenLinkLiveBatch {
            val types = bundle.getStringArrayList(KEY_TYPES).orEmpty()
            val times = bundle.getLongArray(KEY_TIMES) ?: LongArray(0)
            val values = bundle.getDoubleArray(KEY_VALUES) ?: DoubleArray(0)
            val count = minOf(types.size, times.size, values.size)
            val samples = (0 until count).mapNotNull { i ->
                // A type this side does not know is dropped, not a crash.
                val type = OpenLinkSampleType.entries.firstOrNull { it.name == types[i] } ?: return@mapNotNull null
                OpenLinkSample(type, times[i], values[i])
            }
            return OpenLinkLiveBatch(
                sessionId = bundle.getString(KEY_SESSION) ?: "",
                deviceId = bundle.getString(KEY_DEVICE) ?: "",
                samples = samples,
            )
        }

        @JvmField
        val CREATOR: Parcelable.Creator<OpenLinkLiveBatch> = parcelableCreator(::fromBundle) { arrayOfNulls(it) }
    }
}
