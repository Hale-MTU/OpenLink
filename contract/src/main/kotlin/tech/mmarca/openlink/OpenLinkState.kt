package tech.mmarca.openlink

import android.app.PendingIntent
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/** The host's answer to hello, and what it sends when the caller's trust changes. */
data class OpenLinkState(
    /** The version both sides speak from here: the lower of the two. */
    val protocolVersion: Int = OpenLink.PROTOCOL_VERSION,
    val trust: OpenLinkTrust = OpenLinkTrust.PENDING,
    /** Empty unless trusted. */
    val grantedCapabilities: Set<String> = emptySet(),
    /** Opens the host's consent screen for this caller; present while pending. */
    val consentIntent: PendingIntent? = null,
) : Parcelable {
    val isTrusted: Boolean get() = trust == OpenLinkTrust.TRUSTED

    fun has(capability: String): Boolean = isTrusted && capability in grantedCapabilities

    fun toBundle(): Bundle = Bundle().apply {
        putInt(KEY_PROTOCOL, protocolVersion)
        putString(KEY_TRUST, trust.name)
        putStringArrayList(KEY_CAPABILITIES, ArrayList(grantedCapabilities))
        if (consentIntent != null) putParcelable(KEY_CONSENT, consentIntent)
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeFields(toBundle())

    companion object {
        private const val KEY_PROTOCOL = "protocolVersion"
        private const val KEY_TRUST = "trust"
        private const val KEY_CAPABILITIES = "grantedCapabilities"
        private const val KEY_CONSENT = "consentIntent"

        @Suppress("DEPRECATION")
        fun fromBundle(bundle: Bundle): OpenLinkState = OpenLinkState(
            protocolVersion = bundle.getInt(KEY_PROTOCOL, 0),
            trust = bundle.getEnumOrNull<OpenLinkTrust>(KEY_TRUST) ?: OpenLinkTrust.PENDING,
            grantedCapabilities = bundle.getStringArrayList(KEY_CAPABILITIES).orEmpty().toSet(),
            consentIntent = bundle.getParcelable(KEY_CONSENT),
        )

        @JvmField
        val CREATOR: Parcelable.Creator<OpenLinkState> = parcelableCreator(::fromBundle) { arrayOfNulls(it) }
    }
}
