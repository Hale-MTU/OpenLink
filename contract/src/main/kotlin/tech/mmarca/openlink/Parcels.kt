package tech.mmarca.openlink

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * Every OpenLink parcelable is written as a Bundle: a reader ignores keys it does not know
 * and defaults the ones it does not find, so a side one version ahead of the other still
 * parses. The cost is a few bytes per field, which the channel's traffic never notices.
 */
internal fun Parcel.writeFields(fields: Bundle) {
    writeBundle(fields)
}

internal fun Parcel.readFields(): Bundle =
    readBundle(OpenLink::class.java.classLoader) ?: Bundle()

internal fun Bundle.putNullableInt(key: String, value: Int?) {
    if (value != null) putInt(key, value)
}

internal fun Bundle.putNullableLong(key: String, value: Long?) {
    if (value != null) putLong(key, value)
}

internal fun Bundle.putNullableDouble(key: String, value: Double?) {
    if (value != null) putDouble(key, value)
}

internal fun Bundle.getNullableInt(key: String): Int? = if (containsKey(key)) getInt(key) else null

internal fun Bundle.getNullableLong(key: String): Long? = if (containsKey(key)) getLong(key) else null

internal fun Bundle.getNullableDouble(key: String): Double? = if (containsKey(key)) getDouble(key) else null

internal inline fun <reified E : Enum<E>> Bundle.getEnumOrNull(key: String): E? =
    getString(key)?.let { name -> enumValues<E>().firstOrNull { it.name == name } }

internal inline fun <reified E : Enum<E>> Bundle.getEnumSet(key: String): Set<E> =
    getStringArrayList(key).orEmpty().mapNotNull { name -> enumValues<E>().firstOrNull { it.name == name } }.toSet()

internal fun <T : Parcelable> parcelableCreator(read: (Bundle) -> T, newArray: (Int) -> Array<T?>): Parcelable.Creator<T> =
    object : Parcelable.Creator<T> {
        override fun createFromParcel(source: Parcel): T = read(source.readFields())
        override fun newArray(size: Int): Array<T?> = newArray(size)
    }
