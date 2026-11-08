package tech.mmarca.openlink

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * The body profile. The last writer wins; [updatedAtMillis] lets a side ignore an older
 * copy. An empty profile (every field null, updatedAt 0) is what a host returns when it
 * has none or the caller lacks the capability.
 */
data class OpenLinkProfile(
    val birthYear: Int? = null,
    val sex: OpenLinkSex? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val restingHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val updatedAtMillis: Long = 0L,
) : Parcelable {
    val isEmpty: Boolean
        get() = birthYear == null && sex == null && heightCm == null && weightKg == null &&
            restingHeartRateBpm == null && maxHeartRateBpm == null

    fun toBundle(): Bundle = Bundle().apply {
        putNullableInt(KEY_BIRTH_YEAR, birthYear)
        if (sex != null) putString(KEY_SEX, sex.name)
        putNullableDouble(KEY_HEIGHT_CM, heightCm)
        putNullableDouble(KEY_WEIGHT_KG, weightKg)
        putNullableInt(KEY_RESTING_HR, restingHeartRateBpm)
        putNullableInt(KEY_MAX_HR, maxHeartRateBpm)
        putLong(KEY_UPDATED_AT, updatedAtMillis)
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeFields(toBundle())

    companion object {
        private const val KEY_BIRTH_YEAR = "birthYear"
        private const val KEY_SEX = "sex"
        private const val KEY_HEIGHT_CM = "heightCm"
        private const val KEY_WEIGHT_KG = "weightKg"
        private const val KEY_RESTING_HR = "restingHeartRateBpm"
        private const val KEY_MAX_HR = "maxHeartRateBpm"
        private const val KEY_UPDATED_AT = "updatedAtMillis"

        val EMPTY = OpenLinkProfile()

        fun fromBundle(bundle: Bundle): OpenLinkProfile = OpenLinkProfile(
            birthYear = bundle.getNullableInt(KEY_BIRTH_YEAR),
            sex = bundle.getEnumOrNull<OpenLinkSex>(KEY_SEX),
            heightCm = bundle.getNullableDouble(KEY_HEIGHT_CM),
            weightKg = bundle.getNullableDouble(KEY_WEIGHT_KG),
            restingHeartRateBpm = bundle.getNullableInt(KEY_RESTING_HR),
            maxHeartRateBpm = bundle.getNullableInt(KEY_MAX_HR),
            updatedAtMillis = bundle.getLong(KEY_UPDATED_AT, 0L),
        )

        @JvmField
        val CREATOR: Parcelable.Creator<OpenLinkProfile> = parcelableCreator(::fromBundle) { arrayOfNulls(it) }
    }
}
