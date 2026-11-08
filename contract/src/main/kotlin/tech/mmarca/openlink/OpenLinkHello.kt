package tech.mmarca.openlink

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/** What a client says on its first call: which protocol it speaks and what it wants. */
data class OpenLinkHello(
    val protocolVersion: Int = OpenLink.PROTOCOL_VERSION,
    /** The client app's own version name, for the host's connected-apps screen. */
    val appVersion: String = "",
    val requestedCapabilities: Set<String> = emptySet(),
) : Parcelable {
    fun toBundle(): Bundle = Bundle().apply {
        putInt(KEY_PROTOCOL, protocolVersion)
        putString(KEY_APP_VERSION, appVersion)
        putStringArrayList(KEY_CAPABILITIES, ArrayList(requestedCapabilities))
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeFields(toBundle())

    companion object {
        private const val KEY_PROTOCOL = "protocolVersion"
        private const val KEY_APP_VERSION = "appVersion"
        private const val KEY_CAPABILITIES = "requestedCapabilities"

        fun fromBundle(bundle: Bundle): OpenLinkHello = OpenLinkHello(
            protocolVersion = bundle.getInt(KEY_PROTOCOL, 0),
            appVersion = bundle.getString(KEY_APP_VERSION) ?: "",
            requestedCapabilities = bundle.getStringArrayList(KEY_CAPABILITIES).orEmpty().toSet(),
        )

        @JvmField
        val CREATOR: Parcelable.Creator<OpenLinkHello> = parcelableCreator(::fromBundle) { arrayOfNulls(it) }
    }
}
